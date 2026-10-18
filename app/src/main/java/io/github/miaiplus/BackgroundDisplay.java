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
    public static final String ERROR_TASK_IN_USE = "TASK_IN_USE";
    public static final String ERROR_CAPTURE_FAILED = "CAPTURE_FAILED";
    public static final String ERROR_INPUT_FAILED = "INPUT_FAILED";
    public static final String ERROR_IME_ISOLATION = "IME_ISOLATION_FAILED";
    public static final String ERROR_FOCUS_ISOLATION = "FOCUS_ISOLATION_FAILED";

    private static final long COMMAND_TIMEOUT_MS = 25_000;
    private static final Object LOCK = new Object();
    private static volatile BackgroundDisplay active;

    private final Process helper;
    private final BufferedWriter toHelper;
    private final BufferedReader fromHelper;
    private final File frameDirectory;
    private final Object requestLock = new Object();
    private volatile DisplaySession session;
    private volatile boolean closed;

    /**
     * Runs the helper's development capability probe and returns its report.
     *
     * <p>Deliberately does not become the active session: the probe creates and destroys its own display, so it
     * must not be mistaken for a display the caller can act on, and it must not block a real task afterwards. The
     * display is torn down inside the helper even when a step fails.
     */
    public static JSONObject capability(Context context, String component, RunState run) throws Exception {
        Config config = new Config(context);
        BackgroundDisplay display = start(context.getApplicationContext());
        try {
            display.request(run, "probe", null);
            return display.request(run, "capability", config.displayWidth + " " + config.displayHeight + " "
                + config.displayDensity + (component == null || component.isEmpty() ? "" : " " + component));
        } finally {
            display.close();
        }
    }

    /**
     * Lightweight availability check: starts the helper and asks who it is, without creating a display.
     *
     * <p>Separate from {@link #capability} because that one creates and destroys a real screen. A status refresh
     * runs on a timer, so it must report the same facts without briefly giving the user a second display.
     */
    public static JSONObject availability(Context context, RunState run) throws Exception {
        BackgroundDisplay display = start(context.getApplicationContext());
        try {
            return display.request(run, "probe", null);
        } finally {
            display.close();
        }
    }

    private BackgroundDisplay(Process helper, File frameDirectory) {
        this.helper = helper;
        this.frameDirectory = frameDirectory;
        this.toHelper = new BufferedWriter(new OutputStreamWriter(helper.getOutputStream(), StandardCharsets.UTF_8));
        this.fromHelper = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8));
    }

    private static File createFrameDirectory(Context context) throws IOException {
        File parent = new File(context.getCacheDir(), "background-display");
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())
            throw new IOException("无法创建后台截图缓存目录");
        // Availability checks and capability probes own helpers too. Their teardown must never delete frames
        // belonging to a real session, even when they run while that session is reading a capture.
        return java.nio.file.Files.createTempDirectory(parent.toPath(), "session-").toFile();
    }

    private static BackgroundDisplay start(Context context) throws Exception {
        File frames = createFrameDirectory(context);
        try {
            return new BackgroundDisplay(startHelper(context), frames);
        } catch (Exception failure) {
            frames.delete();
            throw failure;
        }
    }

    public DisplaySession session() { return session; }
    public static BackgroundDisplay active() { return active; }
    public boolean isOpen() { return !closed && helper.isAlive() && session != null; }

    /**
     * Starts the helper and opens the display. Fails closed with a coded error; the caller never receives a
     * session that could later act on the wrong screen.
     */
    public static BackgroundDisplay open(Context context, RunState run, Config config) throws Exception {
        synchronized (LOCK) {
            if (active != null && active.isOpen()) return active;
            if (active != null) active.close();
            Context app = context.getApplicationContext();
            BackgroundDisplay display = start(app);
            active = display;
            try {
                JSONObject probe = display.request(run, "probe", null);
                if (!"0".equals(probe.optString("uid")))
                    throw new IllegalStateException(ERROR_PERMISSION + "：后台显示需要 root 授权，请在 root 管理器中允许 MiAIPlus");
                if ("true".equals(probe.optString("keyguardLocked")))
                    throw new IllegalStateException(ERROR_DEVICE_LOCKED + "：请先解锁手机再使用后台显示");
                JSONObject created = display.request(run, "create",
                    config.displayWidth + " " + config.displayHeight + " " + config.displayDensity);
                DisplaySession createdSession = new DisplaySession(created.getInt("displayId"), created.optString("surfaceFlingerId"),
                    config.displayWidth, config.displayHeight, config.displayDensity, 0);
                // Creation already verified no-steal focus and HIDE; recheck without reapplying a ready policy.
                JSONObject ime = display.request(run, "ime", null);
                if (!"HIDE".equals(ime.optString("policy")) || !"true".equals(ime.optString("verified")))
                    throw new IllegalStateException(ERROR_IME_ISOLATION + "：无法确认后台屏输入法隔离，任务已停止");
                display.session = createdSession;
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
        // The child's stdin is this protocol's command channel, so it must stay open: closing it here makes the
        // helper see end-of-input and exit before it answers anything, which surfaces as an IOException on the
        // first command. It closes on its own when this process dies, which is what tears the display down.
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
        long started = android.os.SystemClock.elapsedRealtime();
        PhoneUseLog.event("helper_request", "verb", verb, "displayId", session == null ? -1 : session.displayId);
        synchronized (requestLock) {
            try {
                JSONObject result = requestLocked(run, verb, arguments);
                PhoneUseLog.event("helper_reply", "verb", verb, "status", result.optString("status"),
                    "source", result.optString("source"), "frames", result.optString("frames"),
                    "foreground", result.optString("foreground"), "focus", verb.equals("focus") ? result.optString("display") : "",
                    "durationMs", android.os.SystemClock.elapsedRealtime() - started);
                return result;
            } catch (Exception failure) { PhoneUseLog.failure("helper_" + verb, failure); throw failure; }
        }
    }

    private JSONObject requestLocked(RunState run, String verb, String arguments) throws Exception {
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
                if ("error".equals(parsed.optString("status"))) {
                    PhoneUseLog.event("helper_error", "verb", verb, "code", parsed.optString("code"));
                    throw failure(parsed.optString("code"), parsed.optString("message"));
                }
                return parsed;
            }
            if (!helper.isAlive()) throw new IllegalStateException(ERROR_BACKEND_DIED + "：后台显示进程已退出");
            Thread.sleep(20);
        }
        throw new IllegalStateException(ERROR_BACKEND_DIED + "：后台显示无响应");
    }

    /**
     * Turns a helper failure into the exception the run reports, keyed on the code rather than on prose.
     *
     * <p>The codes that are part of this client's own contract are translated here; anything else keeps the
     * helper's own message, because a specific reason is more useful than a generic one. The two task codes are
     * the important cases: the helper refuses a launch that would move a task the user already has on screen, and
     * that must reach the user as a clear refusal instead of a retry that quietly takes their screen.
     */
    private static IllegalStateException failure(String code, String message) {
        String text = message == null ? "" : message;
        String reason = code == null ? "" : code;
        if (reason.equals(ERROR_PERMISSION)) return new IllegalStateException(ERROR_PERMISSION + "：后台显示需要 root 授权");
        if (reason.equals(ERROR_DEVICE_LOCKED)) return new IllegalStateException(ERROR_DEVICE_LOCKED + "：请先解锁手机再使用后台显示");
        if (reason.equals("NO_DISPLAY") || reason.equals("DISPLAY_UNAVAILABLE"))
            return new IllegalStateException(ERROR_DISPLAY_LOST + "：后台显示已失效，任务已停止");
        if (reason.equals("TASK_IN_USE")) return new IllegalStateException(ERROR_TASK_IN_USE
            + "：该应用已在你的屏幕上打开，后台执行会把它移走，请先关闭该应用后重试");
        if (reason.equals("TASK_STOLEN")) return new IllegalStateException(ERROR_TASK_IN_USE
            + "：启动未落在后台屏，任务已停止");
        if (reason.equals("CAPTURE_FAILED")) return new IllegalStateException(ERROR_CAPTURE_FAILED
            + "：后台截图失败，任务已停止");
        return new IllegalStateException(text.isEmpty() ? ERROR_BACKEND_DIED + "：后台显示异常" : reason + "：" + text);
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
        PhoneUseLog.observation();
        long started = android.os.SystemClock.elapsedRealtime();
        File raw = new File(frameDirectory, "frame-" + session.generation.substring(0, 8) + "-" + System.nanoTime() + ".png");
        File jpeg = null;
        try {
            JSONObject reply = request(run, "capture", raw.getAbsolutePath());
            jpeg = new File(reply.getString("path"));
            byte[] bytes = java.nio.file.Files.readAllBytes(jpeg.toPath());
            if (bytes.length == 0) throw new IllegalStateException(ERROR_DISPLAY_LOST + "：后台截图内容为空");
            String foreground = reply.optString("foreground");
            String tree = ScreenService.treeOnDisplay(session.displayId, foreground);
            ScreenService.Frame frame = ScreenService.Frame.background(
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), tree,
                reply.optInt("displayWidth", session.width), reply.optInt("displayHeight", session.height),
                reply.optInt("imageWidth", session.width), reply.optInt("imageHeight", session.height),
                foreground, session.displayId, session.generation);
            PhoneUseLog.frame(frame);
            PhoneUseLog.event("capture_complete", "displayId", session.displayId, "source", reply.optString("source"),
                "frames", reply.optString("frames"), "durationMs", android.os.SystemClock.elapsedRealtime() - started);
            return frame;
        } catch (Exception failure) {
            PhoneUseLog.failure("capture_background", failure); throw failure;
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

    /** The current application on the physical screen, used only to request an explicit mode handoff. */
    public String mainForeground(RunState run) throws Exception {
        return request(run, "main_focus", null).optString("package");
    }

    /** Read-only task ownership evidence used before requesting a foreground handoff. */
    public JSONObject taskState(RunState run, String packageName) throws Exception {
        return request(run, "task_state", packageName);
    }

    public boolean launch(RunState run, String component) throws Exception {
        JSONObject reply = request(run, "launch", component);
        String status = reply.optString("status");
        if (status.equals("launched")) return true;
        if (status.equals("awaiting_confirmation")) return false;
        throw new IllegalStateException("后台启动返回了无法识别的状态，未确认应用已打开");
    }

    public void tap(RunState run, double normalizedX, double normalizedY) throws Exception {
        JSONObject reply = request(run, "tap", pixel(normalizedX, session.width) + " " + pixel(normalizedY, session.height));
        requireInputAccepted(reply, "点击", "down", "up");
    }

    /**
     * Holds the background display at one point.
     *
     * <p>Separate from {@link #tap} because the two are different gestures, not different durations: a long press
     * has to keep the pointer down for real, which the helper does by sleeping between the down and up events. It
     * returns whether the release was delivered, so a hold that was accepted but never released is not reported
     * as a completed long press.
     */
    public void longPress(RunState run, double normalizedX, double normalizedY) throws Exception {
        JSONObject reply = request(run, "longpress",
            pixel(normalizedX, session.width) + " " + pixel(normalizedY, session.height));
        requireInputAccepted(reply, "长按", "down", "up");
    }

    public void swipe(RunState run, double[] from, double[] to) throws Exception {
        JSONObject reply = request(run, "swipe", pixel(from[0], session.width) + " " + pixel(from[1], session.height) + " "
            + pixel(to[0], session.width) + " " + pixel(to[1], session.height) + " 450");
        requireInputAccepted(reply, "滑动", "accepted");
    }

    public void key(RunState run, int keyCode) throws Exception {
        requireInputAccepted(request(run, "key", String.valueOf(keyCode)), "按键", "delivered");
    }

    private static void requireInputAccepted(JSONObject reply, String action, String... fields) {
        for (String field : fields) {
            if (!"true".equals(reply.optString(field)))
                throw new IllegalStateException(ERROR_INPUT_FAILED + "：后台屏" + action + "未确认送达，任务已停止");
        }
    }

    private static int pixel(double normalized, int size) {
        return (int) Math.max(0, Math.min(size - 1, normalized * size / 1000));
    }

    @Override public void close() {
        synchronized (LOCK) {
            if (closed) return;
            PhoneUseLog.event("display_close", "displayId", session == null ? -1 : session.displayId);
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
        if (files != null) for (File file : files) file.delete();
        frameDirectory.delete();
    }

    public static void closeActive() {
        BackgroundDisplay display = active;
        if (display != null) display.close();
    }
}
