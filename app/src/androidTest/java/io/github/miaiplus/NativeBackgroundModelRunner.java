package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Runs a paid, real official-model conversation only when explicitly selected. */
final class NativeBackgroundModelRunner {
    private NativeBackgroundModelRunner() { }

    static void run(Instrumentation test) {
        run(test, false);
    }

    static void run(Instrumentation test, boolean voice) {
        var context = test.getTargetContext();
        var prefs = Config.prefs(context);
        Map<String, ?> saved = prefs.getAll();
        Bundle report = new Bundle();
        int code = Activity.RESULT_CANCELED;
        boolean hostFinished = false;
        boolean hostStarted = false;
        boolean configured = false;
        String baseline = null;
        String latest = "no host report";
        TreeSet<String> initialDisplays = null;
        int samples = 0;
        boolean cleaningUp = false;
        var savedFrames = new java.util.concurrent.ConcurrentHashMap<String, CalculatorFrame>();
        var pixelAcks = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        Object previousFrame = null;
        boolean receiverRegistered = false;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent intent) {
                if (!"io.github.miaiplus.NATIVE_BACKGROUND_PIXELS_VERIFIED".equals(intent.getAction())) return;
                String nonce = intent.getStringExtra("nonce");
                if (nonce != null && savedFrames.containsKey(nonce)) pixelAcks.add(nonce);
            }
        };
        try {
            Bundle busy = context.getContentResolver().call(BridgeProvider.URI, "conversation_settings", null, null);
            int hostUid = context.getPackageManager().getApplicationInfo("com.miui.voiceassist", 0).uid;
            check(busy != null && !busy.getBoolean("screenBusy") && !PluginRuntime.busy(hostUid).getBoolean("active")
                && !PluginRuntime.busy(android.os.Process.myUid()).getBoolean("active"), "another screen/plugin task is active");
            check(PluginSettings.load(prefs).find(PluginSettings.BACKGROUND_ID).enabled(), "enable background phone plugin first");
            context.registerReceiver(receiver, new IntentFilter("io.github.miaiplus.NATIVE_BACKGROUND_PIXELS_VERIFIED"),
                "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
            signal(test, "MIAIPLUS_CONNECT_SERVICE");
            long serviceDeadline = SystemClock.elapsedRealtime() + 20_000;
            while (!ScreenService.isHealthy() && SystemClock.elapsedRealtime() < serviceDeadline) Thread.sleep(100);
            check(ScreenService.isHealthy(), "accessibility service is not healthy after connection handshake");
            initialDisplays = displays();
            check(prefs.edit().putBoolean("enabled", true).putBoolean("globalModel", false)
                .putString("planner", "native").putInt("maxSteps", 40).remove("hook_report").commit(), "probe configuration failed");
            configured = true;
            signal(test, "MIAIPLUS_RESTART_XIAOAI " + (voice ? "MIAIPLUS_NATIVE_BACKGROUND_VOICE" : "MIAIPLUS_NATIVE_BACKGROUND_MODEL"));
            hostStarted = true;
            long deadline = SystemClock.elapsedRealtime() + 630_000;
            String previous = "";
            while (SystemClock.elapsedRealtime() < deadline) {
                latest = prefs.getString("hook_report", "");
                if (latest.startsWith("PROBE ")) {
                    hostFinished = true;
                    check(latest.startsWith("PROBE PASS"), latest);
                    check(baseline != null && samples > 0, "main-screen isolation was not measured");
                    long cleanupDeadline = SystemClock.elapsedRealtime() + 10_000;
                    while (SystemClock.elapsedRealtime() < cleanupDeadline && (helper() || !initialDisplays.equals(displays()))) Thread.sleep(150);
                    check(!helper() && initialDisplays.equals(displays()), "helper/display remained after real model finish");
                    report.putString("stream", latest + "; physical isolation samples=" + samples
                        + "; real task helper/display released; original settings restored\n");
                    code = Activity.RESULT_OK;
                    return;
                }
                if (latest.equals("NATIVE_BACKGROUND_WAIT_ISOLATION") && baseline == null) {
                    baseline = physical();
                    check(baseline.contains("io.github.miaiplus/"), "owned physical fixture is not foreground");
                    check(FixtureActivity.current != null && "none".equals(FixtureActivity.lastTouch), "physical fixture disappeared or received touches");
                    shell("am broadcast --user 0 -a io.github.miaiplus.DEBUG_PROBE -p com.miui.voiceassist --es frame MIAIPLUS_NATIVE_BACKGROUND_READY");
                }
                if (latest.startsWith("NATIVE_BACKGROUND_CLEANUP") || latest.startsWith("NATIVE_BACKGROUND_WAIT_OWNED_SCREEN")) cleaningUp = true;
                if (baseline != null && !cleaningUp && !latest.startsWith("PROBE ")) {
                    check(baseline.equals(physical()), "real background task changed physical task/focus");
                    check("none".equals(FixtureActivity.lastTouch), "real background task touched physical fixture");
                    test.runOnMainSync(() -> check(FixtureActivity.input != null && FixtureActivity.input.getText().length() == 0,
                        "real background task wrote physical fixture"));
                    samples++;
                    CalculatorFrame snapshot = calculatorFrame(context, latest, previousFrame);
                    if (snapshot != null) {
                        previousFrame = snapshot.frame();
                        savedFrames.put(snapshot.nonce(), snapshot);
                        signal(test, "NATIVE_BACKGROUND_CALCULATOR_FRAME " + snapshot.nonce() + " " + snapshot.path());
                    }
                    String verified;
                    while ((verified = pixelAcks.poll()) != null) {
                        CalculatorFrame frame = savedFrames.get(verified);
                        if (frame == null || frame.executedTaps() < 4) continue;
                        shell("am broadcast --user 0 -a io.github.miaiplus.DEBUG_PROBE -p com.miui.voiceassist --es frame MIAIPLUS_NATIVE_BACKGROUND_PIXELS"
                            + " --es nonce " + frame.nonce() + " --es task_ref " + frame.taskRef() + " --es session_ref " + frame.sessionRef()
                            + " --es frame_ref " + frame.frameRef() + " --ei step " + frame.step());
                        signal(test, "NATIVE_BACKGROUND_CALCULATOR_PIXELS " + frame.nonce() + ": owned calculator result verified");
                    }
                }
                if (!latest.equals(previous) && !latest.isBlank()) { signal(test, latest); previous = latest; }
                Thread.sleep(250);
            }
            throw new AssertionError("real official-model probe timed out; " + latest);
        } catch (Throwable failure) {
            report.putString("stream", "PROBE FAIL: " + failure + "; last=" + latest + "\n");
        } finally {
            if (hostStarted && !hostFinished) {
                try {
                    shell("am broadcast --user 0 -a io.github.miaiplus.DEBUG_PROBE -p com.miui.voiceassist --es frame MIAIPLUS_NATIVE_BACKGROUND_CANCEL");
                    long deadline = SystemClock.elapsedRealtime() + 40_000;
                    while (!prefs.getString("hook_report", "").startsWith("PROBE ") && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
                    hostFinished = prefs.getString("hook_report", "").startsWith("PROBE ");
                } catch (Exception failure) { report.putString("stream", report.getString("stream", "") + "cleanup=" + failure.getClass().getSimpleName() + "\n"); }
            }
            if (receiverRegistered) context.unregisterReceiver(receiver);
            for (CalculatorFrame frame : savedFrames.values()) new java.io.File(frame.path()).delete();
            if (hostStarted && !hostFinished) {
                report.putString("stream", report.getString("stream", "")
                    + "FAIL: owned host worker has not exited; settings retained until its cancellation settles\n");
                test.finish(Activity.RESULT_CANCELED, report);
                return;
            }
            if (!configured) { test.finish(code, report); return; }
            var restore = prefs.edit().clear();
            for (var entry : saved.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String v) restore.putString(entry.getKey(), v);
                else if (value instanceof Boolean v) restore.putBoolean(entry.getKey(), v);
                else if (value instanceof Integer v) restore.putInt(entry.getKey(), v);
                else if (value instanceof Long v) restore.putLong(entry.getKey(), v);
                else if (value instanceof Float v) restore.putFloat(entry.getKey(), v);
                else if (value instanceof java.util.Set<?> v) {
                    var strings = new java.util.HashSet<String>(); for (Object item : v) strings.add((String)item);
                    restore.putStringSet(entry.getKey(), strings);
                }
            }
            if (!restore.commit() || !saved.equals(prefs.getAll())) {
                code = Activity.RESULT_CANCELED;
                report.putString("stream", report.getString("stream", "") + "FAIL: original settings restore failed\n");
            }
            test.finish(code, report);
        }
    }

    private record CalculatorFrame(String nonce, String taskRef, String sessionRef, String frameRef, int step,
        int executedTaps, String path, ScreenService.Frame frame) { }
    private static CalculatorFrame calculatorFrame(Context context, String report, Object previous) throws Exception {
        var task = Pattern.compile("(?:^|,)taskRef=([a-f0-9]{16})(?:,|$)").matcher(report);
        var session = Pattern.compile("(?:^|,)sessionRef=([a-f0-9]{16})(?:,|$)").matcher(report);
        var taps = Pattern.compile("(?:^|,)executedTaps=([0-9]+)(?:,|$)").matcher(report);
        var frameRef = Pattern.compile("(?:^|,)frameRef=([a-f0-9]{16})(?:,|$)").matcher(report);
        var step = Pattern.compile("(?:^|,)observationStep=([0-9]+)(?:,|$)").matcher(report);
        if (!task.find() || !session.find() || !taps.find() || !frameRef.find() || !step.find()) return null;
        Bundle state = context.getContentResolver().call(BridgeProvider.URI, "screen_state", null, null);
        if (state == null || !state.getBoolean("active") || !task.group(1).equals(PhoneUseLog.reference(state.getString("screen_task")))) return null;
        // The executor publishes its immutable Frame before the volatile executing=false. Never wait on its monitor.
        var executing = NativeSession.class.getDeclaredField("executing"); executing.setAccessible(true);
        if (executing.getBoolean(null)) return null;
        var active = NativeSession.class.getDeclaredField("active"); active.setAccessible(true);
        Object current = active.get(null);
        if (current == null) return null;
        var id = current.getClass().getDeclaredField("id"); id.setAccessible(true);
        var background = current.getClass().getDeclaredField("background"); background.setAccessible(true);
        var observed = current.getClass().getDeclaredField("frame"); observed.setAccessible(true);
        var steps = current.getClass().getDeclaredField("steps"); steps.setAccessible(true);
        if (!session.group(1).equals(PhoneUseLog.reference((String)id.get(current))) || !background.getBoolean(current)) return null;
        ScreenService.Frame frame = (ScreenService.Frame)observed.get(current);
        if (frame == null || frame == previous || !frame.isBackground() || !(frame.foreground().equals("com.miui.calculator")
            || frame.foreground().startsWith("com.miui.calculator/"))) return null;
        int observationStep = Integer.parseInt(step.group(1));
        if (steps.getInt(current) != observationStep || !frameRef.group(1).equals(PhoneUseLog.reference(frame.image()))) return null;
        if (executing.getBoolean(null) || active.get(null) != current || observed.get(current) != frame) return null;
        String nonce = UUID.randomUUID().toString();
        java.io.File directory = new java.io.File(context.getCacheDir(), "native-background-probe");
        check(directory.isDirectory() || directory.mkdir(), "owned calculator cache unavailable");
        java.io.File file = new java.io.File(directory, nonce + ".jpg");
        try (var stream = new java.io.FileOutputStream(file)) {
            stream.write(android.util.Base64.decode(frame.image(), android.util.Base64.DEFAULT));
        }
        return new CalculatorFrame(nonce, task.group(1), session.group(1), frameRef.group(1), observationStep,
            Integer.parseInt(taps.group(1)), file.getAbsolutePath(), frame);
    }

    private static String physical() throws Exception {
        String dump = shell("dumpsys activity activities");
        var headers = Pattern.compile("Display #(\\d+)").matcher(dump);
        String section = "";
        int display = -1, from = 0;
        while (headers.find()) {
            if (display == 0) section += dump.substring(from, headers.start());
            display = Integer.parseInt(headers.group(1)); from = headers.end();
        }
        if (display == 0) section += dump.substring(from);
        var top = Pattern.compile("topResumedActivity=ActivityRecord\\{[^}]*?\\s([\\w.]+/[\\w.$.]+)").matcher(section);
        check(top.find(), "physical top activity unavailable");
        String activity = top.group(1);
        dump = shell("dumpsys window displays");
        headers = Pattern.compile("Display: mDisplayId=(\\d+)").matcher(dump);
        section = ""; display = -1; from = 0;
        while (headers.find()) {
            if (display == 0) section += dump.substring(from, headers.start());
            display = Integer.parseInt(headers.group(1)); from = headers.end();
        }
        if (display == 0) section += dump.substring(from);
        var focus = Pattern.compile("mFocusedApp=ActivityRecord\\{[^}]*?\\s([\\w.]+/[\\w.$.]+)").matcher(section);
        check(focus.find(), "physical focused app unavailable");
        return activity + "|" + focus.group(1);
    }
    private static TreeSet<String> displays() throws Exception {
        var result = new TreeSet<String>();
        var matcher = Pattern.compile("^\\s*Display Id=(\\d+)", Pattern.MULTILINE).matcher(shell("dumpsys display"));
        while (matcher.find()) result.add(matcher.group(1));
        check(!result.isEmpty(), "display list unavailable");
        return result;
    }
    private static boolean helper() throws Exception { return shell("ps -A").contains("io.github.miaiplus.helper.DisplayHelper"); }
    private static String shell(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0, "probe shell failed");
        return result;
    }
    private static void signal(Instrumentation test, String message) { Bundle status = new Bundle(); status.putString("stream", message + "\n"); test.sendStatus(1, status); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
