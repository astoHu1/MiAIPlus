package io.github.miaiplus;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Parcelable;
import android.os.SystemClock;
import io.github.miaiplus.hook.Reflector;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Sends only a fixed text in a fresh, locally owned conversation through the real chat UI. */
final class ReplyActionsProbe {
    private static volatile CountDownLatch uiVerified;
    private static volatile boolean accepted;
    private ReplyActionsProbe() { }

    static void signalReady() {
        accepted = true;
        CountDownLatch ready = uiVerified;
        if (ready != null) ready.countDown();
    }
    static void signalCancel() {
        CountDownLatch ready = uiVerified;
        if (ready != null) ready.countDown();
    }

    static String run(Context context, ClassLoader loader) throws Exception {
        check(!context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked(), "device must be unlocked");
        Object entry = Reflector.callStaticMethod(Reflector.findClass("n02.e", loader), "fromApplication", context,
            Reflector.findClass("pe1.q", loader));
        Object local = Reflector.getObjectField(Reflector.getObjectField(Reflector.callMethod(entry, "getTopicByIdUseCase"), "a"), "a");
        Intent original = ConversationReturn.capture(context, loader);
        if (original == null) original = route(context, Reflector.newInstance(
            Reflector.findClass("com.xiaomi.voiceassistant.data.model.PageNavigationData", loader), null, "quick"));
        String topicId = "miaiplus-reply-actions-" + UUID.randomUUID();
        String chatId = "miaiplus-reply-actions-" + UUID.randomUUID();
        Object type = Reflector.getStaticObjectField(Reflector.findClass("com.miui.voiceassist.model.topicgroup.TopicType", loader), "MAIN_AGENT_SESSION");
        Object group = Reflector.newInstance(Reflector.findClass("com.miui.voiceassist.model.topicgroup.TopicGroup", loader),
            topicId, "MiAIPlus reply actions test", System.currentTimeMillis(), false, 0, null, "osbot.main", chatId,
            null, Reflector.callMethod(type, "getRaw"), true);
        Throwable failure = null;
        accepted = false;
        uiVerified = new CountDownLatch(1);
        Diagnostics diagnostics = new Diagnostics(loader);
        try {
            suspend(loader, continuation -> Reflector.callMethod(local, "insertConversationGroup", group, continuation));
            Object execution = executionContext(loader, chatId);
            Intent ownedRoute;
            try (var scope = ConversationReturn.bind(execution)) { ownedRoute = ConversationReturn.capture(context, loader); }
            check(ownedRoute != null, "owned conversation route unavailable");
            open(context, ownedRoute);
            assertChat(context, loader, chatId, topicId);
            String query = "MIAIPLUS_REPLY_ACTIONS_TEST " + UUID.randomUUID()
                + "。这是本地界面测试，不要调用任何工具，只回复 miaiplus-reply-actions-ok";
            MainThread.call(() -> {
                Object callback = HookSmokeProbe.chatCallbackForProbe();
                check(callback != null, "native text-chat callback unavailable");
                Reflector.callMethod(callback, "onSendClick", query, query);
                return null;
            });
            android.os.Bundle status = new android.os.Bundle();
            status.putString("report", "REPLY_ACTIONS_WAIT_UI");
            context.getContentResolver().call(BridgeProvider.URI, "hook_report", null, status);
            check(uiVerified.await(110, TimeUnit.SECONDS) && accepted,
                "reply/action controls did not pass visible UI verification; " + diagnostics.summary());
            long completionDeadline = SystemClock.elapsedRealtime() + 5_000;
            while (!diagnostics.nativeReplyComplete() && SystemClock.elapsedRealtime() < completionDeadline) Thread.sleep(50);
            check(diagnostics.nativeReplyComplete(), "visible actions without a completed fixed native reply; " + diagnostics.summary());
            assertChat(context, loader, chatId, topicId);
            return "PROBE PASS: owned local conversation; actual chat submission; fixed reply verified in native stream with STOP and no errors; visible enabled copy/retry controls verified through accessibility; rendered body requires screenshot inspection; " + diagnostics.summary();
        } catch (Exception | Error error) {
            failure = error;
            throw error;
        } finally {
            uiVerified = null;
            Intent restore = original;
            Throwable cleanup = null;
            try { cancelOwned(loader, chatId); }
            catch (Exception | Error error) { cleanup = error; }
            diagnostics.close();
            try { open(context, restore); }
            catch (Exception | Error error) { if (cleanup == null) cleanup = error; else cleanup.addSuppressed(error); }
            try { suspend(loader, continuation -> Reflector.callMethod(local, "deleteConversationGroup", topicId, continuation)); }
            catch (Exception | Error error) { if (cleanup == null) cleanup = error; else cleanup.addSuppressed(error); }
            if (cleanup != null) {
                if (failure != null) failure.addSuppressed(cleanup);
                else if (cleanup instanceof Exception exception) throw exception;
                else throw (Error)cleanup;
            }
        }
    }

