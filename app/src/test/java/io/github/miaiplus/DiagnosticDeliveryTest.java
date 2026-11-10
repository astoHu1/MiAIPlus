package io.github.miaiplus;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowSystemClock;

/** Exercises the separate host-process path, including the actual bounded asynchronous delivery worker. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class DiagnosticDeliveryTest {
    private Application application;
    private RecordingProvider provider;
    private ThreadPoolExecutor delivery;
    private CountDownLatch workerRelease;

    @Before public void setUp() throws Exception {
        application = RuntimeEnvironment.getApplication();
        PhoneUseLog.disposeRemote();
        PhoneUseLog.init(application);
        field("preferences").set(null, null);
        delivery = (ThreadPoolExecutor) field("delivery").get(null);
        provider = new RecordingProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = BridgeProvider.URI.getAuthority();
        provider.attachInfo(application, info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        PhoneUseLog.initRemote(application);
    }

    @After public void tearDown() throws Exception {
        provider.settingsRelease.countDown();
        if (workerRelease != null) workerRelease.countDown();
        drain();
        PhoneUseLog.disposeRemote();
        PhoneUseLog.init(application);
    }

    @Test public void disposingAfterEnqueueDiscardsCallbacksFromThePreviousHostGeneration() throws Exception {
        blockWorker();
        AtomicInteger inspections = new AtomicInteger();
        PhoneUseLog.hostInspect(inspections::incrementAndGet);
        assertEquals(1, delivery.getQueue().size());

        PhoneUseLog.disposeRemote();
        workerRelease.countDown();
        drain();
        assertEquals(0, inspections.get());
        assertEquals(0, provider.settingsCalls.get());
        assertTrue(provider.records.isEmpty());
    }

    @Test public void disabledRemoteLoggingNeverInspectsHostObjectsAndCachesTheOffState() throws Exception {
        provider.enabled = false;
        AtomicInteger inspections = new AtomicInteger();
        PhoneUseLog.hostInspect(inspections::incrementAndGet);
        drain();
        assertEquals(0, inspections.get());
        assertEquals(1, provider.settingsCalls.get());

        PhoneUseLog.hostInspect(inspections::incrementAndGet);
        drain();
        assertEquals(0, inspections.get());
        assertEquals(1, provider.settingsCalls.get());
        assertTrue(provider.records.isEmpty());
    }

    @Test public void activeHostInspectionRejectsReloadUntilItsReferencesAreReleased() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        workerRelease = new CountDownLatch(1);
        PhoneUseLog.hostInspect(() -> {
            entered.countDown();
            try { assertTrue(workerRelease.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertFalse(io.github.miaiplus.hook.Hooks.freeze());
        workerRelease.countDown();
        drain();
        assertTrue(io.github.miaiplus.hook.Hooks.freeze());
        try {
            AtomicInteger inspections = new AtomicInteger();
            PhoneUseLog.hostInspect(inspections::incrementAndGet);
            drain();
            assertEquals(0, inspections.get());
        } finally { io.github.miaiplus.hook.Hooks.thaw(); }
    }

    @Test public void assertionErrorsInRemoteAndNestedInspectionDoNotStopLaterRecords() throws Exception {
        AtomicInteger inspections = new AtomicInteger();
        PhoneUseLog.hostInspect(() -> { inspections.incrementAndGet(); throw new AssertionError("read failed"); });
        PhoneUseLog.hostInspect(() -> {
            PhoneUseLog.hostInspect(() -> { inspections.incrementAndGet(); throw new AssertionError("nested read failed"); });
            PhoneUseLog.hostEvent("after_nested_failure");
        });
        PhoneUseLog.hostEvent("after_failure");
        drain();

        assertEquals(2, inspections.get());
        assertEquals(List.of("after_nested_failure", "after_failure"), events());
    }

    @Test public void callerReturnsWhileTheDeliveryWorkerIsStillWaitingForSettingsIpc() throws Exception {
        provider.blockSettings = true;
        AtomicInteger inspections = new AtomicInteger();

        // This call runs on the test/UI thread. A synchronous IPC would hold this thread behind settingsRelease.
        PhoneUseLog.hostInspect(inspections::incrementAndGet);
        assertTrue(provider.settingsEntered.await(3, TimeUnit.SECONDS));
        assertEquals(1, provider.settingsRelease.getCount());
        assertEquals(0, inspections.get());

        provider.settingsRelease.countDown();
        drain();
        assertEquals(1, inspections.get());
        assertNotEquals(Thread.currentThread(), provider.settingsThread);
    }

    @Test public void nestedInspectionKeepsCallerTimeAndTraceAndDoesNotRequeueBehindNewerCalls() throws Exception {
        blockWorker();
        long callerTime = System.currentTimeMillis(), callerElapsed = SystemClock.elapsedRealtime();
        JSONObject trace = new JSONObject().put("session", "session-reference").put("step", 7)
            .put("observation", "observation-reference");
        try (PhoneUseLog.Scope ignored = PhoneUseLog.fromMetadata(trace)) {
            PhoneUseLog.hostInspect(() -> {
                PhoneUseLog.hostInspect(() -> PhoneUseLog.hostEvent("nested_first"));
                PhoneUseLog.hostEvent("nested_second");
            });
        }
        long queuedTime = System.currentTimeMillis();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(3));
        PhoneUseLog.hostEvent("later_call");
        workerRelease.countDown();
        drain();

        assertEquals(List.of("nested_first", "nested_second", "later_call"), events());
        for (int index = 0; index < 2; index++) {
            JSONObject record = new JSONObject(provider.records.get(index));
            assertTrue(record.getLong("time") >= callerTime && record.getLong("time") <= queuedTime);
            assertEquals(new JSONObject(provider.records.get(0)).getLong("time"), record.getLong("time"));
            assertEquals(callerElapsed, record.getLong("elapsedMs"));
            assertEquals("session-reference", record.getString("session"));
            assertEquals(7, record.getInt("step"));
            assertEquals("observation-reference", record.getString("observation"));
            assertEquals("host", record.getString("source"));
        }
        JSONObject later = new JSONObject(provider.records.get(2));
        assertTrue(later.getLong("elapsedMs") >= callerElapsed + 3000);
        assertFalse(later.has("session"));
        assertEquals(1, provider.settingsCalls.get());
    }

    private void blockWorker() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        workerRelease = new CountDownLatch(1);
        delivery.execute(() -> { started.countDown(); await(workerRelease); });
        assertTrue(started.await(3, TimeUnit.SECONDS));
    }

    private void drain() throws Exception { delivery.submit(() -> { }).get(5, TimeUnit.SECONDS); }

    private List<String> events() throws Exception {
        java.util.ArrayList<String> events = new java.util.ArrayList<>();
        for (String line : provider.records) events.add(new JSONObject(line).getString("event"));
        return events;
    }

    private static Field field(String name) throws Exception {
        Field field = PhoneUseLog.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test latch timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    public static final class RecordingProvider extends ContentProvider {
        final AtomicInteger settingsCalls = new AtomicInteger();
        final List<String> records = new CopyOnWriteArrayList<>();
        final CountDownLatch settingsEntered = new CountDownLatch(1), settingsRelease = new CountDownLatch(1);
        volatile boolean enabled = true, blockSettings;
        volatile Thread settingsThread;

        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            if ("phone_use_log_settings".equals(method)) {
                settingsCalls.incrementAndGet(); settingsThread = Thread.currentThread();
                settingsEntered.countDown();
                if (blockSettings) await(settingsRelease);
                Bundle reply = new Bundle(); reply.putBoolean("enabled", enabled); return reply;
            }
            if ("phone_use_log_append".equals(method)) { records.add(extras.getString("record")); return new Bundle(); }
            throw new AssertionError("Unexpected diagnostic IPC method");
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
}
