package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.lifecycle.ViewModelProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Real Compose UI checks with synthetic settings only; never calls a model. */
final class EndpointProfilesRunner {
    private final Instrumentation test;
    private final UiAutomation ui;
    private MainActivity activity;
    private SettingsState state;

    private EndpointProfilesRunner(Instrumentation test) {
        this.test = test;
        ui = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    }

    static void run(Instrumentation test) { new EndpointProfilesRunner(test).run(); }

    private void run() {
        var context = test.getTargetContext();
        var prefs = Config.prefs(context);
        Map<String, ?> original = prefs.getAll();
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        boolean changed = false;
        try {
            Bundle screen = context.getContentResolver().call(BridgeProvider.URI, "screen_state", null, null);
            Bundle health = context.getContentResolver().call(BridgeProvider.URI, "accessibility_health", null, null);
            check(screen != null && !screen.getBoolean("active") && health != null
                && !health.getBoolean("busy") && !health.getBoolean("preparing"), "Stop active work before settings tests");
            changed = true;
            check(prefs.edit().clear().putString("endpoint", "https://a.example.com/v1")
                .putString("apiKey", "fixture-a-key").putString("model", "fixture-a")
                .putString("api", "responses").putString("protocol", "computer").commit(), "Fixture save failed");
            activity = (MainActivity) test.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            onMain(() -> state = new ViewModelProvider(activity).get(SettingsState.class));
            check("phone_use".equals(state.getConfig().protocol), "Legacy protocol migration failed");
            click("模型 API");
            edit("默认配置", "服务 A");
            waitFor(state::getDirty, "Profile edit did not reach draft");
            check(!"服务 A".equals(new EndpointProfiles(prefs).active().name), "Draft saved without confirmation");
            checkKeyboard();
            recreate();
            check("服务 A".equals(state.getDraft().getName()), "Draft lost on activity recreation");
            click("保存配置");
            waitFor(() -> "服务 A".equals(new EndpointProfiles(prefs).active().name), "Profile save failed");
            check("fixture-a-key".equals(new Config(context).apiKey), "Saved key changed");
            capture("model");

            edit("服务 A", "未保存");
            click("首页");
            click("放弃修改");
            click("模型 API");
            waitFor(() -> "服务 A".equals(state.getDraft().getName()) && !state.getDirty(), "Discard failed");
            click("另存为新配置");
            edit("", "服务 B");
            click("保存并切换");
            waitFor(() -> new EndpointProfiles(prefs).profiles.size() == 2, "New profile missing");
            check("服务 B".equals(new EndpointProfiles(prefs).active().name), "New profile not selected");
            click("API 协议");
            click("Chat Completions");
            click("保存配置");
            waitFor(() -> "chat".equals(new Config(context).api), "API protocol not saved");
            click("当前配置");
            click("服务 A");
            waitFor(() -> "responses".equals(new Config(context).api), "Profile switch did not restore API");
            click("当前配置");
            click("服务 B");
            waitFor(() -> "chat".equals(new Config(context).api), "Second profile not restored");
            click("删除当前配置");
            click("删除");
            waitFor(() -> new EndpointProfiles(prefs).profiles.size() == 1, "Delete failed");
            check("服务 A".equals(new EndpointProfiles(prefs).active().name), "Delete did not switch profile");
            click("首页");
            capture("home");
            click("控屏");
            check(!new Config(context).backgroundDisplay, "Background display default changed");
            click("最大步数");
            edit("30", "0"); click("保存");
            waitFor(() -> "步数需在 1–80 之间".equals(state.getError()), "Lower bound accepted");
            edit("0", "81"); click("保存");
            check(new Config(context).maxSteps == 30, "Invalid steps persisted");
            edit("81", "1"); click("保存");
            waitFor(() -> new Config(context).maxSteps == 1, "Minimum step not saved");
            click("最大步数"); edit("1", "80"); click("保存");
            waitFor(() -> new Config(context).maxSteps == 80, "Maximum step not saved");
            capture("control");
            click("首页");
            click("运行状态");
            check(state.getBackgroundCheck() == null, "Background check ran automatically");
            click("检查后台屏可用性");
            waitFor(() -> state.getBackgroundCheck() != null && !state.getCheckingBackground(), "Background check did not finish");
            // Older backends can report unavailable; the UI must still finish the check and remain usable.
            capture("status");
            click("直接测试");
            waitFor(() -> find(node -> "想在手机上做什么？".contentEquals(node.getHintText() == null ? "" : node.getHintText())
                || text(node).contains("想在手机上做什么")) != null, "Direct test page missing");
            capture("test");
            click("返回");
            click("返回");
            check(state.getJob() == null, "UI checks started a model job");
            result.putString("stream", "PASS: physical-device Compose navigation, legacy migration, draft/recreation/discard, keyboard/save visibility, save/create/switch/delete, API persistence, step limits 0/81/1/80, background status result, direct-test navigation. No model requests.\n");
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure.getClass().getSimpleName() + "\n"
                + android.util.Log.getStackTraceString(failure));
        } finally {
            if (activity != null) {
                onMain(activity::finish);
                test.waitForIdleSync();
            }
            if (changed) {
                var editor = prefs.edit().clear();
                for (var entry : original.entrySet()) {
                    Object value = entry.getValue(); String key = entry.getKey();
                    if (value instanceof String s) editor.putString(key, s);
                    else if (value instanceof Boolean b) editor.putBoolean(key, b);
                    else if (value instanceof Integer i) editor.putInt(key, i);
                    else if (value instanceof Long l) editor.putLong(key, l);
                    else if (value instanceof Float f) editor.putFloat(key, f);
                    else if (value instanceof Set<?>) {
                        @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
                        editor.putStringSet(key, strings);
                    }
                }
                if (!editor.commit() || !original.equals(prefs.getAll())) {
                    code = Activity.RESULT_CANCELED;
                    result.putString("stream", "FAIL: Could not restore original settings\n");
                } else result.putString("stream", result.getString("stream", "") + "Original settings restored and verified.\n");
            }
        }
        test.finish(code, result);
    }

    private void checkKeyboard() throws Exception {
        var field = find(n -> n.isEditable() && "服务 A".equals(text(n)));
        check(field != null, "Edited field not visible");
        Rect bounds = new Rect();
        field.getBoundsInScreen(bounds);
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                bounds.centerX(), bounds.centerY(), 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            check(ui.injectInputEvent(event, true), "Touch injection failed");
            event.recycle();
        }
        waitFor(() -> activity.getWindow().getDecorView().getRootWindowInsets()
            .isVisible(WindowInsets.Type.ime()), "Keyboard did not open");
        var save = find(n -> "保存配置".equals(text(n)));
        check(save != null, "Save action missing with keyboard open");
        save.getBoundsInScreen(bounds);
        int keyboardTop = activity.getWindow().getDecorView().getHeight()
            - activity.getWindow().getDecorView().getRootWindowInsets().getInsets(WindowInsets.Type.ime()).bottom;
        check(bounds.bottom <= keyboardTop && bounds.height() > 0, "Keyboard covers save action");
        capture("model-keyboard");
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitFor(() -> !activity.getWindow().getDecorView().getRootWindowInsets()
            .isVisible(WindowInsets.Type.ime()), "Keyboard did not close");
    }

    private void onMain(Runnable action) {
        Throwable[] failure = new Throwable[1];
        test.runOnMainSync(() -> {
            try { action.run(); } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new AssertionError("UI action failed", failure[0]);
    }

    private void recreate() {
        var monitor = test.addMonitor(MainActivity.class.getName(), null, false);
        onMain(activity::recreate);
        var next = test.waitForMonitorWithTimeout(monitor, 10000);
        test.removeMonitor(monitor);
        check(next instanceof MainActivity, "Activity recreation timed out");
        activity = (MainActivity) next;
        onMain(() -> state = new ViewModelProvider(activity).get(SettingsState.class));
        test.waitForIdleSync();
    }

    private static String text(AccessibilityNodeInfo node) { return node.getText() == null ? "" : node.getText().toString(); }

    private AccessibilityNodeInfo find(Predicate<AccessibilityNodeInfo> predicate) {
        return find(ui.getRootInActiveWindow(), predicate);
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, Predicate<AccessibilityNodeInfo> predicate) {
        if (node == null) return null;
        if (test.getTargetContext().getPackageName().contentEquals(node.getPackageName()) && predicate.test(node)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            var match = find(node.getChild(i), predicate);
            if (match != null) return match;
        }
        return null;
    }

    private void click(String label) {
        for (int attempt = 0; attempt < 18; attempt++) {
            var node = find(n -> label.equals(text(n)) || label.contentEquals(n.getContentDescription() == null ? "" : n.getContentDescription()));
            if (node != null) {
                while (!node.isClickable() && node.getParent() != null) node = node.getParent();
                if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { settle(); return; }
            }
            if (attempt < 9) scroll(true); else scroll(false);
        }
        throw new AssertionError("UI control unavailable: " + label);
    }

    private void edit(String current, String replacement) {
        for (int attempt = 0; attempt < 18; attempt++) {
            var node = find(n -> n.isEditable() && current.equals(text(n)));
            if (node != null) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement);
                check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args), "Text edit failed");
                settle(); return;
            }
            if (attempt < 9) scroll(true); else scroll(false);
        }
        throw new AssertionError("Editable field unavailable");
    }

    private void scroll(boolean forward) {
        var node = find(AccessibilityNodeInfo::isScrollable);
        if (node != null) node.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        settle();
    }

    private void settle() { test.waitForIdleSync(); SystemClock.sleep(350); }

    private void waitFor(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) settle();
        check(condition.getAsBoolean(), message);
    }

    private void capture(String name) throws Exception {
        // Only fixture data is visible. Production FLAG_SECURE stays enabled outside this capture.
        onMain(() -> activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
        try {
            settle();
            SystemClock.sleep(2000); // Let confirmation toasts disappear.
            Bitmap bitmap = ui.takeScreenshot();
            check(bitmap != null, "Fixture screenshot failed");
            File directory = new File(test.getTargetContext().getExternalFilesDir(null), "settings-ui-test");
            check(directory.isDirectory() || directory.mkdirs(), "Screenshot directory unavailable");
            try (var output = new FileOutputStream(new File(directory, name + ".png"))) {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Screenshot write failed");
            } finally { bitmap.recycle(); }
        } finally {
            onMain(() -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
