package io.github.miaiplus;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import io.github.miaiplus.hook.Reflector;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Calls the actual typed tool and host stop gate; only waits on an owned fixture, without a model. */
final class PhoneUseTerminalProbe {
    private static final int NOTIFICATION = 0x4d494149;
    private static volatile CountDownLatch ready;
    private PhoneUseTerminalProbe() { }
    static void signalReady() { CountDownLatch waiting = ready; if (waiting != null) waiting.countDown(); }

    static String run(Context context, ClassLoader loader) throws Exception {
        Object registry = NativePhoneUseTool.registry(context, loader);
        check(registry != null, "native registry unavailable");
        Object tool = call(registry, HostCompatibility.choose("get", "peek"), PhoneUseRouting.FOREGROUND_TOOL);
        check(tool != null, "foreground native tool unavailable");
        try {
            Object started = execute(loader, tool, new JSONObject().put("goal", "仅在自有测试页面等待，验证控屏步数与错误终态"));
            continuing(loader, started);
            String session = session(text(started));
            check(text(started).contains("前台=io.github.miaiplus；"), "owned physical fixture was not observed");
            for (int i = 1; i <= 2; i++) {
                Object waited = execute(loader, tool, waitStep(session, 100 + i));
                continuing(loader, waited);
                check(session.equals(session(text(waited))), "wait replaced the original session");
                check(text(waited).contains("剩余动作步数=" + (2 - i)), "remaining action budget was not reported");
                if (i == 2) check(text(waited).contains("只允许finish、take_over或ask_user"), "last observation did not restrict further actions");
            }
            Object exhausted = execute(loader, tool, waitStep(session, 103));
            permanentFailure(loader, exhausted);
            terminal(loader, exhausted, "budget exhaustion");
            check(text(exhausted).contains("最大步数"), "budget exhaustion lost its cause");
            Bundle ended = ended(context);
            String task = ended.getString("screen_task");
            check(task != null, "terminal task identity missing");
            long postedAt = terminalNotification(context, "手机操作已停止");
            stage(context, "TERMINAL_BUDGET_READY");
            for (int retry = 0; retry < 3; retry++) {
                Object stale = execute(loader, tool, waitStep(session, 110 + retry));
                permanentFailure(loader, stale);
                terminal(loader, stale, "expired session retry");
                check(text(stale).contains("过期"), "stale session lost its actionable cause");
                unchanged(context, task, postedAt);
            }
            Object malformed = execute(loader, tool, new JSONObject());
            permanentFailure(loader, malformed);
            terminal(loader, malformed, "malformed request");
            unchanged(context, task, postedAt);
            stage(context, "TERMINAL_RETRIES_DONE");

            for (String ending : new String[]{"take_over", "ask_user", "finish"}) {
                Object begin = execute(loader, tool, new JSONObject().put("goal", "仅等待自有测试页面并验证" + ending + "收尾"));
                continuing(loader, begin);
                String ownSession = session(text(begin));
                // The last frame must remain available for a truthful finish or manual handoff.
                for (int i = 0; i < 2; i++) continuing(loader, execute(loader, tool, waitStep(ownSession, 120 + i)));
                Object result = execute(loader, tool, step(ownSession, new JSONObject().put("action", ending)
                    .put("message", ending.equals("finish") ? "自有页面等待已完成" : "测试动作预算已用完，需要用户决定后续操作")));
                check(!text(result).startsWith("CONTINUE"), "ending action continued the session");
                if (!ending.equals("finish")) terminal(loader, result, ending);
                ended(context);
                terminalNotification(context, ending.equals("finish") ? "手机操作已结束" : "需要你接管手机");
                stage(context, "TERMINAL_" + ending.toUpperCase(java.util.Locale.ROOT) + "_DONE");
            }
            for (boolean exhaustedModel : new boolean[]{true, false}) {
                Object execution = executionContext(loader);
                Object begin = execute(loader, tool, execution,
                    new JSONObject().put("goal", "仅观察自有页面，验证宿主模型结束后释放未完成控屏"));
                continuing(loader, begin);
                Object agentContext = agentContext(loader, execution);
                if (exhaustedModel) {
                    Object guard = Reflector.newInstance(Reflector.findClass(HostCompatibility.choose("mj.h", "xj.h"), loader));
                    Object response = Reflector.newInstance(Reflector.findClass(HostCompatibility.choose("kj.h", "vj.h"), loader),
                        "", "", List.of(), null, 0, false, java.util.Set.of(), false, 0);
                    for (int iteration : new int[]{41, 61, 205}) {
                        check(call(guard, "shouldStop", agentContext, iteration, response, null) == null,
                            "conversation count-only guard stopped at " + iteration);
                    }
                    Object companion = Reflector.getStaticObjectField(guard.getClass(), "b");
                    Object clearedKey = call(companion, "getTOOLS_FORCE_CLEARED_KEY");
                    call(call(agentContext, "getExtras"), "set", clearedKey, true);
                    Object decision = call(guard, "shouldStop", agentContext, 205, response, null);
                    check(decision != null && decision.getClass().getName().equals(HostCompatibility.name("kj.i$b"))
                        && call(decision, "getReason") == Reflector.getStaticObjectField(Reflector.findClass("oj.b", loader), "MAX_ITERATIONS"),
                        "actual forced-clear guard did not report MAX_ITERATIONS");
                } else call(agentContext, "release");
                Bundle state = ended(context);
                check(state.getString("result", "").contains(exhaustedModel ? "模型" : "结束"), "host model cleanup lost its cause");
                check(state.getString("native_session", "").isEmpty(), "host model cleanup left a native screen session active");
                terminalNotification(context, "手机操作已停止");
                stage(context, exhaustedModel ? "TERMINAL_MODEL_EXHAUSTED_DONE" : "TERMINAL_MODEL_RELEASED_DONE");
            }
            return "PROBE PASS: real foreground tool and native stop gate; final budget observation stays nonterminal; extra action, stale retries, malformed request and manual handoffs stop the turn; stale calls preserve task identity and notification post time; finish/handoff accepted after final observation; conversation guard allows 41/61/205 iterations; actual force-cleared guard and AgentContext.release clean unfinished screen sessions; no model request";
        } finally {
            ready = null;
            context.getContentResolver().call(BridgeProvider.URI, "stop_screen", null, null);
        }
    }

