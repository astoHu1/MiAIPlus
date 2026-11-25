package io.github.miaiplus;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class AnthropicStreamTest {
    private static final String START = event("message_start", """
        "message":{"id":"msg_fixture","type":"message","role":"assistant","model":"fixture-model",
        "content":[],"stop_reason":null,"stop_sequence":null,
        "usage":{"input_tokens":40,"cache_creation_input_tokens":10,"cache_read_input_tokens":50,"output_tokens":1}}
        """);
    private static final String STOP = event("message_stop", "");

    @Test public void streamsTextAndHeartbeatsBeforeReleasingValidatedToolAndUsage() throws Exception {
        String text = START + ": upstream detail\r\n\r\n" + event("ping", "")
            + event("content_block_start", """
                "index":0,"content_block":{"type":"text","text":""}
                """)
            + event("content_block_delta", """
                "index":0,"delta":{"type":"text_delta","text":"你好"}
                """);
        String tool = stopBlock(0) + startTool(1) + inputDelta(1, "{\"label\":") + inputDelta(1, "\"雪\"}") + stopBlock(1)
            + messageDelta("tool_use", 3) + messageDelta("tool_use", 13);

        AtomicReference<JSONObject> completed = new AtomicReference<>();
        try (var stream = new AnthropicStream(input(text + tool + STOP), completed::set)) {
            StringBuilder beforeCompletion = new StringBuilder();
            while (beforeCompletion.indexOf("你好") < 0) beforeCompletion.append(readChunk(stream));
            String visible = beforeCompletion.toString();
            List<JSONObject> textChunks = chunks(visible);
            assertEquals("msg_fixture", textChunks.get(0).getString("id"));
            assertEquals("fixture-model", textChunks.get(0).getString("model"));
            assertEquals("你好", delta(textChunks.get(textChunks.size() - 1)).getString("content"));
            assertTrue(visible.contains(": keep-alive\n\n"));
            assertFalse(visible.contains("upstream detail"));
            assertNull(completed.get());

            String terminal;
            do {
                terminal = readChunk(stream);
                if (completed.get() == null) {
                    assertFalse(terminal.contains("tool_calls"));
                    assertFalse(terminal.contains("[DONE]"));
                }
            } while (completed.get() == null);
            terminal += new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(terminal.endsWith("data: [DONE]\n\n"));
            List<JSONObject> chunks = chunks(terminal);
            JSONObject call = delta(chunks.get(0)).getJSONArray("tool_calls").getJSONObject(0);
            assertEquals(0, call.getInt("index"));
            assertEquals("toolu_fixture", call.getString("id"));
            assertEquals("function", call.getString("type"));
            JSONObject function = call.getJSONObject("function");
            assertEquals("lookup", function.getString("name"));
            assertEquals("雪", new JSONObject(function.getString("arguments")).getString("label"));
            assertEquals("tool_calls", choice(chunks.get(1)).getString("finish_reason"));
            JSONObject usage = chunks.get(2).getJSONObject("usage");
            assertEquals(100, usage.getInt("prompt_tokens"));
            assertEquals(13, usage.getInt("completion_tokens"));
            assertEquals(113, usage.getInt("total_tokens"));
            assertEquals(50, usage.getJSONObject("prompt_tokens_details").getInt("cached_tokens"));
        }
    }

    @Test public void unfinishedMalformedOrFailedStreamsNeverReleaseToolCalls() throws Exception {
        String openTool = START + startTool(0);
        String validInput = inputDelta(0, "{\"label\":\"ok\"}");
        List<String> failed = List.of(
            START + STOP,
            openTool + validInput + stopBlock(0) + messageDelta("tool_use", 4),
            openTool + validInput + messageDelta("tool_use", 4) + STOP,
            openTool + inputDelta(0, "{\"label\":") + stopBlock(0) + messageDelta("tool_use", 4) + STOP,
            openTool + validInput + stopBlock(0) + messageDelta("max_tokens", 4) + STOP,
            openTool + validInput + stopBlock(0) + messageDelta("refusal", 4) + STOP,
            openTool + validInput + stopBlock(0) + messageDelta("tool_use", 4) + event("error", """
                "error":{"type":"overloaded_error","message":"private upstream detail"}
                """),
            openTool + validInput + stopBlock(0) + messageDelta("tool_use", 4) + "data: [DONE]\n\n");

        for (String fixture : failed) {
            try (var stream = new AnthropicStream(input(fixture), ignored -> fail("Incomplete message cached"))) {
                IOException error = assertThrows(IOException.class, () -> {
                    byte[] buffer = new byte[4096];
                    for (int count; (count = stream.read(buffer)) >= 0;) {
                        String translated = new String(buffer, 0, count, StandardCharsets.UTF_8);
                        assertFalse(translated.contains("tool_calls"));
                        assertFalse(translated.contains("[DONE]"));
                    }
                });
                assertFalse(error.toString().contains("private upstream detail"));
            }
        }
    }

    @Test public void preservesStreamedThinkingSignaturesWithoutSendingThemToTheHost() throws Exception {
        String source = START + event("content_block_start", """
            "index":0,"content_block":{"type":"thinking","thinking":""}
            """) + event("content_block_delta", """
            "index":0,"delta":{"type":"thinking_delta","thinking":"private thought"}
            """) + event("content_block_delta", """
            "index":0,"delta":{"type":"signature_delta","signature":"opaque fixture"}
            """) + stopBlock(0) + startTool(1) + inputDelta(1, "{}") + stopBlock(1)
            + messageDelta("tool_use", 4) + STOP;
        AtomicReference<JSONObject> completed = new AtomicReference<>();
        try (var stream = new AnthropicStream(input(source), completed::set)) {
            String translated = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(translated.contains("private thought"));
            assertFalse(translated.contains("opaque fixture"));
            assertTrue(translated.endsWith("data: [DONE]\n\n"));
            JSONObject thinking = completed.get().getJSONArray("content").getJSONObject(0);
            assertEquals("private thought", thinking.getString("thinking"));
            assertEquals("opaque fixture", thinking.getString("signature"));
        }
    }

    @Test public void completesTextAfterAnUnrecognizedEventWithoutRequiringNetworkEof() throws Exception {
        String source = START + event("future_event", "\"value\":\"private unknown payload\"")
            + event("content_block_start", """
                "index":0,"content_block":{"type":"text","text":""}
                """)
            + event("content_block_delta", """
                "index":0,"delta":{"type":"text_delta","text":"ready"}
                """)
            + stopBlock(0) + messageDelta("end_turn", 6) + STOP;
        InputStream openConnection = new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                if (available() == 0) throw new AssertionError("Read past Anthropic message_stop");
                return super.read(bytes, offset, length);
            }
        };
        try (var stream = new AnthropicStream(openConnection, ignored -> {})) {
            String translated = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(translated.contains("private unknown payload"));
            assertTrue(translated.contains("\"content\":\"ready\""));
            assertTrue(translated.contains("\"finish_reason\":\"stop\""));
            assertTrue(translated.endsWith("data: [DONE]\n\n"));
        }
    }

    private static String event(String type, String fields) {
        return "event: " + type + "\r\ndata: {\"type\":" + JSONObject.quote(type)
            + (fields.isBlank() ? "" : "," + fields.strip().replace('\n', ' ')) + "}\r\n\r\n";
    }

    private static String startTool(int index) {
        return event("content_block_start", "\"index\":" + index + """
            ,"content_block":{"type":"tool_use","id":"toolu_fixture","name":"lookup","input":{}}
            """);
    }

    private static String inputDelta(int index, String json) {
        return event("content_block_delta", "\"index\":" + index
            + ",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":" + JSONObject.quote(json) + "}");
    }

    private static String stopBlock(int index) { return event("content_block_stop", "\"index\":" + index); }

    private static String messageDelta(String reason, int outputTokens) {
        return event("message_delta", "\"delta\":{\"stop_reason\":" + JSONObject.quote(reason)
            + ",\"stop_sequence\":null},\"usage\":{\"output_tokens\":" + outputTokens + "}");
    }

    private static ByteArrayInputStream input(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String readChunk(InputStream input) throws IOException {
        byte[] buffer = new byte[4096];
        int count = input.read(buffer);
        assertTrue("Expected another translated event", count > 0);
        return new String(buffer, 0, count, StandardCharsets.UTF_8);
    }

    private static List<JSONObject> chunks(String wire) throws Exception {
        List<JSONObject> chunks = new ArrayList<>();
        for (String line : wire.split("\n")) {
            if (line.startsWith("data: {")) chunks.add(new JSONObject(line.substring(6)));
        }
        return chunks;
    }

    private static JSONObject choice(JSONObject chunk) throws Exception {
        JSONArray choices = chunk.getJSONArray("choices");
        return choices.getJSONObject(0);
    }

    private static JSONObject delta(JSONObject chunk) throws Exception { return choice(chunk).getJSONObject("delta"); }
}
