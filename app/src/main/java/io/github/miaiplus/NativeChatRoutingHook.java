package io.github.miaiplus;

import android.content.Context;
import android.widget.Toast;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.lang.ref.WeakReference;
import java.util.List;

/** Pinned text-chat routing; request-local tool access never persists a Xiaomi mode. */
final class NativeChatRoutingHook {
    private static WeakReference<Object> sender = new WeakReference<>(null);
    private NativeChatRoutingHook() {}

    static synchronized Object voiceSender(Context context, ClassLoader loader) {
        Object ui = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.UiManager", loader),
            "getInstance", context);
        Object current = Reflector.callMethod(ui, "getInputModuleViewApi");
        Object callback = current == null ? sender.get() : Reflector.callStaticMethod(current.getClass(),
            "access$getMInputModuleViewCallback$p", current);
        // Existing input views survive a hot reload. Resolve their actual callback
        // again rather than waiting for another constructor to run. A cached sender
        // is usable only while it still belongs to the current native float manager.
        if (callback == null || !Reflector.findClass("ll1.f0", loader).isInstance(callback)
            || Reflector.getObjectField(callback, "d") != Reflector.callMethod(ui, "getFloatManager")) return null;
        sender = new WeakReference<>(callback);
        return callback;
    }
    static synchronized void dispose() { sender.clear(); }

    static void install(Context context, ClassLoader loader, List<HookCallback.Unhook> hooks) throws Exception {
        // AUTO/QUICK text chats otherwise use AIVS Nlp.Request, bypassing every OSBot
        // model client. Reuse the native MiClaw send path, history and cards. Its
        // existing device-support and attachment checks still run in sendContent.
        Class<?> callback = Reflector.findClass("ll1.f0", loader);
        hooks.addAll(Hooks.hookAllConstructors(callback, new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable()) synchronized (NativeChatRoutingHook.class) {
                    sender = new WeakReference<>(p.thisObject);
                }
            }
        }));
        // Xposed logs exceptions from hook callbacks and then runs the original.
        // Stop the UI submit explicitly when configuration cannot be read.
        hooks.add(Hooks.hookMethod(callback.getDeclaredMethod(HostCompatibility.choose("A", "B"), String.class, String.class), new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                // This is where a typed request is sent to XiaoAi's agent or left on its ordinary path. The
                // wording never leaves this method; the record keeps the rule that matched and what it led to.
                String query = (String)p.args[0];
                var decision = NativeChatRouting.decide(query);
                String stage = "model_config";
                try {
                    var settings = NativeModelHook.requestConfig(context);
                    String requestedTool = decision.tool();
                    boolean global = settings.getBoolean("globalModel"), available = false;
                    boolean languageRoute = requestedTool.isEmpty() && NativePhoneLanguageRouting.candidate(context, query);
                    if (!global && (!requestedTool.isEmpty() || languageRoute)) {
                        stage = "plugin_catalog";
                        available = languageRoute ? NativePhoneLanguageRouting.available(context)
                            : NativePluginTools.availableForRouting(context, requestedTool);
                    }
                    boolean phoneUse = !global && available;
                    // D()/C() runs synchronously inside this submission. Do not leave a flag on
                    // the callback or the main thread for a later, unrelated chat to consume.
                    p.setObjectExtra("phoneChatRoute", NativeChatRouting.begin(p.thisObject, phoneUse));
                    PhoneUseLog.hostEvent("chat_route", "entry", "text", "route", HostTrace.route(phoneUse || global),
                        "queryChars", query == null ? -1 : query.length(), "rule", languageRoute && available ? "locale_agent" : decision.rule(),
                        "tool", requestedTool, "globalModel", global, "toolAvailable", available,
                        "agent", phoneUse ? languageRoute ? "forced_for_language" : "forced_for_tool"
                            : global ? "forced_global_model" : "host_default");
                }
                catch (Exception unavailable) {
                    p.setResult(null);
                    PhoneUseLog.hostFailure("chat_route", unavailable, "stage", stage, "rule", decision.rule());
                    Toast.makeText(context, "模型配置不可用，请检查 MiAIPlus 后重试", Toast.LENGTH_LONG).show();
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.getObjectExtra("phoneChatRoute") instanceof NativeChatRouting.Scope scope) scope.close();
            }
        }));
        var route = callback.getDeclaredMethod(HostCompatibility.choose("C", "D"));
        if (route.getReturnType() != boolean.class) throw new IllegalStateException("chat route signature mismatch");
        hooks.add(Hooks.hookMethod(route, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (NativeChatRouting.forcesAgent(p.thisObject)
                    || NativeModelHook.config(context).getBoolean("globalModel")) p.setResult(true);
            }
        }));
    }
}
