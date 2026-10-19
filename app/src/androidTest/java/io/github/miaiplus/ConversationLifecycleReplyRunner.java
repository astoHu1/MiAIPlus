package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

/** Verifies each generated fixture reply through accessibility or exact screenshot OCR. */
final class ConversationLifecycleReplyRunner {
    private ConversationLifecycleReplyRunner() { }

    static void run(Instrumentation test) {
        var prefs = Config.prefs(test.getTargetContext());
        Map<String, ?> saved = prefs.getAll();
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        boolean hostFinished = false;
        Set<String> verified = new HashSet<>();
        UiState last = new UiState();
        AtomicReference<String> waitingNonce = new AtomicReference<>();
        AtomicReference<String> screenshotNonce = new AtomicReference<>();
        Context target = test.getTargetContext();
        BroadcastReceiver screenshotReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!"io.github.miaiplus.CONVERSATION_SCREENSHOT_VERIFIED".equals(intent.getAction())) return;
                String nonce = intent.getStringExtra("nonce");
                if (nonce != null && nonce.equals(waitingNonce.get())) screenshotNonce.set(nonce);
            }
        };
        boolean receiverRegistered = false;
        try {
            target.registerReceiver(screenshotReceiver,
                new IntentFilter("io.github.miaiplus.CONVERSATION_SCREENSHOT_VERIFIED"), Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
            signal(test, "MIAIPLUS_CONNECT_SERVICE");
            long deadline = SystemClock.elapsedRealtime() + 20_000;
            while (ScreenService.instance == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            check(ScreenService.instance != null, "accessibility service unavailable");
            check(prefs.edit().putBoolean("enabled", true).putBoolean("globalModel", true)
                .putBoolean("backgroundDisplay", false).putString("planner", "native")
                .putInt("conversationTimeoutMinutes", 1).putString("endpoint", "http://127.0.0.1:18766/v1")
                .putString("apiKey", "test-key").putString("api", "responses")
                .putString("model", "fixture-reply-actions-model").remove("hook_report").commit(),
                "conversation reply fixture configuration failed");
            signal(test, "MIAIPLUS_RESTART_XIAOAI MIAIPLUS_CONVERSATION_REPLIES");
            deadline = SystemClock.elapsedRealtime() + 360_000;
            String stage = "not-started";
            String previousUi = "";
            while (SystemClock.elapsedRealtime() < deadline) {
                String report = prefs.getString("hook_report", "");
                if (report.startsWith("PROBE FAIL") || report.startsWith("hook failed")) {
                    hostFinished = true;
                    throw new AssertionError(report + "; stage=" + stage + "; visibleReplies=" + verified.size() + "; UI=" + last);
                }
                if (report.startsWith("PROBE PASS")) {
                    hostFinished = true;
                    check(verified.size() == 4, "host finished without four distinct visible replies");
                    result.putString("stream", report + "; four distinct reply bodies verified through accessibility or screenshot OCR; loopback Responses fixture\n");
                    code = Activity.RESULT_OK;
                    break;
                }
                if (report.startsWith("CONVERSATION_REPLY_WAIT_UI ")) {
                    String[] fields = report.split(" ");
                    check(fields.length == 3 && fields[2].matches("[a-f0-9-]{36}"), "invalid owned reply handshake");
                    stage = fields[1];
                    String nonce = fields[2];
                    if (!verified.contains(nonce)) {
                        if (!nonce.equals(waitingNonce.get())) {
                            waitingNonce.set(nonce);
                            screenshotNonce.set(null);
                            // The external Mac Vision check confirms this exact full token in a screenshot.
                            signal(test, "CONVERSATION_REPLY_SCREENSHOT " + stage + " " + nonce);
                        }
                        last = visible(test, "miaiplus-ok-" + nonce.substring(0, 8));
                        if (!last.toString().equals(previousUi)) {
                            previousUi = last.toString();
                            signal(test, "CONVERSATION_REPLY_UI " + stage + ": " + last);
                        }
                        boolean screenshotVerified = nonce.equals(screenshotNonce.get());
                        if (last.found || screenshotVerified) {
                            shell("am broadcast --user 0 -a io.github.miaiplus.DEBUG_PROBE -p com.miui.voiceassist --es frame MIAIPLUS_CONVERSATION_REPLY_READY --es nonce " + nonce);
                            verified.add(nonce);
                            waitingNonce.compareAndSet(nonce, null);
                            screenshotNonce.compareAndSet(nonce, null);
                            signal(test, "CONVERSATION_REPLY_VISIBLE " + stage + ": "
                                + (last.found ? "unique full reply found in accessibility tree"
                                    : "unique full reply verified by screenshot OCR"));
                        }
                    }
                }
                Thread.sleep(100);
            }
            check(code == Activity.RESULT_OK, "conversation reply test timed out; visibleReplies=" + verified.size() + "; UI=" + last);
        } catch (Throwable error) {
            result.putString("stream", "PROBE FAIL: " + android.util.Log.getStackTraceString(error));
        } finally {
            if (!hostFinished) {
                try {
                    shell("am broadcast --user 0 -a io.github.miaiplus.DEBUG_PROBE -p com.miui.voiceassist --es frame MIAIPLUS_CONVERSATION_REPLY_CANCEL");
                    long deadline = SystemClock.elapsedRealtime() + 40_000;
                    while (!prefs.getString("hook_report", "").startsWith("PROBE ") && SystemClock.elapsedRealtime() < deadline)
                        Thread.sleep(100);
                } catch (Exception error) {
                    result.putString("stream", result.getString("stream", "") + "\nCleanup: " + error.getClass().getSimpleName());
                }
            }
            if (receiverRegistered) target.unregisterReceiver(screenshotReceiver);
            var restore = prefs.edit().clear();
            for (var entry : saved.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String v) restore.putString(entry.getKey(), v);
                else if (value instanceof Boolean v) restore.putBoolean(entry.getKey(), v);
                else if (value instanceof Integer v) restore.putInt(entry.getKey(), v);
                else if (value instanceof Long v) restore.putLong(entry.getKey(), v);
                else if (value instanceof Float v) restore.putFloat(entry.getKey(), v);
                else if (value instanceof Set<?> v) {
                    Set<String> strings = new HashSet<>();
                    for (Object item : v) if (item instanceof String string) strings.add(string);
                    restore.putStringSet(entry.getKey(), strings);
                }
            }
            if (!restore.commit() || !prefs.getAll().equals(saved)) {
                code = Activity.RESULT_CANCELED;
                result.putString("stream", result.getString("stream", "") + "\nFAIL: fixture configuration did not restore");
            }
            test.finish(code, result);
        }
    }

    private static final class UiState {
        boolean found, hiddenExpected, truncated;
        int nodes, visibleText, text, webViews;
        final Set<String> windows = new TreeSet<>(), resources = new TreeSet<>(), classes = new TreeSet<>();
        @Override public String toString() { return "found=" + found + ",hiddenExpected=" + hiddenExpected
            + ",nodes=" + nodes + ",textNodes=" + text + ",visibleTextNodes=" + visibleText + ",webViews=" + webViews
            + ",truncated=" + truncated + ",windows=" + windows + ",resources=" + resources + ",classes=" + classes; }
    }

    private static UiState visible(Instrumentation test, String expected) {
        UiState state = new UiState();
        test.runOnMainSync(() -> {
            ScreenService service = ScreenService.instance;
            if (service == null) { state.windows.add("service-unavailable"); return; }
            for (var window : service.getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                state.windows.add("id=" + window.getId() + ",type=" + window.getType() + ",focused=" + window.isFocused()
                    + ",package=" + (root == null ? "null" : root.getPackageName()));
                if (root != null && "com.miui.voiceassist".contentEquals(root.getPackageName())) scanRoot(root, expected, state);
            }
            AccessibilityNodeInfo active = service.getRootInActiveWindow();
            if (active != null && "com.miui.voiceassist".contentEquals(active.getPackageName())) scanRoot(active, expected, state);
        });
        return state;
    }

    private static void scanRoot(AccessibilityNodeInfo root, String expected, UiState state) {
        StringBuilder visibleText = new StringBuilder();
        StringBuilder visibleDescriptions = new StringBuilder();
        scan(root, expected, new int[]{0}, state, visibleText, visibleDescriptions);
        // Android may expose wrapped rich text as separate nodes. The fixed reply token contains
        // no spaces, so normalize layout whitespace and join only visible nodes in traversal order.
        state.found |= compact(visibleText).contains(expected) || compact(visibleDescriptions).contains(expected);
    }

    private static void scan(AccessibilityNodeInfo node, String expected, int[] count, UiState state,
        StringBuilder visibleText, StringBuilder visibleDescriptions) {
        if (count[0]++ >= 2400) { state.truncated = true; return; }
        state.nodes++;
        boolean visible = node.isVisibleToUser();
        CharSequence text = node.getText(), description = node.getContentDescription();
        if (text != null || description != null) {
            state.text++;
            if (visible) state.visibleText++;
        }
        boolean matches = compact(text).contains(expected) || compact(description).contains(expected);
        if (visible) {
            state.found |= matches;
            if (text != null && visibleText.length() < 100_000) visibleText.append(text);
            if (description != null && visibleDescriptions.length() < 100_000) visibleDescriptions.append(description);
        } else state.hiddenExpected |= matches;
        String id = node.getViewIdResourceName();
        if (id != null && state.resources.size() < 48) state.resources.add(id);
        String type = String.valueOf(node.getClassName());
        if (state.classes.size() < 24) state.classes.add(type);
        if (type.contains("WebView")) state.webViews++;
        for (int index = 0; index < node.getChildCount() && count[0] < 2400; index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) scan(child, expected, count, state, visibleText, visibleDescriptions);
        }
        if (count[0] >= 2400) state.truncated = true;
    }

    private static String compact(CharSequence value) { return value == null ? "" : value.toString().replaceAll("\\s+", ""); }

    private static void signal(Instrumentation test, String text) { Bundle status = new Bundle(); status.putString("stream", text + "\n"); test.sendStatus(1, status); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void shell(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0)
            throw new AssertionError("owned reply control broadcast failed: " + output);
    }
}
