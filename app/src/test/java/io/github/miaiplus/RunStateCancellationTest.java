package io.github.miaiplus;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public final class RunStateCancellationTest {
    @Test public void interruptedIpcCancelsTheBoundNativeStepOnce() {
        RunState ipc = new RunState("ipc", 1000);
        RunState nativeStep = new RunState("session", 1000);
        AtomicInteger cleanup = new AtomicInteger();
        try (var ignored = ipc.onCancel(() -> { nativeStep.cancel(); cleanup.incrementAndGet(); })) {
            ipc.cancel();
            ipc.cancel();
            assertTrue(nativeStep.cancelled.get());
            assertEquals(1, cleanup.get());
        }
    }

    @Test public void cancellationBeforeNativeSessionSetupIsNotLost() {
        RunState ipc = new RunState("ipc", 1000);
        RunState nativeStep = new RunState("session", 1000);
        ipc.cancel();
        try (var ignored = ipc.onCancel(nativeStep::cancel)) {
            assertTrue(nativeStep.cancelled.get());
        }
    }

    @Test public void cancelledOldIpcCannotStopTheNextStepOrReplacement() {
        RunState oldIpc = new RunState("old-ipc", 1000);
        RunState nativeSession = new RunState("session", 1000);
        oldIpc.onCancel(nativeSession::cancel).close();
        RunState nextIpc = new RunState("next-ipc", 1000);
        RunState replacement = new RunState("replacement", 1000);
        try (var ignored = nextIpc.onCancel(replacement::cancel)) {
            oldIpc.cancel();
            assertFalse(nativeSession.cancelled.get());
            assertFalse(replacement.cancelled.get());
            nextIpc.cancel();
            assertTrue(replacement.cancelled.get());
        }
    }
}
