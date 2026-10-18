package io.github.miaiplus;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Map;

/** Credentials persist only in module-private storage; global mode shares them with the scoped native client in memory. */
public final class Config {
    public static final int MAX_PHONE_USE_STEPS = 200;
    public static final int DEFAULT_CONVERSATION_TIMEOUT_MINUTES = 15;
    public static final int MAX_CONVERSATION_TIMEOUT_MINUTES = 1440;
    public static final int MIN_DISPLAY_SIZE = 320;
    public static final int MAX_DISPLAY_SIZE = 4096;
    public static final int MIN_DISPLAY_DENSITY = 80;
    public static final int MAX_DISPLAY_DENSITY = 1000;
    public static final int DEFAULT_DISPLAY_WIDTH = 1080;
    public static final int DEFAULT_DISPLAY_HEIGHT = 1920;
    public static final int DEFAULT_DISPLAY_DENSITY = 320;
    public final boolean enabled, globalModel, backgroundDisplay;
    public final String endpoint, apiKey, model, protocol, planner, api, reasoning;
    public final int maxSteps;
    public final int conversationTimeoutMinutes;
    public final int displayWidth, displayHeight, displayDensity;
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
        Object conversationTimeout = p.get("conversationTimeoutMinutes");
        conversationTimeoutMinutes = conversationTimeout instanceof Number
            ? Math.max(0, Math.min(MAX_CONVERSATION_TIMEOUT_MINUTES, ((Number) conversationTimeout).intValue()))
            : DEFAULT_CONVERSATION_TIMEOUT_MINUTES;
        displayWidth = boundedInt(p, "displayWidth", DEFAULT_DISPLAY_WIDTH, MIN_DISPLAY_SIZE, MAX_DISPLAY_SIZE);
        displayHeight = boundedInt(p, "displayHeight", DEFAULT_DISPLAY_HEIGHT, MIN_DISPLAY_SIZE, MAX_DISPLAY_SIZE);
        displayDensity = boundedInt(p, "displayDensity", DEFAULT_DISPLAY_DENSITY,
            MIN_DISPLAY_DENSITY, MAX_DISPLAY_DENSITY);
        maxSteps = Math.max(1, Math.min(MAX_PHONE_USE_STEPS, p.containsKey("maxSteps") ? (Integer) p.get("maxSteps") : 30));
    }
    private static int boundedInt(Map<String, ?> values, String key, int fallback, int min, int max) {
        Object value = values.get(key);
        if (!(value instanceof Number)) return fallback;
        return Math.max(min, Math.min(max, ((Number) value).intValue()));
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