    private static void cancelOwned(ClassLoader loader, String chatId) throws Exception {
        Object engine = MainThread.call(() -> Reflector.callMethod(
            Reflector.callStaticMethod(Reflector.findClass("p31.g", loader), "getInstance"), "getMiClawEngineOrNull"));
        if (engine == null) return;
        Object conversation = Reflector.callMethod(Reflector.getObjectField(engine, "a"), "getConversation");
        // The explicit overload cancels only this UUID chat, even if the user navigated elsewhere.
        suspend(loader, continuation -> Reflector.callMethod(conversation, "cancel", "osbot.main", chatId, continuation));
        Object job = MainThread.call(() -> {
            Object route = ((java.util.concurrent.atomic.AtomicReference<?>)Reflector.getObjectField(engine, "l")).get();
            if (route == null || !chatId.equals(Reflector.callMethod(route, "getChatId"))) return null;
            Object ownedJob = Reflector.getObjectField(engine, "i");
            if (ownedJob != null) Reflector.callMethod(ownedJob, "cancel", new java.util.concurrent.CancellationException("owned reply probe cleanup"));
            return ownedJob;
        });
        if (job != null) suspend(loader, continuation -> Reflector.callMethod(job, "join", continuation));
    }

    private static final class Diagnostics implements AutoCloseable {
        private final java.util.List<io.github.miaiplus.hook.HookCallback.Unhook> hooks = new java.util.ArrayList<>();
        private final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger metadata = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger finishes = new java.util.concurrent.atomic.AtomicInteger();
        private volatile String reason = "none", footer = "unseen", stopVisible = "unseen", response = "unseen";
        private String candidate = "";
        private volatile boolean fixedNativeReply;
        Diagnostics(ClassLoader loader) {
            for (var method : ResponsesStream.class.getDeclaredMethods()) {
                if (!method.getName().equals("availableChunk")) continue;
                hooks.add(io.github.miaiplus.hook.Hooks.hookMethod(method, new io.github.miaiplus.hook.HookCallback() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Throwable error = p.getThrowable();
                        if (error == null) return;
                        java.util.Set<String> known = java.util.Set.of("Responses 流响应过大", "Responses 流提前结束",
                            "Responses 请求失败或输出不完整", "Responses 流格式无效");
                        response = error.getClass().getSimpleName() + (error.getMessage() != null && known.contains(error.getMessage()) ? ":" + error.getMessage() : "");
                    }
                }));
            }
            for (var method : ModelProtocol.class.getDeclaredMethods()) {
                if (!method.getName().equals("message")) continue;
                hooks.add(io.github.miaiplus.hook.Hooks.hookMethod(method, new io.github.miaiplus.hook.HookCallback() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!(p.args[0] instanceof org.json.JSONObject object)) return;
                        String status = object.optString("status");
                        response = java.util.Set.of("completed", "failed", "incomplete").contains(status) ? status : "other-status";
                        if (p.hasThrowable()) response += ":" + p.getThrowable().getClass().getSimpleName();
                    }
                }));
            }
            hooks.addAll(io.github.miaiplus.hook.Hooks.hookAllConstructors(Reflector.findClass("sm.b$d", loader), new io.github.miaiplus.hook.HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    String delta = (String)Reflector.callMethod(p.thisObject, "getText");
                    synchronized (Diagnostics.this) {
                        String fixed = "miaiplus-reply-actions-ok";
                        if (delta != null && fixed.startsWith(candidate + delta)) candidate += delta;
                        else candidate = delta != null && fixed.startsWith(delta) ? delta : "";
                    }
                }
            }));
            hooks.addAll(io.github.miaiplus.hook.Hooks.hookAllConstructors(Reflector.findClass("sm.b$b", loader), new io.github.miaiplus.hook.HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    done.incrementAndGet();
                    try { reason = String.valueOf(Reflector.callMethod(p.thisObject, "getReason")); }
                    catch (RuntimeException unavailable) { reason = "present"; }
                    synchronized (Diagnostics.this) {
                        if ("STOP".equals(reason) && "miaiplus-reply-actions-ok".equals(candidate)) fixedNativeReply = true;
                        candidate = "";
                    }
                }
            }));
            hooks.addAll(io.github.miaiplus.hook.Hooks.hookAllConstructors(Reflector.findClass("sm.b$a", loader), new io.github.miaiplus.hook.HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam p) { errors.incrementAndGet(); }
            }));
            Class<?> card = Reflector.findClass("com.xiaomi.voiceassistant.instruction.card.stream.c", loader);
            for (var method : card.getDeclaredMethods()) {
                if (!method.getName().equals("i1") && !method.getName().equals("K1") && !method.getName().equals("g1")) continue;
                hooks.add(io.github.miaiplus.hook.Hooks.hookMethod(method, new io.github.miaiplus.hook.HookCallback() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (method.getName().equals("K1")) finishes.incrementAndGet();
                        if (method.getName().equals("g1")) stopVisible = String.valueOf(Reflector.getStaticObjectField(card, "ca"));
                        Object info = Reflector.getObjectField(p.thisObject, "X2");
                        footer = info == null ? "no-metadata" : "wait=" + Reflector.callMethod(info, "isShouldWaitCardFinish");
                    }
                }));
            }
            for (var method : Reflector.findClass("g51.a", loader).getDeclaredMethods()) {
                if (!method.getName().equals("buildResultOperationInfo")) continue;
                hooks.add(io.github.miaiplus.hook.Hooks.hookMethod(method, new io.github.miaiplus.hook.HookCallback() {
                    @Override protected void afterHookedMethod(MethodHookParam p) { metadata.incrementAndGet(); }
                }));
            }
        }
        boolean nativeReplyComplete() { return fixedNativeReply && errors.get() == 0; }
        String summary() { return "fixedNativeReply=" + nativeReplyComplete() + ", nativeDone=" + done + ", reason=" + reason + ", nativeErrors=" + errors
            + ", metadata=" + metadata + ", cardFinish=" + finishes + ", footer=" + footer + ", stopVisible=" + stopVisible + ", response=" + response; }
        @Override public void close() { for (var hook : hooks) hook.unhook(); }
    }

    private static Object executionContext(ClassLoader loader, String chatId) throws Exception {
        Object ids = Reflector.getStaticObjectField(Reflector.findClass("ck.a", loader), "a");
        String scoped = (String)Reflector.callMethod(ids, "buildAgentSubId", "osbot.main", chatId);
        Class<?> permissionType = Reflector.findClass("sr.q0", loader);
        Object permission = Proxy.newProxyInstance(loader, new Class<?>[]{permissionType}, (proxy, method, args) -> switch (method.getName()) {
            case "toString" -> "MiAIPlus reply actions probe";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new AssertionError("reply actions probe unexpectedly requested tool permission");
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
            values[0] = "miaiplus-local-probe"; values[1] = scoped; values[4] = permission; values[32] = ~0x13;
            return constructor.newInstance(values);
        }
        throw new AssertionError("pinned execution context constructor unavailable");
    }

    private static void assertChat(Context context, ClassLoader loader, String chatId, String topicId) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do {
            if (MainThread.call(() -> {
                Activity activity = activity();
                if (activity == null || !activity.getClass().getName().equals("com.xiaomi.voiceassistant.TabHostMainActivity")) return false;
                Object page = Reflector.callMethod(activity, "getPageNavigationData");
                Object info = page == null ? null : Reflector.callMethod(page, "getTopicGroupInfo");
                Object ui = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.UiManager", loader), "getInstance", context);
                return info != null && chatId.equals(Reflector.callMethod(info, "getChatId"))
                    && topicId.equals(Reflector.callMethod(Reflector.callMethod(ui, "getFloatManager"), "getTopicGroupId"));
            })) return;
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("owned conversation did not become active");
    }

    private static Activity activity() {
        Object thread = Reflector.callStaticMethod(Reflector.findClass("android.app.ActivityThread", null), "currentActivityThread");
        for (Object record : ((Map<?, ?>)Reflector.getObjectField(thread, "mActivities")).values())
            if (!Boolean.TRUE.equals(Reflector.getObjectField(record, "paused")))
                return (Activity)Reflector.getObjectField(record, "activity");
        return null;
    }
    private static void open(Context context, Intent intent) throws Exception {
        MainThread.call(() -> { context.startActivity(intent); return null; });
    }
    private static Intent route(Context context, Object page) {
        return new Intent().setClassName(context.getPackageName(), "com.xiaomi.voiceassistant.TabHostMainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("page_navigation_data", (Parcelable)page);
    }
    private interface Call { Object invoke(Object continuation); }
    private static Object suspend(ClassLoader loader, Call call) throws Exception {
        CompletableFuture<Object> completion = new CompletableFuture<>();
        Object empty = Reflector.getStaticObjectField(Reflector.findClass("kotlin.coroutines.EmptyCoroutineContext", loader), "INSTANCE");
        Object continuation = Proxy.newProxyInstance(loader, new Class<?>[]{Reflector.findClass("kotlin.coroutines.Continuation", loader)}, (proxy, method, args) -> switch (method.getName()) {
            case "getContext" -> empty;
            case "resumeWith" -> { completion.complete(args[0]); yield null; }
            case "toString" -> "MiAIPlus reply probe continuation";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Object value = call.invoke(continuation);
        Object suspended = Reflector.callStaticMethod(Reflector.findClass("kotlin.coroutines.intrinsics.IntrinsicsKt", loader), "getCOROUTINE_SUSPENDED");
        Object result = value == suspended ? completion.get(30, TimeUnit.SECONDS) : value;
        Reflector.callStaticMethod(Reflector.findClass("kotlin.ResultKt", loader), "throwOnFailure", result);
        return result;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
