package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real, explicitly selected upstream MCP shell checks in the non-root Termux environment. */
public final class TermuxMcpRunner {
    static void run(Instrumentation test, Bundle arguments) { new Probe(test, arguments).run(); }

    private static final class Probe {
        private final Instrumentation test;
        private final Context context;
        private final String endpoint, token, sourceTool, commandField, nonce, expectedUid, expectedHome, protocol;
        private final boolean host;
        private final List<String> jobs = new ArrayList<>();
        private PluginSettings.Entry entry;
        private PluginToolSpec shellTool;
        private String timeoutField, stage = "preflight";
        private boolean fixtureMayExist, reportChanged, hadReport, hadHookAt, hostRequested, hostExited;
        private Object savedReport, savedHookAt;

        Probe(Instrumentation test, Bundle arguments) {
            this.test = test;
            context = test.getTargetContext();
            Bundle args = arguments == null ? new Bundle() : arguments;
            endpoint = option(args, "mcp_endpoint", "endpoint", "");
            token = option(args, "mcp_token", "token", "");
            sourceTool = option(args, "mcp_source_tool", "sourceTool", "");
            commandField = option(args, "mcp_command_field", "commandField", "command");
            nonce = option(args, "plugin_nonce", "nonce", UUID.randomUUID().toString());
            expectedUid = option(args, "termux_uid", "expected_uid", "10351");
            expectedHome = args.getString("termux_home", "/data/data/com.termux/files/home");
            protocol = option(args, "mcp_protocol", "protocol", PluginSettings.MCP_PROTOCOL_VERSION);
            host = "true".equals(args.getString("termux_host"));
        }

