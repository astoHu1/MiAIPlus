package com.aios.osbot.tools;

import java.util.List;
import java.util.Map;

/** Records observable tool-result flags without implementing the host agent. */
public abstract class ToolResult {
    public static final class Success extends ToolResult {
        private final String result;
        public final Map<?, ?> metadata;
        public final boolean directAnswer;
        public Success(String text, Map<?, ?> metadata, List<?> images, List<?> resources,
            boolean directAnswer, String ignored, boolean hidden) {
            result = text; this.metadata = metadata; this.directAnswer = directAnswer;
        }
        public String getResult() { return result; }
    }
    public static final class Failure extends ToolResult {
        private final String error;
        public final boolean retryable;
        public final boolean terminateAgent;
        public Failure(String error, boolean retryable, FailureType type, String details,
            boolean terminateAgent, String message, Object extra) {
            this.error = error; this.retryable = retryable; this.terminateAgent = terminateAgent;
        }
        public String getError() { return error; }
    }
}
