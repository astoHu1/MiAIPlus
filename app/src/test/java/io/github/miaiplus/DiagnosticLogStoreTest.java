package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class DiagnosticLogStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

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

    @Test public void rejectsMultilineOversizedAndMultibyteRecordsWithoutBreakingNextWrite() throws Exception {
        DiagnosticLogStore store = new DiagnosticLogStore(temporary.newFolder(), DiagnosticLogStore.FILE_BYTES);
        store.append("first\nsecond"); store.append("x\rhidden");
        store.append("中".repeat(4000)); store.append("x".repeat(9000));
        store.append("{\"event\":\"kept\"}");
        assertEquals(4, store.snapshot().dropped());
        assertEquals(java.util.List.of("{\"event\":\"kept\"}"), store.snapshot().lines());
    }

    @Test public void diskFailureIsCountedAndClearAllowsRecovery() throws Exception {
        File blocked = temporary.newFile();
        DiagnosticLogStore store = new DiagnosticLogStore(blocked, 100);
        store.append("{\"event\":\"lost\"}");
        assertEquals(1, store.snapshot().errors());
        assertTrue(blocked.delete());
        store.clear(); store.append("{\"event\":\"recovered\"}");
        assertEquals(0, store.snapshot().errors());
        assertEquals(1, store.snapshot().lines().size());
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

    @Test public void zipIncludesBothSourcesAndOnlyWhitelistedEnvironmentAndClearOrdersWrites() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        Config.prefs(context).edit().putString("apiKey", "private-secret").putString("endpoint", "private-url")
            .putString("model", "private-model").commit();
        DiagnosticLogStore store = new DiagnosticLogStore(temporary.newFolder(), 1000);
        store.append("{\"event\":\"observe\",\"source\":\"module\"}");
        store.append("{\"event\":\"image_attached\",\"source\":\"host\"}");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); store.exportZip(bytes, false);
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(java.util.Set.of("phone-use.jsonl", "environment.json", "README.txt"), files.keySet());
        assertTrue(files.get("phone-use.jsonl").contains("module")); assertTrue(files.get("phone-use.jsonl").contains("host"));
        assertEquals(2, new JSONObject(files.get("environment.json")).getInt("records"));
        assertFalse(new JSONObject(files.get("environment.json")).getBoolean("detailedLogEnabled"));
        assertFalse(files.toString().contains("private-"));
        store.clear(); assertTrue(store.snapshot().lines().isEmpty());
        store.append("{\"event\":\"after_clear\"}");
        assertEquals(java.util.List.of("{\"event\":\"after_clear\"}"), store.snapshot().lines());
    }
}
