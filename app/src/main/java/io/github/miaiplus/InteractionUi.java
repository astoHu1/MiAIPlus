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
    // Keep the existing notification channel ID so upgrades preserve the user's channel settings.
    private static final String CHANNEL = "miaiplus_computer";
    private static final String STOP = "com.miui.voiceassist.MIAIPLUS_STOP";
    private final Application app;
    private final Context receiverContext;
    private final ClassLoader loader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> resumed = new WeakReference<>(null);
    private ActivityManager.AppTask originTask;
    private PendingIntent returnIntent;
    private String controlToken;
    private String screenTask;
    private String shownProgress = "";
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
            if (running && STOP.equals(intent.getAction()) && controlToken != null
                && controlToken.equals(intent.getStringExtra("token")))
                app.getContentResolver().call(BridgeProvider.URI, "stop_screen", null, null);
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
                if (running) { p.setResult(null); return; }
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
    void begin(String taskId) throws Exception {
        MainThread.call(() -> {
                if (running && !java.util.Objects.equals(screenTask, taskId)) {
                    // A new task can arrive before the previous task's last poll.
                    // Keep the saved entry hidden, but retire its observer and queued callbacks.
                    screenTask = taskId;
                    stopWatching();
                    controlToken = java.util.UUID.randomUUID().toString();
                    shownProgress = "正在理解任务";
                    try { publish("正在操作手机", shownProgress, true); }
                    catch (Exception failure) { finish("控屏已停止", false); throw failure; }
                }
                if (!running) {
                    screenTask = taskId;
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
                    controlToken = java.util.UUID.randomUUID().toString();
                    shownProgress = "正在理解任务";
                    publish("正在操作手机", shownProgress, true);
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
            String watchedTask = screenTask;
            watcher = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "miaiplus-focus-watch"));
            watcher.scheduleWithFixedDelay(() -> {
                try {
                    Bundle state = app.getContentResolver().call(BridgeProvider.URI, "screen_state", watchedTask, null);
                    if (state != null && state.getBoolean("active")) {
                        String progress = TaskProgress.compact(state.getString("progress", ""));
                        main.post(() -> {
                            if (!running || !java.util.Objects.equals(screenTask, watchedTask)
                                || progress.isEmpty() || progress.equals(shownProgress)) return;
                            try { publish("正在操作手机", progress, true); shownProgress = progress; }
                            catch (Exception failure) { android.util.Log.w("MiAIPlus", "Unable to update task intent"); }
                        });
                        return;
                    }
                    main.post(() -> {
                        if (java.util.Objects.equals(screenTask, watchedTask))
                            finish(state == null ? "控屏连接已中断" : state.getString("result", "操作已结束"),
                                state != null && state.getBoolean("completed"));
                    });
                } catch (RuntimeException failure) { main.post(() -> {
                    if (java.util.Objects.equals(screenTask, watchedTask)) finish("控屏连接已中断", false);
                }); }
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
        // Build model-supplied display text with JSONObject, never interpolate it into native JSON strings.
        JSONObject focus = new JSONObject((String)Reflector.callMethod(nativeBuilder, "getFocusNotificationTemplateJson", title, ""));
        focus.put("content", text);
        JSONObject large = new JSONObject((String)Reflector.callMethod(nativeBuilder, "getLargeIslandJson", title, ""));
        large.getJSONObject("imageTextInfoRight").getJSONObject("textInfo").put("title", text);
        String small = (String)Reflector.callMethod(nativeBuilder, "getSmallIslandJson");
        JSONObject params = new JSONObject((String)Reflector.callMethod(nativeBuilder, "buildJsonParams", title, focus.toString(), large.toString(), small));
        JSONObject v2 = params.getJSONObject("param_v2");
        boolean updating = ongoing && running;
        v2.put("islandFirstFloat", !updating).put("enableFloat", !updating);
        Intent stopIntent = new Intent(STOP).setPackage(app.getPackageName()).putExtra("token", controlToken);
        PendingIntent stopPending = PendingIntent.getBroadcast(app, NOTIFICATION, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action stopAction = new Notification.Action.Builder((android.graphics.drawable.Icon)null,
            "停止操作", stopPending).build();
        Bundle pictures = (Bundle)Reflector.callMethod(nativeBuilder, "getIconResource", app);
        if (ongoing) {
            // HyperOS expanded focus cards read param_v2.actions, not Notification.actions.
            Object controls = Reflector.newInstance(Reflector.findClass("gp1.c", loader));
            pictures.putAll((Bundle)Reflector.callMethod(controls, "getIconResource", app));
            v2.put("actions", new org.json.JSONArray().put(new JSONObject()
                .put("action", "miaiplus_stop").put("actionTitle", "停止操作").put("actionIcon", "miui.focus.pic_action_close")
                .put("actionIconDark", "miui.focus.pic_action_close_dark")));
        }
        Bundle extras = new Bundle();
        extras.putString("miui.focus.param", params.toString());
        extras.putBundle("miui.focus.pics", pictures);
        if (ongoing) {
            Bundle actions = new Bundle(); actions.putParcelable("miaiplus_stop", stopAction);
            extras.putBundle("miui.focus.actions", actions);
        }
        Notification.Builder builder = new Notification.Builder(app, CHANNEL).setSmallIcon(app.getApplicationInfo().icon)
            .setContentTitle(title).setContentText(text).setContentIntent(returnIntent).setOngoing(ongoing)
            .setCategory(Notification.CATEGORY_PROGRESS).setOnlyAlertOnce(updating).setAutoCancel(!ongoing).addExtras(extras);
        if (ongoing) builder.addAction(stopAction);
        else builder.setTimeoutAfter(30_000);
        manager.notify(NOTIFICATION, builder.build());
    }
    private void stopWatching() {
        if (watcher != null) {
            watcher.shutdownNow();
            watcher = null;
        }
    }
    private void finish(String result, boolean restore) {
        if (!running) return;
        stopWatching();
        app.getSystemService(NotificationManager.class).cancel(NOTIFICATION);
        running = false;
        if (restore && !app.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            try {
                if (compact || result.startsWith("PAUSE")) publish(result.startsWith("PAUSE") ? "需要你接管手机" : "手机操作已结束", "点击返回小爱查看结果", false);
                else if (originTask != null) originTask.moveToFront();
                else if (returnIntent != null) returnIntent.send();
            } catch (Exception failure) { android.util.Log.w("MiAIPlus", "Unable to restore interaction", failure); }
        }
        originTask = null; returnIntent = null; controlToken = null; screenTask = null; running = false;
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
        // Native tool-result rendering may resume the chat after each CONTINUE.
        // Keep the original task in the background until its terminal acknowledgement.
        if (running) activity.moveTaskToBack(true);
        if (!running && overlayHidden) releaseOverlay();
    }
    @Override public void onActivityPaused(Activity activity) { if (resumed.get() == activity) resumed.clear(); }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) { if (resumed.get() == activity) resumed.clear(); }
}
