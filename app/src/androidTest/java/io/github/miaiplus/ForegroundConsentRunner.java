package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Coordinates the host-side consent probe while checking only self-owned physical fixtures. */
final class ForegroundConsentRunner {
    private ForegroundConsentRunner() { }
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
            prefs.edit().putBoolean("enabled", true).putBoolean("globalModel", false)
                .putBoolean("backgroundDisplay", false).putString("planner", "native")
                .putInt("maxSteps", 12).remove("hook_report").commit();
            TreeSet<String> displays = displayIds();
            signal(test, "MIAIPLUS_RESTART_XIAOAI MIAIPLUS_FOREGROUND_CONSENT");
            String previous = "", result = "";
            int stages = 0;
            long deadline = SystemClock.elapsedRealtime() + 180_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                result = prefs.getString("hook_report", "");
                if (result.startsWith("PROBE ")) break;
                if (result.startsWith("CONSENT_") && !previous.equals(result)) {
                    if (result.endsWith("_READY")) {
                        // The host probe waits before sending the actual notification PendingIntent. Hold a
                        // stable baseline long enough to catch the old host-Activity focus jump while waiting.
                        check(FixtureActivity.current != null, "physical fixture was destroyed before consent");
                        String task = physical("topResumedActivity"), focus = physical("mFocusedApp");
                        check(task.startsWith("io.github.miaiplus/"), "waiting for consent moved the host to foreground");
                        for (int sample = 0; sample < 4; sample++) {
                            Thread.sleep(200);
                            check(task.equals(physical("topResumedActivity")), "physical top task changed while consent was pending");
                            check(focus.equals(physical("mFocusedApp")), "physical app focus changed while consent was pending");
                        }
                        check("none".equals(FixtureActivity.lastTouch), "handoff replayed a touch from the old display");
                        test.runOnMainSync(() -> check(FixtureActivity.input != null
                            && FixtureActivity.input.getText().length() == 0, "handoff changed physical fixture text"));
                    } else if (result.endsWith("_DONE")) {
                        Thread.sleep(1000);
                        check(physical("topResumedActivity").startsWith("io.github.miaiplus/"),
                            "completion automatically returned to the host before a user tapped its card");
                        check("none".equals(FixtureActivity.lastTouch), "handoff replayed physical input");
                        long cleanup = SystemClock.elapsedRealtime() + 8_000;
                        while (SystemClock.elapsedRealtime() < cleanup && (helperRunning() || !displays.equals(displayIds()))) Thread.sleep(100);
                        check(!helperRunning() && displays.equals(displayIds()), "handoff task leaked helper/display");
                        // Keep each scenario's physical fixture fresh, without opening a personal application.
                        // It is already topmost. startActivitySync can wait forever when Android reuses the
                        // existing Activity; no new Activity monitor event is required for these scenarios.
                        test.runOnMainSync(() -> test.getTargetContext().startActivity(
                            new Intent(test.getTargetContext(), FixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                        Thread.sleep(600);
                        FixtureActivity.lastTouch = "none";
                    } else throw new AssertionError("unknown consent probe stage");
                    check(!prefs.getBoolean("backgroundDisplay", true), "explicit background tool changed the user's default");
                    shell("am broadcast --user 0 -a io.github.miaiplus.DEBUG_PROBE -p com.miui.voiceassist --es frame MIAIPLUS_FOREGROUND_CONSENT_READY");
                    signal(test, result + "; owned fixture checks passed");
                    previous = result; stages++;
                }
                Thread.sleep(100);
            }
            check(result.startsWith("PROBE PASS"), result.isEmpty() ? "consent probe timed out" : result);
            check(stages == 6, "consent probe skipped a required stage: " + stages);
            check(!helperRunning() && displays.equals(displayIds()), "consent regression leaked display resources");
            report.putString("stream", result + "; physical fixture focus/task/input preserved while awaiting choices; original default restored\n");
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
    private static String physical(String field) throws Exception {
        String header = field.equals("mFocusedApp") ? "Display: mDisplayId=" : "Display #";
        String dump = shell(field.equals("mFocusedApp") ? "dumpsys window displays" : "dumpsys activity activities");
        StringBuilder physical = new StringBuilder();
        var headers = Pattern.compile(Pattern.quote(header) + "(\\d+)").matcher(dump);
        int display = -1, from = 0;
        while (headers.find()) {
            if (display == 0) physical.append(dump, from, headers.start());
            display = Integer.parseInt(headers.group(1)); from = headers.end();
        }
        if (display == 0) physical.append(dump.substring(from));
        var match = Pattern.compile(field + "=ActivityRecord\\{[^}]*?\\s([\\w.]+/[\\w.$.]+)")
            .matcher(physical);
        check(match.find(), "physical " + field + " unavailable"); return match.group(1);
    }
    private static TreeSet<String> displayIds() throws Exception {
        var ids = new TreeSet<String>();
        var match = Pattern.compile("^\\s*Display Id=(\\d+)", Pattern.MULTILINE).matcher(shell("dumpsys display"));
        while (match.find()) ids.add(match.group(1));
        check(!ids.isEmpty(), "display inventory unavailable"); return ids;
    }
    private static boolean helperRunning() throws Exception {
        return shell("ps -A").lines().anyMatch(line -> line.contains("io.github.miaiplus.helper.DisplayHelper"));
    }
    private static String shell(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(process.waitFor() == 0, "test shell command failed"); return output;
    }
    private static void signal(Instrumentation test, String message) {
        Bundle status = new Bundle(); status.putString("stream", message + "\n"); test.sendStatus(1, status);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
