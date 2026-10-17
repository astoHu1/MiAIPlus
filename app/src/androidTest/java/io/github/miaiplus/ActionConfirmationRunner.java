package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityWindowInfo;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/** Checks physical focus and exact touch delivery while host-side native tools await island confirmation. */
final class ActionConfirmationRunner {
    private ActionConfirmationRunner() { }
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
            openFixture(test);
            signal(test, "MIAIPLUS_RESTART_XIAOAI MIAIPLUS_ACTION_CONFIRMATION");
            String previous = "", result = "", top = "", focus = "";
            boolean waiting = false;
            int stages = 0;
            long nextSample = 0;
            long deadline = SystemClock.elapsedRealtime() + 180_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                result = prefs.getString("hook_report", "");
                if (result.startsWith("PROBE ")) break;
                if (result.startsWith("ACTION_CONFIRMATION_") && !previous.equals(result)) {
                    check(RobustFixtureActivity.current != null, "owned confirmation fixture disappeared");
                    if (result.endsWith("_READY")) {
                        counters(0);
                        top = physical("topResumedActivity"); focus = physical("mFocusedApp");
                        check(top.equals("io.github.miaiplus/.RobustFixtureActivity")
                            || top.equals("io.github.miaiplus/io.github.miaiplus.RobustFixtureActivity"), "confirmation moved the foreground Activity");
                        check(focus.contains("RobustFixtureActivity"), "confirmation took app focus away from owned fixture");
                        for (int sample = 0; sample < 4; sample++) {
                            Thread.sleep(200);
                            stable(test, top, focus);
                        }
                        if (result.equals("ACTION_CONFIRMATION_ALLOW_READY")) {
                            signal(test, "MIAIPLUS_CONFIRMATION_SCREENSHOT");
                            Thread.sleep(3000);
                            stable(test, top, focus);
                        }
                        waiting = result.equals("ACTION_CONFIRMATION_TIMEOUT_READY");
                    } else if (result.endsWith("_DONE")) {
                        waiting = false;
                        counters(result.equals("ACTION_CONFIRMATION_ALLOW_DONE") ? 1 : 0);
                        check(top.equals(physical("topResumedActivity")) && focus.equals(physical("mFocusedApp")),
                            "confirmation completion unexpectedly changed app focus or Activity");
                        noOverlay(test);
                        if (!result.equals("ACTION_CONFIRMATION_TIMEOUT_DONE")) {
                            test.runOnMainSync(() -> {
                                RobustFixtureActivity.clicks = 0;
                                RobustFixtureActivity.touchDowns = 0;
                            });
                        }
                    } else throw new AssertionError("unknown confirmation stage: " + result);
                    shell("am broadcast --user 0 -a io.github.miaiplus.DEBUG_PROBE -p com.miui.voiceassist --es frame MIAIPLUS_ACTION_CONFIRMATION_READY");
                    signal(test, result + "; owned fixture touch/focus and no legacy overlay verified");
                    previous = result; stages++;
                }
                // Monitor the actual one-minute wait, including the interval after READY acknowledgement.
                if (waiting && SystemClock.elapsedRealtime() >= nextSample) {
                    stable(test, top, focus);
                    nextSample = SystemClock.elapsedRealtime() + 1000;
                }
                Thread.sleep(70);
            }
            check(result.startsWith("PROBE PASS"), result.isEmpty() ? "confirmation probe timed out" : result);
            check(stages == 8, "confirmation probe skipped required cases/stages: " + stages);
            counters(0);
            report.putString("stream", result + "; allow exactly one touch/click, deny/stop/timeout zero; unchanged physical Activity/focus and no accessibility overlay while waiting; preferences restored\n");
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            report.putString("stream", "FAIL: " + failure + "\n" + android.util.Log.getStackTraceString(failure));
        } finally {
            BridgeProvider.cancelActive();
            // Abort the probe itself, rather than acknowledging a failed stage and opening another session.
            if (code != Activity.RESULT_OK) try {
                shell("am broadcast --user 0 -a io.github.miaiplus.DEBUG_PROBE -p com.miui.voiceassist --es frame MIAIPLUS_ACTION_CONFIRMATION_CANCEL");
                long cleanupDeadline = SystemClock.elapsedRealtime() + 12_000;
                while (!prefs.getString("hook_report", "").startsWith("PROBE ")
                    && SystemClock.elapsedRealtime() < cleanupDeadline) Thread.sleep(100);
                BridgeProvider.cancelActive();
            } catch (Exception ignored) { }
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
    private static void openFixture(Instrumentation test) throws Exception {
        test.runOnMainSync(() -> test.getTargetContext().startActivity(
            new Intent(test.getTargetContext(), RobustFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        while (RobustFixtureActivity.current == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
        check(RobustFixtureActivity.current != null, "owned confirmation fixture failed to start");
        Thread.sleep(700);
    }
    private static void stable(Instrumentation test, String top, String focus) throws Exception {
        counters(0);
        check(top.equals(physical("topResumedActivity")), "Activity changed while confirmation was pending");
        check(focus.equals(physical("mFocusedApp")), "app focus changed while confirmation was pending");
        noOverlay(test);
    }
    private static void noOverlay(Instrumentation test) {
        test.runOnMainSync(() -> {
            ScreenService service = ScreenService.instance;
            check(service != null, "accessibility disconnected during confirmation");
            for (AccessibilityWindowInfo window : service.getWindows())
                check(window.getType() != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, "legacy accessibility confirmation overlay appeared");
        });
    }
    private static void counters(int expected) {
        check(RobustFixtureActivity.clicks == expected, "owned confirmation click count " + RobustFixtureActivity.clicks + ", expected " + expected);
        check(RobustFixtureActivity.touchDowns == expected, "owned confirmation touch count " + RobustFixtureActivity.touchDowns + ", expected " + expected);
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
        var match = Pattern.compile(field + "=ActivityRecord\\{[^}]*?\\s([\\w.]+/[\\w.$.]+)").matcher(physical);
        check(match.find(), "physical " + field + " unavailable"); return match.group(1);
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
