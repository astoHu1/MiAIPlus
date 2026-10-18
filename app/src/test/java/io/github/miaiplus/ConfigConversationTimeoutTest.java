package io.github.miaiplus;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ConfigConversationTimeoutTest {
    @Test public void existingInstallationsDefaultToFifteenMinutes() {
        assertEquals(15, new Config(Map.of()).conversationTimeoutMinutes);
    }

    @Test public void zeroDisablesTimeoutAndSupportedMinutesArePreserved() {
        assertEquals(0, new Config(Map.of("conversationTimeoutMinutes", 0)).conversationTimeoutMinutes);
        assertEquals(1, new Config(Map.of("conversationTimeoutMinutes", 1)).conversationTimeoutMinutes);
        assertEquals(1440, new Config(Map.of("conversationTimeoutMinutes", 1440)).conversationTimeoutMinutes);
    }

    @Test public void outOfRangePersistedMinutesAreClamped() {
        assertEquals(0, new Config(Map.of("conversationTimeoutMinutes", -1)).conversationTimeoutMinutes);
        assertEquals(1440, new Config(Map.of("conversationTimeoutMinutes", 1441)).conversationTimeoutMinutes);
    }

    @Test public void malformedPersistedValueUsesDefault() {
        assertEquals(15, new Config(Map.of("conversationTimeoutMinutes", "invalid")).conversationTimeoutMinutes);
    }

}
