package io.github.miaiplus;

import android.content.Context;
import io.github.miaiplus.hook.HookCallback;
import java.util.List;

/** Compatibility facade for existing probes and callers; registration is owned by the plugin platform. */
public final class NativePhoneUseTool {
    public static final String NAME = PhoneUseRouting.FOREGROUND_TOOL;
    public static final String BACKGROUND_NAME = PhoneUseRouting.BACKGROUND_TOOL;

    static void dispose() { NativePluginTools.dispose(); }
    static boolean available(Context context) { return NativePluginTools.available(context, NAME); }
    static Object json(ClassLoader loader, String text) { return NativePluginTools.json(loader, text); }
    public static void install(Context context, ClassLoader loader, List<HookCallback.Unhook> hooks) {
        NativePluginTools.install(context, loader, hooks);
    }
    static Object registry(Context context, ClassLoader loader) { return NativePluginTools.registry(context, loader); }
    static void addDefinition(Context context, HookCallback.MethodHookParam param) {
        NativePluginTools.addDefinition(context, param);
    }

    private NativePhoneUseTool() { }
}
