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

/** Compatibility profile: XiaoAi 8.2.65.4714, versionCode 508002065. */
public final class XiaoAiHook extends XposedModule {
    private static final java.util.List<HookCallback.Unhook> installed = new java.util.ArrayList<>();
    private static Context context;
    private static InteractionUi interaction;
    private static volatile boolean enabled;
    private static volatile long flagsAt;
    private static final String DESCRIPTION = "本机全应用识屏控屏工具；默认由小爱当前模型逐步规划，无需另配API。用户要求在手机已安装应用中执行 UI 操作（包括微信）时，首次原样传入完整请求；CONTINUE表示尚未完成，按返回协议继续调用同一工具，每次只执行一个动作；"
        + "系统设置、搜索及已有专用工具优先走原功能。用户明确指定第三方模型对话时，query 使用前缀『模型对话：』加用户问题；此模式不读取屏幕。"
        + "控屏由 MiAIPlus 在本机执行；默认由你逐步规划，只有用户选择独立外部模式才另调其配置端点。返回 PAUSE 时提示用户接管，不要自行重试。";
    private static final String POLICY = "\n\n[MiAIPlus 扩展能力，覆盖上文同一工具的旧范围说明]\n"
        + "默认逐步模式优先直接使用结构化函数 miaiplus_phone_use：首次{goal:完整任务}，之后{session:返回会话号,coordinate_space:screen_pixels,action:{action:tap,element:[树中目标中心x,树中目标中心y]}}，直至finish。不要把自然语言动作再次当成首次任务。voiceassist__controlApp 已由本机 MiAIPlus 接管，不再受原云端的应用白名单或微信不支持限制。"
        + "对于用户明确要求的应用内 UI 操作，已有专用能力优先；无专用能力时可用 cli 调用 voiceassist__controlApp，原样传递完整任务。"
        + "包括微信等应用的页面操作、消息等，仅在用户明确要求时执行。"
        + "需要后续识屏、点击或输入的任务先开始miaiplus_phone_use会话，再用launch打开目标，不要先用app start打开应用；仅打开应用的普通请求仍保留原功能。"
        + "控屏会话内打开应用使用miaiplus_phone_use的launch动作，app从首次观察的应用列表选择包名，不要另调app start或原生打开应用工具；只有观察到目标前台才报告打开。当前launch对应的小米启动确认由执行器自动允许本次；其他系统权限应接管。"
        + "每个控屏action附intent简短中文说明当前意图供灵动岛显示；不含隐私、坐标或推理过程，普通意图不要放入触发确认的message。"
        + "用户说『用第三方模型回答/对话』时调用 cli: voiceassist__controlApp \"模型对话：用户问题\"，原样返回结果。"
        + "普通对话、联网搜索、系统设置、智能家居仍使用现有主模型、路由和工具（主模型可由用户全局替换）。不要把这些任务改派到控屏。"
        + "工具返回CONTINUE时必须按返回的session与MIAIPLUS:动作JSON继续调用voiceassist__controlApp，依据最新图片或无障碍树规划；此模式无需额外视觉模型。不要只复述观察后结束。工具报错不要重试、不要伪称完成；PAUSE 表示需要用户接管。\n";

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
            if (info.getLongVersionCode() != 508002065L || !"8.2.65.4714".equals(info.versionName)) {
                report("unsupported: " + info.versionName); return;
            }
            install(loader);
        } catch (Throwable failure) {
            Hooks.unhookAll(); installed.clear();
            if (interaction != null && !interaction.isRunning()) try { interaction.dispose(); } catch (Exception ignored) { }
            report("hook failed, rolled back: " + failure.getClass().getSimpleName());
        }
    }
    @Override public boolean onHotReloading(HotReloadingParam param) {
        if (!Hooks.freeze()) return false;
        if ((interaction != null && interaction.isRunning()) || !NativeModelHook.canReload()) { Hooks.thaw(); return false; }
        try {
            // UI listeners and host tool proxies also retain the old module classloader.
            NativeReasoningHook.dispose();
            NativePhoneUseTool.dispose();
            NativeModelHook.dispose();
            try { Class.forName("io.github.miaiplus.HookSmokeProbe").getMethod("dispose", Context.class).invoke(null, context); }
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
                    param.setResult(error.invoke(null, "控屏工具不可在主线程执行")); return;
                }
                String query = (String)param.args[0];
                if (query == null || query.isBlank()) { param.setResult(error.invoke(null, "缺少 query")); return; }
                boolean chat = query.startsWith("模型对话：") || query.startsWith("模型对话:");
                if (chat) query = query.substring(5).trim();
                String id = null;
                try {
                    Bundle request = new Bundle(); request.putString("query", query); request.putString("mode", chat ? "chat" : "screen");
                    request.putBoolean("screen_ui", !chat);
                    Bundle started = context.getContentResolver().call(BridgeProvider.URI, "start", null, request);
                    if (started == null || started.containsKey("error")) throw new IllegalStateException(started == null ? "模块桥接不可用" : started.getString("error"));
                    id = started.getString("id");
                    if (!chat) {
                        interaction.begin(started.getString("screen_task"));
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
                            param.setResult(("done".equals(status) ? success : error).invoke(null, state.getString("result")));
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
                    param.setResult(error.invoke(null, text));
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
                        Reflector.setObjectField(param.thisObject, "description", DESCRIPTION);
                }
            }));
        }
        // Version-pinned name from the decompiled PromptBuilder; no on-disk prompt/config changes.
        Class<?> builder = Reflector.findClass("as.a", loader);
        for (String name : new String[]{"build", "buildSessionHead", "buildTurnInject"}) {
            installed.add(Reflector.findAndHookMethod(builder, name, new HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || !(param.getResult() instanceof String text)) return;
                    if ((text.contains("voiceassist__controlApp") || text.contains("你是超级小爱") || text.contains("你是用户的智能助手超级小爱")) && !text.contains("[MiAIPlus 扩展能力"))
                        param.setResult(text + POLICY);
                }
            }));
        }
        NativeModelHook.install(context, loader, installed);
        // The optional diagnostic class exists only in debug APKs, never release artifacts.
        try { Class.forName("io.github.miaiplus.HookSmokeProbe").getMethod("install", Context.class, ClassLoader.class).invoke(null, context, loader); }
        catch (ClassNotFoundException expected) { }
        report("ready: api102 + focus-island + hot-reload + controlApp + descriptions + prompt + native-planner + global-model; " + Application.getProcessName());
    }
    private static void report(String text) {
        Hooks.log("MiAIPlus: " + text);
        try {
            Bundle b = new Bundle(); b.putString("report", text);
            context.getContentResolver().call(BridgeProvider.URI, "hook_report", null, b);
        } catch (Throwable ignored) { }
    }
}
