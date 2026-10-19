package io.github.miaiplus;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real automatic wake routing and native text replies, using only a loopback fixture. */
final class ConversationLifecycleReplyProbe {
    private static volatile String waiting;
    private static volatile CountDownLatch ready;
    private static volatile boolean canceled;
    private static long minimumOpening;
    private static final Set<String> originalTopics = new HashSet<>();
    private record Topic(String topic, String chat, String agent) { }
    private record EngineRoute(String agent, String chat) { }
    private ConversationLifecycleReplyProbe() { }

    static void signal(String nonce) {
        if (nonce != null && nonce.equals(waiting)) {
            CountDownLatch latch = ready;
            if (latch != null) latch.countDown();
        }
    }

    static void cancel() {
        canceled = true;
        CountDownLatch latch = ready;
        if (latch != null) latch.countDown();
    }

    @SuppressWarnings("unchecked")
    static String run(Context context, ClassLoader loader) throws Exception {
        check(!context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked(), "device must be unlocked");
        Intent original = ConversationReturn.capture(context, loader);
        if (original == null) original = ConversationReturn.route(context, NativeConversationRouting.page(loader, null));
        var preferences = context.getSharedPreferences("miaiplus_conversation", Context.MODE_PRIVATE);
        Map<String, ?> savedPreferences = preferences.getAll();
        Map<String, Object> savedState = MainThread.call(() -> (Map<String, Object>)base("snapshot"));
        MainThread.call(() -> {
            originalTopics.clear();
            rememberOriginal(savedState.get("conversationInfo"));
            Object manager = manager(context, loader);
            Object cards = Reflector.callMethod(manager, "getTopicDialogCardManager");
            rememberOriginal(cards == null ? null : Reflector.callMethod(cards, "getTopicGroupInfo"));
            rememberOriginal(NativeConversationRouting.popupInfo(manager));
            return null;
        });
        Object local = base("localRepository", context, loader);
        List<Topic> owned = new ArrayList<>();
        Throwable failure = null;
        canceled = false;
        try (AudioGuard guard = new AudioGuard(loader)) {
            seedClock(61_000);
            base("goHome", context);
            base("launch", context);
            Topic first = awaitSelection(context, loader, false, null, owned);
            reply(context, loader, false, first, "fullscreen-expired", null, guard);

            long before = seedClock(50_000);
            base("goHome", context);
            base("launch", context);
            Topic continued = awaitSelection(context, loader, false, first, owned);
            check(clock() > before, "reopening fullscreen did not restart the timer");
            reply(context, loader, false, continued, "fullscreen-reopened", null, guard);

            seedClock(61_000);
            base("goHome", context);
            awaitBackground(context, loader);
            String popupNonce = UUID.randomUUID().toString();
            try (ReplyTrace trace = new ReplyTrace(loader, answer(popupNonce))) {
                guard.wake(context, popupNonce);
                Topic popup = awaitSelection(context, loader, true, null, owned, false);
                check(!first.topic.equals(popup.topic) && !first.chat.equals(popup.chat),
                    "expired popup retained the fullscreen conversation");
                submit(popupNonce);
                awaitSelection(context, loader, true, popup, owned);
                reply(context, loader, true, popup, "popup-expired", popupNonce, guard, trace);
                dismiss(context, loader);

                before = seedClock(50_000);
                base("goHome", context);
                awaitBackground(context, loader);
                popupNonce = UUID.randomUUID().toString();
                try (ReplyTrace repeated = new ReplyTrace(loader, answer(popupNonce))) {
                    guard.wake(context, popupNonce);
                    Topic repeatedPopup = awaitSelection(context, loader, true, popup, owned, false);
                    check(clock() > before, "reopening popup did not restart the timer");
                    submit(popupNonce);
                    awaitSelection(context, loader, true, popup, owned);
                    reply(context, loader, true, repeatedPopup, "popup-reopened", popupNonce, guard, repeated);
                }
                dismiss(context, loader);
                base("goHome", context);
                base("launch", context);
                awaitSelection(context, loader, false, popup, owned);
            }
            guard.checkSafety();
            return "PROBE PASS: real launcher and no-microphone VoiceService text wakes; automatic expired fullscreen/popup create distinct topics; "
                + "reopening each surface restarts persisted timer and continues its chat; four unique loopback replies complete in the native engine "
                + "and appear in the matching visible surface; popup-to-fullscreen preserves exact topic/chat; owned topics and original route restored";
        } catch (Exception | Error error) {
            failure = error;
            throw error;
        } finally {
            waiting = null;
            ready = null;
            Throwable cleanup = null;
            for (Topic topic : owned) {
                try {
                    if (topic.chat != null && !topic.chat.isBlank())
                        cancelOwned(loader, topic);
                }
                catch (Exception | Error error) { cleanup = append(cleanup, error); }
            }
            try { base("open", context, original); base("awaitOriginalRoute", context, loader, original); }
            catch (Exception | Error error) { cleanup = append(cleanup, error); }
            for (Topic topic : owned) {
                try { base("localCall", loader, local, "deleteConversationGroup", topic.topic); }
                catch (Exception | Error error) { cleanup = append(cleanup, error); }
            }
            try {
                MainThread.call(() -> {
                    base("restorePreferences", preferences, savedPreferences);
                    long generation = ((Number)state("generation")).longValue() + 1;
                    for (var entry : savedState.entrySet())
                        if (!entry.getKey().equals("generation")) base("setState", entry.getKey(), entry.getValue());
                    base("setState", "generation", generation);
                    return null;
                });
            } catch (Exception | Error error) { cleanup = append(cleanup, error); }
            if (cleanup != null) {
                if (failure != null) failure.addSuppressed(cleanup);
                else if (cleanup instanceof Exception exception) throw exception;
                else throw (Error)cleanup;
            }
        }
    }

