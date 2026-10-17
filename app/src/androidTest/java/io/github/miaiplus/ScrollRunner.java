package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.regex.Pattern;

/**
 * Device acceptance for a long collection task on the background display.
 *
 * <p>Reproduces the reported failure rather than a convenient one: a real third-party app whose list loads more
 * content by itself, driven through the product's native session for many scroll steps. The two properties under
 * test are that the session survives the whole run — it must keep the same session id, not be replaced — and that
 * the user's own screen is untouched throughout.
 *
 * <p>Deliberately sends the next step after a settle delay, so the app has time to change the page between the
 * observation the model was given and the swipe that follows it. That interleaving is what previously ended the
 * session with "页面已改变".
 */
final class ScrollRunner {
    private static final Pattern TOP_TASK = Pattern.compile("topResumedActivity=ActivityRecord\\{[^}]*?\\s([\\w.]+/[\\w.$.]+)");
    private static final Pattern SESSION = Pattern.compile("会话=([a-f0-9-]{36})");
    private static final Pattern FOREGROUND = Pattern.compile("前台=([^；\\n]*)");
    private static final String PACKAGE = "com.coolapk.market";
    private static final int STEPS = 12;

    private ScrollRunner() { }

    static void run(Instrumentation test) {
        var context = test.getTargetContext();
        var prefs = Config.prefs(context);
        var saved = prefs.getAll();
        Bundle report = new Bundle();
        int code = Activity.RESULT_CANCELED;
        var notes = new StringBuilder();
        try {
            signal(test, "MIAIPLUS_CONNECT_SERVICE");
            long deadline = SystemClock.elapsedRealtime() + 20_000;
            while (ScreenService.instance == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            check(ScreenService.instance != null, "accessibility service not connected");

            // The reported run had the step budget configured too low to finish a collection task at all.
            prefs.edit().putBoolean("enabled", true).putBoolean("backgroundDisplay", true)
                .putString("planner", "native").putString("endpoint", "").putString("apiKey", "").putString("model", "")
                .putInt("maxSteps", 40).commit();

            String taskBefore = physicalTask();

            Bundle started = start(test, "screen", "打开酷安，浏览头条列表并向下滚动收集内容");
            Bundle observation = await(test, started.getString("id"), 120_000);
            String first = observation.getString("result");
            check(first != null && first.startsWith("CONTINUE"), "session did not start: " + compact(first));
            var matcher = SESSION.matcher(first);
            check(matcher.find(), "no session id in the first observation");
            String session = matcher.group(1);
            notes.append("session=").append(session).append("; ");

            String launched = stepResult(test, session, step(session, "launch", "app", PACKAGE), "launch", 120_000);
            check(launched.startsWith("CONTINUE"), "launch did not keep the session: " + compact(launched));
            check(foregroundOf(launched).startsWith(PACKAGE), "the app is not on the background display: " + foregroundOf(launched));
            notes.append("launch=ok; ");
            recordObservation(test, "launch", launched);

            String previousSession = session;
            for (int i = 0; i < STEPS; i++) {
                // Let the feed load on its own, so the page differs from the one the last observation showed.
                Thread.sleep(1800);
                String next = stepResult(test, session,
                    step(session, "swipe", "start", new org.json.JSONArray().put(500).put(820),
                        "end", new org.json.JSONArray().put(500).put(240)), "swipe " + i, 120_000);
                check(next.startsWith("CONTINUE"), "scroll " + i + " ended the session: " + compact(next));
                var seen = SESSION.matcher(next);
                check(seen.find(), "scroll " + i + " lost the session id");
                check(seen.group(1).equals(previousSession),
                    "scroll " + i + " started a replacement session instead of continuing the task");
                check(foregroundOf(next).startsWith(PACKAGE),
                    "scroll " + i + " left the app: " + foregroundOf(next));
                recordObservation(test, "scroll-" + i, next);
                notes.append(i).append("=ok,");
            }
            notes.append(" steps=").append(STEPS).append("; ");

            String finished = stepResult(test, session, step(session, "finish", "message", "滚动采集结束"), "finish", 120_000);
            check(finished.contains("模型报告任务完成"), "finish did not end the task: " + compact(finished));

            // The invariant this feature exists for: nothing above may have moved the user's own screen.
            check(taskBefore.equals(physicalTask()),
                "the user's screen changed: " + taskBefore + " -> " + physicalTask());

            report.putString("stream", "PASS: real app collection task on the background display; "
                + notes + " user's screen held at " + taskBefore + "\n");
            code = Activity.RESULT_OK;
        } catch (Throwable e) {
            report.putString("stream", "FAIL: " + e + "\nnotes: " + notes + "\n"
                + android.util.Log.getStackTraceString(e));
        } finally {
            BridgeProvider.cancelActive();
            BackgroundDisplay.closeActive();
            var edit = prefs.edit().clear();
            for (var entry : saved.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String v) edit.putString(entry.getKey(), v);
                else if (value instanceof Boolean v) edit.putBoolean(entry.getKey(), v);
                else if (value instanceof Integer v) edit.putInt(entry.getKey(), v);
                else if (value instanceof Long v) edit.putLong(entry.getKey(), v);
            }
            edit.commit();
            test.finish(code, report);
        }
    }

