package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Coordinates an actual host-tool session while mutating only a self-owned physical fixture. */
final class RobustScreenRunner {
    private RobustScreenRunner() { }
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
                .putInt("maxSteps", 24).remove("hook_report").commit();
            test.runOnMainSync(() -> test.getTargetContext().startActivity(
                new Intent(test.getTargetContext(), RobustFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            long fixtureDeadline = SystemClock.elapsedRealtime() + 10_000;
            while (RobustFixtureActivity.current == null && SystemClock.elapsedRealtime() < fixtureDeadline) Thread.sleep(100);
            check(RobustFixtureActivity.current != null, "owned robustness fixture failed to start");
            Thread.sleep(700);
            signal(test, "MIAIPLUS_RESTART_XIAOAI MIAIPLUS_ROBUST_SCREEN");
            String previous = "", result = "";
            int stages = 0;
            long deadline = SystemClock.elapsedRealtime() + 180_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                result = prefs.getString("hook_report", "");
                if (result.startsWith("PROBE ")) break;
                if (result.startsWith("ROBUST_") && !previous.equals(result)) {
                    check(RobustFixtureActivity.current != null, "owned fixture disappeared during control");
                    switch (result) {
                        case "ROBUST_STARTED" -> counters(0);
                        case "ROBUST_STABLE_TAP_DONE" -> {
                            counters(1);
                            check(RobustFixtureActivity.ticks >= 10, "unrelated dynamic text did not refresh during planning");
                        }
                        case "ROBUST_SHIFT_READY" -> {
                            counters(1);
                            test.runOnMainSync(RobustFixtureActivity::shiftTarget);
                            test.waitForIdleSync();
                        }
                        case "ROBUST_SHIFT_REPLAN_DONE" -> counters(1);
                        case "ROBUST_SHIFT_TAP_DONE" -> counters(2);
                        case "ROBUST_REPLACE_READY" -> {
                            counters(2);
                            test.runOnMainSync(RobustFixtureActivity::replaceTarget);
                            test.waitForIdleSync();
                        }
                        case "ROBUST_REPLACE_REPLAN_DONE" -> counters(2);
                        case "ROBUST_REPLACE_TAP_DONE", "ROBUST_FINISHED" -> counters(3);
                        default -> throw new AssertionError("unknown robustness stage: " + result);
                    }
                    shell("am broadcast --user 0 -a io.github.miaiplus.DEBUG_PROBE -p com.miui.voiceassist --es frame MIAIPLUS_ROBUST_SCREEN_READY");
                    signal(test, result + "; owned fixture click/touch counts verified");
                    previous = result; stages++;
                }
                Thread.sleep(70);
            }
            check(result.startsWith("PROBE PASS"), result.isEmpty() ? "robustness probe timed out" : result);
            check(stages == 9, "robustness probe skipped required stages: " + stages);
            counters(3);
            report.putString("stream", result + "; exactly three physical touch-downs and button clicks, zero stale-coordinate injection; preferences restored\n");
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            report.putString("stream", "FAIL: " + failure + "\n" + android.util.Log.getStackTraceString(failure));
        } finally {
            BridgeProvider.cancelActive();
            test.runOnMainSync(() -> {
                for (var task : test.getTargetContext().getSystemService(android.app.ActivityManager.class).getAppTasks()) {
                    var base = task.getTaskInfo().baseActivity;
                    if (base != null && base.getPackageName().equals("io.github.miaiplus")
                        && base.getClassName().equals("io.github.miaiplus.RobustFixtureActivity")) task.finishAndRemoveTask();
                }
                if (RobustFixtureActivity.current != null) RobustFixtureActivity.current.finish();
            });
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
    private static void counters(int expected) {
        check(RobustFixtureActivity.clicks == expected, "unexpected owned target click count: " + RobustFixtureActivity.clicks + ", expected " + expected);
        check(RobustFixtureActivity.touchDowns == expected, "stale/unexpected touch was injected: " + RobustFixtureActivity.touchDowns + ", expected " + expected);
    }
    private static void shell(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(process.waitFor() == 0, "test-control broadcast failed: " + output);
    }
    private static void signal(Instrumentation test, String message) {
        Bundle status = new Bundle(); status.putString("stream", message + "\n"); test.sendStatus(1, status);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