    private static long seedClock(long age) throws Exception {
        return MainThread.call(() -> {
            long now = System.currentTimeMillis() - age;
            minimumOpening = System.currentTimeMillis();
            ((ConversationPolicy)state("policy")).userActivity(now);
            Reflector.callStaticMethod(NativeConversationHook.class, "save");
            return now;
        });
    }

    private static long clock() throws Exception {
        return MainThread.call(() -> ((ConversationPolicy)state("policy")).lastActivity());
    }

    private static Topic awaitSelection(Context context, ClassLoader loader, boolean popup,
        Topic expected, List<Topic> owned) throws Exception {
        return awaitSelection(context, loader, popup, expected, owned, true);
    }

    private static Topic awaitSelection(Context context, ClassLoader loader, boolean popup,
        Topic expected, List<Topic> owned, boolean requireResultPage) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 25_000;
        do {
            Topic selected = MainThread.call(() -> {
                Object info = state("conversationInfo");
                if (info == null || state("pending") != null
                    || ((ConversationPolicy)state("policy")).lastActivity() < minimumOpening) return null;
                Topic actual = topic(info);
                if (actual.topic == null || actual.topic.isBlank()) return null;
                if (expected == null && originalTopics.contains(actual.topic)) return null;
                // Record the automatically generated topic before waiting for async engine metadata.
                if (expected == null && owned.stream().noneMatch(t -> t.topic.equals(actual.topic))) owned.add(actual);
                if (actual.chat == null || actual.chat.isBlank()) return null;
                if (expected != null && !expected.equals(actual)) return null;
                Object manager = manager(context, loader);
                if (!surface(context, loader, popup)) return null;
                if (!requireResultPage) return actual;
                Object cards = popup ? NativeConversationRouting.popupInfo(manager)
                    : Reflector.callMethod(Reflector.callMethod(manager, "getTopicDialogCardManager"), "getTopicGroupInfo");
                Object page = popup ? NativeConversationRouting.popupPage(manager) : base("currentPage", loader);
                Object visible = page == null ? null : Reflector.callMethod(page, "getTopicGroupInfo");
                if (cards == null || visible == null || !actual.equals(topic(cards)) || !actual.equals(topic(visible))) return null;
                return actual;
            });
            if (selected != null) {
                // Replace a pre-initialization record with its completed native chat identity.
                for (int index = 0; index < owned.size(); index++)
                    if (owned.get(index).topic.equals(selected.topic)) owned.set(index, selected);
                return selected;
            }
            check(!canceled, "probe canceled");
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError((popup ? "popup" : "fullscreen") + " automatic selection did not settle; "
            + base("diagnostics", context, loader, newTopicArray()));
    }

