package io.github.miaiplus;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ConversationNavigationTest {
    public record Topic(String topicId, String agentId, String chatId) {
        public String getTopicId() { return topicId; }
        public String getAgentId() { return agentId; }
        public String getChatId() { return chatId; }
    }

    @Test public void postedNewTopicNavigationRetainsTheHydratedNativeSession() {
        Topic pending = new Topic("topic-a", null, null);
        Topic parcelCopy = new Topic("topic-a", null, null);
        Topic initialized = new Topic("topic-a", "osbot.main", "chat-created-asynchronously");

        assertSame(initialized, NativeConversationHook.automaticNavigationTopic(pending, parcelCopy, initialized));
        assertEquals("chat-created-asynchronously", ((Topic) NativeConversationHook.automaticNavigationTopic(
            pending, parcelCopy, initialized)).getChatId());
    }

    @Test public void automaticSynchronizationDoesNotConsumeAnotherConversationSelection() {
        Topic pending = new Topic("topic-a", null, null);
        Topic other = new Topic("topic-b", "osbot.main", "chat-b");
        assertNull(NativeConversationHook.automaticNavigationTopic(pending, other, pending));
        assertNull(NativeConversationHook.automaticNavigationTopic(pending, pending, other));
        assertNull(NativeConversationHook.automaticNavigationTopic(null, pending, pending));
        assertNull(NativeConversationHook.automaticNavigationTopic(pending, pending, null));
        assertNull(NativeConversationHook.automaticNavigationTopic(new Topic("", null, null), pending, pending));
    }
}
