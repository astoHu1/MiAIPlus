package io.github.miaiplus;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModelsDevCatalogTest {
    private JSONObject model(String... efforts) throws Exception {
        return new JSONObject().put("reasoning", true).put("reasoning_options", new JSONArray()
            .put(new JSONObject().put("type", "effort").put("values", new JSONArray(List.of(efforts)))));
    }
    private JSONObject provider(String endpoint, String npm, String id, JSONObject model) throws Exception {
        return new JSONObject().put("api", endpoint).put("npm", npm).put("models", new JSONObject().put(id, model));
    }
    private ModelsDevCatalog catalog(JSONObject source) throws Exception { return ModelsDevCatalog.parse(source.toString()); }

    @Test public void endpointContractOverridesOfficialModelWithoutCombiningEfforts() throws Exception {
        ModelsDevCatalog catalog = catalog(new JSONObject()
            .put("openai", provider("https://api.openai.com/v1", "@ai-sdk/openai", "future", model("none", "high")))
            .put("gateway", provider("https://gateway.test/proxy/v1", "@ai-sdk/openai-compatible", "future", model("low", "max"))));
        assertEquals(List.of("default", "low", "max"), catalog.find("https://gateway.test/proxy/v1/", "future", "chat").choices());
        assertEquals(List.of("default", "none", "high"), catalog.find("https://api.openai.com", "future", "chat").choices());
        assertEquals(List.of("default", "none", "high"), catalog.find("https://other.test/v1", "future", "chat").choices());
        assertNull(catalog.find("https://gateway.test/proxy/v1", "openai/future", "chat"));
        assertEquals(2, catalog.providerCount()); assertEquals(2, catalog.modelCount());
    }
    @Test public void unknownGatewayUsesExactOfficialIdsAndExplicitNamespacesOnly() throws Exception {
        ModelsDevCatalog catalog = catalog(new JSONObject().put("openai", provider("", "@ai-sdk/openai", "new-model", model("high"))));
        assertEquals(List.of("default", "high"), catalog.find("https://proxy.test", "openai/new-model", "responses").choices());
        assertNull(catalog.find("https://proxy.test", "NEW-MODEL", "responses"));
        assertNull(catalog.find("https://proxy.test", "new-model-2026-11-14", "responses"));
        assertNull(catalog.find("https://proxy.test", "someone/new-model", "responses"));
    }
    @Test public void knownProviderNeverInheritsMissingModelFromAnotherProvider() throws Exception {
        ModelsDevCatalog catalog = catalog(new JSONObject()
            .put("openai", provider("https://api.openai.com/v1", "@ai-sdk/openai", "future", model("high")))
            .put("gateway", provider("https://gateway.test/v1", "@ai-sdk/openai-compatible", "other", model("low"))));
        assertNull(catalog.find("https://gateway.test", "future", "chat"));
    }
    @Test public void modelOverrideRejectsParentProtocolAndUsesItsOwnEndpointAndAdapter() throws Exception {
        JSONObject overridden = model("high").put("provider", new JSONObject().put("api", "https://gateway.test/anthropic/v1")
            .put("npm", "@ai-sdk/anthropic"));
        ModelsDevCatalog catalog = catalog(new JSONObject().put("gateway",
            provider("https://gateway.test/openai/v1", "@ai-sdk/openai-compatible", "qwen-future", overridden)));
        assertEquals(List.of("default"), catalog.find("https://gateway.test/openai/v1", "qwen-future", "chat").choices());
        assertEquals(List.of("default"), catalog.find("https://gateway.test/anthropic/v1", "qwen-future", "chat").choices());
    }
    @Test public void unsupportedProviderAndUnknownShapeOverrideOldBuiltins() throws Exception {
        ModelsDevCatalog catalog = catalog(new JSONObject()
            .put("openrouter", provider("https://openrouter.ai/api/v1", "@openrouter/ai-sdk-provider", "gpt-5.1", model("high")))
            .put("unknown", provider("https://unknown.test/v1", "@ai-sdk/openai-compatible", "gpt-5.1",
                model("high").put("provider", new JSONObject().put("shape", "future-protocol")))));
        assertEquals(List.of("default"), catalog.find("https://openrouter.ai/api/v1", "gpt-5.1", "chat").choices());
        assertEquals(List.of("default"), catalog.find("https://unknown.test/v1", "gpt-5.1", "chat").choices());
    }
    @Test public void explicitShapeOnlyAppliesToSelectedProtocol() throws Exception {
        ModelsDevCatalog catalog = catalog(new JSONObject().put("gateway", provider("https://gateway.test/v1", "@ai-sdk/openai",
            "future", model("high").put("provider", new JSONObject().put("shape", "responses")))));
        assertEquals(List.of("default"), catalog.find("https://gateway.test/v1", "future", "chat").choices());
        assertEquals(List.of("default", "high"), catalog.find("https://gateway.test/v1", "future", "responses").choices());
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
    @Test public void qwenToggleOnlyUsesBooleanInChatAndDoesNotInventResponsesSwitch() throws Exception {
        JSONObject toggle = new JSONObject().put("reasoning", true).put("reasoning_options",
            new JSONArray().put(new JSONObject().put("type", "toggle")).put(new JSONObject().put("type", "budget_tokens")));
        ModelsDevCatalog catalog = catalog(new JSONObject().put("alibaba", provider("https://qwen.test/v1",
            "@ai-sdk/openai-compatible", "qwen-new", toggle)));
        ReasoningPolicy.Plan chat = catalog.find("https://proxy.test", "qwen/qwen-new", "chat");
        assertEquals(List.of("default", "off", "on"), chat.choices());
        JSONObject body = new JSONObject(); chat.apply(body, "qwen-new", "chat", "off");
        assertFalse(body.getBoolean("enable_thinking"));
        assertEquals(List.of("default"), catalog.find("https://proxy.test", "qwen-new", "responses").choices());
    }
    @Test public void deepseekAddsDisabledOnlyForChatToggleWithExplicitEfforts() throws Exception {
        JSONObject model = model("low", "max");
        model.getJSONArray("reasoning_options").put(new JSONObject().put("type", "toggle"));
        ModelsDevCatalog catalog = catalog(new JSONObject().put("deepseek", provider("https://api.deepseek.com",
            "@ai-sdk/openai-compatible", "deepseek-future", model)));
        ReasoningPolicy.Plan chat = catalog.find("https://api.deepseek.com/v1", "deepseek-future", "chat");
        assertEquals(List.of("default", "none", "low", "max"), chat.choices());
        JSONObject body = new JSONObject(); chat.apply(body, "deepseek-future", "chat", "none");
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
        assertEquals(List.of("default", "low", "max"), catalog.find("https://api.deepseek.com/v1", "deepseek-future", "responses").choices());
    }
    @Test public void duplicateEndpointContractsFailClosedAndPathIsNotDropped() throws Exception {
        ModelsDevCatalog catalog = catalog(new JSONObject()
            .put("one", provider("https://shared.test/custom/v1", "@ai-sdk/openai-compatible", "future", model("low")))
            .put("two", provider("https://shared.test/custom/v1", "@ai-sdk/openai-compatible", "future", model("high"))));
        assertEquals(List.of("default"), catalog.find("https://shared.test/custom", "future", "chat").choices());
        assertNull(catalog.find("https://shared.test/v1", "future", "chat"));
        assertNull(catalog.find("http://shared.test/custom/v1", "future", "chat"));
    }
    @Test public void nonChatModelCannotExposeChatEfforts() throws Exception {
        ModelsDevCatalog catalog = catalog(new JSONObject().put("openai", provider("https://api.openai.com/v1", "@ai-sdk/openai",
            "future-audio", model("high").put("type", "realtime"))));
        assertEquals(List.of("default"), catalog.find("https://api.openai.com/v1", "future-audio", "responses").choices());
    }
    @Test public void optionalLiveCatalogParsesEveryProviderAndModel() throws Exception {
        String fixture = System.getProperty("MIAIPLUS_MODELS_DEV_FIXTURE");
        if (fixture == null || fixture.isBlank()) fixture = System.getenv("MIAIPLUS_MODELS_DEV_FIXTURE");
        org.junit.Assume.assumeTrue("Set MIAIPLUS_MODELS_DEV_FIXTURE to an ignored live JSON snapshot",
            fixture != null && !fixture.isBlank());
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(fixture));
        JSONObject root = new JSONObject(source);
        ModelsDevCatalog catalog = ModelsDevCatalog.parse(source);
        int models = 0;
        for (java.util.Iterator<String> providers = root.keys(); providers.hasNext();)
            models += root.getJSONObject(providers.next()).getJSONObject("models").length();
        assertTrue(catalog.providerCount() > 0);
        assertTrue(catalog.modelCount() > 0);
        assertEquals(root.length(), catalog.providerCount());
        assertEquals(models, catalog.modelCount());
    }
    @Test public void malformedOrEmptySnapshotsAreRejected() throws Exception {
        for (String source : List.of("{}", "[]", "{} trailing", "{\"provider\":{}}", "{\"provider\":{\"models\":{}}}")) {
            assertThrows(Exception.class, () -> ModelsDevCatalog.parse(source));
        }
        JSONObject mismatched = model("high").put("id", "other");
        assertThrows(Exception.class, () -> catalog(new JSONObject().put("openai", provider("https://api.openai.com/v1",
            "@ai-sdk/openai", "future", mismatched))));
    }
}
