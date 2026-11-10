package io.github.miaiplus;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import io.github.libxposed.api.XposedModule;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;

import java.lang.reflect.Method;

/** Exact-version XiaoAI integration; bindings are selected before installing any host hooks. */
public final class XiaoAiHook extends XposedModule {
    private static final java.util.List<HookCallback.Unhook> installed = new java.util.ArrayList<>();
    private static Context context;
    private static InteractionUi interaction;
    private static volatile boolean enabled;
    private static volatile long flagsAt;
    // Why the module is or is not acting in this XiaoAi process: the switch, or an unreachable module process.
    private static volatile String gate = "unknown";
    private static int gateFailures;
    private static long gateFailedAt;
    private static String hostVersionName = "";
    private static long hostVersionCode;
    private static String hostProfile = "unsupported";

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        Hooks.initialize(this);
        if (!param.getProcessName().equals("com.miui.voiceassist")) detach();
    }
    @Override public void onPackageReady(PackageReadyParam param) {
        if (!param.getPackageName().equals("com.miui.voiceassist") || !param.isFirstPackage()) return;
        installed.add(Reflector.findAndHookMethod(Application.class, "attach", Context.class, new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                initialize((Application)call.thisObject, param.getClassLoader());
            }
        }));
    }
    private static void initialize(Application application, ClassLoader loader) {
        context = application;
        HostTrace.stage("host_version");
        try {
            var info = context.getPackageManager().getPackageInfo("com.miui.voiceassist", 0);
            hostVersionName = String.valueOf(info.versionName); hostVersionCode = info.getLongVersionCode();
            hostProfile = HostCompatibility.profile(info.versionName, hostVersionCode);
            if (!HostCompatibility.select(info.versionName, info.getLongVersionCode())) {
                report("unsupported: " + info.versionName + " (" + info.getLongVersionCode() + ")");
                status("install", "phase", "unsupported");
                return;
            }
            install(loader);
        } catch (Throwable failure) {
            // Read before cleanup: which hook group and which XiaoAi binding stopped the install.
            String stage = HostTrace.stage();
            Object[] cause = PhoneUseLog.describe(failure);
            String binding = "";
            for (int i = 0; i + 1 < cause.length; i += 2) if ("binding".equals(cause[i])) binding = String.valueOf(cause[i + 1]);
            Hooks.log(failure);
            android.util.Log.e("MiAIPlus", "Host hook initialization failed", failure);
            try { Class.forName("io.github.miaiplus.PluginPlatformProbe").getMethod("dispose", Context.class).invoke(null, context); }
            catch (Throwable ignored) { }
            try { NativePluginTools.dispose(); } catch (Exception ignored) { }
            try { NativeModelHook.dispose(); } catch (Exception ignored) { }
            Hooks.unhookAll(); installed.clear();
            try { NativeConversationHook.dispose(); } catch (Exception ignored) { }
            try { NativeSpeechPolicyHook.dispose(); } catch (Exception ignored) { }
            if (interaction != null && !interaction.isRunning()) try { interaction.dispose(); } catch (Exception ignored) { }
            report("hook failed, rolled back: " + failure.getClass().getSimpleName() + " at " + stage
                + (binding.isEmpty() ? "" : " (" + binding + ")"));
            status("install", PhoneUseLog.concat(new Object[]{"phase", "rolled_back", "stage", stage}, cause));
        }
    }
    @Override public boolean onHotReloading(HotReloadingParam param) {
        // A refused reload leaves this XiaoAi on its current module code; say which condition refused it.
        if (!Hooks.freeze()) { status("reload", "phase", "reload_refused", "reason", "hook_call_in_progress"); return false; }
        String busy = interaction != null && interaction.isRunning() ? "screen_task_running"
            : !NativeModelHook.canReload() ? "model_request_running"
            : !NativePluginTools.canReload(context) ? "plugin_call_running" : null;
        if (busy != null) {
            Hooks.thaw();
            status("reload", "phase", "reload_refused", "reason", busy);
            return false;
        }
        try {
            // UI listeners and host tool proxies also retain the old module classloader.
            NativeReasoningHook.dispose();
            NativePluginTools.dispose();
            NativeTurnLifecycle.dispose();
            NativeConversationHook.dispose();
            NativeSpeechPolicyHook.dispose();
            NativeModelHook.dispose();
            try { Class.forName("io.github.miaiplus.HookSmokeProbe").getMethod("dispose", Context.class).invoke(null, context); }
            catch (ClassNotFoundException expected) { }
            try { Class.forName("io.github.miaiplus.PluginPlatformProbe").getMethod("dispose", Context.class).invoke(null, context); }
            catch (ClassNotFoundException expected) { }
            if (interaction != null) interaction.dispose();
            param.setSavedInstanceState(null);
            return true;
        } catch (Throwable failure) {
            Hooks.thaw();
            Hooks.log(failure);
            report("reload cleanup failed; restart XiaoAi: " + failure.getClass().getSimpleName());
            status("reload", PhoneUseLog.concat(new Object[]{"phase", "reload_cleanup_failed"}, PhoneUseLog.describe(failure)));
            return false;
        }
    }
    @Override public void onHotReloaded(HotReloadedParam param) {
        Hooks.initialize(this);
        param.getOldHookHandles().forEach(io.github.libxposed.api.XposedInterface.HookHandle::unhook);
        if (!param.getProcessName().equals("com.miui.voiceassist")) { detach(); return; }
        Application application = (Application)Reflector.callStaticMethod(Reflector.findClass("android.app.ActivityThread", null), "currentApplication");
        if (application != null) initialize(application, application.getClassLoader());
    }
    private static boolean enabled() {
        long now = SystemClock.elapsedRealtime();
        if (now - flagsAt < 2000) return enabled;
        String state;
        try {
            Bundle flags = context.getContentResolver().call(BridgeProvider.URI, "flags", null, null);
            enabled = flags != null && flags.getBoolean("enabled");
            state = flags == null ? "bridge_null" : enabled ? "flags_true" : "flags_false";
        } catch (Throwable failure) { enabled = false; state = "bridge_exception:" + failure.getClass().getSimpleName(); }
        flagsAt = now;
        gate(state, now);
        return enabled;
    }
    /**
     * A disabled switch and an unreachable module process both make every hook step aside, so XiaoAi answers
     * as stock. While the module is unreachable no record can be delivered; the outage is reported once it ends.
     */
    private static synchronized void gate(String state, long now) {
        gate = state;
        if (state.startsWith("flags_")) {
            if (gateFailures > 0) PhoneUseLog.hostEvent("bridge_recovered", "failures", gateFailures,
                "outageMs", now - gateFailedAt);
            gateFailures = 0;
        } else if (gateFailures++ == 0) gateFailedAt = now;
        if (HostTrace.changed("module_gate", state, 60_000)) PhoneUseLog.hostEvent("module_gate", "state", state);
    }
    private static void install(ClassLoader loader) throws Exception {
        PhoneUseLog.initRemote(context);
        HostTrace.stage("interaction_ui");
        interaction = MainThread.call(() -> new InteractionUi((Application)context, loader));
        HostTrace.stage("control_app");
        Class<?> service = Reflector.findClass("com.xiaomi.voiceassistant.UIAgentServiceForOSBot", loader);
        Class<?> result = Reflector.findClass("com.aios.apptoolsdk.AppResult", loader);
        Method success = result.getDeclaredMethod("success", String.class), error = result.getDeclaredMethod("error", String.class);
        Method control = service.getDeclaredMethod("controlApp", String.class);
        if (control.getReturnType() != result) throw new IllegalStateException("controlApp signature mismatch");
        installed.add(Hooks.hookMethod(control, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!enabled()) {
                    // XiaoAi's stock controlApp answers instead. Without this a bypassed module and a XiaoAi
                    // refusal are the same thing to whoever reads the conversation.
                    PhoneUseLog.hostEvent("host_control_call", "outcome", "passthrough", "gate", gate);
                    return;
                }
                // One record per tool call. The wording of every failure below goes to the model; the log keeps
                // which step failed, never the query or the reply.
                long began = SystemClock.elapsedRealtime();
                String stage = "precheck", outcome = "rejected", reason = "", kind = "start";
                int polls = 0;
                Throwable thrown = null;
                try {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    reason = "main_thread";
                    param.setResult(error.invoke(null, PhoneUseResult.stopped("控屏工具不可在主线程执行"))); return;
                }
                String query = (String)param.args[0];
                if (query == null || query.isBlank()) {
                    reason = "blank_query";
                    param.setResult(error.invoke(null, PhoneUseResult.stopped("缺少 query"))); return;
                }
                boolean chat = query.startsWith("模型对话：") || query.startsWith("模型对话:");
                if (chat) query = query.substring(5).trim();
                kind = chat ? "chat" : query.startsWith("MIAIPLUS:") ? "continuation" : "start";
                String id = null;
                try {
                    stage = "conversation";
                    // Resolve a new task's origin before the provider starts its screen-ready timeout.
                    android.content.Intent conversation = !chat && !query.startsWith("MIAIPLUS:")
                        ? ConversationReturn.capture(context, loader) : null;
                    stage = "bridge_start";
                    Bundle request = new Bundle(); request.putString("query", query); request.putString("mode", chat ? "chat" : "screen");
                    request.putBoolean("screen_ui", !chat);
                    Bundle started = context.getContentResolver().call(BridgeProvider.URI, "start", null, request);
                    if (started == null || started.containsKey("error")) {
                        // The module records its own reason as start_rejected.
                        reason = started == null ? "bridge_null" : "start_rejected";
                        throw new IllegalStateException(started == null ? "模块桥接不可用" : started.getString("error"));
                    }
                    id = started.getString("id");
                    if (!chat) {
                        stage = "ui_begin";
                        interaction.begin(started.getString("screen_task"), conversation);
                        context.getContentResolver().call(BridgeProvider.URI, "screen_ready", id, null);
                        interaction.started();
                    }
                    stage = "poll";
                    long deadline = SystemClock.elapsedRealtime() + 245_000;
                    while (SystemClock.elapsedRealtime() < deadline) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        Bundle state = context.getContentResolver().call(BridgeProvider.URI, "poll", id, null);
                        polls++;
                        if (state == null || state.containsKey("error")) {
                            reason = state == null ? "bridge_null" : "task_lost";
                            throw new IllegalStateException("模块任务已丢失，请检查模块进程");
                        }
                        String status = state.getString("status");
                        if ("done".equals(status) || "error".equals(status)) {
                            String text = state.getString("result");
                            if (!chat && "error".equals(status)) text = PhoneUseResult.stopped(text);
                            stage = "done"; outcome = status;
                            reason = text == null ? "empty" : text.startsWith("CONTINUE") ? "CONTINUE"
                                : text.startsWith("STOP") ? "STOP" : text.startsWith("PAUSE") ? "PAUSE" : "other";
                            param.setResult(("done".equals(status) ? success : error).invoke(null, text));
                            return;
                        }
                        Thread.sleep(400);
                    }
                    reason = "poll_timeout";
                    throw new IllegalStateException("控屏等待超时，已停止");
                } catch (Throwable e) {
                    thrown = e; outcome = "exception";
                    Hooks.log(e);
                    if (!chat && id != null) interaction.started();
                    if (id != null) try { context.getContentResolver().call(BridgeProvider.URI, "cancel", id, null); } catch (Throwable ignored) { }
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String text = e instanceof IllegalStateException ? e.getMessage() : "MiAIPlus 桥接失败：" + e.getClass().getSimpleName();
                    param.setResult(error.invoke(null, chat ? text : PhoneUseResult.stopped(text)));
                }
                } finally {
                    Object[] fields = {"kind", kind, "stage", stage, "outcome", outcome, "reason", reason,
                        "polls", polls, "durationMs", SystemClock.elapsedRealtime() - began};
                    Throwable failure = thrown;
                    PhoneUseLog.hostInspect(() -> PhoneUseLog.hostEvent("host_control_call",
                        failure == null ? fields : PhoneUseLog.concat(fields, PhoneUseLog.describe(failure))));
                }
            }
        }));
        HostTrace.stage("tool_definitions");
        // Modify descriptions when definitions are created, including serialization's direct field access.
        for (String name : new String[]{"com.aios.osbot.tools.apptool.AppToolDefinition", "com.aios.osbot.llm.model.ToolDefinition"}) {
            Class<?> definition = Reflector.findClass(name, loader);
            installed.addAll(Hooks.hookAllConstructors(definition, new HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled()) return;
                    String tool = (String)Reflector.getObjectField(param.thisObject, "name");
                    if ("controlApp".equals(tool) || "voiceassist__controlApp".equals(tool))
                        Reflector.setObjectField(param.thisObject, "description", PhoneUsePlugin.legacyDescription());
                }
            }));
        }
        HostTrace.stage("prompt_builder");
        // Version-pinned name from the decompiled PromptBuilder; no on-disk prompt/config changes.
        Class<?> builder = Reflector.findClass("as.a", loader);
        for (String name : new String[]{"build", "buildSessionHead", "buildTurnInject"}) {
            installed.add(Reflector.findAndHookMethod(builder, name, new HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || !(param.getResult() instanceof String text)) return;
                    // Without the policy the model is never told the tools exist; record why it was left out.
                    String outcome = "no_marker";
                    int policyChars = 0;
                    if ((text.contains("voiceassist__controlApp") || text.contains("你是超级小爱") || text.contains("你是用户的智能助手超级小爱"))
                        && !text.contains("[MiAIPlus 扩展能力")) {
                        String policy = NativePluginTools.promptPolicy(context);
                        if (!policy.isEmpty()) param.setResult(text + policy);
                        outcome = policy.isEmpty() ? "no_enabled_tool" : "injected";
                        policyChars = policy.length();
                    } else if (text.contains("[MiAIPlus 扩展能力")) outcome = "already_present";
                    if (HostTrace.changed("prompt_policy:" + name, outcome, 60_000))
                        PhoneUseLog.hostEvent("prompt_policy", "builder", name, "outcome", outcome,
                            "promptChars", text.length(), "policyChars", policyChars);
                }
            }));
        }
        HostTrace.stage("conversation_limits");
        NativeConversationLimitsHook.install(context, loader, installed);
        HostTrace.stage("conversation");
        NativeConversationHook.install(context, loader, installed);
        HostTrace.stage("speech_reply_policy");
        NativeSpeechPolicyHook.install(context, loader, installed);
        HostTrace.stage("model");
        NativeModelHook.install(context, loader, installed);
        HostTrace.stage("probes");
        // The optional diagnostic class exists only in debug APKs, never release artifacts.
        // Legacy probes target mainline fixtures and host preferences; isolated installs use their own probe.
        try {
            if ("io.github.miaiplus".equals(BuildConfig.APPLICATION_ID))
                Class.forName("io.github.miaiplus.HookSmokeProbe").getMethod("install", Context.class, ClassLoader.class).invoke(null, context, loader);
        }
        catch (ClassNotFoundException expected) { }
        try { Class.forName("io.github.miaiplus.PluginPlatformProbe").getMethod("install", Context.class, ClassLoader.class).invoke(null, context, loader); }
        catch (ClassNotFoundException expected) { }
        // A host entry point that could not be guarded is worth surfacing: it is the difference between "the
        // assistant stays off the user's screen" and "it flashes on one path". Reported, never assumed.
        String guards = interaction == null || interaction.missingGuards.isEmpty()
            ? "" : "; unguarded=" + interaction.missingGuards;
        // Diagnostic observers of XiaoAi's own error path. Optional: a build without one keeps every feature.
        HostTrace.stage("diagnostics");
        String optional = String.join(",", NativeHostErrorHook.install(context, loader, installed));
        HostTrace.stage("ready");
        report("ready: api102 + focus-island + hot-reload + controlApp + descriptions + prompt + native-planner + global-model + host-ui-guard"
            + guards + "; " + Application.getProcessName());
        int unguarded = interaction == null ? 0 : interaction.missingGuards.size();
        status("install", "phase", "ready", "hooks", installed.size(), "unguarded", unguarded, "optional", optional);
        // Marks this XiaoAi process in the journal: every later host record carries the same pid.
        PhoneUseLog.hostEvent("host_hooks", "phase", "ready", "profile", hostProfile, "hostVersionCode", hostVersionCode,
            "hooks", installed.size(), "unguarded", unguarded, "optionalMissing", optional);
    }
    /**
     * Structured install or reload state for the diagnostic export, stored by the module beside the one-line
     * report. Sent whether or not logging is on: a failed install is exactly when no log record can exist.
     */
    private static void status(String slot, Object... fields) {
        try {
            org.json.JSONObject status = new org.json.JSONObject();
            for (int i = 0; i + 1 < fields.length; i += 2) status.put(String.valueOf(fields[i]), fields[i + 1]);
            status.put("moduleVersionName", BuildConfig.VERSION_NAME).put("moduleVersionCode", BuildConfig.VERSION_CODE)
                .put("hostVersionName", hostVersionName).put("hostVersionCode", hostVersionCode)
                .put("profile", hostProfile).put("process", Application.getProcessName())
                .put("pid", android.os.Process.myPid()).put("gate", gate);
            String[] framework = Hooks.framework();
            if (framework.length == 4) status.put("frameworkName", framework[0]).put("frameworkVersion", framework[1])
                .put("frameworkVersionCode", framework[2]).put("frameworkApi", framework[3]);
            Bundle b = new Bundle(); b.putString("status", status.toString()); b.putString("slot", slot);
            context.getContentResolver().call(BridgeProvider.URI, "hook_status", null, b);
        } catch (Throwable ignored) { }
    }
    private static void report(String text) {
        Hooks.log("MiAIPlus: " + text);
        try {
            Bundle b = new Bundle(); b.putString("report", text);
            context.getContentResolver().call(BridgeProvider.URI, "hook_report", null, b);
        } catch (Throwable ignored) { }
    }
}
