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
    public static final Uri URI = Uri.parse("content://io.github.miaiplus.bridge");
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
    @Override public boolean onCreate() { PhoneUseLog.init(getContext()); return true; }
    private void enforceCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages == null || !Arrays.asList(packages).contains("com.miui.voiceassist"))
            throw new SecurityException("MiAIPlus bridge is private to XiaoAi");
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
        Config config = new Config(getContext());
        if (method.equals("hook_report")) {
            String report = extras == null ? "" : extras.getString("report", "");
            Config.prefs(getContext()).edit().putString("hook_report", report).putLong("hook_at", System.currentTimeMillis()).apply();
            return b;
        }
        if (method.equals("stop_screen")) { cancelActive(); return b; }
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
                    cancelActive();
                    refreshScreenTask();
                }
                b.putBoolean("accepted", matches);
            }
            return b;
        }
        if (method.equals("foreground_consent") || method.equals("action_confirmation")) {
            synchronized (JOBS) {
                boolean current = screenTaskActive && java.util.Objects.equals(arg, screenTaskId)
                    && active != null && active.owner == Binder.getCallingUid() && !active.cancelled.get();
                b.putBoolean("accepted", current && (method.equals("action_confirmation")
                    ? ActionConfirmation.respond(arg, Binder.getCallingUid(), extras)
                    : ForegroundConsent.respond(arg, Binder.getCallingUid(), extras)));
            }
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
            if (!config.enabled) { b.putString("error", "MiAIPlus 未启用"); return b; }
            String query = extras == null ? null : extras.getString("query");
            String mode = extras == null ? "screen" : extras.getString("mode", "screen");
            final PhoneUseRouting.Request route;
            try { route = mode.equals("screen") ? PhoneUseRouting.decode(query, config.backgroundDisplay)
                : new PhoneUseRouting.Request(query, false, ""); }
            catch (Exception e) { b.putString("error", publicError(e)); return b; }
            query = route.query();
            final String taskQuery = query;
            if (!mode.equals("screen") && !mode.equals("chat")) { b.putString("error", "未知模式"); return b; }
            if (NativeSession.hasActiveSession() && (!mode.equals("screen") || !config.planner.equals("native"))) {
                b.putString("error", "已有小爱逐步控屏会话，请先停止"); return b;
            }
            if (NativeSession.hasActiveSession() && (query == null || !query.startsWith("MIAIPLUS:"))) {
                b.putString("error", "已有控屏会话，请先停止或继续当前会话"); return b;
            }
            synchronized (JOBS) {
                if (backgroundProbeRunning) { b.putString("error", "后台显示检测进行中，请等待检测结束"); return b; }
                if (active != null) { b.putString("error", "已有任务执行中，请先停止"); return b; }
                if (!NativeSession.hasActiveSession()
                    && (BackgroundDisplay.active() != null || NativeSession.hasPendingOperation())) {
                    b.putString("error", "上一控屏任务仍在清理，请等待结束"); return b;
                }
                // A stale continuation is a rejected call, not a new screen task. Reserving a task here
                // would reopen the focus island and hide/show the chat on every model retry.
                if (mode.equals("screen") && config.planner.equals("native")
                    && query != null && query.startsWith("MIAIPLUS:")) {
                    try { NativeSession.verifyContinuation(query, Binder.getCallingUid(), route.sourceTool()); }
                    catch (Exception invalid) { b.putString("error", PhoneUseResult.stopped(publicError(invalid))); return b; }
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
                var timeout = WATCHDOG.schedule(run::cancel, RunState.STEP_BUDGET_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                WORKER.execute(() -> {
                    try {
                        if (!run.screenReady.await(6, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("小爱交互界面未就绪，已停止");
                        run.check();
                        if (run.preparingScreen) {
                            AccessibilityGuard.prepare(getContext(), run);
                            run.preparingScreen = false;
                            run.check();
                        }
                        String text = AgentEngine.run(config, mode, taskQuery, run, route.background(), route.sourceTool());
                        run.result = text.length() > 24000 ? text.substring(0, 24000) + "\n[结果过长已截断]" : text;
                        run.check(); run.status = "done";
                    }
                    catch (Exception e) {
                        // Keep exception details out of IPC to avoid echoing API bodies or typed content.
                        String reason = run.cancelled.get() ? "任务已停止或超时" : publicError(e);
                        run.result = run.screen ? PhoneUseResult.stopped(reason) : reason;
                        run.status = "error";
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
            b.putString("error", "任务不存在或不可访问"); return b;
        }
        if (method.equals("screen_ready")) run.screenReady.countDown();
        else if (method.equals("cancel")) run.cancel();
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
    public static void cancelActive() { RunState run = active; if (run != null) run.cancel(); NativeSession.cancel(); }
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
        if (run != null && !run.preparingScreen) run.cancel();
        NativeSession.cancel();
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
