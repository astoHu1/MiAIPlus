package io.github.miaiplus;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class AnthropicProtocolTest {
    @Test public void convertsSystemInstructionsImagesAndToolHistory() throws Exception {
        JSONObject chat = new JSONObject("""
            {"model":"fixture-model","stream":true,"max_tokens":1024,"temperature":0,"top_p":1,
             "rid":"private","reasoning_effort":"high","messages":[
              {"role":"system","content":"system instructions"},
              {"role":"developer","content":"tool instructions"},
              {"role":"user","content":[{"type":"text","text":"look at this"},
               {"type":"image_url","image_url":{"url":"https://example.test/screen.webp"}}]},
              {"role":"assistant","content":null,"tool_calls":[{"id":"toolu_fixture","type":"function",
               "function":{"name":"lookup","arguments":"{\\"label\\":\\"雪\\"}"}}]},
              {"role":"tool","tool_call_id":"toolu_fixture","content":[{"type":"text","text":"found"},
               {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,YQ==","detail":"high"}}]},
              {"role":"user","content":"continue"}],
             "tools":[{"type":"function","function":{"name":"lookup","description":"Look up a label",
               "parameters":{"type":"object","properties":{"label":{"type":"string"}}},"strict":false}}],
             "tool_choice":{"type":"function","function":{"name":"lookup"}},"parallel_tool_calls":true}
            """);
        String original = chat.toString();
        JSONObject body = AnthropicProtocol.request(chat);

        assertEquals("fixture-model", body.getString("model"));
        assertTrue(body.getBoolean("stream"));
        assertEquals(1024, body.getInt("max_tokens"));
        for (String key : new String[]{"rid", "reasoning_effort", "temperature", "top_p", "parallel_tool_calls"}) {
            assertFalse("Unexpected Anthropic field: " + key, body.has(key));
        }
        JSONArray system = body.getJSONArray("system");
        assertEquals("system instructions", system.getJSONObject(0).getString("text"));
        assertEquals("tool instructions", system.getJSONObject(1).getString("text"));

        JSONArray turns = body.getJSONArray("messages");
        assertEquals(3, turns.length());
        JSONObject user = turns.getJSONObject(0);
        assertEquals("user", user.getString("role"));
        assertEquals("look at this", user.getJSONArray("content").getJSONObject(0).getString("text"));
        JSONObject remoteImage = user.getJSONArray("content").getJSONObject(1);
        assertEquals("image", remoteImage.getString("type"));
        assertEquals("url", remoteImage.getJSONObject("source").getString("type"));
        assertEquals("https://example.test/screen.webp", remoteImage.getJSONObject("source").getString("url"));

        JSONObject assistant = turns.getJSONObject(1);
        assertEquals("assistant", assistant.getString("role"));
        JSONObject call = assistant.getJSONArray("content").getJSONObject(0);
        assertEquals("tool_use", call.getString("type"));
        assertEquals("toolu_fixture", call.getString("id"));
        assertEquals("lookup", call.getString("name"));
        assertEquals("雪", call.getJSONObject("input").getString("label"));

        JSONObject followUp = turns.getJSONObject(2);
        assertEquals("user", followUp.getString("role"));
        JSONArray content = followUp.getJSONArray("content");
        JSONObject result = content.getJSONObject(0);
        assertEquals("tool_result", result.getString("type"));
        assertEquals("toolu_fixture", result.getString("tool_use_id"));
        JSONArray resultContent = result.getJSONArray("content");
        assertEquals("found", resultContent.getJSONObject(0).getString("text"));
        JSONObject screenshot = resultContent.getJSONObject(1).getJSONObject("source");
        assertEquals("base64", screenshot.getString("type"));
        assertEquals("image/jpeg", screenshot.getString("media_type"));
        assertEquals("YQ==", screenshot.getString("data"));
        assertEquals("continue", content.getJSONObject(1).getString("text"));

        JSONObject tool = body.getJSONArray("tools").getJSONObject(0);
        assertEquals("lookup", tool.getString("name"));
        assertEquals("Look up a label", tool.getString("description"));
        assertEquals("string", tool.getJSONObject("input_schema").getJSONObject("properties")
            .getJSONObject("label").getString("type"));
        assertFalse(tool.has("strict"));
        JSONObject choice = body.getJSONObject("tool_choice");
        assertEquals("tool", choice.getString("type"));
        assertEquals("lookup", choice.getString("name"));
        assertTrue(choice.getBoolean("disable_parallel_tool_use"));
        assertEquals(original, chat.toString());
    }

    @Test public void preservesSignedHistoryWithoutExposingItInTheNativeCompletion() throws Exception {
        JSONObject response = new JSONObject("""
            {"id":"msg_fixture","type":"message","role":"assistant","model":"fixture-model",
             "content":[{"type":"thinking","thinking":"private thought","signature":"opaque fixture"},
              {"type":"redacted_thinking","data":"opaque redaction"},{"type":"text","text":"looking up"},
              {"type":"tool_use","id":"toolu_fixture","name":"lookup","input":{"label":"雪"}}],
             "stop_reason":"tool_use","usage":{"input_tokens":7,"output_tokens":11}}
            """);
        JSONObject assistant = AnthropicProtocol.message(response);
        assertEquals("looking up", assistant.getString("content"));
        JSONObject call = assistant.getJSONArray("tool_calls").getJSONObject(0);
        assertEquals("toolu_fixture", call.getString("id"));
        assertEquals("雪", new JSONObject(call.getJSONObject("function").getString("arguments")).getString("label"));

        JSONObject next = AnthropicProtocol.request(new JSONObject().put("model", "fixture-model")
            .put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", "find a label"))
                .put(assistant)
                .put(new JSONObject().put("role", "tool").put("tool_call_id", "toolu_fixture").put("content", "found"))));
        JSONArray replay = next.getJSONArray("messages").getJSONObject(1).getJSONArray("content");
        assertEquals(response.getJSONArray("content").toString(), replay.toString());

        JSONObject completion = AnthropicProtocol.completion(response);
        assertFalse(completion.toString().contains("private thought"));
        assertFalse(completion.toString().contains("opaque fixture"));
        assertFalse(completion.toString().contains("opaque redaction"));
        JSONObject choice = completion.getJSONArray("choices").getJSONObject(0);
        assertEquals("tool_calls", choice.getString("finish_reason"));
        assertEquals("looking up", choice.getJSONObject("message").getString("content"));
        assertEquals(18, completion.getJSONObject("usage").getInt("total_tokens"));
    }

    @Test public void rejectsIncompleteOrRefusedResultsEvenIfTheyContainAnExecutableTool() throws Exception {
        JSONObject result = new JSONObject("""
            {"id":"msg_fixture","type":"message","role":"assistant","model":"fixture-model",
             "content":[{"type":"tool_use","id":"toolu_fixture","name":"lookup","input":{}}]}
            """);
        for (String reason : new String[]{"end_turn", "max_tokens", "refusal", "pause_turn"}) {
            result.put("stop_reason", reason);
            assertThrows(IllegalStateException.class, () -> AnthropicProtocol.message(result));
        }
    }
}
