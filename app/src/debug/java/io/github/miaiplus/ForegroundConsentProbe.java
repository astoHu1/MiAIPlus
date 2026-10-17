package io.github.miaiplus;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
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

/** Debug-only acceptance through real native tools and the host's actual notification action. No model call. */
final class ForegroundConsentProbe {
    private static volatile CountDownLatch ready;
    private ForegroundConsentProbe() { }
    static void signalReady() { CountDownLatch waiting = ready; if (waiting != null) waiting.countDown(); }

    static String run(Context context, ClassLoader loader) throws Exception {
        Object registry = NativePhoneUseTool.registry(context, loader);
        check(registry != null, "native registry unavailable");
        Object tool = call(registry, HostCompatibility.choose("get", "peek"), PhoneUseRouting.BACKGROUND_TOOL);
        check(tool != null, "background native tool unavailable");
        Thread pendingAction = null;
        try {
            String first = text(execute(loader, tool, new JSONObject().put("goal", "仅验证自有测试应用的前后台切换")));
            String session = session(first);
            stage(context, "CONSENT_BASELINE_READY");
            String waited = text(execute(loader, tool, step(session, new JSONObject().put("action", "wait").put("duration_ms", 100))));
            check(waited.contains("执行屏幕=background"), "initial wait changed backend");
            String initial = text(execute(loader, tool, launch(session, "io.github.miaiplus")));
            physical(initial, session);
            check(consentAction(context, true) == null, "first app incorrectly requested consent");
            execute(loader, tool, finish(session));
            stage(context, "CONSENT_INITIAL_DONE");

            for (boolean allow : new boolean[]{false, true}) {
                String started = text(execute(loader, tool, new JSONObject().put("goal", "只使用自有测试页面验证灵动岛同意与拒绝")));
                session = session(started);
                String launched = text(execute(loader, tool, launch(session, "io.github.miaiplus.test")));
                check(launched.contains("执行屏幕=background") && (launched.contains("前台=io.github.miaiplus.test；")
                    || launched.contains("前台=io.github.miaiplus.test/")),
                    "separate background fixture was not launched");
                String pendingSession = session;
                CompletableFuture<Object> result = new CompletableFuture<>();
                Thread action = new Thread(() -> {
                    try { result.complete(execute(loader, tool, launch(pendingSession, "io.github.miaiplus"))); }
                    catch (Throwable failure) { result.completeExceptionally(failure); }
                }, "miaiplus-consent-action");
                pendingAction = action;
                action.start();
                PendingIntent answer = null;
                long deadline = SystemClock.elapsedRealtime() + 15_000;
                while (answer == null && SystemClock.elapsedRealtime() < deadline) {
                    answer = consentAction(context, allow);
                    if (result.isDone()) throw new AssertionError("conflicting launch completed before consent: " + text(result.get()));
                    if (answer == null) Thread.sleep(100);
                }
                check(answer != null, "consent notification action was not posted");
                stage(context, allow ? "CONSENT_ALLOW_READY" : "CONSENT_DENY_READY");
                // This is the exact PendingIntent a user presses in the host focus-island card, including its
                // immutable data URI/token. A direct Provider response would miss receiver/filter regressions.
                answer.send();
                Object completed = result.get(30, TimeUnit.SECONDS);
                action.join(1000);
                check(!action.isAlive(), "consent action worker did not unwind");
                String observed = text(completed);
                if (allow) {
                    physical(observed, session);
                    execute(loader, tool, finish(session));
                } else {
                    check(ForegroundConsent.endsNativeTurn(observed), "denial did not return terminal PAUSE");
                    check(Boolean.TRUE.equals(call(completed, "isDirectAnswer")), "denial result was not native direct answer");
                    verifyStopGate(loader, completed);
                    Bundle state;
                    long cleanupDeadline = SystemClock.elapsedRealtime() + 5000;
                    do {
                        state = context.getContentResolver().call(BridgeProvider.URI, "screen_state", null, null);
                        if (state != null && !state.getBoolean("active")) break;
                        Thread.sleep(100);
                    } while (SystemClock.elapsedRealtime() < cleanupDeadline);
                    check(state != null && !state.getBoolean("active"), "denied session remained active");
                }
                stage(context, allow ? "CONSENT_ALLOW_DONE" : "CONSENT_DENY_DONE");
            }
            return "PROBE PASS: first app handoff after wait; real consent notification deny/allow broadcasts; fresh foreground observation with original background tool; denial direct answer and native STOP gate; no model request";
        } finally {
            ready = null;
            context.getContentResolver().call(BridgeProvider.URI, "stop_screen", null, null);
            if (pendingAction != null && pendingAction.isAlive()) {
                pendingAction.join(10_000);
                check(!pendingAction.isAlive(), "consent action worker did not stop after cancellation");
            }
        }
    }

