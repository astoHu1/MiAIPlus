package io.github.miaiplus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ConversationPolicyTest {
    private static final long NOW = 1_000_000L;

    @Test public void initialWakeStartsNewConversation() {
        assertEquals(ConversationPolicy.Decision.NEW, new ConversationPolicy(0, false).wake(NOW, 30, false));
    }

    @Test public void recentConversationContinuesAcrossSurfaces() {
        ConversationPolicy policy = new ConversationPolicy(NOW, true);
        assertEquals(ConversationPolicy.Decision.CONTINUE, policy.wake(NOW + 29 * 60_000L, 30, false));
        assertEquals(ConversationPolicy.Decision.CONTINUE, policy.wake(NOW + 29 * 60_000L, 30, false));
    }

    @Test public void exactTimeoutStillContinues() {
        assertEquals(ConversationPolicy.Decision.CONTINUE,
            new ConversationPolicy(NOW, true).wake(NOW + 30 * 60_000L, 30, false));
    }

    @Test public void expiredConversationStartsNew() {
        assertEquals(ConversationPolicy.Decision.NEW,
            new ConversationPolicy(NOW, true).wake(NOW + 30 * 60_000L + 1, 30, false));
    }

    @Test public void clockRollbackStartsNewConversation() {
        assertEquals(ConversationPolicy.Decision.NEW, new ConversationPolicy(NOW, true).wake(NOW - 1, 30, false));
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

    @Test public void missingConversationDoesNotContinueEvenWithRecentTouch() {
        assertEquals(ConversationPolicy.Decision.NEW, new ConversationPolicy(NOW, false).wake(NOW + 1, 30, false));
    }

    @Test public void timeoutClampPreventsInvalidLargeDuration() {
        assertEquals(ConversationPolicy.Decision.NEW,
            new ConversationPolicy(NOW, true).wake(NOW + 1440 * 60_000L + 1, Integer.MAX_VALUE, false));
    }

    @Test public void deletedConversationCannotResumeEvenWithRecentUserActivity() {
        ConversationPolicy policy = new ConversationPolicy(NOW, true);
        policy.invalidate();
        assertEquals(ConversationPolicy.Decision.NEW, policy.wake(NOW + 1, 30, false));
    }

    @Test public void nativeInheritedPageCannotConsumePendingWake() {
        assertFalse(ConversationPolicy.acceptsExplicitRoute(true, false, 5, 5));
    }

    @Test public void explicitCompletionReturnOverridesPendingWake() {
        assertTrue(ConversationPolicy.acceptsExplicitRoute(true, true, 5, 5));
    }

    @Test public void separateUserHistorySelectionOverridesPendingWake() {
        assertTrue(ConversationPolicy.acceptsExplicitRoute(true, false, 5, 6));
    }

    @Test public void standaloneRouteCanSelectConversation() {
        assertTrue(ConversationPolicy.acceptsExplicitRoute(false, false, 5, 5));
    }
}