    private static Object newTopicArray() {
        try { return java.lang.reflect.Array.newInstance(Class.forName(ConversationContinuityProbe.class.getName() + "$Topic"), 0); }
        catch (ClassNotFoundException error) { throw new IllegalStateException(error); }
    }

    private static void reply(Context context, ClassLoader loader, boolean popup, Topic topic,
        String stage, String nonce, AudioGuard guard) throws Exception {
        String requestNonce = nonce == null ? UUID.randomUUID().toString() : nonce;
        try (ReplyTrace trace = new ReplyTrace(loader, answer(requestNonce))) {
            if (nonce == null) submit(requestNonce);
            reply(context, loader, popup, topic, stage, requestNonce, guard, trace);
        }
    }

    private static void submit(String nonce) throws Exception {
        MainThread.call(() -> {
            Object callback = HookSmokeProbe.chatCallbackForProbe();
            check(callback != null, "native text callback unavailable");
            Reflector.callMethod(callback, "onSendClick", query(nonce), query(nonce));
            return null;
        });
    }

    private static void reply(Context context, ClassLoader loader, boolean popup, Topic topic,
        String stage, String nonce, AudioGuard guard, ReplyTrace trace) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 60_000;
        do {
            guard.checkSafety();
            check(!canceled, "probe canceled");
            if (trace.complete() && MainThread.call(() -> engineMatches(loader, topic))) break;
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        check(trace.complete(), stage + " native unique reply did not complete: " + trace.summary());
        check(MainThread.call(() -> engineMatches(loader, topic)), stage + " native engine route differs from selected topic; "
            + MainThread.call(() -> routeDescription(loader, topic)));
        awaitSelection(context, loader, popup, topic, new ArrayList<>());
        if (popup) check(MainThread.call(() -> popupDialogMatches(context, loader, topic)),
            stage + " visible popup cards are not attached to the native request dialog");
        check(MainThread.call(() -> surface(context, loader, popup)), stage + " changed wake surface while replying");
        waiting = nonce;
        ready = new CountDownLatch(1);
        Bundle report = new Bundle();
        report.putString("report", "CONVERSATION_REPLY_WAIT_UI " + stage + " " + nonce);
        context.getContentResolver().call(BridgeProvider.URI, "hook_report", null, report);
        check(ready.await(45, TimeUnit.SECONDS) && !canceled, stage + " unique reply was not visibly verified");
        waiting = null;
        ready = null;
    }

    private static boolean engineMatches(ClassLoader loader, Topic expected) {
        EngineRoute actual = engineRoute(loader);
        return actual != null && java.util.Objects.equals(expected.agent, actual.agent)
            && java.util.Objects.equals(expected.chat, actual.chat);
    }

    private static EngineRoute engineRoute(ClassLoader loader) {
        Object engine = Reflector.callMethod(Reflector.callStaticMethod(Reflector.findClass("p31.g", loader), "getInstance"),
            "getMiClawEngineOrNull");
        if (engine == null) return null;
        Object route = ((AtomicReference<?>)Reflector.getObjectField(engine, "l")).get();
        if (route != null) return new EngineRoute((String)Reflector.callMethod(route, "getAgentId"),
            (String)Reflector.callMethod(route, "getChatId"));
        // Both pinned engines set l only for an explicit conversationTargetAgentId. Their own
        // forceStop falls back to SDK activeConversationTarget() for ordinary main-chat requests.
        Object conversation = Reflector.callMethod(Reflector.getObjectField(engine, "a"), "getConversation");
        Object target = Reflector.callMethod(conversation, "activeConversationTarget");
        return target == null ? null : new EngineRoute((String)Reflector.callMethod(target, "component1"),
            (String)Reflector.callMethod(target, "component2"));
    }

    private static String routeDescription(ClassLoader loader, Topic expected) {
        EngineRoute actual = engineRoute(loader);
        // Only the current automatically created test chat can appear verbatim in reports.
        String actualChat = actual == null ? "missing" : java.util.Objects.equals(expected.chat, actual.chat)
            ? actual.chat : actual.chat == null ? "null" : "different-unowned-chat";
        return "expectedAgent=" + expected.agent + ",expectedChat=" + expected.chat + ",actualAgent="
            + (actual == null ? "missing" : actual.agent) + ",actualChat=" + actualChat;
    }

