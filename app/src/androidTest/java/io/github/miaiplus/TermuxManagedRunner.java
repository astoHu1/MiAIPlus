package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import java.util.Map;
import org.json.JSONObject;

/** Real managed Termux acceptance. Explicitly provisions the installed APK's adapter. */
final class TermuxManagedRunner {
    private final Instrumentation runner;
    private final android.content.Context context;

    private TermuxManagedRunner(Instrumentation runner) {
        this.runner = runner;
        context = runner.getTargetContext();
    }

    static void run(Instrumentation runner) { new TermuxManagedRunner(runner).run(); }

    private void signal(String message) {
        Bundle status = new Bundle(); status.putString("stream", message + "\n"); runner.sendStatus(1, status);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private JSONObject call(String name, JSONObject args) throws Exception {
        Bundle catalog = PluginRuntime.catalog(context);
        Bundle request = new Bundle();
        request.putString("revision", catalog.getString("revision"));
        request.putString("pluginId", PluginSettings.TERMUX_ID);
        request.putString("toolName", "miaiplus_termux_" + name);
        request.putString("arguments", args.toString());
        Bundle started = PluginRuntime.start(context, Process.myUid(), request);
        check(started.getString("id") != null, "Managed call was not admitted: " + name);
        long deadline = SystemClock.elapsedRealtime() + 60_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            Bundle result = PluginRuntime.poll(context, Process.myUid(), started.getString("id"));
            if ("running".equals(result.getString("status"))) { Thread.sleep(80); continue; }
            check("done".equals(result.getString("status")), "Managed transport failed: " + result.getString("error"));
            String text = result.getString("result");
            int object = text.indexOf('{');
            check(object >= 0, "No shell JSON returned");
            // parseObject rejects trailing duplicated JSON, including alternate slash escaping.
            return PluginToolSpec.parseObject(text.substring(object));
        }
        PluginRuntime.cancel(Process.myUid(), started.getString("id"));
        throw new AssertionError("Managed bridge timed out");
    }

    private JSONObject shell(String command) throws Exception {
        return call("run_digest", new JSONObject().put("cmd", command));
    }

