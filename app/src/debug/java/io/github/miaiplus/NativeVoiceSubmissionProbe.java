package io.github.miaiplus;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Starts a real native main-engine ASR request, then supplies its final recognized text.
 * This tests recognition-result submission, not microphone accuracy or wake-word detection.
 * No request, event, engine-state or production ownership fields are fabricated.
 */
final class NativeVoiceSubmissionProbe {
    private NativeVoiceSubmissionProbe() { }

    static String submit(Context context, ClassLoader loader, String query) throws Exception {
        check(!context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked(), "unlock device first");
        check(NativeChatRoutingHook.voiceSender(context, loader) != null, "native voice handoff sender unavailable");
        Object speech = Reflector.callStaticMethod(Reflector.findClass("p31.g", loader), "getInstance");
        Object coordinator = Reflector.getObjectField(speech, "j");
        Object listener = Reflector.getObjectField(coordinator, "j");
        Class<?> resultClass = Reflector.findClass(HostCompatibility.choose("t51.b", "l61.b"), loader);
        Object recognized = Reflector.newInstance(resultClass);
        Reflector.setObjectField(recognized, "query", query);
        String wakeup = "miaiplus-final-text-" + UUID.randomUUID();
        AtomicInteger handoffs = new AtomicInteger();
        // A real microphone request is kept ASR-only. Any naturally arriving final
        // result for this owned request is suppressed until the fixed fixture runs.
        HookCallback.Unhook naturalResults = Hooks.hookMethod(listener.getClass().getDeclaredMethod("onResults", resultClass),
            new HookCallback(2000) {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.thisObject == listener && p.args[0] != recognized && ownsWakeup(coordinator, wakeup))
                        p.setResult(null);
                }
            });
        HookCallback.Unhook sender = null;
        boolean submitted = false;
        try {
            Class<?> senderClass = Reflector.findClass("ll1.f0", loader);
            sender = Hooks.hookMethod(senderClass.getDeclaredMethod("sendToMiClaw", String.class, String.class,
                String.class, java.util.List.class), new HookCallback() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (query.equals(p.args[0])) handoffs.incrementAndGet();
                    }
                });
            Intent intent = new Intent()
                .putExtra("request_id", wakeup)
                .putExtra("need_tts", false)
                .putExtra("need_nlp", false)
                .putExtra("is_start_sound", false);
            MainThread.call(() -> {
                Reflector.callMethod(speech, "startSpeechRecognizeByVoice", false, false, intent);
                return null;
            });
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            String requestId = null;
            while (SystemClock.elapsedRealtime() < deadline) {
                requestId = ownedRequestId(coordinator, wakeup);
                if (requestId != null && NativeVoiceRoutingHook.ownsMainVoiceRequest(coordinator, requestId)) break;
                Thread.sleep(20);
            }
            check(requestId != null && NativeVoiceRoutingHook.ownsMainVoiceRequest(coordinator, requestId),
                "real main ASR request never reached production ownership boundary; " + stage(coordinator, wakeup));
            Reflector.setObjectField(recognized, "requestId", requestId);
            final String ownedId = requestId;
            MainThread.call(() -> {
                check(ownsWakeup(coordinator, wakeup) && NativeVoiceRoutingHook.ownsMainVoiceRequest(coordinator, ownedId),
                    "real main ASR request changed before final-text submission");
                Reflector.callMethod(listener, "onResults", recognized);
                check(handoffs.get() == 1, "final recognition did not make exactly one native MiClaw handoff");
                // Deliver the exact same old final again after native submission.
                // Its claimed request must stay consumed even after stopEngine
                // clears the main request or MiClaw begins its replacement turn.
                Reflector.callMethod(listener, "onResults", recognized);
                return null;
            });
            check(handoffs.get() == 1, "duplicate final recognition repeated the native MiClaw handoff");
            submitted = true;
            return "actual main ASR request; actual p1$a.onResults; synthetic final recognized text; "
                + "production request ownership=true; native sendToMiClaw=1; duplicate final suppressed=true; "
                + "acoustic/wake-word recognition untested";
        } finally {
            try {
                if (!submitted && ownsWakeup(coordinator, wakeup)) {
                    MainThread.call(() -> {
                        if (ownsWakeup(coordinator, wakeup)) {
                            Object tasks = Reflector.getObjectField(coordinator, "a");
                            Object main = ((Map<?, ?>)tasks).get("main");
                            Reflector.callMethod(main, "forceStop", true, 0);
                        }
                        return null;
                    });
                }
            } finally {
                if (sender != null) sender.unhook();
                naturalResults.unhook();
            }
        }
    }

    private static Object ownedRequest(Object coordinator, String wakeup) {
        Object tasks = Reflector.getObjectField(coordinator, "a");
        if (!(tasks instanceof Map<?, ?> map)) return null;
        Object main = map.get("main");
        if (main == null) return null;
        Object request = Reflector.getObjectField(main, "g");
        if (request == null || !wakeup.equals(Reflector.getObjectField(request, HostCompatibility.choose("D", "F")))) return null;
        return request;
    }

    private static boolean ownsWakeup(Object coordinator, String wakeup) {
        return ownedRequest(coordinator, wakeup) != null;
    }

    private static String ownedRequestId(Object coordinator, String wakeup) {
        Object request = ownedRequest(coordinator, wakeup);
        if (request == null) return null;
        Object event = Reflector.getObjectField(request, HostCompatibility.choose("w", "y"));
        return event == null ? null : (String)Reflector.callMethod(event, "getId");
    }

    /** Diagnostics deliberately omit queries, wakeup nonces and actual request ids. */
    private static String stage(Object coordinator, String wakeup) {
        boolean mainPresent = false, wakeupOwned = false, voice = false, eventPresent = false;
        boolean requesting = false, keyPresent = false;
        boolean duplexEventPresent = false, tts = false, nlp = false, handlerAlive = false;
        String handlerState = "absent";
        int mapSize = -1;
        String state = "absent";
        try {
            Object tasks = Reflector.getObjectField(coordinator, "a");
            Object main = tasks instanceof Map<?, ?> map ? map.get("main") : null;
            mainPresent = main != null;
            if (main != null) {
                Object handler = Reflector.getObjectField(main, "e");
                if (handler instanceof android.os.Handler taskHandler) {
                    Thread thread = taskHandler.getLooper().getThread();
                    handlerAlive = thread.isAlive();
                    handlerState = thread.getState().name();
                }
                Object request = Reflector.getObjectField(main, "g");
                wakeupOwned = request != null && wakeup.equals(Reflector.getObjectField(request, HostCompatibility.choose("D", "F")));
                Object event = null;
                if (request != null) {
                    voice = "VOICE".equals(String.valueOf(Reflector.getObjectField(request, HostCompatibility.choose("W", "Y"))));
                    event = Reflector.getObjectField(request, HostCompatibility.choose("w", "y"));
                    duplexEventPresent = Reflector.getObjectField(request, HostCompatibility.choose("x", "z")) != null;
                    tts = Boolean.TRUE.equals(Reflector.getObjectField(request, HostCompatibility.choose("h", "j")));
                    nlp = Boolean.TRUE.equals(Reflector.getObjectField(request, HostCompatibility.choose("i", "k")));
                }
                eventPresent = event != null;
                Object engine = Reflector.callMethod(main, "getEngine");
                if (engine != null) {
                    Object account = Reflector.getObjectField(engine, "a");
                    if (account != null) {
                        Object speech = Reflector.callMethod(account, "getSpeechEngine");
                        if (speech != null) requesting = Boolean.TRUE.equals(Reflector.callMethod(speech, "isRequesting"));
                        Object requests = Reflector.callMethod(account, "getRequestInfo");
                        if (requests instanceof Map<?, ?> map) {
                            mapSize = map.size();
                            Object id = event == null ? null : Reflector.callMethod(event, "getId");
                            keyPresent = id != null && map.containsKey(id);
                            Object value = id == null ? null : map.get(id);
                            if (value instanceof Enum<?> enumeration) state = enumeration.name();
                            else if (value != null) state = "non-enum";
                        }
                    }
                }
            }
        } catch (RuntimeException unavailable) { state = "diagnostic-unavailable"; }
        return "mainPresent=" + mainPresent + ",wakeupOwned=" + wakeupOwned + ",VOICE=" + voice
            + ",eventPresent=" + eventPresent + ",requesting=" + requesting + ",mapSize=" + mapSize
            + ",keyPresent=" + keyPresent + ",state=" + state + ",duplexEventPresent=" + duplexEventPresent
            + ",tts=" + tts + ",nlp=" + nlp + ",handlerAlive=" + handlerAlive + ",handlerState=" + handlerState;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
