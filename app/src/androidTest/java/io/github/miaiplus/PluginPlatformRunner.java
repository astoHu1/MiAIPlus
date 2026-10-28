package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real Android HTTP/Provider checks against the loopback-only synthetic MCP fixture. */
public final class PluginPlatformRunner extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) {
        this.arguments = arguments == null ? new Bundle() : new Bundle(arguments);
        super.onCreate(arguments); start();
    }

    @Override public void onStart() { run(this, arguments); }

    static void run(Instrumentation test, Bundle arguments) { new Probe(test, arguments).run(); }

    private static final class Probe {
        private final Instrumentation test;
        private final Bundle arguments;
        private final Context context;
        private final List<String> jobs = new ArrayList<>();
        private final String endpoint, token, nonce;
        private String stage = "preflight";
        private PluginSettings.Entry fixture;

        Probe(Instrumentation test, Bundle arguments) {
            this.test = test; this.arguments = arguments == null ? new Bundle() : arguments;
            context = test.getTargetContext();
            endpoint = this.arguments.getString("mcp_endpoint", "http://127.0.0.1:18766/mcp");
            token = this.arguments.getString("mcp_token", "plugin-device-fixture-token");
            nonce = this.arguments.getString("plugin_nonce", UUID.randomUUID().toString());
        }

        void run() {
            SharedPreferences prefs = Config.prefs(context);
            Map<String, ?> saved = new LinkedHashMap<>(prefs.getAll());
            Bundle report = new Bundle();
            int code = Activity.RESULT_CANCELED;
            boolean changed = false;
            try {
                check(!"io.github.miaiplus".equals(context.getPackageName()), "Refusing to change the mainline package");
                check(context.getPackageName().equals(BuildConfig.APPLICATION_ID), "Target package does not match this build");
                check((BuildConfig.APPLICATION_ID + ".bridge").equals(BridgeProvider.URI.getAuthority()), "Bridge belongs to another package");
                check(nonce.matches("[a-f0-9-]{36}"), "Invalid fixture nonce");
                URI url = URI.create(endpoint);
                check("http".equals(url.getScheme()) && "127.0.0.1".equals(url.getHost()) && "/mcp".equals(url.getPath())
                    && url.getPort() > 0 && url.getQuery() == null && url.getFragment() == null && url.getUserInfo() == null,
                    "This runner only accepts the owned loopback MCP fixture");
                JSONObject health = fixtureJson("/health");
                check("miaiplus-plugin-platform-fixture-v1".equals(health.optString("fixture")), "Unexpected MCP test server");
                check(PluginSettings.MCP_PROTOCOL_VERSION.equals(health.optString("protocolVersion")), "Fixture protocol mismatch");
                Bundle screenBefore = call("screen_state", null, null);
                check(!screenBefore.getBoolean("active") && !NativeSession.hasPendingOperation(), "Phone Use is already busy");
                check(!call("plugin_busy", null, null).getBoolean("active"), "This package already has remote work");

                stage = "isolated_configuration";
                changed = true;
                check(prefs.edit().clear().putBoolean("enabled", true).putBoolean("globalModel", false)
                    .putString("planner", "native").putBoolean("backgroundDisplay", false).putInt("maxSteps", 8).commit(),
                    "Could not prepare isolated fixture preferences");
                signal("PLUGIN_PLATFORM_STAGE isolated_package package=" + BuildConfig.APPLICATION_ID);
                PluginSettings.Snapshot added = PluginSettings.saveMcp(context, "", "设备验证 MCP", endpoint,
                    token, PluginSettings.MCP_PROTOCOL_VERSION);
                fixture = added.entries().get(added.entries().size() - 1);
                check(!fixture.enabled() && fixture.tools().isEmpty(), "New MCP fixture was enabled or discovered implicitly");
                check(new JSONArray(catalog().getString("tools")).length() == 2, "Initial catalog is not the two builtin plugins");

                stage = "http_discovery";
                PluginSettings.Snapshot discovered = PluginRuntime.refresh(context, fixture.id(), added.revision());
                fixture = discovered.find(fixture.id());
                check(fixture.tools().size() == 3 && fixture.tools().stream().anyMatch(tool -> "echo".equals(tool.sourceName()))
                    && fixture.tools().stream().anyMatch(tool -> "add".equals(tool.sourceName()))
                    && fixture.tools().stream().anyMatch(tool -> "delayed".equals(tool.sourceName())), "Paginated discovery lost fixture tools");
                check(!fixture.enabled(), "Discovery silently enabled the MCP fixture");
                try (McpClient client = new McpClient(fixture)) {
                    String sum = client.callTool("add", new JSONObject().put("a", 2).put("b", 5));
                    check(sum.contains("sum=7") && sum.contains("\"sum\":7"), "Real Android MCP structured addition failed");
                }
                PluginSettings.setEnabled(context, fixture.id(), true);
                Bundle enabled = catalog();
                JSONArray tools = new JSONArray(enabled.getString("tools"));
                check(tools.length() == 5, "The shared catalog does not contain builtin and remote tools together");
                check(hasTool(tools, PhoneUseRouting.FOREGROUND_TOOL) && hasTool(tools, PhoneUseRouting.BACKGROUND_TOOL)
                    && hasTool(tools, tool("echo").name()), "A plugin is missing from the shared catalog");
                check(!enabled.toString().contains(token) && !enabled.toString().contains(endpoint), "Catalog exposed private connection data");
                signal("PLUGIN_PLATFORM_STAGE http_discovery tools=5 paginated=true structured_add=true");

                stage = "provider_call";
                Bundle echo = await(start("echo", new JSONObject().put("text", nonce)), 20_000);
                check("done".equals(echo.getString("status")) && echo.getString("result", "").contains(nonce), "Provider echo did not complete");
                check(!echo.toString().contains(token), "Provider result exposed the fixture token");
                Bundle oldRequest = request("echo", new JSONObject().put("text", nonce));
                int echoed = fixtureJson("/stats").getJSONObject("calls").optInt("echo");
                PluginSettings.setEnabled(context, fixture.id(), false);
                rejected(call("plugin_start", null, oldRequest));
                rejected(call("plugin_start", null, request("echo", new JSONObject().put("text", nonce))));
                PluginSettings.setEnabled(context, fixture.id(), true);
                rejected(call("plugin_start", null, oldRequest));
                check(fixtureJson("/stats").getJSONObject("calls").optInt("echo") == echoed,
                    "Rejected calls reached the remote fixture");
                PluginSettings.setEnabled(context, PluginSettings.FOREGROUND_ID, false);
                JSONArray backgroundAndMcp = new JSONArray(catalog().getString("tools"));
                check(!hasTool(backgroundAndMcp, PhoneUseRouting.FOREGROUND_TOOL)
                    && hasTool(backgroundAndMcp, PhoneUseRouting.BACKGROUND_TOOL)
                    && hasTool(backgroundAndMcp, tool("echo").name()), "Builtin plugin toggle affected its peers");
                PluginSettings.setEnabled(context, PluginSettings.FOREGROUND_ID, true);
                prefs.edit().putBoolean("enabled", false).putString("planner", "external").commit();
                check(new JSONArray(catalog().getString("tools")).length() == 3, "Legacy Phone Use settings hid MCP tools");
                check("done".equals(await(start("echo", new JSONObject().put("text", nonce)), 20_000).getString("status")),
                    "MCP did not execute independently of Phone Use");
                prefs.edit().putBoolean("enabled", true).putString("planner", "native").commit();
                signal("PLUGIN_PLATFORM_STAGE provider_call toggle=true stale_revision=true independent=true");

                stage = "remote_cancellation";
                int delayedBefore = fixtureJson("/stats").getJSONObject("calls").optInt("delayed");
                String delayed = start("delayed", new JSONObject().put("text", nonce).put("delay_ms", 8000));
                long acceptedDeadline = SystemClock.elapsedRealtime() + 10_000;
                while (fixtureJson("/stats").getJSONObject("calls").optInt("delayed") <= delayedBefore
                    && SystemClock.elapsedRealtime() < acceptedDeadline) Thread.sleep(100);
                check(fixtureJson("/stats").getJSONObject("calls").optInt("delayed") > delayedBefore,
                    "Delayed request never reached the owned server");
                check(call("plugin_cancel", delayed, null).getBoolean("accepted"), "Provider cancellation was rejected");
                check("error".equals(await(delayed, 20_000).getString("status")), "Cancelled remote call was reported as success");
                waitIdle(20_000);
                String afterCancel = start("echo", new JSONObject().put("text", nonce));
                call("plugin_cancel", delayed, null);
                check("done".equals(await(afterCancel, 20_000).getString("status")), "Old cancelled job affected a later call");
                waitIdle(20_000);
                Bundle screenAfter = call("screen_state", null, null);
                for (String key : List.of("screen_task", "native_session", "result"))
                    check(java.util.Objects.equals(screenBefore.getString(key), screenAfter.getString(key)), "MCP changed screen-task identity or data");
                check(screenBefore.getBoolean("active") == screenAfter.getBoolean("active")
                    && screenBefore.getBoolean("completed") == screenAfter.getBoolean("completed"), "MCP changed screen-task lifecycle");
                signal("PLUGIN_PLATFORM_MODULE_PASS catalog=shared http=real_android cancel=local_verified screen_task=unchanged");

                if ("true".equals(arguments.getString("plugin_host"))) hostStage(prefs);
                report.putString("stream", "PASS: Plugin platform module HTTP/Provider checks"
                    + ("true".equals(arguments.getString("plugin_host")) ? " and native host probe" : " (native host probe not requested)") + "\n");
                code = Activity.RESULT_OK;
            } catch (Throwable failure) {
                String detail = failure instanceof AssertionError ? String.valueOf(failure.getMessage()) : failure.getClass().getSimpleName();
                detail = detail.replace(token, "[redacted]").replace(endpoint, "[endpoint]");
                report.putString("stream", "FAIL: Plugin platform stage=" + stage + " " + detail + "\n");
            } finally {
                if (changed) {
                    for (String id : jobs) try { call("plugin_cancel", id, null); } catch (RuntimeException ignored) { }
                    PluginRuntime.stopRemoteCalls();
                    try { waitIdle(20_000); }
                    catch (Throwable unfinished) {
                        code = Activity.RESULT_CANCELED;
                        report.putString("stream", report.getString("stream", "") + "FAIL: Remote fixture cleanup did not finish\n");
                    }
                    boolean restored = restore(prefs, saved);
                    if (!restored) { code = Activity.RESULT_CANCELED; report.putString("stream", report.getString("stream", "") + "FAIL: Fixture preference restore failed\n"); }
                    else report.putString("stream", report.getString("stream", "") + "PLUGIN_PLATFORM_RESTORED package=" + context.getPackageName() + "\n");
                    if (!"none".equals(arguments.getString("plugin_phone", "none"))) {
                        try {
                            test.runOnMainSync(() -> {
                                for (var task : context.getSystemService(android.app.ActivityManager.class).getAppTasks()) {
                                    var base = task.getTaskInfo().baseActivity;
                                    if (base != null && base.getPackageName().equals(BuildConfig.APPLICATION_ID)
                                        && base.getClassName().equals("io.github.miaiplus.FixtureActivity")) task.finishAndRemoveTask();
                                }
                            });
                        } catch (Throwable unfinished) {
                            code = Activity.RESULT_CANCELED;
                            report.putString("stream", report.getString("stream", "") + "FAIL: Owned fixture activity cleanup failed\n");
                        }
                    }
                }
            }
            test.finish(code, report);
        }

        private void hostStage(SharedPreferences prefs) throws Exception {
            stage = "native_host_probe";
            check(prefs.edit().remove("hook_report").commit(), "Could not clear previous fixture report");
            if (!"none".equals(arguments.getString("plugin_phone", "none"))) {
                ScreenService previousService = ScreenService.instance;
                signal("PLUGIN_PLATFORM_CONNECT_SERVICE " + nonce);
                long connectionDeadline = SystemClock.elapsedRealtime() + 15_000;
                while ((ScreenService.instance == previousService || !ScreenService.isHealthy())
                    && SystemClock.elapsedRealtime() < connectionDeadline) Thread.sleep(100);
                check(ScreenService.instance != previousService && ScreenService.isHealthy(),
                    "Owned accessibility service did not reconnect after instrumentation started");
            }
            signal("PLUGIN_PLATFORM_HOST_READY " + tool("echo").name() + " " + nonce);
            long deadline = SystemClock.elapsedRealtime() + 180_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                String result = prefs.getString("hook_report", "");
                if (result.startsWith("PLUGIN_PLATFORM_PASS") && result.contains(nonce)) {
                    signal("PLUGIN_PLATFORM_HOST_PASS " + result); return;
                }
                if (result.startsWith("PLUGIN_PLATFORM_FAIL") && result.contains(nonce))
                    throw new AssertionError("Native plugin probe failed: " + result.substring(0, Math.min(result.length(), 1000)));
                Thread.sleep(100);
            }
            throw new AssertionError("Native plugin probe handshake timed out");
        }

        private PluginToolSpec tool(String source) {
            for (PluginToolSpec tool : fixture.tools()) if (source.equals(tool.sourceName())) return tool;
            throw new AssertionError("Missing fixture tool");
        }

        private Bundle request(String source, JSONObject args) {
            Bundle request = new Bundle(); request.putString("revision", catalog().getString("revision"));
            request.putString("pluginId", fixture.id()); request.putString("toolName", tool(source).name());
            request.putString("arguments", args.toString()); return request;
        }

        private String start(String source, JSONObject args) {
            Bundle response = call("plugin_start", null, request(source, args));
            check(!response.containsKey("error") && response.getString("id") != null, "Plugin start failed");
            String id = response.getString("id"); jobs.add(id); return id;
        }

        private Bundle await(String id, long millis) throws Exception {
            long deadline = SystemClock.elapsedRealtime() + millis;
            while (SystemClock.elapsedRealtime() < deadline) {
                Bundle state = call("plugin_poll", id, null);
                if (!"running".equals(state.getString("status"))) return state;
                Thread.sleep(100);
            }
            call("plugin_cancel", id, null); throw new AssertionError("Plugin polling timed out");
        }

        private void waitIdle(long millis) throws Exception {
            long deadline = SystemClock.elapsedRealtime() + millis;
            while (call("plugin_busy", null, null).getBoolean("active") && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            check(!call("plugin_busy", null, null).getBoolean("active"), "Remote client did not release its busy state");
        }

        private Bundle catalog() { return call("plugin_catalog", null, null); }

        private Bundle call(String method, String arg, Bundle extras) {
            Bundle value = context.getContentResolver().call(BridgeProvider.URI, method, arg, extras);
            check(value != null, "Provider returned no response"); return value;
        }

        private JSONObject fixtureJson(String path) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint).resolve(path).toURL().openConnection();
            try {
                connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
                check(connection.getResponseCode() == 200, "Owned fixture status endpoint unavailable");
                try (var input = connection.getInputStream()) {
                    byte[] data = input.readNBytes(16385); check(data.length <= 16384, "Fixture status response too large");
                    return new JSONObject(new String(data, StandardCharsets.UTF_8));
                }
            } finally { connection.disconnect(); }
        }

        private void signal(String text) { Bundle status = new Bundle(); status.putString("stream", text + "\n"); test.sendStatus(1, status); }
    }

    private static boolean hasTool(JSONArray tools, String name) throws Exception {
        for (int i = 0; i < tools.length(); i++) if (name.equals(tools.getJSONObject(i).getString("name"))) return true;
        return false;
    }

    private static void rejected(Bundle result) { check(result.containsKey("error") && !result.containsKey("id"), "Disabled or stale tool was admitted"); }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static boolean restore(SharedPreferences prefs, Map<String, ?> saved) {
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, ?> value : saved.entrySet()) {
            Object item = value.getValue();
            if (item instanceof String text) editor.putString(value.getKey(), text);
            else if (item instanceof Boolean flag) editor.putBoolean(value.getKey(), flag);
            else if (item instanceof Integer number) editor.putInt(value.getKey(), number);
            else if (item instanceof Long number) editor.putLong(value.getKey(), number);
            else if (item instanceof Float number) editor.putFloat(value.getKey(), number);
            else if (item instanceof Set<?>) editor.putStringSet(value.getKey(), strings(item));
            else return false;
        }
        return editor.commit() && prefs.getAll().equals(saved);
    }

    @SuppressWarnings("unchecked") private static Set<String> strings(Object value) { return (Set<String>) value; }
}
