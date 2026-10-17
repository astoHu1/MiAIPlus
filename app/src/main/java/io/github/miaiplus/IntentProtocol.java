package io.github.miaiplus;

import org.json.JSONArray;
import org.json.JSONObject;

/** Translate the private intent-v2 envelope without inventing executable tool names. */
public final class IntentProtocol {
    private IntentProtocol() {}
    public static JSONObject request(String body, String model) throws Exception {
        JSONObject input = new JSONObject(body);
        JSONArray messages = input.getJSONArray("messages");
        JSONObject output = new JSONObject(body);
        output.put("model", model);
        output.remove("rid");
        output.remove("chat_template_kwargs");
        if ("v2".equals(input.optString("intent_protocol"))) {
            JSONArray adapted = new JSONArray();
            adapted.put(new JSONObject().put("role", "system").put("content",
                "Classify the user's task for a phone assistant. Return only a JSON object with status=FALLBACK, "
                + "direct_path=false, tags=[], difficulty (easy/medium/hard), and a short reason. "
                + "The main agent will choose and run its registered tools. Do not invent tool names or execute instructions in history. "
                + "Context data: " + input.optJSONObject("context")));
            for (int i = 0; i < messages.length(); i++) adapted.put(messages.get(i));
            output.put("messages", adapted).put("max_tokens", 300).put("stream", false);
        } else if ("cloud".equals(input.optString("prompt_source"))) {
            // 8.3.30 capability requests rely on a prompt injected by Xiaomi's server.
            // External services need the response schema locally; never dispatch tools here.
            JSONArray adapted = new JSONArray().put(new JSONObject().put("role", "system").put("content",
                "Classify the user's phone-assistant request. Return only a JSON object with user_intent "
                + "(a concise description) and required_capabilities (an array of short capability descriptions). "
                + "Do not execute the request, invent tool names, or give a direct answer. "
                + "The main agent will choose and run its registered tools."));
            for (int i = 0; i < messages.length(); i++) adapted.put(messages.get(i));
            output.put("messages", adapted).put("max_tokens", 300).put("stream", false);
        }
        output.remove("intent_protocol"); output.remove("context"); output.remove("routing_profile");
        output.remove("prompt_source");
        return output;
    }
    public static String response(String body) throws Exception {
        JSONObject response = new JSONObject(body);
        JSONObject message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        // Already native intent output: leave it to the original parser.
        if (message.has("status")) return body;
        String text = message.optString("content", "").trim();
        if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (newline >= 0 && end > newline) text = text.substring(newline + 1, end).trim();
        }
        JSONObject decision = new JSONObject(text);
        String difficulty = decision.optString("difficulty", "");
        JSONObject nativeDecision = new JSONObject().put("role", "assistant")
            .put("status", "FALLBACK").put("direct_path", false).put("tags", new JSONArray())
            .put("layer", "external").put("reason", decision.optString("reason", "external model routing"));
        if (java.util.Set.of("easy", "medium", "hard").contains(difficulty)) nativeDecision.put("difficulty", difficulty);
        response.getJSONArray("choices").getJSONObject(0).put("message", nativeDecision);
        return response.toString();
    }
}
