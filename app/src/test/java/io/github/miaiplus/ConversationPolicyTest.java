package io.github.miaiplus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import org.junit.Test;

public class ConversationPolicyTest {
    private static final long NOW = 1_000_000L;

    @Test public void wakeChecksConversationPresenceAndIdleTimeBoundaries() {
        record WakeCase(String name, long lastActivity, boolean selected, long now, int timeoutMinutes,
                        ConversationPolicy.Decision expected) {}
        WakeCase[] cases = {
            new WakeCase("initial wake", 0, false, NOW, 30, ConversationPolicy.Decision.NEW),
            new WakeCase("recent touch without conversation", NOW, false, NOW + 1, 30, ConversationPolicy.Decision.NEW),
            new WakeCase("within timeout", NOW, true, NOW + 29 * 60_000L, 30, ConversationPolicy.Decision.CONTINUE),
            new WakeCase("exact timeout", NOW, true, NOW + 30 * 60_000L, 30, ConversationPolicy.Decision.CONTINUE),
            new WakeCase("expired timeout", NOW, true, NOW + 30 * 60_000L + 1, 30, ConversationPolicy.Decision.NEW),
            new WakeCase("clock rollback", NOW, true, NOW - 1, 30, ConversationPolicy.Decision.NEW),
            new WakeCase("timeout clamped to one day", NOW, true, NOW + 1440 * 60_000L + 1,
                Integer.MAX_VALUE, ConversationPolicy.Decision.NEW)
        };
        for (WakeCase test : cases) {
            assertEquals(test.name(), test.expected(), new ConversationPolicy(test.lastActivity(), test.selected())
                .wake(test.now(), test.timeoutMinutes(), false));
        }
    }

    @Test public void disabledDoesNotMutateActivityOrConversation() {
        ConversationPolicy policy = new ConversationPolicy(100, false);
        assertEquals(ConversationPolicy.Decision.DISABLED, policy.wake(NOW, 0, false));
        assertEquals(100, policy.lastActivity());
        assertFalse(policy.hasConversation());
    }

    @Test public void busyWakeDoesNotExtendExistingConversation() {
        ConversationPolicy policy = new ConversationPolicy(100, true);
        assertEquals(ConversationPolicy.Decision.BUSY, policy.wake(NOW, 30, true));
        assertEquals(100, policy.lastActivity());
    }

    @Test public void rejectedWakeDoesNotConsumeExpiry() {
        ConversationPolicy policy = new ConversationPolicy(100, true);
        assertEquals(ConversationPolicy.Decision.NEW, policy.wake(NOW + 30 * 60_000L, 30, false));
        assertEquals(ConversationPolicy.Decision.NEW, policy.wake(NOW + 30 * 60_000L + 1, 30, false));
    }

    @Test public void userInteractionExtendsConversation() {
        ConversationPolicy policy = new ConversationPolicy(NOW, true);
        policy.userActivity(NOW + 29 * 60_000L);
        assertEquals(ConversationPolicy.Decision.CONTINUE, policy.wake(NOW + 31 * 60_000L, 30, false));
    }

    @Test public void explicitHistoryOrNewConversationResetsActivity() {
        ConversationPolicy policy = new ConversationPolicy(100, false);
        policy.selected(NOW);
        assertEquals(ConversationPolicy.Decision.CONTINUE, policy.wake(NOW + 1, 30, false));
    }

    @Test public void deletedConversationCannotResumeEvenWithRecentUserActivity() {
        ConversationPolicy policy = new ConversationPolicy(NOW, true);
        policy.invalidate();
        assertEquals(ConversationPolicy.Decision.NEW, policy.wake(NOW + 1, 30, false));
    }

    @Test public void explicitRouteOverridesPendingWakeOnlyForReturnOrNewerUserInput() {
        record RouteCase(String name, boolean pending, boolean moduleReturn, long currentInput, boolean accepted) {}
        RouteCase[] cases = {
            new RouteCase("inherited native page", true, false, 5, false),
            new RouteCase("completion return", true, true, 5, true),
            new RouteCase("new user history selection", true, false, 6, true),
            new RouteCase("standalone route", false, false, 5, true)
        };
        for (RouteCase test : cases) {
            assertEquals(test.name(), test.accepted(),
                ConversationPolicy.acceptsExplicitRoute(test.pending(), test.moduleReturn(), 5, test.currentInput()));
        }
    }
}