    private static void cancelOwned(ClassLoader loader, Topic owned) {
        Object engine = Reflector.callMethod(Reflector.callStaticMethod(Reflector.findClass("p31.g", loader), "getInstance"),
            "getMiClawEngineOrNull");
        if (engine == null) return;
        Object conversation = Reflector.callMethod(Reflector.getObjectField(engine, "a"), "getConversation");
        base("suspend", loader, java.lang.reflect.Proxy.newProxyInstance(ConversationContinuityProbe.class.getClassLoader(),
            new Class<?>[]{nestedBaseClass("Call")}, (proxy, method, args) -> switch (method.getName()) {
                case "invoke" -> Reflector.callMethod(conversation, "cancel", owned.agent, owned.chat, args[0]);
                case "toString" -> "MiAIPlus owned conversation cancellation";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            }));
    }

    private static boolean popupDialogMatches(Context context, ClassLoader loader, Topic expected) {
        Object engine = Reflector.callMethod(Reflector.callStaticMethod(Reflector.findClass("p31.g", loader), "getInstance"),
            "getMiClawEngineOrNull");
        if (engine == null || !engineMatches(loader, expected)) return false;
        Object dialog = Reflector.callStaticMethod(engine.getClass(), "access$getCurrentDialogId$p", engine);
        Object controller = NativeConversationRouting.popupController(manager(context, loader));
        Object view = controller == null ? null : Reflector.callMethod(controller, "getFloatResultCardView");
        Object showing = view == null ? null : Reflector.callMethod(view, "getCurrentShowingCards");
        if (dialog == null || !(showing instanceof Iterable<?> cards)) return false;
        for (Object card : cards)
            if (dialog.equals(Reflector.callMethod(card, "getDialogId"))
                && !Boolean.TRUE.equals(Reflector.callMethod(card, "isHistoryCard"))) return true;
        return false;
    }

    private static boolean surface(Context context, ClassLoader loader, boolean popup) {
        Object ui = ui(context, loader);
        boolean full = Boolean.TRUE.equals(Reflector.callMethod(ui, "isFullScreenInForeground"));
        if (!popup) return full && base("currentPage", loader) != null;
        View root = (View)Reflector.callMethod(manager(context, loader), "getRootLayout");
        return !full && root != null && root.isShown() && root.isAttachedToWindow()
            && root.getLayoutParams() instanceof WindowManager.LayoutParams params
            && params.type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW;
    }

