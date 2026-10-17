package io.github.miaiplus;

import static org.junit.Assert.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class ConversationReturnTest {
    public static final class ExecutionContext {
        private final String identity;
        ExecutionContext(String identity) { this.identity = identity; }
        public String getContextVarIdentity() { return identity; }
        public String getSessionId() { throw new AssertionError("Request session IDs are not chat IDs"); }
    }

    public record TopicInfo(String topicId, String agentId, String chatId) {
        public String getTopicId() { return topicId; }
        public String getAgentId() { return agentId; }
        public String getChatId() { return chatId; }
    }
    public record Page(TopicInfo info) {
        public TopicInfo getTopicGroupInfo() { return info; }
    }

    @Test public void visiblePageMustBelongToTheOriginBeforeItCanReplacePersistedLookup() {
        Page origin = new Page(new TopicInfo("topic-a", "osbot.main", "chat-a"));
        assertTrue(ConversationReturn.matches(origin, "osbot.main", "chat-a"));
        assertFalse(ConversationReturn.matches(origin, "osbot.main", "chat-b"));
        assertFalse(ConversationReturn.matches(origin, "other-agent", "chat-a"));
        assertFalse(ConversationReturn.matches(new Page(new TopicInfo("", "osbot.main", "chat-a")), "osbot.main", "chat-a"));
        assertFalse(ConversationReturn.matches(new Page(null), "osbot.main", "chat-a"));
        assertFalse(ConversationReturn.matches(null, "osbot.main", "chat-a"));
    }

    @Test public void nestedControlCallsRestoreTheirOwnConversationEvenAfterFailure() {
        assertNull(ConversationReturn.currentIdentity());
        try (var outer = ConversationReturn.bind(new ExecutionContext("osbot.main::chat-a"))) {
            assertEquals("osbot.main::chat-a", ConversationReturn.currentIdentity());
            try {
                try (var inner = ConversationReturn.bind(new ExecutionContext("osbot.main::chat-b"))) {
                    assertEquals("osbot.main::chat-b", ConversationReturn.currentIdentity());
                    throw new IllegalStateException("tool failed");
                }
            } catch (IllegalStateException expected) {
                assertEquals("osbot.main::chat-a", ConversationReturn.currentIdentity());
            }
        }
        assertNull(ConversationReturn.currentIdentity());
    }

    @Test public void legacyCallDoesNotInheritOuterConversationAndRestoresItAfterwards() {
        try (var outer = ConversationReturn.bind(new ExecutionContext("osbot.main::chat-a"))) {
            try (var legacy = ConversationReturn.bind(null)) { assertNull(ConversationReturn.currentIdentity()); }
            assertEquals("osbot.main::chat-a", ConversationReturn.currentIdentity());
            try (var blank = ConversationReturn.bind(new ExecutionContext("  "))) { assertNull(ConversationReturn.currentIdentity()); }
            assertEquals("osbot.main::chat-a", ConversationReturn.currentIdentity());
        }
        assertNull(ConversationReturn.currentIdentity());
    }

    @Test public void parallelToolCallsCannotBorrowEachOthersConversation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> other = CompletableFuture.runAsync(() -> {
            assertNull(ConversationReturn.currentIdentity());
            try (var scoped = ConversationReturn.bind(new ExecutionContext("osbot.main::chat-b"))) {
                entered.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                assertEquals("osbot.main::chat-b", ConversationReturn.currentIdentity());
            }
            assertNull(ConversationReturn.currentIdentity());
        });
        try (var scoped = ConversationReturn.bind(new ExecutionContext("osbot.main::chat-a"))) {
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals("osbot.main::chat-a", ConversationReturn.currentIdentity());
            } finally { release.countDown(); }
            other.get(3, TimeUnit.SECONDS);
        }
        assertNull(ConversationReturn.currentIdentity());
    }
}
