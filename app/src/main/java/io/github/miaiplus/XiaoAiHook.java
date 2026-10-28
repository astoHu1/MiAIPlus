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
        try {
            var info = context.getPackageManager().getPackageInfo("com.miui.voiceassist", 0);
            if (!HostCompatibility.select(info.versionName, info.getLongVersionCode())) {
                report("unsupported: " + info.versionName + " (" + info.getLongVersionCode() + ")"); return;
            }
            install(loader);
        } catch (Throwable failure) {
            Hooks.log(failure);
            android.util.Log.e("MiAIPlus", "Host hook initialization failed", failure);
            try { Class.forName("io.github.miaiplus.PluginPlatformProbe").getMethod("dispose", Context.class).invoke(null, context); }
            catch (Throwable ignored) { }
            try { NativePluginTools.dispose(); } catch (Exception ignored) { }
            Hooks.unhookAll(); installed.clear();
            try { NativeConversationHook.dispose(); } catch (Exception ignored) { }
            if (interaction != null && !interaction.isRunning()) try { interaction.dispose(); } catch (Exception ignored) { }
            report("hook failed, rolled back: " + failure.getClass().getSimpleName());
        }
    }
    @Override public boolean onHotReloading(HotReloadingParam param) {
        if (!Hooks.freeze()) return false;
        if ((interaction != null && interaction.isRunning()) || !NativeModelHook.canReload()
            || !NativePluginTools.canReload(context)) { Hooks.thaw(); return false; }
        try {
            // UI listeners and host tool proxies also retain the old module classloader.
            NativeReasoningHook.dispose();
            NativePluginTools.dispose();
            NativeTurnLifecycle.dispose();
            NativeConversationHook.dispose();
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
        try {
            Bundle flags = context.getContentResolver().call(BridgeProvider.URI, "flags", null, null);
            enabled = flags != null && flags.getBoolean("enabled");
        } catch (Throwable ignored) { enabled = false; }
        flagsAt = now; return enabled;
    }
    private static void install(ClassLoader loader) throws Exception {
        interaction = MainThread.call(() -> new InteractionUi((Application)context, loader));
        Class<?> service = Reflector.findClass("com.xiaomi.voiceassistant.UIAgentServiceForOSBot", loader);
        Class<?> result = Reflector.findClass("com.aios.apptoolsdk.AppResult", loader);
        Method success = result.getDeclaredMethod("success", String.class), error = result.getDeclaredMethod("error", String.class);
        Method control = service.getDeclaredMethod("controlApp", String.class);
        if (control.getReturnType() != result) throw new IllegalStateException("controlApp signature mismatch");
        installed.add(Hooks.hookMethod(control, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!enabled()) return;
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    param.setResult(error.invoke(null, PhoneUseResult.stopped("控屏工具不可在主线程执行"))); return;
                }
                String query = (String)param.args[0];
                if (query == null || query.isBlank()) { param.setResult(error.invoke(null, PhoneUseResult.stopped("缺少 query"))); return; }
                boolean chat = query.startsWith("模型对话：") || query.startsWith("模型对话:");
                if (chat) query = query.substring(5).trim();
                String id = null;
                try {
                    // Resolve a new task's origin before the provider starts its screen-ready timeout.
                    android.content.Intent conversation = !chat && !query.startsWith("MIAIPLUS:")
                        ? ConversationReturn.capture(context, loader) : null;
                    Bundle request = new Bundle(); request.putString("query", query); request.putString("mode", chat ? "chat" : "screen");
                    request.putBoolean("screen_ui", !chat);
                    Bundle started = context.getContentResolver().call(BridgeProvider.URI, "start", null, request);
                    if (started == null || started.containsKey("error")) throw new IllegalStateException(started == null ? "模块桥接不可用" : started.getString("error"));
                    id = started.getString("id");
                    if (!chat) {
                        interaction.begin(started.getString("screen_task"), conversation);
                        context.getContentResolver().call(BridgeProvider.URI, "screen_ready", id, null);
                        interaction.started();
                    }
                    long deadline = SystemClock.elapsedRealtime() + 245_000;
                    while (SystemClock.elapsedRealtime() < deadline) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        Bundle state = context.getContentResolver().call(BridgeProvider.URI, "poll", id, null);
                        if (state == null || state.containsKey("error")) throw new IllegalStateException("模块任务已丢失，请检查模块进程");
                        String status = state.getString("status");
                        if ("done".equals(status) || "error".equals(status)) {
                            String text = state.getString("result");
                            if (!chat && "error".equals(status)) text = PhoneUseResult.stopped(text);
                            param.setResult(("done".equals(status) ? success : error).invoke(null, text));
                            return;
                        }
                        Thread.sleep(400);
                    }
                    throw new IllegalStateException("控屏等待超时，已停止");
                } catch (Throwable e) {
                    Hooks.log(e);
                    if (!chat && id != null) interaction.started();
                    if (id != null) try { context.getContentResolver().call(BridgeProvider.URI, "cancel", id, null); } catch (Throwable ignored) { }
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String text = e instanceof IllegalStateException ? e.getMessage() : "MiAIPlus 桥接失败：" + e.getClass().getSimpleName();
                    param.setResult(error.invoke(null, chat ? text : PhoneUseResult.stopped(text)));
                }
            }
        }));
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
        // Version-pinned name from the decompiled PromptBuilder; no on-disk prompt/config changes.
        Class<?> builder = Reflector.findClass("as.a", loader);
        for (String name : new String[]{"build", "buildSessionHead", "buildTurnInject"}) {
            installed.add(Reflector.findAndHookMethod(builder, name, new HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || !(param.getResult() instanceof String text)) return;
                    if ((text.contains("voiceassist__controlApp") || text.contains("你是超级小爱") || text.contains("你是用户的智能助手超级小爱"))
                        && !text.contains("[MiAIPlus 扩展能力")) {
                        String policy = NativePluginTools.promptPolicy(context);
                        if (!policy.isEmpty()) param.setResult(text + policy);
                    }
                }
            }));
        }
        NativeConversationLimitsHook.install(context, loader, installed);
        NativeConversationHook.install(context, loader, installed);
        NativeModelHook.install(context, loader, installed);
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
        report("ready: api102 + focus-island + hot-reload + controlApp + descriptions + prompt + native-planner + global-model + host-ui-guard"
            + guards + "; " + Application.getProcessName());
    }
    private static void report(String text) {
        Hooks.log("MiAIPlus: " + text);
        try {
            Bundle b = new Bundle(); b.putString("report", text);
            context.getContentResolver().call(BridgeProvider.URI, "hook_report", null, b);
        } catch (Throwable ignored) { }
    }
}
