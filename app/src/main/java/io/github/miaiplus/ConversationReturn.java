package io.github.miaiplus;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.Parcelable;
import io.github.miaiplus.hook.Reflector;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Captures a host conversation route without opening or changing that conversation. */
final class ConversationReturn {
    private static final ThreadLocal<String> ORIGIN = new ThreadLocal<>();
    private static final String NAVIGATION = "page_navigation_data";
    private static final String RETURN_ROUTE = "io.github.miaiplus.CONVERSATION_RETURN";
    private ConversationReturn() {}

    static String currentIdentity() { return ORIGIN.get(); }

    /** Keep only the host identity string; never retain the execution context or its conversation data. */
    static Scope bind(Object executionContext) {
        String previous = ORIGIN.get();
        String identity = executionContext == null ? null
            : (String)Reflector.callMethod(executionContext, "getContextVarIdentity");
        if (identity == null || identity.isBlank()) ORIGIN.remove(); else ORIGIN.set(identity);
        return new Scope(previous);
    }

    static final class Scope implements AutoCloseable {
        private final String previous;
        private boolean closed;
        private Scope(String previous) { this.previous = previous; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) ORIGIN.remove(); else ORIGIN.set(previous);
        }
    }

    /** Called on the tool worker, once per new screen task, before hiding the assistant UI. */
    static Intent capture(Context context, ClassLoader loader) throws Exception {
        String identity = ORIGIN.get();
        if (identity != null) {
            Object ids = Reflector.getStaticObjectField(Reflector.findClass("ck.a", loader), "a");
            String agentId = (String)Reflector.callMethod(ids, "extractAgentId", identity);
            String chatId = (String)Reflector.callMethod(ids, "extractChatId", identity);
            if (chatId != null && !chatId.isBlank()) {
                Object page = MainThread.call(() -> currentPage(context, loader));
                if (matches(page, agentId, chatId)) return route(context, page);
                Object group = query(context, loader, "getConversationGroupByAgentIdAndChatId", agentId, chatId);
                if (group == null) throw new IllegalStateException("无法定位本轮控屏对应的小爱对话");
                return route(context, navigation(loader, group));
            }
        }
        // Legacy entry points have no typed execution context. Read the page before the task hides it.
        Object[] snapshot = MainThread.call(() -> new Object[]{currentPage(context, loader), currentTopic(context, loader)});
        Object page = snapshot[0];
        String currentTopic = (String)snapshot[1];
        if (currentTopic != null && !currentTopic.isBlank()) {
            Object info = page == null ? null : Reflector.callMethod(page, "getTopicGroupInfo");
            if (info != null && currentTopic.equals(Reflector.callMethod(info, "getTopicId"))) return route(context, page);
            Object group = query(context, loader, "getConversationGroupByTopicId", currentTopic);
            if (group == null) throw new IllegalStateException("无法定位本轮控屏对应的小爱对话");
            return route(context, navigation(loader, group));
        }
        if (page == null) return mainRoute(context, loader);
        Object info = Reflector.callMethod(page, "getTopicGroupInfo");
        if (info == null) return route(context, page);
        String topicId = (String)Reflector.callMethod(info, "getTopicId");
        if (topicId == null || topicId.isBlank()) return mainRoute(context, loader);
        return route(context, page);
    }

    private static Intent mainRoute(Context context, ClassLoader loader) {
        // The main voice popup has no topic. Open its native main chat directly so a completed task's
        // explicit return can release the quiet guard before any launch-router forwarding is attempted.
        return route(context, Reflector.newInstance(
            Reflector.findClass("com.xiaomi.voiceassistant.data.model.PageNavigationData", loader), null, "quick"));
    }

    static boolean matches(Object page, String agentId, String chatId) {
        if (page == null) return false;
        Object info = Reflector.callMethod(page, "getTopicGroupInfo");
        if (info == null) return false;
        Object topicId = Reflector.callMethod(info, "getTopicId");
        return topicId instanceof String id && !id.isBlank()
            && java.util.Objects.equals(agentId, Reflector.callMethod(info, "getAgentId"))
            && java.util.Objects.equals(chatId, Reflector.callMethod(info, "getChatId"));
    }

    private static Object currentPage(Context context, ClassLoader loader) {
        // Recognition/standby can cover an old full-screen conversation, so prefer their active route.
        Object foreground = Reflector.getStaticObjectField(Reflector.findClass("com.xiaomi.voiceassistant.a5", loader), "a");
        Object activePage = Reflector.callMethod(foreground, "getForegroundPageNavigationData");
        if (activePage != null && Reflector.callMethod(activePage, "getTopicGroupInfo") != null) return activePage;
        Object ui = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.UiManager", loader), "getInstance", context);
        if (!Boolean.TRUE.equals(Reflector.callMethod(ui, "isFullScreenInForeground"))) return null;
        Object manager = Reflector.callMethod(ui, "getFloatManager");
        Object tabs = manager == null ? null : Reflector.callMethod(manager, "getTabHostManager");
        Object activity = tabs == null ? null : Reflector.callMethod(tabs, HostCompatibility.choose("getTabHostMainActivity", "getConversationHost"));
        Object page = activity == null ? null : Reflector.callMethod(activity, "getPageNavigationData");
        if (page != null && Reflector.callMethod(page, "getTopicGroupInfo") != null) return page;
        return null;
    }

    private static String currentTopic(Context context, ClassLoader loader) {
        Object ui = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.UiManager", loader), "getInstance", context);
        Object manager = Reflector.callMethod(ui, "getFloatManager");
        return manager == null ? null : (String)Reflector.callMethod(manager, "getTopicGroupId");
    }

    private static Object query(Context context, ClassLoader loader, String method, Object... parameters) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("对话定位必须在后台执行");
        Object entry = Reflector.callStaticMethod(Reflector.findClass("n02.e", loader), "fromApplication", context,
            Reflector.findClass("pe1.q", loader));
        Object useCase = Reflector.callMethod(entry, "getTopicByIdUseCase");
        Object repository = Reflector.getObjectField(useCase, "a");
        Object result = suspend(loader, continuation -> {
            Object[] arguments = java.util.Arrays.copyOf(parameters, parameters.length + 1);
            arguments[parameters.length] = continuation;
            return Reflector.callMethod(repository, method, arguments);
        });
        if (Reflector.findClass("com.miui.voiceassist.model.Result$Success", loader).isInstance(result))
            return Reflector.callMethod(result, "getData");
        throw new IllegalStateException("无法读取本轮控屏对应的小爱对话");
    }

    private interface SuspendCall { Object invoke(Object continuation); }
    private static Object suspend(ClassLoader loader, SuspendCall call) throws Exception {
        CompletableFuture<Object> completion = new CompletableFuture<>();
        Object empty = Reflector.getStaticObjectField(Reflector.findClass("kotlin.coroutines.EmptyCoroutineContext", loader), "INSTANCE");
        Object continuation = Proxy.newProxyInstance(loader,
            new Class<?>[]{Reflector.findClass("kotlin.coroutines.Continuation", loader)}, (proxy, method, arguments) -> {
                return switch (method.getName()) {
                    case "getContext" -> empty;
                    case "resumeWith" -> {
                        try {
                            Reflector.callStaticMethod(Reflector.findClass("kotlin.ResultKt", loader), "throwOnFailure", arguments[0]);
                            completion.complete(arguments[0]);
                        } catch (Throwable failure) { completion.completeExceptionally(failure); }
                        yield null;
                    }
                    case "toString" -> "MiAIPlus conversation capture";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
        Object result = call.invoke(continuation);
        Object suspended = Reflector.callStaticMethod(Reflector.findClass("kotlin.coroutines.intrinsics.IntrinsicsKt", loader), "getCOROUTINE_SUSPENDED");
        return result == suspended ? completion.get(3, TimeUnit.SECONDS) : result;
    }

    private static Object navigation(ClassLoader loader, Object group) {
        // Persisted topic fields are shared by both exact host versions.
        Object info = Reflector.newInstance(Reflector.findClass("com.xiaomi.voiceassistant.data.model.TopicGroupInfo", loader),
            Reflector.callMethod(group, "getTopicId"), Reflector.callMethod(group, "getAgentId"), null,
            Reflector.callMethod(group, "getChatId"), Reflector.callMethod(group, "getAvatar"), Reflector.callMethod(group, "getType"));
        return Reflector.newInstance(Reflector.findClass("com.xiaomi.voiceassistant.data.model.PageNavigationData", loader), info, "quick");
    }

    static Intent route(Context context, Object page) {
        return new Intent().setClassName(context.getPackageName(), HostCompatibility.chatActivity())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(NAVIGATION, (Parcelable)page)
            .putExtra(RETURN_ROUTE, true);
    }

    /** The new navigator does not consume a conversation extra on an ordinary onNewIntent. */
    static Runnable navigation(android.app.Activity activity, Intent intent, ClassLoader loader) {
        if (!HostCompatibility.modern() || intent == null || !intent.getBooleanExtra(RETURN_ROUTE, false)) return null;
        Object page = intent.getParcelableExtra(NAVIGATION);
        if (page == null) return null;
        intent.removeExtra(RETURN_ROUTE);
        // Capture before the host's queued initialization consumes its Intent extras.
        return () -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            try {
                Object info = Reflector.callMethod(page, "getTopicGroupInfo");
                if (info == null) {
                    Reflector.callMethod(activity, "navigateToMainConversationFromList");
                    return;
                }
                Object target = Reflector.newInstance(Reflector.findClass("pm1.q$b", loader), page);
                Object kind = Reflector.getStaticObjectField(Reflector.findClass("pm1.r0", loader), "CONVERSATION");
                Object key = Reflector.newInstance(Reflector.findClass("pm1.q0", loader), kind,
                    Reflector.callMethod(info, "getTopicId"));
                Reflector.callMethod(activity, "navigateToDetail", target, key, false, false);
            } catch (RuntimeException failure) {
                android.util.Log.e("MiAIPlus", "Conversation return navigation failed", failure);
            }
        };
    }
}
