package io.github.miaiplus;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Transport for the independent model loop. */
public final class ModelClient {
    public static String[] listModels(String endpoint, String key) throws Exception {
        return listModels(endpoint, key, "chat");
    }
    public static String[] listModels(String endpoint, String key, String api) throws Exception {
        String base = Config.validateEndpoint(endpoint);
        if ("anthropic".equals(api)) {
            String messages = ModelProtocol.url(base, api);
            base = messages.substring(0, messages.length() - "/messages".length());
        }
        HttpURLConnection c = (HttpURLConnection)new URL(base + "/models").openConnection();
        try {
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(15000);
            ModelHeaders.provider(api, key).forEach(c::setRequestProperty);
            c.setRequestProperty("User-Agent", ModelHeaders.USER_AGENT);
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 2_000_000) throw new IllegalStateException("模型列表过大");
                    out.write(buffer, 0, n);
                }
                JSONArray data = new JSONObject(out.toString(StandardCharsets.UTF_8.name())).getJSONArray("data");
                java.util.List<String> models = new java.util.ArrayList<>();
                for (int i = 0; i < data.length(); i++) models.add(data.getJSONObject(i).getString("id"));
                if (models.isEmpty()) throw new IllegalStateException("模型列表为空");
                return models.toArray(new String[0]);
            }
        } finally { c.disconnect(); }
    }
    public static JSONObject message(String role, Object content) throws Exception {
        return new JSONObject().put("role", role).put("content", content);
    }
    public static JSONObject complete(Config config, JSONArray messages, JSONArray tools, RunState run) throws Exception {
        run.check();
        JSONObject body = new JSONObject().put("model", config.model).put("messages", messages)
            .put("stream", false).put("temperature", 0).put("max_tokens", 2048);
        if (tools != null) body.put("tools", tools).put("tool_choice", "required").put("parallel_tool_calls", false);
        if ("responses".equals(config.api)) body = ModelProtocol.request(body.put("max_tokens", 16384));
        else if ("anthropic".equals(config.api)) body = AnthropicProtocol.request(body);
        config.reasoningPolicy.apply(body, config.model, config.api, config.reasoning);
        HttpURLConnection c = (HttpURLConnection) new URL(config.completionsUrl()).openConnection();
        run.connection = c;
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10_000); c.setReadTimeout((int)Math.min(60_000, Math.max(1, run.deadline - android.os.SystemClock.elapsedRealtime())));
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("User-Agent", ModelHeaders.USER_AGENT);
            c.setRequestProperty(ModelHeaders.SESSION, ModelHeaders.identifier("run:" + run.id));
            ModelHeaders.provider(config.api, config.apiKey).forEach(c::setRequestProperty);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            PhoneUseLog.request(body, config.api, bytes.length);
            c.setFixedLengthStreamingMode(bytes.length);
            try (var out = c.getOutputStream()) { out.write(bytes); }
            int code = c.getResponseCode();
            PhoneUseLog.event("model_http_response", "code", code);
            // Do not echo server bodies: these can include credentials, screenshots, or proxy HTML.
            if (code < 200 || code >= 300) throw new IllegalStateException("模型接口 HTTP " + code);
            String response;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) {
                    run.check();
                    if (out.size() + n > 2_000_000) throw new IllegalStateException("模型响应过大");
                    out.write(buf, 0, n);
                }
                response = out.toString(StandardCharsets.UTF_8.name());
            }
            run.check();
            JSONObject result = new JSONObject(response);
            return switch (config.api) {
                case "responses" -> ModelProtocol.message(result);
                case "anthropic" -> AnthropicProtocol.message(result);
                default -> result.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            };
        } finally { run.connection = null; c.disconnect(); }
    }
}
