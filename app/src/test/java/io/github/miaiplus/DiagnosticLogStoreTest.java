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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowSystemClock;

/** Diagnostic storage and the separate host-process delivery path, including the bounded asynchronous worker. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class DiagnosticLogStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Application application;
    private RecordingProvider provider;
    private ThreadPoolExecutor delivery;
    private CountDownLatch workerRelease;

    @Test public void rotatesWholeRecordsWithinCapAndPersistsAcrossInstances() throws Exception {
        File directory = temporary.newFolder();
        DiagnosticLogStore store = new DiagnosticLogStore(directory, 100);
        for (int i = 0; i < 15; i++) store.append("{\"event\":\"record_" + i + "\",\"source\":\"module\"}");
        var snapshot = store.snapshot();
        assertTrue(snapshot.bytes() <= 400);
        assertTrue(snapshot.lines().size() < 15);
        assertTrue(snapshot.lines().get(snapshot.lines().size() - 1).contains("record_14"));
        for (String line : snapshot.lines()) assertEquals("module", new JSONObject(line).getString("source"));
        assertEquals(4, directory.listFiles().length);
        assertEquals(snapshot.lines(), new DiagnosticLogStore(directory, 100).snapshot().lines());
    }

    @Test public void rejectedRecordsAndDiskFailuresAreCountedWithoutBreakingLaterWrites() throws Exception {
        DiagnosticLogStore store = new DiagnosticLogStore(temporary.newFolder(), DiagnosticLogStore.FILE_BYTES);
        store.append("first\nsecond"); store.append("x\rhidden");
        store.append("中".repeat(4000)); store.append("x".repeat(9000));
        store.append("{\"event\":\"kept\"}");
        assertEquals(4, store.snapshot().dropped());
        assertEquals(java.util.List.of("{\"event\":\"kept\"}"), store.snapshot().lines());

        File blocked = temporary.newFile();
        DiagnosticLogStore failing = new DiagnosticLogStore(blocked, 100);
        failing.append("{\"event\":\"lost\"}");
        assertEquals(1, failing.snapshot().errors());
        assertTrue(blocked.delete());
        failing.clear(); failing.append("{\"event\":\"recovered\"}");
        assertEquals(0, failing.snapshot().errors());
        assertEquals(1, failing.snapshot().lines().size());
    }

    @Test public void saturatedWorkerDropsInsteadOfBlockingControlCaller() throws Exception {
        DiagnosticLogStore store = new DiagnosticLogStore(temporary.newFolder(), DiagnosticLogStore.FILE_BYTES);
        var field = DiagnosticLogStore.class.getDeclaredField("worker"); field.setAccessible(true);
        ThreadPoolExecutor worker = (ThreadPoolExecutor) field.get(store);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        worker.execute(() -> {
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        try {
            for (int i = 0; i < 400; i++) store.append("{\"event\":\"queued\"}");
            assertEquals(256, worker.getQueue().size());
        } finally { release.countDown(); }
        // A barrier may be rejected until one queued write completes; wait on the executor itself.
        worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(256, Files.readAllLines(new File(temporary.getRoot().listFiles()[0], "events.0.jsonl").toPath()).size());
    }

    @Test public void zipIncludesBothSourcesOnlyWhitelistedEnvironmentAndSuppliedStateAfterClear() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        Config.prefs(context).edit().putString("apiKey", "private-secret").putString("endpoint", "private-url")
            .putString("model", "private-model").commit();
        DiagnosticLogStore store = new DiagnosticLogStore(temporary.newFolder(), 1000);
        store.append("{\"event\":\"observe\",\"source\":\"module\"}");
        store.append("{\"event\":\"image_attached\",\"source\":\"host\"}");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); store.exportZip(bytes, false);
        Map<String, String> files = zipEntries(bytes);
        assertEquals(java.util.Set.of("phone-use.jsonl", "environment.json", "README.txt"), files.keySet());
        assertTrue(files.get("phone-use.jsonl").contains("module")); assertTrue(files.get("phone-use.jsonl").contains("host"));
        assertEquals(2, new JSONObject(files.get("environment.json")).getInt("records"));
        assertFalse(new JSONObject(files.get("environment.json")).getBoolean("detailedLogEnabled"));
        assertFalse(files.toString().contains("private-"));
        store.clear(); assertTrue(store.snapshot().lines().isEmpty());
        store.append("{\"event\":\"after_clear\"}");
        assertEquals(java.util.List.of("{\"event\":\"after_clear\"}"), store.snapshot().lines());

        store.clear();
        JSONObject state = new JSONObject().put("host", new JSONObject().put("profile", "modern"))
            .put("config", new JSONObject().put("enabled", false));
        bytes = new ByteArrayOutputStream(); store.exportZip(bytes, false, state);
        JSONObject environment = new JSONObject(zipEntries(bytes).get("environment.json"));
        assertEquals(DiagnosticEnvironment.SCHEMA, environment.getInt("schema"));
        assertEquals(0, environment.getInt("records"));
        assertEquals("modern", environment.getJSONObject("host").getString("profile"));
        assertFalse(environment.getJSONObject("config").getBoolean("enabled"));
        assertEquals(0, environment.getJSONObject("log").getInt("hostRecords"));
    }

    private static Map<String, String> zipEntries(ByteArrayOutputStream bytes) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        return files;
    }

    @Test public void summaryUsesEventTimeAndSeparatesProcessesVersionsAndIncompleteRecords() throws Exception {
        JSONObject summary = DiagnosticLogStore.summary(java.util.List.of(
            "{\"event\":\"host_error\",\"source\":\"host\",\"time\":300,\"pid\":11,\"ver\":10,\"hostDropped\":2}",
            "{\"event\":\"observation\",\"source\":\"module\",\"time\":200,\"pid\":22}",
            "{\"event\":\"capture_complete\",\"source\":\"accessibility\",\"time\":250,\"pid\":44}",
            "invalid-json",
            "{\"event\":\"host_error\",\"source\":\"host\",\"time\":100,\"pid\":33,\"ver\":20,\"hostDeliveryErrors\":3}"
        ));
        assertEquals(1, summary.getInt("moduleRecords"));
        assertEquals(2, summary.getInt("hostRecords"));
        assertEquals(1, summary.getInt("unknownSourceRecords"));
        assertEquals("[22]", summary.getJSONArray("modulePids").toString());
        assertEquals(1, summary.getInt("unreadable"));
        assertEquals(100, summary.getLong("firstTime"));
        assertEquals(300, summary.getLong("lastTime"));
        assertEquals("[11,33]", summary.getJSONArray("hostPids").toString());
        assertEquals("[10,20]", summary.getJSONArray("hostModuleVersions").toString());
        assertEquals(2, summary.getLong("hostDroppedMax"));
        assertEquals(3, summary.getLong("hostDeliveryErrorsMax"));
        assertEquals(2, summary.getJSONObject("events").getInt("host_error"));
        assertFalse(DiagnosticLogStore.summary(java.util.List.of()).has("firstTime"));
    }

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
