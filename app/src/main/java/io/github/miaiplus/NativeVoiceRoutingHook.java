package io.github.miaiplus;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Final main-engine ASR handoff to the same native tool-capable entry as typed chat. */
final class NativeVoiceRoutingHook {
    private static final Map<Object, LinkedHashSet<String>> routed = new WeakHashMap<>();
    private NativeVoiceRoutingHook() { }

    static void install(Context context, ClassLoader loader, List<HookCallback.Unhook> hooks) throws Exception {
        Class<?> callback = Reflector.findClass(HostCompatibility.choose("p31.p1$a", "h41.p1$a"), loader);
        Class<?> result = Reflector.findClass(HostCompatibility.choose("t51.b", "l61.b"), loader);
        hooks.add(Hooks.hookMethod(callback.getDeclaredMethod("onResults", result), new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args[0] == null) return;
                String query = (String)Reflector.callMethod(p.args[0], "getQuery");
                String requestId = (String)Reflector.callMethod(p.args[0], "getRequestId");
                // MiClaw's own Final callback has no request id. It already starts its
                // agent coroutine and must never be submitted a second time here.
                String requestedTool = NativeChatRouting.requestedPhoneTool(query);
                if (requestedTool.isEmpty() || requestId == null || requestId.isBlank()) return;
                Object manager = Reflector.getObjectField(p.thisObject, "c");
                if (wasClaimed(manager, requestId)) { p.setResult(null); return; }
                if (!ownsMainVoiceRequest(manager, requestId)) return;
                try {
                    NativeModelHook.requestConfig(context);
                    if (!NativePluginTools.availableForRouting(context, requestedTool)) return;
                } catch (Exception unavailable) {
                    stopOwnedRequest(context, manager, requestId);
                    p.setResult(null);
                    return;
                }
                // Only this current main request can consume the handoff. Repeated
                // final callbacks cannot cancel or resubmit the new MiClaw turn.
                p.setResult(null);
                if (!claim(manager, requestId)) return;
                Hooks.enter();
                Runnable handoff = () -> {
                    try {
                        // The user may have cancelled or begun another recording
                        // while this final result waited for the UI thread.
                        if (!ownsMainVoiceRequest(manager, requestId)) return;
                        NativeModelHook.requestConfig(context);
                        if (!NativePluginTools.availableForRouting(context, requestedTool)) {
                            stopOwnedRequest(context, manager, requestId);
                            return;
                        }
                        Object sender = NativeChatRoutingHook.voiceSender(context, loader);
                        if (sender == null) throw new IllegalStateException("Native chat sender unavailable");
                        // Native support gating, stopEngine, history and floating UI
                        // handling remain in Xiaomi's own submission implementation.
                        Reflector.callMethod(sender, "sendToMiClaw", query, query, null, List.of());
                        PhoneUseLog.event("native_voice_phone_handoff", "tool", requestedTool);
                    } catch (Exception failure) {
                        PhoneUseLog.failure("native_voice_phone_handoff", failure);
                        stopOwnedRequest(context, manager, requestId);
                    } finally { Hooks.leave(); }
                };
                if (Looper.myLooper() == Looper.getMainLooper()) handoff.run();
                else if (!new Handler(Looper.getMainLooper()).post(handoff)) {
                    Hooks.leave();
                    stopOwnedRequest(context, manager, requestId);
                }
            }
        }));
    }

    static boolean ownsMainVoiceRequest(Object manager, String requestId) {
        return ownsMainVoiceRequest(manager, requestId, HostCompatibility.choose("w", "y"),
            HostCompatibility.choose("x", "z"), HostCompatibility.choose("W", "Y"));
    }

    static boolean ownsMainVoiceRequest(Object manager, String requestId, String eventField, String duplexEventField, String modeField) {
        if (manager == null || requestId == null || requestId.isBlank()) return false;
        try {
            Object tasks = Reflector.getObjectField(manager, "a");
            if (!(tasks instanceof Map<?, ?> map)) return false;
            Object main = map.get("main");
            if (main == null) return false;
            Object request = Reflector.getObjectField(main, "g");
            if (request == null || !"VOICE".equals(String.valueOf(Reflector.getObjectField(request, modeField)))) return false;
            Object event = Reflector.getObjectField(request, eventField);
            Object duplexEvent = Reflector.getObjectField(request, duplexEventField);
            if (!eventMatches(event, requestId) && !eventMatches(duplexEvent, requestId)) return false;
            Object engine = Reflector.callMethod(main, "getEngine");
            Object account = Reflector.getObjectField(engine, "a");
            Object speech = Reflector.callMethod(account, "getSpeechEngine");
            if (speech == null || !Boolean.TRUE.equals(Reflector.callMethod(speech, "isMainEngine"))
                || !Boolean.TRUE.equals(Reflector.callMethod(speech, "isRequesting"))) return false;
            Object requests = Reflector.callMethod(account, "getRequestInfo");
            if (!(requests instanceof Map<?, ?> owned)) return false;
            Object state = owned.get(requestId);
            // Both pinned hosts leave this legacy map empty during real ASR.
            // Current task/event identity plus the actual main engine's request
            // lifecycle is authoritative; a known finished entry still rejects.
            return !"FINISH".equals(String.valueOf(state));
        } catch (RuntimeException unavailable) { return false; }
    }

    private static boolean eventMatches(Object event, String requestId) {
        return event != null && requestId.equals(Reflector.callMethod(event, "getId"));
    }

    static synchronized boolean wasClaimed(Object manager, String requestId) {
        LinkedHashSet<String> ids = routed.get(manager);
        return ids != null && ids.contains(requestId);
    }

    static synchronized boolean claim(Object manager, String requestId) {
        LinkedHashSet<String> ids = routed.computeIfAbsent(manager, ignored -> new LinkedHashSet<>());
        if (!ids.add(requestId)) return false;
        if (ids.size() > 64) ids.remove(ids.iterator().next());
        return true;
    }

    private static void stopOwnedRequest(Context context, Object manager, String requestId) {
        try {
            Object main = ((Map<?, ?>)Reflector.getObjectField(manager, "a")).get("main");
            if (main == null) return;
            Handler speech = (Handler)Reflector.getObjectField(main, "e");
            Hooks.enter();
            PendingStop pending = new PendingStop(Hooks::leave);
            Handler watchdog = new Handler(Looper.getMainLooper());
            Runnable[] timeout = new Runnable[1];
            Runnable cancel = () -> {
                if (!pending.start()) return;
                try {
                    if (cancelOwnedTask(manager, main, requestId, HostCompatibility.choose("w", "y"),
                        HostCompatibility.choose("x", "z"), HostCompatibility.choose("W", "Y")))
                        watchdog.post(() -> Toast.makeText(context,
                            "控屏任务未启动，请检查 MiAIPlus 后重试", Toast.LENGTH_LONG).show());
                } catch (RuntimeException failure) { PhoneUseLog.failure("native_voice_phone_cancel", failure); }
                finally { watchdog.removeCallbacks(timeout[0]); pending.finish(); }
            };
            // Xiaomi clears this handler queue when a newer recording starts. A
            // removed module callback must still release its reload reservation.
            timeout[0] = () -> { if (pending.expire()) speech.removeCallbacks(cancel); };
            boolean posted = false;
            // RequestInfo.g is written on this task handler. Enqueue behind any
            // pending new recording, then recheck before touching its engine.
            try {
                if (watchdog.postDelayed(timeout[0], 5000)) posted = speech.post(cancel);
            } finally {
                if (!posted) { watchdog.removeCallbacks(timeout[0]); pending.expire(); }
            }
        } catch (RuntimeException failure) { PhoneUseLog.failure("native_voice_phone_cancel", failure); }
    }

    static boolean cancelOwnedTask(Object manager, Object main, String requestId,
        String eventField, String duplexEventField, String modeField) {
        Object registered = ((Map<?, ?>)Reflector.getObjectField(manager, "a")).get("main");
        if (registered != main || !ownsMainVoiceRequest(manager, requestId, eventField, duplexEventField, modeField)) return false;
        Reflector.callMethod(main, "interceptAndCancelDialogOperation");
        // Exact pinned n1.G is forceStop's handler body. Unlike forceStop(), it
        // does not remove pending handler callbacks belonging to later requests.
        Reflector.callMethod(main, "G", true, 0);
        return true;
    }

    static final class PendingStop {
        private final AtomicInteger state = new AtomicInteger(); // pending, running, released
        private final Runnable release;
        PendingStop(Runnable release) { this.release = release; }
        boolean start() { return state.compareAndSet(0, 1); }
        void finish() { if (state.compareAndSet(1, 2)) release.run(); }
        boolean expire() {
            if (!state.compareAndSet(0, 2)) return false;
            release.run();
            return true;
        }
    }

    static synchronized void dispose() { routed.clear(); }
}
