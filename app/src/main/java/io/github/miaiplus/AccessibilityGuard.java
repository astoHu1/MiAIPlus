package io.github.miaiplus;

import android.content.Context;
import android.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** On-demand root preflight. No background daemon or boot-time repair. */
public final class AccessibilityGuard {
    private static final String BASE = "/data/adb/miaiplus-accessibility";
    private static final java.util.concurrent.ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static volatile String status = "";
    private static volatile boolean changing;
    private AccessibilityGuard() {}
    public static boolean enabled(Context context) {
        return Config.prefs(context).getBoolean("accessibility_guard", false);
    }
    public static boolean isChanging() { return changing; }
    public static String status(Context context) {
        if (changing || status.startsWith("root 操作失败")) return status;
        return enabled(context) ? "已启用；仅在控屏开始前检查，掉线时修复" : "未启用";
    }
    public static void restore(Context context) {
        if (enabled(context) && !Config.prefs(context).getBoolean("accessibility_preflight_migrated", false))
            setEnabled(context, true, null);
    }
    /** Retire only this module's previous persistent guard, including its boot entry. */
    private static String retireLegacy() {
        return "[ \"$(id -u)\" = 0 ] || exit 3\n"
            + "rm -f /data/adb/service.d/miaiplus-accessibility.sh\n"
            + "for state in " + BASE + "/user*; do\n"
            + "  rm -f \"$state/enabled\"\n"
            + "  [ -f \"$state/pid\" ] || continue\n"
            + "  read -r guard_pid < \"$state/pid\"\n"
            + "  case \"$guard_pid\" in ''|*[!0-9]*) continue ;; esac\n"
            + "  if tr '\\000' ' ' < \"/proc/$guard_pid/cmdline\" 2>/dev/null | grep -Fq '" + BASE + "/daemon.sh '; then\n"
            + "    kill -TERM \"$guard_pid\" 2>/dev/null || true\n"
            + "  fi\ndone\n";
    }
    @android.annotation.SuppressLint("ApplySharedPref") // Root probes must see the committed opt-in.
    public static synchronized void setEnabled(Context context, boolean enable, Consumer<Boolean> complete) {
        if (changing) { if (complete != null) complete.accept(enabled(context)); return; }
        changing = true;
        status = enable ? "正在启用任务前检查…" : "正在关闭任务前检查…";
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                if (enable || !Config.prefs(app).getBoolean("accessibility_preflight_migrated", false))
                    root(retireLegacy(), null);
                if (!Config.prefs(app).edit().putBoolean("accessibility_guard", enable)
                    .putBoolean("accessibility_preflight_migrated", true).commit())
                    throw new java.io.IOException("preference write failed");
                status = "";
            } catch (Exception failure) {
                status = "root 操作失败：请检查 MiAIPlus 的 root 授权后重试";
            } finally {
                changing = false;
                if (complete != null) new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> complete.accept(enabled(app)));
            }
        });
    }
    static void prepare(Context context, RunState run) throws Exception {
        run.check();
        if (ScreenService.isHealthy()) return;
        if (!enabled(context)) throw new IllegalStateException("MiAIPlus 无障碍未连接，请开启服务或启用 root 任务前检查");
        run.progress = "正在恢复无障碍连接";
        int user = android.os.Process.myUid() / 100000;
        String encoded;
        try (var input = context.getAssets().open("accessibility-preflight.sh")) {
            encoded = Base64.encodeToString(input.readAllBytes(), Base64.NO_WRAP);
        }
        // The UUID and UID are generated locally; model text never enters a shell command.
        String command = retireLegacy() + "printf '%s' '" + encoded + "' | base64 -d | /system/bin/sh -s -- " + user + " " + run.id;
        try { root(command, run); }
        catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
        catch (Exception failure) { throw new IllegalStateException("无障碍恢复失败，请检查 MiAIPlus 的 root 授权与系统设置"); }
        run.check();
        if (!ScreenService.isHealthy()) throw new IllegalStateException("无障碍未恢复连接，已停止任务");
    }
    private static void root(String script, RunState run) throws Exception {
        Process process = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
        try {
            process.getOutputStream().close();
            Thread drain = new Thread(() -> {
                try (var output = process.getInputStream()) { output.transferTo(java.io.OutputStream.nullOutputStream()); }
                catch (java.io.IOException ignored) { }
            }, "miaiplus-root-output");
            drain.setDaemon(true); drain.start();
            long deadline = android.os.SystemClock.elapsedRealtime() + 45_000;
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (run != null) run.check();
                if (android.os.SystemClock.elapsedRealtime() >= deadline) throw new java.io.IOException("root timeout");
            }
            if (process.exitValue() != 0) throw new java.io.IOException("root rejected");
        } finally { process.destroy(); }
    }
}
