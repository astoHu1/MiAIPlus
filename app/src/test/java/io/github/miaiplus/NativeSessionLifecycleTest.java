package io.github.miaiplus;

import android.os.SystemClock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class NativeSessionLifecycleTest {
    private static final Class<?> STATE = stateClass();

    @After public void releaseSession() throws Exception {
        NativeSession.cancel();
        awaitCleanup();
    }

    @Test public void stopBetweenStepsReleasesBackgroundWithoutBlockingCaller() throws Exception {
        HelperProcess process = new HelperProcess();
        Object state = state(process);
        synchronized (NativeSession.class) {
            setActive(state);
            // The cleanup executor cannot acquire the session monitor yet. cancel() must still return.
            NativeSession.cancel();
            assertTrue(run(state).cancelled.get());
            assertFalse(process.destroyed);
        }
        awaitCleanup();
        assertTrue(process.destroyed);
        assertNull(field(NativeSession.class, "active").get(null));
    }

    @Test public void interruptedIpcReleasesItsNativeSessionWithoutBlockingCaller() throws Exception {
        HelperProcess process = new HelperProcess();
        Object state = state(process);
        RunState ipc = new RunState("ipc", 1000);
        synchronized (NativeSession.class) {
            setActive(state);
            try (var ignored = bindCaller(state, ipc)) {
                ipc.cancel();
                assertTrue(run(state).cancelled.get());
                assertFalse(process.destroyed);
            }
        }
        awaitCleanup();
        assertTrue(process.destroyed);
        assertNull(field(NativeSession.class, "active").get(null));
    }

    @Test public void cancellationBeforeNativeStepBindsStillReleasesItsDisplay() throws Exception {
        HelperProcess process = new HelperProcess();
        Object state = state(process);
        RunState ipc = new RunState("ipc", 1000);
        ipc.cancel();
        synchronized (NativeSession.class) {
            setActive(state);
            try (var ignored = bindCaller(state, ipc)) {
                assertTrue(run(state).cancelled.get());
                assertFalse(process.destroyed);
            }
        }
        awaitCleanup();
        assertTrue(process.destroyed);
    }

    @Test public void completedIpcCannotCancelItsContinuingSession() throws Exception {
        HelperProcess process = new HelperProcess();
        Object state = state(process);
        setActive(state);
        RunState completed = new RunState("completed-step", 1000);
        bindCaller(state, completed).close();
        RunState next = new RunState("next-step", 1000);
        try (var ignored = bindCaller(state, next)) {
            completed.cancel();
            assertFalse(run(state).cancelled.get());
            assertFalse(process.destroyed);
            assertSame(state, field(NativeSession.class, "active").get(null));
            next.cancel();
        }
        awaitCleanup();
        assertTrue(process.destroyed);
    }

    @Test public void queuedOldCancellationCannotCloseReplacementSession() throws Exception {
        HelperProcess oldProcess = new HelperProcess();
        HelperProcess newProcess = new HelperProcess();
        Object oldState = state(oldProcess);
        Object newState = state(newProcess);
        synchronized (NativeSession.class) {
            setActive(oldState);
            NativeSession.cancel();
            // Reproduce handle() retiring the old state and installing the next one before cleanup runs.
            invoke("close");
            setActive(newState);
        }
        awaitCleanup();
        assertTrue(oldProcess.destroyed);
        assertFalse(newProcess.destroyed);
        assertFalse(run(newState).cancelled.get());
        assertSame(newState, field(NativeSession.class, "active").get(null));
    }

    @Test public void acceptedStepRenewsWatchdogAndRejectsRetiredCallback() throws Exception {
        Object state = state(new HelperProcess());
        setActive(state);
        run(state).deadline = SystemClock.elapsedRealtime() + 1000;
        invoke("scheduleWatchdog", state);
        long oldGeneration = field(STATE, "watchdogGeneration").getLong(state);
        ScheduledFuture<?> oldTimer = (ScheduledFuture<?>) field(STATE, "timeout").get(state);
        invoke("extendBudget", state);
        invoke("watchdog", state, oldGeneration);
        assertTrue(oldTimer.isCancelled());
        assertFalse(run(state).cancelled.get());
        ScheduledFuture<?> renewed = (ScheduledFuture<?>) field(STATE, "timeout").get(state);
        assertTrue(renewed.getDelay(TimeUnit.MILLISECONDS) > RunState.STEP_BUDGET_MS - 1000);
    }

    @Test public void cleanupRemainsBusyUntilBackgroundHelperHasExited() throws Exception {
        HelperProcess process = new HelperProcess(true);
        setActive(state(process));
        NativeSession.cancel();
        try {
            assertTrue("Cleanup must reach helper termination", process.waitingForExit.await(5, TimeUnit.SECONDS));
            assertNull(field(NativeSession.class, "active").get(null));
            assertFalse(NativeSession.hasActiveSession());
            // The state has been detached, but helper teardown is still in flight. Probes must stay excluded.
            assertTrue(NativeSession.hasPendingOperation());
        } finally {
            process.allowExit.countDown();
            awaitCleanup();
        }
        assertFalse(NativeSession.hasPendingOperation());
    }

    private static Object state(HelperProcess process) throws Exception {
        Constructor<?> stateConstructor = STATE.getDeclaredConstructor(String.class, int.class, int.class,
            ScreenService.class, boolean.class);
        stateConstructor.setAccessible(true);
        Object state = stateConstructor.newInstance("test", 1000, 10, null, true);
        Constructor<BackgroundDisplay> displayConstructor = BackgroundDisplay.class.getDeclaredConstructor(Process.class, File.class);
        displayConstructor.setAccessible(true);
        File directory = Files.createTempDirectory("native-session-lifecycle").toFile();
        field(STATE, "display").set(state, displayConstructor.newInstance(process, directory));
        return state;
    }
    private static RunState run(Object state) throws Exception { return (RunState) field(STATE, "run").get(state); }
    private static RunState.CancellationBinding bindCaller(Object state, RunState caller) throws Exception {
        Method method = NativeSession.class.getDeclaredMethod("bindCaller", STATE, RunState.class);
        method.setAccessible(true);
        return (RunState.CancellationBinding) method.invoke(null, state, caller);
    }
    private static void setActive(Object state) throws Exception { field(NativeSession.class, "active").set(null, state); }
    private static void awaitCleanup() throws Exception {
        ExecutorService cleanup = (ExecutorService) field(NativeSession.class, "CLEANUP").get(null);
        cleanup.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static void invoke(String name, Object... args) throws Exception {
        Class<?>[] types = args.length == 0 ? new Class<?>[0]
            : args.length == 1 ? new Class<?>[]{STATE} : new Class<?>[]{STATE, long.class};
        Method method = NativeSession.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(null, args);
    }
    private static Class<?> stateClass() {
        try { return Class.forName(NativeSession.class.getName() + "$State"); }
        catch (ClassNotFoundException failure) { throw new AssertionError(failure); }
    }
    private static final class HelperProcess extends Process {
        boolean destroyed;
        final CountDownLatch waitingForExit = new CountDownLatch(1);
        final CountDownLatch allowExit;
        HelperProcess() { this(false); }
        HelperProcess(boolean holdExit) { allowExit = new CountDownLatch(holdExit ? 1 : 0); }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            waitingForExit.countDown();
            return allowExit.await(timeout, unit);
        }
        @Override public int exitValue() { return 0; }
        @Override public boolean isAlive() { return false; }
        @Override public void destroy() { destroyed = true; }
    }
}
