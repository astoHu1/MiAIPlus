package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.regex.Pattern;

/**
 * Device acceptance for the background display: drive a real control task on the independent screen while proving
 * the user's own screen never moves.
 *
 * <p>Deliberately hostile to a false pass. The user's screen is held by the debug fixture, whose own
 * {@code dispatchTouchEvent} records everything that reaches it, and the physical display's top task and focused
 * app are re-read from the task dump before and after the task. A run that quietly retargeted the physical screen
 * would move that top task, change that focus, or deliver a synthetic touch to the fixture — all three are
 * asserted against. Comparing screenshot hashes would prove nothing, because the user's own screen changes anyway.
 *
 * <p>Before measuring, the runner places the user's fixture and opens its keyboard. A continuous guard latches
 * any loss of IME visibility, window/field focus or the served input connection, even if it recovers later.
 *
 * <p>Runs the native planner with direct action JSON: no model, endpoint, key or network request is involved, so
 * this measures the display backend rather than a model's choices.
 */
final class BackgroundRunner {
    private static final Pattern TOP_TASK = Pattern.compile("topResumedActivity=ActivityRecord\\{[^}]*?\\s([\\w.]+/[\\w.$.]+)");
    private static final Pattern FOCUSED_APP = Pattern.compile("mFocusedApp=ActivityRecord\\{[^}]*?\\s([\\w.]+/[\\w.$.]+)");
    private static final Pattern SESSION = Pattern.compile("会话=([a-f0-9-]{36})");
    private static final Pattern FOREGROUND = Pattern.compile("前台=([^；\\n]*)");
    private static final Pattern GEOMETRY = Pattern.compile("屏幕=(\\d+x\\d+)");
    /** This runner's explicit canvas; a physical capture would report the panel's own size instead. */
    private static final String BACKGROUND_GEOMETRY = "1080x1920";
    /** Typed only on the background screen, so a leak into the user's window would be visible. */
    private static final String CANARY = "后台输入隔离标记";

    private BackgroundRunner() { }

