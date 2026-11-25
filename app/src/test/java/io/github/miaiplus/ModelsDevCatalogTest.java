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
import org.json.JSONArray;
import org.json.JSONObject;
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
public class ModelsDevCatalogTest {
    private static final String CATALOG = "{\"openai\":{\"models\":{\"future\":{\"reasoning\":true,"
        + "\"reasoning_options\":[{\"type\":\"effort\",\"values\":[\"low\",\"high\"]}]}}}}";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final long[] now = {1_700_000_000_000L};
    private final List<String> events = new ArrayList<>();

    @After public void clearPublishedCatalog() { ModelsDevCatalog.publish(null); }

    private JSONObject model(String... efforts) throws Exception {
        return new JSONObject().put("reasoning", true).put("reasoning_options", new JSONArray()
            .put(new JSONObject().put("type", "effort").put("values", new JSONArray(List.of(efforts)))));
    }
    private JSONObject provider(String endpoint, String npm, String id, JSONObject model) throws Exception {
        return new JSONObject().put("api", endpoint).put("npm", npm).put("models", new JSONObject().put(id, model));
    }
    private ModelsDevCatalog catalog(JSONObject source) throws Exception { return ModelsDevCatalog.parse(source.toString()); }

    private ModelsDevCache.Engine engine(File directory, ModelsDevCache.Transport transport) {
        return new ModelsDevCache.Engine(directory, transport, () -> now[0],
            (event, models, providers, age, detail) -> events.add(event + ":" + detail));
    }

