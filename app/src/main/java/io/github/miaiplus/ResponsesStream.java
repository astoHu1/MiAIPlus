package io.github.miaiplus;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Incremental Responses SSE -> native Chat Completions SSE. Never treats a partial call as executable. */
public final class ResponsesStream extends InputStream {
    private final BufferedReader reader;
    private final Consumer<JSONObject> completed;
    private byte[] pending = new byte[0];
    private int offset, received;
    private boolean finished, textSent;
    private String id = "external-response", model = "";

    public ResponsesStream(InputStream input, Consumer<JSONObject> completed) {
        reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.completed = completed;
    }
    @Override public int read() throws IOException {
        if (!availableChunk()) return -1;
        return pending[offset++] & 255;
    }
    @Override public int read(byte[] target, int start, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(start, length, target.length);
        if (length == 0) return 0;
        if (!availableChunk()) return -1;
        int count = Math.min(length, pending.length - offset);
        System.arraycopy(pending, offset, target, start, count); offset += count; return count;
    }
    private String line() throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (++received > 2_000_000) throw new IOException("Responses 流响应过大");
            if (c == '\n') return line.toString();
            if (c != '\r') line.append((char)c);
        }
        return line.length() == 0 ? null : line.toString();
    }
    private boolean availableChunk() throws IOException {
        while (offset == pending.length && !finished) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = line()) != null && !line.isEmpty()) {
                if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).stripLeading());
                }
            }
            if (data.length() == 0) {
                if (line == null) throw new IOException("Responses 流提前结束");
                continue;
            }
            try {
                JSONObject event = new JSONObject(data.toString());
                String type = event.getString("type");
                String wire = "";
                if ("response.created".equals(type)) {
                    JSONObject response = event.getJSONObject("response");
                    id = response.optString("id", id); model = response.optString("model", model);
                    wire = chunk(new JSONObject().put("role", "assistant"), null);
                } else if ("response.output_text.delta".equals(type)) {
                    wire = chunk(new JSONObject().put("content", event.getString("delta")), null);
                    textSent = true;
                } else if ("response.completed".equals(type)) {
                    JSONObject response = event.getJSONObject("response");
                    JSONObject message = ModelProtocol.message(response);
                    completed.accept(response);
                    if (!textSent && !message.getString("content").isEmpty()) wire += chunk(new JSONObject().put("content", message.getString("content")), null);
                    JSONArray calls = message.optJSONArray("tool_calls");
                    if (calls != null) {
                        for (int i = 0; i < calls.length(); i++) {
                            JSONObject call = new JSONObject(calls.getJSONObject(i).toString()).put("index", i);
                            wire += chunk(new JSONObject().put("tool_calls", new JSONArray().put(call)), null);
                        }
                    }
                    wire += chunk(new JSONObject(), calls == null ? "stop" : "tool_calls");
                    if (response.optJSONObject("usage") != null) wire += "data: " + new JSONObject().put("id", id)
                        .put("object", "chat.completion.chunk").put("created", 0).put("model", model)
                        .put("choices", new JSONArray()).put("usage", ModelProtocol.usage(response)) + "\n\n";
                    wire += "data: [DONE]\n\n";
                    finished = true;
                } else if ("error".equals(type) || "response.failed".equals(type) || "response.incomplete".equals(type)) {
                    throw new IOException("Responses 请求失败或输出不完整");
                }
                pending = wire.getBytes(StandardCharsets.UTF_8); offset = 0;
            } catch (IOException e) { throw e; }
            catch (Exception e) { throw new IOException("Responses 流格式无效"); }
        }
        return offset < pending.length;
    }
    private String chunk(JSONObject delta, String reason) throws Exception {
        return "data: " + new JSONObject().put("id", id).put("object", "chat.completion.chunk").put("created", 0)
            .put("model", model).put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                .put("delta", delta).put("finish_reason", reason == null ? JSONObject.NULL : reason))) + "\n\n";
    }
    @Override public void close() throws IOException { reader.close(); }
}
