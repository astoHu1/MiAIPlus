package io.github.miaiplus;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class ResponsesStreamTest {
    private static ByteArrayInputStream input(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    private static String event(JSONObject value) { return "event: " + value.optString("type") + "\r\ndata: " + value + "\r\n\r\n"; }
    private static JSONObject result(String status) throws Exception {
        return new JSONObject("""
            {"id":"resp_1","status":"completed","output":[
             {"type":"function_call","call_id":"call_1","name":"phone_use","arguments":"{}"}]}
            """).put("status", status);
    }
    @Test public void streamsTextBeforeCompletionAndEmitsValidatedCalls() throws Exception {
        JSONObject delta = new JSONObject().put("type", "response.output_text.delta").put("delta", "你好");
        JSONObject done = new JSONObject().put("type", "response.completed").put("response", result("completed"));
        AtomicReference<JSONObject> saved = new AtomicReference<>();
        try (var stream = new ResponsesStream(input(": ping\r\n\r\n" + event(delta) + event(done)), saved::set)) {
            byte[] buffer = new byte[8192];
            int count = stream.read(buffer);
            String first = new String(buffer, 0, count, StandardCharsets.UTF_8);
            assertTrue(first.contains("你好")); assertFalse(first.contains("tool_calls")); assertNull(saved.get());
            String rest = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(rest.contains("call_1")); assertTrue(rest.contains("tool_calls")); assertTrue(rest.endsWith("data: [DONE]\n\n"));
            assertNotNull(saved.get());
        }
    }
    @Test public void incompleteStreamNeverEmitsExecutableCall() throws Exception {
        String added = event(new JSONObject().put("type", "response.output_item.done").put("item", result("completed").getJSONArray("output").get(0)));
        for (String tail : new String[]{"", event(new JSONObject().put("type", "response.incomplete").put("response", result("incomplete"))),
            "data: [DONE]\n\n", "data: {invalid}\n\n"}) {
            try (var stream = new ResponsesStream(input(added + tail), ignored -> fail("Incomplete response cached"))) {
                try { stream.readAllBytes(); fail("Accepted partial stream"); } catch (IOException expected) { }
            }
        }
    }
    @Test public void completedTextWithoutDeltasIsReturnedOnce() throws Exception {
        JSONObject response = new JSONObject().put("status", "completed").put("output", new JSONArray().put(new JSONObject()
            .put("type", "message").put("role", "assistant").put("content", new JSONArray().put(new JSONObject().put("type", "output_text").put("text", "ok")))));
        try (var stream = new ResponsesStream(input(event(new JSONObject().put("type", "response.completed").put("response", response))), ignored -> {})) {
            String wire = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(wire.contains("\"content\":\"ok\"")); assertTrue(wire.contains("\"finish_reason\":\"stop\""));
        }
    }
}
