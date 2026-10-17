package io.github.miaiplus;

import android.content.Context;
import android.widget.Toast;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.util.List;

/** Pinned text-chat routing; external reasoning never persists a Xiaomi mode. */
final class NativeChatRoutingHook {
    private NativeChatRoutingHook() {}

    static void install(Context context, ClassLoader loader, List<HookCallback.Unhook> hooks) throws Exception {
        // AUTO/QUICK text chats otherwise use AIVS Nlp.Request, bypassing every OSBot
        // model client. Reuse the native MiClaw send path, history and cards. Its
        // existing device-support and attachment checks still run in sendContent.
        Class<?> callback = Reflector.findClass("ll1.f0", loader);
        // Xposed logs exceptions from hook callbacks and then runs the original.
        // Stop the UI submit explicitly when configuration cannot be read.
        hooks.add(Hooks.hookMethod(callback.getDeclaredMethod(HostCompatibility.choose("A", "B"), String.class, String.class), new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try { NativeModelHook.requestConfig(context); }
                catch (Exception unavailable) {
                    p.setResult(null);
                    Toast.makeText(context, "模型配置不可用，请检查 MiAIPlus 后重试", Toast.LENGTH_LONG).show();
                }
            }
        }));
        var route = callback.getDeclaredMethod(HostCompatibility.choose("C", "D"));
        if (route.getReturnType() != boolean.class) throw new IllegalStateException("chat route signature mismatch");
        hooks.add(Hooks.hookMethod(route, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (NativeModelHook.config(context).getBoolean("globalModel")) p.setResult(true);
            }
        }));
    }
}
