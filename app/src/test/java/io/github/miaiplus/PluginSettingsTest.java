package io.github.miaiplus;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PluginSettingsTest {
    private static final String VERSION = PluginSettings.MCP_PROTOCOL_VERSION;
    private static final String URL = "https://mcp.example.com/tools/";

    private static final class Storage {
        final Map<String, Object> values = new HashMap<>();
        int commits;
        boolean rejectCommit;
        Runnable afterSnapshot;
        final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getAll" -> {
                    Map<String, Object> snapshot = new HashMap<>(values);
                    if (afterSnapshot != null) {
                        Runnable callback = afterSnapshot; afterSnapshot = null; callback.run();
                    }
                    yield snapshot;
                }
                case "edit" -> editor();
                default -> throw new AssertionError(method.getName());
            });

        SharedPreferences.Editor editor() {
            Map<String, Object> pending = new HashMap<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "putString" -> { pending.put((String) args[0], args[1]); yield proxy; }
                    case "commit" -> {
                        commits++;
                        if (!rejectCommit) values.putAll(pending);
                        yield !rejectCommit;
                    }
                    default -> throw new AssertionError(method.getName());
                });
        }

        PluginSettings.Entry add(String label) {
            var next = PluginSettings.saveMcp(prefs, "", label, URL, "fixture-token", VERSION);
            return next.entries().get(next.entries().size() - 1);
        }
    }

    private static PluginToolSpec tool(String id, String source) {
        return new PluginToolSpec(id, "mcp", PluginToolSpec.mcpName(id, source), source,
            source, "Fixture tool", "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}", 60000);
    }

    private static List<PluginToolSpec> tools(String id, int count, int descriptionChars) {
        ArrayList<PluginToolSpec> tools = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String source = "lookup_" + i;
            tools.add(new PluginToolSpec(id, "mcp", PluginToolSpec.mcpName(id, source), source,
                source, "x".repeat(descriptionChars), "{\"type\":\"object\"}", 60000));
        }
        return tools;
    }

    private static void discover(Storage storage, PluginSettings.Entry entry, List<PluginToolSpec> tools) {
        PluginSettings.updateTools(storage.prefs, entry.id(), PluginSettings.load(storage.prefs).revision(), tools);
    }

    @Test public void defaultsPreserveBuiltinBehaviorAndHaveStableRevisionWithoutWriting() {
        Storage storage = new Storage();
        storage.values.put("enabled", true);
        storage.values.put("endpoint", "https://model.example.com/v1");
        var first = PluginSettings.load(storage.prefs);
        var second = PluginSettings.load(storage.prefs);
        assertEquals(first, second);
        assertEquals(2, first.entries().size());
        assertTrue(first.find(PluginSettings.FOREGROUND_ID).enabled());
        assertTrue(first.find(PluginSettings.BACKGROUND_ID).enabled());
        assertEquals(0, storage.commits);
        assertFalse(storage.values.containsKey(PluginSettings.PREFERENCE_KEY));
        assertEquals("https://model.example.com/v1", storage.values.get("endpoint"));
    }

    @Test public void savesPrivateSnapshotOnceAndKeepsNewMcpDisabledUntilUserEnablesIt() {
        Storage storage = new Storage();
        PluginSettings.Entry entry = storage.add(" 服务 A ");
        assertEquals("服务 A", entry.label());
        assertFalse(entry.enabled());
        assertTrue(entry.tools().isEmpty());
        assertEquals(URL, entry.endpoint());
        assertEquals("fixture-token", entry.bearerToken());
        assertEquals(1, storage.commits);
        assertEquals(entry, PluginSettings.load(storage.prefs).find(entry.id()));
        assertEquals(1, storage.values.size());
        assertFalse(entry.toString().contains("fixture-token"));
        assertFalse(entry.toString().contains(URL));
    }

    @Test public void olderProtocolIsExplicitlyPersistedWhileDefaultAndUnknownRejectionRemain() {
        assertEquals("2025-11-25", PluginSettings.MCP_PROTOCOL_VERSION);
        assertEquals(List.of("2025-11-25", "2025-06-18"), PluginSettings.MCP_PROTOCOL_VERSIONS);
        assertThrows(UnsupportedOperationException.class, () -> PluginSettings.MCP_PROTOCOL_VERSIONS.clear());
        assertFalse(PluginSettings.supportsMcpProtocol(null));
        assertFalse(PluginSettings.supportsMcpProtocol("2025-03-26"));
        Storage storage = new Storage();
        var saved = PluginSettings.saveMcp(storage.prefs, "", "Older MCP", URL, "", "2025-06-18");
        var entry = saved.entries().get(saved.entries().size() - 1);
        assertEquals("2025-06-18", PluginSettings.load(storage.prefs).find(entry.id()).protocolVersion());
        assertFalse(entry.enabled());
        Map<String, Object> untouched = new HashMap<>(storage.values);
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.saveMcp(storage.prefs,
            entry.id(), "Older MCP", URL, "", "2025-03-26"));
        assertEquals(untouched, storage.values);
    }

    @Test public void explicitProtocolChangeInvalidatesDiscoveredToolsAndInflightRevision() {
        Storage storage = new Storage();
        var entry = storage.add("A");
        discover(storage, entry, List.of(tool(entry.id(), "shell")));
        PluginSettings.setEnabled(storage.prefs, entry.id(), true);
        var before = PluginSettings.load(storage.prefs);
        var changed = PluginSettings.saveMcp(storage.prefs, entry.id(), entry.label(), URL,
            "fixture-token", "2025-06-18");
        assertNotEquals(before.revision(), changed.revision());
        assertTrue(changed.find(entry.id()).enabled());
        assertTrue(changed.find(entry.id()).tools().isEmpty());
        assertEquals("2025-06-18", PluginSettings.load(storage.prefs).find(entry.id()).protocolVersion());
        assertThrows(IllegalStateException.class, () -> PluginSettings.updateTools(storage.prefs,
            entry.id(), before.revision(), List.of(tool(entry.id(), "shell"))));
    }

    @Test public void builtinToggleIsIndependentAndCannotBeDeletedOrReplaced() {
        Storage storage = new Storage();
        var before = PluginSettings.load(storage.prefs);
        var changed = PluginSettings.setEnabled(storage.prefs, PluginSettings.FOREGROUND_ID, false);
        assertFalse(changed.find(PluginSettings.FOREGROUND_ID).enabled());
        assertTrue(changed.find(PluginSettings.BACKGROUND_ID).enabled());
        assertNotEquals(before.revision(), changed.revision());
        int commits = storage.commits;
        assertEquals(changed, PluginSettings.setEnabled(storage.prefs, PluginSettings.FOREGROUND_ID, false));
        assertEquals(commits, storage.commits);
        Map<String, Object> untouched = new HashMap<>(storage.values);
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.deleteMcp(storage.prefs, PluginSettings.FOREGROUND_ID));
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.saveMcp(storage.prefs,
            PluginSettings.FOREGROUND_ID, "Replacement", URL, "", VERSION));
        assertEquals(untouched, storage.values);
    }

    @Test public void discoversToolsOnlyIntoExactSnapshotAndReturnsImmutableLists() {
        Storage storage = new Storage();
        var entry = storage.add("A");
        var snapshot = PluginSettings.load(storage.prefs);
        ArrayList<PluginToolSpec> tools = new ArrayList<>(List.of(tool(entry.id(), "lookup")));
        var discovered = PluginSettings.updateTools(storage.prefs, entry.id(), snapshot.revision(), tools);
        tools.clear();
        assertEquals(1, discovered.find(entry.id()).tools().size());
        assertEquals(discovered, PluginSettings.load(storage.prefs));
        assertThrows(UnsupportedOperationException.class, () -> discovered.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> discovered.find(entry.id()).tools().clear());
        Map<String, Object> untouched = new HashMap<>(storage.values);
        assertThrows(IllegalStateException.class, () -> PluginSettings.updateTools(storage.prefs,
            entry.id(), snapshot.revision(), List.of()));
        assertEquals(untouched, storage.values);
    }

    @Test public void editsInvalidateDiscoveryOnlyWhenConnectionIdentityChanges() {
        Storage storage = new Storage();
        var entry = storage.add("A");
        var loaded = PluginSettings.load(storage.prefs);
        PluginSettings.updateTools(storage.prefs, entry.id(), loaded.revision(), List.of(tool(entry.id(), "lookup")));
        PluginSettings.setEnabled(storage.prefs, entry.id(), true);
        var renamed = PluginSettings.saveMcp(storage.prefs, entry.id(), "Renamed", URL, "fixture-token", VERSION);
        assertEquals(1, renamed.find(entry.id()).tools().size());
        assertTrue(renamed.find(entry.id()).enabled());
        var rotated = PluginSettings.saveMcp(storage.prefs, entry.id(), "Renamed", URL, "new-fixture-token", VERSION);
        assertTrue(rotated.find(entry.id()).tools().isEmpty());
        PluginSettings.updateTools(storage.prefs, entry.id(), rotated.revision(), List.of(tool(entry.id(), "lookup")));
        var moved = PluginSettings.saveMcp(storage.prefs, entry.id(), "Renamed", "https://other.example.com/mcp", "new-fixture-token", VERSION);
        assertTrue(moved.find(entry.id()).tools().isEmpty());
    }

    @Test public void deletionDropsCredentialsAndInvalidatesInflightDiscovery() {
        Storage storage = new Storage();
        var entry = storage.add("A");
        String revision = PluginSettings.load(storage.prefs).revision();
        var deleted = PluginSettings.deleteMcp(storage.prefs, entry.id());
        assertEquals(2, deleted.entries().size());
        assertFalse(storage.values.toString().contains("fixture-token"));
        assertThrows(IllegalArgumentException.class, () -> deleted.find(entry.id()));
        assertThrows(IllegalStateException.class, () -> PluginSettings.updateTools(storage.prefs,
            entry.id(), revision, List.of(tool(entry.id(), "lookup"))));
    }

    @Test public void namesAreStableBoundedAndNamespacedAcrossPluginsAndSources() {
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        String name = PluginToolSpec.mcpName(first, "搜索 files/with spaces");
        assertEquals(62, name.length());
        assertTrue(name.matches("[A-Za-z0-9_-]+"));
        assertEquals(name, PluginToolSpec.mcpName(first, "搜索 files/with spaces"));
        assertNotEquals(name, PluginToolSpec.mcpName(second, "搜索 files/with spaces"));
        assertNotEquals(name, PluginToolSpec.mcpName(first, "other"));
        assertThrows(IllegalArgumentException.class, () -> PluginToolSpec.mcpName(PluginSettings.FOREGROUND_ID, "lookup"));
        assertThrows(IllegalArgumentException.class, () -> PluginToolSpec.mcpName(first, "bad\nname"));
        PluginToolSpec spec = tool(first, "lookup");
        assertEquals(spec, PluginToolSpec.fromJson(spec.toJson()));
        assertFalse(spec.toJson().has("endpoint"));
        assertFalse(spec.toJson().has("bearerToken"));
    }

    @Test public void rejectsDuplicateForeignOrForgedToolsWithoutPartialWrites() {
        Storage storage = new Storage();
        var entry = storage.add("A");
        var snapshot = PluginSettings.load(storage.prefs);
        var spec = tool(entry.id(), "lookup");
        var foreign = tool(UUID.randomUUID().toString(), "lookup");
        Map<String, Object> untouched = new HashMap<>(storage.values);
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.updateTools(storage.prefs,
            entry.id(), snapshot.revision(), List.of(spec, spec)));
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.updateTools(storage.prefs,
            entry.id(), snapshot.revision(), List.of(foreign)));
        assertThrows(IllegalArgumentException.class, () -> new PluginToolSpec(entry.id(), "mcp",
            PhoneUseRouting.FOREGROUND_TOOL, "lookup", "lookup", "", "{\"type\":\"object\"}", 60000));
        assertEquals(untouched, storage.values);
    }

    @Test public void limitsToolCatalogBeforeAnyPersistence() {
        Storage storage = new Storage();
        var entry = storage.add("A");
        var snapshot = PluginSettings.load(storage.prefs);
        ArrayList<PluginToolSpec> tools = new ArrayList<>();
        for (int i = 0; i <= PluginToolSpec.MAX_TOOLS_PER_PLUGIN; i++) tools.add(tool(entry.id(), "lookup_" + i));
        Map<String, Object> untouched = new HashMap<>(storage.values);
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.updateTools(storage.prefs,
            entry.id(), snapshot.revision(), tools));
        assertEquals(untouched, storage.values);
    }

    @Test public void enablingCombinedCountOverflowKeepsPreviousRevisionAndStorage() {
        Storage storage = new Storage();
        var first = storage.add("First");
        var second = storage.add("Second");
        discover(storage, first, tools(first.id(), 64, 0));
        discover(storage, second, tools(second.id(), 64, 0));
        PluginSettings.setEnabled(storage.prefs, first.id(), true);
        var before = PluginSettings.load(storage.prefs);
        Map<String, Object> untouched = new HashMap<>(storage.values);
        int commits = storage.commits;
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.setEnabled(storage.prefs, second.id(), true));
        assertEquals(before, PluginSettings.load(storage.prefs));
        assertEquals(untouched, storage.values);
        assertEquals(commits, storage.commits);
        assertFalse(PluginSettings.load(storage.prefs).find(second.id()).enabled());
    }

    @Test public void enablingCombinedCharacterOverflowKeepsPreviousRevisionAndStorage() {
        Storage storage = new Storage();
        var first = storage.add("First");
        var second = storage.add("Second");
        discover(storage, first, tools(first.id(), 10, 6000));
        discover(storage, second, tools(second.id(), 10, 6000));
        PluginSettings.setEnabled(storage.prefs, first.id(), true);
        var before = PluginSettings.load(storage.prefs);
        Map<String, Object> untouched = new HashMap<>(storage.values);
        int commits = storage.commits;
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.setEnabled(storage.prefs, second.id(), true));
        assertEquals(before, PluginSettings.load(storage.prefs));
        assertEquals(untouched, storage.values);
        assertEquals(commits, storage.commits);
    }

    @Test public void updatingEnabledManifestRechecksFinalCatalogBeforeWriting() {
        Storage storage = new Storage();
        var first = storage.add("First");
        var second = storage.add("Second");
        discover(storage, first, tools(first.id(), 64, 0));
        discover(storage, second, tools(second.id(), 1, 0));
        PluginSettings.setEnabled(storage.prefs, first.id(), true);
        PluginSettings.setEnabled(storage.prefs, second.id(), true);
        var before = PluginSettings.load(storage.prefs);
        Map<String, Object> untouched = new HashMap<>(storage.values);
        int commits = storage.commits;
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.updateTools(storage.prefs,
            second.id(), before.revision(), tools(second.id(), 64, 0)));
        assertEquals(before, PluginSettings.load(storage.prefs));
        assertEquals(untouched, storage.values);
        assertEquals(commits, storage.commits);
    }

    @Test public void historicalOverflowCanStillBeLoadedAndRecoveredByDisablingAPlugin() throws Exception {
        Storage storage = new Storage();
        var first = storage.add("First");
        var second = storage.add("Second");
        discover(storage, first, tools(first.id(), 64, 0));
        discover(storage, second, tools(second.id(), 64, 0));
        PluginSettings.setEnabled(storage.prefs, first.id(), true);
        // Simulate an older writer that saved both manifests as active without the total budget check.
        JSONObject root = new JSONObject((String) storage.values.get(PluginSettings.PREFERENCE_KEY));
        JSONArray plugins = root.getJSONArray("plugins");
        for (int i = 0; i < plugins.length(); i++) {
            JSONObject plugin = plugins.getJSONObject(i);
            if (second.id().equals(plugin.getString("id"))) plugin.put("enabled", true);
        }
        storage.values.put(PluginSettings.PREFERENCE_KEY, root.toString());
        var historical = PluginSettings.load(storage.prefs);
        assertTrue(historical.find(first.id()).enabled());
        assertTrue(historical.find(second.id()).enabled());
        var recovered = PluginSettings.setEnabled(storage.prefs, second.id(), false);
        assertTrue(recovered.find(first.id()).enabled());
        assertFalse(recovered.find(second.id()).enabled());
        assertNotEquals(historical.revision(), recovered.revision());
        assertEquals(recovered, PluginSettings.load(storage.prefs));
        assertEquals(64, recovered.find(second.id()).tools().size());
    }

    @Test public void loadKeepsOneAtomicSnapshotDuringConcurrentPreferenceChange() {
        Storage storage = new Storage();
        var entry = storage.add("A");
        var before = PluginSettings.load(storage.prefs);
        storage.afterSnapshot = () -> PluginSettings.saveMcp(storage.prefs, entry.id(), "Changed",
            "https://other.example.com/mcp", "different-fixture-token", VERSION);
        var inflight = PluginSettings.load(storage.prefs);
        var next = PluginSettings.load(storage.prefs);
        assertEquals(before, inflight);
        assertNotEquals(inflight.revision(), next.revision());
        assertEquals("https://other.example.com/mcp", next.find(entry.id()).endpoint());
        assertEquals("different-fixture-token", next.find(entry.id()).bearerToken());
    }

    @Test public void rejectsInvalidSchemasLengthsAndToolTimeouts() {
        String id = UUID.randomUUID().toString(), name = PluginToolSpec.mcpName(id, "lookup");
        for (String schema : List.of("[]", "{}", "{\"type\":\"array\"}", "{\"type\":\"object\"} trailing",
            "{\"type\":\"object\",\"x\":" + "[".repeat(60) + "0" + "]".repeat(60) + "}")) {
            assertThrows(IllegalArgumentException.class, () -> new PluginToolSpec(id, "mcp", name,
                "lookup", "Lookup", "", schema, 60000));
        }
        assertThrows(IllegalArgumentException.class, () -> new PluginToolSpec(id, "mcp", name,
            "lookup", "Lookup", "x".repeat(8193), "{\"type\":\"object\"}", 60000));
        assertThrows(IllegalArgumentException.class, () -> new PluginToolSpec(id, "mcp", name,
            "lookup", "Lookup", "", "{\"type\":\"object\"}", 0));
        assertThrows(IllegalArgumentException.class, () -> new PluginToolSpec(id, "mcp", name,
            "lookup", "Lookup", "", "{\"type\":\"object\"}", 300001));
    }

    @Test public void endpointValidationPreservesExactPathAndRestrictsPlaintextAndUrlSecrets() {
        for (String url : List.of("https://mcp.example.com/path/", "http://localhost:18765/mcp/",
            "http://127.0.0.1:18765/mcp", "http://192.168.10.2:8000/mcp", "http://10.1.2.3/mcp"))
            assertEquals(url, PluginSettings.validateEndpoint(url));
        for (String invalid : List.of("", "stdio://server", "https://user:secret@example.com/mcp",
            "https://example.com/mcp?token=secret", "https://example.com/mcp#fragment", "http://public.example.com/mcp",
            "http://192.168.999.2/mcp", "http://lan-server.local/mcp", "https://example.com:65536/mcp"))
            assertThrows(IllegalArgumentException.class, () -> PluginSettings.validateEndpoint(invalid));
        Storage storage = new Storage();
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.saveMcp(storage.prefs,
            "", "A", URL, "token\r\nInjected: value", VERSION));
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.saveMcp(storage.prefs,
            "", "A", URL, "", "2026-07-28"));
        assertEquals(0, storage.commits);
    }

    @Test public void damagedStorageFailsClosedWithoutReplacingItWithDefaults() throws Exception {
        for (Object malformed : List.of("broken", 1, "{\"version\":1,\"revision\":\"bad\",\"plugins\":[]}")) {
            Storage storage = new Storage();
            storage.values.put(PluginSettings.PREFERENCE_KEY, malformed);
            Map<String, Object> untouched = new HashMap<>(storage.values);
            assertThrows(IllegalStateException.class, () -> PluginSettings.load(storage.prefs));
            assertThrows(IllegalStateException.class, () -> storage.add("Should not overwrite"));
            assertEquals(untouched, storage.values);
            assertEquals(0, storage.commits);
        }
        Storage storage = new Storage(); storage.add("A");
        JSONObject root = new JSONObject((String) storage.values.get(PluginSettings.PREFERENCE_KEY));
        JSONArray plugins = root.getJSONArray("plugins");
        plugins.put(plugins.getJSONObject(0));
        storage.values.put(PluginSettings.PREFERENCE_KEY, root.toString());
        assertThrows(IllegalStateException.class, () -> PluginSettings.load(storage.prefs));
    }

    @Test public void concurrentMutationsRetainBothPluginChanges() throws Exception {
        Storage storage = new Storage();
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (String id : List.of(PluginSettings.FOREGROUND_ID, PluginSettings.BACKGROUND_ID)) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try { start.await(); PluginSettings.setEnabled(storage.prefs, id, false); }
                catch (Throwable error) { failure.compareAndSet(null, error); }
            });
            threads.add(thread); thread.start();
        }
        ready.await(); start.countDown();
        for (Thread thread : threads) thread.join(5000);
        assertNull(failure.get());
        var result = PluginSettings.load(storage.prefs);
        assertFalse(result.find(PluginSettings.FOREGROUND_ID).enabled());
        assertFalse(result.find(PluginSettings.BACKGROUND_ID).enabled());
        assertEquals(2, storage.commits);
    }

    @Test public void unsuccessfulCommitIsReported() {
        Storage storage = new Storage(); storage.rejectCommit = true;
        assertThrows(IllegalStateException.class, () -> storage.add("A"));
        assertFalse(storage.values.containsKey(PluginSettings.PREFERENCE_KEY));
    }
}
