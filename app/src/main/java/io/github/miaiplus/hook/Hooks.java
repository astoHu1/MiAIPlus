package io.github.miaiplus.hook;

import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** All hooks, including debug hooks, use API 102 and belong to a single module generation. */
public final class Hooks {
    private static XposedInterface api;
    private static final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private static final AtomicInteger calls = new AtomicInteger();
    private static boolean retiring;
    private Hooks() {}
    public static synchronized void initialize(XposedInterface framework) { api = framework; retiring = false; }
    public static synchronized void enter() {
        if (retiring) throw new IllegalStateException("MiAIPlus 正在重载，请稍后重试");
        calls.incrementAndGet();
    }
    public static void leave() { calls.decrementAndGet(); }
    public static synchronized HookCallback.Unhook hookMethod(Executable method, HookCallback callback) {
        if (retiring) throw new IllegalStateException("Module generation is retiring");
        var handle = api.hook(method).setPriority(callback.priority).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
            boolean skip;
            synchronized (Hooks.class) { skip = retiring; if (!skip) calls.incrementAndGet(); }
            if (skip) {
                if (method.getName().equals("controlApp")) throw new IllegalStateException("MiAIPlus 正在重载，请稍后重试");
                return chain.proceed();
            }
            try {
                var param = new HookCallback.MethodHookParam(chain.getThisObject(), chain.getExecutable(), chain.getArgs().toArray());
                callback.beforeHookedMethod(param);
                if (!param.early) {
                    try { param.setResult(chain.proceed(param.args)); }
                    catch (Throwable failure) { param.setThrowable(failure); }
                }
                callback.afterHookedMethod(param);
                if (param.hasThrowable()) throw param.getThrowable();
                return param.getResult();
            } finally { calls.decrementAndGet(); }
        });
        handles.add(handle);
        return () -> { handle.unhook(); synchronized (Hooks.class) { handles.remove(handle); } };
    }
    public static List<HookCallback.Unhook> hookAllConstructors(Class<?> type, HookCallback callback) {
        List<HookCallback.Unhook> result = new ArrayList<>();
        try {
            for (var constructor : type.getDeclaredConstructors()) result.add(hookMethod(constructor, callback));
            return result;
        } catch (Throwable failure) { result.forEach(HookCallback.Unhook::unhook); throw failure; }
    }
    public static Object invokeOriginalMethod(Method method, Object receiver, Object[] args) throws ReflectiveOperationException {
        return api.getInvoker(method).setType(XposedInterface.Invoker.Type.ORIGIN).invoke(receiver, args);
    }
    /** Freeze before cleanup; a busy synchronous hook rejects reload without changing its state. */
    public static synchronized boolean freeze() {
        if (calls.get() != 0) return false;
        retiring = true; return true;
    }
    public static synchronized void thaw() { retiring = false; }
    public static synchronized void unhookAll() {
        for (var handle : handles) handle.unhook();
        handles.clear();
    }
    public static void log(String message) { api.log(Log.INFO, "MiAIPlus", message); }
    public static void log(Throwable failure) { api.log(Log.ERROR, "MiAIPlus", failure.getClass().getSimpleName(), failure); }
}
