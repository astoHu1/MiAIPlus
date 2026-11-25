package io.github.miaiplus;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;

public final class SpeechReplyPolicyTest {
    @Test public void unknownStoredPolicyFollowsInputWhileHostDefaultAndTextOnlyIgnoreInput() {
        assertEquals(SpeechReplyPolicy.FOLLOW_INPUT, new Config(Map.of()).speechReplyPolicy);
        for (Object value : new Object[]{"", "unknown", true, 1}) {
            assertEquals(SpeechReplyPolicy.FOLLOW_INPUT,
                new Config(Map.of(SpeechReplyPolicy.KEY, value)).speechReplyPolicy);
        }
        for (String value : new String[]{SpeechReplyPolicy.TEXT_ONLY, SpeechReplyPolicy.FOLLOW_INPUT, SpeechReplyPolicy.HOST_DEFAULT}) {
            assertEquals(value, new Config(Map.of(SpeechReplyPolicy.KEY, value)).speechReplyPolicy);
        }
        assertEquals(Boolean.TRUE, SpeechReplyPolicy.override(SpeechReplyPolicy.FOLLOW_INPUT, SpeechReplyPolicy.Input.VOICE));
        assertEquals(Boolean.FALSE, SpeechReplyPolicy.override(SpeechReplyPolicy.FOLLOW_INPUT, SpeechReplyPolicy.Input.SCREEN));
        assertEquals(Boolean.FALSE, SpeechReplyPolicy.override(SpeechReplyPolicy.FOLLOW_INPUT, SpeechReplyPolicy.Input.TEXT));
        assertNull(SpeechReplyPolicy.override(SpeechReplyPolicy.FOLLOW_INPUT, SpeechReplyPolicy.Input.UNKNOWN));
        for (SpeechReplyPolicy.Input input : SpeechReplyPolicy.Input.values()) {
            assertNull(SpeechReplyPolicy.override(SpeechReplyPolicy.HOST_DEFAULT, input));
            assertEquals(Boolean.FALSE, SpeechReplyPolicy.override(SpeechReplyPolicy.TEXT_ONLY, input));
        }
    }

    @Test public void screenRequestsAreQuietAndDialogsKeepTheirOwnInputUntilRetired() {
        assertEquals(SpeechReplyPolicy.Input.SCREEN, NativeSpeechPolicyHook.classify(SpeechReplyPolicy.Input.TEXT,
            "com.miui.voiceassist.query&&screen_recognition", null));
        assertEquals(SpeechReplyPolicy.Input.SCREEN, NativeSpeechPolicyHook.classify(SpeechReplyPolicy.Input.TEXT,
            "screen_recog_nlp_with_ref", null));
        assertEquals(SpeechReplyPolicy.Input.VOICE, NativeSpeechPolicyHook.classify(SpeechReplyPolicy.Input.VOICE,
            "screen_recognition_query_origin", null));
        assertEquals(SpeechReplyPolicy.Input.VOICE, NativeSpeechPolicyHook.classify(SpeechReplyPolicy.Input.VOICE,
            "long_press_fullscreen_gesture_line", null));
        var dialogs = new NativeSpeechPolicyHook.Dialogs();
        dialogs.record("first-voice", SpeechReplyPolicy.Input.VOICE);
        dialogs.record("screen", SpeechReplyPolicy.Input.SCREEN);
        dialogs.record("typed", SpeechReplyPolicy.Input.TEXT);
        assertEquals(SpeechReplyPolicy.Input.VOICE, dialogs.input("first-voice"));
        assertEquals(SpeechReplyPolicy.Input.SCREEN, dialogs.input("screen"));
        assertEquals(SpeechReplyPolicy.Input.TEXT, dialogs.input("typed"));
        dialogs.remove("screen");
        assertEquals(SpeechReplyPolicy.Input.UNKNOWN, dialogs.input("screen"));
        dialogs.clear();
        assertEquals(SpeechReplyPolicy.Input.UNKNOWN, dialogs.input("first-voice"));
    }
}
