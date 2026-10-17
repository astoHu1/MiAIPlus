package io.github.miaiplus;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class IntentProtocolTest {
    @Test public void preservesCapabilityAndGatePrompts() throws Exception {
        String source = "{\"model\":\"stock\",\"rid\":\"private\",\"messages\":[{\"role\":\"system\",\"content\":\"schema\"},{\"role\":\"user\",\"content\":\"task\"}],\"stream\":false}";
        JSONObject result = IntentProtocol.request(source, "chosen");
        assertEquals("chosen", result.getString("model"));
        assertEquals(new JSONObject(source).getJSONArray("messages").toString(), result.getJSONArray("messages").toString());
        assertFalse(result.has("rid")); assertFalse(result.getBoolean("stream"));
    }
    @Test public void convertsIntentContextIntoModelMessages() throws Exception {
        JSONObject result = IntentProtocol.request("{\"model\":\"stock\",\"intent_protocol\":\"v2\",\"routing_profile\":\"private\",\"context\":{\"conversation_history\":[{\"content\":\"history\"}]},\"messages\":[{\"role\":\"user\",\"content\":\"task\"}]}", "chosen");
        assertEquals(2, result.getJSONArray("messages").length());
        assertTrue(result.getJSONArray("messages").getJSONObject(0).getString("content").contains("history"));
        assertEquals("task", result.getJSONArray("messages").getJSONObject(1).getString("content"));
        assertFalse(result.has("intent_protocol")); assertFalse(result.has("context")); assertFalse(result.has("routing_profile"));
    }
    @Test public void preventsUnregisteredDirectDispatchAndPreservesUsage() throws Exception {
        JSONObject model = new JSONObject().put("status", "SUCCESS").put("direct_path", true).put("tool_name", "invented").put("difficulty", "easy");
        JSONObject response = new JSONObject("{\"usage\":{\"prompt_tokens\":9},\"choices\":[{\"message\":{}}]}");
        response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("content", "```json\n" + model + "\n```");
        JSONObject result = new JSONObject(IntentProtocol.response(response.toString()));
        JSONObject message = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        assertEquals("FALLBACK", message.getString("status")); assertFalse(message.getBoolean("direct_path"));
        assertFalse(message.has("tool_name")); assertEquals(9, result.getJSONObject("usage").getInt("prompt_tokens"));
    }
    @Test public void suppliesLocalCapabilitySchemaForCloudPromptRequests() throws Exception {
        JSONObject result = IntentProtocol.request("{\"prompt_source\":\"cloud\",\"rid\":\"private\",\"messages\":[{\"role\":\"user\",\"content\":\"task\"}]}", "chosen");
        var messages = result.getJSONArray("messages");
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        assertTrue(messages.getJSONObject(0).getString("content").contains("required_capabilities"));
        assertEquals("task", messages.getJSONObject(1).getString("content"));
        assertFalse(result.has("prompt_source"));
        assertFalse(result.has("rid"));
        assertEquals(300, result.getInt("max_tokens"));
        assertFalse(result.getBoolean("stream"));
    }
    @Test(expected = org.json.JSONException.class) public void rejectsInvalidRoutingContent() throws Exception {
        IntentProtocol.response("{\"choices\":[{\"message\":{\"content\":\"not a decision\"}}]}");
    }
}
