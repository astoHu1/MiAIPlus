package io.github.miaiplus;

import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Parcelable;
import android.os.SystemClock;
import io.github.miaiplus.hook.Reflector;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Local-only regression: real native tool, completed notification and exact host conversation routing. */
final class ConversationReturnProbe {
    private ConversationReturnProbe() { }

    static String run(Context context, ClassLoader loader) throws Exception {
        check(!context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked(), "device must be unlocked");
        Object entry = Reflector.callStaticMethod(Reflector.findClass("n02.e", loader), "fromApplication", context,
            Reflector.findClass("pe1.q", loader));
        Object useCase = Reflector.callMethod(entry, "getTopicByIdUseCase");
        Object combined = Reflector.getObjectField(useCase, "a");
        // The inner repository has only local DAO operations. The outer delete also contacts MiClaw SDK.
        Object local = Reflector.getObjectField(combined, "a");
        Intent originalRoute = ConversationReturn.capture(context, loader);
        if (originalRoute == null) originalRoute = route(context, Reflector.newInstance(
            Reflector.findClass("com.xiaomi.voiceassistant.data.model.PageNavigationData", loader), null, "quick"));
        String topicA = "miaiplus-return-" + UUID.randomUUID(), topicB = "miaiplus-return-" + UUID.randomUUID();
        String chatA = "miaiplus-probe-" + UUID.randomUUID(), chatB = "miaiplus-probe-" + UUID.randomUUID();
        Object ctxA = executionContext(loader, chatA), ctxB = executionContext(loader, chatB);
        Object registry = Reflector.getStaticObjectField(Reflector.findClass("com.aios.osbot.tools.ToolRegistry", loader), "instance");
        Object tool = Reflector.callMethod(registry, "get", "miaiplus_phone_use");
        check(tool != null, "foreground native tool unavailable");
        String session = null;
        Throwable failure = null;
        try {
            localCall(loader, local, "insertConversationGroup", group(loader, topicA, chatA));
            localCall(loader, local, "insertConversationGroup", group(loader, topicB, chatB));
            Intent routeA = capture(context, loader, ctxA), routeB = capture(context, loader, ctxB);
            check(chatA.equals(routeChat(routeA)) && chatB.equals(routeChat(routeB)), "typed identities did not resolve distinct owned chats");

            String first = execute(loader, tool, ctxA, new JSONObject().put("goal", "仅观察测试屏幕后结束，不点击、不输入、不启动其他应用"));
            session = session(first);
            PendingIntent activeReturn = notification(context, true);
            // Later tool calls can have a different current context; the screen task must retain its origin.
            String next = execute(loader, tool, ctxB, step(session, "wait"));
            check(session.equals(session(next)), "continuation changed control session");
            check(activeReturn.equals(notification(context, true)), "continuation replaced originating conversation action");
            finish(loader, tool, ctxB, session); session = null;
            PendingIntent completedA = notification(context, false);
            check(activeReturn.equals(completedA), "completion replaced originating conversation action");
            open(context, loader, routeB);
            assertChat(context, loader, chatB, topicB);
            completedA.send();
            assertChat(context, loader, chatA, topicA);

            String second = execute(loader, tool, ctxB, new JSONObject().put("goal", "仅观察测试屏幕后结束，不点击、不输入、不启动其他应用"));
            session = session(second);
            finish(loader, tool, ctxA, session); session = null;
            PendingIntent completedB = notification(context, false);
            check(!completedA.equals(completedB), "different control tasks reused the same PendingIntent identity");
            open(context, loader, routeA);
            assertChat(context, loader, chatA, topicA);
            completedB.send();
            assertChat(context, loader, chatB, topicB);
            return "PROBE PASS: two owned local conversations; real typed tool context; origin pinned through different-context CONTINUE/finish; completed island returns to exact original conversation after switching chats; distinct task PendingIntents; no model request";
        } catch (Exception | Error error) {
            failure = error;
            throw error;
        } finally {
            String remainingSession = session;
            Intent returnRoute = originalRoute;
            Throwable cleanupFailure = null;
            if (remainingSession != null) cleanupFailure = cleanup(cleanupFailure, () -> finish(loader, tool, ctxA, remainingSession));
            cleanupFailure = cleanup(cleanupFailure, () -> open(context, loader, returnRoute));
            // These exact UUIDs are owned by this probe, including an insert that committed before throwing.
            cleanupFailure = cleanup(cleanupFailure, () -> localCall(loader, local, "deleteConversationGroup", topicA));
            cleanupFailure = cleanup(cleanupFailure, () -> localCall(loader, local, "deleteConversationGroup", topicB));
            if (cleanupFailure != null) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else if (cleanupFailure instanceof Exception exception) throw exception;
                else throw (Error)cleanupFailure;
            }
        }
    }

    private interface Cleanup { void run() throws Exception; }
    private static Throwable cleanup(Throwable first, Cleanup operation) {
        try { operation.run(); }
        catch (Exception | Error error) {
            if (first == null) return error;
            first.addSuppressed(error);
        }
        return first;
    }

    private static Object group(ClassLoader loader, String topicId, String chatId) {
        Object type = Reflector.getStaticObjectField(Reflector.findClass("com.miui.voiceassist.model.topicgroup.TopicType", loader), "MAIN_AGENT_SESSION");
        return Reflector.newInstance(Reflector.findClass("com.miui.voiceassist.model.topicgroup.TopicGroup", loader),
            topicId, "MiAIPlus return test", System.currentTimeMillis(), false, 0, null, "osbot.main", chatId,
            null, Reflector.callMethod(type, "getRaw"), true);
    }

    private static Object executionContext(ClassLoader loader, String chatId) throws Exception {
        Object ids = Reflector.getStaticObjectField(Reflector.findClass("ck.a", loader), "a");
        String scoped = (String)Reflector.callMethod(ids, "buildAgentSubId", "osbot.main", chatId);
        Class<?> permissionType = Reflector.findClass("sr.q0", loader);
        check(permissionType.isInterface(), "pinned permission provider is not an interface");
        Object permission = Proxy.newProxyInstance(loader, new Class<?>[]{permissionType}, (proxy, method, args) -> {
            if (method.getName().equals("toString")) return "MiAIPlus local return probe";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            throw new AssertionError("return probe unexpectedly requested a tool permission");
        });
        for (var constructor : Reflector.findClass("com.aios.osbot.tools.ExecutionContext", loader).getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length != 34 || !types[33].getName().equals("kotlin.jvm.internal.DefaultConstructorMarker")) continue;
            Object[] values = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == boolean.class) values[i] = false;
                else if (types[i] == int.class) values[i] = 0;
                else if (types[i] == long.class) values[i] = 0L;
            }
            values[0] = "miaiplus-local-probe"; values[1] = scoped; values[4] = permission;
            values[32] = ~0x13; // Kotlin defaults for everything except required userId, agentId and permissionManager.
            Object execution = constructor.newInstance(values);
            check(scoped.equals(Reflector.callMethod(execution, "getContextVarIdentity")), "host execution context lost scoped chat identity");
            return execution;
        }
        throw new AssertionError("pinned ExecutionContext constructor unavailable");
    }

    private static Intent capture(Context context, ClassLoader loader, Object execution) throws Exception {
        try (var scope = ConversationReturn.bind(execution)) { return ConversationReturn.capture(context, loader); }
    }
    private static String execute(ClassLoader loader, Object tool, Object execution, JSONObject request) throws Exception {
        Object result = suspend(loader, continuation -> Reflector.callMethod(tool, "execute",
            NativePhoneUseTool.json(loader, request.toString()), execution, continuation));
        return String.valueOf(Reflector.callMethod(result, "getResult"));
    }
    private static JSONObject step(String session, String action) throws Exception {
        JSONObject operation = new JSONObject().put("action", action).put("duration_ms", 100);
        if (action.equals("finish")) operation.put("message", "本地对话返回测试结束");
        return new JSONObject().put("session", session).put("action", operation);
    }
    private static void finish(ClassLoader loader, Object tool, Object execution, String session) throws Exception {
        check(execute(loader, tool, execution, step(session, "finish")).contains("模型报告任务完成"), "control task did not finish");
    }
    private static String session(String text) {
        var match = java.util.regex.Pattern.compile("会话=([a-f0-9-]{36})").matcher(text);
        check(text.startsWith("CONTINUE") && match.find(), "native tool did not return a continuing session");
        return match.group(1);
    }
    private static PendingIntent notification(Context context, boolean ongoing) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        do {
            for (var status : context.getSystemService(NotificationManager.class).getActiveNotifications())
                if (status.getId() == 0x4d494149 && status.getNotification().contentIntent != null
                    && ((status.getNotification().flags & android.app.Notification.FLAG_ONGOING_EVENT) != 0) == ongoing)
                    return status.getNotification().contentIntent;
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("control notification has no return action");
    }
    private static void open(Context context, ClassLoader loader, Intent intent) throws Exception {
        Object interaction = Reflector.getStaticObjectField(XiaoAiHook.class, "interaction");
        Reflector.callMethod(interaction, "userEnteredHost"); // Simulates the user's separate conversation entry.
        MainThread.call(() -> { context.startActivity(intent); return null; });
    }
    private static Intent route(Context context, Object page) {
        return new Intent().setClassName(context.getPackageName(), "com.xiaomi.voiceassistant.TabHostMainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("page_navigation_data", (Parcelable)page);
    }
    private static String routeChat(Intent intent) {
        if (intent == null) return null;
        Object page = intent.getParcelableExtra("page_navigation_data");
        return page == null ? null : (String)Reflector.callMethod(Reflector.callMethod(page, "getTopicGroupInfo"), "getChatId");
    }
    private static void assertChat(Context context, ClassLoader loader, String expected, String expectedTopic) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do {
            boolean matched = MainThread.call(() -> {
                Object page = currentPage(context, loader);
                Object info = page == null ? null : Reflector.callMethod(page, "getTopicGroupInfo");
                Object ui = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.UiManager", loader), "getInstance", context);
                Object manager = Reflector.callMethod(ui, "getFloatManager");
                return info != null && expected.equals(Reflector.callMethod(info, "getChatId"))
                    && expectedTopic.equals(Reflector.callMethod(manager, "getTopicGroupId"));
            });
            if (matched) return;
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("host did not display the requested owned conversation");
    }
    private static Object currentPage(Context context, ClassLoader loader) {
        Object thread = Reflector.callStaticMethod(Reflector.findClass("android.app.ActivityThread", null), "currentActivityThread");
        for (Object record : ((Map<?, ?>)Reflector.getObjectField(thread, "mActivities")).values()) {
            Activity activity = (Activity)Reflector.getObjectField(record, "activity");
            if (!Boolean.TRUE.equals(Reflector.getObjectField(record, "paused"))
                && activity.getClass().getName().equals("com.xiaomi.voiceassistant.TabHostMainActivity"))
                return Reflector.callMethod(activity, "getPageNavigationData");
        }
        return null;
    }
    private static void localCall(ClassLoader loader, Object local, String method, Object value) throws Exception {
        suspend(loader, continuation -> Reflector.callMethod(local, method, value, continuation));
    }
    private interface Call { Object invoke(Object continuation); }
    private static Object suspend(ClassLoader loader, Call call) throws Exception {
        CompletableFuture<Object> completion = new CompletableFuture<>();
        Object empty = Reflector.getStaticObjectField(Reflector.findClass("kotlin.coroutines.EmptyCoroutineContext", loader), "INSTANCE");
        Object continuation = Proxy.newProxyInstance(loader, new Class<?>[]{Reflector.findClass("kotlin.coroutines.Continuation", loader)}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getContext" -> empty;
                case "resumeWith" -> { completion.complete(args[0]); yield null; }
                case "toString" -> "MiAIPlus return probe continuation";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        Object value = call.invoke(continuation);
        Object suspended = Reflector.callStaticMethod(Reflector.findClass("kotlin.coroutines.intrinsics.IntrinsicsKt", loader), "getCOROUTINE_SUSPENDED");
        Object result = value == suspended ? completion.get(40, TimeUnit.SECONDS) : value;
        Reflector.callStaticMethod(Reflector.findClass("kotlin.ResultKt", loader), "throwOnFailure", result);
        return result;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
