package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Host request/TTS-boundary checks; no model, microphone or audio playback is needed. */
final class SpeechPolicyRunner {
    private SpeechPolicyRunner() { }

    static void run(Instrumentation test) {
        var prefs = Config.prefs(test.getTargetContext());
        Map<String, ?> saved = prefs.getAll();
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            for (String policy : new String[]{SpeechReplyPolicy.FOLLOW_INPUT,
                SpeechReplyPolicy.TEXT_ONLY, SpeechReplyPolicy.HOST_DEFAULT}) {
                check(prefs.edit().putString(SpeechReplyPolicy.KEY, policy).remove("hook_report").commit(),
                    "Could not configure speech fixture");
                signal(test, "MIAIPLUS_RESTART_XIAOAI MIAIPLUS_SPEECH_POLICY");
                long deadline = SystemClock.elapsedRealtime() + 60_000;
                String report = "";
                while (SystemClock.elapsedRealtime() < deadline) {
                    report = prefs.getString("hook_report", "");
                    if (report.startsWith("PROBE ") || report.startsWith("hook failed")) break;
                    Thread.sleep(100);
                }
                check(report.startsWith("PROBE PASS") && report.contains("policy=" + policy),
                    "Speech fixture failed: " + report);
                signal(test, report);
            }
            result.putString("stream", "PASS: all three speech reply policies at actual host request/TTS boundaries\n");
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("stream", "PROBE FAIL: " + android.util.Log.getStackTraceString(failure));
        } finally {
            var edit = prefs.edit().clear();
            for (var entry : saved.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String v) edit.putString(entry.getKey(), v);
                else if (value instanceof Boolean v) edit.putBoolean(entry.getKey(), v);
                else if (value instanceof Integer v) edit.putInt(entry.getKey(), v);
                else if (value instanceof Long v) edit.putLong(entry.getKey(), v);
                else if (value instanceof Float v) edit.putFloat(entry.getKey(), v);
                else if (value instanceof Set<?> v) {
                    Set<String> strings = new HashSet<>();
                    for (Object item : v) strings.add((String)item);
                    edit.putStringSet(entry.getKey(), strings);
                }
            }
            if (!edit.commit() || !saved.equals(prefs.getAll())) {
                code = Activity.RESULT_CANCELED;
                result.putString("stream", result.getString("stream", "") + "FAIL: original preferences did not restore\n");
            } else result.putString("stream", result.getString("stream", "") + "Original settings restored and verified.\n");
            test.finish(code, result);
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void signal(Instrumentation test, String message) {
        Bundle status = new Bundle(); status.putString("stream", message + "\n"); test.sendStatus(1, status);
    }
}
