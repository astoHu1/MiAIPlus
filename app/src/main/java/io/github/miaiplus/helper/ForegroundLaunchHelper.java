package io.github.miaiplus.helper;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Root-side, single-use MAIN/LAUNCHER submission. It cannot accept shell text or an arbitrary Intent. */
public final class ForegroundLaunchHelper {
    private static final String READY = "MIAIPLUS_FOREGROUND_READY_1";
    private static final String EOF = "MIAIPLUS_FOREGROUND_EOF";
    private static volatile boolean cancelled;
    private static volatile Process command;

    private ForegroundLaunchHelper() { }

    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 0) return;
        Context context = systemContext();
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "window");
        Object windowManager = Class.forName("android.view.IWindowManager$Stub")
            .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
        Method locked = windowManager.getClass().getMethod("isKeyguardLocked");
        locked.setAccessible(true);
        Method query = android.content.pm.PackageManager.class.getMethod("queryIntentActivitiesAsUser", Intent.class, int.class, int.class);
        if (Boolean.TRUE.equals(locked.invoke(windowManager))) return;
        ArrayBlockingQueue<String> requests = new ArrayBlockingQueue<>(2);
        Thread input = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                StringBuilder line = new StringBuilder();
                for (int next; (next = reader.read()) >= 0; ) {
                    if (next == '\n') {
                        requests.offer(line.toString());
                        // A second request or EOF cancels this one-shot process.
                        reader.read();
                        break;
                    }
                    if (line.length() >= 2048) break;
                    line.append((char) next);
                }
            } catch (Exception ignored) { }
            cancelled = true;
            Process child = command;
            if (child != null) child.destroyForcibly();
            requests.offer(EOF);
        }, "foreground-launch-input");
        input.setDaemon(true);
        input.start();
        System.out.println(READY);
        System.out.flush();
        String line = requests.poll(3, TimeUnit.SECONDS);
        if (line == null || EOF.equals(line) || cancelled) return;
        String token = "";
        try {
            JSONObject request = new JSONObject(line);
            token = request.getString("token");
            int userId = request.getInt("userId");
            String pkg = request.getString("package"), activity = request.getString("activity");
            long deadline = request.getLong("deadline");
            if (userId < 0 || userId > 21474 || !pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
                || !activity.matches("[A-Za-z0-9_.$]+")) throw new IllegalArgumentException("INVALID_TARGET");
            ComponentName component = new ComponentName(pkg, activity);
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
            @SuppressWarnings("unchecked")
            List<ResolveInfo> entries = (List<ResolveInfo>) query.invoke(context.getPackageManager(), intent, 0, userId);
            boolean eligible = false;
            for (ResolveInfo entry : entries) {
                var info = entry.activityInfo;
                if (info != null && info.exported && info.enabled && info.applicationInfo.enabled
                    && pkg.equals(info.packageName) && activity.equals(info.name)) eligible = true;
            }
            if (!eligible) throw new IllegalArgumentException("INVALID_TARGET");
            long now = SystemClock.elapsedRealtime();
            if (cancelled || deadline <= now || deadline - now > 1_000) throw new IllegalStateException("EXPIRED_OR_CANCELLED");
            if (Boolean.TRUE.equals(locked.invoke(windowManager))) throw new IllegalStateException("DEVICE_LOCKED");
            if (cancelled || SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("EXPIRED_OR_CANCELLED");
            command = new ProcessBuilder("/system/bin/am", "start", "--user", String.valueOf(userId), "--display", "0",
                "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER, "-f", "0x10200000", "-n", component.flattenToString())
                .redirectErrorStream(true).start();
            if (cancelled) throw new IllegalStateException("CANCELLED");
            StringBuilder output = new StringBuilder();
            Thread drain = new Thread(() -> {
                try (var stream = command.getInputStream()) {
                    byte[] bytes = new byte[1024];
                    for (int count; (count = stream.read(bytes)) >= 0; ) {
                        synchronized (output) {
                            if (output.length() < 4096) output.append(new String(bytes, 0, Math.min(count, 4096 - output.length()), StandardCharsets.UTF_8));
                        }
                    }
                } catch (Exception ignored) { }
            }, "foreground-launch-output");
            drain.setDaemon(true);
            drain.start();
            long finishDeadline = SystemClock.elapsedRealtime() + 3_000;
            while (!command.waitFor(20, TimeUnit.MILLISECONDS)) {
                if (cancelled || SystemClock.elapsedRealtime() >= finishDeadline)
                    throw new IllegalStateException("CANCELLED_OR_TIMEOUT");
                if (Boolean.TRUE.equals(locked.invoke(windowManager))) throw new IllegalStateException("DEVICE_LOCKED");
            }
            drain.join(100);
            synchronized (output) {
                if (command.exitValue() != 0 || output.toString().contains("Error:")) throw new IllegalStateException("COMMAND_FAILED");
            }
            if (cancelled) throw new IllegalStateException("CANCELLED");
            reply(token, "submitted", "");
        } catch (Exception failure) {
            reply(token, "error", failure instanceof IllegalArgumentException || failure instanceof IllegalStateException
                ? failure.getMessage() : "HELPER_FAILED");
        } finally {
            cancelled = true;
            if (command != null && command.isAlive()) command.destroyForcibly();
        }
    }

    private static Context systemContext() throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> type = Class.forName("android.app.ActivityThread");
        Method main = type.getDeclaredMethod("systemMain");
        main.setAccessible(true);
        Object thread = main.invoke(null);
        Method context = type.getDeclaredMethod("getSystemContext");
        context.setAccessible(true);
        return (Context) context.invoke(thread);
    }

    private static void reply(String token, String status, String code) throws Exception {
        System.out.println(new JSONObject().put("token", token).put("status", status).put("code", code));
        System.out.flush();
    }
}