    private static void continuing(ClassLoader loader, Object result) {
        check(text(result).startsWith("CONTINUE"), "expected continuing observation: " + text(result));
        check(!Boolean.TRUE.equals(call(result, "isDirectAnswer")), "continuation incorrectly became direct answer");
        List<?> images = (List<?>)call(result, "getImages");
        check(images != null && !images.isEmpty(), "continuation lost native screenshot");
        // The unmodified gate requires a live AgentContext. This deterministic probe invokes the actual gate
        // only for results that our terminal hook must consume before touching that context.
        check(!PhoneUseResult.endsNativeTurn(text(result)), "ordinary CONTINUE matched the terminal gate predicate");
    }
    private static void permanentFailure(ClassLoader loader, Object result) {
        check(Reflector.findClass("com.aios.osbot.tools.ToolResult$Failure", loader).isInstance(result),
            "terminal tool error was reported as a successful result");
        check(Boolean.FALSE.equals(call(result, "getShouldRetry")), "terminal failure allowed a retry");
        check(Boolean.TRUE.equals(call(result, "getTerminateAgent")), "terminal failure did not terminate the agent");
    }
    private static void terminal(ClassLoader loader, Object result, String scenario) {
        check(stopGate(loader, result), scenario + " failed to stop the actual native tool loop");
    }
    private static boolean stopGate(ClassLoader loader, Object result) {
        Object completed = Reflector.getStaticObjectField(Reflector.findClass("com.aios.osbot.agent.model.ToolStatus", loader), "COMPLETED");
        Object nativeCall = Reflector.newInstance(Reflector.findClass("com.aios.osbot.agent.model.ToolCall", loader),
            UUID.randomUUID().toString(), PhoneUseRouting.FOREGROUND_TOOL, NativePhoneUseTool.json(loader, "{}"),
            text(result), completed, List.of(), null, null, null);
        Object gate = Reflector.newInstance(Reflector.findClass("kj.g", loader), List.of());
        Object decision = call(gate, "evalShouldStopAfterTool", null, nativeCall, result, null);
        if (decision == null || !decision.getClass().getName().equals(HostCompatibility.name("kj.i$b"))) return false;
        return Reflector.getStaticObjectField(Reflector.findClass("oj.b", loader), "STOP") == call(decision, "getReason");
    }
    private static Bundle ended(Context context) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        Bundle state;
        do {
            state = context.getContentResolver().call(BridgeProvider.URI, "screen_state", null, null);
            if (state != null && !state.getBoolean("active") && state.getBoolean("completed")) return state;
            Thread.sleep(80);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("terminal task did not acknowledge completion");
    }
    private static long terminalNotification(Context context, String expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        do {
            for (var posted : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
                if (posted.getId() != NOTIFICATION) continue;
                Notification notification = posted.getNotification();
                if (expected.contentEquals(notification.extras.getCharSequence(Notification.EXTRA_TITLE, ""))
                    && (notification.flags & Notification.FLAG_ONGOING_EVENT) == 0) return posted.getPostTime();
            }
            Thread.sleep(80);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("terminal notification did not settle to " + expected);
    }
    private static void unchanged(Context context, String task, long postedAt) throws Exception {
        Bundle state = ended(context);
        check(task.equals(state.getString("screen_task")), "rejected call created a new screen task");
        check(terminalNotification(context, "手机操作已停止") == postedAt, "rejected call reposted the terminal focus card");
    }
    private static JSONObject waitStep(String session, int duration) throws Exception {
        return step(session, new JSONObject().put("action", "wait").put("duration_ms", duration).put("intent", "等待自有测试页面"));
    }
    private static JSONObject step(String session, JSONObject action) throws Exception {
        return new JSONObject().put("session", session).put("action", action);
    }
    private static String session(String observation) {
        var match = java.util.regex.Pattern.compile("会话=([a-f0-9-]{36})").matcher(observation);
        check(match.find(), "continuing session ID missing");
        return match.group(1);
    }
    private static Object execute(ClassLoader loader, Object tool, JSONObject request) throws Exception {
        return execute(loader, tool, null, request);
    }
    private static Object execute(ClassLoader loader, Object tool, Object executionContext, JSONObject request) throws Exception {
        CompletableFuture<Object> completion = new CompletableFuture<>();
        Object continuation = java.lang.reflect.Proxy.newProxyInstance(loader,
            new Class<?>[]{Reflector.findClass("kotlin.coroutines.Continuation", loader)}, (proxy, method, args) -> {
                if (method.getName().equals("getContext")) return Reflector.getStaticObjectField(
                    Reflector.findClass("kotlin.coroutines.EmptyCoroutineContext", loader), "INSTANCE");
                if (method.getName().equals("resumeWith")) { completion.complete(args[0]); return null; }
                return "terminal-probe-continuation";
            });
        Object value = call(tool, "execute", NativePhoneUseTool.json(loader, request.toString()), executionContext, continuation);
        Object suspended = Reflector.callStaticMethod(Reflector.findClass("kotlin.coroutines.intrinsics.IntrinsicsKt", loader), "getCOROUTINE_SUSPENDED");
        return value == suspended ? completion.get(90, TimeUnit.SECONDS) : value;
    }
    static Object executionContext(ClassLoader loader) throws Exception {
        Class<?> permissionType = Reflector.findClass("sr.q0", loader);
        Object permission = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{permissionType}, (proxy, method, args) -> {
            if (method.getName().equals("toString")) return "MiAIPlus terminal probe permissions";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            throw new AssertionError("terminal probe unexpectedly requested a tool permission");
        });
        for (var constructor : Reflector.findClass("com.aios.osbot.tools.ExecutionContext", loader).getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length != (HostCompatibility.modern() ? 36 : 34)
                || !types[types.length - 1].getName().equals("kotlin.jvm.internal.DefaultConstructorMarker")) continue;
            Object[] values = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == boolean.class) values[i] = false;
                else if (types[i] == int.class) values[i] = 0;
                else if (types[i] == long.class) values[i] = 0L;
            }
            values[0] = "miaiplus-terminal-probe";
            values[1] = "miaiplus-terminal-probe-" + UUID.randomUUID();
            values[2] = UUID.randomUUID().toString();
            values[4] = permission;
            values[types.length - (HostCompatibility.modern() ? 3 : 2)] = ~0x17;
            if (HostCompatibility.modern()) values[34] = -1;
            Object execution = constructor.newInstance(values);
            check(values[1].equals(call(execution, "getContextVarIdentity"))
                && values[2].equals(call(execution, "getRequestId")), "native execution context lost probe turn identity");
            return execution;
        }
        throw new AssertionError("pinned execution context constructor unavailable");
    }
    static Object agentContext(ClassLoader loader, Object execution) throws Exception {
        Object definition = agentDefinition(loader);
        Object agent = Reflector.newInstance(Reflector.findClass(HostCompatibility.choose("rj.d0", "ck.d0"), loader),
            definition, "", null, null, null, null, null);
        Object history = Reflector.newInstance(Reflector.findClass(HostCompatibility.choose("hj.i", "sj.i"), loader), List.of());
        Object extras = Reflector.newInstance(Reflector.findClass(HostCompatibility.choose("hj.p", "sj.p"), loader));
        Object[] configArgs = new Object[]{"fixture-model", 0.0, null, 1.0, "", null, "", "", false,
            "openai", null, false, Reflector.newInstance(Reflector.findClass("sm.f", loader)), null, false};
        if (HostCompatibility.modern()) {
            configArgs = java.util.Arrays.copyOf(configArgs, 16);
            configArgs[15] = false;
        }
        Object config = Reflector.newInstance(Reflector.findClass("sm.a", loader), configArgs);
        return Reflector.newInstance(Reflector.findClass("hj.e", loader), agent, "osbot.main", null,
            UUID.randomUUID().toString(), history, config, List.of(), execution, 1024, 2, 0, null, null, null, null, extras);
    }
    private static Object agentDefinition(ClassLoader loader) throws Exception {
        Class<?> definition = Reflector.findClass("com.aios.osbot.agent.unified.AgentDefinition", loader);
        // The host definition requires id/name/description; the Kotlin constructor supplies every later field.
        // Match its canonical prefix and computed default masks rather than assuming a nonexistent zero-arg ctor.
        for (var canonical : definition.getConstructors()) {
            Class<?>[] fields = canonical.getParameterTypes();
            if (fields.length < 4 || fields[0] != String.class || fields[1] != String.class
                || fields[2] != String.class || fields[3] != boolean.class
                || fields[fields.length - 1].getName().equals("kotlin.jvm.internal.DefaultConstructorMarker")) continue;
            int masks = (fields.length + 31) / 32;
            Class<?>[] signature = java.util.Arrays.copyOf(fields, fields.length + masks + 1);
            for (int i = fields.length; i < fields.length + masks; i++) signature[i] = int.class;
            signature[signature.length - 1] = Reflector.findClass("kotlin.jvm.internal.DefaultConstructorMarker", loader);
            var defaults = definition.getConstructor(signature);
            Object[] values = new Object[signature.length];
            for (int i = 0; i < fields.length; i++) {
                if (fields[i] == boolean.class) values[i] = false;
                else if (fields[i] == int.class) values[i] = 0;
            }
            values[0] = "osbot.main";
            values[1] = "MiAIPlus terminal probe";
            values[2] = "Only observes the owned fixture; no model request";
            for (int i = 0; i < masks; i++) values[fields.length + i] = i == 0 ? ~7 : -1;
            return defaults.newInstance(values);
        }
        throw new AssertionError("pinned AgentDefinition constructor unavailable");
    }
    private static String text(Object result) {
        for (String accessor : new String[]{"getResult", "getError", "getMessage"}) {
            try { return String.valueOf(result.getClass().getMethod(accessor).invoke(result)); }
            catch (ReflectiveOperationException absent) { }
        }
        throw new AssertionError("unknown native ToolResult text accessor: " + result.getClass().getName());
    }
    private static void stage(Context context, String name) throws Exception {
        CountDownLatch waiting = new CountDownLatch(1); ready = waiting;
        Bundle report = new Bundle(); report.putString("report", name);
        context.getContentResolver().call(BridgeProvider.URI, "hook_report", null, report);
        check(waiting.await(20, TimeUnit.SECONDS), "device runner did not acknowledge " + name);
        ready = null;
    }
    private static Object call(Object receiver, String method, Object... args) { return Reflector.callMethod(receiver, method, args); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
