package io.github.miaiplus;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Immutable, reduced models.dev capability snapshot. Remote data never supplies request field names. */
final class ModelsDevCatalog {
    private static final int MAX_CHARACTERS = 32 * 1024 * 1024;
    private static final int MAX_PROVIDERS = 1024;
    private static final int MAX_MODELS = 50_000;
    private static final List<String> EFFORTS = List.of("none", "minimal", "low", "medium", "high", "xhigh", "max");
    private static final ReasoningPolicy.Plan DEFAULT = new ReasoningPolicy.Plan(List.of("default"), "default", false, false);
    private static volatile ModelsDevCatalog snapshot;
    private final Map<String, Map<String, Capability>> endpoints;
    private final Map<String, Map<String, Capability>> originals;
    private final int providerCount, modelCount;

    private record Capability(ReasoningPolicy.Plan chat, ReasoningPolicy.Plan responses) {
        ReasoningPolicy.Plan forApi(String api) {
            return "chat".equals(api) ? chat : "responses".equals(api) ? responses : DEFAULT;
        }
    }
    private ModelsDevCatalog(Map<String, Map<String, Capability>> endpoints,
        Map<String, Map<String, Capability>> originals, int providers, int models) {
        this.endpoints = freeze(endpoints); this.originals = freeze(originals);
        providerCount = providers; modelCount = models;
    }
    private static Map<String, Map<String, Capability>> freeze(Map<String, Map<String, Capability>> source) {
        Map<String, Map<String, Capability>> result = new HashMap<>();
        source.forEach((key, value) -> result.put(key, Map.copyOf(value)));
        return Map.copyOf(result);
    }
    static ModelsDevCatalog current() { return snapshot; }
    static void publish(ModelsDevCatalog catalog) { snapshot = catalog; }
    static ReasoningPolicy.Plan resolve(String endpoint, String model, String api) {
        ModelsDevCatalog current = snapshot;
        return current == null ? null : current.find(endpoint, model, api);
    }
    int providerCount() { return providerCount; }
    int modelCount() { return modelCount; }

