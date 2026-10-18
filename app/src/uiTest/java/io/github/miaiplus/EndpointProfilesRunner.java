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

    static void run(Instrumentation test) { new EndpointProfilesRunner(test).run(false); }

    static void runPerformance(Instrumentation test) { new EndpointProfilesRunner(test).run(true); }

    static void runVirtual(Instrumentation test) { new EndpointProfilesRunner(test).runVirtual(false); }

    static void runVirtualIdle(Instrumentation test) { new EndpointProfilesRunner(test).runVirtual(true); }

    static void runDirect(Instrumentation test) { new EndpointProfilesRunner(test).runDirect(); }

    /** Exercise the actual Start/Stop UI against the local delayed model fixture on a separate display. */
    private void runDirect() {
        var context = test.getTargetContext();
        var prefs = Config.prefs(context);
        Map<String, ?> original = prefs.getAll();
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        boolean changed = false;
        try {
            Bundle health = context.getContentResolver().call(BridgeProvider.URI, "accessibility_health", null, null);
            Bundle screen = context.getContentResolver().call(BridgeProvider.URI, "screen_state", null, null);
            check(health != null && !health.getBoolean("busy") && !health.getBoolean("preparing")
                && screen != null && !screen.getBoolean("active"), "Stop active work before direct UI test");
            Bundle connecting = new Bundle(); connecting.putString("stream", "MIAIPLUS_CONNECT_SERVICE\n");
            test.sendStatus(0, connecting);
            waitFor(ScreenService::isHealthy, "Accessibility did not reconnect");
            changed = true;
            check(prefs.edit().clear().putBoolean("enabled", true).putBoolean("backgroundDisplay", true)
                .putString("endpoint", "http://127.0.0.1:18765/v1").putString("apiKey", "test-key")
                .putString("model", "fixture-model").putString("api", "chat").putString("planner", "external")
                .putString("protocol", "phone_use").putInt("maxSteps", 8).commit(), "Fixture save failed");
            activity = (MainActivity) test.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            onMain(() -> state = new ViewModelProvider(activity).get(SettingsState.class));
            click("运行状态"); click("直接测试");
            edit("", "MIAIPLUS_CANCEL_TEST");
            click("开始测试");
            waitFor(() -> state.getJob() != null && BackgroundDisplay.active() != null
                && BackgroundDisplay.active().isOpen(), "Direct test did not create its background display");
            onMain(() -> {
                check(activity.hasWindowFocus() && activity.getWindow().getDecorView().isShown(),
                    "Background test hid or unfocused the physical settings page");
                check((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0,
                    "Settings unexpectedly blocked screenshots");
            });
            click("停止任务");
            waitFor(() -> state.getJob() == null && BackgroundDisplay.active() == null,
                "Stop did not finish the job and release the display");
            result.putString("stream", "PASS: actual Start/Stop UI created a background display while settings "
                + "remained focused and visible on the physical screen; stop released the display. Local mock only.\n");
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(failure));
        } finally {
            if (changed) {
                BridgeProvider.cancelActive(); BackgroundDisplay.closeActive();
            }
            if (activity != null) { onMain(activity::finish); test.waitForIdleSync(); }
            if (changed) {
                var editor = prefs.edit().clear();
                for (var entry : original.entrySet()) {
                    Object value = entry.getValue(); String key = entry.getKey();
                    if (value instanceof String v) editor.putString(key, v);
                    else if (value instanceof Boolean v) editor.putBoolean(key, v);
                    else if (value instanceof Integer v) editor.putInt(key, v);
                    else if (value instanceof Long v) editor.putLong(key, v);
                    else if (value instanceof Float v) editor.putFloat(key, v);
                    else if (value instanceof Set<?>) {
                        @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
                        editor.putStringSet(key, strings);
                    }
                }
                if (!editor.commit() || !original.equals(prefs.getAll())) {
                    code = Activity.RESULT_CANCELED; result.putString("stream", "FAIL: Could not restore original settings\n");
                }
            }
        }
        test.finish(code, result);
    }

    private void runVirtual(boolean idleSample) {
        var context = test.getTargetContext();
        var prefs = Config.prefs(context);
        boolean originalMode = prefs.getBoolean("backgroundDisplay", false);
        boolean hadMode = prefs.contains("backgroundDisplay");
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            Bundle screen = context.getContentResolver().call(BridgeProvider.URI, "screen_state", null, null);
            Bundle health = context.getContentResolver().call(BridgeProvider.URI, "accessibility_health", null, null);
            check(screen != null && !screen.getBoolean("active") && health != null
                && !health.getBoolean("busy") && !health.getBoolean("preparing"), "Stop active work before UI tests");
            activity = (MainActivity) test.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            onMain(() -> state = new ViewModelProvider(activity).get(SettingsState.class));
            click("控屏");
            click("虚拟屏");
            check(state.getVirtualScreen().getAvailability() == null && state.getVirtualScreen().getReport() == null,
                "Virtual checks ran automatically");
            if (idleSample) {
                Bundle ready = new Bundle();
                ready.putString("stream", "READY: virtual screen idle sample\n");
                test.sendStatus(0, ready);
                SystemClock.sleep(45000);
                check(state.getVirtualScreen().getAvailability() == null && state.getVirtualScreen().getReport() == null
                    && !state.getVirtualScreen().getBusy(), "Idle page started a virtual check");
                result.putString("stream", "PASS: virtual page remained idle without starting checks\n");
                code = Activity.RESULT_OK;
                test.finish(code, result);
                return;
            }
            click("兼容入口默认使用虚拟屏");
            waitFor(() -> new Config(context).backgroundDisplay != originalMode, "Virtual mode did not save");
            click("兼容入口默认使用虚拟屏");
            waitFor(() -> new Config(context).backgroundDisplay == originalMode, "Virtual mode did not restore");
            click("检查前置条件");
            waitFor(() -> state.getVirtualScreen().getAvailability() != null && !state.getVirtualScreen().getChecking(),
                "Prerequisite check did not finish");
            check("前置条件已满足".equals(state.getVirtualScreen().getAvailability().getTitle()),
                "Device root/unlock prerequisites not satisfied");
            click("测试应用");
            edit("", "virtualfixture");
            click("虚拟屏测试");
            check(state.getVirtualScreen().getSelectedApp() != null, "Probe application was not selected");
            click("开始检测");
            click("取消");
            check(state.getVirtualScreen().getReport() == null && !state.getVirtualScreen().getProbing(),
                "Cancelled confirmation started a probe");
            click("开始检测");
            click("开始");
            long deadline = SystemClock.elapsedRealtime() + 90000;
            while (state.getVirtualScreen().getProbing() && SystemClock.elapsedRealtime() < deadline) settle();
            check(!state.getVirtualScreen().getProbing(), "Virtual probe timed out");
            var report = state.getVirtualScreen().getReport();
            check(report != null, "Virtual probe returned no report: " + state.getVirtualScreen().getError());
            check(report.getItems().size() >= 8, "Virtual probe report is incomplete");
            StringBuilder verdicts = new StringBuilder();
            for (var item : report.getItems()) verdicts.append(item.getId()).append('=').append(item.getStatus()).append(' ');
            click("创建屏幕");
            click("关闭");
            capture("virtual-screen-device");
            recreate();
            check(state.getVirtualScreen().getReport() == report && !state.getVirtualScreen().getProbing(),
                "Virtual result did not survive recreation");
            click("返回");
            click("首页");
            check(state.getJob() == null, "UI test started a model job");
            result.putString("stream", "PASS: virtual-screen UI, mode persistence, prerequisites, app selection, "
                + "confirmation/cancel, actual capability probe, details, recreation and return. No model requests.\n"
                + "Probe verdicts: " + verdicts + "\n");
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(failure));
        } finally {
            if (activity != null) { onMain(activity::finish); test.waitForIdleSync(); }
            var editor = prefs.edit();
            if (hadMode) editor.putBoolean("backgroundDisplay", originalMode); else editor.remove("backgroundDisplay");
            if (!editor.commit()) { code = Activity.RESULT_CANCELED; result.putString("stream", "FAIL: mode restore failed\n"); }
        }
        test.finish(code, result);
    }

    private void run(boolean performance) {
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
            settle();
            if (performance) {
                runPerformancePages();
                result.putString("stream", "PASS: settings performance samples completed. No model requests or display probes.\n");
            } else {
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
                var modelField = find(n -> n.isEditable() && "fixture-a".equals(text(n)));
                check(modelField != null, "Model field not visible for popup brightness check");
                Rect sample = new Rect(); modelField.getBoundsInScreen(sample);
                int sampleX = sample.left + 4, sampleY = sample.centerY();
                Bitmap beforePopup = ui.takeScreenshot();
                check(beforePopup != null, "Could not capture popup baseline");
                int baselineColor = beforePopup.getPixel(sampleX, sampleY);
                check((baselineColor & 0x00ffffff) != 0, "Black background cannot detect dimming");
                click("API 协议");
                capture("protocol-popup-no-dim-device");
                Bitmap afterPopup = ui.takeScreenshot();
                try {
                    check(afterPopup != null, "Could not capture popup");
                    check(baselineColor == afterPopup.getPixel(sampleX, sampleY), "Selector dimmed the background");
                } finally { beforePopup.recycle(); if (afterPopup != null) afterPopup.recycle(); }
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
                checkGlobalPlanner();
                click("首页");
                capture("home");
                click("控屏");
                check(!new Config(context).backgroundDisplay, "Background display default changed");
                checkPhoneUsePermissionPresets();
                click("最大步数");
                edit("30", "0"); click("保存");
                waitFor(() -> "步数需在 1–200 之间".equals(state.getError()), "Lower bound accepted");
                edit("0", "201"); click("保存");
                check(new Config(context).maxSteps == 30, "Invalid steps persisted");
                edit("201", "1"); click("保存");
                waitFor(() -> new Config(context).maxSteps == 1, "Minimum step not saved");
                click("最大步数"); edit("1", "200"); click("保存");
                waitFor(() -> new Config(context).maxSteps == 200, "Maximum step not saved");
                capture("control");
                click("首页");
                click("运行状态");
                click("虚拟屏");
                check(state.getVirtualScreen().getAvailability() == null, "Background check ran automatically");
                click("检查前置条件");
                waitFor(() -> state.getVirtualScreen().getAvailability() != null && !state.getVirtualScreen().getChecking(), "Background check did not finish");
                // Older backends can report unavailable; the UI must still finish the check and remain usable.
                capture("virtual-screen");
                click("返回");
                click("首页");
                click("运行状态");
                capture("status");
                click("直接测试");
                waitFor(() -> find(node -> "想在手机上做什么？".contentEquals(node.getHintText() == null ? "" : node.getHintText())
                    || text(node).contains("想在手机上做什么")) != null, "Direct test page missing");
                capture("test");
                click("返回");
                click("返回");
                check(state.getJob() == null, "UI checks started a model job");
                result.putString("stream", "PASS: physical-device Compose navigation, legacy migration, draft/recreation/discard, keyboard/save visibility, save/create/switch/delete, API persistence, selector background brightness, global-model planner lock/unlock, Phone Use Manual/Auto/YOLO dropdown/persistence/recreation/fresh ViewModel, step limits 0/201/1/200, background status result, direct-test navigation. No model requests.\n");
            }
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            if (activity != null && changed) {
                try { capture("failure"); } catch (Exception ignored) { }
            }
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

    private void checkPhoneUsePermissionPresets() throws Exception {
        var context = test.getTargetContext();
        var prefs = Config.prefs(context);
        check(Config.PHONE_USE_PERMISSION_MANUAL.equals(new Config(context).phoneUsePermissionMode),
            "Phone Use permission default is not Manual");
        String[][] modes = {
            {"Auto", Config.PHONE_USE_PERMISSION_AUTO, "模型自动审计，无法确定时请求你确认"},
            {"YOLO", Config.PHONE_USE_PERMISSION_YOLO, "除严重安全问题外自动同意，安全限制始终生效"},
            {"Manual", Config.PHONE_USE_PERMISSION_MANUAL, "敏感操作由你逐次确认"}
        };
        for (int i = 0; i < modes.length; i++) {
            String label = modes[i][0], mode = modes[i][1], summary = modes[i][2];
            click("Phone Use 权限预设");
            for (String[] option : modes) {
                check(find(n -> option[0].equals(text(n))) != null,
                    "Phone Use permission menu is missing " + option[0]);
            }
            if (i == 0) capture("control-phone-use-permission-menu-device");
            click(label);
            waitFor(() -> mode.equals(state.getConfig().phoneUsePermissionMode)
                && mode.equals(prefs.getString(Config.PHONE_USE_PERMISSION_MODE_KEY, null))
                && mode.equals(new Config(context).phoneUsePermissionMode),
                label + " permission preset did not persist");
            checkPermissionSelection(label, summary);
            capture("control-phone-use-permission-" + mode + "-device");

            SettingsState previous = state;
            recreate();
            enterControlPage();
            check(mode.equals(state.getConfig().phoneUsePermissionMode),
                label + " permission preset lost on Activity recreation");
            checkPermissionSelection(label, summary);

            // A recreation can retain the ViewModel. Reopen after finish as well, so the UI
            // must construct a fresh SettingsState from the persisted fixture preferences.
            onMain(activity::finish);
            test.waitForIdleSync();
            activity = (MainActivity) test.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            onMain(() -> state = new ViewModelProvider(activity).get(SettingsState.class));
            check(state != previous && mode.equals(state.getConfig().phoneUsePermissionMode),
                label + " permission preset lost in a fresh ViewModel");
            enterControlPage();
            checkPermissionSelection(label, summary);
            check(state.getJob() == null, "Permission selection started a model job");
        }
        check(!new Config(context).phoneUseDetailedLog, "Detailed logging is not off by default");
        click("Phone Use 详细日志");
        waitFor(() -> state.getConfig().phoneUseDetailedLog && new Config(context).phoneUseDetailedLog,
            "Detailed log switch did not persist");
        recreate(); enterControlPage();
        check(state.getConfig().phoneUseDetailedLog, "Detailed log switch lost on recreation");
        capture("control-phone-use-log-device");
        click("Phone Use 详细日志");
        waitFor(() -> !state.getConfig().phoneUseDetailedLog && !new Config(context).phoneUseDetailedLog,
            "Detailed log switch did not turn off");
        check(state.getJob() == null, "Logging switch started a task");
    }

    private void enterControlPage() {
        settle();
        // Recreation restores the current page and scroll. Its non-clickable toolbar title
        // can precede the same-named navigation item in the accessibility tree.
        if (find(n -> "Phone Use 权限预设".equals(text(n)) || "最大步数".equals(text(n))
            || "动作协议".equals(text(n)) || "启用控屏工具".equals(text(n))
            || "任务前检查无障碍".equals(text(n))) == null) click("控屏");
    }

    private void checkPermissionSelection(String label, String summary) {
        for (int attempt = 0; attempt < 18; attempt++) {
            if (find(n -> summary.equals(text(n))) != null && find(n -> label.equals(text(n))) != null) return;
            if (attempt < 9) scroll(true); else scroll(false);
        }
        throw new AssertionError("Permission preset label/description missing: " + label);
    }

    private void checkGlobalPlanner() throws Exception {
        onMain(() -> check(state.setOption("planner", "external"), "External planner setup failed"));
        click("使用外部模型"); click("开启");
        waitFor(() -> state.getConfig().globalModel && "native".equals(state.getConfig().planner),
            "Global model did not force native planner");
        check("native".equals(Config.prefs(test.getTargetContext()).getString("planner", "")),
            "Native planner was not persisted");
        click("控屏");
        // Disabled merged Compose semantics may expose the row's title, summary and value as one text.
        waitFor(() -> find(n -> text(n).contains("规划模型")) != null, "Planner selector was not visible");
        var planner = find(n -> text(n).contains("规划模型"));
        // Android exposes the title text as a child; enabled belongs to the preference row.
        while (planner != null && planner.isEnabled() && !planner.isClickable() && planner.getParent() != null)
            planner = planner.getParent();
        check(planner != null && !planner.isEnabled(), "Planner selector was not disabled");
        Rect bounds = new Rect(); planner.getBoundsInScreen(bounds);
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                bounds.centerX(), bounds.centerY(), 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            check(ui.injectInputEvent(event, true), "Disabled selector touch failed");
            event.recycle();
        }
        settle();
        check(find(n -> "外部模型".equals(text(n))) == null, "Disabled selector opened a menu");
        capture("control-global-model-device");
        click("模型 API"); click("使用外部模型");
        waitFor(() -> !state.getConfig().globalModel, "Global model switch did not turn off");
        click("控屏"); click("规划模型"); click("外部模型");
        waitFor(() -> "external".equals(state.getConfig().planner), "Planner did not unlock");
        click("规划模型"); click("小爱当前模型");
        waitFor(() -> "native".equals(state.getConfig().planner), "Native planner selection failed");
    }

    /** Same fixture and gesture stream for Debug/Preview; keep accessibility services connected. */
    private void runPerformancePages() throws Exception {
        settle();
        SystemClock.sleep(1500);
        sample("home-idle", () -> SystemClock.sleep(5000));
        sample("tabs", () -> {
            for (int i = 0; i < 4; i++) {
                click("模型 API"); click("控屏"); click("首页");
            }
        });
        click("模型 API");
        sample("model-scroll", this::performanceScroll);
        // ACTION_SET_TEXT exercises Compose state/layout without involving a network or real credentials.
        scroll(false); scroll(false);
        sample("model-edit", () -> {
            for (int i = 0; i < 12; i++) {
                edit(i == 0 ? "默认配置" : "草稿 " + (i - 1), "草稿 " + i);
            }
        });
        click("还原");
        click("控屏");
        sample("control-scroll", this::performanceScroll);
        click("虚拟屏");
        sample("virtual-idle", () -> SystemClock.sleep(5000));
        sample("virtual-scroll", this::performanceScroll);
        check(state.getVirtualScreen().getAvailability() == null && state.getVirtualScreen().getReport() == null,
            "Performance sample started a display probe");
        click("首页");
        sample("secondary-navigation", () -> {
            for (int i = 0; i < 4; i++) {
                click("运行状态"); click("直接测试"); click("返回"); click("返回");
            }
        });
        check(state.getJob() == null, "Performance sample started a model job");
    }

    private void sample(String label, Runnable action) throws Exception {
        shell("dumpsys gfxinfo " + test.getTargetContext().getPackageName() + " reset");
        long started = SystemClock.elapsedRealtime();
        long cpu = android.os.Process.getElapsedCpuTime();
        action.run();
        SystemClock.sleep(500); // Include the final fling/transition frames in the same sample.
        test.waitForIdleSync();
        long cpuMs = android.os.Process.getElapsedCpuTime() - cpu;
        long elapsedMs = SystemClock.elapsedRealtime() - started;
        StringBuilder summary = new StringBuilder("SAMPLE ").append(label)
            .append(" elapsedMs=").append(elapsedMs).append(" cpuMs=").append(cpuMs)
            .append(" cpuPercent=").append(String.format(java.util.Locale.ROOT, "%.2f", 100.0 * cpuMs / elapsedMs))
            .append('\n');
        for (String line : shell("dumpsys gfxinfo " + test.getTargetContext().getPackageName()).split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Total frames rendered:") || trimmed.startsWith("Janky frames:")
                || trimmed.startsWith("95th percentile:") || trimmed.startsWith("99th percentile:"))
                summary.append(trimmed).append('\n');
        }
        Bundle status = new Bundle(); status.putString("stream", summary.toString()); test.sendStatus(0, status);
    }

    private String shell(String command) throws Exception {
        try (var input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void performanceScroll() {
        int[] size = new int[2];
        onMain(() -> {
            size[0] = activity.getWindow().getDecorView().getWidth();
            size[1] = activity.getWindow().getDecorView().getHeight();
        });
        float x = size[0] * 0.5f;
        for (int gesture = 0; gesture < 12; gesture++) {
            float from = size[1] * (gesture % 2 == 0 ? 0.72f : 0.30f);
            float to = size[1] * (gesture % 2 == 0 ? 0.30f : 0.72f);
            long down = SystemClock.uptimeMillis();
            for (int step = 0; step <= 20; step++) {
                long due = down + step * 15L;
                long remaining = due - SystemClock.uptimeMillis();
                if (remaining > 0) SystemClock.sleep(remaining);
                int action = step == 0 ? MotionEvent.ACTION_DOWN : step == 20 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
                MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    x, from + (to - from) * step / 20f, 0);
                event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                try { check(ui.injectInputEvent(event, step == 20), "Performance gesture injection failed"); }
                finally { event.recycle(); }
            }
            SystemClock.sleep(60);
        }
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
        throw new AssertionError("Editable fixture field unavailable: " + current);
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
        // Capture the production window policy with fixture data visible.
        settle();
        SystemClock.sleep(2000); // Let confirmation toasts disappear.
        Bitmap bitmap = ui.takeScreenshot();
        check(bitmap != null, "Fixture screenshot failed");
        File directory = new File(test.getTargetContext().getExternalFilesDir(null), "settings-ui-test");
        check(directory.isDirectory() || directory.mkdirs(), "Screenshot directory unavailable");
        try (var output = new FileOutputStream(new File(directory, name + ".png"))) {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Screenshot write failed");
        } finally { bitmap.recycle(); }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
