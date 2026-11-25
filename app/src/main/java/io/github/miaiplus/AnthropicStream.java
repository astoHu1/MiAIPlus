package io.github.miaiplus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

/** Streams text to the host immediately and holds tool calls until a validated message_stop. */
public final class AnthropicStream extends InputStream {
    private static final String HEARTBEAT = ": keep-alive\n\n";
    private static final int MAX_CHARACTERS = 2_000_000;

    private final BufferedReader reader;
    private final Consumer<JSONObject> completed;
    private final JSONArray content = new JSONArray();
    private JSONObject response;
    private Block block;
    private String id = "external-response", model = "";
    private byte[] pending = new byte[0];
    private int offset, received;
    private boolean skipLineFeed, messageDelta, finished;
    private IOException failure;

    public AnthropicStream(InputStream input, Consumer<JSONObject> completed) {
        reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.completed = Objects.requireNonNull(completed);
    }

    @Override public int read() throws IOException {
        if (!availableChunk()) return -1;
        return pending[offset++] & 255;
    }

    @Override public int read(byte[] target, int start, int length) throws IOException {
        Objects.checkFromIndexSize(start, length, target.length);
        if (length == 0) return 0;
        if (!availableChunk()) return -1;
        int count = Math.min(length, pending.length - offset);
        System.arraycopy(pending, offset, target, start, count);
        offset += count;
        return count;
    }

