package io.github.miaiplus;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Request-local speech policy, including MiClaw's separate streaming synthesizer. */
final class NativeSpeechPolicyHook {
    private static final Map<Object, SpeechReplyPolicy.Input> requests = new WeakHashMap<>();
    private static final Dialogs dialogs = new Dialogs();
    private static final ThreadLocal<SpeechReplyPolicy.Input> entry = new ThreadLocal<>();
    private static final ThreadLocal<SpeechReplyPolicy.Input> miclaw = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> voiceHandoff = new ThreadLocal<>();

    private NativeSpeechPolicyHook() { }

    static void voiceHandoff(Runnable submit) {
        Boolean prior = voiceHandoff.get();
        voiceHandoff.set(true);
        try { submit.run(); }
        finally { if (prior == null) voiceHandoff.remove(); else voiceHandoff.set(prior); }
    }

    static void install(Context context, ClassLoader loader, List<HookCallback.Unhook> hooks) throws Exception {
        Class<?> request = Reflector.findClass(HostCompatibility.choose("w31.c", "o41.c"), loader);
        Class<?> manager = Reflector.findClass(HostCompatibility.choose("p31.p1", "h41.p1"), loader);
        // These exact entry methods create RequestInfo synchronously. A long-press PTT remains VOICE;
        // screen recognition is classified by its actual text request, never by the gesture name.
        for (String method : new String[]{"L", "O"}) {
            hooks.add(Hooks.hookMethod(manager.getDeclaredMethod(method, String.class, Intent.class),
                scope(SpeechReplyPolicy.Input.VOICE)));
        }
        hooks.add(Hooks.hookMethod(manager.getDeclaredMethod("T", String.class, Intent.class, List.class, boolean.class),
            scope(SpeechReplyPolicy.Input.TEXT)));
        hooks.add(Hooks.hookMethod(request.getDeclaredMethod("getRequestInfo", Intent.class,
            Reflector.findClass(HostCompatibility.choose("w31.c$a", "o41.c$a"), loader)), new HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || p.getResult() == null) return;
                    try {
                        Object info = p.getResult();
                        Intent intent = (Intent)p.args[0];
                        SpeechReplyPolicy.Input input = classify(entry.get(), intent == null ? null : intent.getStringExtra("queryOrigin"),
                            intent == null ? null : intent.getStringExtra("meta_vars"));
                        synchronized (requests) { requests.put(info, input); }
                        applyRequest(context, loader, info, input);
                    } catch (RuntimeException failure) { failed("request", failure); }
                }
            }));
        Class<?> query = Reflector.findClass(HostCompatibility.choose("x51.m0", "p61.m0"), loader);
        hooks.add(Hooks.hookMethod(query.getDeclaredMethod("e", Reflector.findClass(
            HostCompatibility.choose("x51.m0$d", "p61.m0$d"), loader)), new HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || !(p.getResult() instanceof Intent intent)) return;
                    try {
                        SpeechReplyPolicy.Input input = classify(Boolean.TRUE.equals(voiceHandoff.get())
                            ? SpeechReplyPolicy.Input.VOICE : SpeechReplyPolicy.Input.TEXT,
                            intent.getStringExtra("queryOrigin"), intent.getStringExtra("meta_vars"));
                        Boolean override = SpeechReplyPolicy.override(policy(context), input);
                        if (override != null) intent.putExtra("need_tts", override);
                    } catch (RuntimeException failure) { failed("query_intent", failure); }
                }
            }));
        Class<?> state = Reflector.findClass("com.xiaomi.voiceassist.engine.context.j0", loader);
        hooks.add(Hooks.hookMethod(state.getDeclaredMethod("getItemsContext", request, boolean.class, boolean.class), new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || p.args[0] == null || !(p.getResult() instanceof List<?> contexts)) return;
                try {
                    Boolean override = requestOverride(context, p.args[0], input(p.args[0], SpeechReplyPolicy.Input.UNKNOWN));
                    if (override != null) p.setResult(contexts(loader, contexts, override));
                } catch (RuntimeException failure) { failed("contexts", failure); }
            }
        }));
        Class<?> engine = Reflector.findClass(HostCompatibility.choose("e51.v", "w51.z"), loader);
        for (String method : new String[]{"startSpeech", "startSpeechWithoutVoice"}) {
            SpeechReplyPolicy.Input fallback = method.equals("startSpeech") ? SpeechReplyPolicy.Input.VOICE : SpeechReplyPolicy.Input.TEXT;
            hooks.add(Hooks.hookMethod(engine.getDeclaredMethod(method, request), new HookCallback() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    SpeechReplyPolicy.Input previous = miclaw.get();
                    p.setObjectExtra("speechPolicyPrevious", previous);
                    SpeechReplyPolicy.Input current = input(p.args[0], fallback);
                    miclaw.set(current);
                    try { applyRequest(context, loader, p.args[0], current); }
                    catch (RuntimeException failure) { failed("miclaw_request", failure); }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object previous = p.getObjectExtra("speechPolicyPrevious");
                    if (previous instanceof SpeechReplyPolicy.Input value) miclaw.set(value); else miclaw.remove();
                }
            }));
        }
        Class<?> marker = Reflector.findClass(HostCompatibility.choose("e51.a0", "w51.e0"), loader);
        hooks.add(Hooks.hookMethod(marker.getDeclaredMethod("markMiClawResult", String.class), new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (miclaw.get() != null) dialogs.record((String)p.args[0], miclaw.get());
            }
        }));
        Class<?> streamPolicy = Reflector.findClass(HostCompatibility.choose("xf1.t1", "ah1.u1"), loader);
        hooks.add(Hooks.hookMethod(streamPolicy.getDeclaredMethod("isEnabled", String.class), new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable()) return;
                try {
                    String selected = policy(context);
                    SpeechReplyPolicy.Input input = dialogs.input((String)p.args[0]);
                    Boolean override = SpeechReplyPolicy.override(selected, input);
                    // Only ids created by the real MiClaw engine can be enabled. Unknown ids retain stock gating.
                    if (override != null && input != SpeechReplyPolicy.Input.UNKNOWN) {
                        Object companion = Reflector.getStaticObjectField(marker, "a");
                        if (Boolean.TRUE.equals(Reflector.callMethod(companion, "isMiClawResult", p.args[0]))) p.setResult(override);
                    }
                } catch (RuntimeException failure) { failed("stream", failure); }
            }
        }));
        hooks.add(Hooks.hookMethod(marker.getDeclaredMethod("clear", String.class), new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam p) { if (!p.hasThrowable()) dialogs.remove((String)p.args[0]); }
        }));
        // Action narration has a linking branch that ignores the normal global needTts field.
        hooks.add(Hooks.hookMethod(Reflector.findClass(HostCompatibility.choose("fo1.x", "rp1.x"), loader)
            .getDeclaredMethod("speakTts", String.class, Reflector.findClass(HostCompatibility.choose("g21.a", "x21.a"), loader)),
            new HookCallback() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Object operations = Reflector.callStaticMethod(Reflector.findClass(
                            "com.xiaomi.voiceassistant.instruction.base.OperationManager", loader), "getInstance");
                        Object info = Reflector.callMethod(operations, "getQueryInfo");
                        String id = (String)Reflector.callMethod(info, "getDialogId");
                        Boolean override = SpeechReplyPolicy.override(policy(context), dialogs.input(id));
                        if (!Boolean.FALSE.equals(override)) return;
                        p.setResult(null);
                        if (p.args[1] != null) Reflector.callMethod(p.args[1], "onPlayFinish", true);
                    } catch (RuntimeException failure) { failed("action_tts", failure); }
                }
            }));
    }

    private static HookCallback scope(SpeechReplyPolicy.Input fallback) {
        return new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.setObjectExtra("speechEntryPrevious", entry.get());
                entry.set(Boolean.TRUE.equals(voiceHandoff.get()) ? SpeechReplyPolicy.Input.VOICE : fallback);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object previous = p.getObjectExtra("speechEntryPrevious");
                if (previous instanceof SpeechReplyPolicy.Input value) entry.set(value); else entry.remove();
            }
        };
    }

    static SpeechReplyPolicy.Input classify(SpeechReplyPolicy.Input source, String origin, String metadata) {
        if (source == SpeechReplyPolicy.Input.VOICE) return source;
        if (origin != null && (origin.contains("com.miui.voiceassist.query&&screen_recognition")
            || origin.equals("screen_recog_nlp_with_ref"))) return SpeechReplyPolicy.Input.SCREEN;
        if (metadata != null) {
            try { if (new org.json.JSONObject(metadata).optBoolean("isVoiceInput")) return SpeechReplyPolicy.Input.VOICE; }
            catch (org.json.JSONException ignored) { }
        }
        return source == null ? SpeechReplyPolicy.Input.UNKNOWN : source;
    }

    private static SpeechReplyPolicy.Input input(Object request, SpeechReplyPolicy.Input fallback) {
        synchronized (requests) { return requests.getOrDefault(request, fallback); }
    }

    private static String policy(Context context) {
        Bundle settings = context.getContentResolver().call(BridgeProvider.URI, "conversation_settings", null, null);
        return settings == null ? SpeechReplyPolicy.HOST_DEFAULT
            : SpeechReplyPolicy.normalize(settings.getString(SpeechReplyPolicy.KEY, SpeechReplyPolicy.HOST_DEFAULT));
    }

    private static Boolean requestOverride(Context context, Object info, SpeechReplyPolicy.Input input) {
        Boolean override = SpeechReplyPolicy.override(policy(context), input);
        if (override == null || info == null) return override;
        // A recognition-only request has no reply. Turning needTts on changes the coordinator
        // branch from startAsr to startSpeech; removing its contexts also changes transcription.
        // The later MiClaw handoff creates its own reply-capable request and enables voice there.
        if (Boolean.TRUE.equals(override) && input == SpeechReplyPolicy.Input.VOICE
            && !Boolean.TRUE.equals(Reflector.getObjectField(info, HostCompatibility.choose("i", "k")))
            && !Boolean.TRUE.equals(Reflector.getObjectField(info, HostCompatibility.choose("h", "j")))) return null;
        return override;
    }

    private static void applyRequest(Context context, ClassLoader loader, Object info, SpeechReplyPolicy.Input input) {
        Boolean override = requestOverride(context, info, input);
        if (override == null || info == null) return;
        Reflector.setObjectField(info, HostCompatibility.choose("h", "j"), override);
        Object existing = Reflector.getObjectField(info, HostCompatibility.choose("t", "v"));
        if (existing instanceof List<?> list) Reflector.setObjectField(info, HostCompatibility.choose("t", "v"), contexts(loader, list, override));
        PhoneUseLog.hostEvent("speech_reply_policy", "input", input.name().toLowerCase(java.util.Locale.ROOT), "tts", override);
    }

    /** Preserve unrelated request restrictions while overriding only speech output. */
    private static List<Object> contexts(ClassLoader loader, List<?> existing, boolean tts) {
        List<Object> result = new ArrayList<>();
        boolean hasControl = false;
        List<Object> speechDisabled = new ArrayList<>();
        Object speechContext = null;
        if (!tts) {
            speechContext = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassist.engine.context.g0", loader), "buildDisableTtsContext");
            Object speechPayload = Reflector.callMethod(speechContext, "getPayload");
            Object nativeDisabled = Reflector.callMethod(speechPayload, "getDisabled");
            if (nativeDisabled instanceof List<?> values) speechDisabled.addAll(values);
            Class<?> controlType = Reflector.findClass("com.xiaomi.ai.api.Execution$RequestControlType", loader);
            for (String name : List.of("TTS", "TTS_STREAM", "LLM_TTS_AUDIO")) {
                Object value = Reflector.getStaticObjectField(controlType, name);
                if (!speechDisabled.contains(value)) speechDisabled.add(value);
            }
            Reflector.callMethod(speechPayload, "setDisabled", speechDisabled);
        }
        for (Object context : existing) {
            if (!"Execution.RequestControl".equals(Reflector.callMethod(context, "getFullName"))) { result.add(context); continue; }
            Object payload = Reflector.callMethod(context, "getPayload");
            Object disabled = Reflector.callMethod(payload, "getDisabled");
            if (!(disabled instanceof List<?> list)) { result.add(context); continue; }
            hasControl = true;
            List<Object> keep = new ArrayList<>();
            for (Object value : list) if (!tts || !List.of("TTS", "TTS_STREAM", "LLM_TTS_AUDIO").contains(String.valueOf(value))) {
                if (!keep.contains(value)) keep.add(value);
            }
            for (Object value : speechDisabled) if (!keep.contains(value)) keep.add(value);
            // RequestControl also carries environment metadata; preserve it even when all speech
            // restrictions are removed. The wrapper's header remains the original context header.
            {
                Object copy = Reflector.newInstance(payload.getClass());
                Reflector.callMethod(copy, "setDisabled", keep);
                Reflector.setObjectField(copy, "domain_env", Reflector.getObjectField(payload, "domain_env"));
                Reflector.setObjectField(copy, "mdcd_env", Reflector.getObjectField(payload, "mdcd_env"));
                Object wrapper = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.ai.api.common.APIUtils", loader), "buildContext", copy);
                Reflector.setObjectField(wrapper, "header", Reflector.getObjectField(context, "header"));
                result.add(wrapper);
            }
        }
        if (!tts && !hasControl) result.add(speechContext);
        return result;
    }

    static void dispose() { synchronized (requests) { requests.clear(); } dialogs.clear(); entry.remove(); miclaw.remove(); voiceHandoff.remove(); }

    private static void failed(String stage, RuntimeException failure) { PhoneUseLog.hostFailure("speech_policy", failure, "stage", stage); }

    static final class Dialogs {
        private final LinkedHashMap<String, SpeechReplyPolicy.Input> values = new LinkedHashMap<>();
        synchronized void record(String id, SpeechReplyPolicy.Input input) {
            if (id == null || id.isBlank()) return;
            values.put(id, input);
            while (values.size() > 128) values.remove(values.keySet().iterator().next());
        }
        synchronized SpeechReplyPolicy.Input input(String id) { return values.getOrDefault(id, SpeechReplyPolicy.Input.UNKNOWN); }
        synchronized void remove(String id) { values.remove(id); }
        synchronized void clear() { values.clear(); }
    }
}