    static ModelsDevCatalog parse(String json) throws Exception {
        if (json == null || json.length() > MAX_CHARACTERS) throw new IllegalArgumentException("Catalog size");
        JSONTokener tokener = new JSONTokener(json);
        Object parsed = tokener.nextValue();
        if (!(parsed instanceof JSONObject root) || tokener.nextClean() != 0)
            throw new IllegalArgumentException("Catalog JSON");
        if (root.length() == 0 || root.length() > MAX_PROVIDERS) throw new IllegalArgumentException("Catalog providers");
        Map<String, Map<String, Capability>> endpoints = new HashMap<>(), originals = new HashMap<>();
        int models = 0;
        for (java.util.Iterator<String> providers = root.keys(); providers.hasNext();) {
            String providerId = providers.next();
            identity(providerId);
            JSONObject provider = root.getJSONObject(providerId);
            JSONObject entries = provider.getJSONObject("models");
            String base = endpoint(provider.optString("api", "openai".equals(providerId) ? "https://api.openai.com/v1" : ""));
            for (java.util.Iterator<String> ids = entries.keys(); ids.hasNext();) {
                String modelId = ids.next();
                if (++models > MAX_MODELS) throw new IllegalArgumentException("Catalog models");
                identity(modelId);
                JSONObject model = entries.getJSONObject(modelId);
                if (model.has("id") && !modelId.equals(model.getString("id")))
                    throw new IllegalArgumentException("Catalog model identity");
                JSONObject override = model.optJSONObject("provider");
                String npm = override != null && override.has("npm") ? override.getString("npm") : provider.optString("npm");
                String address = override != null && override.has("api") ? endpoint(override.getString("api")) : base;
                String shape = override != null && override.has("shape") ? override.getString("shape") : provider.optString("shape");
                Capability capability = new Capability(plan(providerId, modelId, model, npm, shape, "chat"),
                    plan(providerId, modelId, model, npm, shape, "responses"));
                // A model redirected to Anthropic (for example) must not inherit its parent's OpenAI adapter.
                if (!base.isEmpty() && !base.equals(address)) put(endpoints, base, modelId, new Capability(DEFAULT, DEFAULT));
                if (!address.isEmpty()) put(endpoints, address, modelId, capability);
                if (Set.of("openai", "deepseek", "alibaba").contains(providerId))
                    originals.computeIfAbsent(providerId, ignored -> new HashMap<>()).put(modelId, capability);
            }
        }
        if (models == 0) throw new IllegalArgumentException("Catalog empty");
        return new ModelsDevCatalog(endpoints, originals, root.length(), models);
    }
    private static void identity(String value) {
        if (value.isEmpty() || value.length() > 512 || !value.equals(value.trim()))
            throw new IllegalArgumentException("Catalog identity");
    }
    private static void put(Map<String, Map<String, Capability>> index, String endpoint, String model, Capability value) {
        Map<String, Capability> entries = index.computeIfAbsent(endpoint, ignored -> new HashMap<>());
        Capability previous = entries.putIfAbsent(model, value);
        // Several providers can share an address; inconsistent contracts must never become a union.
        if (previous != null && !previous.equals(value)) entries.put(model, new Capability(DEFAULT, DEFAULT));
    }
    ReasoningPolicy.Plan find(String endpoint, String model, String api) {
        if (model == null) return null;
        String id = model.trim();
        Map<String, Capability> exact = endpoints.get(endpoint(endpoint));
        if (exact != null) {
            Capability found = exact.get(id);
            return found == null ? null : found.forApi(api);
        }
        // Unknown compatible gateways retain established official-model semantics, never provider unions.
        String provider = null;
        for (String prefix : List.of("openai/", "deepseek/", "qwen/", "alibaba/")) {
            if (id.startsWith(prefix)) {
                provider = prefix.equals("qwen/") || prefix.equals("alibaba/") ? "alibaba" : prefix.substring(0, prefix.length() - 1);
                id = id.substring(prefix.length()); break;
            }
        }
        if (provider != null) {
            Capability found = originals.getOrDefault(provider, Map.of()).get(id);
            return found == null ? null : found.forApi(api);
        }
        Capability result = null;
        for (Map<String, Capability> entries : originals.values()) {
            Capability found = entries.get(id);
            if (found == null) continue;
            if (result != null && !result.equals(found)) return DEFAULT;
            result = found;
        }
        return result == null ? null : result.forApi(api);
    }
    private static ReasoningPolicy.Plan plan(String provider, String id, JSONObject model, String npm, String shape,
        String api) throws Exception {
        if (!(npm.equals("@ai-sdk/openai") || npm.equals("@ai-sdk/openai-compatible"))) return DEFAULT;
        if (!shape.isEmpty() && !(shape.equals("responses") && api.equals("responses"))
            && !(shape.equals("completions") && api.equals("chat"))) return DEFAULT;
        if (model.has("type") && !Set.of("text", "chat").contains(model.optString("type"))) return DEFAULT;
        if (!Boolean.TRUE.equals(model.opt("reasoning"))) return DEFAULT;
        JSONArray options = model.optJSONArray("reasoning_options");
        if (options == null) return DEFAULT;
        Set<String> supported = new LinkedHashSet<>();
        boolean toggle = false;
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option == null) continue;
            if ("toggle".equals(option.optString("type"))) toggle = true;
            if (!"effort".equals(option.optString("type"))) continue;
            JSONArray values = option.optJSONArray("values");
            if (values == null) continue;
            for (int j = 0; j < values.length(); j++) {
                Object value = values.opt(j);
                if (value instanceof String effort && EFFORTS.contains(effort)) supported.add(effort);
            }
        }
        boolean qwen = provider.equals("alibaba") && id.startsWith("qwen");
        boolean deepseek = provider.equals("deepseek") && id.startsWith("deepseek");
        if (qwen && api.equals("chat") && toggle)
            return new ReasoningPolicy.Plan(List.of("default", "off", "on"), "qwen", false, false);
        List<String> choices = new ArrayList<>(List.of("default"));
        if (deepseek && api.equals("chat") && toggle && !supported.isEmpty()) supported.add("none");
        for (String effort : EFFORTS) if (supported.contains(effort)) choices.add(effort);
        boolean openai = npm.equals("@ai-sdk/openai");
        boolean stripSampling = Boolean.FALSE.equals(model.opt("temperature")) || openai || deepseek;
        String wire = deepseek && api.equals("chat") && toggle && choices.size() > 1 ? "deepseek" : "standard";
        return new ReasoningPolicy.Plan(choices, wire, stripSampling, openai);
    }
    private static String endpoint(String value) {
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme(), host = uri.getHost();
            if (scheme == null || host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) return "";
            String path = uri.getRawPath().replaceAll("/+$", "").replaceFirst("/v1$", "");
            int port = uri.getPort();
            if (port == 443 && scheme.equalsIgnoreCase("https") || port == 80 && scheme.equalsIgnoreCase("http")) port = -1;
            return scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT)
                + (port == -1 ? "" : ":" + port) + path;
        } catch (Exception invalid) { return ""; }
    }
}
