package io.github.miaiplus;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Complete, atomic plugin snapshots; endpoint credentials never form part of the host catalog. */
public final class PluginSettings {
    public static final String PREFERENCE_KEY = "pluginSettingsV1";
    public static final String MCP_PROTOCOL_VERSION = "2025-11-25";
    public static final List<String> MCP_PROTOCOL_VERSIONS = List.of(MCP_PROTOCOL_VERSION, "2025-06-18");
    public static final String FOREGROUND_ID = "phone_foreground";
    public static final String BACKGROUND_ID = "phone_background";
    public static final int MAX_MCP_PLUGINS = 32;
    private static final int MAX_STORAGE_CHARS = 1048576;
    private static final String DEFAULT_REVISION = "00000000-0000-0000-0000-000000000001";
    private static final Object LOCK = new Object();

    private PluginSettings() { }

    public static boolean supportsMcpProtocol(String protocolVersion) {
        return protocolVersion != null && MCP_PROTOCOL_VERSIONS.contains(protocolVersion);
    }

    public record Entry(String id, String kind, String label, boolean enabled, String endpoint,
        String bearerToken, String protocolVersion, List<PluginToolSpec> tools) {
        public Entry {
            PluginToolSpec.requireText(label, 80, "请填写不超过 80 字符的插件名称");
            if (endpoint == null || bearerToken == null || protocolVersion == null || tools == null)
                throw new IllegalArgumentException("插件配置不完整");
            tools = List.copyOf(tools);
            if ("builtin".equals(kind)) {
                if ((!FOREGROUND_ID.equals(id) && !BACKGROUND_ID.equals(id)) || !endpoint.isEmpty()
                    || !bearerToken.isEmpty() || !protocolVersion.isEmpty() || !tools.isEmpty())
                    throw new IllegalArgumentException("内置插件配置无效");
            } else if ("mcp".equals(kind)) {
                PluginToolSpec.mcpName(id, "identity_check");
                endpoint = validateEndpoint(endpoint);
                validateToken(bearerToken);
                if (!supportsMcpProtocol(protocolVersion)) throw new IllegalArgumentException("不支持的 MCP 协议版本");
                validateTools(id, tools);
            } else throw new IllegalArgumentException("不支持的插件类型");
        }

        @Override public String toString() {
            return "Entry[id=" + id + ", kind=" + kind + ", label=" + label + ", enabled=" + enabled
                + ", tools=" + tools.size() + "]";
        }
    }

    public record Snapshot(String revision, List<Entry> entries) {
        public Snapshot {
            if (revision == null || !revision.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw new IllegalArgumentException("插件配置版本无效");
            entries = List.copyOf(entries);
            if (entries.size() > MAX_MCP_PLUGINS + 2) throw new IllegalArgumentException("插件数量超过限制");
            HashSet<String> ids = new HashSet<>(), names = new HashSet<>();
            for (Entry entry : entries) {
                if (!ids.add(entry.id())) throw new IllegalArgumentException("插件身份重复");
                for (PluginToolSpec tool : entry.tools()) {
                    if (!names.add(tool.name())) throw new IllegalArgumentException("工具名称重复");
                }
            }
            if (!ids.contains(FOREGROUND_ID) || !ids.contains(BACKGROUND_ID))
                throw new IllegalArgumentException("内置插件缺失");
        }

        public Entry find(String id) {
            for (Entry entry : entries) if (entry.id().equals(id)) return entry;
            throw new IllegalArgumentException("插件不存在或已删除");
        }
    }

    public static Snapshot load(Context context) { return load(Config.prefs(context)); }