        void run() {
            SharedPreferences prefs = Config.prefs(context);
            boolean hadKey = prefs.contains(PluginSettings.PREFERENCE_KEY);
            Object saved = prefs.getAll().get(PluginSettings.PREFERENCE_KEY);
            Bundle screenBefore = null;
            boolean changed = false, passed = false;
            Bundle report = new Bundle();
            try {
                check(!"io.github.miaiplus".equals(context.getPackageName()), "Refusing the mainline package");
                check(BuildConfig.APPLICATION_ID.equals(context.getPackageName())
                    && (BuildConfig.APPLICATION_ID + ".bridge").equals(BridgeProvider.URI.getAuthority()), "Install identity mismatch");
                check(!hadKey || saved instanceof String, "Existing plugin configuration is not a string");
                check(nonce.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"), "Invalid test nonce");
                check(expectedUid.matches("[1-9][0-9]{4,6}"), "Expected a non-root Android application UID");
                check("/data/data/com.termux/files/home".equals(expectedHome) || expectedHome.matches(
                    "/data/data/com\\.termux/files/home/\\.cache/miaiplus-mcp-service-"
                    + "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}/home"),
                    "Expected the canonical or an explicitly owned isolated Termux home");
                check(sourceTool.matches("[A-Za-z0-9_.:-]{1,128}"), "An explicit upstream source tool is required");
                check(commandField.matches("[A-Za-z_][A-Za-z0-9_]{0,79}"), "Invalid shell command field");
                URI uri = URI.create(endpoint);
                check("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() > 0
                    && uri.getQuery() == null && uri.getFragment() == null && uri.getUserInfo() == null,
                    "Only the explicitly selected Termux loopback HTTP service is accepted");
                screenBefore = call("screen_state", null, null);
                check(!screenBefore.getBoolean("active") && !NativeSession.hasPendingOperation(), "A Phone Use task is already active");
                check(!call("plugin_busy", null, null).getBoolean("active"), "Another module MCP job is already active");

                stage = "upstream_discovery";
                changed = true;
                PluginSettings.Snapshot added = PluginSettings.saveMcp(context, "", "Termux MCP 验证 " + nonce.substring(0, 8),
                    endpoint, token, protocol);
                entry = added.entries().get(added.entries().size() - 1);
                PluginSettings.Snapshot discovered = PluginRuntime.refresh(context, entry.id(), added.revision());
                entry = discovered.find(entry.id());
                for (PluginToolSpec tool : entry.tools()) if (sourceTool.equals(tool.sourceName())) shellTool = tool;
                check(shellTool != null, "Selected upstream shell tool was not discovered");
                JSONObject schema = new JSONObject(shellTool.inputSchema());
                JSONObject properties = schema.getJSONObject("properties");
                JSONObject command = properties.getJSONObject(commandField);
                check("string".equals(command.optString("type")), "Selected tool does not declare a string command parameter");
                JSONArray required = schema.optJSONArray("required");
                if (required != null) for (int index = 0; index < required.length(); index++) {
                    String name = required.getString(index);
                    check(name.equals(commandField) || name.equals("timeout") || name.equals("timeout_seconds"),
                        "Selected shell tool has another required argument");
                }
                for (String name : List.of("timeout", "timeout_seconds")) {
                    JSONObject field = properties.optJSONObject(name);
                    if (field != null && List.of("number", "integer").contains(field.optString("type"))) {
                        check(field.optDouble("minimum", 0) <= 10 && field.optDouble("maximum", 10) >= 10,
                            "Shell timeout schema does not accept the bounded 10-second test");
                        timeoutField = name;
                        break;
                    }
                }
                PluginSettings.setEnabled(context, entry.id(), true);
                entry = PluginSettings.load(context).find(entry.id());
                signal("TERMUX_MCP_STAGE discovery source=" + sourceTool + " tools=" + entry.tools().size()
                    + " schema_sha256=" + digest(shellTool.inputSchema()) + " command=string timeout="
                    + (timeoutField == null ? "upstream_default" : "10s"));

                stage = "shell_identity";
                String identity = success(command(identityGuard() + "printf 'TERMUX_IDENTITY_" + nonce
                    + "\\n'; printf 'UID=%s\\n' \"$(id -u)\"; printf 'HOME=%s\\nPREFIX=%s\\nSHELL=%s\\n' \"$HOME\" \"$PREFIX\" \"$(command -v sh)\""));
                check(identity.contains("TERMUX_IDENTITY_" + nonce) && identity.contains("UID=" + expectedUid)
                    && readable(identity).contains("HOME=" + expectedHome) && identity.contains("com.termux/files/usr"),
                    "Shell did not prove the expected Termux application identity");
                signal("TERMUX_MCP_STAGE identity expected_uid=true home=true prefix=true shell=true");

                stage = "shell_file_pipeline";
                fixtureMayExist = true;
                String input = "alpha\n中文验证\n";
                String fileCommand = identityGuard() + "umask 077; d=\"$HOME/.cache/miaiplus-mcp-" + nonce + "\"; "
                    + "mkdir -p \"$HOME/.cache\" && mkdir \"$d\" || exit 85; "
                    + "printf '%s' '" + nonce + "' > \"$d/.miaiplus-owner\" || exit 86; "
                    + "cd \"$d\" || exit 87; printf 'alpha\\n中文验证\\n' > input.txt || exit 88; "
                    + "test \"$(wc -l < input.txt | tr -d '[:space:]')\" = 2 || exit 89; "
                    + "test \"$(grep -c '中文验证' input.txt)\" = 1 || exit 90; "
                    + "test \"$(sha256sum input.txt | cut -d ' ' -f 1)\" = '" + digest(input) + "' || exit 91; "
                    + "printf 'TERMUX_PIPELINE_" + nonce + "\\n'; cat input.txt; printf 'TERMUX_READBACK_" + nonce + "\\n'";
                String files = success(command(fileCommand));
                check(files.contains("TERMUX_PIPELINE_" + nonce) && readable(files).contains("中文验证")
                    && files.contains("TERMUX_READBACK_" + nonce), "Termux file, Unicode, pipeline or digest check failed");
                signal("TERMUX_MCP_STAGE files unicode=true lines=2 grep=true sha256=true readback=true");

                stage = "shell_stderr_exit";
                String failureCommand = identityGuard() + "printf 'TERMUX_STDERR_" + nonce + "\\n' >&2; exit 7";
                boolean upstreamError = false;
                try (McpClient client = new McpClient(entry)) {
                    String result = client.callTool(sourceTool, args(failureCommand));
                    check(shellFailure(result), "Upstream result lost stderr or the nonzero exit code");
                } catch (McpClient.ToolException expected) { upstreamError = true; }
                Bundle failed = command(failureCommand);
                if ("done".equals(failed.getString("status")))
                    check(!upstreamError && shellFailure(failed.getString("result", "")), "Provider lost the nonzero shell result");
                else check(upstreamError && "error".equals(failed.getString("status")), "Shell failure became an unrelated transport failure");
                signal("TERMUX_MCP_STAGE shell_failure exit=7 semantics=" + (upstreamError
                    ? "mcp_isError_not_success stderr_payload_not_exposed" : "stderr_and_exit_preserved_in_result"));

                stage = "fixture_cleanup";
                cleanupFixture();
                if (host) hostStage(prefs);
                passed = true;
                report.putString("stream", "PASS: Termux MCP real upstream shell identity, file pipeline and nonzero-exit checks"
                    + (host ? " and actual host proxy" : "") + " nonce=" + nonce + "\n");
            } catch (Throwable failure) {
                String detail = failure instanceof AssertionError ? failure.getMessage() : failure.getClass().getSimpleName();
                report.putString("stream", "FAIL: Termux MCP stage=" + stage + " " + redact(detail) + " nonce=" + nonce + "\n");
            } finally {
                boolean hostRestoreSafe = !hostRequested;
                if (hostRequested) {
                    try {
                        awaitHostExit(prefs);
                        hostRestoreSafe = true;
                    } catch (Throwable unfinished) {
                        passed = false;
                        append(report, "FAIL: Termux host exit was not confirmed; host report restore skipped nonce=" + nonce);
                    }
                }
                if (changed) {
                    for (String id : List.copyOf(jobs)) try { call("plugin_cancel", id, null); } catch (RuntimeException ignored) { }
                    try {
                        waitIdle(70_000);
                        if (fixtureMayExist && shellTool != null) cleanupFixture();
                        waitIdle(70_000);
                    } catch (Throwable cleanupFailure) {
                        passed = false;
                        append(report, "FAIL: Termux owned shell directory or remote job cleanup needs attention nonce=" + nonce);
                    }
                    SharedPreferences.Editor editor = prefs.edit();
                    if (hadKey) editor.putString(PluginSettings.PREFERENCE_KEY, (String) saved);
                    else editor.remove(PluginSettings.PREFERENCE_KEY);
                    boolean restored = editor.commit() && prefs.contains(PluginSettings.PREFERENCE_KEY) == hadKey
                        && Objects.equals(saved, prefs.getAll().get(PluginSettings.PREFERENCE_KEY));
                    if (!restored) { passed = false; append(report, "FAIL: Termux plugin configuration restore failed"); }
                    else append(report, "TERMUX_MCP_RESTORED package=" + context.getPackageName() + " exact_plugin_key=true nonce=" + nonce);
                }
                if (reportChanged && hostRestoreSafe) {
                    SharedPreferences.Editor editor = prefs.edit();
                    if (hadReport) editor.putString("hook_report", (String) savedReport);
                    else editor.remove("hook_report");
                    if (hadHookAt) editor.putLong("hook_at", (Long) savedHookAt);
                    else editor.remove("hook_at");
                    boolean restored = editor.commit() && prefs.contains("hook_report") == hadReport
                        && Objects.equals(savedReport, prefs.getAll().get("hook_report"))
                        && prefs.contains("hook_at") == hadHookAt && Objects.equals(savedHookAt, prefs.getAll().get("hook_at"));
                    if (!restored) { passed = false; append(report, "FAIL: Termux host report restore failed"); }
                    else append(report, "TERMUX_MCP_HOST_REPORT_RESTORED exact_report_key=true exact_hook_at=true nonce=" + nonce);
                }
                try {
                    if (screenBefore != null) for (String key : List.of("screen_task", "native_session", "result", "active", "completed"))
                        check(Objects.equals(screenBefore.get(key), call("screen_state", null, null).get(key)), "MCP changed screen-task state");
                    check(!call("plugin_busy", null, null).getBoolean("active"), "Module remote work remains active");
                } catch (Throwable stateFailure) {
                    passed = false;
                    append(report, "FAIL: Termux post-test screen or remote state differs");
                }
            }
            test.finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
        }

        private void hostStage(SharedPreferences prefs) throws Exception {
            stage = "native_host_proxy";
            hadReport = prefs.contains("hook_report");
            savedReport = prefs.getAll().get("hook_report");
            check(!hadReport || savedReport instanceof String, "Existing host report is not a string");
            hadHookAt = prefs.contains("hook_at");
            savedHookAt = prefs.getAll().get("hook_at");
            check(!hadHookAt || savedHookAt instanceof Long, "Existing host report timestamp is not a long");
            reportChanged = true;
            check(prefs.edit().remove("hook_report").commit(), "Could not prepare the owned host report handshake");
            hostRequested = true;
            signal("TERMUX_MCP_HOST_READY " + shellTool.name() + " " + nonce);
            long deadline = SystemClock.elapsedRealtime() + 45_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                String result = prefs.getString("hook_report", "");
                if (hostExitReported(result) && result.contains("TERMUX_MCP_HOST_PASS " + nonce)) {
                    hostExited = true;
                    signal("TERMUX_MCP_STAGE native_host_proxy=true");
                    return;
                }
                if (result.contains("TERMUX_MCP_HOST_FAIL " + nonce)
                    || result.contains("PLUGIN_PLATFORM_FAIL " + nonce))
                    throw new AssertionError("The actual host Termux MCP probe reported failure");
                Thread.sleep(100);
            }
            throw new AssertionError("The actual host Termux MCP handshake timed out");
        }

