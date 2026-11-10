package io.github.miaiplus;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Triage facts for an exported log: versions, switches, counts and states an engineer needs before reading a
 * single record. Never configuration values: no endpoint, model name, key, plugin label, tool name or device id.
 *
 * <p>Each section is isolated. A section that cannot be read reports the exception class instead of failing the
 * export, because a broken configuration is itself the kind of fault this file exists to show.
 */
final class DiagnosticEnvironment {
    static final int SCHEMA = 2;
    static final String HOST = "com.miui.voiceassist";
    private static final int MAX_REPORT_CHARS = 512;

    private DiagnosticEnvironment() { }

    private interface Section { Object build() throws Exception; }

    static JSONObject snapshot(Context context) {
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        JSONObject result = new JSONObject();
        section(result, "app", () -> app(app));
        section(result, "device", () -> device(app));
        section(result, "host", () -> host(app));
        section(result, "hook", () -> hook(app));
        section(result, "framework", HotReload::snapshot);
        section(result, "config", () -> config(app));
        section(result, "plugins", () -> plugins(app));
        section(result, "accessibility", () -> new JSONObject().put("connected", ScreenService.isHealthy())
            .put("preflightEnabled", AccessibilityGuard.enabled(app)));
        section(result, "bridge", BridgeProvider::bridgeSnapshot);
        section(result, "process", () -> process(app));
        return result;
    }

    private static void section(JSONObject target, String key, Section section) {
        try { target.put(key, section.build()); }
        catch (Exception | LinkageError failure) {
            try { target.put(key, new JSONObject().put("error", failure.getClass().getSimpleName())); }
            catch (JSONException ignored) { /* A fixed key and a class name always encode. */ }
        }
    }

