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
    public record Frame(String image, String tree, int width, int height, String foreground) {}

    @Override public void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* Read only on an explicit task. */ }
    @Override public void onInterrupt() { BridgeProvider.cancelActive(); }
    @Override public void onDestroy() {
        BridgeProvider.cancelActive(); hideControls(); instance = null; super.onDestroy();
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
                    f.complete(new Frame(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), tree[0], width, height, tree[1]));
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
    public String apps() throws Exception {
        return onMain(() -> {
            StringBuilder out = new StringBuilder();
            for (var info : getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                out.append(info.loadLabel(getPackageManager())).append(" = ").append(info.activityInfo.packageName).append('\n');
            }
            return out.toString();
        });
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
            case "launch" -> onMain(() -> {
                unlocked(run);
                String app = action.data.getString("app"), pkg = app;
                if (getPackageManager().getLaunchIntentForPackage(pkg) == null) {
                    pkg = null;
                    for (var info : getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                        if (info.loadLabel(getPackageManager()).toString().equals(app)) {
                            if (pkg != null) throw new IllegalStateException("应用名称不唯一，请指定包名");
                            pkg = info.activityInfo.packageName;
                        }
                    }
                }
                Intent intent = pkg == null ? null : getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent == null) throw new IllegalStateException("应用未安装或无启动入口");
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return null;
            });
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

    public void showControls(RunState run) throws Exception {
        onMain(() -> {
            removeOverlay();
            Button stop = new Button(this); stop.setText("■ 停止 MiAIPlus");
            stop.setOnClickListener(v -> run.cancel());
            addOverlay(stop); return null;
        });
    }
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
        } finally { if (!run.cancelled.get()) showControls(run); }
    }
    private void addOverlay(View v) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.END; p.y = 64;
        getSystemService(WindowManager.class).addView(v, p); overlay = v;
    }
    private void removeOverlay() { if (overlay != null) { getSystemService(WindowManager.class).removeView(overlay); overlay = null; } }
    public void hideControls() { main.post(this::removeOverlay); }
}
