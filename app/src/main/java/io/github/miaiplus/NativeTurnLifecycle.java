package io.github.miaiplus;

import android.content.Context;
import android.os.Bundle;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** End only the screen task owned by the host turn whose model budget was exhausted. */
final class NativeTurnLifecycle {
    private static final String CONTINUE = "CONTINUE：控屏尚未完成。";
    private static final Pattern SESSION = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    private static final Bindings bindings = new Bindings();

    static void observe(Context context, Object executionContext, String text) {
        Turn turn = turn(executionContext);
        if (turn == null) return;
        String plain = resultText(text);
        boolean continuing = plain != null && plain.startsWith(CONTINUE);
        String session = continuing ? continuationSession(plain) : null;
        if (continuing && session == null) return;
        String previous = bindings.current(turn);
        if (!continuing && previous == null) return;
        try {
            Bundle state = context.getContentResolver().call(BridgeProvider.URI, "screen_state", null, null);
            if (state == null) return;
            if (continuing && state.getBoolean("active") && session.equals(state.getString("native_session")))
                bindings.record(turn, state.getString("screen_task"));
            else if (!continuing && (!state.getBoolean("active") || !previous.equals(state.getString("screen_task"))))
                bindings.retire(turn, previous);
        } catch (RuntimeException failure) {
            android.util.Log.e("MiAIPlus", "Cannot bind screen task to native model turn", failure);
        }
    }

    static void install(Context context, ClassLoader loader, List<HookCallback.Unhook> hooks) {
        Class<?> guard = Reflector.findClass(HostCompatibility.choose("mj.h", "xj.h"), loader);
        Object companion = Reflector.getStaticObjectField(guard, "b");
        Object clearedKey = Reflector.callMethod(companion, "getTOOLS_FORCE_CLEARED_KEY");
        Class<?> stop = Reflector.findClass("kj.i$b", loader);
        Object exhausted = Reflector.getStaticObjectField(Reflector.findClass("oj.b", loader), "MAX_ITERATIONS");
        Method shouldStop = null;
        for (Method method : guard.getDeclaredMethods()) {
            if (!method.getName().equals("shouldStop") || method.getParameterCount() != 4) continue;
            if (shouldStop != null) throw new IllegalStateException("Ambiguous native iteration guard");
            shouldStop = method;
        }
        if (shouldStop == null) throw new IllegalStateException("Missing native iteration guard");
        hooks.add(Hooks.hookMethod(shouldStop, new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable()) return;
                try {
                    Object result = p.getResult();
                    boolean stopped = stop.isInstance(result)
                        && exhausted.equals(Reflector.callMethod(result, "getReason"));
                    Object extras = Reflector.callMethod(p.args[0], "getExtras");
                    boolean toolsCleared = Boolean.TRUE.equals(Reflector.callMethod(extras, "get", clearedKey));
                    if (!stopped && !toolsCleared) return;
                    endTurn(context, Reflector.callMethod(p.args[0], "getExecutionContext"), "model_exhausted");
                } catch (RuntimeException failure) {
                    android.util.Log.e("MiAIPlus", "Cannot release exhausted native model screen task", failure);
                }
            }
        }));
        Class<?> agentContext = Reflector.findClass("hj.e", loader);
        try {
            hooks.add(Hooks.hookMethod(agentContext.getDeclaredMethod("release"), new HookCallback() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        endTurn(context, Reflector.callMethod(p.thisObject, "getExecutionContext"), "model_finished");
                    } catch (RuntimeException failure) {
                        android.util.Log.e("MiAIPlus", "Cannot release unfinished native model screen task", failure);
                    }
                }
            }));
        } catch (NoSuchMethodException missing) { throw new IllegalStateException("Missing native turn cleanup", missing); }
    }

    private static void endTurn(Context context, Object executionContext, String method) {
        String task = bindings.retire(turn(executionContext));
        if (task != null) context.getContentResolver().call(BridgeProvider.URI, method, task, null);
    }

    private static Turn turn(Object executionContext) {
        if (executionContext == null) return null;
        try {
            return Turn.of((String)Reflector.callMethod(executionContext, "getContextVarIdentity"),
                (String)Reflector.callMethod(executionContext, "getRequestId"));
        } catch (RuntimeException unavailable) { return null; }
    }

    static void dispose() { bindings.clear(); }

    static String continuationSession(String text) {
        String plain = resultText(text);
        if (plain == null || !plain.startsWith(CONTINUE)) return null;
        int header = plain.indexOf("\n会话=");
        if (header < 0) return null;
        int start = header + "\n会话=".length();
        int end = start + 36;
        if (plain.length() < end + "；目标=".length() || !plain.startsWith("；目标=", end)) return null;
        String session = plain.substring(start, end);
        return SESSION.matcher(session).matches() ? session : null;
    }

    private static String resultText(String text) {
        if (text == null || !text.startsWith("{")) return text;
        try {
            JSONObject value = new JSONObject(text);
            if (value.optInt("__envelope__") == 1) {
                Object data = value.opt("data");
                if (data instanceof String plain) return plain;
                if (data instanceof JSONObject result) value = result;
            }
            return value.optString("type").equals("text") && value.opt("text") instanceof String plain ? plain : null;
        } catch (org.json.JSONException invalid) { return null; }
    }

    record Turn(String identity, String request) {
        static Turn of(String identity, String request) {
            return identity == null || identity.isBlank() || request == null || request.isBlank()
                ? null : new Turn(identity, request);
        }
    }

    /** Tokens survive no longer than their exact request; provider checks them again before cancellation. */
    static final class Bindings {
        private final LinkedHashMap<Turn, String> tasks = new LinkedHashMap<>();

        synchronized void record(Turn turn, String task) {
            if (turn == null || task == null || task.isBlank()) return;
            tasks.put(turn, task);
            while (tasks.size() > 64) tasks.remove(tasks.keySet().iterator().next());
        }

        synchronized String retire(Turn turn) { return turn == null ? null : tasks.remove(turn); }
        synchronized String current(Turn turn) { return turn == null ? null : tasks.get(turn); }
        synchronized void retire(Turn turn, String expected) {
            if (turn != null && expected != null) tasks.remove(turn, expected);
        }
        synchronized void clear() { tasks.clear(); }
    }

    private NativeTurnLifecycle() { }
}
