package io.github.miaiplus;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReasoningPolicyTest {
    @Test public void sol61RejectsNoneAndMinimalButSol60AllowsNone() {
        assertEquals("responses", ModelProtocol.resolve("auto", "openai/gpt-6.1-sol"));
        assertEquals(java.util.List.of("default", "low", "medium", "high", "xhigh", "max"),
            ReasoningPolicy.choices("openai/gpt-6.1-sol", "responses"));
        assertEquals("default", ReasoningPolicy.normalize("gpt-6.1-sol", "responses", "none"));
        assertEquals("default", ReasoningPolicy.normalize("gpt-6.1-sol", "responses", "minimal"));
        assertTrue(ReasoningPolicy.choices("gpt-6-sol", "responses").contains("none"));
        assertFalse(ReasoningPolicy.choices("gpt-6-astra", "responses").contains("none"));
    }
    @Test public void olderOpenAiModelsHaveDifferentEfforts() {
        assertTrue(ReasoningPolicy.choices("gpt-5-2025-08-07", "chat").contains("minimal"));
        assertFalse(ReasoningPolicy.choices("gpt-5.1", "chat").contains("xhigh"));
        assertTrue(ReasoningPolicy.choices("gpt-5.5", "responses").contains("xhigh"));
        assertFalse(ReasoningPolicy.choices("gpt-5.5", "responses").contains("max"));
        assertTrue(ReasoningPolicy.choices("gpt-5.4", "responses").contains("none"));
        assertFalse(ReasoningPolicy.choices("gpt-5.3-codex", "responses").contains("none"));
    }
    @Test public void aliasesAndThinkingOnlyQwenNeverGetInventedSwitches() {
        for (String model : java.util.List.of("custom-alias", "claude-sonnet-4-5", "gemini-2.5-pro",
            "gpt-5.1-chat-latest", "gpt-6.1-sol-fast-unknown", "qwen3.7-max-preview", "qwen3.8-2.4t-a95b"))
            assertEquals(java.util.List.of("default"), ReasoningPolicy.choices(model, "chat"));
        assertEquals(java.util.List.of("default"), ReasoningPolicy.choices("qwen3.8-max", "responses"));
    }
    @Test public void finalWireBodyUsesCorrectFieldAndClearsStockThinking() throws Exception {
        JSONObject response = new JSONObject("{\"enable_thinking\":false,\"reasoning_effort\":\"low\",\"temperature\":0}");
        ReasoningPolicy.apply(response, "gpt-6.1-sol", "responses", "max");
        assertEquals("max", response.getJSONObject("reasoning").getString("effort"));
        assertFalse(response.has("reasoning_effort")); assertFalse(response.has("enable_thinking"));
        assertFalse(response.has("temperature"));
        JSONObject chat = new JSONObject("{\"max_tokens\":1024}");
        ReasoningPolicy.apply(chat, "gpt-5.1", "chat", "high");
        assertEquals("high", chat.getString("reasoning_effort"));
        assertEquals(8192, chat.getInt("max_completion_tokens")); assertFalse(chat.has("max_tokens"));
    }
    @Test public void qwenUsesBooleanAndDeepseekUsesProviderSwitch() throws Exception {
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
    @Test public void providerDefaultDoesNotReintroduceInvalidNativeSamplingForKnownOpenAi() throws Exception {
        JSONObject body = new JSONObject("{\"temperature\":0,\"top_p\":1,\"max_tokens\":1024}");
        ReasoningPolicy.apply(body, "gpt-5.1", "chat", "default");
        assertFalse(body.has("temperature")); assertFalse(body.has("top_p"));
        assertFalse(body.has("reasoning_effort")); assertEquals(8192, body.getInt("max_completion_tokens"));
        JSONObject ordinary = new JSONObject("{\"temperature\":0,\"max_tokens\":1024}");
        ReasoningPolicy.apply(ordinary, "gpt-4.1", "chat", "default");
        assertEquals(0, ordinary.getInt("temperature")); assertEquals(1024, ordinary.getInt("max_tokens"));
    }
}