    public static Snapshot load(SharedPreferences prefs) {
        synchronized (LOCK) {
            Map<String, ?> values = prefs.getAll();
            if (!values.containsKey(PREFERENCE_KEY)) return defaults();
            Object raw = values.get(PREFERENCE_KEY);
            if (!(raw instanceof String encoded) || encoded.length() > MAX_STORAGE_CHARS)
                throw unreadable();
            try {
                PluginToolSpec.validateNesting(encoded, 56);
                JSONObject root = PluginToolSpec.parseObject(encoded);
                if (!(root.get("version") instanceof Number version) || version.doubleValue() != 1)
                    throw new IllegalArgumentException("不支持的插件配置版本");
                JSONArray plugins = root.getJSONArray("plugins");
                if (plugins.length() > MAX_MCP_PLUGINS + 2) throw new IllegalArgumentException("插件数量超过限制");
                ArrayList<Entry> entries = new ArrayList<>();
                for (int i = 0; i < plugins.length(); i++) {
                    JSONObject plugin = plugins.getJSONObject(i);
                    if (!(plugin.get("enabled") instanceof Boolean enabled)) throw new IllegalArgumentException("插件开关无效");
                    JSONArray toolArray = plugin.getJSONArray("tools");
                    if (toolArray.length() > PluginToolSpec.MAX_TOOLS_PER_PLUGIN) throw new IllegalArgumentException("工具数量超过限制");
                    ArrayList<PluginToolSpec> tools = new ArrayList<>();
                    for (int j = 0; j < toolArray.length(); j++) tools.add(PluginToolSpec.fromJson(toolArray.getJSONObject(j)));
                    entries.add(new Entry(PluginToolSpec.string(plugin, "id"), PluginToolSpec.string(plugin, "kind"),
                        PluginToolSpec.string(plugin, "label"), enabled, PluginToolSpec.string(plugin, "endpoint"),
                        PluginToolSpec.string(plugin, "bearerToken"), PluginToolSpec.string(plugin, "protocolVersion"), tools));
                }
                return new Snapshot(PluginToolSpec.string(root, "revision"), entries);
            } catch (JSONException | IllegalArgumentException e) { throw unreadable(); }
        }
    }

    public static Snapshot setEnabled(Context context, String id, boolean enabled) {
        return setEnabled(Config.prefs(context), id, enabled);
    }

    public static Snapshot setEnabled(SharedPreferences prefs, String id, boolean enabled) {
        synchronized (LOCK) {
            Snapshot previous = load(prefs);
            Entry entry = previous.find(id);
            if (entry.enabled() == enabled) return previous;
            return replace(prefs, previous, new Entry(entry.id(), entry.kind(), entry.label(), enabled,
                entry.endpoint(), entry.bearerToken(), entry.protocolVersion(), entry.tools()));
        }
    }

    public static Snapshot saveMcp(Context context, String idOrEmpty, String label, String endpoint,
        String bearerToken, String protocolVersion) {
        return saveMcp(Config.prefs(context), idOrEmpty, label, endpoint, bearerToken, protocolVersion);
    }

    public static Snapshot saveMcp(SharedPreferences prefs, String idOrEmpty, String label, String endpoint,
        String bearerToken, String protocolVersion) {
        synchronized (LOCK) {
            Snapshot previous = load(prefs);
            boolean creating = idOrEmpty == null || idOrEmpty.isEmpty();
            Entry old = creating ? null : requireMcp(previous.find(idOrEmpty));
            String id = creating ? UUID.randomUUID().toString() : old.id();
            String normalizedEndpoint = validateEndpoint(endpoint);
            String normalizedLabel = label == null ? "" : label.trim();
            boolean changedConnection = old == null || !old.endpoint().equals(normalizedEndpoint)
                || !old.bearerToken().equals(bearerToken) || !old.protocolVersion().equals(protocolVersion);
            Entry next = new Entry(id, "mcp", normalizedLabel, old != null && old.enabled(), normalizedEndpoint,
                bearerToken, protocolVersion, changedConnection ? List.of() : old.tools());
            if (next.equals(old)) return previous;
            return replace(prefs, previous, next);
        }
    }

    public static Snapshot deleteMcp(Context context, String id) { return deleteMcp(Config.prefs(context), id); }

    public static Snapshot deleteMcp(SharedPreferences prefs, String id) {
        synchronized (LOCK) {
            Snapshot previous = load(prefs);
            requireMcp(previous.find(id));
            ArrayList<Entry> next = new ArrayList<>(previous.entries());
            next.removeIf(entry -> entry.id().equals(id));
            return commit(prefs, new Snapshot(UUID.randomUUID().toString(), next));
        }
    }

    /** Only the exact snapshot used to discover these tools may commit their manifest. */
    public static Snapshot updateTools(Context context, String id, String expectedRevision, List<PluginToolSpec> tools) {
        return updateTools(Config.prefs(context), id, expectedRevision, tools);
    }

    public static Snapshot updateTools(SharedPreferences prefs, String id, String expectedRevision, List<PluginToolSpec> tools) {
        synchronized (LOCK) {
            Snapshot previous = load(prefs);
            if (!previous.revision().equals(expectedRevision)) throw new IllegalStateException("插件配置已更改，请重新刷新工具");
            Entry entry = requireMcp(previous.find(id));
            Entry next = new Entry(entry.id(), entry.kind(), entry.label(), entry.enabled(), entry.endpoint(),
                entry.bearerToken(), entry.protocolVersion(), tools);
            if (next.equals(entry)) return previous;
            return replace(prefs, previous, next);
        }
    }

