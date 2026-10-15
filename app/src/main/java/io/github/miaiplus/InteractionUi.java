package io.github.miaiplus;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Reflector;
import java.lang.ref.WeakReference;
import org.json.JSONObject;

/** Runs in XiaoAi's UID so HyperOS applies XiaoAi's existing focus-notification permission. */
final class InteractionUi implements Application.ActivityLifecycleCallbacks {
    private static final int NOTIFICATION = 0x4d494149;
    private static final String CHANNEL = "miaiplus_computer";
    private static final String STOP = "com.miui.voiceassist.MIAIPLUS_STOP";
    private final Application app;
    private final Context receiverContext;
    private final ClassLoader loader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> resumed = new WeakReference<>(null);
    private ActivityManager.AppTask originTask;
    private PendingIntent returnIntent;
    private boolean compact;
    private Object overlayManager;
    private View overlayRoot;
    private boolean overlayHidden;
    private int overlayFlags;
    private float overlayDim;
    private final Runnable expireOverlay = this::releaseOverlay;
    private volatile boolean running;
    private java.util.concurrent.ScheduledExecutorService watcher;
    private final BroadcastReceiver stop = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (STOP.equals(intent.getAction())) app.getContentResolver().call(BridgeProvider.URI, "stop_screen", null, null);
        }
    };
    InteractionUi(Application app, ClassLoader loader) {
        this.app = app; this.loader = loader;
        // The host reparents its base ContextImpl after Application.attach; use a stable owner.
        receiverContext = app.createConfigurationContext(app.getResources().getConfiguration());
        // A reloaded generation does not receive already-resumed Activity callbacks again.
        try {
            Object thread = Reflector.callStaticMethod(Reflector.findClass("android.app.ActivityThread", null), "currentActivityThread");
            var records = (java.util.Map<?, ?>)Reflector.getObjectField(thread, "mActivities");
            for (Object record : records.values()) {
                Activity activity = (Activity)Reflector.getObjectField(record, "activity");
                if (!Boolean.TRUE.equals(Reflector.getObjectField(record, "paused"))) resumed = new WeakReference<>(activity);
            }
        } catch (RuntimeException unavailable) { android.util.Log.w("MiAIPlus", "Activity snapshot unavailable"); }
        app.registerActivityLifecycleCallbacks(this);
        IntentFilter actions = new IntentFilter(STOP);
        receiverContext.registerReceiver(stop, actions, Context.RECEIVER_NOT_EXPORTED);
        // Shortcut wake-up uses a system window, without a resumed FloatActivity.
        // Keep native streaming UI updates from reattaching that window over the app being operated.
        Reflector.findAndHookMethod("com.xiaomi.voiceassistant.UiManager", loader, "start", Intent.class, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Exception {
                if (!running && overlayHidden) MainThread.call(() -> { releaseOverlay(); return null; });
            }
        });
        for (String method : new String[]{"X0", "removeWindows"}) Reflector.findAndHookMethod("com.xiaomi.voiceassistant.widget.s1", loader, method, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (overlayHidden && p.thisObject == overlayManager) p.setResult(null);
            }
        });
        Reflector.findAndHookMethod("com.xiaomi.voiceassistant.widget.s1", loader, "getViewLayoutParams", new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (overlayHidden && p.thisObject == overlayManager && p.getResult() instanceof WindowManager.LayoutParams params) hideWindowParams(params);
            }
        });
        for (String method : new String[]{"O2", "enableWindowTouchEvent"}) {
            HookCallback callback = new HookCallback() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (overlayHidden && p.thisObject == overlayManager) p.args[0] = false;
                }
            };
            if (method.equals("O2")) Reflector.findAndHookMethod("com.xiaomi.voiceassistant.widget.s1", loader, method, boolean.class, boolean.class, callback);
            else Reflector.findAndHookMethod("com.xiaomi.voiceassistant.widget.s1", loader, method, boolean.class, callback);
        }
    }
    /** Capture once for the whole native multi-call session, never for individual continuation steps. */
    void begin() throws Exception {
        MainThread.call(() -> {
                if (!running) {
                    if (overlayHidden) releaseOverlay();
                    Activity activity = resumed.get();
                    Object ui = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.UiManager", loader), "getInstance", app);
                    Object manager = Reflector.callMethod(ui, "getFloatManager");
                    View root = manager == null ? null : (View)Reflector.callMethod(manager, "getRootLayout");
                    boolean overlay = root != null && root.isAttachedToWindow() && root.isShown()
                        && root.getLayoutParams() instanceof WindowManager.LayoutParams params
                        && params.type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW
                        && !Boolean.TRUE.equals(Reflector.callMethod(ui, "isFullScreenInForeground"));
                    compact = overlay || activity == null || activity.getClass().getName().equals("com.xiaomi.voiceassistant.mainui.home.FloatActivity");
                    overlayManager = overlay ? manager : null; overlayRoot = overlay ? root : null;
                    originTask = null;
                    if (!compact && activity != null) {
                        for (ActivityManager.AppTask task : app.getSystemService(ActivityManager.class).getAppTasks())
                            if (task.getTaskInfo().taskId == activity.getTaskId()) { originTask = task; break; }
                    }
                    Intent intent = activity == null ? app.getPackageManager().getLaunchIntentForPackage(app.getPackageName())
                        : new Intent(activity.getIntent()).setComponent(activity.getComponentName());
                    if (intent == null) intent = new Intent().setClassName(app.getPackageName(), "com.xiaomi.voiceassistant.LaunchHomeRouterActivity");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    returnIntent = PendingIntent.getActivity(app, NOTIFICATION, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    publish("正在操作手机", "MiAIPlus 正在执行控屏任务", true);
                    try {
                        running = true;
                        if (overlay) {
                            WindowManager.LayoutParams params = (WindowManager.LayoutParams)Reflector.callMethod(manager, "getViewLayoutParams");
                            overlayFlags = params.flags; overlayDim = params.dimAmount;
                            overlayHidden = true;
                            Reflector.callMethod(manager, "setRootViewVisible", false, false);
                            hideWindowParams(params);
                            app.getSystemService(WindowManager.class).updateViewLayout(root, params);
                            if (root.isShown()) throw new IllegalStateException("无法收起小爱悬浮窗口，已停止控屏");
                        } else if (activity != null) {
                            // Native pushBackgroundOperate stops the speech/agent engine and clears its task.
                            if (!activity.moveTaskToBack(true)) throw new IllegalStateException("无法收起小爱界面，已停止控屏");
                        }
                    } catch (Throwable failure) {
                        app.getSystemService(NotificationManager.class).cancel(NOTIFICATION);
                        running = false;
                        if (overlay) releaseOverlay();
                        originTask = null; returnIntent = null;
                        throw failure;
                    }
                }
            return null;
        });
    }
    void started() {
        main.post(() -> {
            if (!running || watcher != null) return;
            watcher = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "miaiplus-focus-watch"));
            watcher.scheduleWithFixedDelay(() -> {
                try {
                    Bundle state = app.getContentResolver().call(BridgeProvider.URI, "screen_state", null, null);
                    if (state != null && state.getBoolean("active")) return;
                    main.post(() -> finish(state == null ? "控屏连接已中断" : state.getString("result", "操作已结束"), state != null));
                } catch (RuntimeException failure) { main.post(() -> finish("控屏连接已中断", false)); }
            }, 700, 700, java.util.concurrent.TimeUnit.MILLISECONDS);
        });
    }
    void failed() { main.post(() -> finish("控屏已停止", true)); }
    boolean isRunning() { return running; }
    @android.annotation.SuppressLint("NotificationPermission") // Runs in the host UID; areNotificationsEnabled checks its grant.
    private void publish(String title, String text, boolean ongoing) throws Exception {
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) throw new IllegalStateException("请允许超级小爱显示通知与焦点通知后再使用控屏");
        NotificationChannel channel = new NotificationChannel(CHANNEL, "MiAIPlus 手机操作", NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null); channel.enableVibration(false); manager.createNotificationChannel(channel);
        if (manager.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE)
            throw new IllegalStateException("请开启超级小爱的 MiAIPlus 手机操作通知渠道");
        // Reuse the exact supported XiaoAi version's native HyperOS island templates and icons.
        Object nativeBuilder = Reflector.newInstance(Reflector.findClass("xh1.a", loader), app);
        String focus = (String)Reflector.callMethod(nativeBuilder, "getFocusNotificationTemplateJson", title, text);
        String large = (String)Reflector.callMethod(nativeBuilder, "getLargeIslandJson", title, ongoing ? "正在操作" : "已结束");
        String small = (String)Reflector.callMethod(nativeBuilder, "getSmallIslandJson");
        JSONObject params = new JSONObject((String)Reflector.callMethod(nativeBuilder, "buildJsonParams", title, focus, large, small));
        JSONObject v2 = params.getJSONObject("param_v2");
        v2.put("islandFirstFloat", true).put("enableFloat", true);
        Bundle extras = new Bundle();
        extras.putString("miui.focus.param", params.toString());
        extras.putBundle("miui.focus.pics", (Bundle)Reflector.callMethod(nativeBuilder, "getIconResource", app));
        Notification.Builder builder = new Notification.Builder(app, CHANNEL).setSmallIcon(app.getApplicationInfo().icon)
            .setContentTitle(title).setContentText(text).setContentIntent(returnIntent).setOngoing(ongoing)
            .setCategory(Notification.CATEGORY_PROGRESS).setOnlyAlertOnce(false).setAutoCancel(!ongoing).addExtras(extras);
        if (ongoing) builder.addAction(new Notification.Action.Builder((android.graphics.drawable.Icon)null, "停止操作", PendingIntent.getBroadcast(app, NOTIFICATION,
            new Intent(STOP).setPackage(app.getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        else builder.setTimeoutAfter(30_000);
        manager.notify(NOTIFICATION, builder.build());
    }
    private void finish(String result, boolean restore) {
        if (!running) return;
        if (watcher != null) {
            watcher.shutdownNow();
            try { watcher.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            watcher = null;
        }
        app.getSystemService(NotificationManager.class).cancel(NOTIFICATION);
        if (restore && !app.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            try {
                if (compact || result.startsWith("PAUSE")) publish(result.startsWith("PAUSE") ? "需要你接管手机" : "手机操作已结束", "点击返回小爱查看结果", false);
                else if (originTask != null) originTask.moveToFront();
                else if (returnIntent != null) returnIntent.send();
            } catch (Exception failure) { android.util.Log.w("MiAIPlus", "Unable to restore interaction", failure); }
        }
        originTask = null; returnIntent = null; running = false;
        if (overlayHidden) main.postDelayed(expireOverlay, 30_000);
    }
    private static void hideWindowParams(WindowManager.LayoutParams params) {
        params.flags = (params.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            & ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        params.dimAmount = 0;
    }
    private void releaseOverlay() {
        main.removeCallbacks(expireOverlay);
        overlayHidden = false;
        try {
            if (overlayManager != null && overlayRoot == Reflector.callMethod(overlayManager, "getRootLayout")) {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams)Reflector.callMethod(overlayManager, "getViewLayoutParams");
                params.flags = overlayFlags; params.dimAmount = overlayDim;
                if (overlayRoot.isAttachedToWindow()) app.getSystemService(WindowManager.class).updateViewLayout(overlayRoot, params);
            }
        } catch (RuntimeException failure) { android.util.Log.w("MiAIPlus", "Unable to restore XiaoAi overlay", failure); }
        finally { overlayManager = null; overlayRoot = null; }
    }
    void dispose() throws Exception {
        MainThread.call(() -> {
        if (running) throw new IllegalStateException("控屏期间不能重载");
        releaseOverlay();
        main.removeCallbacksAndMessages(null);
        app.unregisterActivityLifecycleCallbacks(this); receiverContext.unregisterReceiver(stop);
        app.getSystemService(NotificationManager.class).cancel(NOTIFICATION);
        resumed.clear(); overlayManager = null; overlayRoot = null; returnIntent = null; originTask = null; return null;
        });
    }
    @Override public void onActivityResumed(Activity activity) {
        resumed = new WeakReference<>(activity);
        if (!running && overlayHidden) releaseOverlay();
    }
    @Override public void onActivityPaused(Activity activity) { if (resumed.get() == activity) resumed.clear(); }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) { if (resumed.get() == activity) resumed.clear(); }
}
