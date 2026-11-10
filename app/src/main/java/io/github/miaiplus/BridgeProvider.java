package io.github.miaiplus;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Scoped IPC: small commands, ephemeral image pipes, and opt-in external model configuration. */
public final class BridgeProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".bridge");
    private static final Map<String, RunState> JOBS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor();
    private static volatile RunState active;
    // Reserved under JOBS before creating a probe display; start uses the same monitor.
    private static boolean backgroundProbeRunning;
    private static volatile String screenResult = "";
    private static String screenStopReason = "";
    // A task spans all native CONTINUE calls, including the idle model-planning gaps.
    private static String screenTaskId;
    private static boolean screenTaskActive;
    // Memory-only reachability facts for the diagnostic export. Updated on every host call, never logged per call.
    private static final java.util.concurrent.atomic.AtomicLong hostCalls = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong deniedCalls = new java.util.concurrent.atomic.AtomicLong();
    private static volatile long lastHostCallAt, lastFlagsAt, lastCatalogAt;
    private static volatile int lastHostPid;
    private static volatile String lastHostMethod = "";
    private static final java.util.Set<String> HOST_METHODS = java.util.Set.of("flags", "start", "poll", "cancel",
        "screen_ready", "screen_state", "stop_screen", "model_config", "set_reasoning", "conversation_settings",
        "hook_report", "hook_status", "model_exhausted", "model_finished", "foreground_consent", "action_confirmation",
        "phone_use_log_settings", "phone_use_log_append", "plugin_catalog", "plugin_start", "plugin_poll",
        "plugin_cancel", "plugin_busy");
    private static final java.util.Set<String> STATUS_KEYS = java.util.Set.of("phase", "stage", "reason", "exception",
        "site", "causes", "causeSite", "binding", "hostVersionName", "hostVersionCode", "profile", "process",
        "moduleVersionName", "moduleVersionCode", "hooks", "unguarded", "optional", "frameworkName",
        "frameworkVersion", "frameworkVersionCode", "frameworkApi", "gate", "pid");
    private static final java.util.Set<String> STATUS_NUMBERS = java.util.Set.of("hostVersionCode", "moduleVersionCode",
        "hooks", "unguarded", "frameworkVersionCode", "frameworkApi", "pid");
    private static final String SYMBOL = "[A-Za-z_$][A-Za-z0-9_.$]*";
    @Override public boolean onCreate() {
        PhoneUseLog.init(getContext());
        // A new pid here after a task started means the module process was replaced and its tasks were lost.
        PhoneUseLog.event("module_process_start", "versionCode", BuildConfig.VERSION_CODE);
        return true;
    }
    private void enforceCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages == null || !Arrays.asList(packages).contains("com.miui.voiceassist")) {
            deniedCalls.incrementAndGet();
            throw new SecurityException("MiAIPlus bridge is private to XiaoAi");
        }
    }
    /** Whether XiaoAi has reached this process at all, and when; an idle bridge and a disabled module look alike otherwise. */
    static JSONObject bridgeSnapshot() throws org.json.JSONException {
        long now = System.currentTimeMillis();
        JSONObject bridge = new JSONObject().put("hostCalls", hostCalls.get()).put("deniedCalls", deniedCalls.get())
            .put("lastHostMethod", lastHostMethod).put("lastHostPid", lastHostPid);
        if (lastHostCallAt > 0) bridge.put("lastHostCallAgeMs", now - lastHostCallAt);
        if (lastFlagsAt > 0) bridge.put("lastFlagsAgeMs", now - lastFlagsAt);
        if (lastCatalogAt > 0) bridge.put("lastCatalogAgeMs", now - lastCatalogAt);
        synchronized (JOBS) {
            RunState current = active;
            bridge.put("jobs", JOBS.size()).put("activeRun", current != null)
                .put("screenTaskActive", screenTaskActive).put("probeRunning", backgroundProbeRunning);
        }
        return bridge.put("nativeSession", NativeSession.hasActiveSession())
            .put("nativePending", NativeSession.hasPendingOperation())
            .put("backgroundDisplayOpen", BackgroundDisplay.active() != null);
    }
    private static String methodName(String method) { return HOST_METHODS.contains(method) ? method : "other"; }
    private static Bundle rejected(Bundle b, String reason, String mode, String query, String message) {
        // The model is told why in words; the log needs the branch, without the goal or the session id.
        PhoneUseLog.event("start_rejected", "reason", reason, "mode", "chat".equals(mode) ? "chat" : "screen",
            "continuation", query != null && query.startsWith("MIAIPLUS:"),
            "activeRun", active != null, "nativeSession", NativeSession.hasActiveSession(),
            "nativePending", NativeSession.hasPendingOperation(),
            "displayOpen", BackgroundDisplay.active() != null);
        b.putString("error", message);
        return b;
    }
    private static String resultKind(String text) {
        if (text == null || text.isEmpty()) return "empty";
        for (String kind : new String[]{"CONTINUE", "STOP", "PAUSE"}) if (text.startsWith(kind)) return kind;
        return "other";
    }
    /** Kept both when receiving a status and when exporting an older persisted one. */
    static JSONObject sanitizeHookStatus(JSONObject received) throws org.json.JSONException {
        JSONObject kept = new JSONObject();
        for (String key : STATUS_KEYS) {
            Object value = received.opt(key);
            if (STATUS_NUMBERS.contains(key)) {
                // Framework API metadata is sent as strings by the host; normalize it to an integer.
                if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                    if (((Number)value).longValue() >= 0) kept.put(key, ((Number)value).longValue());
                } else if (key.startsWith("framework") && value instanceof String text && text.matches("[0-9]{1,10}")) {
                    kept.put(key, Long.parseLong(text));
                }
            } else if (value instanceof String text && text.length() <= 240) {
                String safe = statusText(key, text);
                if (safe != null) kept.put(key, safe);
            }
        }
        return kept;
    }
    private static String statusText(String key, String text) {
        boolean valid = switch (key) {
            case "phase" -> java.util.Set.of("ready", "unsupported", "rolled_back", "reload_refused", "reload_cleanup_failed").contains(text);
            case "stage" -> java.util.Set.of("host_version", "interaction_ui", "control_app", "tool_definitions", "prompt_builder",
                "conversation_limits", "conversation", "model", "probes", "diagnostics", "ready", "model.plugin_tools",
                "model.turn_lifecycle", "model.intent", "model.reasoning", "model.chat_routing", "model.voice_routing",
                "model.messages", "model.turn_stop", "model.transport", "model.memory").contains(text);
            case "reason" -> java.util.Set.of("hook_call_in_progress", "screen_task_running", "model_request_running", "plugin_call_running").contains(text);
            case "profile" -> java.util.Set.of("legacy", "modern", "unsupported").contains(text);
            case "gate" -> java.util.Set.of("unknown", "flags_true", "flags_false", "bridge_null").contains(text)
                || text.matches("bridge_exception:" + SYMBOL);
            case "process" -> text.matches("com\\.miui\\.voiceassist(?::[A-Za-z0-9_.]+)?");
            case "exception" -> text.matches(SYMBOL);
            case "causes" -> text.matches(SYMBOL + "(?:>" + SYMBOL + "){0,5}");
            case "site", "causeSite" -> text.isEmpty() || text.matches("[A-Za-z0-9_.$]+(?:\\.<(?:cl)?init>)?(?::[0-9]+)?(?:<[A-Za-z0-9_.$]+(?:\\.<(?:cl)?init>)?(?::[0-9]+)?){0,2}");
            case "binding" -> text.matches("(?:(?:Missing host (?:class|method|field)|Missing compatible host (?:method|constructor)|Ambiguous host (?:overload|constructor)): )?"
                + SYMBOL + "(?:\\((?:" + SYMBOL + "(?:\\[\\])*(?:, " + SYMBOL + "(?:\\[\\])*)*)?\\))?");
            case "optional" -> text.isEmpty() || text.matches("(?:error_handler|agent_error|agent_support)(?:,(?:error_handler|agent_error|agent_support)){0,2}");
            case "hostVersionName", "moduleVersionName" -> text.matches("[0-9]+(?:\\.[0-9]+){1,5}(?:-(?:preview|debug|alpha|beta|rc)(?:[0-9.]*)?)?");
            case "frameworkVersion" -> text.matches("[vV]?[0-9]+(?:[._-][0-9]+)*(?:-(?:release|preview|debug|alpha|beta|rc)[0-9.]*)?(?: \\([0-9]+\\))?");
            default -> false;
        };
        if (key.equals("frameworkName")) {
            String lower = text.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("lsposed")) return "LSPosed";
            if (lower.contains("vector")) return "Vector";
            if (lower.contains("xposed")) return "Xposed";
            return "other";
        }
        return valid ? text : null;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (method.equals("accessibility_health") || method.equals("accessibility_recover")) {
            int uid = Binder.getCallingUid();
            if (uid != 0 && uid != Process.myUid()) throw new SecurityException("Accessibility guard is module/root only");
            Bundle health = new Bundle();
            // Older resident guards see false and exit after an update. Never let them cancel work.
            health.putBoolean("guard_enabled", false);
            health.putBoolean("preflight_enabled", AccessibilityGuard.enabled(getContext()));
            health.putBoolean("unlocked", getContext().getSystemService(android.os.UserManager.class).isUserUnlocked());
            health.putBoolean("connected", ScreenService.isHealthy());
            RunState current = active;
            health.putBoolean("preparing", current != null && current.preparingScreen && !current.cancelled.get());
            health.putString("preflight_id", current != null && current.preparingScreen ? current.id : "");
            health.putBoolean("busy", NativeSession.hasPendingOperation() || (current != null && !current.preparingScreen));
            return health;
        }
        if (method.equals("background_display_status") || method.equals("background_display_probe")) {
            int uid = Binder.getCallingUid();
            if (uid != 0 && uid != Process.myUid()) throw new SecurityException("Background display control is module/root only");
            return backgroundDisplay(method.equals("background_display_probe"), arg, extras);
        }
        enforceCaller();
        if (Binder.getCallingUid() != Process.myUid()) {
            long now = System.currentTimeMillis();
            hostCalls.incrementAndGet(); lastHostCallAt = now; lastHostPid = Binder.getCallingPid();
            lastHostMethod = methodName(method);
            if (method.equals("flags")) lastFlagsAt = now;
            if (method.equals("plugin_catalog")) lastCatalogAt = now;
        }
        // Remote plugin jobs have their own bounded executor and never claim a screen task.
        if (method.startsWith("plugin_")) {
            int uid = Binder.getCallingUid();
            if (method.equals("plugin_catalog")) {
                try { return PluginRuntime.catalog(getContext()); }
                catch (RuntimeException failure) {
                    // The host only sees an unavailable catalog and withdraws every tool; the cause is here.
                    PhoneUseLog.failure("plugin_catalog", failure);
                    throw failure;
                }
            }
            return switch (method) {
                case "plugin_start" -> PluginRuntime.start(getContext(), uid, extras);
                case "plugin_poll" -> PluginRuntime.poll(getContext(), uid, arg);
                case "plugin_cancel" -> PluginRuntime.cancel(uid, arg);
                case "plugin_busy" -> PluginRuntime.busy(uid);
                default -> throw new SecurityException("Plugin configuration is module-local only");
            };
        }
        Bundle b = new Bundle();
        if (method.equals("phone_use_log_settings")) {
            b.putBoolean("enabled", PhoneUseLog.enabled());
            return b;
        }
        if (method.equals("phone_use_log_append")) {
            if (PhoneUseLog.enabled() && extras != null) {
                String line = extras.getString("record");
                if (line != null && line.length() <= DiagnosticLogStore.MAX_LINE_BYTES) {
                    try {
                        JSONObject record = new JSONObject(line);
                        // Never trust the sending process to label a record as module-side.
                        record.put("source", Binder.getCallingUid() == Process.myUid() ? "module" : "host");
                        DiagnosticLogStore.get(getContext()).append(record.toString());
                    } catch (org.json.JSONException ignored) { /* Invalid diagnostic data is discarded. */ }
                }
            }
            return b;
        }
        if (method.equals("hook_status")) {
            // Structured install state for the diagnostic export. Fixed keys and bounded scalars only, and a
            // separate slot for reload refusals so a busy old generation cannot overwrite the install report.
            String status = extras == null ? null : extras.getString("status");
            if (status != null && status.length() <= 4096) {
                try {
                    JSONObject kept = sanitizeHookStatus(new JSONObject(status));
                    boolean reload = "reload".equals(extras.getString("slot"));
                    Config.prefs(getContext()).edit()
                        .putString(reload ? "hook_reload" : "hook_status", kept.toString())
                        .putLong(reload ? "hook_reload_at" : "hook_status_at", System.currentTimeMillis()).apply();
                } catch (org.json.JSONException ignored) { /* Invalid diagnostic data is discarded. */ }
            }
            return b;
        }
        Config config = new Config(getContext());
        if (method.equals("hook_report")) {
            String report = extras == null ? "" : extras.getString("report", "");
            Config.prefs(getContext()).edit().putString("hook_report", report).putLong("hook_at", System.currentTimeMillis()).apply();
            return b;
        }
        if (method.equals("stop_screen")) { cancelActive("user_stop"); return b; }
        if (method.equals("model_exhausted") || method.equals("model_finished")) {
            synchronized (JOBS) {
                refreshScreenTask();
                // A late result from another host turn must never cancel a replacement screen task.
                boolean matches = arg != null && arg.equals(screenTaskId) && screenTaskActive;
                if (matches) {
                    screenStopReason = PhoneUseResult.stopped(method.equals("model_exhausted")
                        ? "小爱主模型已达到最大执行轮次，无法继续调用控屏工具"
                        : "小爱主模型已结束本轮，未提交控屏完成确认");
                    screenResult = screenStopReason;
                    cancelActive(method.equals("model_exhausted") ? "host_model_exhausted" : "host_turn_finished");
                    refreshScreenTask();
                }
                b.putBoolean("accepted", matches);
            }
            // A turn that ends without a matching task is normal; one that stops a task explains its ending.
            if (b.getBoolean("accepted")) PhoneUseLog.event("host_turn_stop", "signal", method);
            return b;
        }
        if (method.equals("foreground_consent") || method.equals("action_confirmation")) {
            boolean current;
            synchronized (JOBS) {
                current = screenTaskActive && java.util.Objects.equals(arg, screenTaskId)
                    && active != null && active.owner == Binder.getCallingUid() && !active.cancelled.get();
                b.putBoolean("accepted", current && (method.equals("action_confirmation")
                    ? ActionConfirmation.respond(arg, Binder.getCallingUid(), extras)
                    : ForegroundConsent.respond(arg, Binder.getCallingUid(), extras)));
            }
            PhoneUseLog.event("prompt_answer", "kind", method, "allow", extras != null && extras.getBoolean("allow"),
                "accepted", b.getBoolean("accepted"), "taskCurrent", current);
            return b;
        }
        if (method.equals("screen_state")) {
            synchronized (JOBS) {
                refreshScreenTask();
                boolean matches = arg == null || arg.equals(screenTaskId);
                b.putBoolean("active", matches && screenTaskActive);
                b.putBoolean("completed", matches && screenTaskId != null && !screenTaskActive);
                b.putString("screen_task", screenTaskId);
                b.putString("native_session", matches ? NativeSession.activeSessionId(Binder.getCallingUid()) : "");
                b.putString("result", matches ? screenResult : "控屏任务已更换");
                String progress = NativeSession.progress();
                RunState current = active;
                if (progress.isEmpty() && current != null && current.screen) progress = current.progress;
                b.putString("progress", matches ? TaskProgress.compact(progress) : "");
                if (matches && screenTaskActive) {
                    ForegroundConsent.describe(b, screenTaskId, Binder.getCallingUid());
                    ActionConfirmation.describe(b, screenTaskId, Binder.getCallingUid());
                }
            }
            return b;
        }
        if (method.equals("flags")) {
            b.putBoolean("enabled", config.enabled);
            b.putBoolean("nativePlanner", config.planner.equals("native"));
            b.putBoolean("accessibility", ScreenService.isHealthy());
            return b;
        }
        if (method.equals("conversation_settings")) {
            b.putInt("conversationTimeoutMinutes", config.conversationTimeoutMinutes);
            synchronized (JOBS) {
                refreshScreenTask();
                b.putBoolean("screenBusy", screenTaskActive || NativeSession.hasPendingOperation()
                    || (active != null && active.screen));
            }
            return b;
        }
        if (method.equals("set_reasoning")) {
            if (!config.globalModel || extras == null
                || !config.endpoint.equals(extras.getString("endpoint"))
                || !config.model.equals(extras.getString("model")) || !config.api.equals(extras.getString("api")))
                throw new IllegalStateException("模型配置已更改，请重新打开推理强度菜单");
            String effort = extras.getString("reasoning", "default");
            if (!ReasoningPolicy.choices(config.model, config.api).contains(effort))
                throw new IllegalArgumentException("当前模型不支持该推理强度");
            if (!Config.prefs(getContext()).edit().putString(
                ReasoningPolicy.preferenceKey(config.endpoint, config.model, config.api), effort).commit())
                throw new IllegalStateException("推理强度保存失败");
        }
        if (method.equals("model_config") || method.equals("set_reasoning")) {
            b.putBoolean("globalModel", config.globalModel);
            if (config.globalModel) {
                // Ephemeral IPC to the scoped XiaoAi UID; never write credentials into its files.
                b.putString("endpoint", config.endpoint); b.putString("apiKey", config.apiKey); b.putString("model", config.model);
                b.putString("api", config.api);
                b.putString("reasoning", method.equals("set_reasoning") ? extras.getString("reasoning", "default") : config.reasoning);
            }
            return b;
        }
        if (method.equals("start")) {
            String query = extras == null ? null : extras.getString("query");
            String mode = extras == null ? "screen" : extras.getString("mode", "screen");
            if (!config.enabled) return rejected(b, "module_disabled", mode, query, "MiAIPlus 未启用");
            final PhoneUseRouting.Request route;
            try { route = mode.equals("screen") ? PhoneUseRouting.decode(query, config.backgroundDisplay)
                : new PhoneUseRouting.Request(query, false, ""); }
            catch (Exception e) {
                PhoneUseLog.failure("start_route", e);
                return rejected(b, "route_invalid", mode, query, publicError(e));
            }
            if (mode.equals("screen")) {
                final boolean originalBackground;
                try {
                    originalBackground = config.planner.equals("native") && query != null && query.startsWith("MIAIPLUS:")
                        ? NativeSession.continuationBackground(query, Binder.getCallingUid(), route.sourceTool())
                        : route.sourceTool().isEmpty() ? route.background() : PhoneUseRouting.background(route.sourceTool());
                } catch (Exception invalid) {
                    PhoneUseLog.failure("start_continuation", invalid);
                    return rejected(b, "continuation_invalid", mode, query, PhoneUseResult.stopped(publicError(invalid)));
                }
                try {
                    String pluginId = originalBackground ? PluginSettings.BACKGROUND_ID : PluginSettings.FOREGROUND_ID;
                    if (!PluginSettings.load(getContext()).find(pluginId).enabled())
                        return rejected(b, originalBackground ? "background_plugin_disabled" : "foreground_plugin_disabled",
                            mode, query, PhoneUseResult.stopped("对应 Phone Use 插件已停用"));
                } catch (Exception unavailable) {
                    PhoneUseLog.failure("start_plugin_settings", unavailable);
                    return rejected(b, "plugin_settings_unavailable", mode, query, PhoneUseResult.stopped("插件配置不可用"));
                }
            }
            query = route.query();
            final String taskQuery = query;
            if (!mode.equals("screen") && !mode.equals("chat")) return rejected(b, "unknown_mode", mode, query, "未知模式");
            if (NativeSession.hasActiveSession() && (!mode.equals("screen") || !config.planner.equals("native")))
                return rejected(b, "native_session_active", mode, query, "已有小爱逐步控屏会话，请先停止");
            if (NativeSession.hasActiveSession() && (query == null || !query.startsWith("MIAIPLUS:")))
                return rejected(b, "session_active_new_goal", mode, query, "已有控屏会话，请先停止或继续当前会话");
            synchronized (JOBS) {
                if (backgroundProbeRunning) return rejected(b, "display_probe_running", mode, query, "后台显示检测进行中，请等待检测结束");
                if (active != null) return rejected(b, "run_active", mode, query, "已有任务执行中，请先停止");
                if (!NativeSession.hasActiveSession()
                    && (BackgroundDisplay.active() != null || NativeSession.hasPendingOperation()))
                    return rejected(b, "cleanup_pending", mode, query, "上一控屏任务仍在清理，请等待结束");
                // A stale continuation is a rejected call, not a new screen task. Reserving a task here
                // would reopen the focus island and hide/show the chat on every model retry.
                if (mode.equals("screen") && config.planner.equals("native")
                    && query != null && query.startsWith("MIAIPLUS:")) {
                    try { NativeSession.verifyContinuation(query, Binder.getCallingUid(), route.sourceTool()); }
                    catch (Exception invalid) {
                        PhoneUseLog.failure("start_continuation", invalid);
                        return rejected(b, "continuation_stale", mode, query, PhoneUseResult.stopped(publicError(invalid)));
                    }
                }
                JOBS.entrySet().removeIf(e -> !e.getValue().status.equals("running")
                    && android.os.SystemClock.elapsedRealtime() > e.getValue().deadline + 300_000);
                if (JOBS.size() >= 16) JOBS.values().stream().filter(r -> !r.status.equals("running"))
                    .min(java.util.Comparator.comparingLong(r -> r.deadline)).ifPresent(r -> JOBS.remove(r.id));
                RunState run = new RunState(UUID.randomUUID().toString(), Binder.getCallingUid());
                run.screen = mode.equals("screen");
                run.preparingScreen = run.screen && query != null && !query.startsWith("MIAIPLUS:");
                if (run.screen) run.progress = run.preparingScreen ? "正在检查无障碍连接" : "正在判断下一步";
                if (run.screen && !NativeSession.hasActiveSession()) {
                    screenTaskId = run.id; screenTaskActive = true; screenResult = ""; screenStopReason = "";
                }
                if (!run.screen || extras == null || !extras.getBoolean("screen_ui")) run.screenReady.countDown();
                active = run; JOBS.put(run.id, run);
                var timeout = WATCHDOG.schedule(() -> run.cancel("step_watchdog"), RunState.STEP_BUDGET_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                final boolean continuation = query != null && query.startsWith("MIAIPLUS:");
                final long accepted = android.os.SystemClock.elapsedRealtime();
                WORKER.execute(() -> {
                    // Everything before the engine used to be invisible: a missing interface, a failed
                    // accessibility recovery and a stop during either all ended as the same sentence.
                    String phase = "ui_wait";
                    try (PhoneUseLog.Scope ignored = PhoneUseLog.scope(run.id, 0)) {
                        try {
                            if (!run.screenReady.await(6, java.util.concurrent.TimeUnit.SECONDS))
                                throw new IllegalStateException("小爱交互界面未就绪，已停止");
                            run.check();
                            if (run.preparingScreen) {
                                phase = "accessibility";
                                AccessibilityGuard.prepare(getContext(), run);
                                run.preparingScreen = false;
                                run.check();
                            }
                            phase = "engine";
                            String text = AgentEngine.run(config, mode, taskQuery, run, route.background(), route.sourceTool());
                            run.result = text.length() > 24000 ? text.substring(0, 24000) + "\n[结果过长已截断]" : text;
                            phase = "finish";
                            run.check(); run.status = "done";
                        }
                        catch (Exception e) {
                            // The engine records its own failures; the phases around it had no record at all.
                            if (!phase.equals("engine")) PhoneUseLog.failure("run_" + phase, e);
                            // Keep exception details out of IPC to avoid echoing API bodies or typed content.
                            String reason = run.cancelled.get() ? "任务已停止或超时" : publicError(e);
                            run.result = run.screen ? PhoneUseResult.stopped(reason) : reason;
                            run.status = "error";
                        } finally {
                            PhoneUseLog.event("run_end", "status", run.status, "phase", phase,
                                "mode", run.screen ? "screen" : "chat", "continuation", continuation,
                                "cancelled", run.cancelled.get(), "cancelSource", run.cancelSource(),
                                "resultKind", resultKind(run.result),
                                "durationMs", android.os.SystemClock.elapsedRealtime() - accepted);
                        }
                    } finally {
                        timeout.cancel(false);
                        synchronized (JOBS) {
                            if (run.screen) screenResult = screenStopReason.isEmpty() ? run.result : screenStopReason;
                            active = null;
                            refreshScreenTask();
                        }
                    }
                });
                b.putString("id", run.id);
                if (run.screen) b.putString("screen_task", screenTaskId);
                return b;
            }
        }
        RunState run = JOBS.get(arg);
        if (run == null || (run.owner != Binder.getCallingUid() && Binder.getCallingUid() != Process.myUid())) {
            // The host polls a task this process no longer knows: usually the module process was replaced.
            PhoneUseLog.event("bridge_task_missing", "method", methodName(method), "known", run != null,
                "jobs", JOBS.size(), "processAgeMs", android.os.SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime());
            b.putString("error", "任务不存在或不可访问"); return b;
        }
        if (method.equals("screen_ready")) run.screenReady.countDown();
        else if (method.equals("cancel")) run.cancel("host_cancel");
        else if (!method.equals("poll")) throw new IllegalArgumentException("Unknown bridge method");
        b.putString("status", run.status); b.putString("result", run.result); b.putString("progress", run.progress);
        return b;
    }
    /**
     * Reports whether the root-backed background display is available, and optionally runs the full probe.
     *
     * <p>Restricted to the module and root, because the probe creates a real display and the answer must not be
     * available to the host app: XiaoAi reaches the rest of this provider as a scoped caller, and the background
     * backend deliberately starts from the module uid, which is the one the root manager allowlists.
     *
     * <p>The probe is opt-in through a separate method rather than folded into the status call, because it is slow
     * and briefly creates a screen; a status refresh that runs every second must not do that.
     */
    private Bundle backgroundDisplay(boolean probe, String component, Bundle extras) {
        Bundle b = new Bundle();
        b.putBoolean("configured", new Config(getContext()).backgroundDisplay);
        BackgroundDisplay display = BackgroundDisplay.active();
        b.putBoolean("active", display != null && display.isOpen());
        if (probe) {
            synchronized (JOBS) {
                // A native session remains busy between tool calls while its model plans the next action.
                // Preparing tasks and chat runs also reserve active before their worker starts.
                // Read display ownership before NativeSession's closing flag: close publishes closing before
                // withdrawing the display, so the two reads cannot both miss an in-progress helper teardown.
                if (backgroundProbeRunning || active != null || BackgroundDisplay.active() != null
                    || NativeSession.hasPendingOperation()) {
                    b.putString("error", "已有任务或后台显示检测进行中，请先停止或等待结束");
                    return b;
                }
                backgroundProbeRunning = true;
            }
        }
        try {
            // Probing creates a screen, so the timer-driven status asks only who the helper is.
            JSONObject result = probe
                ? BackgroundDisplay.capability(getContext(), component, null)
                : BackgroundDisplay.availability(getContext(), null);
            b.putString("uid", result.optString("uid"));
            b.putString("api", result.optString("api"));
            b.putString("build", result.optString("build"));
            b.putString("fingerprint", result.optString("fingerprint"));
            b.putBoolean("deviceLocked", "true".equals(result.optString("keyguardLocked")));
            if (probe) {
                b.putString("items", result.optString("items"));
                b.putString("supported", result.optString("supported"));
                b.putString("tested", result.optString("tested"));
                b.putString("total", result.optString("total"));
                b.putString("romFlags", result.optString("romFlags"));
            }
            b.putBoolean("root", "0".equals(result.optString("uid")));
        } catch (Exception failure) {
            // Report the reason instead of a bare false, so a missing root grant is distinguishable from a ROM
            // that simply cannot host the display.
            b.putBoolean("root", false);
            b.putString("error", publicError(failure));
        } finally {
            if (probe) {
                synchronized (JOBS) { backgroundProbeRunning = false; }
            }
        }
        return b;
    }
    private static String publicError(Exception e) {
        Throwable cause = e;
        while ((cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof IllegalStateException || cause instanceof IllegalArgumentException || cause instanceof java.util.concurrent.CancellationException)
            return cause.getMessage() == null ? "任务失败" : cause.getMessage();
        if (cause instanceof java.net.SocketTimeoutException) return "模型请求超时，任务已停止";
        if (cause instanceof org.json.JSONException) return "模型响应不符合动作协议";
        return "任务失败（" + cause.getClass().getSimpleName() + "），未切回原控屏以免重复操作";
    }
    public static void cancelActive() { cancelActive("module_request"); }
    public static void cancelActive(String source) {
        RunState run = active;
        if (run != null) run.cancel(source);
        NativeSession.cancel(source);
    }
    static String screenTaskFor(int owner) {
        synchronized (JOBS) {
            if (active == null || !active.screen || active.owner != owner || !screenTaskActive
                || active.cancelled.get() || screenTaskId == null)
                throw new IllegalStateException("PAUSE：控屏任务已更换，前台应用使用请求已取消");
            return screenTaskId;
        }
    }
    static void onScreenDisconnected() {
        RunState run = active;
        if (run != null && !run.preparingScreen) run.cancel("accessibility_disconnected");
        NativeSession.cancel("accessibility_disconnected");
    }
    private static void refreshScreenTask() {
        // Called under JOBS. A done tool reply is not a terminal task signal;
        // wait for both execution to unwind and the native session to close.
        if (screenTaskActive && (active == null || !active.screen) && !NativeSession.hasPendingOperation()) {
            screenTaskActive = false;
            if (screenResult.startsWith("CONTINUE")) screenResult = "任务已停止或超时";
        }
    }
    @Override public android.os.ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        enforceCaller();
        if (!mode.equals("r") || uri.getPathSegments().size() != 2 || !uri.getPathSegments().get(0).equals("frame"))
            throw new java.io.FileNotFoundException("Unknown frame");
        byte[] bytes = FrameStore.take(uri.getLastPathSegment(), Binder.getCallingUid());
        try {
            var pipe = android.os.ParcelFileDescriptor.createPipe();
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try (var out = new android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { out.write(bytes); }
                catch (java.io.IOException ignored) { }
            });
            return pipe[0];
        } catch (java.io.IOException e) { throw new java.io.FileNotFoundException("Frame pipe unavailable"); }
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String order) { throw new SecurityException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new SecurityException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new SecurityException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new SecurityException(); }
}