    private static ModelsDevCache.Response response(int code, String body, String etag) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new ModelsDevCache.Response(code, etag, "Thu, 01 Jan 2026 00:00:00 GMT", bytes.length,
            new ByteArrayInputStream(bytes));
    }

    @Test public void endpointContractsOverrideOfficialModelsWithoutCrossProviderInheritanceAndFailClosedOnDuplicates() throws Exception {
        ModelsDevCatalog catalog = catalog(new JSONObject()
            .put("openai", provider("https://api.openai.com/v1", "@ai-sdk/openai", "future", model("none", "high")))
            .put("gateway", provider("https://gateway.test/proxy/v1", "@ai-sdk/openai-compatible", "future", model("low", "max"))));
        assertEquals(List.of("default", "low", "max"), catalog.find("https://gateway.test/proxy/v1/", "future", "chat").choices());
        assertEquals(List.of("default", "none", "high"), catalog.find("https://api.openai.com", "future", "chat").choices());
        assertEquals(List.of("default", "none", "high"), catalog.find("https://other.test/v1", "future", "chat").choices());
        assertNull(catalog.find("https://gateway.test/proxy/v1", "openai/future", "chat"));
        assertEquals(2, catalog.providerCount()); assertEquals(2, catalog.modelCount());
        ModelsDevCatalog separate = catalog(new JSONObject()
            .put("openai", provider("https://api.openai.com/v1", "@ai-sdk/openai", "future", model("high")))
            .put("gateway", provider("https://gateway.test/v1", "@ai-sdk/openai-compatible", "other", model("low"))));
        assertNull(separate.find("https://gateway.test", "future", "chat"));
        ModelsDevCatalog duplicate = catalog(new JSONObject()
            .put("one", provider("https://shared.test/custom/v1", "@ai-sdk/openai-compatible", "future", model("low")))
            .put("two", provider("https://shared.test/custom/v1", "@ai-sdk/openai-compatible", "future", model("high"))));
        assertEquals(List.of("default"), duplicate.find("https://shared.test/custom", "future", "chat").choices());
        assertNull(duplicate.find("https://shared.test/v1", "future", "chat"));
        assertNull(duplicate.find("http://shared.test/custom/v1", "future", "chat"));
    }

    @Test public void unknownGatewayUsesExactOfficialIdsAndExplicitNamespacesOnly() throws Exception {
        ModelsDevCatalog catalog = catalog(new JSONObject().put("openai", provider("", "@ai-sdk/openai", "new-model", model("high"))));
        assertEquals(List.of("default", "high"), catalog.find("https://proxy.test", "openai/new-model", "responses").choices());
        assertNull(catalog.find("https://proxy.test", "NEW-MODEL", "responses"));
        assertNull(catalog.find("https://proxy.test", "new-model-2026-11-14", "responses"));
        assertNull(catalog.find("https://proxy.test", "someone/new-model", "responses"));
    }

    @Test public void unsupportedShapesAdaptersAndModelTypesResolveToDefaultExceptTheSelectedShape() throws Exception {
        ModelsDevCatalog catalog = catalog(new JSONObject()
            .put("openrouter", provider("https://openrouter.ai/api/v1", "@openrouter/ai-sdk-provider", "gpt-5.1", model("high")))
            .put("unknown", provider("https://unknown.test/v1", "@ai-sdk/openai-compatible", "gpt-5.1",
                model("high").put("provider", new JSONObject().put("shape", "future-protocol"))))
            .put("openai", provider("https://api.openai.com/v1", "@ai-sdk/openai",
                "future-audio", model("high").put("type", "realtime"))));
        assertEquals(List.of("default"), catalog.find("https://openrouter.ai/api/v1", "gpt-5.1", "chat").choices());
        assertEquals(List.of("default"), catalog.find("https://unknown.test/v1", "gpt-5.1", "chat").choices());
        assertEquals(List.of("default"), catalog.find("https://api.openai.com/v1", "future-audio", "responses").choices());
        JSONObject overridden = model("high").put("provider", new JSONObject().put("api", "https://gateway.test/anthropic/v1")
            .put("npm", "@ai-sdk/anthropic"));
        ModelsDevCatalog overrideCatalog = catalog(new JSONObject().put("gateway",
            provider("https://gateway.test/openai/v1", "@ai-sdk/openai-compatible", "qwen-future", overridden)));
        assertEquals(List.of("default"), overrideCatalog.find("https://gateway.test/openai/v1", "qwen-future", "chat").choices());
        assertEquals(List.of("default"), overrideCatalog.find("https://gateway.test/anthropic/v1", "qwen-future", "chat").choices());
        ModelsDevCatalog shaped = catalog(new JSONObject().put("gateway", provider("https://gateway.test/v1", "@ai-sdk/openai",
            "future", model("high").put("provider", new JSONObject().put("shape", "responses")))));
        assertEquals(List.of("default"), shaped.find("https://gateway.test/v1", "future", "chat").choices());
        assertEquals(List.of("default", "high"), shaped.find("https://gateway.test/v1", "future", "responses").choices());
    }

    @Test public void booleanReasoningNeverInventsEffortsAndUnknownValuesAreIgnored() throws Exception {
        JSONObject models = new JSONObject().put("fixed", new JSONObject().put("reasoning", true))
            .put("disabled", model("high").put("reasoning", false))
            .put("future", model("unknown", "default", "max", "high", "high"));
        ModelsDevCatalog catalog = catalog(new JSONObject().put("gateway", new JSONObject().put("api", "https://gateway.test/v1")
            .put("npm", "@ai-sdk/openai-compatible").put("models", models)));
        assertEquals(List.of("default"), catalog.find("https://gateway.test/v1", "fixed", "chat").choices());
        assertEquals(List.of("default"), catalog.find("https://gateway.test/v1", "disabled", "chat").choices());
        assertEquals(List.of("default", "high", "max"), catalog.find("https://gateway.test/v1", "future", "chat").choices());
    }

    @Test public void qwenAndDeepseekTogglesOnlyAddSwitchesInChat() throws Exception {
        JSONObject toggle = new JSONObject().put("reasoning", true).put("reasoning_options",
            new JSONArray().put(new JSONObject().put("type", "toggle")).put(new JSONObject().put("type", "budget_tokens")));
        ModelsDevCatalog catalog = catalog(new JSONObject().put("alibaba", provider("https://qwen.test/v1",
            "@ai-sdk/openai-compatible", "qwen-new", toggle)));
        ReasoningPolicy.Plan chat = catalog.find("https://proxy.test", "qwen/qwen-new", "chat");
        assertEquals(List.of("default", "off", "on"), chat.choices());
        JSONObject body = new JSONObject(); chat.apply(body, "qwen-new", "chat", "off");
        assertFalse(body.getBoolean("enable_thinking"));
        assertEquals(List.of("default"), catalog.find("https://proxy.test", "qwen-new", "responses").choices());
        JSONObject deepseekModel = model("low", "max");
        deepseekModel.getJSONArray("reasoning_options").put(new JSONObject().put("type", "toggle"));
        ModelsDevCatalog deepseek = catalog(new JSONObject().put("deepseek", provider("https://api.deepseek.com",
            "@ai-sdk/openai-compatible", "deepseek-future", deepseekModel)));
        ReasoningPolicy.Plan deepseekChat = deepseek.find("https://api.deepseek.com/v1", "deepseek-future", "chat");
        assertEquals(List.of("default", "none", "low", "max"), deepseekChat.choices());
        JSONObject disabled = new JSONObject(); deepseekChat.apply(disabled, "deepseek-future", "chat", "none");
        assertEquals("disabled", disabled.getJSONObject("thinking").getString("type"));
        assertEquals(List.of("default", "low", "max"), deepseek.find("https://api.deepseek.com/v1", "deepseek-future", "responses").choices());
    }

    @Test public void malformedOrEmptySnapshotsAreRejected() throws Exception {
        for (String source : List.of("{}", "[]", "{} trailing", "{\"provider\":{}}", "{\"provider\":{\"models\":{}}}")) {
            assertThrows(Exception.class, () -> ModelsDevCatalog.parse(source));
        }
        JSONObject mismatched = model("high").put("id", "other");
        assertThrows(Exception.class, () -> catalog(new JSONObject().put("openai", provider("https://api.openai.com/v1",
            "@ai-sdk/openai", "future", mismatched))));
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

    @Test public void malformedOversizedOrUnwritableDownloadsNeverReplaceOrPublishSnapshot() throws Exception {
        File directory = temporary.newFolder();
        engine(directory, (etag, modified) -> response(200, CATALOG, "v1")).refresh();
        byte[] original = Files.readAllBytes(new File(directory, "catalog.bin").toPath());
        now[0] += ModelsDevCache.REFRESH_MS;
        ModelsDevCache.Engine cache = engine(directory, (etag, modified) -> response(200, "{broken", "v2"));
        cache.refresh();
        assertEquals(1, cache.status().modelCount());
        assertArrayEquals(original, Files.readAllBytes(new File(directory, "catalog.bin").toPath()));
        assertNotNull(ModelsDevCatalog.resolve("https://api.openai.com/v1", "future", "chat"));
        File empty = temporary.newFolder();
        ModelsDevCache.Engine declared = engine(empty, (etag, modified) -> new ModelsDevCache.Response(
            200, "", "", ModelsDevCache.MAX_BYTES + 1L, new ByteArrayInputStream(new byte[0])));
        declared.refresh(); assertEquals(0, declared.status().modelCount());
        ModelsDevCache.Engine streaming = engine(empty, (etag, modified) -> new ModelsDevCache.Response(
            200, "", "", -1, new InputStream() {
                int remaining = ModelsDevCache.MAX_BYTES + 1;
                @Override public int read() { if (remaining == 0) return -1; remaining--; return ' '; }
                @Override public int read(byte[] buffer, int offset, int length) {
                    if (remaining == 0) return -1;
                    int count = Math.min(length, remaining); remaining -= count; return count;
                }
            }));
        streaming.refresh(); assertEquals(0, streaming.status().modelCount());
        ModelsDevCache.Engine malformed = engine(empty, (etag, modified) -> new ModelsDevCache.Response(
            200, "", "", 2, new ByteArrayInputStream(new byte[]{(byte) 0xc3, (byte) 0x28})));
        malformed.refresh(); assertEquals(0, malformed.status().modelCount());
        assertFalse(new File(empty, "catalog.bin").exists());
        ModelsDevCatalog.publish(null);
        File blocked = temporary.newFile();
        ModelsDevCache.Engine unwritable = engine(blocked, (etag, modified) -> response(200, CATALOG, "v1"));
        unwritable.refresh();
        assertEquals(0, unwritable.status().modelCount());
        assertNull(ModelsDevCatalog.resolve("https://api.openai.com/v1", "future", "chat"));
    }

    @Test public void corruptDiskAndUnsafeValidatorsForceUnconditionalFetch() throws Exception {
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
        File unsafe = temporary.newFolder();
        AtomicInteger calls = new AtomicInteger();
        ModelsDevCache.Engine cache = engine(unsafe, (etag, modified) -> {
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
