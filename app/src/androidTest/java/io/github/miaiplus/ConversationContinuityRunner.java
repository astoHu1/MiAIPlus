package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.Map;

/** Runs owned local-conversation wake/timeout checks in the real scoped host, without a model. */
final class ConversationContinuityRunner {
    private ConversationContinuityRunner() { }

    static void run(Instrumentation test) {
        var prefs = Config.prefs(test.getTargetContext());
        Map<String, ?> saved = prefs.getAll();
        Bundle report = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            check(prefs.edit().putBoolean("enabled", true).putBoolean("globalModel", false)
                .putBoolean("backgroundDisplay", false).putString("planner", "native")
                .putInt("conversationTimeoutMinutes", 1).remove("hook_report").commit(),
                "conversation probe configuration failed");
            signal(test, "MIAIPLUS_RESTART_XIAOAI MIAIPLUS_CONVERSATION_CONTINUITY");
            long deadline = SystemClock.elapsedRealtime() + 180_000;
            String result = "", previous = "";
            while (SystemClock.elapsedRealtime() < deadline) {
                result = prefs.getString("hook_report", "");
                check(!result.startsWith("hook failed"), "host hook installation failed: " + result);
                if (result.startsWith("PROBE ")) break;
                if (result.startsWith("CONVERSATION_") && !result.equals(previous)) {
                    signal(test, result);
                    previous = result;
                }
                Thread.sleep(100);
            }
            check(result.startsWith("PROBE PASS"), result.isBlank() ? "conversation continuity probe timed out" : result);
            report.putString("stream", result + "; runner preferences restored\n");
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            report.putString("stream", "FAIL: " + failure + "\n" + android.util.Log.getStackTraceString(failure));
        } finally {
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
            if (!edit.commit()) {
                code = Activity.RESULT_CANCELED;
                report.putString("stream", report.getString("stream", "")
                    + "FAIL: conversation probe preferences did not restore\n");
            }
            test.finish(code, report);
        }
    }

    private static void signal(Instrumentation test, String message) {
        Bundle status = new Bundle(); status.putString("stream", message + "\n"); test.sendStatus(1, status);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
