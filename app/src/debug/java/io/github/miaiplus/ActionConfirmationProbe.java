package io.github.miaiplus;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import io.github.miaiplus.hook.Reflector;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real registered foreground tools and focus actions; only touches the module's own harmless button. */
final class ActionConfirmationProbe {
    private static final String EXPLANATION = "仅点击 MiAIPlus 自有测试页面的按钮一次，不发送、不付款、不删除。\n"
        + "完整说明中的引号\"与换行必须保留；" + "此内容是确认说明完整性测试。".repeat(12) + "【说明结尾】";
    private static volatile CountDownLatch ready;
    private static volatile boolean cancelled;
    private ActionConfirmationProbe() { }
    static void signalReady() { CountDownLatch waiting = ready; if (waiting != null) waiting.countDown(); }
    static void signalCancel() { cancelled = true; signalReady(); }

    static String run(Context context, ClassLoader loader) throws Exception {
        cancelled = false;
        Object registry = NativePhoneUseTool.registry(context, loader);
        check(registry != null, "native registry unavailable");
        Object tool = call(registry, HostCompatibility.choose("get", "peek"), PhoneUseRouting.FOREGROUND_TOOL);
        check(tool != null, "foreground native tool unavailable");
        Thread worker = null;
        PendingIntent priorAllow = null, priorDeny = null;
        try {
            for (String scenario : new String[]{"ALLOW", "DENY", "CANCEL", "TIMEOUT"}) {
                check(!cancelled, "device runner cancelled confirmation probe");
                String observed = text(execute(loader, tool, new JSONObject().put("goal", "仅验证自有测试按钮的灵动岛确认，不操作其他应用")));
                String session = session(observed);
                check(observed.contains("执行屏幕=foreground") && observed.contains("前台=io.github.miaiplus；"),
                    "confirmation did not observe the self-owned physical fixture");
                observed = text(execute(loader, tool, step(session,
                    new JSONObject().put("action", "wait").put("duration_ms", 800))));
                JSONObject request = tap(session, observed);
                CompletableFuture<Object> completion = new CompletableFuture<>();
                worker = new Thread(() -> {
                    try { completion.complete(execute(loader, tool, request)); }
                    catch (Throwable failure) { completion.completeExceptionally(failure); }
                }, "miaiplus-confirmation-probe-action");
                worker.start();
                Notification notification = null;
                long postedDeadline = SystemClock.elapsedRealtime() + 15_000;
                while (notification == null && SystemClock.elapsedRealtime() < postedDeadline) {
                    check(!cancelled, "device runner cancelled confirmation probe");
                    notification = pendingNotification(context);
                    check(!completion.isDone(), "action completed before user confirmation");
                    if (notification == null) Thread.sleep(70);
                }
                check(notification != null, "confirmation focus notification was not posted");
                Bundle actions = validateNotification(notification);
                PendingIntent allow = action(actions, "miaiplus_action_allow");
                PendingIntent deny = action(actions, "miaiplus_action_deny");
                PendingIntent stop = action(actions, "miaiplus_stop");
                if (priorAllow != null) {
                    check(!priorAllow.equals(allow) && !priorDeny.equals(deny), "new session reused a confirmation PendingIntent");
                    cancelled(priorAllow); cancelled(priorDeny);
                    check(!completion.isDone(), "old session's answer affected the new pending action");
                }
                long pendingAt = SystemClock.elapsedRealtime();
                stage(context, "ACTION_CONFIRMATION_" + scenario + "_READY");
                check(!completion.isDone(), "confirmation completed while the runner verified waiting UI");
                switch (scenario) {
                    case "ALLOW" -> allow.send();
                    case "DENY" -> deny.send();
                    case "CANCEL" -> stop.send();
                    case "TIMEOUT" -> { /* Real deadline: never shorten the production consent timeout. */ }
                    default -> throw new AssertionError("unknown confirmation scenario");
                }
                Object completed = completion.get(75, TimeUnit.SECONDS);
                worker.join(1000);
                check(!worker.isAlive(), "confirmation worker did not unwind");
                String result = text(completed);
                if (scenario.equals("ALLOW")) {
                    check(session.equals(session(result)) && !result.contains("本步未执行"), "approved harmless tap did not continue");
                    execute(loader, tool, step(session, new JSONObject().put("action", "finish").put("message", "自有确认验证完成")));
                } else {
                    check(!result.startsWith("CONTINUE"), "rejected/cancelled/expired confirmation kept its session alive");
                    if (scenario.equals("TIMEOUT")) check(SystemClock.elapsedRealtime() - pendingAt >= 55_000,
                        "confirmation expired without exercising the real timeout");
                }
                long cleanup = SystemClock.elapsedRealtime() + 5000;
                Bundle state;
                do {
                    state = context.getContentResolver().call(BridgeProvider.URI, "screen_state", null, null);
                    if (state != null && !state.getBoolean("active") && pendingNotification(context) == null) break;
                    Thread.sleep(100);
                } while (SystemClock.elapsedRealtime() < cleanup);
                check(state != null && !state.getBoolean("active"), "confirmation session remained active after completion");
                check(pendingNotification(context) == null, "completed confirmation notification remained actionable");
                cancelled(allow); cancelled(deny);
                priorAllow = allow; priorDeny = deny;
                stage(context, "ACTION_CONFIRMATION_" + scenario + "_DONE");
            }
            return "PROBE PASS: registered foreground tool sensitive-action confirmation; actual focus allow/deny/stop PendingIntents; real 60s timeout; full explanation and native island actions; one-use retired answer intents; harmless owned fixture only; no model request";
        } finally {
            ready = null;
            context.getContentResolver().call(BridgeProvider.URI, "stop_screen", null, null);
            if (worker != null && worker.isAlive()) {
                worker.join(10_000);
                check(!worker.isAlive(), "confirmation worker did not stop after cleanup");
            }
        }
    }
    private static Notification pendingNotification(Context context) {
        for (var posted : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            Notification notification = posted.getNotification();
            if ("小爱请求确认操作".contentEquals(notification.extras.getCharSequence(Notification.EXTRA_TITLE, ""))) return notification;
        }
        return null;
    }
    private static Bundle validateNotification(Notification notification) throws Exception {
        check(notification.contentIntent == null, "confirmation would open the host Activity");
        check((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0, "confirmation card is not ongoing");
        check(EXPLANATION.contentEquals(notification.extras.getCharSequence(Notification.EXTRA_TEXT, "")), "confirmation explanation was truncated");
        check(EXPLANATION.contentEquals(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT, "")), "expanded explanation was truncated");
        JSONObject params = new JSONObject(notification.extras.getString("miui.focus.param", ""));
        JSONObject v2 = params.getJSONObject("param_v2");
        check(containsExplanation(params), "native focus JSON lost the full explanation");
        JSONArray controls = v2.getJSONArray("actions");
        check(controls.length() == 2, "confirmation island has unexpected controls");
        check(controls.getJSONObject(0).getString("action").equals("miaiplus_action_allow")
            && controls.getJSONObject(0).getString("actionTitle").equals("确认本次操作"), "island confirm control missing");
        check(controls.getJSONObject(1).getString("action").equals("miaiplus_action_deny")
            && controls.getJSONObject(1).getString("actionTitle").equals("停止任务"), "island stop control missing");
        Bundle actions = notification.extras.getBundle("miui.focus.actions");
        check(actions != null, "native focus action mapping missing");
        check(notification.actions != null && notification.actions.length == 3, "standard notification confirmation controls missing");
        check(notification.actions[0].isAuthenticationRequired()
            && !notification.actions[1].isAuthenticationRequired(), "confirmation unlock requirement missing");
        check(action(actions, "miaiplus_action_allow").isImmutable()
            && action(actions, "miaiplus_action_deny").isImmutable(), "confirmation action is mutable");
        return actions;
    }
    private static boolean containsExplanation(Object value) throws Exception {
        if (value instanceof JSONObject object) {
            for (var keys = object.keys(); keys.hasNext();) if (containsExplanation(object.get(keys.next()))) return true;
        } else if (value instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) if (containsExplanation(array.get(i))) return true;
        } else if (value instanceof String string) {
            if (string.equals(EXPLANATION)) return true;
            if (string.startsWith("{")) try { return containsExplanation(new JSONObject(string)); }
            catch (org.json.JSONException ignored) { }
        }
        return false;
    }
    private static PendingIntent action(Bundle actions, String key) {
        Notification.Action action = actions.getParcelable(key, Notification.Action.class);
        check(action != null && action.actionIntent != null, "missing focus PendingIntent: " + key);
        return action.actionIntent;
    }
    private static void cancelled(PendingIntent pending) throws Exception {
        try { pending.send(); throw new AssertionError("retired confirmation PendingIntent remained usable"); }
        catch (PendingIntent.CanceledException expected) { }
    }
    private static JSONObject tap(String session, String observation) throws Exception {
        var bounds = Pattern.compile("\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*]\\s*\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*]");
        for (String line : observation.split("\\n")) {
            if (!line.contains("稳健点击目标") || !line.contains("clickable")) continue;
            var match = bounds.matcher(line);
            check(match.find(), "self-owned confirmation target has no bounds");
            double x = (Double.parseDouble(match.group(1)) + Double.parseDouble(match.group(3))) / 2;
            double y = (Double.parseDouble(match.group(2)) + Double.parseDouble(match.group(4))) / 2;
            return step(session, new JSONObject().put("action", "tap").put("element", new JSONArray().put(x).put(y))
                .put("requires_confirmation", true).put("message", EXPLANATION).put("intent", "点击自有测试按钮"))
                .put("coordinate_space", "screen_pixels");
        }
        throw new AssertionError("self-owned confirmation target absent from observation");
    }
    private static Object execute(ClassLoader loader, Object tool, JSONObject request) throws Exception {
        CompletableFuture<Object> completion = new CompletableFuture<>();
        Object continuation = java.lang.reflect.Proxy.newProxyInstance(loader,
            new Class<?>[]{Reflector.findClass("kotlin.coroutines.Continuation", loader)}, (proxy, method, args) -> {
                if (method.getName().equals("getContext")) return Reflector.getStaticObjectField(
                    Reflector.findClass("kotlin.coroutines.EmptyCoroutineContext", loader), "INSTANCE");
                if (method.getName().equals("resumeWith")) { completion.complete(args[0]); return null; }
                return "action-confirmation-probe-continuation";
            });
        Object value = call(tool, "execute", NativePhoneUseTool.json(loader, request.toString()), null, continuation);
        Object suspended = Reflector.callStaticMethod(Reflector.findClass("kotlin.coroutines.intrinsics.IntrinsicsKt", loader), "getCOROUTINE_SUSPENDED");
        Object result = value == suspended ? completion.get(100, TimeUnit.SECONDS) : value;
        if (text(result).startsWith("CONTINUE")) {
            List<?> images = (List<?>)call(result, "getImages");
            check(images != null && !images.isEmpty(), "confirmation continuation has no native screenshot");
        }
        return result;
    }
    private static String session(String observed) {
        var match = Pattern.compile("会话=([a-f0-9-]{36})").matcher(observed);
        check(observed.startsWith("CONTINUE") && match.find(), "native session did not continue");
        return match.group(1);
    }
    private static JSONObject step(String session, JSONObject action) throws Exception { return new JSONObject().put("session", session).put("action", action); }
    private static String text(Object result) { return String.valueOf(call(result, "getResult")); }
    private static Object call(Object receiver, String method, Object... args) { return Reflector.callMethod(receiver, method, args); }
    private static void stage(Context context, String name) throws Exception {
        CountDownLatch waiting = new CountDownLatch(1); ready = waiting;
        Bundle report = new Bundle(); report.putString("report", name);
        context.getContentResolver().call(BridgeProvider.URI, "hook_report", null, report);
        check(waiting.await(20, TimeUnit.SECONDS), "device runner did not acknowledge " + name);
        ready = null;
        check(!cancelled, "device runner cancelled confirmation probe");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
