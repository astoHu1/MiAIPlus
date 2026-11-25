package io.github.miaiplus;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModelHeadersTest {
    @After public void clearSessions() { ModelHeaders.clear(); }

    @Test public void nativeHistoryKeepsSessionAcrossTurnsRetriesAndPartialTrimming() {
        String session = ModelHeaders.sessionForMessages(List.of("first-user"));
        assertEquals(session, ModelHeaders.sessionForMessages(List.of("first-user")));
        assertEquals(session, ModelHeaders.sessionForMessages(List.of("first-user", "assistant", "second-user")));
        assertEquals(session, ModelHeaders.sessionForMessages(List.of("second-user", "tool-call")));
        assertNotEquals(session, ModelHeaders.sessionForMessages(List.of("other-chat-user")));
        assertNotEquals(ModelHeaders.sessionForMessages(List.of()), ModelHeaders.sessionForMessages(List.of()));
    }

    @Test public void anthropicAuthenticationSupportsApiKeysAndExplicitBearerTokens() {
        assertEquals(Map.of("anthropic-version", "2023-06-01", "x-api-key", "fixture-key"),
            ModelHeaders.provider("anthropic", "fixture-key"));
        assertEquals(Map.of("anthropic-version", "2023-06-01", "Authorization", "Bearer fixture-token"),
            ModelHeaders.provider("anthropic", "Bearer fixture-token"));
        assertEquals(Map.of("anthropic-version", "2023-06-01"), ModelHeaders.provider("anthropic", ""));
        assertEquals(Map.of("Authorization", "Bearer fixture-key"), ModelHeaders.provider("chat", "fixture-key"));
    }

    @Test public void nativeSessionsUseMessageIdentityRatherThanPromptContent() {
        assertEquals(ModelHeaders.nativeSession(List.of(new Message("a", "SYSTEM"), new Message("b", "USER"))),
            ModelHeaders.nativeSession(List.of(new Message("c", "SYSTEM"), new Message("b", "USER"))));
        assertNotEquals(ModelHeaders.nativeSession(List.of(new Message("b", "USER"))),
            ModelHeaders.nativeSession(List.of(new Message("d", "USER"))));
    }

    @Test public void identifiersAreOpaqueStableAndHeaderSafe() {
        String id = ModelHeaders.identifier("run:private\r\nidentity");
        assertEquals(id, UUID.fromString(id).toString());
        assertEquals(id, ModelHeaders.identifier("run:private\r\nidentity"));
        assertNotEquals(id, ModelHeaders.identifier("run:another"));
    }

    public static final class Message {
        private final String id, role;
        Message(String id, String role) { this.id = id; this.role = role; }
        public String getId() { return id; }
        public String getRole() { return role; }
    }
}
