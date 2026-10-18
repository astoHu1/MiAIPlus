package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Real native tool proxies plus physical-screen isolation; no model request or personal app is needed. */
final class DualToolsRunner {
    private DualToolsRunner() { }

    static void run(Instrumentation test) {
        var prefs = Config.prefs(test.getTargetContext());
        Map<String, ?> saved = prefs.getAll();
        boolean diagnostics = new Config(prefs).phoneUseDetailedLog;
        Bundle report = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            signal(test, "MIAIPLUS_CONNECT_SERVICE");
            long serviceDeadline = SystemClock.elapsedRealtime() + 20_000;
            while (ScreenService.instance == null && SystemClock.elapsedRealtime() < serviceDeadline) Thread.sleep(100);
            check(ScreenService.instance != null, "accessibility service unavailable");
            for (boolean legacyBackground : new boolean[]{true, false}) {
                prefs.edit().putBoolean("enabled", true).putBoolean("globalModel", false)
                    .putBoolean("backgroundDisplay", legacyBackground).putString("planner", "native")
                    .putInt("displayWidth", 1080).putInt("displayHeight", 1920).putInt("displayDensity", 320)
                    .putInt("maxSteps", 8).remove("hook_report").commit();
                signal(test, "MIAIPLUS_RESTART_XIAOAI MIAIPLUS_DUAL_TOOLS");
                long deadline = SystemClock.elapsedRealtime() + 150_000;
                boolean acknowledged = false;
                boolean expectedDefault = legacyBackground;
                String lastStage = "";
                String task = null, focus = null;
                TreeSet<String> displays = null;
                String result = "";
                while (SystemClock.elapsedRealtime() < deadline) {
                    result = prefs.getString("hook_report", "");
                    if (result.startsWith("PROBE ")) break;
                    if (result.startsWith("DUAL_TOOLS_") && !result.equals(lastStage)) {
                        check(prefs.getBoolean("backgroundDisplay", !expectedDefault) == expectedDefault,
                            "native tool changed global backend preference during execution");
                        if (result.equals("DUAL_TOOLS_FOREGROUND_STARTED")) {
                            expectedDefault = !legacyBackground;
                            prefs.edit().putBoolean("backgroundDisplay", expectedDefault).commit();
                            if (diagnostics) {
                                check(prefs.edit().putBoolean(Config.PHONE_USE_LOG_KEY, false).commit(), "Could not disable host logs");
                                Thread.sleep(1100); // Host switch cache expires without restarting XiaoAi/the session.
                                android.util.Log.i("MiAIPlusPhoneUseTest", "HOST_LOG_OFF");
                            }
                        } else if (result.equals("DUAL_TOOLS_BACKGROUND_STARTED")) {
                            expectedDefault = legacyBackground;
                            prefs.edit().putBoolean("backgroundDisplay", expectedDefault).commit();
                        } else if (result.equals("DUAL_TOOLS_FOREGROUND_DONE")) {
                            check(FixtureActivity.current != null, "physical fixture disappeared after foreground control");
                            task = physical("topResumedActivity"); focus = physical("mFocusedApp"); displays = displayIds();
                            check(task.startsWith("io.github.miaiplus/"), "physical baseline is not the owned fixture: " + task);
                            check(!"none".equals(FixtureActivity.lastTouch), "foreground tool's tap did not reach the physical fixture");
                            FixtureActivity.lastTouch = "none";
                            test.runOnMainSync(() -> check(FixtureActivity.input != null && FixtureActivity.input.getText().length() == 0,
                                "physical fixture input was not empty before background test"));
                            acknowledged = true;
                            if (diagnostics) {
                                check(prefs.edit().putBoolean(Config.PHONE_USE_LOG_KEY, true).commit(), "Could not enable host logs");
                                Thread.sleep(1100);
                                android.util.Log.i("MiAIPlusPhoneUseTest", "HOST_LOG_ON");
                            }
                        } else throw new AssertionError("unknown dual-tool stage: " + result);
                        // The receiver is DUMP-protected. Run this one constant test-control broadcast as root,
                        // just as the existing background runner reads dumpsys as root; no app is force-stopped.
                        shell("am broadcast --user 0 -a io.github.miaiplus.DEBUG_PROBE -p com.miui.voiceassist --es frame MIAIPLUS_DUAL_TOOLS_READY");
                        lastStage = result;
                    }
                    Thread.sleep(150);
                }
                check(result.startsWith("PROBE PASS"), result.isEmpty() ? "dual-tool probe timed out" : result);
                check(acknowledged, "background measurement stage was skipped");
                check(prefs.getBoolean("backgroundDisplay", !legacyBackground) == legacyBackground,
                    "native tools changed the global backend preference");
                check(task.equals(physical("topResumedActivity")), "background tool changed the physical top task");
                check(focus.equals(physical("mFocusedApp")), "background tool changed the physical app focus");
                check("none".equals(FixtureActivity.lastTouch), "background tool leaked touch to main fixture: " + FixtureActivity.lastTouch);
                test.runOnMainSync(() -> check(FixtureActivity.current != null && FixtureActivity.input != null
                    && FixtureActivity.input.getText().length() == 0, "background tool changed/destroyed the main fixture"));
                long cleanupDeadline = SystemClock.elapsedRealtime() + 8_000;
                while (SystemClock.elapsedRealtime() < cleanupDeadline && (helperRunning() || !displays.equals(displayIds()))) Thread.sleep(150);
                check(!helperRunning(), "helper remained after normal tool finish");
                check(displays.equals(displayIds()), "virtual display remained after normal tool finish");
                signal(test, result + "; legacy backgroundDisplay=" + legacyBackground + "; physical fixture untouched; normal helper/display teardown");
            }
            report.putString("stream", "PASS: both registered/exported native control tools with both legacy defaults; cross-tool continuation rejection preserves sessions; virtual-screen tap/swipe isolated from main fixture; normal teardown; defaults unchanged.\n");
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
                    var strings = new java.util.HashSet<String>();
                    for (Object item : v) strings.add((String)item);
                    edit.putStringSet(entry.getKey(), strings);
                }
            }
            if (!edit.commit() || !saved.equals(prefs.getAll())) {
                code = Activity.RESULT_CANCELED;
                report.putString("stream", report.getString("stream", "")
                    + "FAIL: Could not restore original settings\n");
            }
            test.finish(code, report);
        }
    }
    private static String physical(String field) throws Exception {
        String header = field.equals("mFocusedApp") ? "Display: mDisplayId=" : "Display #";
        String dump = shell(field.equals("mFocusedApp") ? "dumpsys window displays" : "dumpsys activity activities");
        StringBuilder physical = new StringBuilder();
        // Some ROM dumps concatenate display headers on the same line. Delimit by the next header rather
        // than line boundaries, and never let a virtual display supply the physical-screen assertion.
        var headers = Pattern.compile(Pattern.quote(header) + "(\\d+)").matcher(dump);
        int display = -1, from = 0;
        while (headers.find()) {
            if (display == 0) physical.append(dump, from, headers.start());
            display = Integer.parseInt(headers.group(1)); from = headers.end();
        }
        if (display == 0) physical.append(dump.substring(from));
        var match = Pattern.compile(field + "=ActivityRecord\\{[^}]*?\\s([\\w.]+/[\\w.$.]+)")
            .matcher(physical);
        check(match.find(), "physical " + field + " was unavailable");
        return match.group(1);
    }
    private static TreeSet<String> displayIds() throws Exception {
        var ids = new TreeSet<String>();
        var match = Pattern.compile("^\\s*Display Id=(\\d+)", Pattern.MULTILINE).matcher(shell("dumpsys display"));
        while (match.find()) ids.add(match.group(1));
        check(!ids.isEmpty(), "display dump was empty");
        return ids;
    }
    private static boolean helperRunning() throws Exception {
        return shell("ps -A").lines().anyMatch(line -> line.contains("io.github.miaiplus.helper.DisplayHelper"));
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
