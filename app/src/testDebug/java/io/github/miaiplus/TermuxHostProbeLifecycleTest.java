package io.github.miaiplus;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class TermuxHostProbeLifecycleTest {
    private static final String NONCE = "01234567-89ab-cdef-0123-456789abcdef";

    @Test public void reportsCompletionOnlyAfterTheActionHasExited() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicInteger released = new AtomicInteger();
        var run = new PluginPlatformProbe.TermuxHostRun(NONCE, () -> {
            entered.countDown(); release.await(); return "TERMUX_MCP_HOST_PASS " + NONCE;
        }, result::set, released::incrementAndGet);
        try {
            run.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertNull(result.get());
            assertEquals(0, released.get());
        } finally { release.countDown(); run.completion.join(2000); }
        assertFalse(run.completion.isAlive());
        assertEquals("TERMUX_MCP_HOST_PASS " + NONCE + "\nTERMUX_MCP_HOST_EXITED " + NONCE, result.get());
        assertEquals(1, released.get());
    }

    @Test public void cancellationDoesNotAcknowledgeAnActionWhichIgnoresInterruption() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        var run = new PluginPlatformProbe.TermuxHostRun(NONCE, () -> {
            entered.countDown();
            while (true) {
                try { release.await(); break; }
                catch (InterruptedException ignored) { interrupted.countDown(); }
            }
            return "late success";
        }, result::set, () -> { });
        try {
            run.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            run.cancel();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertNull(result.get());
            assertTrue(run.completion.isAlive());
        } finally { release.countDown(); run.completion.join(2000); }
        assertFalse(run.completion.isAlive());
        assertEquals("TERMUX_MCP_HOST_CANCELLED " + NONCE + "\nTERMUX_MCP_HOST_EXITED " + NONCE, result.get());
    }

    @Test public void cancellationBeforeStartAcknowledgesExitWithoutRunningTheAction() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> result = new AtomicReference<>();
        var run = new PluginPlatformProbe.TermuxHostRun(NONCE, () -> {
            calls.incrementAndGet(); return "unexpected action";
        }, result::set, () -> { });
        run.cancel(); run.start(); run.completion.join(2000);
        assertFalse(run.completion.isAlive());
        assertEquals(0, calls.get());
        assertEquals("TERMUX_MCP_HOST_CANCELLED " + NONCE + "\nTERMUX_MCP_HOST_EXITED " + NONCE, result.get());
    }

    @Test public void actionFailureStillAcknowledgesActualExit() throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        var run = new PluginPlatformProbe.TermuxHostRun(NONCE, () -> {
            throw new IllegalStateException("owned failure");
        }, result::set, () -> { });
        run.start(); run.completion.join(2000);
        assertFalse(run.completion.isAlive());
        assertEquals("TERMUX_MCP_HOST_FAIL " + NONCE + " IllegalStateException: owned failure\nTERMUX_MCP_HOST_EXITED " + NONCE, result.get());
    }

    @Test public void interruptingTheCompletionWaitDoesNotPublishAnEarlyAcknowledgement() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        var run = new PluginPlatformProbe.TermuxHostRun(NONCE, () -> {
            entered.countDown(); release.await(); return "owned success";
        }, result::set, () -> { });
        try {
            run.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            run.completion.interrupt();
            assertNull(result.get());
        } finally { release.countDown(); run.completion.join(2000); }
        assertFalse(run.completion.isAlive());
        assertEquals("owned success\nTERMUX_MCP_HOST_EXITED " + NONCE, result.get());
    }

    @Test public void cancellingAnAlreadyCompletedActionCannotWriteAnotherReport() throws Exception {
        AtomicInteger reports = new AtomicInteger();
        AtomicReference<String> result = new AtomicReference<>();
        var run = new PluginPlatformProbe.TermuxHostRun(NONCE, () -> "owned success", value -> {
            reports.incrementAndGet(); result.set(value);
        }, () -> { });
        run.start(); run.completion.join(2000);
        assertFalse(run.completion.isAlive());
        run.cancel(); run.cancel();
        assertEquals(1, reports.get());
        assertEquals("owned success\nTERMUX_MCP_HOST_EXITED " + NONCE, result.get());
    }
}
