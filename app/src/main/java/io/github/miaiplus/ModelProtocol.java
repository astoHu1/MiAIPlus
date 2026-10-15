package io.github.miaiplus;

import org.json.JSONArray;
import org.json.JSONObject;

/** Wire protocol conversion. The agent-facing message format remains Chat Completions shaped. */
public final class ModelProtocol {
    private ModelProtocol() {}
    public static String resolve(String selection, String model) {
        if ("responses".equals(selection) || "chat".equals(selection)) return selection;
        if (!"auto".equals(selection)) throw new IllegalArgumentException("未知模型 API 协议");
        return ReasoningPolicy.canonical(model).startsWith("gpt-6") ? "responses" : "chat";
    }
    public static String path(String api) { return "responses".equals(api) ? "/responses" : "/chat/completions"; }

    public static JSONObject request(JSONObject chat) throws Exception {
        JSONObject body = new JSONObject().put("model", chat.getString("model"))
            .put("stream", chat.optBoolean("stream", false)).put("store", false)
            .put("include", new JSONArray().put("reasoning.encrypted_content"));
        if (chat.has("max_completion_tokens")) body.put("max_output_tokens", chat.get("max_completion_tokens"));
        else if (chat.has("max_tokens")) body.put("max_output_tokens", chat.get("max_tokens"));
        // Stock caps budget only visible text; reasoning models share this budget with reasoning tokens.
        if (ReasoningPolicy.canonical(chat.getString("model")).startsWith("gpt-6") && body.has("max_output_tokens"))
            body.put("max_output_tokens", Math.max(8192, body.getInt("max_output_tokens")));
        if (chat.has("reasoning_effort")) body.put("reasoning", new JSONObject().put("effort", chat.get("reasoning_effort")));
        JSONArray input = new JSONArray();
        JSONArray messages = chat.getJSONArray("messages");
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            String role = message.getString("role");
            if ("tool".equals(role)) {
                input.put(new JSONObject().put("type", "function_call_output").put("call_id", message.getString("tool_call_id"))
                    .put("output", message.optString("content", "")));
                continue;
            }
            // Replay the actual output, including opaque reasoning and assistant phase, when available.
            JSONArray raw = message.optJSONArray("_responses_output");
            if (raw != null) {
                for (int j = 0; j < raw.length(); j++) input.put(raw.get(j));
                continue;
            }
            Object content = message.opt("content");
            if (content != null && content != JSONObject.NULL && !(content instanceof String s && s.isEmpty())) {
                JSONArray parts = new JSONArray();
                if (content instanceof String text) parts.put(textPart(role, text));
                else if (content instanceof JSONArray array) {
                    for (int j = 0; j < array.length(); j++) {
                        JSONObject part = array.getJSONObject(j);
                        switch (part.getString("type")) {
                            case "text" -> parts.put(textPart(role, part.getString("text")));
                            case "image_url" -> {
                                JSONObject image = part.getJSONObject("image_url");
                                JSONObject converted = new JSONObject().put("type", "input_image").put("image_url", image.getString("url"));
                                if (image.has("detail")) converted.put("detail", image.get("detail"));
                                parts.put(converted);
                            }
                            default -> throw new IllegalArgumentException("Responses 不支持该消息内容类型");
                        }
                    }
                } else throw new IllegalArgumentException("不支持的模型消息内容");
                input.put(new JSONObject().put("type", "message").put("role", role).put("content", parts));
            }
            JSONArray calls = message.optJSONArray("tool_calls");
            if (calls != null) for (int j = 0; j < calls.length(); j++) {
                JSONObject call = calls.getJSONObject(j), fn = call.getJSONObject("function");
                input.put(new JSONObject().put("type", "function_call").put("call_id", call.getString("id"))
                    .put("name", fn.getString("name")).put("arguments", fn.getString("arguments")));
            }
        }
        body.put("input", input);
        JSONArray tools = chat.optJSONArray("tools");
        if (tools != null && tools.length() > 0) {
            JSONArray converted = new JSONArray();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject tool = tools.getJSONObject(i);
                if (!"function".equals(tool.getString("type"))) throw new IllegalArgumentException("不支持的外部工具类型");
                JSONObject fn = new JSONObject(tool.getJSONObject("function").toString());
                fn.put("type", "function");
                // Existing tool schemas contain optional properties; do not silently make them mandatory.
                if (!fn.has("strict")) fn.put("strict", false);
                converted.put(fn);
            }
            body.put("tools", converted).put("parallel_tool_calls", false);
            Object choice = chat.opt("tool_choice");
            if (choice instanceof JSONObject selected) body.put("tool_choice", new JSONObject().put("type", "function")
                .put("name", selected.getJSONObject("function").getString("name")));
            else if (choice != null) body.put("tool_choice", choice);
        }
        JSONObject format = chat.optJSONObject("response_format");
        if (format != null) {
            JSONObject converted = new JSONObject(format.toString());
            if ("json_schema".equals(format.optString("type"))) converted = new JSONObject(format.getJSONObject("json_schema").toString()).put("type", "json_schema");
            body.put("text", new JSONObject().put("format", converted));
        }
        return body;
    }
    private static JSONObject textPart(String role, String text) throws Exception {
        return new JSONObject().put("type", "assistant".equals(role) ? "output_text" : "input_text").put("text", text);
    }
    public static JSONObject message(JSONObject response) throws Exception {
        if (response.has("error") && !response.isNull("error")) throw new IllegalStateException("Responses 返回错误");
        if (!"completed".equals(response.optString("status"))) throw new IllegalStateException("Responses 未完成，已停止执行");
        JSONArray output = response.getJSONArray("output"), calls = new JSONArray();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.getJSONObject(i);
            if ("function_call".equals(item.optString("type"))) {
                calls.put(new JSONObject().put("id", item.getString("call_id")).put("type", "function")
                    .put("function", new JSONObject().put("name", item.getString("name")).put("arguments", item.getString("arguments"))));
            } else if ("message".equals(item.optString("type")) && "assistant".equals(item.optString("role"))) {
                JSONArray parts = item.getJSONArray("content");
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject part = parts.getJSONObject(j);
                    if ("refusal".equals(part.optString("type"))) throw new IllegalStateException("模型拒绝了请求，已停止执行");
                    if ("output_text".equals(part.optString("type"))) text.append(part.getString("text"));
                }
            }
        }
        if (text.length() == 0 && calls.length() == 0) throw new IllegalStateException("Responses 没有返回文本或工具调用");
        JSONObject message = new JSONObject().put("role", "assistant").put("content", text.toString()).put("_responses_output", output);
        if (calls.length() > 0) message.put("tool_calls", calls);
        return message;
    }
    public static JSONObject completion(JSONObject response) throws Exception {
        JSONObject message = message(response);
        message.remove("_responses_output");
        JSONObject completion = new JSONObject().put("id", response.optString("id", "external-response")).put("object", "chat.completion")
            .put("choices", new JSONArray().put(new JSONObject().put("index", 0).put("message", message)
                .put("finish_reason", message.has("tool_calls") ? "tool_calls" : "stop")));
        if (response.optJSONObject("usage") != null) completion.put("usage", usage(response));
        return completion;
    }
    public static JSONObject usage(JSONObject response) throws Exception {
        JSONObject usage = response.getJSONObject("usage");
        JSONObject translated = new JSONObject().put("prompt_tokens", usage.optLong("input_tokens"))
            .put("completion_tokens", usage.optLong("output_tokens"))
            .put("total_tokens", usage.optLong("total_tokens", usage.optLong("input_tokens") + usage.optLong("output_tokens")));
        if (usage.has("input_tokens_details")) translated.put("prompt_tokens_details", usage.get("input_tokens_details"));
        if (usage.has("output_tokens_details")) translated.put("completion_tokens_details", usage.get("output_tokens_details"));
        return translated;
    }
}
