package io.github.miaiplus;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** One resolved launcher submission. Only failure before sending a request permits public-API fallback. */
final class ForegroundAppLauncher {
    private static final String READY = "MIAIPLUS_FOREGROUND_READY_1";
    private static final String EOF = "MIAIPLUS_FOREGROUND_EOF";
    private static final long READY_TIMEOUT_MS = 2_000;
    private static final long REPLY_TIMEOUT_MS = 4_000;

    private ForegroundAppLauncher() { }

    static boolean launch(Context context, AppLaunchTarget target, Runnable check) throws Exception {
        checkUnlocked(context, check);
        PhoneUseLog.event("launch_transport", "backend", "root", "phase", "starting");
        String apk = context.getPackageCodePath();
        if (apk == null || !new File(apk).isFile()) {
            PhoneUseLog.event("launch_transport", "backend", "standard", "phase", "apk_unavailable");
            return false;
        }
        Process helper;
        try {
            helper = new ProcessBuilder("su", "-c", "CLASSPATH='" + apk.replace("'", "'\\''")
                + "' exec /system/bin/app_process /system/bin io.github.miaiplus.helper.ForegroundLaunchHelper")
                .redirectError(new File("/dev/null")).start();
        } catch (IOException unavailable) {
            checkUnlocked(context, check);
            PhoneUseLog.event("launch_transport", "backend", "standard", "phase", "su_start_failed");
            return false;
        }
        ArrayBlockingQueue<String> replies = new ArrayBlockingQueue<>(8);
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder line = new StringBuilder();
                for (int next; (next = input.read()) >= 0; ) {
                    if (next == '\n') {
                        if (!replies.offer(line.toString())) break;
                        line.setLength(0);
                    } else if (line.length() < 4096) line.append((char) next);
                    else break;
                }
            } catch (IOException ignored) { }
            replies.offer(EOF);
        }, "foreground-launch-reply");
        reader.setDaemon(true);
        reader.start();
        boolean sent = false;
        try (BufferedWriter output = new BufferedWriter(new OutputStreamWriter(helper.getOutputStream(), StandardCharsets.UTF_8))) {
            long readyDeadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS;
            boolean ready = false;
            while (SystemClock.elapsedRealtime() < readyDeadline) {
                checkUnlocked(context, check);
                String reply = replies.poll(20, TimeUnit.MILLISECONDS);
                if (READY.equals(reply)) { ready = true; break; }
                if (EOF.equals(reply)) {
                    PhoneUseLog.event("launch_transport", "backend", "standard", "phase", "root_unavailable");
                    return false;
                }
            }
            if (!ready) {
                PhoneUseLog.event("launch_transport", "backend", "standard", "phase", "ready_timeout");
                return false;
            }
            checkUnlocked(context, check);
            String token = UUID.randomUUID().toString();
            JSONObject request = new JSONObject().put("token", token)
                .put("userId", android.os.Process.myUid() / 100000)
                .put("package", target.packageName()).put("activity", target.activityName())
                .put("deadline", SystemClock.elapsedRealtime() + 1_000);
            // Set before writing: a partial write is indeterminate and must never cause a second launch.
            sent = true;
            output.write(request.toString());
            output.newLine();
            output.flush();
            PhoneUseLog.event("launch_transport", "backend", "root", "phase", "sent");
            long replyDeadline = SystemClock.elapsedRealtime() + REPLY_TIMEOUT_MS;
            while (SystemClock.elapsedRealtime() < replyDeadline) {
                checkUnlocked(context, check);
                String line = replies.poll(20, TimeUnit.MILLISECONDS);
                if (EOF.equals(line)) throw new IOException("启动助手在提交请求后退出，已停止，不能重复启动");
                if (line == null || !line.startsWith("{")) continue;
                JSONObject reply = new JSONObject(line);
                if (!token.equals(reply.optString("token"))) continue;
                if (!"submitted".equals(reply.optString("status")))
                    throw new IllegalStateException("启动助手未完成提交，已停止：" + reply.optString("code", "FAILED"));
                checkUnlocked(context, check);
                PhoneUseLog.event("launch_transport", "backend", "root", "phase", "submitted");
                return true;
            }
            throw new IOException("启动助手提交结果超时，已停止，不能重复启动");
        } catch (IOException unavailable) {
            checkUnlocked(context, check);
            if (sent) throw unavailable;
            PhoneUseLog.event("launch_transport", "backend", "standard", "phase", "request_not_sent");
            return false;
        } finally {
            // EOF is the root process's cancellation signal; it owns and terminates its own am child.
            try { helper.getOutputStream().close(); } catch (IOException ignored) { }
            if (!helper.waitFor(100, TimeUnit.MILLISECONDS)) helper.destroy();
        }
    }

    private static void checkUnlocked(Context context, Runnable check) {
        check.run();
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (keyguard == null || keyguard.isKeyguardLocked()) throw new IllegalStateException("请先解锁手机");
    }
}
