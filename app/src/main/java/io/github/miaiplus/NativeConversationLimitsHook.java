package io.github.miaiplus;

import android.content.Context;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Remove count-only conversation gates, retaining cancellation, context compaction and tool failures. */
final class NativeConversationLimitsHook {
    static void install(Context context, ClassLoader loader, List<HookCallback.Unhook> hooks) throws Exception {
        Class<?> agentContext = Reflector.findClass("hj.e", loader);
        Class<?> guard = Reflector.findClass(HostCompatibility.choose("mj.h", "xj.h"), loader);
        Object companion = Reflector.getStaticObjectField(guard, "b");
        Object clearedKey = Reflector.callMethod(companion, "getTOOLS_FORCE_CLEARED_KEY");
        hooks.add(Hooks.hookMethod(unique(guard, "beforeModelCall", 4), new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (conversation(p.args[0]) && !toolsCleared(p.args[0], clearedKey))
                    p.setResult(p.args[2]);
            }
        }));
        hooks.add(Hooks.hookMethod(unique(guard, "shouldStop", 4), new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (conversation(p.args[0]) && !toolsCleared(p.args[0], clearedKey)) p.setResult(null);
            }
        }));

        Class<?> agent = Reflector.findClass(HostCompatibility.choose("fj.e", "qj.e"), loader);
        Class<?> continuation = Reflector.findClass("kotlin.coroutines.Continuation", loader);
        Class<?> kotlinFailure = Reflector.findClass("kotlin.Result$Failure", loader);
        Object suspended = Reflector.callStaticMethod(Reflector.findClass(
            "kotlin.coroutines.intrinsics.IntrinsicsKt", loader), "getCOROUTINE_SUSPENDED");
        Object eventCompanion = Reflector.getStaticObjectField(Reflector.findClass(
            HostCompatibility.choose("oj.a", "zj.a"), loader), "a");
        Class<?> iterationIds = Reflector.findClass(HostCompatibility.choose("fj.e$f", "qj.e$f"), loader);
        Class<?> iterationEvent = Reflector.findClass(HostCompatibility.choose("oj.a$g", "zj.a$g"), loader);
        Method begin = null;
        for (Method method : agent.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!method.getName().equals("e") || types.length != 4 || types[1] != agentContext
                || types[2] != int.class || types[3] != continuation) continue;
            if (begin != null) throw new IllegalStateException("Ambiguous native beginIteration");
            begin = method;
        }
        if (begin == null) throw new IllegalStateException("Missing native beginIteration");
        hooks.add(Hooks.hookMethod(begin, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                // Resume entries pass a null context. Leave the host's existing suspended frames alone.
                if (p.args[1] == null || !(p.args[2] instanceof Integer count) || count <= 60
                    || !conversation(p.args[1])) return;
                try {
                    Object ids = Reflector.newInstance(iterationIds,
                        Reflector.callMethod(eventCompanion, "generateId"),
                        Reflector.callMethod(eventCompanion, "generateId"), UUID.randomUUID().toString());
                    Object event = Reflector.newInstance(iterationEvent, count,
                        Reflector.callMethod(p.args[1], "getSessionId"));
                    Object target = p.args[3];
                    Object result = sendIteration(ids, event, suspended, (value, resume) -> {
                        Object bridge = Proxy.newProxyInstance(loader, new Class<?>[]{continuation}, (proxy, method, args) -> {
                            return switch (method.getName()) {
                                case "getContext" -> Reflector.callMethod(target, "getContext");
                                case "resumeWith" -> { resume.accept(args[0]); yield null; }
                                case "equals" -> proxy == args[0];
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "toString" -> "MiAIPlus unlimited conversation iteration";
                                default -> throw new IllegalStateException("Unexpected continuation method");
                            };
                        });
                        return Reflector.callMethod(p.args[0], "send", value, bridge);
                    }, value -> Reflector.callMethod(target, "resumeWith", value), kotlinFailure::isInstance);
                    p.setResult(result);
                } catch (Throwable failure) {
                    // A failed/cancelled event send must remain a failure, never a fresh model iteration.
                    p.setThrowable(failure);
                }
            }
        }));

        // This callback uses count only to reject creating topic 101. Database queries retain their real count.
        Class<?> sidebar = Reflector.findClass(HostCompatibility.choose("im1.w0", "sn1.w0"), loader);
        Class<?> callback = Reflector.findClass(HostCompatibility.choose("im1.w0$c", "sn1.w0$c"), loader);
        hooks.add(Hooks.hookMethod(callback.getDeclaredMethod("d", sidebar, int.class), new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.args[1] = creationGateCount((Integer)p.args[1]);
            }
        }));
    }

    static boolean conversationAgent(String id) {
        return "osbot.main".equals(id) || "osbot.chat".equals(id) || "osbot.overlayassistant".equals(id);
    }

    static int creationGateCount(int actual) { return actual >= 100 ? 99 : actual; }

    private static boolean conversation(Object nativeContext) {
        return nativeContext != null && conversationAgent((String)Reflector.callMethod(nativeContext, "getAgentId"));
    }

    private static boolean toolsCleared(Object nativeContext, Object key) {
        return Boolean.TRUE.equals(Reflector.callMethod(Reflector.callMethod(nativeContext, "getExtras"), "get", key));
    }

    private static Method unique(Class<?> type, String name, int parameters) {
        Method found = null;
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != parameters) continue;
            if (found != null) throw new IllegalStateException("Ambiguous native " + name);
            found = method;
        }
        if (found == null) throw new IllegalStateException("Missing native " + name);
        return found;
    }

    interface Sender { Object send(Object event, Consumer<Object> resume); }

    /** Keep the suspending channel contract and propagate Kotlin Result failures unchanged. */
    static Object sendIteration(Object ids, Object event, Object suspended, Sender sender,
        Consumer<Object> resume, Predicate<Object> failed) {
        Object result = sender.send(event, value -> resume.accept(failed.test(value) ? value : ids));
        return result == suspended ? suspended : ids;
    }

    private NativeConversationLimitsHook() { }
}
