package io.github.miaiplus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Credential-free tool definition shared by the module and the pinned host adapter. */
public record PluginToolSpec(String pluginId, String kind, String name, String sourceName,
    String displayName, String description, String inputSchema, long timeoutMs) {
    public static final int MAX_DESCRIPTION_CHARS = 8192;
    public static final int MAX_SCHEMA_CHARS = 32768;
    public static final int MAX_TOOLS_PER_PLUGIN = 64;

    public PluginToolSpec {
        requireText(pluginId, 64, "插件身份无效");
        requireText(name, 64, "工具名称无效");
        if (!name.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("工具名称无效");
        requireText(sourceName, 128, "来源工具名称无效");
        requireText(displayName, 128, "工具显示名称无效");
        if (description == null || description.length() > MAX_DESCRIPTION_CHARS)
            throw new IllegalArgumentException("工具说明过长");
        if (timeoutMs < 1000 || timeoutMs > 300000) throw new IllegalArgumentException("工具时限无效");
        if ("builtin".equals(kind)) {
            String expected = "phone_foreground".equals(pluginId) ? PhoneUseRouting.FOREGROUND_TOOL
                : "phone_background".equals(pluginId) ? PhoneUseRouting.BACKGROUND_TOOL : "";
            if (expected.isEmpty() || !name.equals(expected) || !sourceName.equals(expected))
                throw new IllegalArgumentException("内置工具身份无效");
        } else if ("mcp".equals(kind)) {
            if (!name.equals(mcpName(pluginId, sourceName))) throw new IllegalArgumentException("MCP 工具名称无效");
        } else throw new IllegalArgumentException("不支持的插件类型");
        inputSchema = validateSchema(inputSchema);
    }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("pluginId", pluginId).put("kind", kind).put("name", name)
                .put("sourceName", sourceName).put("displayName", displayName).put("description", description)
                .put("inputSchema", new JSONObject(inputSchema)).put("timeoutMs", timeoutMs);
        } catch (JSONException e) { throw new IllegalStateException("无法编码工具定义"); }
    }

    public static PluginToolSpec fromJson(JSONObject value) {
        try {
            Object schema = value.get("inputSchema");
            if (!(schema instanceof JSONObject)) throw new IllegalArgumentException("工具参数必须为对象 schema");
            Object timeout = value.get("timeoutMs");
            if (!(timeout instanceof Number number) || number.doubleValue() != number.longValue())
                throw new IllegalArgumentException("工具时限无效");
            return new PluginToolSpec(string(value, "pluginId"), string(value, "kind"), string(value, "name"),
                string(value, "sourceName"), string(value, "displayName"), string(value, "description"),
                schema.toString(), number.longValue());
        } catch (JSONException e) { throw new IllegalArgumentException("工具定义无效"); }
    }

    /** Stable within a plugin, disjoint across plugins, and safe for native/OpenAI tool names. */
    public static String mcpName(String pluginId, String sourceName) {
        if (pluginId == null || !pluginId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("MCP 插件身份无效");
        requireText(sourceName, 128, "来源工具名称无效");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sourceName.getBytes(StandardCharsets.UTF_8));
            StringBuilder suffix = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                int b = digest[i] & 0xff;
                suffix.append(Character.forDigit(b >>> 4, 16)).append(Character.forDigit(b & 15, 16));
            }
            return "miaiplus_mcp_" + pluginId.replace("-", "") + "_" + suffix;
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 不可用"); }
    }

    static String string(JSONObject value, String key) throws JSONException {
        Object field = value.get(key);
        if (!(field instanceof String text)) throw new IllegalArgumentException("插件字段类型无效");
        return text;
    }

    static void requireText(String text, int maxLength, String error) {
        if (text == null || text.isBlank() || text.length() > maxLength) throw new IllegalArgumentException(error);
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) throw new IllegalArgumentException(error);
        }
    }

    static void validateNesting(String text, int maxDepth) {
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') {
                if (++depth > maxDepth) throw new IllegalArgumentException("插件 JSON 嵌套过深");
            } else if (c == '}' || c == ']') depth--;
        }
    }

    static JSONObject parseObject(String text) throws JSONException {
        JSONTokener tokens = new JSONTokener(text);
        Object parsed = tokens.nextValue();
        if (!(parsed instanceof JSONObject object) || tokens.nextClean() != 0) throw new JSONException("Invalid object");
        return object;
    }

    private static String validateSchema(String schema) {
        Objects.requireNonNull(schema, "工具参数 schema 缺失");
        if (schema.length() > MAX_SCHEMA_CHARS) throw new IllegalArgumentException("工具参数 schema 过长");
        // Bound nesting before invoking the platform parser, including on untrusted discovery data.
        validateNesting(schema, 48);
        try {
            JSONObject object = parseObject(schema);
            if (!"object".equals(object.opt("type"))) throw new IllegalArgumentException("工具参数必须为对象 schema");
            return object.toString();
        } catch (JSONException e) { throw new IllegalArgumentException("工具参数 schema 无效"); }
    }
}
