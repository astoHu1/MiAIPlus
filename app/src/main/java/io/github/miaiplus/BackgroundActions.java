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

    static void execute(BackgroundDisplay display, Action action, RunState run, Config config) throws Exception {
        DisplaySession session = display.session();
        if (session == null) throw new IllegalStateException(BackgroundDisplay.ERROR_DISPLAY_LOST + "：后台显示已关闭");
        // Cheap generation check before any input so a recreated display cannot receive stale coordinates.
        display.verify(run);
        switch (action.name) {
            case "tap", "long_press", "double_tap" -> {
                double[] point = action.point("element");
                display.tap(run, point[0], point[1]);
                if (action.name.equals("double_tap")) {
                    run.pause(120);
                    display.verify(run);
                    display.tap(run, point[0], point[1]);
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
                display.launch(run, target.packageName() + "/" + target.activityName());
            }
            case "wait" -> run.pause(Math.max(100, Math.min(3000, action.data.optInt("duration_ms", 1000))));
            default -> throw new IllegalArgumentException("后台模式不支持的动作: " + action.name);
        }
        run.check();
    }

    /** The package currently on the background display, used to bound which window may receive text. */
    static String currentForeground(BackgroundDisplay display, RunState run) throws Exception {
        return display.foregroundPackage(run);
    }

    /** Actions that change the page so much that a re-observation before them would be wrong. */
    static final Set<String> NAVIGATION = Set.of("launch", "back", "home");
}
