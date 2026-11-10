package io.github.miaiplus;

import android.content.Context;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only observers of XiaoAi's own error path, for diagnosis only.
 *
 * <p>When XiaoAi answers with one of its stock sentences ("当前功能暂未对你开放。", a quota or login notice), the
 * request never reached a MiAIPlus tool, so no other record exists. These hooks keep the error <em>codes</em>
 * XiaoAi mapped to that sentence. They never change an argument or a result, never read the request, and never
 * keep the error text: XiaoAi's messages can quote the service's reply.
 *
 * <p>Every binding here is optional. A XiaoAi build where one is absent keeps all module features; the missing
 * name is reported instead of rolling the install back.
 */
final class NativeHostErrorHook {
    private static final Pattern UPSTREAM = Pattern.compile("^\\[([A-Za-z0-9_.:-]{1,48})] ");
    private static final Pattern CODE = Pattern.compile("(?:-?[0-9]{1,16}|[A-Z][A-Z0-9_.:-]{0,47})");

    private NativeHostErrorHook() { }

    /** Returns the observers that could not be installed on this XiaoAi build. */
    static List<String> install(Context context, ClassLoader loader, List<HookCallback.Unhook> hooks) {
        List<String> missing = new ArrayList<>();
        optional(missing, "error_handler", () -> uiErrors(loader, hooks));
        optional(missing, "agent_error", () -> agentErrors(loader, hooks));
        optional(missing, "agent_support", () -> agentSupport(loader, hooks));
        return missing;
    }

    private interface Install { void run() throws Exception; }

    private static void optional(List<String> missing, String name, Install install) {
        try { install.run(); }
        catch (Exception | LinkageError unavailable) {
            missing.add(name);
            PhoneUseLog.hostFailure("optional_hook_" + name, unavailable);
        }
    }