        private boolean hostExitReported(String value) {
            for (String line : value.split("\n"))
                if (line.equals("TERMUX_MCP_HOST_EXITED " + nonce)) return true;
            return false;
        }

        private void awaitHostExit(SharedPreferences prefs) throws Exception {
            if (!hostExited && hostExitReported(prefs.getString("hook_report", ""))) hostExited = true;
            if (!hostExited) {
                // The shell harness sends a DUMP-protected cancel for this nonce only. Cancelling
                // an unknown nonce also seals it, so a delayed start cannot follow this ack.
                signal("TERMUX_MCP_HOST_CANCEL " + nonce);
                long deadline = SystemClock.elapsedRealtime() + 75_000;
                while (!hostExited && SystemClock.elapsedRealtime() < deadline) {
                    hostExited = hostExitReported(prefs.getString("hook_report", ""));
                    if (!hostExited) Thread.sleep(100);
                }
            }
            check(hostExited, "The Termux host worker has not acknowledged exit");
            signal("TERMUX_MCP_HOST_EXIT_CONFIRMED");
        }

        private String identityGuard() {
            return "test \"$(id -u)\" = '" + expectedUid + "' || exit 81; "
                + "test \"$HOME\" = '" + expectedHome + "' || exit 82; "
                + "case \"$PREFIX\" in /data/data/com.termux/files/usr|/data/user/0/com.termux/files/usr) ;; *) exit 83;; esac; "
                + "test -x \"$PREFIX/bin/sh\" || exit 84; ";
        }

