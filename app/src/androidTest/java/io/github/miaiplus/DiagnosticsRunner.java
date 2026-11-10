package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

/** Reads records from prior real-host probes; never seeds, clears or enables the journal. */
final class DiagnosticsRunner {
    private DiagnosticsRunner() { }

    static void run(Instrumentation test) {
        Bundle report = new Bundle();
        int resultCode = Activity.RESULT_CANCELED;
        var context = test.getTargetContext();
        var prefs = Config.prefs(context);
        boolean hadLogOption = prefs.contains(Config.PHONE_USE_LOG_KEY);
        boolean savedLogOption = prefs.getBoolean(Config.PHONE_USE_LOG_KEY, false);
        try {
            DiagnosticLogStore store = DiagnosticLogStore.get(context);
            Counts before = validateRecords(String.join("\n", store.snapshot().lines()));
            File external = context.getExternalFilesDir(null);
            check(external != null, "external_files_unavailable");
            File directory = new File(external, "diagnostic-test-" + UUID.randomUUID());
            check(directory.mkdir(), "export_directory_failed");
            File exported = new File(directory, "diagnostics.zip");
            try (FileOutputStream output = new FileOutputStream(exported)) {
                store.exportZip(output, savedLogOption, DiagnosticEnvironment.snapshot(context));
            }
            Map<String, String> entries = readZip(exported);
            check(entries.keySet().equals(Set.of("environment.json", "phone-use.jsonl", "README.txt")), "zip_entries_invalid");
            for (String text : entries.values()) check(!text.contains("test-key"), "test_key_in_export");
            Counts exportedCounts = validateRecords(entries.get("phone-use.jsonl"));
            JSONObject environment = new JSONObject(entries.get("environment.json"));
            check(environment.getInt("schema") == 2, "environment_schema_invalid");
            check(environment.getInt("versionCode") == BuildConfig.VERSION_CODE, "export_version_invalid");
            JSONObject host = environment.getJSONObject("host");
            check(host.getBoolean("installed"), "host_not_installed");
            String profile = host.getString("profile");
            check(profile.equals("legacy") || profile.equals("modern"), "host_profile_unsupported");
            JSONObject hook = environment.getJSONObject("hook");
            JSONObject status = hook.getJSONObject("status");
            check("ready".equals(status.getString("phase")), "hook_status_not_ready");
            check(status.getLong("moduleVersionCode") == BuildConfig.VERSION_CODE, "hook_status_version_skew");
            check(status.has("optional"), "hook_optional_state_missing");
            String optional = status.getString("optional");
            check(optional.isEmpty() || optional.matches("(?:error_handler|agent_error|agent_support)(?:,(?:error_handler|agent_error|agent_support)){0,2}"),
                "hook_optional_state_invalid");
            check(!hook.optBoolean("versionSkew", true), "hook_version_skew");
            JSONObject log = environment.getJSONObject("log");
            check(log.getInt("moduleRecords") == exportedCounts.module, "module_summary_count_mismatch");
            check(log.getInt("hostRecords") == exportedCounts.host, "host_summary_count_mismatch");
            check(environment.getInt("records") == exportedCounts.total, "summary_total_mismatch");
            check(prefs.contains(Config.PHONE_USE_LOG_KEY) == hadLogOption
                && prefs.getBoolean(Config.PHONE_USE_LOG_KEY, false) == savedLogOption, "log_option_changed");
            report.putString("stream", "PASS: Diagnostics persisted real host metadata; module=" + exportedCounts.module
                + "; host=" + exportedCounts.host + "; historicalUnknownSource=" + exportedCounts.unknown + "; currentVersionRecords=" + exportedCounts.current
                + "; beforeRecords=" + before.total + "; profile=" + profile + "; hookPhase=ready; optionalMissing="
                + (optional.isEmpty() ? "none" : optional) + "; logOptionUnchanged=true\n"
                + "DIAGNOSTICS_ZIP " + exported.getAbsolutePath() + "\n");
            resultCode = Activity.RESULT_OK;
        } catch (Throwable failure) {
            // Fixed assertions and exception types only: this runner must not echo ZIP content or credentials.
            String reason = failure instanceof AssertionError ? String.valueOf(failure.getMessage()) : failure.getClass().getSimpleName();
            report.putString("stream", "FAIL: Diagnostics " + reason + "\n");
        }
        test.finish(resultCode, report);
    }

    private record Counts(int module, int host, int total, int current, int unknown) { }

    private static Counts validateRecords(String text) throws Exception {
        int module = 0, host = 0, total = 0, current = 0, unknown = 0;
        boolean hostBoundary = false, currentModule = false, currentHost = false;
        Set<String> identities = new HashSet<>();
        for (String line : text.split("\n")) {
            if (line.isEmpty()) continue;
            JSONObject record = new JSONObject(line);
            total++;
            String source = record.getString("source");
            if (source.equals("host")) host++;
            else if (source.equals("module")) module++;
            // Earlier versions let capture origin overwrite process source. Preserve the historical evidence;
            // it cannot be safely assigned back to a process, and must not count as a current boundary.
            else { unknown++; continue; }
            if (record.optLong("ver", -1) != BuildConfig.VERSION_CODE) continue;
            current++;
            check(record.getLong("seq") > 0 && record.getLong("pid") > 0 && record.getLong("time") > 0, "record_identity_invalid");
            check(identities.add(source + ":" + record.getLong("pid") + ":" + record.optString("writer") + ":" + record.getLong("seq")), "record_identity_duplicated");
            if (source.equals("module")) currentModule = true;
            if (source.equals("host")) {
                currentHost = true;
                String event = record.getString("event");
                if (event.equals("model_call") || event.equals("host_hooks")) hostBoundary = true;
            }
        }
        check(currentModule && currentHost, "current_version_dual_source_missing");
        check(hostBoundary, "real_host_boundary_missing");
        return new Counts(module, host, total, current, unknown);
    }

    private static Map<String, String> readZip(File file) throws Exception {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream input = new ZipInputStream(new FileInputStream(file), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                check(!entry.isDirectory() && !entries.containsKey(entry.getName()), "zip_entry_duplicate");
                entries.put(entry.getName(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
                input.closeEntry();
            }
        }
        return entries;
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
