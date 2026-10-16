package io.github.miaiplus;

import android.content.Context;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * Module-side owner of the background display session.
 *
 * <p>Spawns {@code io.github.miaiplus.helper.DisplayHelper} under {@code su} and speaks one JSON request per line
 * to it. All display work happens in that root process; this class never creates a display itself, never touches
 * the physical screen, and never silently falls back to the default display. If the helper dies mid-task the
 * session reports {@code BACKEND_DIED} and the run stops rather than retargeting the user's screen.
 *
 * <p>One session exists at a time, matching the module's existing single-active-run rule. {@link #close()} is
 * idempotent and releases the display, the reader, the pipes and the process exactly once.
 */
public final class BackgroundDisplay implements AutoCloseable {
    public static final String ERROR_BACKEND_DIED = "BACKEND_DIED";
    public static final String ERROR_DISPLAY_LOST = "DISPLAY_LOST";
    public static final String ERROR_PERMISSION = "PERMISSION_DENIED";
    public static final String ERROR_DEVICE_LOCKED = "DEVICE_LOCKED";

    private static final int DEFAULT_WIDTH = 1080, DEFAULT_HEIGHT = 1920, DEFAULT_DENSITY = 320;
    private static final long COMMAND_TIMEOUT_MS = 25_000;
    private static final Object LOCK = new Object();
    private static volatile BackgroundDisplay active;

    private final Process helper;
    private final BufferedWriter toHelper;
    private final BufferedReader fromHelper;
    private final File frameDirectory;
    private volatile DisplaySession session;
    private volatile boolean closed;