        private void cleanupFixture() throws Exception {
            String cleanup = identityGuard() + "d=\"$HOME/.cache/miaiplus-mcp-" + nonce + "\"; "
                + "if test -e \"$d\"; then test -d \"$d\" && test ! -L \"$d\" "
                + "&& test -f \"$d/.miaiplus-owner\" && test ! -L \"$d/.miaiplus-owner\" "
                + "&& test \"$(cat \"$d/.miaiplus-owner\")\" = '" + nonce + "' || exit 92; "
                + "rm -f \"$d/input.txt\" \"$d/.miaiplus-owner\" && rmdir \"$d\" || exit 93; fi; "
                + "test ! -e \"$d\" || exit 94; printf 'TERMUX_CLEANED_" + nonce + "\\n'";
            check(success(command(cleanup)).contains("TERMUX_CLEANED_" + nonce), "Owned Termux directory was not removed");
            fixtureMayExist = false;
            signal("TERMUX_MCP_STAGE cleanup fixture_directory_removed=true");
        }

        private boolean shellFailure(String result) throws Exception {
            String value = readable(result);
            return value.contains("TERMUX_STDERR_" + nonce) && Pattern.compile(
                "(?i)(?:exit(?:[_ -]?(?:code|status))?|return(?:[_ -]?code)?|code)[\\\"'\\s:=]+7(?:[^0-9]|$)")
                .matcher(value).find();
        }

