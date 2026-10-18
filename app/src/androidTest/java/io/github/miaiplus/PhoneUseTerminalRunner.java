package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/** Drives terminal native-tool regressions while keeping a physical self-owned fixture in front. */
final class PhoneUseTerminalRunner {
    private PhoneUseTerminalRunner() { }
    static void run(Instrumentation test) {
        var prefs = Config.prefs(test.getTargetContext());
        Map<String, ?> saved = prefs.getAll();
        Bundle report = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            signal(test, "MIAIPLUS_CONNECT_SERVICE");
            long serviceDeadline = SystemClock.elapsedRealtime() + 20_000;
            while (ScreenService.instance == null && SystemClock.elapsedRealtime() < serviceDeadline) Thread.sleep(100);
            check(ScreenService.instance != null, "accessibility service unavailable");
            check(prefs.edit().putBoolean("enabled", true).putBoolean("globalModel", false)
                .putBoolean("backgroundDisplay", false).putString("planner", "native")
                .putInt("maxSteps", 2).remove("hook_report").commit(), "probe configuration failed");
            signal(test, "MIAIPLUS_RESTART_XIAOAI MIAIPLUS_PHONE_USE_TERMINAL");
            String previous = "", result = "", baseline = null;
            int stages = 0, focusSamples = 0;
            long deadline = SystemClock.elapsedRealtime() + 180_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                result = prefs.getString("hook_report", "");
                if (result.startsWith("PROBE ")) break;
                if (baseline != null) {
                    check(baseline.equals(physicalFocus()), "terminal tool result automatically changed the physical task/focus");
                    focusSamples++;
                }
                if (result.startsWith("TERMINAL_") && !previous.equals(result)) {
                    check(java.util.Set.of("TERMINAL_BUDGET_READY", "TERMINAL_RETRIES_DONE", "TERMINAL_TAKE_OVER_DONE",
                        "TERMINAL_ASK_USER_DONE", "TERMINAL_FINISH_DONE", "TERMINAL_MODEL_EXHAUSTED_DONE",
                        "TERMINAL_MODEL_RELEASED_DONE").contains(result), "unexpected terminal probe stage");
                    if (baseline == null) baseline = physicalFocus();
                    check(baseline.contains("io.github.miaiplus/.FixtureActivity")
                        || baseline.contains("io.github.miaiplus/io.github.miaiplus.FixtureActivity"), "owned fixture is not foreground");
                    for (int sample = 0; sample < 3; sample++) {
                        Thread.sleep(150);
                        check(baseline.equals(physicalFocus()), "terminal stage changed the physical task/focus");
                        focusSamples++;
                    }
                    check("none".equals(FixtureActivity.lastTouch), "wait-only probe injected physical touches");
                    test.runOnMainSync(() -> check(FixtureActivity.input != null
                        && FixtureActivity.input.getText().length() == 0, "wait-only probe changed fixture input"));
                    shell("am broadcast --user 0 -a io.github.miaiplus.DEBUG_PROBE -p com.miui.voiceassist --es frame MIAIPLUS_PHONE_USE_TERMINAL_READY");
                    signal(test, result + "; owned fixture task/focus/input unchanged");
                    previous = result;
                    stages++;
                }
                Thread.sleep(100);
            }
            check(result.startsWith("PROBE PASS"), result.isEmpty() ? "terminal probe timed out" : result);
            check(stages == 7, "terminal probe skipped required stages: " + stages);
            check(baseline != null && baseline.equals(physicalFocus()), "probe completion returned to the host automatically");
            report.putString("stream", result + "; " + focusSamples + " physical focus/task samples; fixture untouched; preferences restored\n");
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            report.putString("stream", "FAIL: " + failure + "\n" + android.util.Log.getStackTraceString(failure));
        } finally {
            BridgeProvider.cancelActive();
            var edit = prefs.edit().clear();
            for (var entry : saved.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String v) edit.putString(entry.getKey(), v);
                else if (value instanceof Boolean v) edit.putBoolean(entry.getKey(), v);
                else if (value instanceof Integer v) edit.putInt(entry.getKey(), v);
                else if (value instanceof Long v) edit.putLong(entry.getKey(), v);
                else if (value instanceof Float v) edit.putFloat(entry.getKey(), v);
                else if (value instanceof java.util.Set<?> v) {
                    var strings = new java.util.HashSet<String>(); for (Object item : v) strings.add((String)item);
                    edit.putStringSet(entry.getKey(), strings);
                }
            }
            edit.commit();
            test.finish(code, report);
        }
    }
    private static String physicalFocus() throws Exception {
        String dump = shell("dumpsys activity activities");
        var header = Pattern.compile("Display #(\\d+)").matcher(dump);
        String physical = "";
        int display = -1, from = 0;
        while (header.find()) {
            if (display == 0) physical += dump.substring(from, header.start());
            display = Integer.parseInt(header.group(1)); from = header.end();
        }
        if (display == 0) physical += dump.substring(from);
        var resumed = Pattern.compile("topResumedActivity=ActivityRecord\\{[^}]*?\\s([\\w.]+/[\\w.$.]+)").matcher(physical);
        check(resumed.find(), "physical top resumed activity unavailable");
        String activity = resumed.group(1);
        var focus = Pattern.compile("mFocusedApp=ActivityRecord\\{([^}]+)\\}").matcher(shell("dumpsys window displays"));
        check(focus.find(), "physical focused app unavailable");
        return activity + "|" + focus.group(1);
    }
    private static String shell(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(process.waitFor() == 0, "test shell command failed");
        return output;
    }
    private static void signal(Instrumentation test, String message) {
        Bundle status = new Bundle(); status.putString("stream", message + "\n"); test.sendStatus(1, status);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