    private BackgroundDisplay(Process helper, File frameDirectory) {
        this.helper = helper;
        this.frameDirectory = frameDirectory;
        this.toHelper = new BufferedWriter(new OutputStreamWriter(helper.getOutputStream(), StandardCharsets.UTF_8));
        this.fromHelper = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8));
    }

    public DisplaySession session() { return session; }
    public static BackgroundDisplay active() { return active; }
    public boolean isOpen() { return !closed && helper.isAlive() && session != null; }

    /**
     * Starts the helper and opens the display. Fails closed with a coded error; the caller never receives a
     * session that could later act on the wrong screen.
     */
    public static BackgroundDisplay open(Context context, RunState run) throws Exception {
        synchronized (LOCK) {
            if (active != null && active.isOpen()) return active;
            if (active != null) active.close();
            Context app = context.getApplicationContext();
            File frames = new File(app.getCacheDir(), "background-display");
            if (!frames.isDirectory() && !frames.mkdirs()) throw new IOException("无法创建后台截图缓存目录");
            BackgroundDisplay display = new BackgroundDisplay(startHelper(app), frames);
            active = display;
            try {
                JSONObject probe = display.request(run, "probe", null);
                if (!"0".equals(probe.optString("uid")))
                    throw new IllegalStateException(ERROR_PERMISSION + "：后台显示需要 root 授权，请在 root 管理器中允许 MiAIPlus");
                if ("true".equals(probe.optString("keyguardLocked")))
                    throw new IllegalStateException(ERROR_DEVICE_LOCKED + "：请先解锁手机再使用后台显示");
                JSONObject created = display.request(run, "create",
                    DEFAULT_WIDTH + " " + DEFAULT_HEIGHT + " " + DEFAULT_DENSITY);
                display.session = new DisplaySession(created.getInt("displayId"), created.optString("surfaceFlingerId"),
                    DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_DENSITY, 0);
                // Keep the IME off the physical screen; the agent types through accessibility, not the keyboard.
                display.request(run, "ime", null);
                run.progress = "后台显示已就绪";
                return display;
            } catch (Exception failure) {
                display.close();
                throw failure;
            }
        }
    }

    /**
     * Launches under {@code su} from the module's own uid. XiaoAi's process cannot reach {@code su} on the target
     * ROM, so the helper must be started from here; the module uid is the one the root manager allowlists.
     */
    private static Process startHelper(Context context) throws Exception {
        String classpath = context.getPackageCodePath();
        if (classpath == null || !new File(classpath).isFile())
            throw new IllegalStateException(ERROR_BACKEND_DIED + "：无法定位模块代码路径");
        // The classpath is the installed APK path from the package manager; no user or model text reaches this command.
        Process process = new ProcessBuilder("su", "-c",
            "CLASSPATH='" + classpath + "' /system/bin/app_process /system/bin io.github.miaiplus.helper.DisplayHelper")
            .redirectErrorStream(false).start();
        process.getOutputStream().close();
        drain(process.getErrorStream(), "background-display-stderr");
        return process;
    }

    private static void drain(java.io.InputStream stream, String name) {
        Thread thread = new Thread(() -> {
            try (stream) {
                byte[] buffer = new byte[4096];
                // Discard helper diagnostics; failures arrive on the protocol line instead.
                while (stream.read(buffer) > 0) { }
            } catch (IOException ignored) { }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    /** Sends one command and waits for its reply. A dead helper or a mismatched token becomes BACKEND_DIED. */
    private JSONObject request(RunState run, String verb, String arguments) throws Exception {
        if (closed) throw new IllegalStateException(ERROR_BACKEND_DIED + "：后台显示已关闭");
        if (!helper.isAlive()) throw new IllegalStateException(ERROR_BACKEND_DIED + "：后台显示进程已退出");
        long token = System.nanoTime();
        toHelper.write(token + " " + verb + (arguments == null || arguments.isEmpty() ? "" : " " + arguments));
        toHelper.newLine();
        toHelper.flush();
        long deadline = android.os.SystemClock.elapsedRealtime() + COMMAND_TIMEOUT_MS;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (run != null) run.check();
            if (fromHelper.ready()) {
                String reply = fromHelper.readLine();
                if (reply == null) throw new IllegalStateException(ERROR_BACKEND_DIED + "：后台显示进程已退出");
                JSONObject parsed = new JSONObject(reply);
                if (parsed.optLong("token") != token) continue; // Late reply for a command that already timed out.
                if ("error".equals(parsed.optString("status"))) throw failure(parsed.optString("message"));
                return parsed;
            }
            if (!helper.isAlive()) throw new IllegalStateException(ERROR_BACKEND_DIED + "：后台显示进程已退出");
            Thread.sleep(20);
        }
        throw new IllegalStateException(ERROR_BACKEND_DIED + "：后台显示无响应");
    }

    private static IllegalStateException failure(String message) {
        String text = message == null ? "" : message;
        if (text.contains(ERROR_PERMISSION)) return new IllegalStateException(ERROR_PERMISSION + "：后台显示需要 root 授权");
        if (text.contains(ERROR_DEVICE_LOCKED)) return new IllegalStateException(ERROR_DEVICE_LOCKED + "：请先解锁手机再使用后台显示");
        if (text.contains("NO_DISPLAY")) return new IllegalStateException(ERROR_DISPLAY_LOST + "：后台显示已失效，任务已停止");
        return new IllegalStateException(text.isEmpty() ? ERROR_BACKEND_DIED + "：后台显示异常" : text);
    }

    /** Confirms the display still belongs to this session before any action reuses its coordinates. */
    public void verify(RunState run) throws Exception {
        JSONObject state = request(run, "display", null);
        int current = Integer.parseInt(state.optString("displayId", "-1"));
        if (!session.matches(current, session.generation))
            throw new IllegalStateException(ERROR_DISPLAY_LOST + "：后台显示已关闭或已被替换");
    }

    /**
     * Captures the background display and returns it with that display's own geometry. The tree comes from the
     * accessibility service on the same display id; when the ROM refuses to expose a virtual display's windows the
     * tree is empty, which the caller reports as a vision-only observation instead of borrowing the physical tree.
     */
    public ScreenService.Frame observe(RunState run) throws Exception {
        File raw = new File(frameDirectory, "frame-" + session.generation.substring(0, 8) + "-" + System.nanoTime() + ".png");
        File jpeg = null;
        try {
            JSONObject reply = request(run, "capture", raw.getAbsolutePath());
            jpeg = new File(reply.getString("path"));
            byte[] bytes = java.nio.file.Files.readAllBytes(jpeg.toPath());
            if (bytes.length == 0) throw new IllegalStateException(ERROR_DISPLAY_LOST + "：后台截图内容为空");
            String foreground = reply.optString("foreground");
            String tree = ScreenService.treeOnDisplay(session.displayId, foreground);
            return ScreenService.Frame.background(
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), tree,
                reply.optInt("displayWidth", session.width), reply.optInt("displayHeight", session.height),
                reply.optInt("imageWidth", session.width), reply.optInt("imageHeight", session.height),
                foreground, session.displayId, session.generation);
        } finally {
            // Exactly one captured pair per observation; leaving them would grow the cache without bound.
            raw.delete();
            if (jpeg != null) jpeg.delete();
        }
    }

    /** The package the helper reports as focused on the background display; empty when nothing is focused. */
    public String foregroundPackage(RunState run) throws Exception {
        return request(run, "focus", null).optString("display");
    }

    public void launch(RunState run, String component) throws Exception {
        request(run, "launch", component);
    }

    public void tap(RunState run, double normalizedX, double normalizedY) throws Exception {
        request(run, "tap", pixel(normalizedX, session.width) + " " + pixel(normalizedY, session.height));
    }

    public void swipe(RunState run, double[] from, double[] to) throws Exception {
        request(run, "swipe", pixel(from[0], session.width) + " " + pixel(from[1], session.height) + " "
            + pixel(to[0], session.width) + " " + pixel(to[1], session.height) + " 450");
    }

    public void key(RunState run, int keyCode) throws Exception {
        request(run, "key", String.valueOf(keyCode));
    }

    private static int pixel(double normalized, int size) {
        return (int) Math.max(0, Math.min(size - 1, normalized * size / 1000));
    }

    @Override public void close() {
        synchronized (LOCK) {
            if (closed) return;
            closed = true;
            if (active == this) active = null;
            try { request(null, "destroy", null); }
            catch (Exception ignored) { /* The process may already be gone; teardown below still runs. */ }
            try { toHelper.close(); } catch (IOException ignored) { }
            try { fromHelper.close(); } catch (IOException ignored) { }
            helper.destroy();
            try {
                if (!helper.waitFor(3, TimeUnit.SECONDS)) helper.destroyForcibly();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                helper.destroyForcibly();
            } finally {
                session = null;
            }
            clearCache();
        }
    }

    /** Frames left by a crashed helper would otherwise keep the last background screenshot on disk after teardown. */
    private void clearCache() {
        File[] files = frameDirectory.listFiles();
        if (files == null) return;
        for (File file : files) file.delete();
    }

    public static void closeActive() {
        BackgroundDisplay display = active;
        if (display != null) display.close();
    }
}
