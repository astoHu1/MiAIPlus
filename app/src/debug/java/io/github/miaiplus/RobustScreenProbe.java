package io.github.miaiplus;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import io.github.miaiplus.hook.Reflector;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real typed foreground tools and notifications; deterministic self-owned UI, no model request. */
final class RobustScreenProbe {
    private static final int NOTIFICATION = 0x4d494149;
    private static volatile CountDownLatch ready;
    private RobustScreenProbe() { }
    static void signalReady() { CountDownLatch waiting = ready; if (waiting != null) waiting.countDown(); }

    static String run(Context context, ClassLoader loader) throws Exception {
        Object registry = Reflector.getStaticObjectField(Reflector.findClass("com.aios.osbot.tools.ToolRegistry", loader), "instance");
        check(registry != null, "native registry unavailable");
        Object tool = call(registry, "get", PhoneUseRouting.FOREGROUND_TOOL);
        check(tool != null, "foreground native tool unavailable");
        AtomicBoolean monitor = new AtomicBoolean(false);
        AtomicReference<Throwable> notificationFailure = new AtomicReference<>();
        Thread watcher = null;
        try {
            String observed = text(execute(loader, tool, new JSONObject().put("goal", "仅验证自有动态页面的按钮点击与连续控屏通知")));
            String session = session(observed);
            check(observed.contains("执行屏幕=foreground"), "typed foreground session selected wrong backend");
            check(observed.contains("前台=io.github.miaiplus；"), "owned physical fixture was not observed");
            ongoing(context);
            monitor.set(true);
            watcher = new Thread(() -> {
                while (monitor.get()) {
                    try { ongoing(context); Thread.sleep(50); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    catch (Throwable failure) { notificationFailure.compareAndSet(null, failure); return; }
                }
            }, "miaiplus-robust-notification-probe");
            watcher.start();
            // Moving the host behind the fixture can cause an orientation/inset transition. Establish the
            // dynamic-text baseline only after that legitimate geometry change has settled.
            observed = text(execute(loader, tool, step(session,
                new JSONObject().put("action", "wait").put("duration_ms", 800).put("intent", "等待测试页面稳定"))));
            sameSession(observed, session);
            stage(context, "ROBUST_STARTED");
            String before = observed;
            Thread.sleep(1300); // The independent ticker has changed repeatedly since the model observation.
            observed = tap(loader, tool, session, before, "稳健点击目标");
            sameSession(observed, session);
            check(!observed.contains("本步未执行"), "unrelated dynamic text invalidated a stable target");
            stage(context, "ROBUST_STABLE_TAP_DONE");

            before = observed;
            stage(context, "ROBUST_SHIFT_READY");
            Thread.sleep(450);
            observed = tap(loader, tool, session, before, "稳健点击目标");
            replanned(observed, session);
            stage(context, "ROBUST_SHIFT_REPLAN_DONE");
            observed = tap(loader, tool, session, observed, "稳健点击目标");
            sameSession(observed, session);
            check(!observed.contains("本步未执行"), "new shifted target observation remained unusable");
            stage(context, "ROBUST_SHIFT_TAP_DONE");

            before = observed;
            stage(context, "ROBUST_REPLACE_READY");
            Thread.sleep(450);
            observed = tap(loader, tool, session, before, "稳健点击目标");
            replanned(observed, session);
            stage(context, "ROBUST_REPLACE_REPLAN_DONE");
            observed = tap(loader, tool, session, observed, "替换后的目标");
            sameSession(observed, session);
            check(!observed.contains("本步未执行"), "new replacement target observation remained unusable");
            stage(context, "ROBUST_REPLACE_TAP_DONE");

            // Exercise per-call completion and idle gaps, with several watcher polls in each gap.
            for (int i = 0; i < 3; i++) {
                Thread.sleep(1250);
                observed = text(execute(loader, tool, step(session,
                    new JSONObject().put("action", "wait").put("duration_ms", 100).put("intent", "等待自有动态页面"))));
                sameSession(observed, session);
                ongoing(context);
                check(notificationFailure.get() == null, "ongoing notification broke during tool steps: " + notificationFailure.get());
            }
            Thread.sleep(1000);
            ongoing(context);
            monitor.set(false);
            watcher.join(1500);
            check(!watcher.isAlive(), "notification monitor did not stop");
            check(notificationFailure.get() == null, "ongoing notification broke between tool calls: " + notificationFailure.get());
            String ended = text(execute(loader, tool, step(session,
                new JSONObject().put("action", "finish").put("message", "自有动态页面控屏验证完成"))));
            check(ended.contains("模型报告任务完成"), "explicit finish did not complete task");
            long deadline = SystemClock.elapsedRealtime() + 6000;
            while (SystemClock.elapsedRealtime() < deadline && !completed(context)) Thread.sleep(50);
            check(completed(context), "explicit finish did not publish the completion card");
            stage(context, "ROBUST_FINISHED");
            return "PROBE PASS: real typed foreground tools; stable target survives unrelated 100ms text refresh; shifted/replaced targets reject stale touches with same-session fresh observations; replanned taps delivered; 50ms ongoing-notification sampling across every continuation and idle gap; completion only after explicit finish; no model request";
        } finally {
            ready = null;
            monitor.set(false);
            if (watcher != null) watcher.join(1500);
            context.getContentResolver().call(BridgeProvider.URI, "stop_screen", null, null);
        }
    }
    private static void ongoing(Context context) {
        for (var posted : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (posted.getId() != NOTIFICATION) continue;
            Notification notification = posted.getNotification();
            String title = String.valueOf(notification.extras.getCharSequence(Notification.EXTRA_TITLE, ""));
            check(title.equals("正在操作手机"), "continuing session posted terminal/nonongoing title: " + title);
            check((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0, "continuing card lost ongoing flag");
            return;
        }
        throw new AssertionError("continuing session lost its focus notification");
    }
    private static boolean completed(Context context) {
        for (var posted : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (posted.getId() != NOTIFICATION) continue;
            Notification notification = posted.getNotification();
            return "手机操作已结束".contentEquals(notification.extras.getCharSequence(Notification.EXTRA_TITLE, ""))
                && (notification.flags & Notification.FLAG_ONGOING_EVENT) == 0;
        }
        return false;
    }
    private static String tap(ClassLoader loader, Object tool, String session, String observation, String label) throws Exception {
        var bounds = Pattern.compile("\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*]\\s*\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*]");
        for (String line : observation.split("\\n")) {
            if (!line.contains(label) || !line.contains("clickable")) continue;
            var match = bounds.matcher(line);
            check(match.find(), "owned target has no observable bounds");
            double x = (Double.parseDouble(match.group(1)) + Double.parseDouble(match.group(3))) / 2;
            double y = (Double.parseDouble(match.group(2)) + Double.parseDouble(match.group(4))) / 2;
            JSONObject request = step(session, new JSONObject().put("action", "tap")
                .put("element", new JSONArray().put(x).put(y)).put("intent", "点击自有测试目标"));
            request.put("coordinate_space", "screen_pixels");
            return text(execute(loader, tool, request));
        }
        throw new AssertionError("owned button missing from observation: " + label);
    }
    private static void replanned(String observation, String expectedSession) {
        sameSession(observation, expectedSession);
        check(observation.contains("本步未执行"), "stale target was not refused with an actionable fresh observation");
    }
    private static void sameSession(String observation, String expectedSession) {
        check(expectedSession.equals(session(observation)), "continuation replaced the original session");
    }
    private static String session(String observation) {
        var match = Pattern.compile("会话=([a-f0-9-]{36})").matcher(observation);
        check(observation.startsWith("CONTINUE") && match.find(), "tool did not preserve continuing session: "
            + observation.substring(0, Math.min(250, observation.length())));
        return match.group(1);
    }
    private static JSONObject step(String session, JSONObject action) throws Exception {
        return new JSONObject().put("session", session).put("action", action);
    }
    private static Object execute(ClassLoader loader, Object tool, JSONObject request) throws Exception {
        CompletableFuture<Object> completion = new CompletableFuture<>();
        Object continuation = java.lang.reflect.Proxy.newProxyInstance(loader,
            new Class<?>[]{Reflector.findClass("kotlin.coroutines.Continuation", loader)}, (proxy, method, args) -> {
                if (method.getName().equals("getContext")) return Reflector.getStaticObjectField(
                    Reflector.findClass("kotlin.coroutines.EmptyCoroutineContext", loader), "INSTANCE");
                if (method.getName().equals("resumeWith")) { completion.complete(args[0]); return null; }
                return "robust-screen-probe-continuation";
            });
        Object value = call(tool, "execute", NativePhoneUseTool.json(loader, request.toString()), null, continuation);
        Object suspended = Reflector.callStaticMethod(Reflector.findClass("kotlin.coroutines.intrinsics.IntrinsicsKt", loader), "getCOROUTINE_SUSPENDED");
        Object result = value == suspended ? completion.get(90, TimeUnit.SECONDS) : value;
        if (text(result).startsWith("CONTINUE")) {
            List<?> images = (List<?>)call(result, "getImages");
            check(images != null && !images.isEmpty(), "continuation did not attach a fresh native screenshot");
            check(!Boolean.TRUE.equals(call(result, "isDirectAnswer")), "continuation incorrectly ended the native turn");
        }
        return result;
    }
    private static void stage(Context context, String name) throws Exception {
        CountDownLatch waiting = new CountDownLatch(1); ready = waiting;
        Bundle report = new Bundle(); report.putString("report", name);
        context.getContentResolver().call(BridgeProvider.URI, "hook_report", null, report);
        check(waiting.await(20, TimeUnit.SECONDS), "device runner did not acknowledge " + name);
        ready = null;
    }
    private static String text(Object result) { return String.valueOf(call(result, "getResult")); }
    private static Object call(Object receiver, String method, Object... args) { return Reflector.callMethod(receiver, method, args); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