    private static void awaitBackground(Context context, ClassLoader loader) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        while (MainThread.call(() -> Boolean.TRUE.equals(Reflector.callMethod(ui(context, loader), "isFullScreenInForeground")))) {
            check(SystemClock.elapsedRealtime() < deadline, "fullscreen did not leave foreground for popup");
            Thread.sleep(100);
        }
    }

    private static void dismiss(Context context, ClassLoader loader) throws Exception {
        MainThread.call(() -> { Reflector.callMethod(ui(context, loader), "exeBackNoAnimAndNotStopEngine"); return null; });
    }

    private static final class ReplyTrace implements AutoCloseable {
        private final List<HookCallback.Unhook> hooks = new ArrayList<>();
        private final String expected;
        private final AtomicInteger errors = new AtomicInteger();
        private String candidate = "";
        private volatile boolean done;
        ReplyTrace(ClassLoader loader, String expected) {
            this.expected = expected;
            hooks.addAll(Hooks.hookAllConstructors(Reflector.findClass("sm.b$d", loader), new HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    String delta = (String)Reflector.callMethod(p.thisObject, "getText");
                    synchronized (ReplyTrace.this) {
                        if (delta != null && expected.startsWith(candidate + delta)) candidate += delta;
                        else candidate = delta != null && expected.startsWith(delta) ? delta : "";
                    }
                }
            }));
            hooks.addAll(Hooks.hookAllConstructors(Reflector.findClass("sm.b$b", loader), new HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    synchronized (ReplyTrace.this) {
                        if ("STOP".equals(String.valueOf(Reflector.callMethod(p.thisObject, "getReason"))) && expected.equals(candidate)) done = true;
                        candidate = "";
                    }
                }
            }));
            hooks.addAll(Hooks.hookAllConstructors(Reflector.findClass("sm.b$a", loader), new HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam p) { errors.incrementAndGet(); }
            }));
        }
        boolean complete() { return done && errors.get() == 0; }
        String summary() { return "uniqueReply=" + done + ",errors=" + errors.get(); }
        @Override public void close() { for (var hook : hooks) hook.unhook(); }
    }

    private static final class AudioGuard implements AutoCloseable {
        private static final String KEY = "io.github.miaiplus.CONVERSATION_REPLY_PROBE";
        private final String ownership = UUID.randomUUID().toString();
        private final List<HookCallback.Unhook> hooks = new ArrayList<>();
        private final AtomicInteger recordings = new AtomicInteger();
        private volatile boolean armed;
        private final ClassLoader loader;
        private Context wakeContext;
        AudioGuard(ClassLoader loader) {
            this.loader = loader;
            hooks.add(Reflector.findAndHookMethod("com.xiaomi.voiceassistant.VoiceService", loader,
                HostCompatibility.choose("t0", "y0"), Intent.class, String.class, new HookCallback(10_000) {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.args[0] instanceof Intent intent && ownership.equals(intent.getStringExtra(KEY))) p.setResult(null);
                    }
                }));
            for (var method : android.media.AudioRecord.class.getDeclaredMethods()) {
                if (!method.getName().equals("startRecording")) continue;
                hooks.add(Hooks.hookMethod(method, new HookCallback(10_000) {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (armed) { recordings.incrementAndGet(); p.setResult(null); }
                    }
                }));
            }
        }
        void wake(Context context, String nonce) throws Exception {
            wakeContext = context;
            armed = true;
            MainThread.call(() -> {
                context.startService(new Intent("com.miui.voiceassist.ACTION_VOICE_START_VOICEASSIST")
                    .setClassName(context.getPackageName(), "com.xiaomi.voiceassistant.VoiceService")
                    .putExtra(KEY, ownership).putExtra("isCloseMic", true).putExtra("is_idle_state", true)
                    .putExtra("request_source_type", "TEXT_INPUT").putExtra("need_tts", false));
                return null;
            });
        }
        void checkSafety() { check(recordings.get() == 0, "no-microphone text wake reached AudioRecord failsafe"); }
        @Override public void close() {
            try {
                if (armed && wakeContext != null) MainThread.call(() -> {
                    Reflector.callMethod(ui(wakeContext, loader), "stopEngine", true);
                    Reflector.callMethod(ui(wakeContext, loader), "exeBackNoAnimAndNotStopEngine");
                    return null;
                });
            } catch (Exception error) {
                throw new IllegalStateException("owned text wake cleanup failed", error);
            } finally {
                armed = false;
                for (var hook : hooks) hook.unhook();
            }
        }
    }

    private static Object base(String name, Object... args) { return Reflector.callStaticMethod(ConversationContinuityProbe.class, name, args); }
    private static void rememberOriginal(Object info) {
        if (info == null) return;
        String id = (String)Reflector.callMethod(info, "getTopicId");
        if (id != null) originalTopics.add(id);
    }
    private static Object state(String name) { return Reflector.getStaticObjectField(NativeConversationHook.class, name); }
    private static Object ui(Context context, ClassLoader loader) { return Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.UiManager", loader), "getInstance", context); }
    private static Object manager(Context context, ClassLoader loader) { return Reflector.callMethod(ui(context, loader), "getFloatManager"); }
    private static Topic topic(Object info) { return new Topic((String)Reflector.callMethod(info, "getTopicId"),
        (String)Reflector.callMethod(info, "getChatId"), (String)Reflector.callMethod(info, "getAgentId")); }
    private static Class<?> nestedBaseClass(String name) {
        try { return Class.forName(ConversationContinuityProbe.class.getName() + "$" + name); }
        catch (ClassNotFoundException error) { throw new IllegalStateException(error); }
    }
    private static String query(String nonce) { return "MIAIPLUS_REPLY_ACTIONS_TEST MIAIPLUS_CONVERSATION_STAGE_" + nonce + "。本地界面测试，不调用任何工具。"; }
    private static String answer(String nonce) { return "miaiplus-ok-" + nonce.substring(0, 8); }
    private static Throwable append(Throwable first, Throwable next) { if (first == null) return next; first.addSuppressed(next); return first; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
