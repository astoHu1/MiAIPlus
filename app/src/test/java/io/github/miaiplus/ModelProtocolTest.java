package io.github.miaiplus;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModelProtocolTest {
    @Test public void selectsResponsesForGpt6AndPreservesExplicitChoice() {
        assertEquals("responses", ModelProtocol.resolve("auto", "gpt-6.1-sol"));
        assertEquals("chat", ModelProtocol.resolve("auto", "fixture-model"));
        assertEquals("responses", ModelProtocol.resolve("responses", "provider-alias"));
        assertEquals("chat", ModelProtocol.resolve("chat", "gpt-6.1-sol"));
        assertEquals("/responses", ModelProtocol.path("responses"));
    }
    @Test public void convertsVisionToolsAndResultsWithoutChatOnlyParameters() throws Exception {
        JSONObject chat = new JSONObject("""
            {"model":"gpt-6.1-sol","temperature":0,"top_p":1,"max_tokens":8192,"stream":false,
             "messages":[{"role":"system","content":"instructions"},
              {"role":"user","content":[{"type":"text","text":"screen"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,fixture","detail":"high"}}]},
              {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"computer","arguments":"{}"}}]},
              {"role":"tool","tool_call_id":"call_1","content":"done"}],
             "tools":[{"type":"function","function":{"name":"computer","parameters":{"type":"object","properties":{"action":{"type":"string"}}}}}],
             "tool_choice":"required","parallel_tool_calls":true}
            """);
        String original = chat.toString();
        JSONObject body = ModelProtocol.request(chat);
        assertFalse(body.has("messages")); assertFalse(body.has("temperature")); assertFalse(body.has("top_p"));
        assertFalse(body.has("max_tokens")); assertFalse(body.getBoolean("store"));
        assertEquals(8192, body.getInt("max_output_tokens"));
        assertFalse(body.getBoolean("parallel_tool_calls")); assertEquals("required", body.getString("tool_choice"));
        JSONArray input = body.getJSONArray("input");
        assertEquals("input_text", input.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("type"));
        JSONObject image = input.getJSONObject(1).getJSONArray("content").getJSONObject(1);
        assertEquals("input_image", image.getString("type")); assertEquals("high", image.getString("detail"));
        assertEquals("data:image/jpeg;base64,fixture", image.getString("image_url"));
        assertEquals("function_call", input.getJSONObject(2).getString("type"));
        assertEquals("call_1", input.getJSONObject(2).getString("call_id"));
        assertEquals("function_call_output", input.getJSONObject(3).getString("type"));
        assertEquals("call_1", input.getJSONObject(3).getString("call_id"));
        JSONObject tool = body.getJSONArray("tools").getJSONObject(0);
        assertEquals("computer", tool.getString("name")); assertFalse(tool.has("function")); assertFalse(tool.getBoolean("strict"));
        assertEquals(original, chat.toString());
    }
    @Test public void parsesMixedOutputAndReplaysOpaqueReasoningAndPhase() throws Exception {
        JSONObject response = new JSONObject("""
            {"status":"completed","output":[{"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":"opaque"},
              {"type":"message","role":"assistant","phase":"commentary","content":[{"type":"output_text","text":"observe"}]},
              {"type":"function_call","id":"fc_1","call_id":"call_1","name":"computer","arguments":"{}"}]}
            """);
        JSONObject message = ModelProtocol.message(response);
        assertEquals("observe", message.getString("content"));
        assertEquals("call_1", message.getJSONArray("tool_calls").getJSONObject(0).getString("id"));
        JSONObject next = ModelProtocol.request(new JSONObject().put("model", "gpt-6.1-sol")
            .put("messages", new JSONArray().put(message).put(new JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "done"))));
        JSONArray input = next.getJSONArray("input");
        assertEquals(4, input.length()); assertEquals("opaque", input.getJSONObject(0).getString("encrypted_content"));
        assertEquals("commentary", input.getJSONObject(1).getString("phase"));
        assertEquals("function_call", input.getJSONObject(2).getString("type"));
        assertEquals("function_call_output", input.getJSONObject(3).getString("type"));
    }
    @Test public void rejectsIncompleteFailedEmptyAndRefusedOutput() throws Exception {
        for (String json : new String[]{
            "{\"status\":\"incomplete\",\"output\":[{\"type\":\"function_call\",\"call_id\":\"c\",\"name\":\"computer\",\"arguments\":\"{}\"}]}",
            "{\"status\":\"failed\",\"output\":[]}", "{\"status\":\"completed\",\"output\":[]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"no\"}]}]}"}) {
            try { ModelProtocol.message(new JSONObject(json)); fail("Accepted invalid output"); }
            catch (IllegalStateException expected) { }
        }
    }
    @Test public void convertsStructuredOutputAndNamedToolChoice() throws Exception {
        JSONObject chat = new JSONObject("""
            {"model":"alias","messages":[{"role":"user","content":"classify"}],
             "response_format":{"type":"json_schema","json_schema":{"name":"decision","schema":{"type":"object"}}},
             "tools":[{"type":"function","function":{"name":"computer","parameters":{"type":"object"}}}],
             "tool_choice":{"type":"function","function":{"name":"computer"}},"reasoning_effort":"high"}
            """);
        JSONObject body = ModelProtocol.request(chat);
        assertEquals("computer", body.getJSONObject("tool_choice").getString("name"));
        assertEquals("high", body.getJSONObject("reasoning").getString("effort"));
        assertEquals("decision", body.getJSONObject("text").getJSONObject("format").getString("name"));
    }
    @Test public void preservesTokenUsageAndReasoningDetails() throws Exception {
        JSONObject response = new JSONObject("{\"usage\":{\"input_tokens\":100,\"output_tokens\":40,\"input_tokens_details\":{\"cached_tokens\":50},\"output_tokens_details\":{\"reasoning_tokens\":30}}}");
        JSONObject usage = ModelProtocol.usage(response);
        assertEquals(100, usage.getInt("prompt_tokens")); assertEquals(40, usage.getInt("completion_tokens"));
        assertEquals(140, usage.getInt("total_tokens"));
        assertEquals(50, usage.getJSONObject("prompt_tokens_details").getInt("cached_tokens"));
        assertEquals(30, usage.getJSONObject("completion_tokens_details").getInt("reasoning_tokens"));
    }

}