    private static void physical(String observation, String session) {
        check(session.equals(session(observation)), "handoff replaced the session");
        check(observation.contains("执行屏幕=foreground；会话工具=" + PhoneUseRouting.BACKGROUND_TOOL),
            "handoff lost physical backend or pinned source tool");
        check(observation.contains("前台=io.github.miaiplus；"), "handoff did not obtain a new physical fixture observation");
        check(observation.contains("旧后台截图和坐标已作废"), "handoff did not invalidate the old observation");
    }
    private static PendingIntent consentAction(Context context, boolean allow) {
        for (var posted : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            Notification notification = posted.getNotification();
            if (!"小爱请求使用当前应用".contentEquals(notification.extras.getCharSequence(Notification.EXTRA_TITLE, ""))) continue;
            check(notification.contentIntent == null, "consent card would return to the host Activity");
            Bundle focus = notification.extras.getBundle("miui.focus.actions");
            Notification.Action action = focus == null ? null : focus.getParcelable(
                allow ? "miaiplus_foreground_allow" : "miaiplus_foreground_deny", Notification.Action.class);
            if (action != null) return action.actionIntent;
        }
        return null;
    }
    private static void verifyStopGate(ClassLoader loader, Object denied) {
        Object pending = Reflector.getStaticObjectField(Reflector.findClass("com.aios.osbot.agent.model.ToolStatus", loader), "COMPLETED");
        Object nativeCall = Reflector.newInstance(Reflector.findClass("com.aios.osbot.agent.model.ToolCall", loader),
            UUID.randomUUID().toString(), PhoneUseRouting.BACKGROUND_TOOL, NativePhoneUseTool.json(loader, "{}"),
            text(denied), pending, List.of(), null, null, null);
        Object gate = Reflector.newInstance(Reflector.findClass("kj.g", loader), List.of());
        Object decision = call(gate, "evalShouldStopAfterTool", null, nativeCall, denied, null);
        check(decision != null && decision.getClass().getName().equals(HostCompatibility.name("kj.i$b")), "native stop gate did not terminate denied tool");
        check(Reflector.getStaticObjectField(Reflector.findClass("oj.b", loader), "STOP") == call(decision, "getReason"),
            "native gate returned a different terminal reason");
    }
    private static Object execute(ClassLoader loader, Object tool, JSONObject request) throws Exception {
        CompletableFuture<Object> completion = new CompletableFuture<>();
        Class<?> continuationType = Reflector.findClass("kotlin.coroutines.Continuation", loader);
        Object continuation = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{continuationType}, (proxy, method, args) -> {
            if (method.getName().equals("getContext"))
                return Reflector.getStaticObjectField(Reflector.findClass("kotlin.coroutines.EmptyCoroutineContext", loader), "INSTANCE");
            if (method.getName().equals("resumeWith")) { completion.complete(args[0]); return null; }
            return "consent-probe-continuation";
        });
        Object value = call(tool, "execute", NativePhoneUseTool.json(loader, request.toString()), null, continuation);
        Object suspended = Reflector.callStaticMethod(Reflector.findClass("kotlin.coroutines.intrinsics.IntrinsicsKt", loader), "getCOROUTINE_SUSPENDED");
        Object result = value == suspended ? completion.get(90, TimeUnit.SECONDS) : value;
        String observed = text(result);
        if (observed.startsWith("CONTINUE")) {
            List<?> images = (List<?>)call(result, "getImages");
            check(images != null && !images.isEmpty(), "fresh observation has no native screenshot");
            check(!Boolean.TRUE.equals(call(result, "isDirectAnswer")), "continuing observation incorrectly ended its turn");
        }
        return result;
    }
    private static String text(Object result) { return String.valueOf(call(result, "getResult")); }
    private static JSONObject launch(String session, String pkg) throws Exception {
        return step(session, new JSONObject().put("action", "launch").put("app", pkg));
    }
    private static JSONObject finish(String session) throws Exception {
        return step(session, new JSONObject().put("action", "finish").put("message", "自有测试页面验证结束"));
    }
    private static JSONObject step(String session, JSONObject action) throws Exception {
        return new JSONObject().put("session", session).put("action", action);
    }
    private static String session(String observation) {
        var match = java.util.regex.Pattern.compile("会话=([a-f0-9-]{36})").matcher(observation);
        check(observation.startsWith("CONTINUE") && match.find(), "no continuing native session");
        return match.group(1);
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
