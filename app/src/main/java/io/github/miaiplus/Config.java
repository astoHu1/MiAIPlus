package io.github.miaiplus;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Map;

/** Credentials persist only in module-private storage; global mode shares them with the scoped native client in memory. */
public final class Config {
    public final boolean enabled, globalModel, backgroundDisplay;
    public final String endpoint, apiKey, model, protocol, planner, api, reasoning;
    public final int maxSteps;
    public Config(Context context) { this(prefs(context)); }
    Config(SharedPreferences prefs) { this(prefs.getAll()); }
    Config(Map<String, ?> p) {
        enabled = Boolean.TRUE.equals(p.get("enabled"));
        // Default off: an existing installation keeps sending every task to the physical screen.
        backgroundDisplay = Boolean.TRUE.equals(p.get("backgroundDisplay"));
        endpoint = string(p, "endpoint", "").trim();
        apiKey = string(p, "apiKey", "");
        model = string(p, "model", "").trim();
        api = ModelProtocol.resolve(string(p, "api", "auto"), model);
        reasoning = ReasoningPolicy.normalize(model, api,
            string(p, ReasoningPolicy.preferenceKey(endpoint, model, api), "default"));
        String storedProtocol = string(p, "protocol", "phone_use");
        protocol = "computer".equals(storedProtocol) ? "phone_use" : storedProtocol;
        planner = string(p, "planner", "native");
        globalModel = Boolean.TRUE.equals(p.get("globalModel"));
        maxSteps = Math.max(1, Math.min(80, p.containsKey("maxSteps") ? (Integer) p.get("maxSteps") : 30));
    }
    private static String string(Map<String, ?> values, String key, String fallback) {
        Object value = values.get(key); return value == null ? fallback : (String) value;
    }
    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("config", Context.MODE_PRIVATE); }
    public static String validateEndpoint(String endpoint) {
        return Endpoint.validate(endpoint);
    }
    public String completionsUrl() { return validateEndpoint(endpoint) + ModelProtocol.path(api); }
}