    /**
     * XiaoAi's ErrorHandler turns every engine error into the sentence it shows or speaks. Its class and method
     * names are identical in both supported builds; only the error type's package differs, so it is found by
     * shape rather than by name.
     */
    private static void uiErrors(ClassLoader loader, List<HookCallback.Unhook> hooks) throws Exception {
        Class<?> handler = Class.forName("com.xiaomi.voiceassistant.instruction.base.z", false, loader);
        Class<?> base = Class.forName("com.xiaomi.ai.error.AivsError", false, loader);
        Method target = null;
        for (Method method : handler.getDeclaredMethods()) {
            if (!method.getName().equals("handleError") || !Modifier.isStatic(method.getModifiers())
                || method.getParameterCount() != 1 || !base.isAssignableFrom(method.getParameterTypes()[0])) continue;
            if (target != null) throw new IllegalStateException("Ambiguous host error handler");
            target = method;
        }
        if (target == null) throw new IllegalStateException("Missing host error handler");
        hooks.add(Hooks.hookMethod(target, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Object error = p.args[0];
                if (error == null) return;
                HostTrace.Snapshot route = HostTrace.snapshot();
                PhoneUseLog.hostInspect(() -> recordUiError(error, route));
            }
        }));
    }

    /** Runs only on the opt-in diagnostic worker; neither arbitrary messages nor object toString are retained. */
    static void recordUiError(Object error, HostTrace.Snapshot route) {
        try {
            Object text = optionalCall(error, "getErrorMessage");
            String message = text instanceof String value ? value : null;
            Matcher upstream = UPSTREAM.matcher(message == null ? "" : message);
            // Extra metadata may be unavailable even when the base error is readable. Do not lose its code.
            Object status = HostCompatibility.modern() ? optionalField(error, "f") : null;
            PhoneUseLog.hostEvent("host_error",
                "code", code(Reflector.callMethod(error, "getErrorCode")),
                "upstream", upstream.find() ? code(upstream.group(1)) : "",
                "httpStatus", status instanceof Integer value ? value : -1,
                "local", Boolean.TRUE.equals(optionalField(error, "d")),
                "messageChars", message == null ? -1 : message.length(),
                "route", route.reference(), "sinceRouteMs", route.ageMs(), "forcedAgent", route.forcedAgent());
        } catch (RuntimeException | LinkageError unavailable) { PhoneUseLog.hostFailure("host_error_read", unavailable); }
    }

    private static Object optionalField(Object value, String field) {
        try { return Reflector.getObjectField(value, field); }
        catch (RuntimeException | LinkageError unavailable) { return null; }
    }

    private static Object optionalCall(Object value, String method) {
        try { return Reflector.callMethod(value, method); }
        catch (RuntimeException | LinkageError unavailable) { return null; }
    }

    static String code(Object value) {
        if (!(value instanceof String || value instanceof Integer || value instanceof Long)) return "other";
        String code = String.valueOf(value);
        return CODE.matcher(code).matches() ? code : "other";
    }

    static String category(Object value) { return value instanceof Enum<?> entry ? entry.name() : "other"; }

    /** The agent ("MiClaw") engine's service errors, before they are folded into a UI error code. */
    private static void agentErrors(ClassLoader loader, List<HookCallback.Unhook> hooks) throws Exception {
        Class<?> engine = Class.forName(HostCompatibility.choose("e51.v", "w51.z"), false, loader);
        Class<?> error = Class.forName(HostCompatibility.choose("f51.c$d", "x51.b$d"), false, loader);
        Class<?> state = Class.forName(HostCompatibility.choose("f51.d", "x51.c"), false, loader);
        Method target = engine.getDeclaredMethod(HostCompatibility.choose("C", "E"), error, state, String.class);
        if (target.getReturnType() != void.class) throw new IllegalStateException("agent error signature mismatch");
        hooks.add(Hooks.hookMethod(target, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Object value = p.args[0];
                if (value == null) return;
                HostTrace.Snapshot route = HostTrace.snapshot();
                PhoneUseLog.hostInspect(() -> recordAgentError(value, route));
            }
        }));
    }

    static void recordAgentError(Object value, HostTrace.Snapshot route) {
        try {
            Object text = optionalCall(value, "getMessage");
            String message = text instanceof String content ? content : null;
            Object status = HostCompatibility.modern() ? optionalCall(value, "getHttpStatus") : null;
            PhoneUseLog.hostEvent("host_agent_error", "code", code(Reflector.callMethod(value, "getCode")),
                "origin", category(optionalCall(value, "getOrigin")),
                "type", category(optionalCall(value, "getType")),
                "httpStatus", status instanceof Integer number ? number : -1,
                "messageChars", message == null ? -1 : message.length(),
                "route", route.reference(), "sinceRouteMs", route.ageMs(), "forcedAgent", route.forcedAgent());
        } catch (RuntimeException | LinkageError unavailable) { PhoneUseLog.hostFailure("host_agent_error_read", unavailable); }
    }

    /** XiaoAi's own check that this ROM region may use the agent; a refusal drops the request before any model. */
    private static void agentSupport(ClassLoader loader, List<HookCallback.Unhook> hooks) throws Exception {
        Class<?> gate = Class.forName(HostCompatibility.choose("io1.x", "up1.x"), false, loader);
        Method target = gate.getDeclaredMethod("ensureSupported", Context.class);
        if (target.getReturnType() != boolean.class) throw new IllegalStateException("agent support signature mismatch");
        hooks.add(Hooks.hookMethod(target, new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    HostTrace.Snapshot route = HostTrace.snapshot();
                    if (p.hasThrowable()) {
                        Throwable failure = p.getThrowable();
                        PhoneUseLog.hostInspect(() -> PhoneUseLog.hostFailure("host_agent_support", failure));
                        return;
                    }
                    boolean supported = Boolean.TRUE.equals(p.getResult());
                    // Consulted on every agent entry; a refusal is always worth a record, an approval rarely.
                    PhoneUseLog.hostInspect(() -> {
                        if (HostTrace.changed("agent_support", String.valueOf(supported), 60_000) || !supported)
                            PhoneUseLog.hostEvent("host_agent_support", "supported", supported,
                                "route", route.reference(), "sinceRouteMs", route.ageMs());
                    });
                } catch (Throwable unavailable) { /* Diagnostics never affect the host's own check. */ }
            }
        }));
    }
}
