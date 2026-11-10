package io.github.miaiplus;

/** Reply policy for an individual user request; never changes Xiaomi's persisted speech preference. */
final class SpeechReplyPolicy {
    static final String KEY = "speechReplyPolicy";
    static final String TEXT_ONLY = "text_only";
    static final String FOLLOW_INPUT = "follow_input";
    static final String HOST_DEFAULT = "host_default";

    enum Input { VOICE, TEXT, SCREEN, UNKNOWN }

    private SpeechReplyPolicy() { }

    static String normalize(Object value) {
        return TEXT_ONLY.equals(value) ? TEXT_ONLY : HOST_DEFAULT.equals(value) ? HOST_DEFAULT : FOLLOW_INPUT;
    }

    /** Null means leave the host's own decision untouched. */
    static Boolean override(String policy, Input input) {
        if (HOST_DEFAULT.equals(policy)) return null;
        if (TEXT_ONLY.equals(policy)) return false;
        return switch (input) {
            case VOICE -> true;
            case TEXT, SCREEN -> false;
            case UNKNOWN -> null;
        };
    }
}
