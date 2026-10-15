package io.github.miaiplus;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/** Scoped IPC: small commands, ephemeral image pipes, and opt-in external model configuration. */
public final class BridgeProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://io.github.miaiplus.bridge");
    private static final Map<String, RunState> JOBS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor();
    private static volatile RunState active;
    private static volatile String screenResult = "";
    @Override public boolean onCreate() { return true; }
    private void enforceCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages == null || !Arrays.asList(packages).contains("com.miui.voiceassist"))
            throw new SecurityException("MiAIPlus bridge is private to XiaoAi");
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        enforceCaller();
        Bundle b = new Bundle();
        Config config = new Config(getContext());
        if (method.equals("hook_report")) {
            String report = extras == null ? "" : extras.getString("report", "");
            Config.prefs(getContext()).edit().putString("hook_report", report).putLong("hook_at", System.currentTimeMillis()).apply();
            return b;
        }
        if (method.equals("stop_screen")) { cancelActive(); return b; }
        if (method.equals("screen_state")) {
            RunState current = active;
            b.putBoolean("active", NativeSession.hasPendingOperation()
                || (current != null && current.screen && current.status.equals("running")));
            b.putString("result", screenResult);
            return b;
        }
        if (method.equals("flags")) {
            b.putBoolean("enabled", config.enabled);
            b.putBoolean("nativePlanner", config.planner.equals("native"));
            b.putBoolean("accessibility", ScreenService.instance != null);
            return b;
        }
        if (method.equals("set_reasoning")) {
            if (!config.globalModel || extras == null
                || !config.endpoint.equals(extras.getString("endpoint"))
                || !config.model.equals(extras.getString("model")) || !config.api.equals(extras.getString("api")))
                throw new IllegalStateException("模型配置已更改，请重新打开推理强度菜单");
            String effort = extras.getString("reasoning", "default");
            if (!ReasoningPolicy.choices(config.model, config.api).contains(effort))
                throw new IllegalArgumentException("当前模型不支持该推理强度");
            if (!Config.prefs(getContext()).edit().putString(
                ReasoningPolicy.preferenceKey(config.endpoint, config.model, config.api), effort).commit())
                throw new IllegalStateException("推理强度保存失败");
        }
        if (method.equals("model_config") || method.equals("set_reasoning")) {
            b.putBoolean("globalModel", config.globalModel);
            if (config.globalModel) {
                // Ephemeral IPC to the scoped XiaoAi UID; never write credentials into its files.
                b.putString("endpoint", config.endpoint); b.putString("apiKey", config.apiKey); b.putString("model", config.model);
                b.putString("api", config.api);
                b.putString("reasoning", method.equals("set_reasoning") ? extras.getString("reasoning", "default") : config.reasoning);
            }
            return b;
        }
        if (method.equals("start")) {
            if (!config.enabled) { b.putString("error", "MiAIPlus 未启用"); return b; }
            String query = extras == null ? null : extras.getString("query");
            String mode = extras == null ? "screen" : extras.getString("mode", "screen");
            if (!mode.equals("screen") && !mode.equals("chat")) { b.putString("error", "未知模式"); return b; }
            if (NativeSession.hasActiveSession() && (!mode.equals("screen") || !config.planner.equals("native"))) {
                b.putString("error", "已有小爱逐步控屏会话，请先停止"); return b;
            }
            if (NativeSession.hasActiveSession() && (query == null || !query.startsWith("MIAIPLUS:"))) {
                b.putString("error", "已有控屏会话，请先停止或继续当前会话"); return b;
            }
            synchronized (JOBS) {
                if (active != null) { b.putString("error", "已有任务执行中，请先停止"); return b; }
                JOBS.entrySet().removeIf(e -> !e.getValue().status.equals("running")
                    && android.os.SystemClock.elapsedRealtime() > e.getValue().deadline + 300_000);
                if (JOBS.size() >= 16) JOBS.values().stream().filter(r -> !r.status.equals("running"))
                    .min(java.util.Comparator.comparingLong(r -> r.deadline)).ifPresent(r -> JOBS.remove(r.id));
                RunState run = new RunState(UUID.randomUUID().toString(), Binder.getCallingUid());
                run.screen = mode.equals("screen");
                if (!run.screen || extras == null || !extras.getBoolean("screen_ui")) run.screenReady.countDown();
                active = run; JOBS.put(run.id, run);
                var timeout = WATCHDOG.schedule(run::cancel, 240, java.util.concurrent.TimeUnit.SECONDS);
                WORKER.execute(() -> {
                    try {
                        if (!run.screenReady.await(6, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("小爱交互界面未就绪，已停止");
                        run.check();
                        String text = AgentEngine.run(config, mode, query, run);
                        run.result = text.length() > 24000 ? text.substring(0, 24000) + "\n[结果过长已截断]" : text;
                        run.check(); run.status = "done";
                    }
                    catch (Exception e) {
                        // Keep exception details out of IPC to avoid echoing API bodies or typed content.
                        run.result = run.cancelled.get() ? "任务已停止或超时" : publicError(e); run.status = "error";
                    } finally {
                        if (run.screen) screenResult = run.result;
                        timeout.cancel(false); synchronized (JOBS) { active = null; }
                    }
                });
                b.putString("id", run.id); return b;
            }
        }
        RunState run = JOBS.get(arg);
        if (run == null || (run.owner != Binder.getCallingUid() && Binder.getCallingUid() != Process.myUid())) {
            b.putString("error", "任务不存在或不可访问"); return b;
        }
        if (method.equals("screen_ready")) run.screenReady.countDown();
        else if (method.equals("cancel")) run.cancel();
        else if (!method.equals("poll")) throw new IllegalArgumentException("Unknown bridge method");
        b.putString("status", run.status); b.putString("result", run.result); b.putString("progress", run.progress);
        return b;
    }
    private static String publicError(Exception e) {
        Throwable cause = e;
        while ((cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof IllegalStateException || cause instanceof IllegalArgumentException || cause instanceof java.util.concurrent.CancellationException)
            return cause.getMessage() == null ? "任务失败" : cause.getMessage();
        if (cause instanceof java.net.SocketTimeoutException) return "模型请求超时，任务已停止";
        if (cause instanceof org.json.JSONException) return "模型响应不符合动作协议";
        return "任务失败（" + cause.getClass().getSimpleName() + "），未切回原控屏以免重复操作";
    }
    public static void cancelActive() { RunState run = active; if (run != null) run.cancel(); NativeSession.cancel(); }
    @Override public android.os.ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        enforceCaller();
        if (!mode.equals("r") || uri.getPathSegments().size() != 2 || !uri.getPathSegments().get(0).equals("frame"))
            throw new java.io.FileNotFoundException("Unknown frame");
        byte[] bytes = FrameStore.take(uri.getLastPathSegment(), Binder.getCallingUid());
        try {
            var pipe = android.os.ParcelFileDescriptor.createPipe();
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try (var out = new android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { out.write(bytes); }
                catch (java.io.IOException ignored) { }
            });
            return pipe[0];
        } catch (java.io.IOException e) { throw new java.io.FileNotFoundException("Frame pipe unavailable"); }
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String order) { throw new SecurityException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new SecurityException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new SecurityException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new SecurityException(); }
}
