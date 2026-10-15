package io.github.miaiplus;

import android.content.Context;
import android.content.SharedPreferences;

/** Credentials persist only in module-private storage; global mode shares them with the scoped native client in memory. */
public final class Config {
    public final boolean enabled, globalModel;
    public final String endpoint, apiKey, model, protocol, planner;
    public final int maxSteps;
    public Config(Context context) {
        SharedPreferences p = prefs(context);
        enabled = p.getBoolean("enabled", false);
        endpoint = p.getString("endpoint", "").trim();
        apiKey = p.getString("apiKey", "");
        model = p.getString("model", "").trim();
        protocol = p.getString("protocol", "computer");
        planner = p.getString("planner", "native");
        globalModel = p.getBoolean("globalModel", false);
        maxSteps = Math.max(1, Math.min(80, p.getInt("maxSteps", 30)));
    }
    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("config", Context.MODE_PRIVATE); }
    public static String validateEndpoint(String endpoint) {
        return Endpoint.validate(endpoint);
    }
    public String completionsUrl() { return validateEndpoint(endpoint) + "/chat/completions"; }
}