    private JSONObject awaitJob(String id) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 30_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONObject result = call("job_status", new JSONObject().put("job_id", id));
            if (!result.optBoolean("running")) return result;
            Thread.sleep(500);
        }
        call("job_cancel", new JSONObject().put("job_id", id));
        throw new AssertionError("Managed background job timed out");
    }

    private void run() {
        Bundle result = new Bundle();
        Map<String, ?> before = Config.prefs(context).getAll();
        try {
            check(!PluginRuntime.busy(Process.myUid()).getBoolean("active"), "Another plugin call is running");
            var snapshot = TermuxMcp.configure(context);
            var entry = TermuxMcp.find(snapshot);
            check(entry != null && entry.tools().size() == 5, "Five managed tools were not provisioned");
            signal("PASS: installed APK provisioned five real Termux tools");
            String command = "  printf 'literal=$HOME\\n'; printf '%s %s %s\\n' \"$((3+4))\" \"$(printf sub)\" \"`printf tick`\"; id -u  ";
            JSONObject body = shell(command);
            int uid = context.getPackageManager().getApplicationInfo("com.termux", 0).uid;
            check(body.getBoolean("ok") && command.equals(body.getString("cmd")), "Command bytes changed");
            check(body.getString("out").equals("literal=$HOME\n7 sub tick\n" + uid + "\n"), "Shell expansion or UID mismatch");
            body = shell("[[ -d \"$HOME\" && -d \"$TMPDIR\" ]] && arr=(甲 乙); printf '%s\\n' \"${arr[1]}\" \"$HOME\" \"$TMPDIR\"");
            check(body.getBoolean("ok") && body.getString("out").equals("乙\n" + body.getString("home_dir")
                + "\n" + body.getString("tmp_dir") + "\n"), "Bash or environment mismatch");
            check(shell("printf 'line%d\\n' $(seq 1 2000) | wc -l").getString("out").trim().equals("2000"), "Command substitution count mismatch");
            body = shell("ls /miaiplus_missing 2>/dev/null; echo rc=$?; printf a; printf b >&2; printf c");
            check(body.getString("out").equals("rc=2\nabc") && !body.has("summary") && !body.has("facts"), "Redirection/order/summary mismatch");
            body = shell("printf SHOULD_NOT_RUN; if then");
            check(!body.getBoolean("ok") && !body.getBoolean("executed")
                && body.getJSONArray("errors").getJSONObject(0).getString("code").equals("E_SYNTAX"), "Syntax did not fail before execution");
            signal("PASS: exact cmd, substitutions, literal quotes, real HOME/TMPDIR, Bash, stderr order, syntax and unique JSON");
            body = call("run", new JSONObject().put("cmd", "sleep 16; printf QUIET_OK").put("timeout_ms", 20000));
            check(body.getBoolean("ok") && body.getString("out").equals("QUIET_OK"), "Quiet synchronous command lost SSE keepalive");
            signal("PASS: 16-second synchronous command through MCP SSE and module bridge");
            body = call("run_digest", new JSONObject().put("cmd", "sleep 11; printf ASYNC_OK")
                .put("background", true).put("timeout_ms", 20000));
            check(body.getBoolean("running"), "Background submission did not return immediately");
            body = awaitJob(body.getString("job_id"));
            check(body.getBoolean("ok") && body.getString("out").equals("ASYNC_OK"), "Background job did not finish");
            body = call("run_digest", new JSONObject().put("cmd", "sleep 10; printf CANCEL_FAILED").put("background", true));
            String cancelled = body.getString("job_id");
            call("job_cancel", new JSONObject().put("job_id", cancelled));
            body = awaitJob(cancelled);
            check(!body.getBoolean("ok") && body.getJSONArray("errors").getJSONObject(0).getString("code").equals("E_CANCELLED"), "Background cancel failed");
            body = call("run_digest", new JSONObject().put("cmd", "sleep 2").put("timeout_ms", 200));
            check(body.getInt("exit") == 124, "Timeout code mismatch");
            body = shell("printf failure >&2; exit 7");
            check(!body.getBoolean("ok") && body.getInt("exit") == 7 && body.getString("out").equals("failure"), "Exit diagnostic mismatch");
            body = shell("seq 1 8000");
            check(body.getBoolean("ok") && body.has("out_ref"), "Large output reference missing");
            JSONObject output = call("out_read", new JSONObject().put("ref", body.getString("out_ref")).put("tail", 2));
            check(output.toString().contains("7999") && output.toString().contains("8000"), "Large output tail missing");
            body = shell("apt-get remove miaiplus-test-never-installed");
            check(!body.getBoolean("ok") && !body.getBoolean("executed")
                && body.getJSONArray("errors").getJSONObject(0).getString("code").equals("E_CONFIRM"), "Confirmation policy was bypassed");
            signal("PASS: >10-second async completion, cancellation, timeout, nonzero exit, out_read and confirmation refusal");
            String nonce = java.util.UUID.randomUUID().toString();
            var prefs = Config.prefs(context);
            Object savedReport = prefs.getAll().get("hook_report"), savedAt = prefs.getAll().get("hook_at");
            try {
                String broadcast = "am broadcast -a " + BuildConfig.APPLICATION_ID + ".PLUGIN_PROBE -p com.miui.voiceassist"
                    + " --es mode termux --es tool miaiplus_termux_run_digest --es nonce " + nonce + " --es expected_uid " + uid;
                try (var commandResult = runner.getUiAutomation().executeShellCommand(broadcast);
                     var input = new java.io.FileInputStream(commandResult.getFileDescriptor())) { input.readAllBytes(); }
                long deadline = SystemClock.elapsedRealtime() + 25_000;
                boolean passed = false;
                while (SystemClock.elapsedRealtime() < deadline) {
                    String report = prefs.getString("hook_report", "");
                    if (report.contains("TERMUX_MCP_HOST_EXITED " + nonce)) {
                        check(report.contains("TERMUX_MCP_HOST_PASS " + nonce), "Native host proxy rejected the semantics probe");
                        passed = true; break;
                    }
                    Thread.sleep(100);
                }
                check(passed, "Native debug host did not finish the managed semantics probe");
                signal("PASS: actual XiaoAI registry/proxy preserved cmd bytes, arithmetic, substitutions and literal quotes");
            } finally {
                var edit = prefs.edit();
                if (savedReport instanceof String text) edit.putString("hook_report", text); else edit.remove("hook_report");
                if (savedAt instanceof Long value) edit.putLong("hook_at", value); else edit.remove("hook_at");
                check(edit.commit(), "Probe report restore failed");
            }
            for (var value : before.entrySet()) {
                if (value.getKey().equals(PluginSettings.PREFERENCE_KEY) || value.getKey().startsWith("hook_")) continue;
                check(java.util.Objects.equals(value.getValue(), Config.prefs(context).getAll().get(value.getKey())),
                    "Unrelated preference changed: " + value.getKey());
            }
            result.putString("stream", "PASS: managed Termux real-device acceptance; upgraded service/catalog retained\n");
            runner.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: managed Termux acceptance: " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n");
            runner.finish(Activity.RESULT_CANCELED, result);
        }
    }
}
