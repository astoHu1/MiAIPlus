package io.github.miaiplus;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ScreenService extends AccessibilityService {
    public static volatile ScreenService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private View overlay;
    volatile int launchConfirmationCount;

    /**
     * One observation.
     *
     * <p>{@code width}/{@code height} are the display's logical size and {@code imageWidth}/{@code imageHeight} the
     * encoded JPEG size; they differ because a capture is downscaled, and mixing the two misplaces every tap. A
     * background observation carries its display id and generation, so a stale frame can never be replayed against
     * a different screen and a background action can never fall through to the physical one.
     */
    public record Frame(String image, String tree, int width, int height, String foreground,
                        int imageWidth, int imageHeight, int displayId, String generation) {
        public static Frame physical(String image, String tree, int width, int height, String foreground) {
            return new Frame(image, tree, width, height, foreground, width, height,
                android.view.Display.DEFAULT_DISPLAY, "");
        }
        public static Frame background(String image, String tree, int displayWidth, int displayHeight,
            int imageWidth, int imageHeight, String foreground, int displayId, String generation) {
            return new Frame(image, tree, displayWidth, displayHeight, foreground, imageWidth, imageHeight,
                displayId, generation);
        }
        public boolean isBackground() { return displayId != android.view.Display.DEFAULT_DISPLAY; }
    }

    @Override public void onServiceConnected() {
        instance = this;
    }
    public static boolean isHealthy() {
        ScreenService service = instance;
        try { return service != null && service.getServiceInfo() != null; }
        catch (RuntimeException disconnected) { return false; }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* Read only on an explicit task. */ }
    @Override public void onInterrupt() {
        // Android interrupts accessibility feedback here; it does not revoke the connection.
        if (instance == this && !isHealthy()) BridgeProvider.onScreenDisconnected();
    }
    @Override public void onDestroy() {
        disconnected(); super.onDestroy();
    }
    @Override public boolean onUnbind(Intent intent) { disconnected(); return super.onUnbind(intent); }
    private void disconnected() {
        if (instance == this) {
            instance = null;
            BridgeProvider.onScreenDisconnected();
        }
        hideControls();
    }
    private <T> T onMain(Callable<T> callable) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return callable.call();
        CompletableFuture<T> f = new CompletableFuture<>();
        main.post(() -> { try { f.complete(callable.call()); } catch (Throwable e) { f.completeExceptionally(e); } });
        return f.get(5, TimeUnit.SECONDS);
    }
    private void unlocked(RunState run) {
        run.check();
        if (getSystemService(KeyguardManager.class).isKeyguardLocked()) throw new IllegalStateException("请先解锁手机");
    }
    /** Prefer the actually focused window: OEM permission dialogs may leave the old active root cached. */
    private AccessibilityNodeInfo currentRoot() {
        for (var window : getWindows()) {
            if (window.isFocused()
                && window.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                && window.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null || !root.refresh()) throw new IllegalStateException("当前窗口无法识别，请手动接管");
                return root;
            }
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && !root.refresh()) throw new IllegalStateException("窗口信息已失效，请重新发起任务");
        return root;
    }
    /**
     * Resolves a requested app name to a launchable component for the background backend. Returns null when the
     * request does not match an installed launcher entry, so the caller refuses instead of launching a guess.
     */
    static AppLaunchTarget resolveLaunchTarget(String requested) {
        ScreenService service = instance;
        if (service == null) return null;
        try {
            return service.onMain(() -> AppLaunchTarget.resolve(requested, service.launchTargets()));
        } catch (Exception unresolved) {
            return null;
        }
    }

    /**
     * Reads the accessibility tree of one display, without ever falling back to the physical screen.
     *
     * <p>Windows are keyed by display id in {@code getWindowsOnAllDisplays()}; the focused window's root is
     * preferred, matching the physical path. When the ROM exposes no window for that display the result is empty,
     * which callers report as a vision-only observation rather than substituting the user's own screen.
     */
    static String treeOnDisplay(int displayId, String expectedPackage) {
        ScreenService service = instance;
        if (service == null) return "";
        try {
            return service.onMain(() -> {
                java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = null;
                try {
                    var byDisplay = service.getWindowsOnAllDisplays();
                    if (byDisplay != null) windows = byDisplay.get(displayId);
                } catch (RuntimeException unsupported) { /* Older ROMs may not implement this; treat as no tree. */ }
                if (windows == null || windows.isEmpty()) return "";
                AccessibilityNodeInfo fallback = null;
                for (var window : windows) {
                    if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                        || window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null || !root.refresh()) continue;
                    if (!expectedPackage.isEmpty() && expectedPackage.equals(String.valueOf(root.getPackageName()))) {
                        StringBuilder builder = new StringBuilder();
                        service.dump(root, builder, new int[]{0});
                        return builder.toString();
                    }
                    if (fallback == null && window.isFocused()) fallback = root;
                }
                if (fallback == null) return "";
                StringBuilder builder = new StringBuilder();
                service.dump(fallback, builder, new int[]{0});
                return builder.toString();
            });
        } catch (Exception unavailable) {
            return "";
        }
    }

    /**
     * Types into the editable node on the target display and reports whether a node accepted the text.
     *
     * <p>Chinese cannot travel through {@code input text}, and borrowing the physical screen's input focus would
     * type into whatever the user is doing. So the text goes to the target display's own editable node, and a
     * password field is refused. {@code false} means no editable node was found, which the caller reports as an
     * unsupported action rather than silently typing somewhere else.
     */
    static boolean setTextOnDisplay(int displayId, String expectedPackage, String text) {
        ScreenService service = instance;
        if (service == null) return false;
        try {
            return service.onMain(() -> {
                java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = null;
                try {
                    var byDisplay = service.getWindowsOnAllDisplays();
                    if (byDisplay != null) windows = byDisplay.get(displayId);
                } catch (RuntimeException unsupported) {
                    return false;
                }
                if (windows == null) return false;
                for (var window : windows) {
                    if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                        || window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null || !root.refresh()) continue;
                    if (!expectedPackage.isEmpty() && !expectedPackage.equals(String.valueOf(root.getPackageName()))) continue;
                    AccessibilityNodeInfo target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (target == null || !target.isEditable()) target = findEditable(root);
                    if (target == null || !target.isEditable() || target.isPassword()) continue;
                    Bundle arguments = new Bundle();
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
                }
                return false;
            });
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isVisibleToUser()) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo found = findEditable(node.getChild(index));
            if (found != null) return found;
        }
        return null;
    }

    public Frame capture(RunState run) throws Exception {
        unlocked(run);
        String[] tree = onMain(() -> {
            AccessibilityNodeInfo root = currentRoot();
            StringBuilder b = new StringBuilder();
            String foreground = root == null ? "" : String.valueOf(root.getPackageName());
            if (root != null) dump(root, b, new int[]{0});
            return new String[]{b.toString(), foreground};
        });
        CompletableFuture<Frame> f = new CompletableFuture<>();
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                try (HardwareBuffer buffer = result.getHardwareBuffer()) {
                    Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (hardware == null) throw new IllegalStateException("截图为空");
                    Bitmap bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    hardware.recycle();
                    int width = bitmap.getWidth(), height = bitmap.getHeight();
                    double scale = Math.min(1, 1280.0 / Math.max(width, height));
                    Bitmap small = Bitmap.createScaledBitmap(bitmap, Math.max(1, (int)(width * scale)), Math.max(1, (int)(height * scale)), true);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    small.compress(Bitmap.CompressFormat.JPEG, 80, out);
                    if (small != bitmap) small.recycle(); bitmap.recycle();
                    f.complete(Frame.physical(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), tree[0],
                        width, height, tree[1]));
                } catch (Throwable e) { f.completeExceptionally(e); }
            }
            @Override public void onFailure(int code) { f.completeExceptionally(new IllegalStateException("无法截图（安全窗口/服务权限），错误码 " + code)); }
        });
        Frame frame = f.get(5, TimeUnit.SECONDS);
        run.check(); return frame;
    }
    private void dump(AccessibilityNodeInfo node, StringBuilder out, int[] count) {
        if (count[0]++ >= 250 || out.length() > 24000) return;
        if (node.isVisibleToUser()) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            out.append(node.getClassName()).append(' ').append(bounds.toShortString());
            if (node.isPassword()) out.append(" [password redacted]");
            else {
                if (node.getText() != null) out.append(" text=").append(node.getText());
                if (node.getContentDescription() != null) out.append(" description=").append(node.getContentDescription());
            }
            if (node.isClickable()) out.append(" clickable");
            if (node.isEditable()) out.append(" editable");
            if (node.isFocused()) out.append(" focused");
            out.append('\n');
        }
        for (int i = 0; i < node.getChildCount() && count[0] < 250; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) dump(child, out, count);
        }
    }
    private java.util.List<AppLaunchTarget> launchTargets() {
        java.util.List<AppLaunchTarget> entries = new java.util.ArrayList<>();
        var pm = getPackageManager();
        for (var info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            var activity = info.activityInfo;
            if (activity == null || !activity.exported || !activity.enabled || !activity.applicationInfo.enabled) continue;
            entries.add(new AppLaunchTarget(activity.packageName, activity.name, info.loadLabel(pm).toString()));
        }
        return entries;
    }
    public String apps() throws Exception {
        return onMain(() -> {
            StringBuilder out = new StringBuilder();
            var seen = new java.util.HashSet<String>();
            for (AppLaunchTarget entry : launchTargets()) {
                if (seen.add(entry.packageName())) out.append(entry.label()).append(" = ").append(entry.packageName()).append('\n');
                if (out.length() >= 12000) { out.append("[应用列表过长已截断]\n"); break; }
            }
            return out.toString();
        });
    }
    private boolean allowLaunchPrompt(AppLaunchTarget target, RunState run) throws Exception {
        return onMain(() -> {
            unlocked(run);
            AccessibilityNodeInfo root = currentRoot();
            if (root == null) return false;
            java.util.List<String> texts = new java.util.ArrayList<>();
            java.util.List<AccessibilityNodeInfo> buttons = new java.util.ArrayList<>();
            collectLaunchPrompt(root, texts, buttons, new int[]{0});
            var pm = getPackageManager();
            String targetLabel = pm.getApplicationLabel(pm.getApplicationInfo(target.packageName(), 0)).toString();
            String callerLabel = pm.getApplicationLabel(getApplicationInfo()).toString();
            if (!LaunchPrompt.matches(String.valueOf(root.getPackageName()),
                launchPromptText(root, "permission_group_title"), launchPromptText(root, "event_title"), texts,
                java.util.List.of(callerLabel, getPackageName()),
                java.util.List.of(target.label(), targetLabel, target.packageName())) || buttons.size() != 1) return false;
            AccessibilityNodeInfo button = buttons.get(0);
            if (!button.refresh() || !button.isVisibleToUser() || !button.isEnabled()) return false;
            unlocked(run);
            if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return false;
            launchConfirmationCount++;
            run.progress = "正在允许本次打开：" + TaskProgress.compact(target.label());
            return true;
        });
    }
    private String launchPromptText(AccessibilityNodeInfo root, String id) {
        String found = "";
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId("com.miui.securitycenter:id/" + id)) {
            if (!node.isVisibleToUser() || node.getText() == null) continue;
            if (!found.isEmpty()) return "";
            found = node.getText().toString().strip();
        }
        return found;
    }
    private void collectLaunchPrompt(AccessibilityNodeInfo node, java.util.List<String> texts,
        java.util.List<AccessibilityNodeInfo> buttons, int[] count) {
        if (count[0]++ >= 100) return;
        if (node.isVisibleToUser()) {
            if (node.getText() != null) {
                String text = node.getText().toString().strip();
                texts.add(text);
                if (text.equals("本次允许") && node.isEnabled() && node.isClickable()) buttons.add(node);
            }
        }
        for (int i = 0; i < node.getChildCount() && count[0] < 100; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectLaunchPrompt(child, texts, buttons, count);
        }
    }
    private void launch(String requested, RunState run) throws Exception {
        AppLaunchTarget target = onMain(() -> { unlocked(run); return AppLaunchTarget.resolve(requested, launchTargets()); });
        run.progress = "正在打开：" + TaskProgress.compact(target.label());
        LaunchVerifier.launch(target.packageName(), new LaunchVerifier.Driver() {
            @Override public String foreground() throws Exception {
                return onMain(() -> {
                    unlocked(run);
                    AccessibilityNodeInfo root;
                    try { root = currentRoot(); }
                    catch (IllegalStateException transientWindow) { return ""; }
                    if (root == null || root.getPackageName() == null) return "";
                    String pkg = root.getPackageName().toString();
                    // Security also owns ordinary launcher pages; only permission UI is a blocker.
                    return LaunchVerifier.classifyForeground(pkg, pkg.equals("com.miui.securitycenter")
                        ? launchPromptText(root, "permission_group_title") : "");
                });
            }
            @Override public void start() throws Exception {
                onMain(() -> {
                    unlocked(run);
                    Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(new android.content.ComponentName(target.packageName(), target.activityName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    // The launcher request belongs to the accessibility service, never an IPC caller.
                    long identity = android.os.Binder.clearCallingIdentity();
                    try { startActivity(intent); }
                    catch (SecurityException denied) {
                        throw new IllegalStateException("PAUSE：系统拒绝打开应用，请手动处理系统授权，或检查 MiAIPlus 的后台弹出界面权限后重新发起任务。");
                    } catch (android.content.ActivityNotFoundException missing) {
                        throw new IllegalStateException("应用启动入口已失效，请检查应用是否仍安装并启用");
                    } finally { android.os.Binder.restoreCallingIdentity(identity); }
                    return null;
                });
            }
            @Override public boolean allowLaunchPrompt() throws Exception { return ScreenService.this.allowLaunchPrompt(target, run); }
            @Override public void check() { unlocked(run); }
            @Override public long now() { return android.os.SystemClock.elapsedRealtime(); }
            @Override public void pause(long millis) throws Exception { run.pause(millis); }
        });
        run.progress = "已打开：" + TaskProgress.compact(target.label());
    }
    public void execute(Action action, Frame frame, RunState run) throws Exception {
        unlocked(run);
        if (!action.name.equals("launch") && !action.name.equals("wait")) onMain(() -> {
            unlocked(run);
            AccessibilityNodeInfo root = currentRoot();
            if (root == null || !String.valueOf(root.getPackageName()).equals(frame.foreground()))
                throw new IllegalStateException("前台应用已改变，请重新发起任务");
            return null;
        });
        switch (action.name) {
            case "tap", "long_press", "double_tap" -> {
                double[] p = action.point("element");
                gesture(p, p, action.name.equals("long_press") ? 800 : 60, frame, run);
                if (action.name.equals("double_tap")) { run.pause(100); gesture(p, p, 60, frame, run); }
            }
            case "swipe" -> gesture(action.point("start"), action.point("end"), 450, frame, run);
            case "type" -> {
                boolean ok = onMain(() -> {
                    unlocked(run);
                    AccessibilityNodeInfo root = currentRoot();
                    AccessibilityNodeInfo focused = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (focused == null || !focused.isEditable() || focused.isPassword()) return false;
                    Bundle b = new Bundle(); b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.data.getString("text"));
                    return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
                });
                if (!ok) throw new IllegalStateException("当前焦点不可编辑或为密码框，请手动接管");
            }
            case "back", "home" -> {
                boolean ok = onMain(() -> { unlocked(run); return performGlobalAction(action.name.equals("back") ? GLOBAL_ACTION_BACK : GLOBAL_ACTION_HOME); });
                if (!ok) throw new IllegalStateException("系统拒绝返回/主页动作");
            }
            case "launch" -> launch(action.data.getString("app"), run);
            case "wait" -> run.pause(Math.max(100, Math.min(3000, action.data.optInt("duration_ms", 1000))));
            default -> throw new IllegalArgumentException("非执行动作: " + action.name);
        }
        run.check();
    }
    private void gesture(double[] from, double[] to, long duration, Frame frame, RunState run) throws Exception {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        onMain(() -> {
            unlocked(run);
            AccessibilityNodeInfo root = currentRoot();
            if (root == null || !String.valueOf(root.getPackageName()).equals(frame.foreground()))
                throw new IllegalStateException("前台应用已改变，请重新发起任务");
            // Reject stale coordinates after rotation rather than tapping the wrong UI.
            Rect current = getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
            if (current.width() != frame.width() || current.height() != frame.height())
                throw new IllegalStateException("屏幕尺寸已改变，请重新发起任务");
            Path path = new Path();
            path.moveTo(pixel(from[0], frame.width()), pixel(from[1], frame.height()));
            if (from[0] != to[0] || from[1] != to[1]) path.lineTo(pixel(to[0], frame.width()), pixel(to[1], frame.height()));
            GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
            boolean accepted = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gesture) { done.complete(true); }
                @Override public void onCancelled(GestureDescription gesture) { done.complete(false); }
            }, main);
            if (!accepted) done.complete(false);
            return null;
        });
        if (!done.get(3, TimeUnit.SECONDS)) throw new IllegalStateException("手势执行失败或被用户打断");
        run.check();
    }
    private static float pixel(double n, int size) { return (float)Math.min(size - 1, n * size / 1000); }

    public boolean confirm(String message, RunState run) throws Exception {
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        onMain(() -> {
            removeOverlay();
            LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(16,16,16,16); box.setBackgroundColor(0xffeeeeee);
            TextView text = new TextView(this); text.setText("MiAIPlus 等待确认\n" + message); text.setMaxWidth(700); box.addView(text);
            Button yes = new Button(this); yes.setText("确认本次操作"); yes.setOnClickListener(v -> decision.complete(true)); box.addView(yes);
            Button no = new Button(this); no.setText("停止任务"); no.setOnClickListener(v -> { run.cancel(); decision.complete(false); }); box.addView(no);
            addOverlay(box); return null;
        });
        try {
            while (!decision.isDone()) run.pause(100);
            return decision.get();
        } finally { onMain(() -> { removeOverlay(); return null; }); }
    }
    private void addOverlay(View v) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.END;
        p.y = (int)(120 * getResources().getDisplayMetrics().density);
        getSystemService(WindowManager.class).addView(v, p); overlay = v;
    }
    private void removeOverlay() { if (overlay != null) { getSystemService(WindowManager.class).removeView(overlay); overlay = null; } }
    public void hideControls() { main.post(this::removeOverlay); }
}