        private JSONObject args(String command) throws Exception {
            JSONObject value = new JSONObject().put(commandField, command);
            if (timeoutField != null) value.put(timeoutField, 10);
            return value;
        }

        private Bundle command(String command) throws Exception {
            Bundle request = new Bundle();
            request.putString("revision", call("plugin_catalog", null, null).getString("revision"));
            request.putString("pluginId", entry.id()); request.putString("toolName", shellTool.name());
            request.putString("arguments", args(command).toString());
            Bundle started = call("plugin_start", null, request);
            check(!started.containsKey("error") && started.getString("id") != null, "Provider rejected the selected shell call");
            String id = started.getString("id"); jobs.add(id);
            long deadline = SystemClock.elapsedRealtime() + 70_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                Bundle result = call("plugin_poll", id, null);
                if (!"running".equals(result.getString("status"))) return result;
                Thread.sleep(100);
            }
            call("plugin_cancel", id, null); throw new AssertionError("Termux MCP polling timed out");
        }

        private String success(Bundle result) {
            check("done".equals(result.getString("status")), "Remote shell did not return a successful tool result");
            return result.getString("result", "");
        }

        private Bundle call(String method, String arg, Bundle extras) {
            Bundle response = context.getContentResolver().call(BridgeProvider.URI, method, arg, extras);
            check(response != null, "Module Provider returned no response"); return response;
        }

        private void waitIdle(long millis) throws Exception {
            long deadline = SystemClock.elapsedRealtime() + millis;
            while (call("plugin_busy", null, null).getBoolean("active") && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            check(!call("plugin_busy", null, null).getBoolean("active"), "A remote client did not finish cleanup");
        }

        private String redact(String detail) {
            String value = detail == null ? "unknown" : detail.replace('\n', ' ');
            if (!token.isEmpty()) value = value.replace(token, "[redacted]");
            if (!endpoint.isEmpty()) value = value.replace(endpoint, "[endpoint]");
            return value.substring(0, Math.min(value.length(), 300));
        }

        private void signal(String value) {
            Bundle status = new Bundle(); status.putString("stream", value + " nonce=" + nonce + "\n"); test.sendStatus(1, status);
        }
    }

    private static String readable(String text) throws Exception {
        String value = text == null ? "" : text;
        int start = value.indexOf('{');
        if (start >= 0) try { return value + "\n" + new JSONObject(value.substring(start)).toString(); }
        catch (org.json.JSONException ignored) { }
        return value;
    }

    private static String option(Bundle args, String key, String alias, String fallback) {
        String value = args.getString(key); return value == null ? args.getString(alias, fallback) : value;
    }

    private static String digest(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte item : bytes) { result.append(Character.forDigit((item >> 4) & 15, 16)); result.append(Character.forDigit(item & 15, 16)); }
        return result.toString();
    }

    private static void append(Bundle report, String text) { report.putString("stream", report.getString("stream", "") + text + "\n"); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private TermuxMcpRunner() { }
}
