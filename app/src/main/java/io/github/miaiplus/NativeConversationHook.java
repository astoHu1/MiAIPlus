package io.github.miaiplus;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.util.List;
import java.lang.reflect.Proxy;
import org.json.JSONObject;

/** One user-driven conversation lifetime for the host's fullscreen and system-window entries. */
final class NativeConversationHook {
    private static final String PREFS = "miaiplus_conversation";
    private static final String PAGE = "page_navigation_data";
    private static ConversationPolicy policy;
    private static SharedPreferences preferences;
    private static Context application;
    private static ClassLoader hostLoader;
    private static Object conversationInfo;
    private static Pending pending;
    private static int internalNavigation;
    private static long lastInputUptime;
    private static Handler main;
    private static boolean launcherPending;
    private static long generation;
    private static long inputSequence;
    private static Object automaticNavigationInfo;
    private static long automaticNavigationUntil;
    private static boolean disposed = true;
    private record Pending(ConversationPolicy.Decision decision, long timestamp, long deadline, long inputSequence) { }

    private NativeConversationHook() { }

    static void install(Context context, ClassLoader loader, List<HookCallback.Unhook> hooks) throws Exception {
        // Application.attach runs before ContextImpl can return its application context.
        application = context instanceof android.app.Application ? context : context.getApplicationContext();
        hostLoader = loader;
        main = new Handler(Looper.getMainLooper());
        reinitializeState(application, loader);
        hooks.add(Reflector.findAndHookMethod("com.xiaomi.voiceassistant.VoiceService", loader,
            "processIntent", Intent.class, new HookCallback() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Intent intent = (Intent)p.args[0];
                    if (isWakeIntent(intent)) wakeSafely(context, loader);
                }
            }));
        hooks.add(Reflector.findAndHookMethod("com.xiaomi.voiceassistant.LaunchHomeRouterActivity", loader,
            "onCreate", Bundle.class, new HookCallback() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Intent intent = ((Activity)p.thisObject).getIntent();
                    if (isLauncherIntent(intent)) {
                        wakeSafely(context, loader);
                        launcherPending = pending != null;
                    }
                }
            }));
        // Both exact host APKs reset their float manager before this method. Its body then starts
        // recognition or submits the text query, so route before either can capture a stale main chat.
        hooks.add(Reflector.findAndHookMethod("com.xiaomi.voiceassistant.mainui.c4", loader,
            HostCompatibility.choose("K", "L"), boolean.class, new HookCallback() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    applySafely(context, loader);
                    Intent intent = (Intent)Reflector.callMethod(p.thisObject, "getCurrentIntent");
                    if (isTextSubmission(intent)) userActivity();
                }
            }));
        hooks.add(Reflector.findAndHookMethod("ll1.f0", loader, HostCompatibility.choose("A", "B"),
            String.class, String.class, new HookCallback() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { userActivity(); }
            }));
        hooks.add(Reflector.findAndHookMethod(HostCompatibility.choose("ll1.f0$b", "rm1.f0$b"), loader,
            "onResults", Reflector.findClass(HostCompatibility.choose("t51.b", "l61.b"), loader), new HookCallback() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Handler handler = main;
                    long expectedGeneration = generation;
                    if (!disposed && handler != null) handler.post(() -> {
                        if (generation == expectedGeneration) userActivity();
                    });
                }
            }));
        hooks.add(Reflector.findAndHookMethod("com.xiaomi.voiceassistant.deepthink.b", loader,
            "handleNotificationClick", new HookCallback() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Exception {
                    MainThread.call(() -> {
                        if (disposed) return null;
                        // This exact host method is entered by the notification click, including
                        // its main-chat/no-PAGE form. Model streaming never enters this method.
                        pending = null;
                        launcherPending = false;
                        userActivity();
                        policy.selected(System.currentTimeMillis());
                        save();
                        return null;
                    });
                }
            }));
        Class<?> info = Reflector.findClass("com.xiaomi.voiceassistant.data.model.TopicGroupInfo", loader);
        Object[] topicSignature = HostCompatibility.modern()
            ? new Object[]{info, boolean.class, boolean.class, topicObserver()}
            : new Object[]{info, boolean.class, topicObserver()};
        hooks.add(Reflector.findAndHookMethod("com.xiaomi.voiceassistant.widget.s1", loader,
            "startTopicDialogPage", topicSignature));
        // A history selection may occur without a new Intent. Observe the native topic initialization,
        // but only a recent real input or an explicit Activity route may advance the activity clock.
        hooks.add(Reflector.findAndHookMethod(HostCompatibility.choose("el1.s2", "jm1.v2"), loader,
            "startTopicDialog", info, boolean.class, topicObserver()));
        hooks.add(Reflector.findAndHookMethod(HostCompatibility.choose("el1.s2", "jm1.v2"), loader,
            "getTopicGroupInfo", new HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (disposed || conversationInfo == null || p.getResult() == null
                        || Looper.myLooper() != Looper.getMainLooper()) return;
                    Object updated = p.getResult();
                    if (java.util.Objects.equals(Reflector.callMethod(conversationInfo, "getTopicId"),
                        Reflector.callMethod(updated, "getTopicId"))) {
                        // The native empty topic acquires agent/chat IDs on its first accepted turn.
                        // Persist that identity without treating its reply as another user operation.
                        conversationInfo = updated;
                        save();
                    }
                }
            }));
        for (String method : new String[]{"onCreate", "onNewIntent"}) {
            hooks.add(Reflector.findAndHookMethod(HostCompatibility.chatActivity(), loader, method,
                method.equals("onCreate") ? Bundle.class : Intent.class, new HookCallback() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        Intent intent = method.equals("onCreate") ? ((Activity)p.thisObject).getIntent() : (Intent)p.args[0];
                        if (hasExplicitRoute(intent)) explicitRoute(intent);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Intent intent = method.equals("onCreate") ? ((Activity)p.thisObject).getIntent() : (Intent)p.args[0];
                        // A native launcher can attach a restored old PAGE itself. That is still
                        // the same wake, and must reach the queued replacement after initialization.
                        if (pending != null) queueApply(context, loader);
                    }
                }));
        }
        hooks.add(Reflector.findAndHookMethod(HostCompatibility.chatActivity(), loader, "onResume", new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                // The router may move an existing task forward without onNewIntent. Wait until
                // its native resume/route restoration has run before replacing the launcher route.
                if (launcherPending) queueApply(context, loader, true);
            }
        }));
        hooks.add(Hooks.hookMethod(Activity.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class),
            touchObserver()));
        hooks.add(Hooks.hookMethod(Activity.class.getDeclaredMethod("dispatchKeyEvent", KeyEvent.class),
            keyObserver()));
        Class<?> floatRoot = Reflector.findClass("com.xiaomi.voiceassistant.mainui.board.FloatViewRootLayout", loader);
        hooks.add(Reflector.findAndHookMethod(floatRoot, "dispatchTouchEvent", MotionEvent.class, touchObserver()));
        hooks.add(Reflector.findAndHookMethod(floatRoot, "dispatchKeyEvent", KeyEvent.class, keyObserver()));
        Class<?> callback = Reflector.findClass("kotlin.jvm.functions.Function1", loader);
        String topics = HostCompatibility.choose("pe1.v", "sf1.w");
        hooks.add(Reflector.findAndHookMethod(topics, loader, "deleteConversationGroup", String.class, callback,
            deletionObserver(loader, false)));
        hooks.add(Reflector.findAndHookMethod(topics, loader, "clearAllConversationGroups", callback,
            deletionObserver(loader, true)));
    }

    private static HookCallback deletionObserver(ClassLoader loader, boolean all) {
        return new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                int index = all ? 0 : 1;
                Object original = p.args[index];
                String topic = all ? null : (String)p.args[0];
                long expectedGeneration = generation;
                p.args[index] = Proxy.newProxyInstance(loader,
                    new Class<?>[]{Reflector.findClass("kotlin.jvm.functions.Function1", loader)}, (proxy, method, args) -> {
                        if (method.getName().equals("invoke")) {
                            if (!disposed && generation == expectedGeneration && Boolean.TRUE.equals(args[0])) {
                                Handler handler = main;
                                if (handler != null) handler.post(() -> {
                                    if (generation == expectedGeneration) invalidateDeletedTopic(topic, all);
                                });
                            }
                            if (original != null) return Reflector.callMethod(original, "invoke", args[0]);
                            return Reflector.getStaticObjectField(Reflector.findClass("kotlin.Unit", loader), "INSTANCE");
                        }
                        if (method.getName().equals("toString")) return "MiAIPlus conversation deletion observer";
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) return proxy == args[0];
                        throw new UnsupportedOperationException(method.getName());
                    });
            }
        };
    }

    private static void invalidateDeletedTopic(String topic, boolean all) {
        if (disposed || policy == null) return;
        if (!all && (conversationInfo == null || !java.util.Objects.equals(topic,
            Reflector.callMethod(conversationInfo, "getTopicId")))) return;
        conversationInfo = null;
        pending = null;
        launcherPending = false;
        policy.invalidate();
        save();
    }

    private static HookCallback touchObserver() {
        return new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args[0] instanceof MotionEvent event && event.getActionMasked() == MotionEvent.ACTION_DOWN)
                    userActivity();
            }
        };
    }

    private static HookCallback keyObserver() {
        return new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args[0] instanceof KeyEvent event && event.getAction() == KeyEvent.ACTION_DOWN)
                    userActivity();
            }
        };
    }

    private static HookCallback topicObserver() {
        return new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (automaticNavigationInfo == null) return;
                if (SystemClock.elapsedRealtime() > automaticNavigationUntil) {
                    automaticNavigationInfo = null;
                    return;
                }
                // MainNavigator's q.b route feeds back through startTopicDialogPage(info, true).
                // The automatic wake already selected this exact metadata object before ASR starts;
                // its UI synchronization must not force-stop that wake's recognition engine.
                if (p.args[0] == automaticNavigationInfo) p.args[1] = false;
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (disposed || internalNavigation > 0) return;
                if (pending != null && inputSequence <= pending.inputSequence()) return;
                boolean explicit = SystemClock.elapsedRealtime() - lastInputUptime < 2_000;
                if (!explicit) return;
                try {
                    // Prefer the initialized info: a null new-topic argument is assigned an ID synchronously.
                    Object manager = floatManager(application, hostLoader);
                    capture(manager, true, System.currentTimeMillis());
                    pending = null;
                } catch (RuntimeException failure) { report(failure); }
            }
        };
    }

    static boolean isWakeIntent(Intent intent) {
        if (intent == null || hasExplicitRoute(intent)) return false;
        String action = intent.getAction();
        return "com.miui.voiceassist.ACTION_VOICE_START_VOICEASSIST".equals(action)
            || "com.miui.voicetrigger.ACTION_VOICE_TRIGGER_START_VOICEASSIST".equals(action)
            || "com.xiaoai.ACTION_BLUETOOTH_START_VOICEASSIST".equals(action)
            || Intent.ACTION_VOICE_COMMAND.equals(action) || Intent.ACTION_ASSIST.equals(action);
    }

    static boolean isLauncherIntent(Intent intent) {
        return intent != null && !hasExplicitRoute(intent)
            && ((Intent.ACTION_MAIN.equals(intent.getAction()) && intent.hasCategory(Intent.CATEGORY_LAUNCHER))
                || "com.xiaomi.voiceassistant.ACTION_SHORTCUT_START".equals(intent.getAction()))
            && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0;
    }

    static boolean hasExplicitRoute(Intent intent) { return intent != null && intent.hasExtra(PAGE); }

    static boolean isNativeNotification(Intent intent) {
        return intent != null && "deepthink_notification".equals(intent.getStringExtra("from"));
    }

    static boolean isTextSubmission(Intent intent) {
        return intent != null && intent.getStringExtra("assist_query") != null
            && !intent.getStringExtra("assist_query").isBlank()
            && (intent.getBooleanExtra("is_standard_edit_state", false)
                || "TEXT_INPUT".equals(intent.getStringExtra("request_source_type")));
    }

    /** Reload only our private identity metadata; hook installation stays owned by the outer lifecycle. */
    static void reinitializeState(Context context, ClassLoader loader) {
        generation++;
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encoded = preferences.getString("conversation", "");
        conversationInfo = readInfo(loader, encoded);
        policy = new ConversationPolicy(preferences.getLong("lastActivity", 0),
            preferences.getBoolean("selected", false) && (encoded.isEmpty() || conversationInfo != null));
        pending = null;
        launcherPending = false;
        automaticNavigationInfo = null;
        disposed = false;
    }

    private static void wakeSafely(Context context, ClassLoader loader) {
        try { MainThread.call(() -> { onUserWake(context, loader, System.currentTimeMillis()); return null; }); }
        catch (Exception failure) { report(failure); }
    }

    private static void applySafely(Context context, ClassLoader loader) {
        applySafely(context, loader, false);
    }

    private static void applySafely(Context context, ClassLoader loader, boolean launcherResumed) {
        try { MainThread.call(() -> { applyPending(context, loader, launcherResumed); return null; }); }
        catch (Exception failure) { report(failure); }
    }

    private static void queueApply(Context context, ClassLoader loader) {
        queueApply(context, loader, false);
    }

    private static void queueApply(Context context, ClassLoader loader, boolean launcherResumed) {
        Handler handler = main;
        Pending expected = pending;
        if (!disposed && handler != null && expected != null)
            handler.post(() -> {
                if (pending == expected) applySafely(context, loader, launcherResumed);
            });
    }

    /** Main-thread entry, shared by the real voice and launcher hooks and the device probe. */
    static ConversationPolicy.Decision onUserWake(Context context, ClassLoader loader, long now) {
        if (disposed) return ConversationPolicy.Decision.DISABLED;
        launcherPending = false;
        Bundle settings = settings(context);
        if (settings == null) { pending = null; return ConversationPolicy.Decision.DISABLED; }
        ConversationPolicy.Decision decision = policy.wake(now,
            settings.getInt("conversationTimeoutMinutes", Config.DEFAULT_CONVERSATION_TIMEOUT_MINUTES), settings.getBoolean("screenBusy", true));
        if (decision == ConversationPolicy.Decision.NEW || decision == ConversationPolicy.Decision.CONTINUE)
            pending = new Pending(decision, now, SystemClock.elapsedRealtime() + 10_000, inputSequence);
        else pending = null;
        return decision;
    }

    /** Execute only after the native UI reset, never in a timer or model-result callback. */
    static void applyPending(Context context, ClassLoader loader) {
        applyPending(context, loader, false);
    }

    private static void applyPending(Context context, ClassLoader loader, boolean launcherResumed) {
        Pending request = pending;
        if (disposed || request == null) return;
        if (SystemClock.elapsedRealtime() > request.deadline()) { pending = null; launcherPending = false; return; }
        Bundle settings = settings(context);
        if (settings == null || settings.getBoolean("screenBusy", true)
            || settings.getInt("conversationTimeoutMinutes", Config.DEFAULT_CONVERSATION_TIMEOUT_MINUTES) <= 0) {
            // A wake that lost permission to navigate must not be replayed by a later UI callback.
            pending = null;
            launcherPending = false;
            return;
        }
        // A router post or pre-ASR callback can run while an old Activity still exists but is
        // stopped. Consuming now lets its later inherited PAGE overwrite the new shared identity.
        if (launcherPending && !launcherResumed) return;
        Object manager = floatManager(context, loader);
        Object tabs = Reflector.callMethod(manager, "getTabHostManager");
        Object candidate = tabs == null ? null : Reflector.callMethod(tabs,
            HostCompatibility.choose("getTabHostMainActivity", "getConversationHost"));
        Object lifecycle = candidate == null ? null : Reflector.callMethod(tabs, "getActivityLifecycle");
        // UiManager's screen flag can still say fullscreen after HOME and during voice startup.
        // Navigate only a genuinely resumed conversation Activity; a paused old host belongs to
        // neither the new popup nor its route. The native voice path chooses its own screen next.
        Object host = isResumedConversationHost(candidate, lifecycle) ? candidate : null;
        if (launcherPending && host == null) return;
        pending = null;
        launcherPending = false;
        Object target = request.decision() == ConversationPolicy.Decision.NEW ? null : conversationInfo;
        // A user explicitly chose the native main conversation: retain its intent until the next expiry.
        if (request.decision() == ConversationPolicy.Decision.CONTINUE && target == null) {
            policy.selected(request.timestamp());
            save();
            return;
        }
        internalNavigation++;
        try {
            Object previousCards = Reflector.callMethod(manager, "getTopicDialogCardManager");
            Object previousInfo = previousCards == null ? null : Reflector.callMethod(previousCards, "getTopicGroupInfo");
            Object previousTopic = previousInfo == null ? null : Reflector.callMethod(previousInfo, "getTopicId");
            if (HostCompatibility.modern()) Reflector.callMethod(manager, "startTopicDialogPage", target, false, false);
            else Reflector.callMethod(manager, "startTopicDialogPage", target, false);
            if (request.decision() == ConversationPolicy.Decision.NEW) {
                Object cards = Reflector.callMethod(manager, "getTopicDialogCardManager");
                Object info = cards == null ? null : Reflector.callMethod(cards, "getTopicGroupInfo");
                Object topic = info == null ? null : Reflector.callMethod(info, "getTopicId");
                if (previousTopic != null && java.util.Objects.equals(previousTopic, topic)) {
                    // The controller treats an empty topic as "already a new conversation" and
                    // returns early. Expiry requires a new lifetime even for that empty topic.
                    // Call its real initialization path, including startNewSession and clearCards.
                    Reflector.callMethod(cards, "startTopicDialog", null, false);
                }
            }
            capture(manager, true, request.timestamp());
            if (host != null) navigateFullScreen(host, loader);
        } finally { internalNavigation--; }
    }

    static boolean isResumedConversationHost(Object host, Object lifecycle) {
        if (!(host instanceof Activity activity) || activity.isFinishing() || activity.isDestroyed()
            || lifecycle == null) return false;
        Object state = Reflector.callMethod(lifecycle, "getCurrentState");
        return state instanceof Enum<?> value && value.name().equals("RESUMED");
    }

    private static void navigateFullScreen(Object host, ClassLoader loader) {
        if (conversationInfo == null) return;
        Object page = Reflector.newInstance(Reflector.findClass("com.xiaomi.voiceassistant.data.model.PageNavigationData", loader),
            conversationInfo, "quick");
        if (HostCompatibility.modern()) {
            automaticNavigationInfo = conversationInfo;
            automaticNavigationUntil = SystemClock.elapsedRealtime() + 10_000;
            Object route = Reflector.newInstance(Reflector.findClass("pm1.q$b", loader), page);
            Object kind = Reflector.getStaticObjectField(Reflector.findClass("pm1.r0", loader), "CONVERSATION");
            Object key = Reflector.newInstance(Reflector.findClass("pm1.q0", loader), kind,
                Reflector.callMethod(conversationInfo, "getTopicId"));
            Reflector.callMethod(host, "navigateToDetail", route, key, false, false);
        } else {
            Reflector.callMethod(host, "setPageNavigationData", page, "MiAIPlus_conversation_wake");
        }
    }

    /** The marker is native navigation data; do not reinterpret notification/history as a fresh wake. */
    private static void explicitRoute(Intent intent) {
        if (disposed) return;
        try {
            boolean moduleReturn = intent.getBooleanExtra("io.github.miaiplus.CONVERSATION_RETURN", false);
            boolean nativeNotification = isNativeNotification(intent);
            // startTabHostMainActivity fills a missing PAGE from the currently visible route.
            // During a pending wake that inherited page is initialization, not a history choice.
            if (!ConversationPolicy.acceptsExplicitRoute(pending != null, moduleReturn || nativeNotification,
                pending == null ? 0 : pending.inputSequence(), inputSequence)) return;
            Object page = intent.getParcelableExtra(PAGE);
            conversationInfo = page == null ? null : Reflector.callMethod(page, "getTopicGroupInfo");
            pending = null;
            launcherPending = false;
            automaticNavigationInfo = null;
            if (moduleReturn || nativeNotification || SystemClock.elapsedRealtime() - lastInputUptime < 2_000)
                policy.selected(System.currentTimeMillis());
            save();
        } catch (RuntimeException failure) { report(failure); }
    }

    private static void userActivity() {
        if (disposed) return;
        inputSequence++;
        lastInputUptime = SystemClock.elapsedRealtime();
        // A launcher's first touch must not consume the expired timestamp before wake is applied.
        if (pending != null) return;
        policy.userActivity(System.currentTimeMillis());
        save();
    }

    private static Bundle settings(Context context) {
        try { return context.getContentResolver().call(BridgeProvider.URI, "conversation_settings", null, null); }
        catch (RuntimeException failure) { report(failure); return null; }
    }

    private static Object floatManager(Context context, ClassLoader loader) {
        Object ui = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.UiManager", loader),
            "getInstance", context);
        return Reflector.callMethod(ui, "getFloatManager");
    }

    private static void capture(Object manager, boolean selected, long now) {
        Object cards = Reflector.callMethod(manager, "getTopicDialogCardManager");
        if (cards == null) throw new IllegalStateException("Native conversation manager unavailable");
        Object info = Reflector.callMethod(cards, "getTopicGroupInfo");
        if (info == null) throw new IllegalStateException("Native conversation identity unavailable");
        conversationInfo = info;
        if (selected) policy.selected(now);
        save();
    }

    private static void save() {
        if (preferences == null || policy == null) return;
        SharedPreferences.Editor edit = preferences.edit().putLong("lastActivity", policy.lastActivity())
            .putBoolean("selected", policy.hasConversation());
        if (conversationInfo == null) edit.remove("conversation");
        else {
            try {
                JSONObject json = new JSONObject();
                for (String field : new String[]{"TopicId", "AgentId", "ChatId", "Type"}) {
                    Object value = Reflector.callMethod(conversationInfo, "get" + field);
                    if (value != null) json.put(field, value);
                }
                edit.putString("conversation", json.toString());
            } catch (Exception failure) { report(failure); return; }
        }
        edit.apply();
    }

    private static Object readInfo(ClassLoader loader, String encoded) {
        if (encoded.isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(encoded);
            String topic = json.optString("TopicId", "");
            if (topic.isEmpty()) return null;
            return Reflector.newInstance(Reflector.findClass("com.xiaomi.voiceassistant.data.model.TopicGroupInfo", loader),
                topic, json.has("AgentId") ? json.getString("AgentId") : null, null,
                json.has("ChatId") ? json.getString("ChatId") : null, null,
                json.has("Type") ? Integer.valueOf(json.getInt("Type")) : null);
        } catch (Exception failure) { report(failure); return null; }
    }

    static void dispose() throws Exception {
        MainThread.call(() -> {
            disposed = true;
            generation++;
            inputSequence = 0;
            if (main != null) main.removeCallbacksAndMessages(null);
            main = null;
            pending = null;
            launcherPending = false;
            automaticNavigationInfo = null;
            automaticNavigationUntil = 0;
            conversationInfo = null;
            preferences = null;
            application = null;
            hostLoader = null;
            policy = null;
            lastInputUptime = 0;
            internalNavigation = 0;
            return null;
        });
    }

    private static void report(Throwable failure) {
        android.util.Log.w("MiAIPlus", "Conversation continuity unavailable", failure);
    }
}
