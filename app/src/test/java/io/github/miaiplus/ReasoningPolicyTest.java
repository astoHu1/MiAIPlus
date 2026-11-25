package io.github.miaiplus;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReasoningPolicyTest {
    @Test public void builtinChoicesFollowKnownModelFamiliesAndNeverInventSwitchesForAliases() {
        assertEquals(java.util.List.of("default", "low", "medium", "high", "xhigh", "max"),
            ReasoningPolicy.choices("openai/gpt-6.1-sol", "responses"));
        assertEquals("default", ReasoningPolicy.normalize("gpt-6.1-sol", "responses", "none"));
        assertEquals("default", ReasoningPolicy.normalize("gpt-6.1-sol", "responses", "minimal"));
        assertTrue(ReasoningPolicy.choices("gpt-6-sol", "responses").contains("none"));
        assertFalse(ReasoningPolicy.choices("gpt-6-astra", "responses").contains("none"));
        assertTrue(ReasoningPolicy.choices("gpt-5-2025-08-07", "chat").contains("minimal"));
        assertFalse(ReasoningPolicy.choices("gpt-5.1", "chat").contains("xhigh"));
        assertTrue(ReasoningPolicy.choices("gpt-5.5", "responses").contains("xhigh"));
        assertFalse(ReasoningPolicy.choices("gpt-5.5", "responses").contains("max"));
        assertTrue(ReasoningPolicy.choices("gpt-5.4", "responses").contains("none"));
        assertFalse(ReasoningPolicy.choices("gpt-5.3-codex", "responses").contains("none"));
        for (String model : java.util.List.of("custom-alias", "claude-sonnet-4-5", "gemini-2.5-pro",
            "gpt-5.1-chat-latest", "gpt-6.1-sol-fast-unknown", "qwen3.7-max-preview", "qwen3.8-2.4t-a95b"))
            assertEquals(java.util.List.of("default"), ReasoningPolicy.choices(model, "chat"));
        assertEquals(java.util.List.of("default"), ReasoningPolicy.choices("qwen3.8-max", "responses"));
    }
    @Test public void anthropicUsesModelDefaultEvenForRecognizedOpenAiAliases() throws Exception {
        var plan = ReasoningPolicy.plan("https://example.com/v1", "gpt-6.1-sol", "anthropic");
        assertEquals(java.util.List.of("default"), plan.choices());
        JSONObject body = new JSONObject("{\"enable_thinking\":false,\"reasoning_effort\":\"low\",\"max_tokens\":1024}");
        plan.apply(body, "gpt-6.1-sol", "anthropic", "high");
        assertFalse(body.has("enable_thinking"));
        assertFalse(body.has("reasoning_effort"));
        assertEquals(1024, body.getInt("max_tokens"));
    }
    @Test public void builtinApplyUsesEachProviderWireFormatAndStripsInvalidSampling() throws Exception {
        JSONObject response = new JSONObject("{\"enable_thinking\":false,\"reasoning_effort\":\"low\",\"temperature\":0}");
        ReasoningPolicy.apply(response, "gpt-6.1-sol", "responses", "max");
        assertEquals("max", response.getJSONObject("reasoning").getString("effort"));
        assertFalse(response.has("reasoning_effort")); assertFalse(response.has("enable_thinking"));
        assertFalse(response.has("temperature"));
        JSONObject chat = new JSONObject("{\"max_tokens\":1024}");
        ReasoningPolicy.apply(chat, "gpt-5.1", "chat", "high");
        assertEquals("high", chat.getString("reasoning_effort"));
        assertEquals(8192, chat.getInt("max_completion_tokens")); assertFalse(chat.has("max_tokens"));
        JSONObject providerDefault = new JSONObject("{\"temperature\":0,\"top_p\":1,\"max_tokens\":1024}");
        ReasoningPolicy.apply(providerDefault, "gpt-5.1", "chat", "default");
        assertFalse(providerDefault.has("temperature")); assertFalse(providerDefault.has("top_p"));
        assertFalse(providerDefault.has("reasoning_effort")); assertEquals(8192, providerDefault.getInt("max_completion_tokens"));
        JSONObject ordinary = new JSONObject("{\"temperature\":0,\"max_tokens\":1024}");
        ReasoningPolicy.apply(ordinary, "gpt-4.1", "chat", "default");
        assertEquals(0, ordinary.getInt("temperature")); assertEquals(1024, ordinary.getInt("max_tokens"));
        JSONObject body = new JSONObject();
        ReasoningPolicy.apply(body, "qwen3.8-max", "chat", "off");
        assertFalse(body.getBoolean("enable_thinking")); assertFalse(body.has("reasoning_effort"));
        ReasoningPolicy.apply(body, "deepseek-flash", "chat", "max");
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"));
        assertEquals("max", body.getString("reasoning_effort")); assertFalse(body.has("enable_thinking"));
        ReasoningPolicy.apply(body, "deepseek-flash", "responses", "none");
        assertEquals("none", body.getJSONObject("reasoning").getString("effort")); assertFalse(body.has("thinking"));
    }
    @Test public void defaultAndInvalidSelectionOmitReasoningParameters() throws Exception {
        for (String effort : java.util.List.of("default", "none", "unsupported")) {
            JSONObject body = new JSONObject("{\"reasoning\":{\"effort\":\"low\"},\"enable_thinking\":true,\"thinking\":{},\"thinking_budget\":500}");
            ReasoningPolicy.apply(body, "gpt-6.1-sol", "responses", effort);
            assertFalse(body.has("reasoning")); assertFalse(body.has("enable_thinking"));
            assertFalse(body.has("thinking")); assertFalse(body.has("thinking_budget"));
        }
    }
    @Test public void persistenceIsScopedToEndpointModelAndApi() {
        String key = ReasoningPolicy.preferenceKey("https://example.test/v1", "gpt-6.1-sol", "responses");
        assertEquals(key, ReasoningPolicy.preferenceKey("https://example.test/v1/", "gpt-6.1-sol", "responses"));
        assertNotEquals(key, ReasoningPolicy.preferenceKey("https://other.test/v1", "gpt-6.1-sol", "responses"));
        assertNotEquals(key, ReasoningPolicy.preferenceKey("https://example.test/v1", "gpt-6-sol", "responses"));
        assertNotEquals(key, ReasoningPolicy.preferenceKey("https://example.test/v1", "gpt-6.1-sol", "chat"));
        assertFalse(key.contains("example"));
    }
    @Test public void wireContractRoundTripPreservesImmutableCapabilities() {
        var mutable = new java.util.ArrayList<>(java.util.List.of("default", "low", "high"));
        var plan = new ReasoningPolicy.Plan(mutable, "standard", true, true);
        mutable.add("max");
        assertEquals(java.util.List.of("default", "low", "high"), plan.choices());
        assertThrows(UnsupportedOperationException.class, () -> plan.choices().add("max"));
        assertEquals(plan, ReasoningPolicy.Plan.decode(plan.encode()));
        assertEquals(plan.encode(), ReasoningPolicy.Plan.decode(plan.encode()).encode());
        assertEquals("default", plan.normalize("max"));
        assertEquals("default", plan.normalize(null));
        var ordinary = new ReasoningPolicy.Plan(java.util.List.of("default"), "default", false, false);
        assertEquals(ordinary, ReasoningPolicy.Plan.decode(ordinary.encode()));
    }
    @Test public void malformedOrInventedWireContractsAreRejected() throws Exception {
        String valid = new ReasoningPolicy.Plan(java.util.List.of("default", "high"), "standard", true, true).encode();
        for (String malformed : java.util.List.of("", "{}", "[]", valid + " trailing", " ".repeat(513),
            new JSONObject(valid).put("version", 2).toString(),
            new JSONObject(valid).put("wire", "arbitrary_field").toString(),
            new JSONObject(valid).put("stripSampling", "true").toString(),
            new JSONObject(valid).put("completionTokens", 1).toString(),
            new JSONObject(valid).put("extra", true).toString(),
            new JSONObject(valid).put("choices", new org.json.JSONArray(java.util.List.of("high"))).toString(),
            new JSONObject(valid).put("choices", new org.json.JSONArray(java.util.List.of("default", "high", "high"))).toString(),
            new JSONObject(valid).put("choices", new org.json.JSONArray(java.util.List.of("default", "ultra"))).toString(),
            new JSONObject(valid).put("choices", new org.json.JSONArray(java.util.List.of("default", "on"))).toString(),
            new JSONObject(valid).put("wire", "default").toString()))
            assertNull(malformed, ReasoningPolicy.Plan.decode(malformed));
        assertNull(ReasoningPolicy.Plan.decode(null));
        assertThrows(IllegalArgumentException.class, () -> new ReasoningPolicy.Plan(
            java.util.List.of("default", "off"), "deepseek", false, false));
        assertThrows(IllegalArgumentException.class, () -> new ReasoningPolicy.Plan(
            java.util.List.of("default", "on"), "qwen", false, true));
    }
    @Test public void remoteWireContractUsesExplicitProviderCapabilitiesAndKeepsProviderSwitchSemantics() throws Exception {
        var standard = new ReasoningPolicy.Plan(java.util.List.of("default", "high"), "standard", true, true);
        JSONObject body = new JSONObject("{\"temperature\":0,\"top_p\":1,\"max_tokens\":1024,\"enable_thinking\":true}");
        standard.apply(body, "new-provider-model", "chat", "high");
        assertEquals("high", body.getString("reasoning_effort"));
        assertEquals(8192, body.getInt("max_completion_tokens"));
        assertFalse(body.has("max_tokens")); assertFalse(body.has("temperature"));
        assertFalse(body.has("top_p")); assertFalse(body.has("enable_thinking"));
        JSONObject responses = new JSONObject();
        standard.apply(responses, "new-provider-model", "responses", "high");
        assertEquals("high", responses.getJSONObject("reasoning").getString("effort"));
        var ordinary = new ReasoningPolicy.Plan(java.util.List.of("default"), "default", false, false);
        ordinary.apply(body, "gpt-new-alias", "chat", "high");
        assertFalse(body.has("reasoning_effort"));
        JSONObject untouched = new JSONObject("{\"temperature\":0,\"max_tokens\":1024}");
        ordinary.apply(untouched, "gpt-new-alias", "chat", "default");
        assertEquals(0, untouched.getInt("temperature")); assertEquals(1024, untouched.getInt("max_tokens"));
        var qwen = new ReasoningPolicy.Plan(java.util.List.of("default", "off", "on"), "qwen", false, false);
        JSONObject switches = new JSONObject();
        qwen.apply(switches, "new-qwen-model", "chat", "off");
        assertFalse(switches.getBoolean("enable_thinking"));
        qwen.apply(switches, "new-qwen-model", "chat", "on");
        assertTrue(switches.getBoolean("enable_thinking"));
        qwen.apply(switches, "new-qwen-model", "responses", "on");
        assertFalse(switches.has("enable_thinking")); assertFalse(switches.has("reasoning"));
        var deepseek = new ReasoningPolicy.Plan(java.util.List.of("default", "none", "high"), "deepseek", true, false);
        deepseek.apply(switches, "new-deepseek-model", "chat", "none");
        assertEquals("disabled", switches.getJSONObject("thinking").getString("type"));
        assertFalse(switches.has("reasoning_effort"));
        deepseek.apply(switches, "new-deepseek-model", "chat", "high");
        assertEquals("enabled", switches.getJSONObject("thinking").getString("type"));
        assertEquals("high", switches.getString("reasoning_effort"));
        deepseek.apply(switches, "new-deepseek-model", "responses", "high");
        assertFalse(switches.has("thinking")); assertEquals("high", switches.getJSONObject("reasoning").getString("effort"));
    }
}
