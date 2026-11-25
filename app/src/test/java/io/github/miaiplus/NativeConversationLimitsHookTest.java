package io.github.miaiplus;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Test;

public final class NativeConversationLimitsHookTest {
    @Test public void onlyConversationAgentsLoseCountLimitsAndGateCapsBelowHundred() {
        for (String agent : new String[]{"osbot.main", "osbot.chat", "osbot.overlayassistant"})
            assertTrue(agent, NativeConversationLimitsHook.conversationAgent(agent));
        for (String agent : new String[]{"com.xiaomi.type", "osbot.content_assistant-title_generate", null})
            assertFalse(agent, NativeConversationLimitsHook.conversationAgent(agent));
        assertEquals(0, NativeConversationLimitsHook.creationGateCount(0));
        assertEquals(99, NativeConversationLimitsHook.creationGateCount(99));
        assertEquals(99, NativeConversationLimitsHook.creationGateCount(100));
        assertEquals(99, NativeConversationLimitsHook.creationGateCount(205));
    }

    @Test public void immediateEventSendReturnsIterationIdsOrFailureWithoutResumingCaller() {
        Object ids = new Object(), event = new Object(), suspended = new Object(), unit = new Object();
        Object result = NativeConversationLimitsHook.sendIteration(ids, event, suspended, (sent, resume) -> {
            assertSame(event, sent);
            return unit;
        }, value -> fail("immediate send resumed caller"), RuntimeException.class::isInstance);
        assertSame(ids, result);

        RuntimeException failure = new IllegalStateException("owned closed channel");
        try {
            NativeConversationLimitsHook.sendIteration(ids, event, suspended, (sent, resume) -> {
                throw failure;
            }, value -> fail("failed send resumed caller"), RuntimeException.class::isInstance);
            fail("event failure was swallowed");
        } catch (RuntimeException actual) { assertSame(failure, actual); }
    }

    @Test public void suspendedEventSendResumesWithIterationIdsOrPropagatesCancellation() {
        for (boolean cancel : new boolean[]{false, true}) {
            Object ids = new Object(), event = new Object(), suspended = new Object();
            AtomicReference<Consumer<Object>> completion = new AtomicReference<>();
            AtomicReference<Object> resumed = new AtomicReference<>();
            assertSame(suspended, NativeConversationLimitsHook.sendIteration(ids, event, suspended, (sent, resume) -> {
                assertSame(event, sent);
                completion.set(resume);
                return suspended;
            }, resumed::set, RuntimeException.class::isInstance));
            assertNull(resumed.get());
            RuntimeException cancelled = new java.util.concurrent.CancellationException("owned cancelled send");
            completion.get().accept(cancel ? cancelled : new Object());
            assertSame(cancel ? cancelled : ids, resumed.get());
        }
    }
}
