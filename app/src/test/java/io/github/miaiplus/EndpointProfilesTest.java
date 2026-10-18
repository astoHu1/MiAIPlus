package io.github.miaiplus;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class EndpointProfilesTest {
    private static final class Storage {
        final Map<String, String> values = new HashMap<>();
        Runnable afterSnapshot;
        final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getString" -> values.getOrDefault((String) args[0], (String) args[1]);
                case "getAll" -> {
                    Map<String, String> snapshot = new HashMap<>(values);
                    if (afterSnapshot != null) { Runnable callback = afterSnapshot; afterSnapshot = null; callback.run(); }
                    yield snapshot;
                }
                case "edit" -> editor();
                default -> throw new AssertionError(method.getName());
            });

        SharedPreferences.Editor editor() {
            Map<String, String> pending = new HashMap<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "putString" -> { pending.put((String) args[0], (String) args[1]); yield proxy; }
                    case "commit" -> { values.putAll(pending); yield true; }
                    default -> throw new AssertionError(method.getName());
                });
        }

        void save(EndpointProfiles.Profile profile, boolean delete) {
            var editor = prefs.edit();
            new EndpointProfiles(prefs).write(editor, profile, delete);
            editor.commit();
        }
    }

    private static EndpointProfiles.Profile service(String id, String name, String key, String model, String api) {
        return new EndpointProfiles.Profile(id, name, "https://" + id + ".example.com/v1", key, model, api);
    }

    @Test public void migratesExistingEndpointWithoutLosingCredentialsOrProtocol() {
        Storage storage = new Storage();
        storage.values.putAll(Map.of("endpoint", "https://legacy.example.com/v1", "apiKey", "fixture-key",
            "model", "custom-model", "api", "responses", "planner", "native"));
        EndpointProfiles profiles = new EndpointProfiles(storage.prefs);
        assertEquals(1, profiles.profiles.size());
        EndpointProfiles.Profile legacy = profiles.active();
        assertEquals("默认配置", legacy.name);
        assertEquals("fixture-key", legacy.apiKey);
        assertEquals("custom-model", legacy.model);
        assertEquals("responses", legacy.api);
        storage.save(legacy, false);
        assertEquals(legacy.encode(), new EndpointProfiles(storage.prefs).active().encode());
        assertEquals("native", storage.values.get("planner"));
    }

    @Test public void freshInstallKeepsApiFieldsEmptyAndAllowsNativeMode() {
        Storage storage = new Storage();
        var profile = new EndpointProfiles(storage.prefs).active().validated(false);
        assertEquals("", profile.endpoint); assertEquals("", profile.apiKey); assertEquals("", profile.model);
        assertEquals("auto", profile.api);
        storage.save(profile, false);
        assertEquals("", storage.values.get("endpoint"));
        assertEquals("", storage.values.get("apiKey"));
    }

    @Test public void switchingRestoresCompleteProfilesOnSameOrDifferentEndpointsWithoutChangingOtherSettings() {
        for (boolean sameEndpoint : new boolean[]{false, true}) {
            String label = sameEndpoint ? "same endpoint" : "different endpoints";
            Storage storage = new Storage();
            var first = service("a", "服务 A", "key-a", "alias-a", sameEndpoint ? "auto" : "responses");
            var second = new EndpointProfiles.Profile("b", "服务 B",
                sameEndpoint ? first.endpoint : "https://b.example.com/v1", "key-b", "alias-b",
                sameEndpoint ? "responses" : "chat");
            storage.values.put("globalModel", "unchanged");
            String reasoningKey = ReasoningPolicy.preferenceKey(first.endpoint, first.model, first.api);
            storage.values.put(reasoningKey, "low");
            storage.save(first, true);
            storage.save(second, false);
            assertEquals(label, 2, new EndpointProfiles(storage.prefs).profiles.size());
            storage.save(new EndpointProfiles(storage.prefs).find(first.id), false);
            var profiles = new EndpointProfiles(storage.prefs);
            assertEquals(label, first.id, profiles.activeId);
            assertEquals(label, first.endpoint, storage.values.get("endpoint"));
            assertEquals(label, first.apiKey, storage.values.get("apiKey"));
            assertEquals(label, first.model, storage.values.get("model"));
            assertEquals(label, first.api, storage.values.get("api"));
            assertEquals(label, second.encode(), profiles.find(second.id).encode());
            assertEquals(label, "unchanged", storage.values.get("globalModel"));
            assertEquals(label, "low", storage.values.get(reasoningKey));
        }
    }

    @Test public void renameUpdatesInPlaceAndDuplicateNamesLeaveStorageUntouched() {
        Storage storage = new Storage();
        var first = service("a", "服务 A", "key-a", "model-a", "auto");
        storage.save(first, true);
        storage.save(service("a", "重命名", "new-key", "new-model", "chat"), false);
        assertEquals(1, new EndpointProfiles(storage.prefs).profiles.size());
        assertEquals("new-key", storage.values.get("apiKey"));
        Map<String, String> before = new HashMap<>(storage.values);
        try {
            storage.save(service("b", "重命名", "key-b", "model-b", "auto"), false);
            fail("Duplicate accepted");
        } catch (IllegalArgumentException expected) { assertEquals(before, storage.values); }
    }

    @Test public void deletingActiveRemovesItsStoredKeyAndSelectsRemainingProfile() {
        Storage storage = new Storage();
        var first = service("a", "A", "unique-secret-a", "model-a", "auto");
        var second = service("b", "B", "key-b", "model-b", "responses");
        storage.save(first, true); storage.save(second, false); storage.save(first, false);
        storage.save(second, true);
        var profiles = new EndpointProfiles(storage.prefs);
        assertEquals(1, profiles.profiles.size()); assertEquals(second.id, profiles.activeId);
        assertFalse(storage.values.toString().contains("unique-secret-a"));
    }

    @Test public void switchDuringConfigReadKeepsUrlKeyModelAndProtocolFromOneSnapshot() {
        Storage storage = new Storage();
        var first = service("a", "A", "key-a", "model-a", "responses");
        var second = service("b", "B", "key-b", "model-b", "chat");
        storage.save(first, true); storage.save(second, false); storage.save(first, false);
        storage.afterSnapshot = () -> storage.save(second, false);
        Config inFlight = new Config(storage.prefs);
        assertEquals(first.endpoint, inFlight.endpoint); assertEquals(first.apiKey, inFlight.apiKey);
        assertEquals(first.model, inFlight.model); assertEquals(first.api, inFlight.api);
        Config next = new Config(storage.prefs);
        assertEquals(second.endpoint, next.endpoint); assertEquals(second.apiKey, next.apiKey);
        assertEquals(second.model, next.model); assertEquals(second.api, next.api);
    }

    @Test public void invalidProfilesCannotBeActivatedForExternalMode() {
        for (var profile : new EndpointProfiles.Profile[]{EndpointProfiles.Profile.blank("空配置"),
            new EndpointProfiles.Profile("id", " ", "https://example.com/v1", "", "model", "auto"),
            new EndpointProfiles.Profile("id", "A", "http://public.example.com/v1", "", "model", "auto"),
            new EndpointProfiles.Profile("id", "A", "https://example.com/v1", "", " ", "auto"),
            new EndpointProfiles.Profile("id", "A", "https://example.com/v1", "", "model", "unknown")}) {
            try { profile.validated(true); fail("Invalid profile accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void malformedStorageIsRejectedWithoutOverwritingCredentials() {
        Storage storage = new Storage();
        storage.values.put("endpointProfiles", "broken"); storage.values.put("apiKey", "preserved-key");
        Map<String, String> before = new HashMap<>(storage.values);
        try { new EndpointProfiles(storage.prefs); fail("Corrupt storage accepted"); }
        catch (IllegalStateException expected) { assertEquals(before, storage.values); }
    }
}
