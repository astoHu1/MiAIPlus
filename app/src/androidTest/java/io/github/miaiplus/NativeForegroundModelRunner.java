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

/** Runs a real configured or official-model conversation only when explicitly selected. */
final class NativeForegroundModelRunner {
    private NativeForegroundModelRunner() { }

    static void run(Instrumentation test, boolean voice, boolean official) {
        run(test, voice, official, false);
    }

    static void run(Instrumentation test, boolean voice, boolean official, boolean video) {
        var context = test.getTargetContext();
        var prefs = Config.prefs(context);
        Map<String, ?> saved = prefs.getAll();
        Bundle report = new Bundle();
        int code = Activity.RESULT_CANCELED;
        boolean hostFinished = false;
        boolean hostStarted = false;
        boolean configured = false;
        String latest = "no host report";
        TreeSet<String> initialDisplays = null;
        boolean cleaningUp = false;
        var savedFrames = new java.util.concurrent.ConcurrentHashMap<String, CalculatorFrame>();
        var pixelAcks = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        Object previousFrame = null;
        boolean receiverRegistered = false;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent intent) {
                if (!"io.github.miaiplus.NATIVE_FOREGROUND_PIXELS_VERIFIED".equals(intent.getAction())) return;
                String nonce = intent.getStringExtra("nonce");
                if (nonce != null && savedFrames.containsKey(nonce)) pixelAcks.add(nonce);
            }
        };
        try {
            Bundle busy = context.getContentResolver().call(BridgeProvider.URI, "conversation_settings", null, null);
            int hostUid = context.getPackageManager().getApplicationInfo("com.miui.voiceassist", 0).uid;
            check(busy != null && !busy.getBoolean("screenBusy") && !PluginRuntime.busy(hostUid).getBoolean("active")
                && !PluginRuntime.busy(android.os.Process.myUid()).getBoolean("active"), "another screen/plugin task is active");
            check(PluginSettings.load(prefs).find(PluginSettings.FOREGROUND_ID).enabled(), "enable foreground phone plugin first");
            if (video) {
                java.io.File reference = new java.io.File(context.getCacheDir(), "native-foreground-reference.jpg");
                check(reference.isFile() && reference.length() > 0 && reference.length() <= 20_000_000,
                    "put an owned Douyin screenshot at cache/native-foreground-reference.jpg first");
                var bounds = new android.graphics.BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(reference.getAbsolutePath(), bounds);
                check(bounds.outWidth > 0 && bounds.outHeight > 0 && (long)bounds.outWidth * bounds.outHeight <= 20_000_000,
                    "owned reference must be a decodable screenshot with at most 20 million pixels");
            }
            context.registerReceiver(receiver, new IntentFilter("io.github.miaiplus.NATIVE_FOREGROUND_PIXELS_VERIFIED"),
                "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
            signal(test, "MIAIPLUS_CONNECT_SERVICE");
            long serviceDeadline = SystemClock.elapsedRealtime() + 20_000;
            while (!ScreenService.isHealthy() && SystemClock.elapsedRealtime() < serviceDeadline) Thread.sleep(100);
            check(ScreenService.isHealthy(), "accessibility service is not healthy after connection handshake");
            initialDisplays = displays();
            var configure = prefs.edit().putBoolean("enabled", true).putString("planner", "native")
                .putInt("maxSteps", 40).remove("hook_report");
            if (official) configure.putBoolean("globalModel", false);
            check(configure.commit(), "probe configuration failed");
            configured = true;
            signal(test, "MIAIPLUS_RESTART_XIAOAI " + (video ? "MIAIPLUS_NATIVE_FOREGROUND_VIDEO"
                : voice ? "MIAIPLUS_NATIVE_FOREGROUND_VOICE" : "MIAIPLUS_NATIVE_FOREGROUND_MODEL"));
            hostStarted = true;
            long deadline = SystemClock.elapsedRealtime() + 630_000;
            String previous = "";
            while (SystemClock.elapsedRealtime() < deadline) {
                latest = prefs.getString("hook_report", "");
                if (latest.startsWith("PROBE ")) {
                    hostFinished = true;
                    check(latest.startsWith("PROBE PASS"), latest);

                    long cleanupDeadline = SystemClock.elapsedRealtime() + 10_000;
                    while (SystemClock.elapsedRealtime() < cleanupDeadline && (helper() || !initialDisplays.equals(displays()))) Thread.sleep(150);
                    check(!helper() && initialDisplays.equals(displays()), "helper/display remained after real model finish");
                    report.putString("stream", latest + "; real task helper/display released; original settings restored\n");
                    code = Activity.RESULT_OK;
                    return;
                }
                if (latest.startsWith("NATIVE_FOREGROUND_CLEANUP") || latest.startsWith("NATIVE_FOREGROUND_WAIT_OWNED_SCREEN")) cleaningUp = true;
                if (!cleaningUp && !latest.startsWith("PROBE ")) {
                    CalculatorFrame snapshot = calculatorFrame(context, latest, previousFrame,
                        video ? "com.ss.android.ugc.aweme" : "com.miui.calculator");
                    if (snapshot != null) {
                        previousFrame = snapshot.frame();
                        savedFrames.put(snapshot.nonce(), snapshot);
                        signal(test, (video ? "NATIVE_FOREGROUND_VIDEO_FRAME " : "NATIVE_FOREGROUND_CALCULATOR_FRAME ")
                            + snapshot.nonce() + " " + snapshot.path());
                    }
                    String verified;
                    while ((verified = pixelAcks.poll()) != null) {
                        CalculatorFrame frame = savedFrames.get(verified);
                        if (frame == null || !video && frame.executedTaps() < 1) continue;
                        shell("am broadcast --user 0 -a io.github.miaiplus.DEBUG_PROBE -p com.miui.voiceassist --es frame MIAIPLUS_NATIVE_FOREGROUND_PIXELS"
                            + " --es nonce " + frame.nonce() + " --es task_ref " + frame.taskRef() + " --es session_ref " + frame.sessionRef()
                            + " --es frame_ref " + frame.frameRef() + " --ei step " + frame.step());
                        signal(test, (video ? "NATIVE_FOREGROUND_VIDEO_EXPORTED " : "NATIVE_FOREGROUND_CALCULATOR_PIXELS ")
                            + frame.nonce() + (video ? ": owned target-app frame exported; identity requires manual review" : ": owned calculator result verified"));
                    }
                }
                if (!latest.equals(previous) && !latest.isBlank()) { signal(test, latest); previous = latest; }
                Thread.sleep(250);
            }
            throw new AssertionError("real foreground-model probe timed out; " + latest);
        } catch (Throwable failure) {
            report.putString("stream", "PROBE FAIL: " + failure + "; last=" + latest + "\n");
        } finally {
            if (hostStarted && !hostFinished) {
                try {
                    shell("am broadcast --user 0 -a io.github.miaiplus.DEBUG_PROBE -p com.miui.voiceassist --es frame MIAIPLUS_NATIVE_FOREGROUND_CANCEL");
                    long deadline = SystemClock.elapsedRealtime() + 40_000;
                    while (!prefs.getString("hook_report", "").startsWith("PROBE ") && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
                    hostFinished = prefs.getString("hook_report", "").startsWith("PROBE ");
                } catch (Exception failure) { report.putString("stream", report.getString("stream", "") + "cleanup=" + failure.getClass().getSimpleName() + "\n"); }
            }
            if (receiverRegistered) context.unregisterReceiver(receiver);
            test.runOnMainSync(() -> {
                ScreenshotFixtureActivity fixture = ScreenshotFixtureActivity.current;
                if (fixture != null) fixture.finish();
            });
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
    private static CalculatorFrame calculatorFrame(Context context, String report, Object previous, String targetPackage) throws Exception {
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
        if (!session.group(1).equals(PhoneUseLog.reference((String)id.get(current))) || background.getBoolean(current)) return null;
        ScreenService.Frame frame = (ScreenService.Frame)observed.get(current);
        if (frame == null || frame == previous || frame.isBackground() || !(frame.foreground().equals(targetPackage)
            || frame.foreground().startsWith(targetPackage + "/"))) return null;
        int observationStep = Integer.parseInt(step.group(1));
        if (steps.getInt(current) != observationStep || !frameRef.group(1).equals(PhoneUseLog.reference(frame.image()))) return null;
        if (executing.getBoolean(null) || active.get(null) != current || observed.get(current) != frame) return null;
        String nonce = UUID.randomUUID().toString();
        java.io.File directory = new java.io.File(context.getCacheDir(), "native-foreground-probe");
        check(directory.isDirectory() || directory.mkdir(), "owned target-app frame cache unavailable");
        java.io.File file = new java.io.File(directory, nonce + ".jpg");
        try (var stream = new java.io.FileOutputStream(file)) {
            stream.write(android.util.Base64.decode(frame.image(), android.util.Base64.DEFAULT));
        }
        return new CalculatorFrame(nonce, task.group(1), session.group(1), frameRef.group(1), observationStep,
            Integer.parseInt(taps.group(1)), file.getAbsolutePath(), frame);
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
