package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Map;

/** Reads only a fresh probe conversation; never dumps arbitrary conversation text. */
final class ReplyActionsRunner {
    private ReplyActionsRunner() { }

    static void run(Instrumentation instrumentation, String api, boolean currentConfig) {
        var context = instrumentation.getTargetContext();
        var prefs = Config.prefs(context);
        Map<String, ?> saved = prefs.getAll();
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        boolean hostFinished = false;
        UiState last = new UiState();
        try {
            signal(instrumentation, "MIAIPLUS_CONNECT_SERVICE");
            long serviceDeadline = SystemClock.elapsedRealtime() + 20_000;
            while (ScreenService.instance == null && SystemClock.elapsedRealtime() < serviceDeadline) Thread.sleep(100);
            if (ScreenService.instance == null) throw new AssertionError("accessibility service unavailable");
            var edit = prefs.edit().putBoolean("enabled", true).putBoolean("globalModel", true).remove("hook_report");
            if (currentConfig) {
                Config config = new Config(saved);
                if (config.endpoint.isEmpty() || config.apiKey.isEmpty() || config.model.isEmpty())
                    throw new AssertionError("saved external model configuration unavailable");
                edit.putString("api", api);
            } else edit.putString("endpoint", "http://127.0.0.1:18766/v1").putString("apiKey", "test-key")
                .putString("api", api).putString("model", "fixture-reply-actions-model");
            edit.commit();
            signal(instrumentation, "MIAIPLUS_RESTART_XIAOAI MIAIPLUS_REPLY_ACTIONS");
            long deadline = SystemClock.elapsedRealtime() + 120_000;
            boolean sent = false;
            String previousState = "";
            while (SystemClock.elapsedRealtime() < deadline) {
                String report = prefs.getString("hook_report", "");
                if (report.startsWith("PROBE FAIL")) { hostFinished = true; throw new AssertionError(report + "; UI=" + last); }
                if (report.startsWith("PROBE PASS")) {
                    hostFinished = true;
                    if (!sent) throw new AssertionError("host finished before UI verification");
                    result.putString("stream", report + (currentConfig ? "; saved model configuration, forced " + api : "; mock " + api) + "\n");
                    code = Activity.RESULT_OK;
                    return;
                }
                if (!sent && report.equals("REPLY_ACTIONS_WAIT_UI")) {
                    UiState state = new UiState();
                    instrumentation.runOnMainSync(() -> {
                        try {
                            ScreenService service = ScreenService.instance;
                            if (service == null) { state.windows.add("service-unavailable"); return; }
                            for (var window : service.getWindows()) {
                                AccessibilityNodeInfo root = window.getRoot();
                                state.windows.add("window=" + window.getId() + ",type=" + window.getType()
                                    + ",focused=" + window.isFocused() + ",package=" + (root == null ? "null" : root.getPackageName()));
                                if (root != null && "com.miui.voiceassist".contentEquals(root.getPackageName())) scan(root, false, state);
                            }
                            AccessibilityNodeInfo active = service.getRootInActiveWindow();
                            if (active != null && "com.miui.voiceassist".contentEquals(active.getPackageName())) scan(active, false, state);
                        } catch (RuntimeException error) { state.windows.add("accessibility=" + error.getClass().getSimpleName()); }
                    });
                    last = state;
                    if (!state.toString().equals(previousState)) {
                        previousState = state.toString();
                        signal(instrumentation, "REPLY_ACTIONS_UI: " + state);
                    }
                    if (state.copy && state.retry) {
                        shell("am broadcast --user 0 -a io.github.miaiplus.DEBUG_PROBE -p com.miui.voiceassist --es frame MIAIPLUS_REPLY_ACTIONS_READY");
                        sent = true;
                        signal(instrumentation, "REPLY_ACTIONS_VISIBLE: enabled copy/retry controls; native reply checked by host");
                    }
                }
                Thread.sleep(150);
            }
            throw new AssertionError("reply actions timed out; " + last);
        } catch (Throwable error) {
            result.putString("stream", "PROBE FAIL: " + android.util.Log.getStackTraceString(error));
        } finally {
            // Keep the fixture endpoint active until the host has canceled its exact owned chat.
            if (!hostFinished) {
                try {
                    shell("am broadcast --user 0 -a io.github.miaiplus.DEBUG_PROBE -p com.miui.voiceassist --es frame MIAIPLUS_REPLY_ACTIONS_CANCEL");
                    long cleanupDeadline = SystemClock.elapsedRealtime() + 40_000;
                    do {
                        if (prefs.getString("hook_report", "").startsWith("PROBE ")) break;
                        Thread.sleep(100);
                    } while (SystemClock.elapsedRealtime() < cleanupDeadline);
                } catch (Exception cleanup) {
                    result.putString("stream", result.getString("stream", "") + "\nProbe cleanup: " + cleanup.getClass().getSimpleName());
                }
            }
            var restore = prefs.edit().clear();
            for (var entry : saved.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String v) restore.putString(entry.getKey(), v);
                else if (value instanceof Boolean v) restore.putBoolean(entry.getKey(), v);
                else if (value instanceof Integer v) restore.putInt(entry.getKey(), v);
                else if (value instanceof Long v) restore.putLong(entry.getKey(), v);
                else if (value instanceof Float v) restore.putFloat(entry.getKey(), v);
                else if (value instanceof java.util.Set<?> v) {
                    java.util.Set<String> strings = new java.util.HashSet<>();
                    for (Object item : v) if (item instanceof String string) strings.add(string);
                    restore.putStringSet(entry.getKey(), strings);
                }
            }
            restore.commit();
            instrumentation.finish(code, result);
        }
    }

    private static final class UiState {
        boolean copy, retry;
        int count;
        final java.util.Set<String> controls = new java.util.TreeSet<>();
        final java.util.Set<String> windows = new java.util.TreeSet<>();
        @Override public String toString() { return "copy=" + copy + ",retry=" + retry
            + ",nodes=" + count + ",windows=" + windows + ",controls=" + controls; }
    }

    private static void scan(AccessibilityNodeInfo node, boolean clickableParent, UiState state) {
        if (state.count++ >= 1200) return;
        boolean visible = node.isVisibleToUser();
        boolean clickable = node.isEnabled() && (node.isClickable() || clickableParent);
        String id = node.getViewIdResourceName();
        String label = (String.valueOf(node.getText()) + " " + String.valueOf(node.getContentDescription())).toLowerCase(java.util.Locale.ROOT);
        String identity = (id == null ? "" : id.toLowerCase(java.util.Locale.ROOT)) + " " + label;
        if (visible && clickable && (identity.contains("copy") || identity.contains("复制"))) state.copy = true;
        if (visible && clickable && (identity.contains("retry") || identity.contains("re_answer") || identity.contains("regenerate")
            || identity.contains("重试") || identity.contains("重新生成") || identity.contains("重新回答"))) state.retry = true;
        boolean action = identity.contains("copy") || identity.contains("retry") || identity.contains("re_answer")
            || identity.contains("regenerate") || identity.contains("feedback") || identity.contains("share")
            || label.contains("复制") || label.contains("重试") || label.contains("重新回答") || label.contains("重新生成")
            || label.contains("分享") || label.contains("停止生成");
        if (visible && clickable && action && state.controls.size() < 20) {
            android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
            // Record only resource identities and a fixed allowlist of generic action labels.
            String generic = label.contains("复制") ? "copy" : label.contains("重新") || label.contains("重试") ? "retry"
                : label.contains("分享") ? "share" : label.contains("停止") ? "stop" : "";
            state.controls.add((id == null ? String.valueOf(node.getClassName()) : id) + ":" + generic + "@" + bounds.toShortString());
        }
        for (int i = 0; i < node.getChildCount() && state.count < 1200; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) scan(child, clickable, state);
        }
    }
    private static void signal(Instrumentation instrumentation, String message) {
        Bundle status = new Bundle(); status.putString("stream", message + "\n"); instrumentation.sendStatus(1, status);
    }
    private static void shell(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0)
            throw new AssertionError("test-control broadcast failed: " + output);
    }
}