    private String line() throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c; (c = reader.read()) != -1; ) {
            if (++received > MAX_CHARACTERS) throw new IOException("Anthropic 流响应过大");
            if (received == 1 && c == '\ufeff') continue;
            if (skipLineFeed) {
                skipLineFeed = false;
                if (c == '\n') continue;
            }
            if (c == '\r' || c == '\n') {
                skipLineFeed = c == '\r';
                return line.toString();
            }
            line.append((char)c);
        }
        return null;
    }

    private String nextChunk() throws Exception {
        StringBuilder data = new StringBuilder();
        String eventType = null;
        boolean hasData = false;
        while (true) {
            String line = line();
            if (line == null) throw new IOException("Anthropic 流提前结束");
            if (line.isEmpty()) break;
            if (line.startsWith("data:")) {
                if (hasData) data.append('\n');
                data.append(sseValue(line, 5));
                hasData = true;
            } else if (line.startsWith("event:")) {
                eventType = sseValue(line, 6);
            }
        }
        if (!hasData || data.toString().isBlank()) {
            if ("error".equals(eventType)) throw new IOException("Anthropic 请求失败");
            return HEARTBEAT;
        }
        JSONObject event = AnthropicProtocol.parseObject(data.toString());
        String type = event.getString("type");
        if (eventType != null && !eventType.isEmpty() && !eventType.equals(type))
            throw new IOException("Anthropic 流事件类型不一致");
        return switch (type) {
            case "message_start" -> startMessage(event.getJSONObject("message"));
            case "content_block_start" -> startBlock(event);
            case "content_block_delta" -> currentBlock(event).delta(event.getJSONObject("delta"));
            case "content_block_stop" -> stopBlock(event);
            case "message_delta" -> updateMessage(event);
            case "message_stop" -> finishMessage();
            case "error" -> throw new IOException("Anthropic 请求失败");
            default -> HEARTBEAT;
        };
    }

    private static String sseValue(String line, int start) {
        return line.substring(start < line.length() && line.charAt(start) == ' ' ? start + 1 : start);
    }

    private boolean availableChunk() throws IOException {
        if (failure != null) throw failure;
        if (offset < pending.length) return true;
        if (finished) return false;
        try {
            pending = nextChunk().getBytes(StandardCharsets.UTF_8);
            offset = 0;
            return true;
        } catch (IOException e) {
            failure = e;
            throw e;
        } catch (Exception invalid) {
            failure = new IOException("Anthropic 流格式无效或消息未完成");
            throw failure;
        }
    }

    private String startMessage(JSONObject message) throws Exception {
        if (response != null || !"message".equals(message.optString("type"))
            || !"assistant".equals(message.optString("role")) || !message.isNull("stop_reason")
            || message.getJSONArray("content").length() != 0)
            throw new IOException("Anthropic 流起始消息无效");
        response = message.put("content", content);
        id = message.optString("id", id);
        model = message.optString("model", model);
        return chunk(new JSONObject().put("role", "assistant"), null);
    }

    private String startBlock(JSONObject event) throws Exception {
        if (response == null || block != null || messageDelta || event.getInt("index") != content.length())
            throw new IOException("Anthropic 流内容块顺序无效");
        block = new Block(event.getJSONObject("content_block"));
        return block.type.equals("text") ? visibleText(block.value.toString()) : HEARTBEAT;
    }

    private Block currentBlock(JSONObject event) throws Exception {
        if (block == null || event.getInt("index") != content.length())
            throw new IOException("Anthropic 流内容块索引无效");
        return block;
    }

    private String stopBlock(JSONObject event) throws Exception {
        content.put(currentBlock(event).finish());
        block = null;
        return HEARTBEAT;
    }

    private String updateMessage(JSONObject event) throws Exception {
        if (response == null || block != null) throw new IOException("Anthropic 流消息更新顺序无效");
        messageDelta = true;
        JSONObject update = event.getJSONObject("delta");
        if (!update.isNull("stop_reason")) {
            String reason = AnthropicProtocol.nonBlank(update, "stop_reason");
            if (!response.isNull("stop_reason") && !reason.equals(response.getString("stop_reason")))
                throw new IOException("Anthropic 流结束原因不一致");
            response.put("stop_reason", reason);
        }
        JSONObject fields = event.optJSONObject("usage");
        if (fields != null) {
            JSONObject usage = response.optJSONObject("usage");
            if (usage == null) {
                usage = new JSONObject();
                response.put("usage", usage);
            }
            for (var keys = fields.keys(); keys.hasNext(); ) {
                String field = keys.next();
                if (!fields.isNull(field)) usage.put(field, fields.get(field));
            }
        }
        return HEARTBEAT;
    }

    private String finishMessage() throws Exception {
        if (response == null || !messageDelta || block != null)
            throw new IOException("Anthropic 流消息未完成");
        JSONObject message = AnthropicProtocol.message(response);
        JSONArray calls = message.optJSONArray("tool_calls");
        StringBuilder wire = new StringBuilder();
        if (calls != null) for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.getJSONObject(i).put("index", i);
            wire.append(chunk(new JSONObject().put("tool_calls", new JSONArray().put(call)), null));
        }
        wire.append(chunk(new JSONObject(), calls == null ? "stop" : "tool_calls"));
        if (response.optJSONObject("usage") != null) {
            wire.append("data: ").append(chunkHeader().put("choices", new JSONArray())
                .put("usage", AnthropicProtocol.usage(response))).append("\n\n");
        }
        wire.append("data: [DONE]\n\n");
        completed.accept(response);
        finished = true;
        return wire.toString();
    }

    private String visibleText(String text) throws Exception {
        return text.isEmpty() ? HEARTBEAT : chunk(new JSONObject().put("content", text), null);
    }

    private JSONObject chunkHeader() throws Exception {
        return new JSONObject().put("id", id).put("object", "chat.completion.chunk").put("created", 0).put("model", model);
    }

    private String chunk(JSONObject delta, String reason) throws Exception {
        JSONObject choice = new JSONObject().put("index", 0).put("delta", delta)
            .put("finish_reason", reason == null ? JSONObject.NULL : reason);
        return "data: " + chunkHeader().put("choices", new JSONArray().put(choice)) + "\n\n";
    }

    @Override public void close() throws IOException { reader.close(); }

    private final class Block {
        final JSONObject source;
        final String type;
        final StringBuilder value = new StringBuilder(), signature = new StringBuilder();

        Block(JSONObject source) throws Exception {
            this.source = source;
            type = source.getString("type");
            switch (type) {
                case "text" -> value.append(source.getString("text"));
                case "tool_use" -> source.getJSONObject("input");
                case "thinking" -> {
                    value.append(source.getString("thinking"));
                    signature.append(source.optString("signature", ""));
                }
                case "redacted_thinking" -> AnthropicProtocol.nonBlank(source, "data");
                default -> throw new IOException("Anthropic 流返回不支持的内容块");
            }
        }

        String delta(JSONObject delta) throws Exception {
            String deltaType = delta.getString("type");
            switch (type) {
                case "text" -> {
                    if (deltaType.equals("text_delta")) {
                        String text = delta.getString("text");
                        value.append(text);
                        return visibleText(text);
                    }
                    if (deltaType.equals("citations_delta")) {
                        JSONArray citations = source.optJSONArray("citations");
                        if (citations == null) {
                            citations = new JSONArray();
                            source.put("citations", citations);
                        }
                        citations.put(delta.getJSONObject("citation"));
                        return HEARTBEAT;
                    }
                }
                case "tool_use" -> {
                    if (deltaType.equals("input_json_delta")) {
                        value.append(delta.getString("partial_json"));
                        return HEARTBEAT;
                    }
                }
                case "thinking" -> {
                    if (deltaType.equals("thinking_delta")) {
                        value.append(delta.getString("thinking"));
                        return HEARTBEAT;
                    }
                    if (deltaType.equals("signature_delta")) {
                        signature.append(delta.getString("signature"));
                        return HEARTBEAT;
                    }
                }
                default -> { }
            }
            throw new IOException("Anthropic 流内容增量无效");
        }

        JSONObject finish() throws Exception {
            if (type.equals("text")) source.put("text", value.toString());
            else if (type.equals("thinking")) {
                if (signature.length() == 0) throw new IOException("Anthropic 流思考签名缺失");
                source.put("thinking", value.toString()).put("signature", signature.toString());
            } else if (type.equals("tool_use") && value.length() > 0) {
                if (source.getJSONObject("input").length() != 0) throw new IOException("Anthropic 工具参数前后不一致");
                source.put("input", AnthropicProtocol.parseObject(value.toString()));
            }
            return source;
        }
    }
}
