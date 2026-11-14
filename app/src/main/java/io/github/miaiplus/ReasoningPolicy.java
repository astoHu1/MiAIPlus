package io.github.miaiplus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Explicit model capabilities, not a universal effort list for every compatible gateway. */
public final class ReasoningPolicy {
    private ReasoningPolicy() {}
    private static boolean named(String model, String name) {
        return model.equals(name) || model.matches(java.util.regex.Pattern.quote(name) + "-\\d{4}-\\d{2}-\\d{2}");
    }
    static String canonical(String model) {
        String value = model.trim().toLowerCase(Locale.ROOT);
        // Common catalog namespaces; arbitrary aliases remain unknown.
        for (String prefix : List.of("openai/", "qwen/", "deepseek/"))
            if (value.startsWith(prefix)) return value.substring(prefix.length());
        return value;
    }
    public static List<String> choices(String model, String api) {
        return builtin(model, api).choices();
    }
    public static Plan plan(String endpoint, String model, String api) {
        Plan remote = ModelsDevCatalog.resolve(endpoint, model, api);
        return remote != null ? remote : builtin(model, api);
    }
    public static Plan builtin(String model, String api) {
        List<String> choices = builtinChoices(model, api);
        String m = canonical(model);
        String wire = choices.size() == 1 ? "default" : qwenHybrid(m) ? "qwen"
            : named(m, "deepseek-flash") && "chat".equals(api) ? "deepseek" : "standard";
        boolean stripSampling = choices.size() > 1 && (m.startsWith("gpt-") || m.startsWith("deepseek-"));
        return new Plan(choices, wire, stripSampling, stripSampling && m.startsWith("gpt-"));
    }
    private static List<String> builtinChoices(String model, String api) {
        String m = canonical(model);
        if (named(m, "gpt-6.1-sol") || named(m, "gpt-6-astra"))
            return List.of("default", "low", "medium", "high", "xhigh", "max");
        if (named(m, "gpt-6-sol") || named(m, "gpt-6-luna") || named(m, "gpt-5.6-sol"))
            return List.of("default", "none", "low", "medium", "high", "xhigh", "max");
        if (named(m, "gpt-5.5") || named(m, "gpt-5.4") || named(m, "gpt-5.2"))
            return List.of("default", "none", "low", "medium", "high", "xhigh");
        if (named(m, "gpt-5.3-codex")) return List.of("default", "low", "medium", "high", "xhigh");
        if (named(m, "gpt-5.1")) return List.of("default", "none", "low", "medium", "high");
        if (named(m, "gpt-5")) return List.of("default", "minimal", "low", "medium", "high");
        if (named(m, "deepseek-flash")) return List.of("default", "none", "low", "high", "max");
        // enable_thinking is a Chat Completions extension, not a standard Responses field.
        if ("chat".equals(api) && qwenHybrid(m)) return List.of("default", "off", "on");
        return List.of("default");
    }
    private static boolean qwenHybrid(String model) {
        return List.of("qwen3.8-max", "qwen3.8-max-0902", "qwen3.8-flash", "qwen3.8-27b",
            "qwen3.7-max", "qwen3.7-max-2026-05-20", "qwen3.7-max-2026-06-08",
            "qwen3.7-plus", "qwen3.7-plus-2026-05-26", "qwen3.7-flash", "qwen3.7-flash-2026-07-15",
            "qwen3.6-plus", "qwen3.6-plus-2026-04-02", "qwen3.6-flash", "qwen3.6-flash-2026-04-16",
            "qwen3.6-35b-a3b", "qwen3.5-plus", "qwen3.5-plus-2026-02-15",
            "qwen3.5-flash", "qwen3.5-flash-2026-02-23", "qwen3.5-397b-a17b", "qwen3.5-122b-a10b",
            "qwen3.5-27b", "qwen3.5-35b-a3b", "qwen-plus", "qwen-plus-latest",
            "qwen-plus-2025-04-28", "qwen-flash", "qwen-flash-2025-07-28").contains(model);
    }
    public static String normalize(String model, String api, String effort) {
        return builtin(model, api).normalize(effort);
    }
    public static String label(String effort) {
        return switch (effort) {
            case "none", "off" -> "关闭思考";
            case "on" -> "开启思考";
            case "minimal" -> "极低 · minimal";
            case "low" -> "低 · low";
            case "medium" -> "中 · medium";
            case "high" -> "高 · high";
            case "xhigh" -> "超高 · xhigh";
            case "max" -> "最高 · max";
            default -> "模型默认";
        };
    }
    public static String preferenceKey(String endpoint, String model, String api) {
        try {
            String identity = endpoint.trim().replaceAll("/+$", "") + "\n" + model.trim() + "\n" + api;
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("reasoning.");
            for (byte b : hash) key.append(String.format(Locale.ROOT, "%02x", b & 255));
            return key.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    /** Applied to the final wire body; removes stock Xiaomi boolean thinking settings even for default. */
    public static void apply(JSONObject body, String model, String api, String selection) throws Exception {
        builtin(model, api).apply(body, model, api, selection);
    }
    /** Small, validated wire contract shared with the scoped host instead of the full remote catalog. */
    public record Plan(List<String> choices, String wire, boolean stripSampling, boolean completionTokens) {
        public Plan {
            if (choices == null || choices.isEmpty() || choices.size() > 10 || !"default".equals(choices.get(0))
                || choices.stream().anyMatch(java.util.Objects::isNull)
                || new java.util.HashSet<>(choices).size() != choices.size())
                throw new IllegalArgumentException("Invalid reasoning choices");
            if (wire == null || !List.of("default", "standard", "qwen", "deepseek").contains(wire))
                throw new IllegalArgumentException("Invalid reasoning wire format");
            List<String> allowed = "qwen".equals(wire) ? List.of("default", "off", "on")
                : "default".equals(wire) ? List.of("default")
                : List.of("default", "none", "minimal", "low", "medium", "high", "xhigh", "max");
            if (!allowed.containsAll(choices) || completionTokens && !("standard".equals(wire) || "default".equals(wire)))
                throw new IllegalArgumentException("Unsupported reasoning contract");
            choices = List.copyOf(choices);
        }
        public String normalize(String selection) { return selection != null && choices.contains(selection) ? selection : "default"; }
        public String encode() {
            try {
                return new JSONObject().put("version", 1).put("choices", new JSONArray(choices)).put("wire", wire)
                    .put("stripSampling", stripSampling).put("completionTokens", completionTokens).toString();
            } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        /** Invalid, unknown-version and oversized IPC descriptors safely fall back to the local contract. */
        public static Plan decode(String encoded) {
            if (encoded == null || encoded.length() > 512) return null;
            try {
                JSONTokener tokener = new JSONTokener(encoded);
                JSONObject value = new JSONObject(tokener);
                if (tokener.nextClean() != 0 || value.length() != 5 || !Integer.valueOf(1).equals(value.opt("version"))
                    || !(value.opt("choices") instanceof JSONArray choices)
                    || !(value.opt("wire") instanceof String wire)
                    || !(value.opt("stripSampling") instanceof Boolean stripSampling)
                    || !(value.opt("completionTokens") instanceof Boolean completionTokens)
                    || choices.length() > 10) return null;
                java.util.ArrayList<String> efforts = new java.util.ArrayList<>();
                for (int i = 0; i < choices.length(); i++) {
                    if (!(choices.get(i) instanceof String effort)) return null;
                    efforts.add(effort);
                }
                return new Plan(efforts, wire, stripSampling, completionTokens);
            } catch (org.json.JSONException | IllegalArgumentException invalid) { return null; }
        }
        public void apply(JSONObject body, String model, String api, String selection) throws Exception {
            body.remove("enable_thinking"); body.remove("thinking"); body.remove("thinking_budget");
            body.remove("reasoning_effort"); body.remove("reasoning");
            String effort = normalize(selection);
            if (effort.equals("default")) { /* Use the provider default. */ }
            else if ("qwen".equals(wire)) {
                if ("chat".equals(api)) body.put("enable_thinking", effort.equals("on"));
            } else if ("deepseek".equals(wire) && "chat".equals(api)) {
                body.put("thinking", new JSONObject().put("type", effort.equals("none") ? "disabled" : "enabled"));
                if (!effort.equals("none")) body.put("reasoning_effort", effort);
            } else if ("responses".equals(api)) body.put("reasoning", new JSONObject().put("effort", effort));
            else body.put("reasoning_effort", effort);
            // These capabilities belong to the matched provider contract, not a guessed model prefix.
            if (stripSampling) { body.remove("temperature"); body.remove("top_p"); }
            if (completionTokens && "chat".equals(api) && body.has("max_tokens")) {
                body.put("max_completion_tokens", Math.max(8192, body.getInt("max_tokens")));
                body.remove("max_tokens");
            }
        }
    }
}
