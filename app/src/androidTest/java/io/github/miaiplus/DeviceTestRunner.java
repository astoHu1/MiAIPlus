package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.Map;

/** Instrumented end-to-end test against scripts/mock_model.py, no paid model required. */
public final class DeviceTestRunner extends LiveProbeRunner {
    private boolean hookMode, liveMode;
    @Override public void onCreate(Bundle arguments) { liveMode = "true".equals(arguments.getString("live")); hookMode = "true".equals(arguments.getString("hook")); super.onCreate(arguments); }
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private void signal(String text) { Bundle status = new Bundle(); status.putString("stream", text + "\n"); sendStatus(1, status); }
    private void configure(String protocol) {
        Config.prefs(getTargetContext()).edit().putBoolean("enabled", true).putString("endpoint", "http://127.0.0.1:18765/v1")
            .putString("apiKey", "test-key").putString("model", "fixture-model").putString("planner", "external").putString("protocol", protocol).putInt("maxSteps", 8).commit();
    }
    private Bundle startJob(String mode, String query) {
        Bundle b = new Bundle(); b.putString("mode", mode); b.putString("query", query);
        Bundle result = getTargetContext().getContentResolver().call(BridgeProvider.URI, "start", null, b);
        check(result != null && result.getString("id") != null, "start failed: " + result); return result;
    }
    private Bundle await(String id) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < deadline) {
            Bundle result = getTargetContext().getContentResolver().call(BridgeProvider.URI, "poll", id, null);
            check(result != null && !result.containsKey("error"), "job lost");
            if (!"running".equals(result.getString("status"))) return result;
            Thread.sleep(100);
        }
        getTargetContext().getContentResolver().call(BridgeProvider.URI, "cancel", id, null);
        throw new AssertionError("test timeout");
    }
    private void checkFrame(String observation) throws Exception {
        var matcher = java.util.regex.Pattern.compile("MIAIPLUS_FRAME:([a-f0-9-]{36})").matcher(observation);
        check(matcher.find(), "frame token missing");
        var uri = android.net.Uri.parse(BridgeProvider.URI + "/frame/" + matcher.group(1));
        try (var in = getTargetContext().getContentResolver().openInputStream(uri)) {
            var json = new org.json.JSONObject(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            check(json.getString("image").length() > 1000, "native frame empty");
        }
        try {
            var unexpected = getTargetContext().getContentResolver().openInputStream(uri);
            if (unexpected != null) unexpected.close();
            throw new AssertionError("frame was replayable");
        } catch (java.io.FileNotFoundException expected) { }
    }
    @Override public void onStart() {
        if (hookMode || liveMode) { super.onStart(); return; }
        Bundle report = new Bundle();
        int resultCode = Activity.RESULT_CANCELED;
        var prefs = Config.prefs(getTargetContext()); Map<String, ?> original = prefs.getAll();
        try {
            signal("MIAIPLUS_CONNECT_SERVICE");
            long deadline = SystemClock.elapsedRealtime() + 15000;
            while (ScreenService.instance == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            check(ScreenService.instance != null, "Accessibility service not connected");
            configure("computer");
            Bundle chat = await(startJob("chat", "MIAIPLUS_CHAT_TEST").getString("id"));
            check("done".equals(chat.getString("status")) && "chat-ok".equals(chat.getString("result")), "chat failed: " + chat);
            for (String protocol : new String[]{"computer", "autoglm", "native"}) {
                configure(protocol);
                // Host launches the fixture without creating a second accessibility controller.
                signal("MIAIPLUS_LAUNCH_FIXTURE");
                long launchDeadline = SystemClock.elapsedRealtime() + 10000;
                while (FixtureActivity.current == null && SystemClock.elapsedRealtime() < launchDeadline) Thread.sleep(100);
                Activity fixture = FixtureActivity.current;
                check(fixture != null, "fixture launch failed");
                waitForIdleSync(); Thread.sleep(800);
                int[] location = new int[2]; int[] center = new int[2];
                runOnMainSync(() -> {
                    FixtureActivity.input.getLocationOnScreen(location);
                    center[0] = location[0] + FixtureActivity.input.getWidth()/2;
                    center[1] = location[1] + FixtureActivity.input.getHeight()/2;
                });
                var frame = ScreenService.instance.capture(new RunState("capture-test", android.os.Process.myUid()));
                check(frame.width() > 0 && frame.image().length() > 1000, "no screenshot");
                check("io.github.miaiplus".equals(frame.foreground()), "fixture not foreground: " + frame.foreground());
                String query = "MIAIPLUS_SCREEN_TEST " + protocol + " " + (center[0]*1000.0/frame.width()) + " " + (center[1]*1000.0/frame.height());
                Bundle result;
                if (protocol.equals("native")) {
                    // Prove no URL/key/model is needed; emulate the existing model's next tool calls.
                    prefs.edit().putString("planner", "native").putString("endpoint", "").putString("apiKey", "").putString("model", "").commit();
                    result = await(startJob("screen", "在测试页面输入你好 MiAIPlus").getString("id"));
                    check("done".equals(result.getString("status")), "native start failed: " + result);
                    String observation = result.getString("result");
                    var match = java.util.regex.Pattern.compile("会话=([a-f0-9-]{36})").matcher(observation);
                    check(match.find(), "no native session"); String session = match.group(1);
                    checkFrame(observation);
                    for (var action : new org.json.JSONObject[]{
                        new org.json.JSONObject().put("action", "tap").put("element", new org.json.JSONArray().put(center[0]*1000.0/frame.width()).put(center[1]*1000.0/frame.height())),
                        new org.json.JSONObject().put("action", "type").put("text", "你好 MiAIPlus")}) {
                        query = "MIAIPLUS:" + new org.json.JSONObject().put("session", session).put("action", action);
                        result = await(startJob("screen", query).getString("id"));
                        check("done".equals(result.getString("status")), "native action failed: " + result);
                        observation = result.getString("result"); checkFrame(observation);
                    }
                    check(observation.contains("你好 MiAIPlus"), "native observation did not confirm input");
                    query = "MIAIPLUS:" + new org.json.JSONObject().put("session", session).put("action", new org.json.JSONObject().put("action", "finish").put("message", "输入已观察确认"));
                    result = await(startJob("screen", query).getString("id"));
                } else result = await(startJob("screen", query).getString("id"));
                check("done".equals(result.getString("status")), "screen failed: " + result + " touch=" + FixtureActivity.lastTouch + " frame=" + frame.width() + "x" + frame.height() + " target=" + center[0] + "," + center[1]);
                check(FixtureActivity.lastTouch.startsWith("1:"), "gesture not delivered to fixture");
                runOnMainSync(() -> check("你好 MiAIPlus".contentEquals(FixtureActivity.input.getText()), "Chinese input failed"));
                runOnMainSync(fixture::finish); Thread.sleep(400);
            }
            configure("computer");
            String id = startJob("chat", "MIAIPLUS_CANCEL_TEST").getString("id");
            Thread.sleep(1000);
            getTargetContext().getContentResolver().call(BridgeProvider.URI, "cancel", id, null);
            check("error".equals(await(id).getString("status")), "cancel did not stop job");
            report.putString("stream", "PASS: native tool session without endpoint/key/model and one-use screenshot pipe; chat without screenshot; computer and AutoGLM screenshot→tap→Chinese input→verification→finish; cancellation.\n");
            resultCode = Activity.RESULT_OK;
        } catch (Throwable e) {
            report.putString("stream", "FAIL: " + e + "\n" + android.util.Log.getStackTraceString(e));
        } finally {
            BridgeProvider.cancelActive();
            var edit = prefs.edit().clear();
            for (var entry : original.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String v) edit.putString(entry.getKey(), v);
                else if (value instanceof Boolean v) edit.putBoolean(entry.getKey(), v);
                else if (value instanceof Integer v) edit.putInt(entry.getKey(), v);
                else if (value instanceof Long v) edit.putLong(entry.getKey(), v);
            }
            edit.commit();
        }
        finish(resultCode, report);
    }
}
