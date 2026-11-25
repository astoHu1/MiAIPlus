package io.github.miaiplus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

/** Converts the shared Chat Completions representation to and from Anthropic Messages. */
public final class AnthropicProtocol {
    static final String HISTORY = "_anthropic_content";
    private static final int DEFAULT_MAX_TOKENS = 2048;
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    private AnthropicProtocol() {}

    public static JSONObject request(JSONObject chat) throws Exception {
        String tokenField = chat.has("max_completion_tokens") ? "max_completion_tokens" : "max_tokens";
        int maxTokens = chat.isNull(tokenField) ? DEFAULT_MAX_TOKENS : chat.getInt(tokenField);
        if (maxTokens < 1) throw new IllegalArgumentException("Anthropic max_tokens 必须为正数");
        JSONObject body = new JSONObject().put("model", chat.getString("model"))
            .put("stream", chat.optBoolean("stream")).put("max_tokens", maxTokens);
        JSONArray messages = chat.getJSONArray("messages");
        JSONArray system = new JSONArray(), turns = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            String role = message.getString("role");
            switch (role) {
                case "system", "developer" -> append(system, content(message.opt("content"), false));
                case "user" -> addTurn(turns, role, content(message.opt("content"), true));
                case "assistant" -> addTurn(turns, role, assistantContent(message));
                case "tool" -> {
                    JSONArray blocks = content(message.opt("content"), true);
                    JSONObject result = new JSONObject().put("type", "tool_result")
                        .put("tool_use_id", nonBlank(message, "tool_call_id")).put("content", blocks);
                    addTurn(turns, "user", new JSONArray().put(result));
                }
                default -> throw new IllegalArgumentException("Anthropic 不支持该消息角色");
            }
        }
        if (system.length() > 0) body.put("system", system);
        body.put("messages", turns);
        addTools(body, chat);
        return body;
    }

    private static void addTurn(JSONArray turns, String role, JSONArray blocks) throws Exception {
        if (blocks.length() == 0) return;
        JSONObject previous = turns.optJSONObject(turns.length() - 1);
        if (previous != null && role.equals(previous.getString("role"))) {
            append(previous.getJSONArray("content"), blocks);
        } else {
            turns.put(new JSONObject().put("role", role).put("content", blocks));
        }
    }

    private static void append(JSONArray target, JSONArray blocks) throws Exception {
        for (int i = 0; i < blocks.length(); i++) target.put(blocks.get(i));
    }

    private static JSONArray assistantContent(JSONObject message) throws Exception {
        JSONArray replay = message.optJSONArray(HISTORY);
        if (replay != null) return replay;
        JSONArray blocks = content(message.opt("content"), false);
        JSONArray calls = message.optJSONArray("tool_calls");
        if (calls != null) for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.getJSONObject(i), function = call.getJSONObject("function");
            if (!"function".equals(call.getString("type"))) throw new IllegalArgumentException("不支持的外部工具类型");
            blocks.put(new JSONObject().put("type", "tool_use").put("id", nonBlank(call, "id"))
                .put("name", nonBlank(function, "name"))
                .put("input", parseObject(function.getString("arguments"))));
        }
        return blocks;
    }

    private static JSONArray content(Object content, boolean images) throws Exception {
        JSONArray blocks = new JSONArray();
        if (content == null || content == JSONObject.NULL) return blocks;
        if (content instanceof String text) {
            addText(blocks, text);
            return blocks;
        }
        if (!(content instanceof JSONArray array)) throw new IllegalArgumentException("不支持的模型消息内容");
        for (int i = 0; i < array.length(); i++) {
            JSONObject part = array.getJSONObject(i);
            String type = part.getString("type");
            if ("text".equals(type)) addText(blocks, part.getString("text"));
            else if (images && "image_url".equals(type))
                blocks.put(image(part.getJSONObject("image_url").getString("url")));
            else throw new IllegalArgumentException("Anthropic 不支持该消息内容类型");
        }
        return blocks;
    }

    private static void addText(JSONArray blocks, String text) throws Exception {
        if (!text.isEmpty()) blocks.put(new JSONObject().put("type", "text").put("text", text));
    }

    private static JSONObject image(String url) throws Exception {
        JSONObject source = new JSONObject();
        if (url.startsWith("data:")) {
            int split = url.indexOf(',');
            if (split <= 5 + ";base64".length() || split == url.length() - 1
                || !url.substring(0, split).endsWith(";base64"))
                throw new IllegalArgumentException("Anthropic 图片需要 Base64 数据");
            String mime = url.substring(5, split - ";base64".length());
            if (!IMAGE_TYPES.contains(mime)) throw new IllegalArgumentException("Anthropic 不支持该图片格式");
            source.put("type", "base64").put("media_type", mime).put("data", url.substring(split + 1));
        } else {
            URI uri = URI.create(url);
            if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException("Anthropic 图片需要 HTTP(S) 地址");
            source.put("type", "url").put("url", url);
        }
        return new JSONObject().put("type", "image").put("source", source);
    }

    private static void addTools(JSONObject body, JSONObject chat) throws Exception {
        JSONArray tools = chat.optJSONArray("tools");
        if (tools == null || tools.length() == 0) return;
        JSONArray converted = new JSONArray();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.getJSONObject(i), function = tool.getJSONObject("function");
            if (!"function".equals(tool.getString("type"))) throw new IllegalArgumentException("不支持的外部工具类型");
            JSONObject schema = function.isNull("parameters")
                ? new JSONObject().put("type", "object").put("properties", new JSONObject())
                : function.getJSONObject("parameters");
            JSONObject item = new JSONObject().put("name", nonBlank(function, "name")).put("input_schema", schema);
            if (!function.isNull("description")) item.put("description", function.getString("description"));
            converted.put(item);
        }
        Object choice = chat.opt("tool_choice");
        JSONObject selected = new JSONObject();
        if (choice instanceof JSONObject named) {
            if (!"function".equals(named.getString("type"))) throw new IllegalArgumentException("不支持的外部工具类型");
            selected.put("type", "tool").put("name", nonBlank(named.getJSONObject("function"), "name"));
        } else if (choice == null || choice == JSONObject.NULL || "auto".equals(choice)) {
            selected.put("type", "auto");
        } else if ("required".equals(choice)) {
            selected.put("type", "any");
        } else if ("none".equals(choice)) {
            selected.put("type", "none");
        } else throw new IllegalArgumentException("Anthropic 不支持该工具选择");
        if (!"none".equals(choice)) selected.put("disable_parallel_tool_use", true);
        body.put("tools", converted).put("tool_choice", selected);
    }

    static JSONObject parseObject(String value) throws Exception {
        PluginToolSpec.validateNesting(value, 64);
        return PluginToolSpec.parseObject(value);
    }

    static String nonBlank(JSONObject object, String field) throws Exception {
        Object value = object.get(field);
        if (!(value instanceof String text) || text.isBlank())
            throw new IllegalArgumentException("Anthropic 响应缺少必需字段");
        return text;
    }

    public static JSONObject message(JSONObject response) throws Exception {
        if (!response.isNull("error")
            || !"message".equals(response.optString("type")) || !"assistant".equals(response.optString("role"))) {
            throw new IllegalStateException("Anthropic 返回无效消息");
        }
        JSONArray blocks = response.getJSONArray("content");
        JSONArray calls = new JSONArray();
        HashSet<String> callIds = new HashSet<>();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.getJSONObject(i);
            switch (block.getString("type")) {
                case "text" -> text.append(block.getString("text"));
                case "tool_use" -> {
                    String id = nonBlank(block, "id");
                    if (!callIds.add(id)) throw new IllegalStateException("Anthropic 工具调用 ID 重复");
                    JSONObject function = new JSONObject().put("name", nonBlank(block, "name"))
                        .put("arguments", block.getJSONObject("input").toString());
                    calls.put(new JSONObject().put("id", id).put("type", "function").put("function", function));
                }
                case "thinking" -> {
                    block.getString("thinking");
                    nonBlank(block, "signature");
                }
                case "redacted_thinking" -> nonBlank(block, "data");
                default -> throw new IllegalStateException("Anthropic 返回不支持的消息内容");
            }
        }
        String reason = response.getString("stop_reason");
        boolean hasCalls = calls.length() > 0;
        if (hasCalls ? !"tool_use".equals(reason) : !("end_turn".equals(reason) || "stop_sequence".equals(reason))) {
            throw new IllegalStateException("Anthropic 请求失败或输出不完整");
        }
        if (text.length() == 0 && !hasCalls) throw new IllegalStateException("Anthropic 没有返回文本或工具调用");
        JSONObject message = new JSONObject().put("role", "assistant").put("content", text.toString()).put(HISTORY, blocks);
        if (hasCalls) message.put("tool_calls", calls);
        return message;
    }

    public static JSONObject completion(JSONObject response) throws Exception {
        JSONObject message = message(response);
        message.remove(HISTORY);
        JSONObject choice = new JSONObject().put("index", 0).put("message", message)
            .put("finish_reason", message.has("tool_calls") ? "tool_calls" : "stop");
        JSONObject completion = new JSONObject().put("id", response.optString("id", "external-response"))
            .put("object", "chat.completion").put("choices", new JSONArray().put(choice));
        if (response.optJSONObject("usage") != null) completion.put("usage", usage(response));
        return completion;
    }

    public static JSONObject usage(JSONObject response) throws Exception {
        JSONObject usage = response.getJSONObject("usage");
        long cached = usage.optLong("cache_read_input_tokens");
        long input = usage.optLong("input_tokens") + usage.optLong("cache_creation_input_tokens") + cached;
        long output = usage.optLong("output_tokens");
        JSONObject translated = new JSONObject().put("prompt_tokens", input).put("completion_tokens", output)
            .put("total_tokens", input + output);
        if (!usage.isNull("cache_read_input_tokens"))
            translated.put("prompt_tokens_details", new JSONObject().put("cached_tokens", cached));
        return translated;
    }
}
