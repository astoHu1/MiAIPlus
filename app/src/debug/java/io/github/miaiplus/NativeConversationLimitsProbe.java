package io.github.miaiplus;

import android.content.Context;
import io.github.miaiplus.hook.Reflector;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory calls to the pinned real host methods; no model, UI, database write or device input. */
final class NativeConversationLimitsProbe {
    static String run(Context context, ClassLoader loader) throws Exception {
        Object execution = PhoneUseTerminalProbe.executionContext(loader);
        Object nativeContext = PhoneUseTerminalProbe.agentContext(loader, execution);
        Object guard = Reflector.newInstance(Reflector.findClass(HostCompatibility.choose("mj.h", "xj.h"), loader));
        List<?> messages = List.of();
        Object tools = Reflector.callMethod(nativeContext, "getTools");
        for (int count : new int[]{41, 61, 205, 1001}) {
            check(Reflector.callMethod(guard, "beforeModelCall", nativeContext, count, messages, null) == messages,
                "count guard changed model messages at " + count);
            check(Reflector.callMethod(nativeContext, "getTools") == tools, "count guard cleared tools at " + count);
        }
        Class<?> agentClass = Reflector.findClass(HostCompatibility.choose("fj.e", "qj.e"), loader);
        Method begin = null;
        for (Method method : agentClass.getDeclaredMethods()) {
            if (method.getName().equals("e") && method.getParameterCount() == 4
                && method.getParameterTypes()[1] == nativeContext.getClass()
                && method.getParameterTypes()[2] == int.class) begin = method;
        }
        check(begin != null, "beginIteration unavailable");
        // beginIteration has no receiver-field reads. Avoid constructing or starting any model client.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        var unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object receiver = unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafeField.get(null), agentClass);
        Class<?> continuation = Reflector.findClass("kotlin.coroutines.Continuation", loader);
        Object suspended = Reflector.callStaticMethod(Reflector.findClass(
            "kotlin.coroutines.intrinsics.IntrinsicsKt", loader), "getCOROUTINE_SUSPENDED");
        Object unit = Reflector.getStaticObjectField(Reflector.findClass("kotlin.Unit", loader), "INSTANCE");
        AtomicReference<Object> sent = new AtomicReference<>(), pending = new AtomicReference<>(), completed = new AtomicReference<>();
        AtomicReference<String> mode = new AtomicReference<>("immediate");
        CancellationException cancelled = new CancellationException("owned conversation limits probe");
        Object channel = Proxy.newProxyInstance(loader, new Class<?>[]{begin.getParameterTypes()[0]}, (proxy, method, args) -> {
            if (!method.getName().equals("send")) throw new AssertionError("unexpected channel operation: " + method);
            sent.set(args[0]);
            if (mode.get().equals("throw")) throw cancelled;
            if (mode.get().equals("suspend")) { pending.set(args[1]); return suspended; }
            return unit;
        });
        Object completion = Proxy.newProxyInstance(loader, new Class<?>[]{continuation}, (proxy, method, args) -> {
            if (method.getName().equals("getContext")) return Reflector.getStaticObjectField(
                Reflector.findClass("kotlin.coroutines.EmptyCoroutineContext", loader), "INSTANCE");
            if (method.getName().equals("resumeWith")) { completed.set(args[0]); return null; }
            return "MiAIPlus conversation limits probe";
        });
        begin.setAccessible(true);
        for (int count : new int[]{41, 61, 205, 1001}) {
            Object ids = begin.invoke(receiver, channel, nativeContext, count, completion);
            check(ids != null && ids != suspended, "absolute guard stopped at " + count);
            check((Integer)Reflector.callMethod(sent.get(), "getIteration") == count, "iteration event lost actual count");
            check(Reflector.callMethod(nativeContext, "getSessionId").equals(
                Reflector.callMethod(sent.get(), "getSessionId")), "iteration event changed session");
            check(completed.get() == null, "synchronous send resumed continuation");
        }
        mode.set("suspend");
        check(begin.invoke(receiver, channel, nativeContext, 205, completion) == suspended, "send lost suspended marker");
        check(completed.get() == null, "suspended send completed early");
        Reflector.callMethod(pending.get(), "resumeWith", unit);
        check(completed.get() != null && completed.get().getClass().getName().equals(
            HostCompatibility.choose("fj.e$f", "qj.e$f")), "suspended send did not return iteration IDs");
        completed.set(null);
        check(begin.invoke(receiver, channel, nativeContext, 205, completion) == suspended, "cancel probe did not suspend");
        Object failure = Reflector.newInstance(Reflector.findClass("kotlin.Result$Failure", loader), cancelled);
        Reflector.callMethod(pending.get(), "resumeWith", failure);
        check(completed.get() == failure, "suspended cancellation was converted to a valid iteration");
        mode.set("throw");
        try {
            begin.invoke(receiver, channel, nativeContext, 205, completion);
            throw new AssertionError("immediate cancellation was swallowed");
        } catch (java.lang.reflect.InvocationTargetException error) {
            check(error.getCause() == cancelled, "immediate cancellation cause changed");
        }
        return "native count guards allow 41/61/205/1001; actual iteration/session events retained; immediate/suspended event sends and cancellation preserved";
    }

    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    private NativeConversationLimitsProbe() { }
}