    static void run(Instrumentation test) {
        var context = test.getTargetContext();
        var prefs = Config.prefs(context);
        var saved = prefs.getAll();
        Bundle report = new Bundle();
        int code = Activity.RESULT_CANCELED;
        var notes = new StringBuilder();
        PhysicalImeGuard keyboard = null;
        try {
            // Enabling the service happens host-side in response to this signal; wait for the connection instead
            // of asserting it, because connecting is setup and does not move the physical display's top task.
            signal(test, "MIAIPLUS_CONNECT_SERVICE");
            long serviceDeadline = SystemClock.elapsedRealtime() + 20_000;
            while (ScreenService.instance == null && SystemClock.elapsedRealtime() < serviceDeadline) Thread.sleep(100);
            check(ScreenService.instance != null, "accessibility service not connected");
            // Placed here rather than by the host: `am instrument` restarts the target process, so a fixture the
            // host launched beforehand is already gone by the time this runs. Taking the fixture up first and the
            // baseline after keeps the invariant honest — everything measured below starts from the user's screen
            // already being where it must stay.
            test.startActivitySync(new android.content.Intent(context, FixtureActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            long fixtureDeadline = SystemClock.elapsedRealtime() + 10_000;
            while (FixtureActivity.current == null && SystemClock.elapsedRealtime() < fixtureDeadline) Thread.sleep(100);
            check(FixtureActivity.current != null, "could not place the physical fixture");
            Thread.sleep(1200);
            // Native planner, no model configuration: the display backend is what is under test here.
            prefs.edit().putBoolean("enabled", true).putBoolean("backgroundDisplay", true)
                .putString("planner", "native").putString("endpoint", "").putString("apiKey", "").putString("model", "")
                .putInt("maxSteps", 8)
                .putInt("displayWidth", 1080).putInt("displayHeight", 1920).putInt("displayDensity", 320).commit();

            String taskBefore = physicalTask(), focusBefore = physicalFocus();
            var displaysBefore = displayIds();
            check(taskBefore.startsWith("io.github.miaiplus/"), "fixture is not the user's screen: " + taskBefore);
            FixtureActivity.lastTouch = "none";
            keyboard = PhysicalImeGuard.start(test);
            keyboard.assertHeld("native-create");

            Bundle started = start(test, "screen", "在后台屏幕打开计算器，输入一段中文并确认页面，然后结束");
            String id = started.getString("id");
            Bundle observation = await(test, id, 90_000);
            String first = observation.getString("result");
            check(first != null && !first.isBlank(), "no observation returned");
            var sessionMatcher = SESSION.matcher(first);
            check(sessionMatcher.find(), "no background session in observation: " + TaskProgress.compact(first));
            String session = sessionMatcher.group(1);
            keyboard.assertHeld("native-launch");
            // The first observation must describe the background screen. The user's screen is the fixture, so the
            // observation naming it as the foreground means the backend fell through to display 0. Checked on the
            // parsed field, not on a substring: the text also lists launchable apps, which legitimately includes
            // this module's own package and would make a substring test fail for the wrong reason.
            check(!FOREGROUND.matcher(first).find() || !"io.github.miaiplus".equals(foregroundOf(first)),
                "observation leaked the user's screen: " + TaskProgress.compact(first));
            check(BACKGROUND_GEOMETRY.equals(geometryOf(first)),
                "observation was not taken at the background display's geometry: " + geometryOf(first));
            notes.append("goal=").append(TaskProgress.compact(first)).append("; ");

            // The observation says whether this ROM exposes a tree for a virtual display. It decides what the
            // type step below can even mean, so it is recorded rather than assumed.
            boolean treeAvailable = !first.contains("未提供后台屏的无障碍树");
            notes.append("tree=").append(treeAvailable ? "available" : "unavailable").append("; ");

            // Each action is asserted to have been *executed*, not merely attempted. A refusal fails the session
            // closed, so back and finish must run before anything that can be refused.
            String launched = stepResult(test, session, step(session, "launch", "app", "com.miui.calculator"), "launch");
            check(launched.startsWith("CONTINUE"), "launch did not keep the background session alive: " + launched);
            keyboard.assertHeld("native-back");
            notes.append("launch=ok; ");

            // Back exercises a display-bound key rather than a global navigation action.
            String backed = stepResult(test, session, step(session, "back", null, null), "back");
            check(backed.startsWith("CONTINUE"), "back did not keep the background session alive: " + backed);
            keyboard.assertHeld("native-finish");
            notes.append("back=ok; ");

            // The launch observation answers the tree question for a populated screen: a freshly created
            // own-content display composites no windows, so the pre-launch reading measured a different thing.
            // Recorded rather than asserted, because vision-only operation is a documented mode here.
            boolean treeAfterLaunch = !launched.contains("未提供后台屏的无障碍树");
            notes.append("treeAfterLaunch=").append(treeAfterLaunch ? "available" : "unavailable").append("; ");

            String finished = stepResult(test, session, step(session, "finish", "message", "后台任务结束"), "finish");
            check(finished.contains("模型报告任务完成"), "finish did not end the task: " + finished);
            keyboard.assertHeld("typing-create");
            notes.append("finish=ok; ");

            // Typing gets its own page and its own display session: the calculator has no editable field, so
            // exercising type there would prove nothing either way. This goes through the same product path the
            // agent uses — BackgroundActions, which resolves the display's own foreground and writes to that
            // page's editable node — rather than calling the setter directly.
            RunState typingRun = new RunState("background-typing", android.os.Process.myUid());
            try (BackgroundDisplay typing = BackgroundDisplay.open(context, typingRun, new Config(context))) {
                keyboard.assertHeld("typing-launch");
                typing.launch(typingRun, "io.github.miaiplus.test/io.github.miaiplus.TypingFixtureActivity");
                ScreenService.Frame typingFrame = typing.observe(typingRun);
                notes.append("typingTree=").append(typingFrame.tree().isEmpty() ? "empty" : "present").append("; ");
                check(!typingFrame.tree().isEmpty(), "no accessibility tree for an app on the background display");
                check(typingFrame.tree().contains("EditText"), "the typing page has no editable node to write to");
                notes.append("typingForeground=").append(typingFrame.foreground()).append("; ");

                // Tapping a real editor exercises the app's own request to show a keyboard. Launch alone and
                // ACTION_SET_TEXT alone do not cover the single-IME migration reported by users.
                keyboard.assertHeld("typing-tap-editor");
                android.graphics.Rect editor = editableBounds(test, typing.session().displayId);
                DisplaySession canvas = typing.session();
                Action tap = new Action(new org.json.JSONObject().put("action", "tap").put("element",
                    new org.json.JSONArray().put(editor.exactCenterX() * 1000.0 / canvas.width)
                        .put(editor.exactCenterY() * 1000.0 / canvas.height)));
                BackgroundActions.execute(typing, tap, typingRun, new Config(context));
                Thread.sleep(400);
                keyboard.assertHeld("typing-set-text");

                // Goes through the product's own action path, so what is measured is what the agent would get.
                Action type = new Action(new org.json.JSONObject().put("action", "type").put("text", CANARY));
                BackgroundActions.execute(typing, type, typingRun, new Config(context));
                notes.append("type=ok; ");

                // The authoritative check is what the next observation shows: the agent judges success from the
                // tree, and the fixture runs in the test APK's own process, so reading its field here would read
                // a different process's copy of the field and prove nothing either way.
                String afterWrite = typing.observe(typingRun).tree();
                check(afterWrite.contains(CANARY), "the typed text did not reach the background page: "
                    + afterWrite.substring(0, Math.min(300, afterWrite.length())));
                notes.append("typeVerified=inBackgroundTree; ");

                // With the background IME hidden, BACK must leave the background page, rather than dismiss
                // the user's keyboard. Both effects are checked; injection acceptance alone proves neither.
                keyboard.assertHeld("typing-back");
                BackgroundActions.execute(typing,
                    new Action(new org.json.JSONObject().put("action", "back")), typingRun, new Config(context));
                long backDeadline = SystemClock.uptimeMillis() + 5000;
                while (typing.foregroundPackage(typingRun).startsWith("io.github.miaiplus.test/")
                    && SystemClock.uptimeMillis() < backDeadline) {
                    keyboard.assertHeld("typing-back");
                    Thread.sleep(100);
                }
                check(!typing.foregroundPackage(typingRun).startsWith("io.github.miaiplus.test/"),
                    "background BACK did not leave the typing fixture; it may have dismissed an IME instead");
                keyboard.assertHeld("typing-teardown");
            }
            Thread.sleep(400);
            keyboard.assertHeld("after-teardown");

            // The settings capability probe owns a separate helper and creation path. Run it only after both
            // product sessions have closed, while the same physical keyboard is still being monitored.
            check(BackgroundDisplay.active() == null || !BackgroundDisplay.active().isOpen(),
                "product display is still active before capability probe");
            check(displayIds().equals(displaysBefore), "product display leaked before capability probe");
            keyboard.assertHeld("capability-create-launch-input-teardown");
            org.json.JSONObject capability = BackgroundDisplay.capability(context,
                "io.github.miaiplus.test/io.github.miaiplus.TypingFixtureActivity",
                new RunState("background-ime-capability", android.os.Process.myUid()));
            org.json.JSONArray items = new org.json.JSONArray(capability.getString("items"));
            org.json.JSONObject creation = null;
            for (int index = 0; index < items.length(); index++) {
                org.json.JSONObject item = items.getJSONObject(index);
                if (item.optString("item").equals("createOwnDisplay")) creation = item;
            }
            check(creation != null && creation.optString("status").equals("supported"),
                "capability probe did not create a real isolated display: " + items);
            check("HIDE".equals(creation.optString("imePolicy")),
                "capability probe did not verify HIDE IME policy: " + creation);
            Thread.sleep(400);
            keyboard.assertHeld("after-capability-teardown");
            check(displayIds().equals(displaysBefore), "capability probe left a display behind");
            notes.append("capabilityCreate=verified; capabilityIme=HIDE; ");

            // The invariant: the user's screen is exactly where they left it.
            String taskAfter = physicalTask(), focusAfter = physicalFocus();
            check(taskBefore.equals(taskAfter),
                "the user's screen changed during the background task: " + taskBefore + " -> " + taskAfter);
            check(focusBefore.equals(focusAfter),
                "the user's focused app changed during the background task: " + focusBefore + " -> " + focusAfter);
            check("none".equals(FixtureActivity.lastTouch),
                "the agent delivered input to the user's screen: " + FixtureActivity.lastTouch);
            test.runOnMainSync(() -> check(FixtureActivity.input.getText().length() == 0,
                "the canary text reached the user's own input field"));
            check(FixtureActivity.current != null, "the user's screen was destroyed during the task");

            // Teardown: no display the module created may outlive the task.
            var displaysAfter = displayIds();
            check(displaysAfter.equals(displaysBefore), "displays changed after the task: " + displaysBefore + " -> " + displaysAfter);
            keyboard.assertHeld("final-check");
            keyboard.close();
            notes.append(keyboard.evidence());

            report.putString("stream", "PASS: background task with the user's screen held at " + taskBefore
                + "; no touch or text reached it; displays " + displaysAfter + "; " + notes + "\n");
            code = Activity.RESULT_OK;
        } catch (Throwable e) {
            if (keyboard != null) notes.append(keyboard.evidence());
            report.putString("stream", "FAIL: " + e + "\n" + android.util.Log.getStackTraceString(e) + "\nnotes: " + notes);
        } finally {
            if (keyboard != null) keyboard.close();
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

    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static android.graphics.Rect editableBounds(Instrumentation test, int displayId) {
        android.graphics.Rect bounds = new android.graphics.Rect();
        test.runOnMainSync(() -> {
            var windows = ScreenService.instance.getWindowsOnAllDisplays().get(displayId);
            check(windows != null, "background typing display has no accessibility windows");
            for (var window : windows) {
                var root = window.getRoot();
                if (root == null || !"io.github.miaiplus.test".equals(String.valueOf(root.getPackageName()))) continue;
                var editable = findEditable(root);
                if (editable != null) { editable.getBoundsInScreen(bounds); break; }
            }
            check(!bounds.isEmpty(), "could not locate the background editor for a real tap");
        });
        return bounds;
    }
    /** Mirrors the product's search so the probe measures the same node the action would pick. */
    private static android.view.accessibility.AccessibilityNodeInfo findEditable(
        android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isVisibleToUser()) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            var found = findEditable(node.getChild(index));
            if (found != null) return found;
        }
        return null;
    }
    /** Runs one follow-up action and returns its observation text, so each step's outcome can be judged. */
    private static String stepResult(Instrumentation test, String session, String query, String what) throws Exception {
        Bundle result = await(test, start(test, "screen", query).getString("id"), 60_000);
        String text = result.getString("result");
        check(text != null && !text.isBlank(), what + " returned no observation");
        return text;
    }
    private static String foregroundOf(String observation) {
        var matcher = FOREGROUND.matcher(observation);
        return matcher.find() ? matcher.group(1).trim() : "";
    }
    private static String geometryOf(String observation) {
        var matcher = GEOMETRY.matcher(observation);
        return matcher.find() ? matcher.group(1) : "none";
    }
    private static void signal(Instrumentation test, String text) {
        Bundle status = new Bundle(); status.putString("stream", text + "\n"); test.sendStatus(1, status);
    }

    /**
     * Runs a read-only dump as root. A normal app uid is denied most of these services, and an empty dump would
     * read as "the user's screen never changed" — a false pass — so the call is made where it can actually answer.
     */
    private static String shell(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        process.waitFor();
        return output;
    }
    /** The physical display's own task and focus, read from the task dump rather than inferred from a screenshot. */
    private static String physicalTask() throws Exception { return match(shell("dumpsys activity activities"), TOP_TASK, "top task"); }
    private static String physicalFocus() throws Exception { return match(shell("dumpsys activity activities"), FOCUSED_APP, "focused app"); }
    private static String match(String dump, Pattern pattern, String what) {
        var matcher = pattern.matcher(dump);
        if (!matcher.find()) throw new AssertionError("could not read the physical display's " + what);
        return matcher.group(1);
    }
    /** Live logical display ids, so teardown is asserted rather than assumed. */
    private static java.util.TreeSet<String> displayIds() throws Exception {
        var ids = new java.util.TreeSet<String>();
        var matcher = Pattern.compile("^\\s*Display Id=(\\d+)", Pattern.MULTILINE).matcher(shell("dumpsys display"));
        while (matcher.find()) ids.add(matcher.group(1));
        return ids;
    }

    private static Bundle start(Instrumentation test, String mode, String query) {
        Bundle b = new Bundle(); b.putString("mode", mode); b.putString("query", query);
        Bundle result = test.getTargetContext().getContentResolver().call(BridgeProvider.URI, "start", null, b);
        check(result != null && result.getString("id") != null, "start failed: " + result);
        return result;
    }
    private static Bundle await(Instrumentation test, String id, long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            Bundle result = test.getTargetContext().getContentResolver().call(BridgeProvider.URI, "poll", id, null);
            check(result != null, "job lost");
            if (!"running".equals(result.getString("status"))) return result;
            Thread.sleep(120);
        }
        test.getTargetContext().getContentResolver().call(BridgeProvider.URI, "cancel", id, null);
        throw new AssertionError("task timed out");
    }
    /** One native-planner follow-up action in the shape the tool contract defines. */
    private static String step(String session, String action, String key, String value) throws Exception {
        var body = new org.json.JSONObject().put("action", action);
        if (key != null) body.put(key, value);
        return "MIAIPLUS:" + new org.json.JSONObject().put("session", session).put("action", body);
    }
}
