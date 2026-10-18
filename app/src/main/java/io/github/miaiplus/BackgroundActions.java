package io.github.miaiplus;

import java.util.Set;

/**
 * Executes one model action on the background display.
 *
 * <p>Every branch is display-local. There is no {@code performGlobalAction} fallback, no default-display window
 * lookup and no borrowed input focus: if the background display cannot serve an action, the action fails and the
 * task stops rather than quietly acting on the user's screen. See docs/background-display.md.
 */
final class BackgroundActions {
    /** KEYCODE_BACK. Sent to the target display so the user's own screen does not navigate. */
    private static final int KEY_BACK = 4;
    /** KEYCODE_HOME. Routed to the target display, i.e. that screen's own home, never the physical launcher. */
    private static final int KEY_HOME = 3;

    private BackgroundActions() {}

    /** First launch is the task's initial app; waits/blank initial observations do not consume it. */
    static final class LaunchPolicy {
        private boolean appUsed;
        boolean initial() { return !appUsed; }
        void used(Action action) {
            if (!action.name.equals("wait")) appUsed = true;
        }
    }

    /**
     * A requested app already on the main screen must never be moved into our display. The first app may use
     * foreground control directly; a later conflict requires a fresh, explicit focus-island grant.
     */
    static String foregroundHandoff(BackgroundDisplay display, Action action, RunState run, LaunchPolicy policy)
        throws Exception {
        if (!action.name.equals("launch")) return null;
        String requested = action.data.getString("app");
        AppLaunchTarget target = ScreenService.resolveLaunchTarget(requested);
        if (target == null) throw new IllegalStateException("找不到应用：" + TaskProgress.compact(requested));
        String targetPackage = target.packageName();
        org.json.JSONObject state = display.taskState(run, targetPackage);
        if (!targetPackage.equals(state.optString("mainPackage"))) return null;
        if (!policy.initial()) ForegroundConsent.await(run, targetPackage, target.label());
        run.check();
        // A grant is for this app only. A changed main-screen app requires a new task/user decision.
        if (!targetPackage.equals(display.taskState(run, targetPackage).optString("mainPackage")))
            throw new ForegroundConsent.Paused("PAUSE：等待期间当前应用已改变，任务已暂停，本轮已结束");
        return targetPackage;
    }

    static void verifyHandoffFrame(ScreenService.Frame frame, String targetPackage) {
        if (!targetPackage.equals(frame.foreground()))
            throw new ForegroundConsent.Paused("PAUSE：切换时当前应用已改变，任务已暂停，本轮已结束");
    }

    static void execute(BackgroundDisplay display, Action action, RunState run, Config config) throws Exception {
        execute(display, action, run, config, null, false);
    }

    static void execute(BackgroundDisplay display, Action action, RunState run, Config config,
        ScreenService.Frame planned, boolean systemDialogApproved) throws Exception {
        PhoneUseLog.action("action_begin", action, planned);
        try {
            executeLogged(display, action, run, config, planned, systemDialogApproved);
            PhoneUseLog.action("action_complete", action, planned);
        } catch (Exception failure) { PhoneUseLog.failure("action_background_" + action.name, failure); throw failure; }
    }
    private static void executeLogged(BackgroundDisplay display, Action action, RunState run, Config config,
        ScreenService.Frame planned, boolean systemDialogApproved) throws Exception {
        DisplaySession session = display.session();
        if (session == null) throw new IllegalStateException(BackgroundDisplay.ERROR_DISPLAY_LOST + "：后台显示已关闭");
        // Cheap generation check before any input so a recreated display cannot receive stale coordinates.
        display.verify(run);
        // A system dialog is a normal observation, not a reason to end the task. Review privilege grants and
        // bind that review to the exact prompt; a newly appeared/changed prompt must be observed and planned.
        if (!action.name.equals("launch") && !action.name.equals("wait"))
            verifyTarget(display, action, run, session, planned, systemDialogApproved);
        switch (action.name) {
            case "tap", "long_press", "double_tap" -> {
                double[] point = action.point("element");
                // long_press is its own gesture on this backend, not a tap with a longer delay: the helper holds
                // the pointer down for real. Sending it as a tap opened context menus as a plain click instead.
                if (action.name.equals("long_press")) {
                    display.longPress(run, point[0], point[1]);
                } else {
                    display.tap(run, point[0], point[1]);
                    if (action.name.equals("double_tap")) {
                        run.pause(120);
                        display.verify(run);
                        try { verifyTarget(display, action, run, session, planned, systemDialogApproved); }
                        catch (ScreenService.ObservationChanged changed) {
                            throw new IllegalStateException("双击首击已执行，后台页面随后变化，未执行第二击；请检查当前页面");
                        }
                        display.tap(run, point[0], point[1]);
                    }
                }
            }
            case "swipe" -> display.swipe(run, action.point("start"), action.point("end"));
            case "type" -> {
                // Chinese cannot travel through `input text`, and borrowing the user's focus would type into their app.
                if (!ScreenService.setTextOnDisplay(session.displayId, currentForeground(display, run),
                    action.data.getString("text")))
                    throw new IllegalStateException("后台页面没有可编辑输入框，或该输入框为密码框，请手动接管");
            }
            case "back" -> display.key(run, KEY_BACK);
            case "home" -> {
                // Background "home" means this display's own home; without one the action is refused outright.
                if (display.foregroundPackage(run).isEmpty())
                    throw new IllegalStateException("后台显示没有可用的主页，已停止而不切换到物理主页");
                display.key(run, KEY_HOME);
            }
            case "launch" -> {
                String requested = action.data.getString("app");
                AppLaunchTarget target = ScreenService.resolveLaunchTarget(requested);
                if (target == null) throw new IllegalStateException("找不到应用：" + TaskProgress.compact(requested));
                run.progress = "正在后台打开：" + TaskProgress.compact(target.label());
                boolean opened = display.launch(run, target.packageName() + "/" + target.activityName());
                run.progress = (opened ? "已在后台打开：" : "等待后台选择或确认打开：")
                    + TaskProgress.compact(target.label());
            }
            case "wait" -> run.pause(Math.max(100, Math.min(3000, action.data.optInt("duration_ms", 1000))));
            default -> throw new IllegalArgumentException("后台模式不支持的动作: " + action.name);
        }
        run.check();
    }

    private static void verifyTarget(BackgroundDisplay display, Action action, RunState run, DisplaySession session,
        ScreenService.Frame planned, boolean approved) throws Exception {
        String foreground = display.foregroundPackage(run);
        String tree = ScreenService.treeOnDisplay(session.displayId, foreground);
        ScreenService.Frame current = new ScreenService.Frame(planned == null ? "" : planned.image(), tree,
            session.width, session.height, foreground, planned == null ? session.width : planned.imageWidth(),
            planned == null ? session.height : planned.imageHeight(), session.displayId, session.generation);
        if (planned != null && !PageGuard.canExecute(action, planned, current)) {
            PhoneUseLog.event("action_skipped", "reason", "target_changed", "displayId", session.displayId);
            throw new ScreenService.ObservationChanged();
        }
        SystemDialogPolicy.verify(action, planned, current, approved);
    }

    /** The package currently on the background display, used to bound which window may receive text. */
    static String currentForeground(BackgroundDisplay display, RunState run) throws Exception {
        return display.foregroundPackage(run);
    }

    /** Actions that change the page so much that a re-observation before them would be wrong. */
    static final Set<String> NAVIGATION = Set.of("launch", "back", "home");
}