    /** Builds the follow-up call; values may be strings or coordinate arrays, so this is not a String varargs. */
    private static String step(String session, String action, Object... extra) throws Exception {
        var body = new org.json.JSONObject().put("action", action);
        for (int i = 0; i + 1 < extra.length; i += 2)
            body.put(String.valueOf(extra[i]), extra[i + 1]);
        return "MIAIPLUS:" + new org.json.JSONObject().put("session", session).put("action", body);
    }

    private static String stepResult(Instrumentation test, String session, String query, String what, long limit)
        throws Exception {
        Bundle result = await(test, start(test, "screen", query).getString("id"), limit);
        String text = result.getString("result");
        check(text != null && !text.isBlank(), what + " returned no observation");
        return text;
    }

    private static Bundle start(Instrumentation test, String mode, String query) {
        Bundle b = new Bundle(); b.putString("mode", mode); b.putString("query", query);
        Bundle result = test.getTargetContext().getContentResolver().call(BridgeProvider.URI, "start", null, b);
        check(result != null && result.getString("id") != null, "start failed: " + result);
        return result;
    }

    private static Bundle await(Instrumentation test, String id, long limit) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + limit;
        while (SystemClock.elapsedRealtime() < deadline) {
            Bundle result = test.getTargetContext().getContentResolver().call(BridgeProvider.URI, "poll", id, null);
            check(result != null && !result.containsKey("error"), "job lost");
            if (!"running".equals(result.getString("status"))) return result;
            Thread.sleep(150);
        }
        throw new AssertionError("step did not finish within " + limit + "ms");
    }

    private static String foregroundOf(String observation) {
        var matcher = FOREGROUND.matcher(observation);
        return matcher.find() ? matcher.group(1).trim() : "";
    }
    private static String compact(String text) {
        return text == null ? "" : TaskProgress.compact(text).substring(0, Math.min(240, TaskProgress.compact(text).length()));
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    /** Keep device-only evidence so an unchanged page is distinguishable from a false no-progress refusal. */
    private static void recordObservation(Instrumentation test, String name, String observation) throws Exception {
        var token = Pattern.compile("MIAIPLUS_FRAME:([a-f0-9-]{36})").matcher(observation);
        check(token.find(), "observation has no screenshot capability");
        var frame = new org.json.JSONObject(new String(FrameStore.take(token.group(1), android.os.Process.myUid()),
            java.nio.charset.StandardCharsets.UTF_8));
        var directory = new java.io.File(test.getTargetContext().getExternalFilesDir(null), "scroll-diagnostics");
        check(directory.isDirectory() || directory.mkdirs(), "cannot create scroll evidence directory");
        java.nio.file.Files.write(new java.io.File(directory, name + ".jpg").toPath(),
            android.util.Base64.decode(frame.getString("image"), android.util.Base64.DEFAULT));
        java.nio.file.Files.writeString(new java.io.File(directory, name + ".txt").toPath(), observation);
    }
    private static void signal(Instrumentation test, String text) {
        Bundle status = new Bundle(); status.putString("stream", text + "\n"); test.sendStatus(1, status);
    }
    /** Read as root: an app uid is denied the task dump, and an empty read would look like "nothing moved". */
    private static String physicalTask() throws Exception {
        Process process = new ProcessBuilder("su", "-c", "dumpsys activity activities").redirectErrorStream(true).start();
        String dump = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        process.waitFor();
        var matcher = TOP_TASK.matcher(dump);
        if (!matcher.find()) throw new AssertionError("could not read the physical display's top task");
        return matcher.group(1);
    }
}
