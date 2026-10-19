package io.github.miaiplus;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Owned local topics only. Checks each wake surface's actual route without making a model request. */
final class ConversationContinuityProbe {
    private ConversationContinuityProbe() { }
    private static volatile LauncherTrace launcherTrace;

    private record Topic(String topic, String chat) { }
    private record PopupResult(String summary, Topic latest) { }

    static String focusPopupUi(Context context, ClassLoader loader) throws Exception {
        try (PopupGuard guard = new PopupGuard(loader)) {
            goHome(context);
            guard.wake(context);
            String result = HookSmokeProbe.focusUi(context, loader);
            guard.checkSafety();
            return result;
        }
    }

    static String run(Context context, ClassLoader loader) throws Exception {
        check(!context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked(),
            "device must be unlocked");
        String limits = NativeConversationLimitsProbe.run(context, loader);
        report(context, "CONVERSATION_LIMITS_DONE");
        Object local = localRepository(context, loader);
        Intent originalRoute = ConversationReturn.capture(context, loader);
        if (originalRoute == null) originalRoute = ConversationReturn.route(context, Reflector.newInstance(
            Reflector.findClass("com.xiaomi.voiceassistant.data.model.PageNavigationData", loader), null, "quick"));
        SharedPreferences prefs = context.getSharedPreferences("miaiplus_conversation", Context.MODE_PRIVATE);
        Map<String, ?> savedPreferences = prefs.getAll();
        Map<String, Object> savedState = MainThread.call(ConversationContinuityProbe::snapshot);
        Topic first = owned(), second = owned();
        List<String> createdTopics = new ArrayList<>(List.of(first.topic, second.topic));
        Throwable failure = null;
        try {
            localCall(loader, local, "insertConversationGroup", group(loader, first));
            localCall(loader, local, "insertConversationGroup", group(loader, second));
            open(context, route(context, loader, first));
            awaitTopic(context, loader, first);
            long now = System.currentTimeMillis();
            check(wake(context, loader, now) == ConversationPolicy.Decision.CONTINUE,
                "recent explicit conversation was not continued");
            apply(context, loader);
            awaitTopic(context, loader, first);
            report(context, "CONVERSATION_SHARED_ROUTE_DONE");

            // An expired ordinary wake cannot override a subsequent explicit history/notification route.
            check(wake(context, loader, now + 61_000) == ConversationPolicy.Decision.NEW,
                "idle wake did not request a new conversation");
            open(context, route(context, loader, second));
            awaitTopic(context, loader, second);
            apply(context, loader);
            awaitTopic(context, loader, second);
            check(state("pending") == null, "explicit conversation retained a pending automatic route");
            report(context, "CONVERSATION_EXPLICIT_ROUTE_DONE");

            MainThread.call(() -> {
                ((ConversationPolicy)state("policy")).userActivity(System.currentTimeMillis() - 61_000);
                Reflector.callStaticMethod(NativeConversationHook.class, "save");
                return null;
            });
            check(wake(context, loader, System.currentTimeMillis()) == ConversationPolicy.Decision.NEW,
                "native notification probe did not begin with an expired pending wake");
            Intent nativeNotification = route(context, loader, second);
            nativeNotification.removeExtra("io.github.miaiplus.CONVERSATION_RETURN");
            nativeNotification.putExtra("from", "deepthink_notification");
            long notificationSent = System.currentTimeMillis();
            open(context, nativeNotification);
            awaitNativeNotificationSelection(loader, second, notificationSent);
            check(wake(context, loader, System.currentTimeMillis()) == ConversationPolicy.Decision.CONTINUE,
                "native notification selection did not refresh conversation activity");
            apply(context, loader);
            awaitTopic(context, loader, second);
            report(context, "CONVERSATION_NATIVE_NOTIFICATION_DONE");

            now = System.currentTimeMillis() + 61_000;
            check(wake(context, loader, now) == ConversationPolicy.Decision.NEW,
                "expired explicit conversation was unexpectedly reused");
            Topic fresh = MainThread.call(() -> {
                NativeConversationHook.applyPending(context, loader);
                Object initialized = currentInfo(context, loader);
                check(initialized != null, "new native conversation was not initialized");
                return new Topic((String)Reflector.callMethod(initialized, "getTopicId"),
                    (String)Reflector.callMethod(initialized, "getChatId"));
            });
            check(fresh.topic != null && !fresh.topic.isBlank() && !fresh.topic.equals(first.topic)
                && !fresh.topic.equals(second.topic), "timeout did not create a distinct native topic");
            createdTopics.add(fresh.topic);
            check(!first.chat.equals(fresh.chat) && !second.chat.equals(fresh.chat),
                "new topic retained an old chat identity");
            check(wake(context, loader, now + 1_000) == ConversationPolicy.Decision.CONTINUE,
                "subsequent wake within the timeout did not continue the new conversation");
            apply(context, loader);
            awaitTopic(context, loader, fresh);
            report(context, "CONVERSATION_TIMEOUT_DONE");

            // Recreate the private policy and typed route from exactly the on-disk values used at install.
            // This checks persisted reconstruction, without claiming that a host process was restarted.
            MainThread.call(() -> {
                NativeConversationHook.reinitializeState(context, loader);
                return null;
            });
            check(wake(context, loader, now + 2_000) == ConversationPolicy.Decision.CONTINUE,
                "persisted policy lost the current conversation after reconstruction");
            apply(context, loader);
            awaitTopic(context, loader, fresh);
            report(context, "CONVERSATION_PERSISTENCE_DONE");

            // Real launcher Intents take the existing-task moveTaskToFront path on this host.
            // Seed only the user clock; wake classification, pending routing and navigation stay hooked.
            String popup;
            try (LauncherTrace trace = new LauncherTrace(context, loader, first, second, fresh)) {
                launcherTrace = trace;
                open(context, route(context, loader, first));
                awaitTopic(context, loader, first);
                MainThread.call(() -> {
                    ((ConversationPolicy)state("policy")).userActivity(System.currentTimeMillis() - 61_000);
                    Reflector.callStaticMethod(NativeConversationHook.class, "save");
                    return null;
                });
                goHome(context);
                trace.record("before real launcher Intent");
                launch(context);
                Topic launcherFresh = awaitNewTopic(context, loader, first, second, fresh);
                createdTopics.add(launcherFresh.topic);
                awaitRoute(context, loader, launcherFresh);
                report(context, "CONVERSATION_LAUNCHER_TIMEOUT_DONE");
                goHome(context);
                launch(context);
                awaitRoute(context, loader, launcherFresh);
                report(context, "CONVERSATION_LAUNCHER_CONTINUE_DONE");
                popup = popupContinuity(context, loader, first, createdTopics).summary;
            }
            return "PROBE PASS: " + limits + "; owned native topic/chat continuation; timeout creates a distinct topic; "
                + "explicit history/completion route wins over expired wake; persisted state reconstruction; "
                + "real native notification onNewIntent refreshes activity and continues its owned chat; "
                + "real launcher expired/recent wake updates both native topic and visible navigation; "
                + popup + "; owned topics and original route restored; no model request or audio capture; "
                + "no reply-delivery, physical-key gesture or process-restart assertion";
        } catch (Exception | Error error) {
            failure = error;
            throw error;
        } finally {
            Throwable cleanupFailure = null;
            Intent returnRoute = originalRoute;
            cleanupFailure = cleanup(cleanupFailure, () -> {
                open(context, returnRoute);
                awaitOriginalRoute(context, loader, returnRoute);
            });
            for (String topic : createdTopics)
                cleanupFailure = cleanup(cleanupFailure, () -> localCall(loader, local, "deleteConversationGroup", topic));
            cleanupFailure = cleanup(cleanupFailure, () -> MainThread.call(() -> {
                restorePreferences(prefs, savedPreferences);
                // Invalidate every queued probe callback; a generation must never be rolled back.
                long nextGeneration = ((Number)state("generation")).longValue() + 1;
                for (var field : savedState.entrySet())
                    if (!field.getKey().equals("generation")) setState(field.getKey(), field.getValue());
                setState("generation", nextGeneration);
                return null;
            }));
            if (cleanupFailure != null) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else if (cleanupFailure instanceof Exception exception) throw exception;
                else throw (Error)cleanupFailure;
            }
        }
    }

    private static Topic owned() {
        return new Topic("miaiplus-continuity-" + UUID.randomUUID(), "miaiplus-probe-" + UUID.randomUUID());
    }

    private static ConversationPolicy.Decision wake(Context context, ClassLoader loader, long now) throws Exception {
        return MainThread.call(() -> NativeConversationHook.onUserWake(context, loader, now));
    }
    private static void apply(Context context, ClassLoader loader) throws Exception {
        MainThread.call(() -> { NativeConversationHook.applyPending(context, loader); return null; });
    }

    /** Temporary debug-only hooks record identity relations, never unknown IDs or conversation content. */
    private static final class LauncherTrace implements AutoCloseable {
        private final Context context;
        private final ClassLoader loader;
        private final Topic[] previous;
        private final List<HookCallback.Unhook> hooks = new ArrayList<>();
        private final java.util.ArrayDeque<String> events = new java.util.ArrayDeque<>();
        private final long started = SystemClock.elapsedRealtime();

        LauncherTrace(Context context, ClassLoader loader, Topic... previous) {
            this.context = context;
            this.loader = loader;
            this.previous = previous;
            try {
                java.util.Set<String> hookedNames = new java.util.HashSet<>();
                for (var method : NativeConversationHook.class.getDeclaredMethods()) {
                    if (!java.util.Set.of("onUserWake", "applyPending", "navigateFullScreen").contains(method.getName())) continue;
                    String name = method.getName();
                    hookedNames.add(name);
                    hooks.add(Hooks.hookMethod(method, new HookCallback() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            record(name + " before");
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            record(name + " after result=" + (p.hasThrowable() ? "threw:" + p.getThrowable().getClass().getSimpleName()
                                : name.equals("onUserWake") ? String.valueOf(p.getResult()) : "returned"));
                        }
                    }));
                }
                check(hookedNames.size() == 3, "debug launcher stage hooks unavailable");
            } catch (RuntimeException | Error error) {
                close();
                throw error;
            }
        }

        void record(String stage) {
            String snapshot;
            try { snapshot = MainThread.call(() -> safeState(context, loader, previous)); }
            catch (Exception | Error failure) { snapshot = "stateUnavailable=" + failure.getClass().getSimpleName(); }
            synchronized (events) {
                if (events.size() >= 20) events.removeFirst();
                events.addLast((SystemClock.elapsedRealtime() - started) + "ms " + stage + ": " + snapshot);
            }
        }

        String describe() {
            record("timeout final sample");
            synchronized (events) { return String.join("\n", events); }
        }

        @Override public void close() {
            if (launcherTrace == this) launcherTrace = null;
            for (int index = hooks.size() - 1; index >= 0; index--) hooks.get(index).unhook();
            hooks.clear();
        }
    }

    private static String diagnostics(Context context, ClassLoader loader, Topic... previous) throws Exception {
        LauncherTrace trace = launcherTrace;
        String snapshot;
        try { snapshot = MainThread.call(() -> safeState(context, loader, previous)); }
        catch (Exception | Error failure) { snapshot = "unavailable=" + failure.getClass().getSimpleName(); }
        return "\nSAFE STATE: " + snapshot
            + (trace == null ? "" : "\nSAFE LAUNCHER TRACE:\n" + trace.describe());
    }

    private static String safeState(Context context, ClassLoader loader, Topic[] previous) {
        Object request = state("pending");
        String pending = request == null ? "none" : String.valueOf(Reflector.callMethod(request, "decision"))
            + ",remainingMs=" + (((Number)Reflector.callMethod(request, "deadline")).longValue() - SystemClock.elapsedRealtime());
        Object canonical = state("conversationInfo");
        Object cards = currentInfo(context, loader);
        Object page = currentPage(loader);
        Object visible = page == null ? null : Reflector.callMethod(page, "getTopicGroupInfo");
        Object popupPage = NativeConversationRouting.popupPage(manager(context, loader));
        Object popup = popupPage == null ? null : Reflector.callMethod(popupPage, "getTopicGroupInfo");
        Object popupCards = NativeConversationRouting.popupInfo(manager(context, loader));
        Object ui = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.UiManager", loader),
            "getInstance", context);
        Object tabs = Reflector.callMethod(manager(context, loader), "getTabHostManager");
        Object host = tabs == null ? null : Reflector.callMethod(tabs,
            HostCompatibility.choose("getTabHostMainActivity", "getConversationHost"));
        String lifecycle = host == null ? "null" : host.getClass().getName();
        if (host instanceof Activity activity) lifecycle += "(finishing=" + activity.isFinishing()
            + ",destroyed=" + activity.isDestroyed() + ",changingConfig=" + activity.isChangingConfigurations()
            + ",windowFocus=" + activity.hasWindowFocus() + ",paused=" + activityPaused(activity) + ")";
        ConversationPolicy clock = (ConversationPolicy)state("policy");
        return "pending=" + pending + ";launcherPending=" + state("launcherPending")
            + ";userIdleMs=" + (clock == null ? "no-policy" : System.currentTimeMillis() - clock.lastActivity())
            + ";canonical=" + safeMembership(canonical, previous)
            + ";cards=" + safeMembership(cards, previous) + ";page=" + safeMembership(visible, previous)
            + ";popupRoute=" + safeMembership(popup, previous)
            + ";popupCards=" + safeMembership(popupCards, previous)
            + ";canonical/cards=" + sameIdentity(canonical, cards)
            + ";canonical/page=" + sameIdentity(canonical, visible)
            + ";cards/page=" + sameIdentity(cards, visible)
            + ";canonical/popupRoute=" + sameIdentity(canonical, popup)
            + ";canonical/popupCards=" + sameIdentity(canonical, popupCards)
            + ";uiFull=" + Reflector.callMethod(ui, "isFullScreenInForeground")
            + ";resumedChatPage=" + (page != null) + ";host=" + lifecycle;
    }

    private static Object activityPaused(Activity activity) {
        Object thread = Reflector.callStaticMethod(Reflector.findClass(
            "android.app.ActivityThread", null), "currentActivityThread");
        for (Object record : ((Map<?, ?>)Reflector.getObjectField(thread, "mActivities")).values())
            if (Reflector.getObjectField(record, "activity") == activity) return Reflector.getObjectField(record, "paused");
        return "not-in-activities";
    }

    private static String safeMembership(Object info, Topic[] previous) {
        if (info == null) return "null";
        Object topic = Reflector.callMethod(info, "getTopicId"), chat = Reflector.callMethod(info, "getChatId");
        List<Integer> topics = new ArrayList<>(), chats = new ArrayList<>();
        for (int index = 0; index < previous.length; index++) {
            if (java.util.Objects.equals(topic, previous[index].topic)) topics.add(index);
            if (chat != null && java.util.Objects.equals(chat, previous[index].chat)) chats.add(index);
        }
        return "hasTopic=" + (topic != null) + ",hasChat=" + (chat != null)
            + ",previousTopic=" + topics + ",previousChat=" + chats;
    }

    private static String sameIdentity(Object left, Object right) {
        if (left == null || right == null) return "missing";
        return "topic=" + java.util.Objects.equals(Reflector.callMethod(left, "getTopicId"), Reflector.callMethod(right, "getTopicId"))
            + ",chat=" + java.util.Objects.equals(Reflector.callMethod(left, "getChatId"), Reflector.callMethod(right, "getChatId"));
    }

    private static PopupResult popupContinuity(Context context, ClassLoader loader, Topic first,
        List<String> createdTopics) throws Exception {
        try (PopupGuard guard = new PopupGuard(loader)) {
            open(context, route(context, loader, first));
            awaitRoute(context, loader, first);
            goHome(context);
            guard.wake(context);
            awaitPopupTopic(context, loader, first);
            check(state("pending") == null, "recent popup did not consume the native pending route");
            report(context, "CONVERSATION_POPUP_CONTINUE_DONE");
            guard.dismiss(context, loader);

            MainThread.call(() -> {
                ((ConversationPolicy)state("policy")).userActivity(System.currentTimeMillis() - 61_000);
                Reflector.callStaticMethod(NativeConversationHook.class, "save");
                return null;
            });
            goHome(context);
            guard.wake(context);
            Topic fresh = awaitFreshPopupTopic(context, loader, createdTopics);
            check(fresh != null && fresh.topic != null && !fresh.topic.isBlank()
                && !createdTopics.contains(fresh.topic), "expired real popup reused an old native topic");
            createdTopics.add(fresh.topic);
            check(!first.chat.equals(fresh.chat), "expired real popup retained the old fullscreen chat");
            check(state("pending") == null, "expired popup did not consume the native pending route");
            report(context, "CONVERSATION_POPUP_TIMEOUT_DONE");
            guard.dismiss(context, loader);

            goHome(context);
            guard.wake(context);
            awaitPopupTopic(context, loader, fresh);
            report(context, "CONVERSATION_POPUP_REPEAT_DONE");
            guard.dismiss(context, loader);
            goHome(context);
            launch(context);
            awaitRoute(context, loader, fresh);
            guard.checkSafety();
            report(context, "CONVERSATION_POPUP_TO_FULLSCREEN_DONE");
            return new PopupResult("three real VoiceService popup wakes, native reset and visible system window verified; "
                + "actual popup card controller and popup page share topic/chat with the canonical selection; "
                + "expired popup creates topic, recent popup/fullscreen route continues it; "
                + guard.warmupStarts.get() + " owned recorder warmups and " + guard.asrStarts.get()
                + " owned ASR starts blocked before recording; audio/model failsafes unused", fresh);
        }
    }

    /** A short, nonce-owned probe guard. The real wake/router/UI path runs through unchanged. */
    private static final class PopupGuard implements AutoCloseable {
        private static final String KEY = "io.github.miaiplus.CONVERSATION_POPUP_PROBE";
        private final String nonce = UUID.randomUUID().toString();
        private final List<HookCallback.Unhook> hooks = new ArrayList<>();
        private final AtomicInteger voiceEntries = new AtomicInteger(), resetEntries = new AtomicInteger();
        private final AtomicInteger warmupStarts = new AtomicInteger();
        private final AtomicInteger asrStarts = new AtomicInteger(), unexpectedStarts = new AtomicInteger();
        private final AtomicInteger audioAttempts = new AtomicInteger(), modelAttempts = new AtomicInteger();
        private final ClassLoader loader;
        private volatile boolean armed;
        private Context wakeContext;
        private boolean popupPending;

        PopupGuard(ClassLoader loader) throws Exception {
            this.loader = loader;
            try {
                hooks.add(Reflector.findAndHookMethod("com.xiaomi.voiceassistant.VoiceService", loader,
                    "processIntent", Intent.class, new HookCallback() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (armed && owned((Intent)p.args[0])) voiceEntries.incrementAndGet();
                        }
                    }));
                // VS.preloadRecorder runs before the UI-reset/ASR facade on both pinned APKs.
                // Suppress only this owned wake's recorder warmup; the real wake and UI still run.
                hooks.add(Reflector.findAndHookMethod("com.xiaomi.voiceassistant.VoiceService", loader,
                    HostCompatibility.choose("t0", "y0"), Intent.class, String.class, new HookCallback(10_000) {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (armed && owned((Intent)p.args[0])) {
                                warmupStarts.incrementAndGet();
                                p.setResult(null);
                            }
                        }
                    }));
                hooks.add(Reflector.findAndHookMethod("com.xiaomi.voiceassistant.mainui.c4", loader,
                    HostCompatibility.choose("K", "L"), boolean.class, new HookCallback() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            p.setObjectExtra(KEY, armed && owned((Intent)Reflector.getObjectField(p.thisObject, "a")));
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (Boolean.TRUE.equals(p.getObjectExtra(KEY))) resetEntries.incrementAndGet();
                        }
                    }));
                int starts = 0;
                for (Class<?> type : new Class<?>[]{Reflector.findClass("p31.g", loader),
                    Reflector.findClass(HostCompatibility.choose("com.xiaomi.voiceassistant.m2",
                        "com.xiaomi.voiceassistant.s2"), loader)}) {
                    for (var method : type.getDeclaredMethods()) {
                        if (!java.util.Set.of("startSpeechRecognizeByVoice", "startSpeechRecognizeWithoutVoice",
                            "startSpeechRecognize").contains(method.getName()) || method.getReturnType() != void.class) continue;
                        starts++;
                        hooks.add(Hooks.hookMethod(method, new HookCallback(10_000) {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                if (!armed) return;
                                Intent request = null;
                                for (Object value : p.args) if (value instanceof Intent intent) request = intent;
                                if (owned(request)) asrStarts.incrementAndGet();
                                else unexpectedStarts.incrementAndGet();
                                // Never schedule the recorder or a text request while this owned wake is armed.
                                p.setResult(null);
                            }
                        }));
                    }
                }
                check(starts >= 4, "pinned native recognition entry points unavailable");
                int recorders = 0;
                for (var method : android.media.AudioRecord.class.getDeclaredMethods()) {
                    if (!method.getName().equals("startRecording")) continue;
                    recorders++;
                    hooks.add(Hooks.hookMethod(method, new HookCallback(10_000) {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (armed) { audioAttempts.incrementAndGet(); p.setResult(null); }
                        }
                    }));
                }
                check(recorders > 0, "recorder failsafe unavailable");
                int clients = 0;
                for (var method : Reflector.findClass("qm.c", loader).getDeclaredMethods()) {
                    if (!method.getName().equals("streamCompletion")) continue;
                    clients++;
                    hooks.add(Hooks.hookMethod(method, new HookCallback(10_000) {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!armed) return;
                            modelAttempts.incrementAndGet();
                            p.setThrowable(new IllegalStateException("owned popup probe blocked an unexpected model request"));
                        }
                    }));
                }
                check(clients > 0, "model request failsafe unavailable");
            } catch (Exception | Error failure) {
                close();
                throw failure;
            }
        }

        private boolean owned(Intent intent) {
            return intent != null && nonce.equals(intent.getStringExtra(KEY));
        }

        void wake(Context context) throws Exception {
            // Native updateScreenManager chooses FULL_SCREEN while any host is foreground or paused.
            // HOME's transition is asynchronous; wait for that native readiness instead of racing it.
            long homeDeadline = SystemClock.elapsedRealtime() + 10_000;
            boolean fullScreen;
            do {
                fullScreen = MainThread.call(() -> {
                    Object ui = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.UiManager", loader),
                        "getInstance", context);
                    return Boolean.TRUE.equals(Reflector.callMethod(ui, "isFullScreenInForeground"));
                });
                if (!fullScreen) break;
                Thread.sleep(100);
            } while (SystemClock.elapsedRealtime() < homeDeadline);
            check(!fullScreen, "native fullscreen host did not leave foreground after HOME"
                + diagnostics(context, loader));
            int voiceBefore = voiceEntries.get(), resetBefore = resetEntries.get(), asrBefore = asrStarts.get();
            int warmupBefore = warmupStarts.get();
            wakeContext = context;
            popupPending = true;
            armed = true;
            MainThread.call(() -> {
                context.startService(new Intent("com.miui.voiceassist.ACTION_VOICE_START_VOICEASSIST")
                    .setClassName(context.getPackageName(), "com.xiaomi.voiceassistant.VoiceService")
                    .putExtra(KEY, nonce));
                return null;
            });
            long deadline = SystemClock.elapsedRealtime() + 15_000;
            do {
                checkSafety();
                boolean popup = MainThread.call(() -> {
                    Object ui = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.UiManager", loader),
                        "getInstance", context);
                    View root = (View)Reflector.callMethod(manager(context, loader), "getRootLayout");
                    return !Boolean.TRUE.equals(Reflector.callMethod(ui, "isFullScreenInForeground"))
                        && root != null && root.isAttachedToWindow() && root.isShown()
                        && root.getLayoutParams() instanceof WindowManager.LayoutParams params
                        && params.type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW;
                });
                if (popup && voiceEntries.get() > voiceBefore && resetEntries.get() > resetBefore
                    && warmupStarts.get() > warmupBefore && asrStarts.get() > asrBefore) {
                    // Recognition is blocked in this route-only probe, so no result card would
                    // demand the lazy view. The facade only restores a view when saved cards
                    // exist. Follow the host's first-card lazy initialization path explicitly;
                    // the reply probe verifies normal text creates it without either call.
                    MainThread.call(() -> {
                        Object floatManager = manager(context, loader);
                        Reflector.callMethod(floatManager, "initFloatScreenResultUi", (Object)null);
                        Object controller = NativeConversationRouting.popupController(floatManager);
                        check(controller != null, "native popup result controller unavailable");
                        if (Reflector.callMethod(controller, "getFloatResultCardView") == null) {
                            Object root = Reflector.callMethod(floatManager, "getRootLayout");
                            check(root != null, "native popup root unavailable during lazy initialization");
                            // cm1.g/mn1.g's first-card coroutine calls w(root, null, false) on
                            // both pinned APKs. This inflates/binds the real result view only.
                            Reflector.callMethod(controller, "w", root, null, false);
                        }
                        return null;
                    });
                    return;
                }
                Thread.sleep(100);
            } while (SystemClock.elapsedRealtime() < deadline);
            String window = MainThread.call(() -> {
                Object floatManager = manager(context, loader);
                Object ui = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.UiManager", loader),
                    "getInstance", context);
                View root = (View)Reflector.callMethod(floatManager, "getRootLayout");
                String rootState = root == null ? "root=null" : "root=" + root.getClass().getName()
                    + ",attached=" + root.isAttachedToWindow() + ",shown=" + root.isShown()
                    + ",windowType=" + (root.getLayoutParams() instanceof WindowManager.LayoutParams params
                        ? params.type : "not-system-layout");
                // Native windowNotAdded treats an existing fullscreen container as an attached window.
                // These getters distinguish stale fullscreen routing from a missing popup window.
                return rootState + ",screenType=" + Reflector.callMethod(ui, "getCurrentScreenType")
                    + ",nativeWindowNotAdded=" + Reflector.callMethod(floatManager, "windowNotAdded")
                    + ",hasPageContainer=" + (Reflector.callMethod(floatManager, "getPageContainer") != null);
            });
            throw new AssertionError("real nonce VoiceService wake did not reach native reset/ASR entry and visible system popup; "
                + "voiceCalls=" + (voiceEntries.get() - voiceBefore) + ",resetCalls=" + (resetEntries.get() - resetBefore)
                + ",blockedWarmups=" + (warmupStarts.get() - warmupBefore)
                + ",blockedOwnedAsr=" + (asrStarts.get() - asrBefore) + ";" + window
                + diagnostics(context, loader));
        }

        void dismiss(Context context, ClassLoader loader) throws Exception {
            MainThread.call(() -> {
                Object ui = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.UiManager", loader),
                    "getInstance", context);
                Reflector.callMethod(ui, "exeBackNoAnimAndNotStopEngine");
                return null;
            });
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            do {
                boolean visible = MainThread.call(() -> {
                    View root = (View)Reflector.callMethod(manager(context, loader), "getRootLayout");
                    return root != null && root.isAttachedToWindow() && root.isShown();
                });
                if (!visible) { popupPending = false; checkSafety(); return; }
                Thread.sleep(100);
            } while (SystemClock.elapsedRealtime() < deadline);
            throw new AssertionError("owned popup did not dismiss");
        }

        void checkSafety() {
            String counters = ";voice=" + voiceEntries.get() + ",reset=" + resetEntries.get()
                + ",blockedWarmups=" + warmupStarts.get() + ",blockedAsr=" + asrStarts.get();
            check(unexpectedStarts.get() == 0, "recognition start lost the owned wake nonce" + counters);
            check(audioAttempts.get() == 0, "audio recorder startup reached the probe failsafe" + counters);
            check(modelAttempts.get() == 0, "model request startup reached the probe failsafe" + counters);
        }

        @Override public void close() {
            RuntimeException cleanupFailure = null;
            // A failed wake must be stopped while the recorder guard is still installed.
            if (armed && popupPending && wakeContext != null) {
                try {
                    MainThread.call(() -> {
                        Object ui = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.UiManager", loader),
                            "getInstance", wakeContext);
                        Reflector.callMethod(ui, "stopEngine", true);
                        Reflector.callMethod(ui, "exeBackNoAnimAndNotStopEngine");
                        return null;
                    });
                } catch (Exception failure) {
                    cleanupFailure = new IllegalStateException("owned popup wake cleanup failed", failure);
                }
            }
            armed = false;
            for (int index = hooks.size() - 1; index >= 0; index--) {
                try { hooks.get(index).unhook(); }
                catch (RuntimeException failure) {
                    if (cleanupFailure == null) cleanupFailure = failure;
                    else cleanupFailure.addSuppressed(failure);
                }
            }
            hooks.clear();
            if (cleanupFailure != null) throw cleanupFailure;
        }
    }

    private static Object state(String name) {
        return Reflector.getStaticObjectField(NativeConversationHook.class, name);
    }
    private static void setState(String name, Object value) throws Exception {
        var field = NativeConversationHook.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
    private static Map<String, Object> snapshot() {
        Map<String, Object> saved = new HashMap<>();
        for (var field : NativeConversationHook.class.getDeclaredFields())
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                && !java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                saved.put(field.getName(), state(field.getName()));
        ConversationPolicy policy = (ConversationPolicy)saved.get("policy");
        if (policy != null) saved.put("policy", new ConversationPolicy(policy.lastActivity(), policy.hasConversation()));
        return saved;
    }

    private static void restorePreferences(SharedPreferences prefs, Map<String, ?> values) {
        var edit = prefs.edit();
        for (String key : new String[]{"conversation", "lastActivity", "selected"}) {
            Object value = values.get(key);
            if (value == null) edit.remove(key);
            else if (value instanceof String v) edit.putString(key, v);
            else if (value instanceof Long v) edit.putLong(key, v);
            else if (value instanceof Boolean v) edit.putBoolean(key, v);
        }
        check(edit.commit(), "host conversation settings did not restore");
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

    private static Object localRepository(Context context, ClassLoader loader) {
        Object entry = Reflector.callStaticMethod(Reflector.findClass("n02.e", loader), "fromApplication", context,
            Reflector.findClass("pe1.q", loader));
        return Reflector.getObjectField(Reflector.getObjectField(
            Reflector.callMethod(entry, "getTopicByIdUseCase"), "a"), "a");
    }

    private static Object group(ClassLoader loader, Topic topic) {
        Object type = Reflector.getStaticObjectField(Reflector.findClass(
            "com.miui.voiceassist.model.topicgroup.TopicType", loader), "MAIN_AGENT_SESSION");
        return Reflector.newInstance(Reflector.findClass("com.miui.voiceassist.model.topicgroup.TopicGroup", loader),
            topic.topic, "MiAIPlus continuity test", System.currentTimeMillis(), false, 0, null,
            "osbot.main", topic.chat, null, Reflector.callMethod(type, "getRaw"), true);
    }

    private static Object info(ClassLoader loader, Topic topic) {
        Object type = Reflector.getStaticObjectField(Reflector.findClass(
            "com.miui.voiceassist.model.topicgroup.TopicType", loader), "MAIN_AGENT_SESSION");
        return Reflector.newInstance(Reflector.findClass(
            "com.xiaomi.voiceassistant.data.model.TopicGroupInfo", loader),
            topic.topic, "osbot.main", "MiAIPlus continuity test", topic.chat, null,
            Reflector.callMethod(type, "getRaw"));
    }

    private static Intent route(Context context, ClassLoader loader, Topic topic) {
        Object page = Reflector.newInstance(Reflector.findClass(
            "com.xiaomi.voiceassistant.data.model.PageNavigationData", loader), info(loader, topic), "quick");
        return ConversationReturn.route(context, page);
    }

    private static Object manager(Context context, ClassLoader loader) {
        Object ui = Reflector.callStaticMethod(Reflector.findClass(
            "com.xiaomi.voiceassistant.UiManager", loader), "getInstance", context);
        return Reflector.callMethod(ui, "getFloatManager");
    }

    private static Object currentInfo(Context context, ClassLoader loader) {
        Object cards = Reflector.callMethod(manager(context, loader), "getTopicDialogCardManager");
        return cards == null ? null : Reflector.callMethod(cards, "getTopicGroupInfo");
    }

    private static Topic current(Context context, ClassLoader loader) throws Exception {
        return MainThread.call(() -> {
            Object info = currentInfo(context, loader);
            return info == null ? null : new Topic((String)Reflector.callMethod(info, "getTopicId"),
                (String)Reflector.callMethod(info, "getChatId"));
        });
    }

    private static Topic popupTopic(Context context, ClassLoader loader) {
        Object popup = NativeConversationRouting.popupInfo(manager(context, loader));
        Object page = NativeConversationRouting.popupPage(manager(context, loader));
        Object visible = page == null ? null : Reflector.callMethod(page, "getTopicGroupInfo");
        Object canonical = state("conversationInfo");
        if (popup == null || visible == null || canonical == null || state("pending") != null) return null;
        Topic actual = new Topic((String)Reflector.callMethod(popup, "getTopicId"),
            (String)Reflector.callMethod(popup, "getChatId"));
        for (Object info : new Object[]{visible, canonical}) {
            if (!java.util.Objects.equals(actual.topic, Reflector.callMethod(info, "getTopicId"))
                || !java.util.Objects.equals(actual.chat, Reflector.callMethod(info, "getChatId"))) return null;
        }
        return actual;
    }

    private static Topic awaitFreshPopupTopic(Context context, ClassLoader loader,
        List<String> previousTopics) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do {
            Topic actual = MainThread.call(() -> popupTopic(context, loader));
            if (actual != null && actual.topic != null && !actual.topic.isBlank()
                && actual.chat != null && !actual.chat.isBlank() && !previousTopics.contains(actual.topic)) return actual;
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("expired popup did not settle a new topic/chat across its own cards, popup page and canonical selection"
            + diagnostics(context, loader));
    }

    private static void awaitPopupTopic(Context context, ClassLoader loader, Topic expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do {
            Topic actual = MainThread.call(() -> popupTopic(context, loader));
            if (expected.equals(actual)) return;
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("popup's own cards and popup page did not match the canonical owned topic/chat"
            + diagnostics(context, loader, expected));
    }

    private static void open(Context context, Intent intent) throws Exception {
        Object interaction = Reflector.getStaticObjectField(XiaoAiHook.class, "interaction");
        Reflector.callMethod(interaction, "userEnteredHost");
        MainThread.call(() -> { context.startActivity(intent); return null; });
    }

    private static void goHome(Context context) throws Exception {
        MainThread.call(() -> {
            context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return null;
        });
        Thread.sleep(500);
    }

    private static void launch(Context context) throws Exception {
        MainThread.call(() -> {
            context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(context.getPackageName(), "com.xiaomi.voiceassistant.LaunchHomeRouterActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            return null;
        });
    }

    private static Topic awaitNewTopic(Context context, ClassLoader loader, Topic... previous) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        do {
            Topic settled = MainThread.call(() -> {
                if (state("pending") != null) return null;
                Object cards = currentInfo(context, loader);
                Object canonical = state("conversationInfo");
                Object page = currentPage(loader);
                Object visible = page == null ? null : Reflector.callMethod(page, "getTopicGroupInfo");
                if (cards == null || canonical == null || visible == null) return null;
                Topic actual = new Topic((String)Reflector.callMethod(cards, "getTopicId"),
                    (String)Reflector.callMethod(cards, "getChatId"));
                if (actual.topic == null || actual.topic.isBlank()
                    || java.util.Arrays.stream(previous).anyMatch(topic -> actual.topic.equals(topic.topic))) return null;
                for (Object info : new Object[]{canonical, visible}) {
                    if (!actual.topic.equals(Reflector.callMethod(info, "getTopicId"))
                        || !java.util.Objects.equals(actual.chat, Reflector.callMethod(info, "getChatId"))) return null;
                }
                return actual;
            });
            if (settled != null) return settled;
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("real launcher wake did not settle a distinct native topic across pending/canonical/page state"
            + diagnostics(context, loader, previous));
    }

    private static void awaitRoute(Context context, ClassLoader loader, Topic expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        do {
            boolean matched = MainThread.call(() -> {
                Object page = currentPage(loader);
                Object info = page == null ? null : Reflector.callMethod(page, "getTopicGroupInfo");
                return info != null && expected.topic.equals(Reflector.callMethod(info, "getTopicId"))
                    && java.util.Objects.equals(expected.chat, Reflector.callMethod(info, "getChatId"));
            });
            if (matched && expected.equals(current(context, loader))) return;
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("real launcher wake did not synchronize visible page and native topic/chat"
            + diagnostics(context, loader, expected));
    }

    private static void awaitOriginalRoute(Context context, ClassLoader loader, Intent route) throws Exception {
        Object expectedPage = route.getParcelableExtra("page_navigation_data");
        Object expectedInfo = expectedPage == null ? null : Reflector.callMethod(expectedPage, "getTopicGroupInfo");
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do {
            boolean matched = MainThread.call(() -> {
                if (HostCompatibility.modern() && expectedInfo == null) {
                    Activity host = resumedHost();
                    if (host == null || host.isFinishing() || host.isDestroyed()
                        || !host.getWindow().getDecorView().isShown()) return false;
                    Object navigation = Reflector.callMethod(Reflector.callMethod(
                        Reflector.callMethod(host, "w2"), "getUiState"), "getValue");
                    Object main = Reflector.getStaticObjectField(Reflector.findClass("pm1.q$f", loader), "a");
                    Object selection = Reflector.callMethod(navigation, "getListSelection");
                    Object mainType = Reflector.getStaticObjectField(Reflector.findClass("pm1.r0", loader),
                        "MAIN_CONVERSATION");
                    Object ui = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.UiManager", loader),
                        "getInstance", context);
                    Object full = Reflector.callMethod(ui, "getFullScreenManager");
                    // Native MAIN_CONVERSATION deliberately clears PageNavigationData (V=null).
                    // Require its exact route, selection, visible host and main-card mode instead.
                    return main.equals(Reflector.callMethod(navigation, "getDetailRoute"))
                        && selection != null && mainType.equals(Reflector.callMethod(selection, "getType"))
                        && Reflector.callMethod(host, "getPageNavigationData") == null
                        && full != null && !Boolean.TRUE.equals(Reflector.callMethod(full, "isTopicDialogPageExcludeAgent"))
                        && state("pending") == null && state("conversationInfo") == null;
                }
                Object page = currentPage(loader);
                if (page == null) return false;
                Object info = Reflector.callMethod(page, "getTopicGroupInfo");
                if (expectedInfo == null || info == null) return expectedInfo == info;
                return java.util.Objects.equals(Reflector.callMethod(expectedInfo, "getTopicId"),
                    Reflector.callMethod(info, "getTopicId"))
                    && java.util.Objects.equals(Reflector.callMethod(expectedInfo, "getChatId"),
                        Reflector.callMethod(info, "getChatId"));
            });
            if (matched) return;
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("original native conversation route did not restore"
            + diagnostics(context, loader));
    }

    private static void awaitNativeNotificationSelection(ClassLoader loader, Topic expected, long since) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do {
            boolean selected = MainThread.call(() -> {
                ConversationPolicy policy = (ConversationPolicy)state("policy");
                Object info = state("conversationInfo");
                return policy.lastActivity() >= since && state("pending") == null && info != null
                    && expected.topic.equals(Reflector.callMethod(info, "getTopicId"))
                    && expected.chat.equals(Reflector.callMethod(info, "getChatId"));
            });
            if (selected) return;
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("native notification onNewIntent did not select its owned topic/chat and refresh activity");
    }

    private static Object currentPage(ClassLoader loader) {
        Activity host = resumedHost();
        return host == null ? null : Reflector.callMethod(host, "getPageNavigationData");
    }

    private static Activity resumedHost() {
        Object thread = Reflector.callStaticMethod(Reflector.findClass(
            "android.app.ActivityThread", null), "currentActivityThread");
        for (Object record : ((Map<?, ?>)Reflector.getObjectField(thread, "mActivities")).values()) {
            Activity activity = (Activity)Reflector.getObjectField(record, "activity");
            if (!Boolean.TRUE.equals(Reflector.getObjectField(record, "paused"))
                && activity.getClass().getName().equals(HostCompatibility.chatActivity()))
                return activity;
        }
        return null;
    }

    private static Topic awaitTopic(Context context, ClassLoader loader, Topic expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do {
            Topic actual = current(context, loader);
            if (expected.equals(actual)) return actual;
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("native topic/chat did not match the owned conversation");
    }

    private static void localCall(ClassLoader loader, Object local, String method, Object value) throws Exception {
        suspend(loader, continuation -> Reflector.callMethod(local, method, value, continuation));
    }

    private interface Call { Object invoke(Object continuation); }
    private static Object suspend(ClassLoader loader, Call call) throws Exception {
        CompletableFuture<Object> completion = new CompletableFuture<>();
        Object empty = Reflector.getStaticObjectField(Reflector.findClass(
            "kotlin.coroutines.EmptyCoroutineContext", loader), "INSTANCE");
        Object continuation = Proxy.newProxyInstance(loader, new Class<?>[]{Reflector.findClass(
            "kotlin.coroutines.Continuation", loader)}, (proxy, method, args) -> switch (method.getName()) {
                case "getContext" -> empty;
                case "resumeWith" -> { completion.complete(args[0]); yield null; }
                case "toString" -> "MiAIPlus owned continuity probe";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
        Object value = call.invoke(continuation);
        Object suspended = Reflector.callStaticMethod(Reflector.findClass(
            "kotlin.coroutines.intrinsics.IntrinsicsKt", loader), "getCOROUTINE_SUSPENDED");
        Object result = value == suspended ? completion.get(40, TimeUnit.SECONDS) : value;
        Reflector.callStaticMethod(Reflector.findClass("kotlin.ResultKt", loader), "throwOnFailure", result);
        return result;
    }

    private static void report(Context context, String stage) {
        Bundle value = new Bundle(); value.putString("report", stage);
        context.getContentResolver().call(BridgeProvider.URI, "hook_report", null, value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
