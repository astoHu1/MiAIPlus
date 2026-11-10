package io.github.miaiplus;

import android.content.Context;
import android.content.Intent;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Real host request construction and streaming gate, with execution stopped before audio/network. */
final class SpeechPolicyProbe {
    private SpeechPolicyProbe() { }

    static String run(Context context, ClassLoader loader) throws Exception {
        var settings = context.getContentResolver().call(BridgeProvider.URI, "conversation_settings", null, null);
        check(settings != null, "speech configuration unavailable");
        String selected = settings.getString(SpeechReplyPolicy.KEY);
        check(selected != null, "speech policy missing from provider");
        Object speech = Reflector.callStaticMethod(Reflector.findClass("p31.g", loader), "getInstance");
        Object coordinator = Reflector.getObjectField(speech, "j");
        Object engines = Reflector.getObjectField(coordinator, "a");
        check(engines instanceof Map<?, ?>, "native engine registry unavailable");
        Object engine = ((Map<?, ?>)engines).get("miclaw_v2");
        check(engine != null, "real MiClaw engine is not registered");
        Class<?> request = Reflector.findClass(HostCompatibility.choose("w31.c", "o41.c"), loader);
        Class<?> state = Reflector.findClass("com.xiaomi.voiceassist.engine.context.j0", loader);
        Class<?> markerClass = Reflector.findClass(HostCompatibility.choose("e51.a0", "w51.e0"), loader);
        Object marker = Reflector.getStaticObjectField(markerClass, "a");
        Class<?> stream = Reflector.findClass(HostCompatibility.choose("xf1.t1", "ah1.u1"), loader);
        List<HookCallback.Unhook> guards = new ArrayList<>();
        List<String> dialogs = new ArrayList<>();
        ThreadLocal<Boolean> owned = new ThreadLocal<>();
        ThreadLocal<List<Object>> contextFixture = new ThreadLocal<>();
        Object[] captured = new Object[1];
        String[] marking = new String[1];
        String[] lastStart = new String[1];
        int[] starts = new int[1];
        try {
            // Reproduce the sticky host preference without changing the user's stored value.
            guards.add(Hooks.hookMethod(state.getDeclaredMethod("getTtsMode"), new HookCallback(10_000) {
                @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(1); }
            }));
            guards.add(Hooks.hookMethod(state.getDeclaredMethod("getItemsContext", request, boolean.class, boolean.class),
                new HookCallback(-1000) {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (contextFixture.get() != null) p.setResult(contextFixture.get());
                    }
                }));
            for (Class<?> task : new Class<?>[]{engine.getClass(),
                Reflector.findClass("com.xiaomi.voiceassist.engine.context.n1", loader)}) {
                for (String method : new String[]{"startSpeech", "startSpeechWithoutVoice", "startAsr"}) {
                    java.lang.reflect.Method boundary;
                    try { boundary = task.getDeclaredMethod(method, request); }
                    catch (NoSuchMethodException absent) { continue; }
                    guards.add(Hooks.hookMethod(boundary, new HookCallback(-1000) {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!Boolean.TRUE.equals(owned.get())) return;
                            starts[0]++;
                            captured[0] = p.args[0];
                            lastStart[0] = p.method.getName();
                            if (marking[0] != null && p.thisObject == engine)
                                Reflector.callMethod(marker, "markMiClawResult", marking[0]);
                            // Lower priority than production request hooks: they run, while the engine body
                            // never starts recording, alters the conversation, or calls a model.
                            p.setResult(null);
                        }
                    }));
                }
            }
            String firstVoice = null;
            for (String entry : new String[]{"asr_only", "voice", "screen", "text", "voice_handoff", "voice"}) {
                captured[0] = null;
                int previous = starts[0];
                MainThread.call(() -> {
                    owned.set(true);
                    try {
                        if (entry.equals("voice") || entry.equals("asr_only")) {
                            Intent intent = new Intent().putExtra("need_tts", !entry.equals("asr_only")).putExtra("need_nlp", false)
                                .putExtra("is_start_sound", false).putExtra("request_id", "miaiplus-speech-" + UUID.randomUUID());
                            if (entry.equals("asr_only")) {
                                Object control = Reflector.callStaticMethod(Reflector.findClass(
                                    "com.xiaomi.voiceassist.engine.context.g0", loader), "buildDisableTtsContext");
                                String json = (String)Reflector.callStaticMethod(Reflector.findClass(
                                    "com.xiaomi.ai.api.common.APIUtils", loader), "toJsonString", control);
                                intent.putStringArrayListExtra("assist_context", new ArrayList<>(List.of(json)));
                            }
                            // Actual coordinator path sets the VOICE request source after construction.
                            Reflector.callMethod(coordinator, "O", "main", intent);
                        } else {
                            Runnable submit = () -> {
                                Intent intent = textIntent(loader, entry.equals("screen")
                                    ? "com.miui.voiceassist.query&&screen_recognition" : "QueryEditBar");
                                boolean expectedIntent = SpeechReplyPolicy.HOST_DEFAULT.equals(selected)
                                    || (entry.equals("voice_handoff") && !SpeechReplyPolicy.TEXT_ONLY.equals(selected));
                                check(intent.getBooleanExtra("need_tts", !expectedIntent) == expectedIntent,
                                    entry + " real query intent inherited the sticky host TTS preference");
                                Reflector.callMethod(coordinator, "T", "main", intent, List.of(), false);
                            };
                            if (entry.equals("voice_handoff")) NativeSpeechPolicyHook.voiceHandoff(submit);
                            else submit.run();
                        }
                    } finally { owned.remove(); }
                    return null;
                });
                check(starts[0] == previous + 1 && captured[0] != null,
                    "real " + entry + " request did not reach exactly one guarded engine start");
                if (entry.equals("asr_only")) {
                    check("startAsr".equals(lastStart[0]), "recognition-only request changed engine branch to " + lastStart[0]);
                    check(Boolean.FALSE.equals(Reflector.getObjectField(captured[0], HostCompatibility.choose("h", "j")))
                        && Boolean.FALSE.equals(Reflector.getObjectField(captured[0], HostCompatibility.choose("i", "k"))),
                        "recognition-only request gained TTS or NLP");
                    var expectedDisabled = new java.util.HashSet<Object>(nativeDisabled(loader));
                    if (SpeechReplyPolicy.TEXT_ONLY.equals(selected))
                        for (String name : List.of("TTS", "TTS_STREAM", "LLM_TTS_AUDIO")) expectedDisabled.add(
                            Reflector.getStaticObjectField(Reflector.findClass("com.xiaomi.ai.api.Execution$RequestControlType", loader), name));
                    check(expectedDisabled.equals(new java.util.HashSet<>(requestDisabled(captured[0]))),
                        "recognition-only request context changed: expected=" + expectedDisabled + ",actual=" + requestDisabled(captured[0]));
                    verifyContextMetadata(loader, state, captured[0], selected, false, contextFixture,
                        !SpeechReplyPolicy.TEXT_ONLY.equals(selected));
                    continue;
                }
                boolean expected = SpeechReplyPolicy.HOST_DEFAULT.equals(selected)
                    || (!SpeechReplyPolicy.TEXT_ONLY.equals(selected) && entry.startsWith("voice"));
                check(Boolean.valueOf(expected).equals(Reflector.getObjectField(captured[0],
                    HostCompatibility.choose("h", "j"))), entry + " request needTts did not match policy");
                check(hasDisabledTts(captured[0]) == !expected,
                    entry + " request execution contexts did not match the TTS decision");
                verifyContextMetadata(loader, state, captured[0], selected, expected, contextFixture, false);
                String dialog = "miaiplus-speech-" + UUID.randomUUID();
                dialogs.add(dialog);
                Object info = captured[0];
                MainThread.call(() -> {
                    owned.set(true); marking[0] = dialog;
                    try { Reflector.callMethod(engine, "startSpeechWithoutVoice", info); }
                    finally { marking[0] = null; owned.remove(); }
                    return null;
                });
                check(Boolean.valueOf(expected).equals(Reflector.callStaticMethod(stream, "isEnabled", dialog)),
                    entry + " actual MiClaw streaming TTS gate did not match policy");
                if (entry.equals("voice") && firstVoice == null) firstVoice = dialog;
                if (firstVoice != null) {
                    boolean voiceExpected = !SpeechReplyPolicy.TEXT_ONLY.equals(selected);
                    check(Boolean.valueOf(voiceExpected).equals(Reflector.callStaticMethod(stream, "isEnabled", firstVoice)),
                        "new input changed an earlier voice dialog's reply policy");
                }
            }
            check(Boolean.FALSE.equals(Reflector.callStaticMethod(stream, "isEnabled", "miaiplus-unregistered-dialog")),
                "unknown dialog unexpectedly gained streaming TTS");
            return "PROBE PASS: policy=" + selected
                + "; real native voice/text coordinator and screen-query builder; ASR-only preserved then voice→screen→text→voice handoff→voice"
                + "; sticky host ttsMode=1; request needTts and real streaming gate verified per dialog"
                + "; nativeDisabled=" + nativeDisabled(loader)
                + "; engine bodies blocked before microphone/model/audio; synthetic dialog ids; acoustic recognition and model replies untested";
        } finally {
            owned.remove(); contextFixture.remove();
            for (String dialog : dialogs) Reflector.callMethod(marker, "clear", dialog);
            for (var guard : guards) guard.unhook();
        }
    }

    private static Intent textIntent(ClassLoader loader, String origin) {
        String queryClass = HostCompatibility.choose("x51.m0", "p61.m0");
        Class<?> type = Reflector.findClass(queryClass, loader);
        Object builder = Reflector.newInstance(Reflector.findClass(queryClass + "$e", loader));
        Reflector.callMethod(builder, "setQuery", "MiAIPlus speech policy fixture");
        Reflector.callMethod(builder, "setQueryOrigin", origin);
        Object descriptor = Reflector.callMethod(builder, "build");
        return (Intent)Reflector.callMethod(Reflector.getStaticObjectField(type, "a"), "e", descriptor);
    }

    private static boolean hasDisabledTts(Object request) {
        for (Object type : requestDisabled(request))
            if (List.of("TTS", "TTS_STREAM", "LLM_TTS_AUDIO").contains(String.valueOf(type))) return true;
        return false;
    }

    private static List<Object> requestDisabled(Object request) {
        List<Object> result = new ArrayList<>();
        Object contexts = Reflector.getObjectField(request, HostCompatibility.choose("t", "v"));
        if (!(contexts instanceof List<?> values)) return result;
        for (Object context : values) {
            if (!"Execution.RequestControl".equals(Reflector.callMethod(context, "getFullName"))) continue;
            Object payload = Reflector.callMethod(context, "getPayload");
            Object disabled = Reflector.callMethod(payload, "getDisabled");
            if (disabled instanceof List<?> types) result.addAll(types);
        }
        return result;
    }

    private static void verifyContextMetadata(ClassLoader loader, Class<?> state, Object request,
        String policy, boolean tts, ThreadLocal<List<Object>> contextFixture, boolean preserveRecognition) {
        Class<?> controlType = Reflector.findClass("com.xiaomi.ai.api.Execution$RequestControlType", loader);
        Object speechType = null, otherType = null;
        for (Object value : controlType.getEnumConstants()) {
            if (String.valueOf(value).equals("TTS")) speechType = value;
            else if (String.valueOf(value).equals("NLP")) otherType = value;
        }
        check(speechType != null && otherType != null, "real RequestControl enum lacks fixture types");
        Object domain = Reflector.findClass("com.xiaomi.ai.api.Sys$Environment", loader).getEnumConstants()[0];
        Object mdcd = Reflector.findClass("com.xiaomi.ai.api.Execution$MDCDEnvironment", loader).getEnumConstants()[0];
        List<Object> originals = new ArrayList<>();
        for (List<Object> disabled : List.of(List.of(speechType), List.of(speechType, otherType))) {
            Object control = Reflector.newInstance(Reflector.findClass("com.xiaomi.ai.api.Execution$RequestControl", loader));
            Reflector.callMethod(control, "setDisabled", disabled);
            Reflector.callMethod(control, "setDomainEnv", domain);
            Reflector.callMethod(control, "setMdcdEnv", mdcd);
            originals.add(Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.ai.api.common.APIUtils", loader),
                "buildContext", control));
        }
        Object normalized;
        contextFixture.set(originals);
        try { normalized = Reflector.callStaticMethod(state, "getItemsContext", request, true, true); }
        finally { contextFixture.remove(); }
        check(normalized instanceof List<?>, "real getItemsContext hook returned no list");
        var values = (List<?>)normalized;
        boolean stock = SpeechReplyPolicy.HOST_DEFAULT.equals(policy) || preserveRecognition;
        check(values.size() == originals.size(), "speech context normalization changed unrelated context count");
        List<?> nativeSpeechDisabled = nativeDisabled(loader);
        for (int index = 0; index < originals.size(); index++) {
            Object before = originals.get(index), after = values.get(index);
            Object beforePayload = Reflector.callMethod(before, "getPayload"), afterPayload = Reflector.callMethod(after, "getPayload");
            check(Reflector.getObjectField(before, "header") == Reflector.getObjectField(after, "header"),
                "speech normalization replaced the original context header");
            for (String field : List.of("domain_env", "mdcd_env"))
                check(Reflector.getObjectField(beforePayload, field) == Reflector.getObjectField(afterPayload, field),
                    "speech normalization lost " + field);
            var disabled = (List<?>)Reflector.callMethod(afterPayload, "getDisabled");
            check(disabled.contains(otherType) == (index == 1), "speech normalization changed a non-speech restriction");
            java.util.LinkedHashSet<Object> expected = new java.util.LinkedHashSet<>(
                (List<?>)Reflector.callMethod(beforePayload, "getDisabled"));
            if (!stock) {
                if (tts) expected.removeIf(value -> List.of("TTS", "TTS_STREAM", "LLM_TTS_AUDIO").contains(String.valueOf(value)));
                else {
                    expected.addAll(nativeSpeechDisabled);
                    for (Object value : controlType.getEnumConstants())
                        if (List.of("TTS", "TTS_STREAM", "LLM_TTS_AUDIO").contains(String.valueOf(value))) expected.add(value);
                }
            }
            check(expected.equals(new java.util.LinkedHashSet<>(disabled)),
                "speech disabled mismatch policy=" + policy + ",tts=" + tts + ",context=" + index
                    + ",before=" + Reflector.callMethod(beforePayload, "getDisabled")
                    + ",native=" + nativeSpeechDisabled + ",expected=" + expected + ",actual=" + disabled);
            check(new java.util.HashSet<>(disabled).size() == disabled.size(),
                "speech normalization duplicated a disabled capability");
        }
    }

    private static List<?> nativeDisabled(ClassLoader loader) {
        Object context = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassist.engine.context.g0", loader),
            "buildDisableTtsContext");
        return (List<?>)Reflector.callMethod(Reflector.callMethod(context, "getPayload"), "getDisabled");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
