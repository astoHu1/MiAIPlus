package io.github.miaiplus;

import android.app.Application;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class ModelsDevCacheTest {
    private static final String CATALOG = "{\"openai\":{\"models\":{\"future\":{\"reasoning\":true,"
        + "\"reasoning_options\":[{\"type\":\"effort\",\"values\":[\"low\",\"high\"]}]}}}}";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final long[] now = {1_700_000_000_000L};
    private final List<String> events = new ArrayList<>();

    @After public void clearPublishedCatalog() { ModelsDevCatalog.publish(null); }

    private ModelsDevCache.Engine engine(File directory, ModelsDevCache.Transport transport) {
        return new ModelsDevCache.Engine(directory, transport, () -> now[0],
            (event, models, providers, age, detail) -> events.add(event + ":" + detail));
    }

    private static ModelsDevCache.Response response(int code, String body, String etag) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new ModelsDevCache.Response(code, etag, "Thu, 01 Jan 2026 00:00:00 GMT", bytes.length,
            new ByteArrayInputStream(bytes));
    }

    @Test public void downloadsPersistedSnapshotAndRestartLoadsItWithoutNetwork() throws Exception {
        File directory = temporary.newFolder();
        AtomicInteger calls = new AtomicInteger();
        ModelsDevCache.Engine cache = engine(directory, (etag, modified) -> {
            calls.incrementAndGet(); assertEquals("", etag); assertEquals("", modified);
            return response(200, CATALOG, "\"v1\"");
        });
        cache.refresh();
        assertEquals(1, calls.get()); assertEquals(1, cache.status().modelCount());
        assertEquals(now[0], cache.status().checkedAt());
        assertTrue(new File(directory, "catalog.bin").isFile());
        assertTrue(events.contains("refreshed:200"));
        ModelsDevCatalog.publish(null);
        ModelsDevCache.Engine restarted = engine(directory, (etag, modified) -> {
            throw new AssertionError("Fresh disk cache must not request the network");
        });
        restarted.refresh();
        assertEquals(1, restarted.status().providerCount()); assertEquals(1, restarted.status().modelCount());
        assertNotNull(ModelsDevCatalog.resolve("https://api.openai.com/v1", "future", "chat"));
        assertTrue(events.contains("cache_loaded:disk"));
    }

    @Test public void staleCacheUsesValidatorsAnd304RenewsTimestampAcrossRestart() throws Exception {
        File directory = temporary.newFolder();
        engine(directory, (etag, modified) -> response(200, CATALOG, "\"v1\"")).refresh();
        now[0] += ModelsDevCache.REFRESH_MS;
        ModelsDevCache.Engine cache = engine(directory, (etag, modified) -> {
            assertEquals("\"v1\"", etag); assertEquals("Thu, 01 Jan 2026 00:00:00 GMT", modified);
            return response(304, "", "\"v2\"");
        });
        cache.refresh();
        assertEquals(now[0], cache.status().checkedAt()); assertEquals(1, cache.status().modelCount());
        assertTrue(events.contains("not_modified:304"));
        ModelsDevCache.Engine restarted = engine(directory, (etag, modified) -> {
            assertEquals("\"v2\"", etag); return response(304, "", "");
        });
        restarted.refresh();
        assertEquals(now[0], restarted.status().checkedAt());
        now[0] += ModelsDevCache.REFRESH_MS;
        restarted.refresh();
        assertEquals(now[0], restarted.status().checkedAt());
    }

    @Test public void failurePreservesLastGoodBytesAndBacksOffBeforeRetrying() throws Exception {
        File directory = temporary.newFolder();
        ArrayDeque<ModelsDevCache.Response> responses = new ArrayDeque<>(List.of(
            response(200, CATALOG, "v1"), response(503, "private server response must not appear in logs", ""),
            response(200, CATALOG, "v2")));
        AtomicInteger calls = new AtomicInteger();
        ModelsDevCache.Engine cache = engine(directory, (etag, modified) -> {
            calls.incrementAndGet(); return responses.remove();
        });
        cache.refresh(); long checked = cache.status().checkedAt();
        byte[] original = Files.readAllBytes(new File(directory, "catalog.bin").toPath());
        now[0] += ModelsDevCache.REFRESH_MS;
        cache.refresh();
        assertEquals(checked, cache.status().checkedAt()); assertEquals(1, cache.status().modelCount());
        assertArrayEquals(original, Files.readAllBytes(new File(directory, "catalog.bin").toPath()));
        cache.refresh(); now[0] += ModelsDevCache.RETRY_MS - 1; cache.refresh();
        assertEquals(2, calls.get());
        now[0]++; cache.refresh(); assertEquals(3, calls.get());
        assertEquals(now[0], cache.status().checkedAt());
        assertFalse(events.toString().contains("private server response"));
    }

    @Test public void malformedDownloadCannotReplaceValidSnapshotOrPublishPartialData() throws Exception {
        File directory = temporary.newFolder();
        engine(directory, (etag, modified) -> response(200, CATALOG, "v1")).refresh();
        byte[] original = Files.readAllBytes(new File(directory, "catalog.bin").toPath());
        now[0] += ModelsDevCache.REFRESH_MS;
        ModelsDevCache.Engine cache = engine(directory, (etag, modified) -> response(200, "{broken", "v2"));
        cache.refresh();
        assertEquals(1, cache.status().modelCount());
        assertArrayEquals(original, Files.readAllBytes(new File(directory, "catalog.bin").toPath()));
        assertNotNull(ModelsDevCatalog.resolve("https://api.openai.com/v1", "future", "chat"));
    }

    @Test public void corruptDiskIsRecoveredByUnconditionalFetchAndUnexpected304CannotBecomeCache() throws Exception {
        File directory = temporary.newFolder();
        Files.writeString(new File(directory, "catalog.bin").toPath(), "corrupt");
        ModelsDevCache.Engine failed = engine(directory, (etag, modified) -> {
            assertEquals("", etag); return response(304, "", "v1");
        });
        failed.refresh(); assertEquals(0, failed.status().modelCount()); assertEquals(0, failed.status().checkedAt());
        assertTrue(events.stream().anyMatch(event -> event.startsWith("cache_invalid:")));
        now[0] += ModelsDevCache.RETRY_MS;
        ModelsDevCache.Engine recovered = engine(directory, (etag, modified) -> {
            assertEquals("", etag); return response(200, CATALOG, "v2");
        });
        recovered.refresh(); assertEquals(1, recovered.status().modelCount());
    }

    @Test public void rejectsDeclaredAndStreamingOversizedBodiesAndInvalidUtf8() throws Exception {
        File directory = temporary.newFolder();
        ModelsDevCache.Engine declared = engine(directory, (etag, modified) -> new ModelsDevCache.Response(
            200, "", "", ModelsDevCache.MAX_BYTES + 1L, new ByteArrayInputStream(new byte[0])));
        declared.refresh(); assertEquals(0, declared.status().modelCount());
        ModelsDevCache.Engine streaming = engine(directory, (etag, modified) -> new ModelsDevCache.Response(
            200, "", "", -1, new InputStream() {
                int remaining = ModelsDevCache.MAX_BYTES + 1;
                @Override public int read() { if (remaining == 0) return -1; remaining--; return ' '; }
                @Override public int read(byte[] buffer, int offset, int length) {
                    if (remaining == 0) return -1;
                    int count = Math.min(length, remaining); remaining -= count; return count;
                }
            }));
        streaming.refresh(); assertEquals(0, streaming.status().modelCount());
        ModelsDevCache.Engine malformed = engine(directory, (etag, modified) -> new ModelsDevCache.Response(
            200, "", "", 2, new ByteArrayInputStream(new byte[]{(byte) 0xc3, (byte) 0x28})));
        malformed.refresh(); assertEquals(0, malformed.status().modelCount());
        assertFalse(new File(directory, "catalog.bin").exists());
    }

    @Test public void diskWriteFailureKeepsCatalogUnpublished() throws Exception {
        File blocked = temporary.newFile();
        ModelsDevCache.Engine cache = engine(blocked, (etag, modified) -> response(200, CATALOG, "v1"));
        cache.refresh();
        assertEquals(0, cache.status().modelCount());
        assertNull(ModelsDevCatalog.resolve("https://api.openai.com/v1", "future", "chat"));
    }

    @Test public void rejectsHeaderInjectionAndRefreshesAfterClockMovesBackwards() throws Exception {
        File directory = temporary.newFolder();
        AtomicInteger calls = new AtomicInteger();
        ModelsDevCache.Engine cache = engine(directory, (etag, modified) -> {
            if (calls.getAndIncrement() == 0) return response(200, CATALOG, "unsafe\r\nheader");
            assertEquals("", etag); return response(304, "", "safe");
        });
        cache.refresh(); now[0] -= ModelsDevCache.REFRESH_MS;
        cache.refresh(); assertEquals(2, calls.get()); assertEquals(now[0], cache.status().checkedAt());
    }

    @Test public void concurrentRequestsAreDeduplicatedWithoutBlockingCaller() throws Exception {
        File directory = temporary.newFolder();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ModelsDevCache.Engine cache = engine(directory, (etag, modified) -> {
            calls.incrementAndGet(); entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for fixture");
            return response(200, CATALOG, "v1");
        });
        try {
            cache.request(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 20; i++) cache.request();
            assertEquals(1, calls.get()); assertTrue(cache.status().refreshing());
        } finally { release.countDown(); }
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cache.status().refreshing() && System.nanoTime() < end) Thread.sleep(10);
        assertFalse(cache.status().refreshing()); assertEquals(1, cache.status().modelCount());
    }
}
