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
    private static final String FOREGROUND_ALLOW = "com.miui.voiceassist.MIAIPLUS_FOREGROUND_ALLOW";
    private static final String FOREGROUND_DENY = "com.miui.voiceassist.MIAIPLUS_FOREGROUND_DENY";
    private static final String CONFIRM_ACTION = "com.miui.voiceassist.MIAIPLUS_CONFIRM_ACTION";
    private static final String DENY_ACTION = "com.miui.voiceassist.MIAIPLUS_DENY_ACTION";
    private static final String RETURN_TOKEN = "io.github.miaiplus.RETURN_CONVERSATION";
    private final Application app;
    private final Context receiverContext;
    private final ClassLoader loader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> resumed = new WeakReference<>(null);
    private ActivityManager.AppTask originTask;
    private PendingIntent returnIntent;
    private String returnToken;
    private PendingIntent consentAllow, consentDeny;
    private String controlToken;
    private String screenTask;
    private String shownProgress = "";
    private String consentToken, shownConsentToken;
    private boolean actionConfirmation;
    private boolean compact;
    private Object overlayManager;
    private View overlayRoot;
    private boolean overlayHidden;
    private int overlayFlags;
    private float overlayDim;
    private final Runnable expireOverlay = this::releaseOverlay;
    private volatile boolean running;
    /**
     * When the host UI must stay off the user's screen, even though the task is already over.
     *
     * <p>The host renders the final tool result on its own thread after the task ends, and that rendering is what
     * used to pull XiaoAi forward — so suppressing only while {@link #running} would let the jump come back on the
     * last step. Explicit user wake-up and launcher entries end this window immediately.
     */
    private volatile long hostQuietUntil;
    /**
     * Short by design. The final tool result is rendered by the host within a second of the task ending, so a few
     * seconds cover it. User wake-up is recognized at the host's service entry, before the UI guards, so even an
     * immediate new request can open the assistant without letting old tool-result updates reopen it on their own.
     */
    private static final long HOST_QUIET_AFTER_FINISH_MS = 10_000;
    /** Host entry points that could not be hooked on this build; empty means every known path is guarded. */
    final java.util.List<String> missingGuards = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private java.util.concurrent.ScheduledExecutorService watcher;
    private final BroadcastReceiver stop = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String allow = actionConfirmation ? CONFIRM_ACTION : FOREGROUND_ALLOW;
            String deny = actionConfirmation ? DENY_ACTION : FOREGROUND_DENY;
            if (running && (allow.equals(intent.getAction()) || deny.equals(intent.getAction()))
                && consentToken != null && consentToken.equals(intent.getStringExtra("token"))
                && java.util.Objects.equals(screenTask, intent.getStringExtra("screen_task"))) {
                Bundle answer = new Bundle();
                answer.putString("token", consentToken);
                boolean accepted = allow.equals(intent.getAction());
                if (accepted && app.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                    // A ROM may deliver the one-shot broadcast during a relock race. Reissue the
                    // notification on the next poll so unlocking does not leave a consumed button.
                    if (consentAllow != null) consentAllow.cancel();
                    consentAllow = null;
                    shownProgress = "";
                    return;
                }
                answer.putBoolean("allow", accepted);
                app.getContentResolver().call(BridgeProvider.URI,
                    actionConfirmation ? "action_confirmation" : "foreground_consent", screenTask, answer);
                return;
            }
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
        // Consent PendingIntents use a unique data URI so a stale action cannot turn into a later grant.
        // Keep its scheme on a separate filter: STOP deliberately has no data URI.
        receiverContext.registerReceiver(stop, consentFilter(), Context.RECEIVER_NOT_EXPORTED);
        // Verified against the supported host's VoiceService.processIntent(Intent): these actions start an
        // actual voice/key/headset request, unlike the UI refreshes produced by a completed tool call.
        try {
            Reflector.findAndHookMethod("com.xiaomi.voiceassistant.VoiceService", loader, "processIntent", Intent.class, new HookCallback() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Exception {
                    if (p.args[0] instanceof Intent intent && isUserWakeAction(intent.getAction())) userEnteredHost();
                }
            });
        } catch (RuntimeException missing) {
            android.util.Log.w("MiAIPlus", "Host wake-up entry unavailable", missing);
            missingGuards.add("com.xiaomi.voiceassistant.VoiceService.processIntent");
        }
        // The supported router declares onCreate and routes to N/L later in the same method. Observe the
        // launcher intent before those guards run; a task restored from recents is not a fresh launcher click.
        try {
            Reflector.findAndHookMethod("com.xiaomi.voiceassistant.LaunchHomeRouterActivity", loader, "onCreate", Bundle.class, new HookCallback() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Exception {
                    Intent intent = ((Activity)p.thisObject).getIntent();
                    if (intent != null && Intent.ACTION_MAIN.equals(intent.getAction())
                        && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                        && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) userEnteredHost();
                }
            });
        } catch (RuntimeException missing) {
            android.util.Log.w("MiAIPlus", "Host launcher entry unavailable", missing);
            missingGuards.add("com.xiaomi.voiceassistant.LaunchHomeRouterActivity.onCreate");
        }
        // A completion click is an explicit user entry. Let the pinned host consume its conversation
        // navigation data even during the short window that suppresses old tool-result UI updates.
        for (String method : new String[]{"onCreate", "onNewIntent"}) {
            try {
                Reflector.findAndHookMethod("com.xiaomi.voiceassistant.TabHostMainActivity", loader, method,
                    method.equals("onCreate") ? Bundle.class : Intent.class, new HookCallback() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) throws Exception {
                            Intent intent = method.equals("onCreate") ? ((Activity)p.thisObject).getIntent() : (Intent)p.args[0];
                            if (!running && intent != null && returnToken != null
                                && returnToken.equals(intent.getStringExtra(RETURN_TOKEN))) userEnteredHost();
                        }
                    });
            } catch (RuntimeException missing) {
                missingGuards.add("com.xiaomi.voiceassistant.TabHostMainActivity." + method);
            }
        }
        // Shortcut wake-up uses a system window, without a resumed FloatActivity.
        // Keep native streaming UI updates from reattaching that window over the app being operated.
        Reflector.findAndHookMethod("com.xiaomi.voiceassistant.UiManager", loader, "start", Intent.class, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Exception {
                if (running) { p.setResult(null); return; }
                if (!running && overlayHidden) MainThread.call(() -> { releaseOverlay(); return null; });
            }
        });
        // Keep the assistant UI off the user's screen for the whole task, blocked at its entry points.
        //
        // Only reacting to onActivityResumed was too late: that callback fires once the host Activity has already
        // resumed and drawn, so the user saw XiaoAi flash over their app on every tool call before it was pushed
        // back. These four methods are where the host's own code decides to bring itself forward — the router that
        // relays a chat launch, the `startActivity` for the chat page itself, and the two paths that move an
        // already-existing chat task to the front. Refusing them while a task runs means nothing is ever drawn.
        // Q, not G: the dex keeps G as a four-instruction synthetic bridge that forwards here, so hooking the
        // bridge works only as long as nothing calls the real method directly. Q is the actual 1080-instruction
        // startImpl that every UiManager.start overload funnels into.
        guard(loader, "com.xiaomi.voiceassistant.mainui.c4", "Q", new Class<?>[]{Intent.class});
        guard(loader, "com.xiaomi.voiceassistant.TabHostMainActivity$a", "g", new Class<?>[]{
            Context.class, String.class, boolean.class, boolean.class, Bundle.class,
            Reflector.findClass("com.xiaomi.voiceassistant.data.model.PageNavigationData", loader), boolean.class});
        guard(loader, "com.xiaomi.voiceassistant.LaunchHomeRouterActivity", "N", new Class<?>[]{});
        guard(loader, "com.xiaomi.voiceassistant.LaunchHomeRouterActivity", "L", new Class<?>[]{String.class});
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
        boolean newTask = MainThread.call(() -> !running || !java.util.Objects.equals(screenTask, taskId));
        // Repository reads may suspend. Resolve on the tool worker, before entering Android's main thread.
        Intent conversation = newTask ? ConversationReturn.capture(app, loader) : null;
        begin(taskId, conversation);
    }
    void begin(String taskId, Intent conversation) throws Exception {
        MainThread.call(() -> {
                if (running && !java.util.Objects.equals(screenTask, taskId)) {
                    // A new task can arrive before the previous task's last poll.
                    // Keep the saved entry hidden, but retire its observer and queued callbacks.
                    screenTask = taskId;
                    stopWatching();
                    setReturnIntent(conversation, resumed.get());
                    controlToken = java.util.UUID.randomUUID().toString();
                    shownProgress = "正在理解任务";
                    clearConsentActions(); consentToken = null; shownConsentToken = null;
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
                    setReturnIntent(conversation, activity);
                    controlToken = java.util.UUID.randomUUID().toString();
                    shownProgress = "正在理解任务";
                    clearConsentActions(); consentToken = null; shownConsentToken = null;
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
    private void setReturnIntent(Intent conversation, Activity activity) {
        Intent intent = conversation != null ? new Intent(conversation)
            : activity == null ? app.getPackageManager().getLaunchIntentForPackage(app.getPackageName())
            : new Intent(activity.getIntent()).setComponent(activity.getComponentName());
        if (intent == null) intent = new Intent().setClassName(app.getPackageName(), "com.xiaomi.voiceassistant.LaunchHomeRouterActivity");
        returnToken = java.util.UUID.randomUUID().toString();
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setIdentifier("miaiplus:return:" + returnToken)
            .putExtra(RETURN_TOKEN, returnToken);
        returnIntent = PendingIntent.getActivity(app, NOTIFICATION, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
                        String confirmationToken = state.getString("action_confirmation");
                        boolean confirmAction = confirmationToken != null;
                        String requestToken = confirmAction ? confirmationToken : state.getString("foreground_consent");
                        String requestLabel = state.getString("foreground_label", "当前应用");
                        String confirmationMessage = state.getString("confirmation_message", "");
                        main.post(() -> {
                            if (!running || !java.util.Objects.equals(screenTask, watchedTask)
                                || progress.isEmpty() || (progress.equals(shownProgress)
                                    && java.util.Objects.equals(consentToken, requestToken))) return;
                            if (!java.util.Objects.equals(consentToken, requestToken)) clearConsentActions();
                            consentToken = requestToken;
                            actionConfirmation = confirmAction;
                            try {
                                publish(requestToken == null ? "正在操作手机" : confirmAction ? "小爱请求确认操作" : "小爱请求使用当前应用",
                                    requestToken == null ? progress : confirmAction ? confirmationMessage
                                        : "使用“" + requestLabel + "”并转为前台控屏，是否同意？", true);
                                shownProgress = progress;
                            }
                            catch (Exception failure) { android.util.Log.w("MiAIPlus", "Unable to update task intent"); }
                        });
                        return;
                    }
                    // A completed tool call is not a completed control session. Only the provider's explicit
                    // terminal acknowledgement for this task may publish a completion notification.
                    boolean completed = completedTask(state, watchedTask);
                    if (state != null && java.util.Objects.equals(watchedTask, state.getString("screen_task"))
                        && !completed) return;
                    main.post(() -> {
                        if (java.util.Objects.equals(screenTask, watchedTask))
                            finish(state == null ? "控屏连接已中断" : state.getString("result", "操作已结束"),
                                completed);
                    });
                } catch (RuntimeException failure) { main.post(() -> {
                    if (java.util.Objects.equals(screenTask, watchedTask)) finish("控屏连接已中断", false);
                }); }
            }, 700, 700, java.util.concurrent.TimeUnit.MILLISECONDS);
        });
    }
    static boolean completedTask(Bundle state, String task) {
        return state != null && task != null && task.equals(state.getString("screen_task"))
            && state.getBoolean("completed") && !state.getBoolean("active");
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
        boolean updating = ongoing && running && java.util.Objects.equals(consentToken, shownConsentToken);
        v2.put("islandFirstFloat", !updating).put("enableFloat", !updating);
        Intent stopIntent = new Intent(STOP).setPackage(app.getPackageName()).putExtra("token", controlToken);
        PendingIntent stopPending = PendingIntent.getBroadcast(app, NOTIFICATION, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action stopAction = new Notification.Action.Builder((android.graphics.drawable.Icon)null,
            "停止操作", stopPending).build();
        Notification.Action allowAction = null, denyAction = null;
        if (ongoing && consentToken != null) {
            allowAction = consentAction(true);
            denyAction = consentAction(false);
        }
        Bundle pictures = (Bundle)Reflector.callMethod(nativeBuilder, "getIconResource", app);
        if (ongoing) {
            // HyperOS expanded focus cards read param_v2.actions, not Notification.actions.
            Object controls = Reflector.newInstance(Reflector.findClass("gp1.c", loader));
            pictures.putAll((Bundle)Reflector.callMethod(controls, "getIconResource", app));
            org.json.JSONArray focusActions = new org.json.JSONArray();
            if (allowAction != null) {
                focusActions.put(new JSONObject().put("action", actionConfirmation ? "miaiplus_action_allow" : "miaiplus_foreground_allow")
                    .put("actionTitle", actionConfirmation ? "确认本次操作" : "同意使用"));
                focusActions.put(new JSONObject().put("action", actionConfirmation ? "miaiplus_action_deny" : "miaiplus_foreground_deny")
                    .put("actionTitle", actionConfirmation ? "停止任务" : "拒绝并暂停"));
            } else focusActions.put(new JSONObject()
                .put("action", "miaiplus_stop").put("actionTitle", "停止操作").put("actionIcon", "miui.focus.pic_action_close")
                .put("actionIconDark", "miui.focus.pic_action_close_dark"));
            v2.put("actions", focusActions);
        }
        Bundle extras = new Bundle();
        extras.putString("miui.focus.param", params.toString());
        extras.putBundle("miui.focus.pics", pictures);
        if (ongoing) {
            Bundle actions = new Bundle(); actions.putParcelable("miaiplus_stop", stopAction);
            if (allowAction != null) {
                actions.putParcelable(actionConfirmation ? "miaiplus_action_allow" : "miaiplus_foreground_allow", allowAction);
                actions.putParcelable(actionConfirmation ? "miaiplus_action_deny" : "miaiplus_foreground_deny", denyAction);
            }
            extras.putBundle("miui.focus.actions", actions);
        }
        Notification.Builder builder = new Notification.Builder(app, CHANNEL).setSmallIcon(app.getApplicationInfo().icon)
            .setContentTitle(title).setContentText(text).setContentIntent(consentToken == null ? returnIntent : null).setOngoing(ongoing)
            .setCategory(Notification.CATEGORY_PROGRESS).setOnlyAlertOnce(updating).setAutoCancel(!ongoing).addExtras(extras);
        if (ongoing && actionConfirmation) builder.setStyle(new Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_PRIVATE);
        if (allowAction != null) builder.addAction(allowAction).addAction(denyAction).addAction(stopAction);
        else if (ongoing) builder.addAction(stopAction);
        else builder.setTimeoutAfter(30_000);
        manager.notify(NOTIFICATION, builder.build());
        shownConsentToken = consentToken;
    }
    private Notification.Action consentAction(boolean allow) {
        Intent answer = consentIntent(app.getPackageName(), screenTask, consentToken, allow, actionConfirmation);
        PendingIntent pending = PendingIntent.getBroadcast(app, allow ? NOTIFICATION + 1 : NOTIFICATION + 2, answer,
            PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        if (allow) consentAllow = pending; else consentDeny = pending;
        return new Notification.Action.Builder((android.graphics.drawable.Icon)null,
            actionConfirmation ? allow ? "确认本次操作" : "停止任务" : allow ? "同意使用当前应用" : "拒绝并暂停任务", pending)
            .setAuthenticationRequired(allow).build();
    }
    static IntentFilter consentFilter() {
        IntentFilter filter = new IntentFilter(FOREGROUND_ALLOW);
        filter.addAction(FOREGROUND_DENY); filter.addAction(CONFIRM_ACTION); filter.addAction(DENY_ACTION);
        filter.addDataScheme("miaiplus");
        return filter;
    }
    static Intent consentIntent(String hostPackage, String task, String token, boolean allow) {
        return consentIntent(hostPackage, task, token, allow, false);
    }
    static Intent consentIntent(String hostPackage, String task, String token, boolean allow, boolean confirmAction) {
        return new Intent(confirmAction ? allow ? CONFIRM_ACTION : DENY_ACTION : allow ? FOREGROUND_ALLOW : FOREGROUND_DENY).setPackage(hostPackage)
            .setData(android.net.Uri.parse("miaiplus://" + (confirmAction ? "confirmation/" : "foreground/") + token + "/" + (allow ? "allow" : "deny")))
            .putExtra("token", token).putExtra("screen_task", task);
    }
    private void clearConsentActions() {
        if (consentAllow != null) consentAllow.cancel();
        if (consentDeny != null) consentDeny.cancel();
        consentAllow = null; consentDeny = null;
        actionConfirmation = false;
    }
    /**
     * Refuses one host bring-to-front entry point while a control task owns the phone.
     *
     * <p>The hook is installed even when the exact method is absent, so a host build that renames one of these
     * still gets the others instead of failing the whole install. A missing target is reported rather than
     * silently dropped, because it changes which path can still flash.
     */
    private void guard(ClassLoader loader, String type, String method, Class<?>[] signature) {
        HookCallback callback = new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                // Outside a task and its short aftermath, XiaoAi behaves normally.
                if (!hostQuiet()) return;
                // N() answers whether it moved an existing task to the front; false is its own "did nothing".
                p.setResult(method.equals("N") ? Boolean.FALSE : null);
            }
        };
        // Built as Object[], not as a copy of the Class[]: copyOf keeps the array's own component type, and
        // storing the callback in a Class[] threw ArrayStoreException instead of installing the hook.
        Object[] hookSignature = new Object[signature.length + 1];
        System.arraycopy(signature, 0, hookSignature, 0, signature.length);
        hookSignature[signature.length] = callback;
        try {
            Reflector.findAndHookMethod(Reflector.findClass(type, loader), method, hookSignature);
        } catch (RuntimeException missing) {
            // Reported rather than silently dropped: a missing guard changes which path can still flash.
            android.util.Log.w("MiAIPlus", "Host UI guard unavailable: " + type + "." + method);
            missingGuards.add(type + "." + method);
        }
    }
    /** Whether the host must not bring its own UI forward right now. */
    private boolean hostQuiet() {
        return running || android.os.SystemClock.elapsedRealtime() < hostQuietUntil;
    }
    private static boolean isUserWakeAction(String action) {
        return "com.miui.voiceassist.ACTION_VOICE_START_VOICEASSIST".equals(action)
            || "com.miui.voicetrigger.ACTION_VOICE_TRIGGER_START_VOICEASSIST".equals(action)
            || "com.xiaoai.ACTION_BLUETOOTH_START_VOICEASSIST".equals(action)
            || Intent.ACTION_VOICE_COMMAND.equals(action);
    }
    private void userEnteredHost() throws Exception {
        MainThread.call(() -> {
            // A new request must not take the screen away from an active control task.
            if (!running) {
                hostQuietUntil = 0;
                if (overlayHidden) releaseOverlay();
            }
            return null;
        });
    }
    private void stopWatching() {
        if (watcher != null) {
            watcher.shutdownNow();
            watcher = null;
        }
    }
    /**
     * Ends the task and asks the user what to do next instead of deciding for them.
     *
     * <p>Finishing used to pull XiaoAi straight back to the front. That took the user out of whatever they were
     * doing at the exact moment the task stopped being their concern: the work was over, but their screen still
     * moved. Now the result only appears as a focus-island card, and tapping it is what returns them — so the phone
     * stays where they left it unless they choose otherwise.
     */
    private void finish(String result, boolean restore) {
        if (!running) return;
        stopWatching();
        app.getSystemService(NotificationManager.class).cancel(NOTIFICATION);
        running = false;
        clearConsentActions(); consentToken = null; shownConsentToken = null;
        // Hold the host UI back until its own result rendering has finished. Without this, the final tool result
        // is drawn on the user's screen a moment after the task ends, which is the same jump, just later.
        hostQuietUntil = android.os.SystemClock.elapsedRealtime() + HOST_QUIET_AFTER_FINISH_MS;
        if (restore && !app.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            try {
                publish(completionTitle(result), completionText(result), false);
            } catch (Exception failure) { android.util.Log.w("MiAIPlus", "Unable to post the completion card", failure); }
        }
        originTask = null; returnIntent = null; controlToken = null; screenTask = null; running = false;
        if (overlayHidden) main.postDelayed(expireOverlay, 30_000);
    }
    static String completionTitle(String result) {
        if (result != null && result.startsWith("PAUSE")) return "需要你接管手机";
        return completedSuccessfully(result) ? "手机操作已结束" : "手机操作已停止";
    }
    static String completionText(String result) {
        if (result != null && result.startsWith("PAUSE")) return "点击返回小爱处理";
        return completedSuccessfully(result) ? "已完成，点击返回小爱查看结果"
            : "任务未完成，点击返回小爱查看原因";
    }
    private static boolean completedSuccessfully(String result) {
        return result != null && result.startsWith("模型报告任务完成：");
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
        clearConsentActions();
        main.removeCallbacksAndMessages(null);
        app.unregisterActivityLifecycleCallbacks(this); receiverContext.unregisterReceiver(stop);
        app.getSystemService(NotificationManager.class).cancel(NOTIFICATION);
        resumed.clear(); overlayManager = null; overlayRoot = null; returnIntent = null; returnToken = null; originTask = null; return null;
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
