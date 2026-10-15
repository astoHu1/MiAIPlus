package io.github.miaiplus.hook;

import java.lang.reflect.Executable;
import java.util.HashMap;
import java.util.Map;

/** Project-local callback state, adapted to libxposed's interceptor chain by Hooks. */
public abstract class HookCallback {
    final int priority;
    public HookCallback() { this(50); }
    public HookCallback(int priority) { this.priority = priority; }
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
    public interface Unhook { void unhook(); }
    public static final class MethodHookParam {
        public final Object thisObject;
        public final Executable method;
        public Object[] args;
        private Object result;
        private Throwable failure;
        boolean early;
        private final Map<String, Object> extras = new HashMap<>();
        MethodHookParam(Object receiver, Executable method, Object[] args) { this.thisObject = receiver; this.method = method; this.args = args; }
        public Object getResult() { return result; }
        public Throwable getThrowable() { return failure; }
        public boolean hasThrowable() { return failure != null; }
        public void setResult(Object value) { result = value; failure = null; early = true; }
        public void setThrowable(Throwable value) { failure = value; result = null; early = true; }
        public void setObjectExtra(String key, Object value) { extras.put(key, value); }
        public Object getObjectExtra(String key) { return extras.get(key); }
    }
}
