package io.github.miaiplus;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Test;

public final class NativeConversationLimitsHookTest {
    @Test public void onlyConversationAgentsLoseCountLimits() {
        assertTrue(NativeConversationLimitsHook.conversationAgent("osbot.main"));
        assertTrue(NativeConversationLimitsHook.conversationAgent("osbot.chat"));
        assertTrue(NativeConversationLimitsHook.conversationAgent("osbot.overlayassistant"));
        assertFalse(NativeConversationLimitsHook.conversationAgent("com.xiaomi.type"));
        assertFalse(NativeConversationLimitsHook.conversationAgent("osbot.content_assistant-title_generate"));
        assertFalse(NativeConversationLimitsHook.conversationAgent(null));
    }

    @Test public void topicCreationGateAllowsHundredsWithoutChangingCountsBelowCap() {
        assertEquals(0, NativeConversationLimitsHook.creationGateCount(0));
        assertEquals(99, NativeConversationLimitsHook.creationGateCount(99));
        assertEquals(99, NativeConversationLimitsHook.creationGateCount(100));
        assertEquals(99, NativeConversationLimitsHook.creationGateCount(205));
    }

    @Test public void immediateEventSendReturnsIterationIdsWithoutResumingCaller() {
        Object ids = new Object(), event = new Object(), suspended = new Object(), unit = new Object();
        Object result = NativeConversationLimitsHook.sendIteration(ids, event, suspended, (sent, resume) -> {
            assertSame(event, sent);
            return unit;
        }, value -> fail("immediate send resumed caller"), RuntimeException.class::isInstance);
        assertSame(ids, result);
    }

    @Test public void suspendedEventSendResumesOnceWithSameIterationIds() {
        Object ids = new Object(), event = new Object(), suspended = new Object();
        AtomicReference<Consumer<Object>> completion = new AtomicReference<>();
        AtomicReference<Object> resumed = new AtomicReference<>();
        assertSame(suspended, NativeConversationLimitsHook.sendIteration(ids, event, suspended, (sent, resume) -> {
            assertSame(event, sent);
            completion.set(resume);
            return suspended;
        }, resumed::set, RuntimeException.class::isInstance));
        assertNull(resumed.get());
        completion.get().accept(new Object());
        assertSame(ids, resumed.get());
    }

    @Test public void suspendedCancellationIsPropagatedWithoutProducingIterationIds() {
        Object ids = new Object(), suspended = new Object();
        AtomicReference<Consumer<Object>> completion = new AtomicReference<>();
        AtomicReference<Object> resumed = new AtomicReference<>();
        NativeConversationLimitsHook.sendIteration(ids, new Object(), suspended, (sent, resume) -> {
            completion.set(resume);
            return suspended;
        }, resumed::set, RuntimeException.class::isInstance);
        RuntimeException cancelled = new java.util.concurrent.CancellationException("owned cancelled send");
        completion.get().accept(cancelled);
        assertSame(cancelled, resumed.get());
    }

    @Test public void immediateEventSendFailureRemainsAFailure() {
        RuntimeException failure = new IllegalStateException("owned closed channel");
        try {
            NativeConversationLimitsHook.sendIteration(new Object(), new Object(), new Object(), (sent, resume) -> {
                throw failure;
            }, value -> fail("failed send resumed caller"), RuntimeException.class::isInstance);
            fail("event failure was swallowed");
        } catch (RuntimeException actual) { assertSame(failure, actual); }
    }
}
