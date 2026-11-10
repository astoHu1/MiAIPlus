package io.github.miaiplus;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;

public final class SpeechReplyPolicyTest {
    @Test public void followsInputByDefaultAndKeepsExplicitDisabledAndTextOnlyPolicies() {
        assertEquals(SpeechReplyPolicy.FOLLOW_INPUT, new Config(Map.of()).speechReplyPolicy);
        for (Object value : new Object[]{"", "unknown", true, 1}) {
            assertEquals(SpeechReplyPolicy.FOLLOW_INPUT,
                new Config(Map.of(SpeechReplyPolicy.KEY, value)).speechReplyPolicy);
        }
        for (String value : new String[]{SpeechReplyPolicy.TEXT_ONLY, SpeechReplyPolicy.FOLLOW_INPUT, SpeechReplyPolicy.HOST_DEFAULT}) {
            assertEquals(value, new Config(Map.of(SpeechReplyPolicy.KEY, value)).speechReplyPolicy);
        }
    }

    @Test public void speechThenScreenThenTextThenSpeechCannotInheritPreviousDecision() {
        assertEquals(Boolean.TRUE, SpeechReplyPolicy.override(SpeechReplyPolicy.FOLLOW_INPUT, SpeechReplyPolicy.Input.VOICE));
        assertEquals(Boolean.FALSE, SpeechReplyPolicy.override(SpeechReplyPolicy.FOLLOW_INPUT, SpeechReplyPolicy.Input.SCREEN));
        assertEquals(Boolean.FALSE, SpeechReplyPolicy.override(SpeechReplyPolicy.FOLLOW_INPUT, SpeechReplyPolicy.Input.TEXT));
        assertEquals(Boolean.TRUE, SpeechReplyPolicy.override(SpeechReplyPolicy.FOLLOW_INPUT, SpeechReplyPolicy.Input.VOICE));
        assertNull(SpeechReplyPolicy.override(SpeechReplyPolicy.FOLLOW_INPUT, SpeechReplyPolicy.Input.UNKNOWN));
    }

    @Test public void actualScreenRequestIsQuietWhileScreenPageMicrophoneAndGesturePttRemainVoice() {
        assertEquals(SpeechReplyPolicy.Input.SCREEN, NativeSpeechPolicyHook.classify(SpeechReplyPolicy.Input.TEXT,
            "com.miui.voiceassist.query&&screen_recognition", null));
        assertEquals(SpeechReplyPolicy.Input.SCREEN, NativeSpeechPolicyHook.classify(SpeechReplyPolicy.Input.TEXT,
            "screen_recog_nlp_with_ref", null));
        assertEquals(SpeechReplyPolicy.Input.VOICE, NativeSpeechPolicyHook.classify(SpeechReplyPolicy.Input.VOICE,
            "screen_recognition_query_origin", null));
        assertEquals(SpeechReplyPolicy.Input.VOICE, NativeSpeechPolicyHook.classify(SpeechReplyPolicy.Input.VOICE,
            "long_press_fullscreen_gesture_line", null));
    }

    @Test public void dialogsKeepTheirOwnInputAcrossLaterRequestsAndRetireWithoutLeaking() {
        var dialogs = new NativeSpeechPolicyHook.Dialogs();
        dialogs.record("first-voice", SpeechReplyPolicy.Input.VOICE);
        dialogs.record("screen", SpeechReplyPolicy.Input.SCREEN);
        dialogs.record("typed", SpeechReplyPolicy.Input.TEXT);
        dialogs.record("next-voice", SpeechReplyPolicy.Input.VOICE);
        assertEquals(SpeechReplyPolicy.Input.VOICE, dialogs.input("first-voice"));
        assertEquals(SpeechReplyPolicy.Input.SCREEN, dialogs.input("screen"));
        assertEquals(SpeechReplyPolicy.Input.TEXT, dialogs.input("typed"));
        assertEquals(SpeechReplyPolicy.Input.VOICE, dialogs.input("next-voice"));
        dialogs.remove("screen");
        assertEquals(SpeechReplyPolicy.Input.UNKNOWN, dialogs.input("screen"));
        dialogs.clear();
        assertEquals(SpeechReplyPolicy.Input.UNKNOWN, dialogs.input("first-voice"));
    }

    @Test public void disabledPolicyLeavesEveryInputUntouchedAndTextOnlyDisablesEveryInput() {
        for (SpeechReplyPolicy.Input input : SpeechReplyPolicy.Input.values()) {
            assertNull(SpeechReplyPolicy.override(SpeechReplyPolicy.HOST_DEFAULT, input));
            assertEquals(Boolean.FALSE, SpeechReplyPolicy.override(SpeechReplyPolicy.TEXT_ONLY, input));
        }
    }
}