    public static String validateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > 2048)
            throw new IllegalArgumentException("请填写 MCP 的 HTTP(S) 完整端点");
        try {
            URI uri = new URI(endpoint.trim());
            if ((!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getHost().isBlank() || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getRawQuery() != null || uri.getPort() < -1 || uri.getPort() > 65535 || uri.getPort() == 0)
                throw new IllegalArgumentException("MCP 端点须为 HTTP(S)，不能含用户信息、查询参数或片段");
            String exact = uri.toASCIIString();
            try { Endpoint.validate(exact); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("公网 MCP 请使用 HTTPS；HTTP 仅限本机或数字局域网地址"); }
            return exact;
        } catch (URISyntaxException e) { throw new IllegalArgumentException("MCP 端点格式无效"); }
    }

    private static Snapshot defaults() {
        return new Snapshot(DEFAULT_REVISION, List.of(
            new Entry(FOREGROUND_ID, "builtin", "前台 Phone Use", true, "", "", "", List.of()),
            new Entry(BACKGROUND_ID, "builtin", "后台 Phone Use", true, "", "", "", List.of())));
    }

    private static Entry requireMcp(Entry entry) {
        if (!"mcp".equals(entry.kind())) throw new IllegalArgumentException("内置插件只能启停");
        return entry;
    }

    private static void validateToken(String token) {
        if (token.length() > 4096) throw new IllegalArgumentException("Bearer Token 过长");
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c <= 32 || c >= 127) throw new IllegalArgumentException("Bearer Token 含无效字符");
        }
    }

    private static void validateTools(String pluginId, List<PluginToolSpec> tools) {
        if (tools.size() > PluginToolSpec.MAX_TOOLS_PER_PLUGIN) throw new IllegalArgumentException("工具数量超过限制");
        HashSet<String> names = new HashSet<>();
        for (PluginToolSpec tool : tools) {
            if (!"mcp".equals(tool.kind()) || !pluginId.equals(tool.pluginId()))
                throw new IllegalArgumentException("工具与插件身份不匹配");
            if (!names.add(tool.name())) throw new IllegalArgumentException("工具名称重复");
        }
    }

    private static Snapshot replace(SharedPreferences prefs, Snapshot previous, Entry entry) {
        ArrayList<Entry> next = new ArrayList<>(previous.entries());
        int index = -1;
        for (int i = 0; i < next.size(); i++) if (next.get(i).id().equals(entry.id())) { index = i; break; }
        if (index < 0) next.add(entry); else next.set(index, entry);
        return commit(prefs, new Snapshot(UUID.randomUUID().toString(), next));
    }

    private static Snapshot commit(SharedPreferences prefs, Snapshot snapshot) {
        // Validate the final combination while the mutation lock is still held. Inactive
        // manifests may be retained, but enabling them must never break the host catalog.
        // Reserve both first-party definitions even if legacy Phone Use is currently off.
        ArrayList<PluginToolSpec> activeTools = new ArrayList<>(PhoneUsePlugin.specs());
        for (Entry entry : snapshot.entries()) {
            if (entry.enabled() && "mcp".equals(entry.kind())) activeTools.addAll(entry.tools());
        }
        PluginRuntime.validateCatalogSize(activeTools);
        try {
            JSONArray entries = new JSONArray();
            for (Entry entry : snapshot.entries()) {
                JSONArray tools = new JSONArray();
                for (PluginToolSpec tool : entry.tools()) tools.put(tool.toJson());
                entries.put(new JSONObject().put("id", entry.id()).put("kind", entry.kind()).put("label", entry.label())
                    .put("enabled", entry.enabled()).put("endpoint", entry.endpoint()).put("bearerToken", entry.bearerToken())
                    .put("protocolVersion", entry.protocolVersion()).put("tools", tools));
            }
            String encoded = new JSONObject().put("version", 1).put("revision", snapshot.revision()).put("plugins", entries).toString();
            if (encoded.length() > MAX_STORAGE_CHARS) throw new IllegalArgumentException("插件目录超过存储限制");
            if (!prefs.edit().putString(PREFERENCE_KEY, encoded).commit()) throw new IllegalStateException("插件配置保存失败");
            return snapshot;
        } catch (JSONException e) { throw new IllegalStateException("无法保存插件配置"); }
    }

    private static IllegalStateException unreadable() {
        return new IllegalStateException("无法读取插件配置，请保留应用数据后重试");
    }
}