    private static JSONObject app(Context context) throws Exception {
        JSONObject app = new JSONObject().put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE).put("buildType", BuildConfig.BUILD_TYPE)
            .put("applicationId", BuildConfig.APPLICATION_ID);
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return app.put("firstInstallTime", info.firstInstallTime).put("lastUpdateTime", info.lastUpdateTime);
    }

    private static JSONObject device(Context context) throws Exception {
        Configuration configuration = context.getResources().getConfiguration();
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return new JSONObject().put("manufacturer", Build.MANUFACTURER).put("brand", Build.BRAND)
            .put("model", Build.MODEL).put("device", Build.DEVICE).put("androidApi", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
            // HyperOS/MIUI publish their own version here (for example OS3.0.x); no hidden property is read.
            .put("incremental", Build.VERSION.INCREMENTAL).put("securityPatch", Build.VERSION.SECURITY_PATCH)
            .put("locale", Locale.getDefault().toLanguageTag())
            .put("utcOffsetMinutes", TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000)
            .put("userId", Process.myUid() / 100000)
            .put("widthPixels", metrics.widthPixels).put("heightPixels", metrics.heightPixels)
            .put("densityDpi", metrics.densityDpi).put("fontScale", configuration.fontScale)
            .put("smallestWidthDp", configuration.smallestScreenWidthDp)
            .put("orientation", configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait");
    }

    private static JSONObject host(Context context) throws Exception {
        JSONObject host = new JSONObject();
        PackageInfo info;
        try { info = context.getPackageManager().getPackageInfo(HOST, 0); }
        catch (PackageManager.NameNotFoundException missing) { return host.put("installed", false); }
        return host.put("installed", true).put("versionName", info.versionName)
            .put("versionCode", info.getLongVersionCode())
            .put("profile", HostCompatibility.profile(info.versionName, info.getLongVersionCode()))
            .put("enabled", info.applicationInfo == null || info.applicationInfo.enabled)
            .put("lastUpdateTime", info.lastUpdateTime);
    }

    /** The last report the hooks sent from XiaoAi. A report is a record of the past, not proof of a live hook. */
    private static JSONObject hook(Context context) throws Exception {
        SharedPreferences prefs = Config.prefs(context);
        long now = System.currentTimeMillis();
        JSONObject hook = new JSONObject();
        String report = prefs.getString("hook_report", null);
        long reportedAt = prefs.getLong("hook_at", 0L);
        hook.put("reported", report != null && reportedAt > 0L);
        if (report != null) {
            String kind = reportKind(report);
            hook.put("reportKind", kind).put("reportChars", report.length());
            // Debug probes reuse this slot for their own results; only the hooks' fixed reports are exported.
            if (!kind.equals("other")) hook.put("report", report.substring(0, Math.min(MAX_REPORT_CHARS, report.length())));
        }
        if (reportedAt > 0L) hook.put("reportedAt", reportedAt).put("ageMs", now - reportedAt);
        String status = prefs.getString("hook_status", null);
        long statusAt = prefs.getLong("hook_status_at", 0L);
        if (status != null) {
            JSONObject value = BridgeProvider.sanitizeHookStatus(new JSONObject(status));
            hook.put("status", value).put("statusAt", statusAt).put("statusAgeMs", now - statusAt);
            if (value.has("moduleVersionCode"))
                hook.put("versionSkew", value.optLong("moduleVersionCode") != BuildConfig.VERSION_CODE);
        }
        String reload = prefs.getString("hook_reload", null);
        // A refused or failed hot reload leaves XiaoAi on its previous module code until it restarts.
        if (reload != null) hook.put("lastReload", BridgeProvider.sanitizeHookStatus(new JSONObject(reload)))
            .put("lastReloadAgeMs", now - prefs.getLong("hook_reload_at", 0L));
        long updated = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        long latest = Math.max(reportedAt, statusAt);
        // The module was replaced after XiaoAi last reported: XiaoAi may still run the previous module code.
        if (latest > 0L) hook.put("reportPredatesModuleUpdate", latest < updated);
        return hook;
    }

    static String reportKind(String report) {
        if (report.startsWith("ready:")) return "ready";
        if (report.startsWith("unsupported:")) return "unsupported";
        if (report.startsWith("hook failed")) return "rolled_back";
        if (report.startsWith("reload cleanup failed")) return "reload_cleanup_failed";
        return "other";
    }

    private static JSONObject config(Context context) throws Exception {
        Config config = new Config(context);
        return new JSONObject().put("enabled", config.enabled).put("planner", choice(config.planner, "native", "external"))
            .put("globalModel", config.globalModel).put("backgroundDisplay", config.backgroundDisplay)
            .put("permissionMode", config.phoneUsePermissionMode).put("maxSteps", config.maxSteps)
            .put("protocol", choice(config.protocol, "phone_use", "autoglm")).put("api", choice(config.api, "chat", "responses"))
            .put("conversationTimeoutMinutes", config.conversationTimeoutMinutes)
            .put("displayWidth", config.displayWidth).put("displayHeight", config.displayHeight)
            .put("displayDensity", config.displayDensity)
            .put("endpointConfigured", !config.endpoint.isEmpty()).put("modelConfigured", !config.model.isEmpty())
            .put("apiKeyConfigured", !config.apiKey.isEmpty())
            .put("reasoningCustomized", !"default".equals(config.reasoning))
            .put("detailedLog", config.phoneUseDetailedLog);
    }

    private static String choice(String value, String... allowed) {
        for (String candidate : allowed) if (candidate.equals(value)) return candidate;
        return "unknown";
    }

    private static JSONObject plugins(Context context) throws Exception {
        PluginSettings.Snapshot settings = PluginSettings.load(context);
        int remote = 0, remoteEnabled = 0, remoteTools = 0;
        boolean termuxConfigured = false, termuxEnabled = false;
        for (PluginSettings.Entry entry : settings.entries()) {
            if ("builtin".equals(entry.kind())) continue;
            if (PluginSettings.TERMUX_KIND.equals(entry.kind())) {
                termuxConfigured = true; termuxEnabled = entry.enabled();
            } else { remote++; if (entry.enabled()) remoteEnabled++; }
            if (entry.enabled()) remoteTools += entry.tools().size();
        }
        JSONObject plugins = new JSONObject()
            .put("foregroundEnabled", settings.find(PluginSettings.FOREGROUND_ID).enabled())
            .put("backgroundEnabled", settings.find(PluginSettings.BACKGROUND_ID).enabled())
            .put("mcpPlugins", remote).put("mcpEnabled", remoteEnabled).put("enabledRemoteTools", remoteTools)
            .put("termuxConfigured", termuxConfigured).put("termuxEnabled", termuxEnabled);
        // What XiaoAi would be offered right now. An empty or failing catalog explains "the model has no tool".
        try {
            JSONArray tools = new JSONArray(PluginRuntime.catalog(context).getString("tools", "[]"));
            boolean foreground = false, background = false;
            for (int i = 0; i < tools.length(); i++) {
                String name = tools.getJSONObject(i).optString("name");
                foreground |= PhoneUseRouting.FOREGROUND_TOOL.equals(name);
                background |= PhoneUseRouting.BACKGROUND_TOOL.equals(name);
            }
            plugins.put("catalogTools", tools.length()).put("catalogForeground", foreground)
                .put("catalogBackground", background);
        } catch (Exception failure) { plugins.put("catalogError", failure.getClass().getSimpleName()); }
        return plugins;
    }

    private static JSONObject process(Context context) throws Exception {
        long now = System.currentTimeMillis();
        JSONObject process = new JSONObject().put("pid", Process.myPid())
            .put("startedAt", now - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()));
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            process.put("backgroundRestricted", manager.isBackgroundRestricted());
            // A killed module process loses its in-memory tasks; the reason separates a crash from a ROM cleanup.
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(null, 0, 3);
            JSONArray recent = new JSONArray();
            for (ApplicationExitInfo exit : exits) {
                // The system's free-form description may contain exception messages, paths or user data.
                recent.put(new JSONObject().put("reason", exit.getReason()).put("status", exit.getStatus())
                    .put("importance", exit.getImportance()).put("at", exit.getTimestamp())
                    .put("ageMs", now - exit.getTimestamp()));
            }
            process.put("recentExits", recent);
        } catch (RuntimeException unavailable) { process.put("exitsError", unavailable.getClass().getSimpleName()); }
        try {
            process.put("batteryOptimizationExempt",
                context.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(context.getPackageName()));
            process.put("standbyBucket", context.getSystemService(UsageStatsManager.class).getAppStandbyBucket());
        } catch (RuntimeException unavailable) { process.put("restrictionError", unavailable.getClass().getSimpleName()); }
        return process;
    }
}
