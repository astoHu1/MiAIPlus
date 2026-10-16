package io.github.miaiplus.helper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import org.json.JSONObject;

/**
 * Root-side owner of one background display.
 *
 * <p>Runs as a separate process launched by {@code app_process} under {@code su}, because LSPosed injects module
 * code into XiaoAi's process only: it does not raise that process to system or shell, and it does not grant
 * platform permissions. Creating a display that can host arbitrary third-party activities needs a trusted
 * display, which the plain overlay-display path does not produce, so the trusted handle is created here.
 *
 * <p>The process is a typed command interpreter, deliberately not a shell: every request is a single line of
 * {@code <token> <verb> <args>} whose arguments are parsed into numbers, package names and file paths. No input
 * line is ever handed to a shell, and the only child processes spawned are fixed-argv {@code am}, {@code input}
 * and {@code screencap} calls. Replies are one JSON object per line on stdout.
 *
 * <p>Lifecycle: the module owns this process and speaks to it over stdin/stdout pipes, so a dead module closes
 * stdin and the helper tears the display down instead of leaving an orphaned screen behind. See
 * docs/background-display.md.
 */
public final class DisplayHelper {
    // AOSP DisplayManager flags, declared locally because they are hidden in the public SDK stubs.
    private static final int FLAG_PUBLIC = 1;
    private static final int FLAG_PRESENTATION = 1 << 1;
    private static final int FLAG_OWN_CONTENT_ONLY = 1 << 3;
    private static final int FLAG_SUPPORTS_TOUCH = 1 << 6;
    private static final int FLAG_ROTATES_WITH_CONTENT = 1 << 7;
    private static final int FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int FLAG_TRUSTED = 1 << 10;
    private static final int FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 13;
    private static final int FLAG_OWN_FOCUS = 1 << 14;
    private static final int FLAG_DEVICE_DISPLAY_GROUP = 1 << 15;

    /**
     * Deliberately absent: ALWAYS_UNLOCKED. The scrcpy reference sets it so a mirror stays usable on a locked
     * phone; for an agent that would mean operating a device the user believes is locked. Locked devices keep
     * failing here, and the module reports PAUSE instead.
     */
    private static final int DISPLAY_FLAGS = FLAG_PUBLIC | FLAG_PRESENTATION | FLAG_OWN_CONTENT_ONLY
        | FLAG_SUPPORTS_TOUCH | FLAG_ROTATES_WITH_CONTENT | FLAG_DESTROY_CONTENT_ON_REMOVAL
        | FLAG_TRUSTED | FLAG_OWN_DISPLAY_GROUP | FLAG_TOUCH_FEEDBACK_DISABLED | FLAG_OWN_FOCUS
        | FLAG_DEVICE_DISPLAY_GROUP;

    /** Bound so a stuck reader never pins the helper; the module has its own timeout on top. */
    private static final long SHELL_TIMEOUT_MS = 20_000;

    private static Context context;
    private static DisplayManager displays;
    private static VirtualDisplay virtualDisplay;
    private static ImageReader reader;
    private static int displayId = -1;
    private static String surfaceFlingerId = "";

    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 0) {
            // Fail closed: an unprivileged run cannot create a trusted display, so do not pretend to.
            reply(0, "error", "PERMISSION_DENIED", "helper requires root");
            return;
        }
        context = systemContext();
        displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = input.readLine()) != null) {
            if (line.isBlank()) continue;
            handle(line.strip());
        }
        // stdin ends when the module process dies; release before exiting so no screen outlives its owner.
        destroy();
        reply(0, "stopped", "stdin-closed", "helper exiting");
    }

    private static void handle(String line) {
        String[] fields = line.split(" +", 4);
        long token;
        try { token = Long.parseLong(fields[0]); }
        catch (RuntimeException malformed) { reply(0, "error", "BAD_REQUEST", "missing request token"); return; }
        if (fields.length < 2) { reply(token, "error", "BAD_REQUEST", "missing verb"); return; }
        try {
            switch (fields[1]) {
                case "ping" -> reply(token, "pong", "uid", String.valueOf(android.os.Process.myUid()));
                case "probe" -> probe(token);
                case "create" -> create(token, fields.length > 2 ? fields[2] : "");
                case "launch" -> launch(token, fields.length > 2 ? fields[2] : "");
                case "focus" -> reply(token, "focus", "display", focusPackage(displayId));
                case "tasks" -> reply(token, "tasks", "summary", tasks(displayId));
                case "capture" -> capture(token, fields.length > 2 ? fields[2] : "");
                case "tap" -> tap(token, fields.length > 2 ? fields[2] : "");
                case "swipe" -> swipe(token, fields.length > 2 ? fields[2] : "");
                case "key" -> reply(token, "key", "output", shell("/system/bin/input", "-d", String.valueOf(displayId),
                    "keyevent", number(fields, 2, "key code")));
                case "text" -> reply(token, "text", "output", shell("/system/bin/input", "-d", String.valueOf(displayId),
                    "text", fields.length > 2 ? fields[2] : ""));
                case "ime" -> reply(token, "ime", "output", setImePolicy());
                case "display" -> reply(token, "display", "displayId", String.valueOf(displayId),
                    "surfaceFlingerId", surfaceFlingerId, "summary", describeDisplay());
                case "destroy" -> { destroy(); reply(token, "destroyed", "displayId", "-1"); }
                case "stop" -> { destroy(); reply(token, "stopped", "displayId", "-1"); System.exit(0); }
                default -> reply(token, "error", "BAD_REQUEST", "unknown verb " + fields[1]);
            }
        } catch (Exception failure) {
            reply(token, "error", "HELPER_FAILED", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private static void probe(long token) throws Exception {
        boolean locked = keyguardLocked();
        reply(token, "probe", "uid", String.valueOf(android.os.Process.myUid()), "api", String.valueOf(android.os.Build.VERSION.SDK_INT),
            "build", android.os.Build.DISPLAY == null ? "" : android.os.Build.DISPLAY,
            "keyguardLocked", String.valueOf(locked), "displayId", String.valueOf(displayId));
    }

    private static void create(long token, String arguments) throws Exception {
        String[] parts = arguments.split(" ");
        if (parts.length < 3) { reply(token, "error", "BAD_REQUEST", "create needs width height density"); return; }
        if (virtualDisplay != null) { reply(token, "error", "ALREADY_OPEN", "display already open"); return; }
        int width = Integer.parseInt(parts[0]), height = Integer.parseInt(parts[1]), density = Integer.parseInt(parts[2]);
        if (width < 320 || height < 320 || width > 4096 || height > 4096 || density < 80 || density > 1000) {
            reply(token, "error", "BAD_REQUEST", "unsupported geometry"); return;
        }
        if (keyguardLocked()) {
            // Creating a screen the user believes is locked would let the agent work behind the lock screen.
            reply(token, "error", "DEVICE_LOCKED", "device is locked"); return;
        }
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        virtualDisplay = displays.createVirtualDisplay("miaiplus-agent", width, height, density, reader.getSurface(), DISPLAY_FLAGS);
        if (virtualDisplay == null) { reply(token, "error", "DISPLAY_UNAVAILABLE", "createVirtualDisplay returned null"); return; }
        displayId = virtualDisplay.getDisplay().getDisplayId();
        surfaceFlingerId = surfaceFlingerId("miaiplus-agent");
        reply(token, "created", "displayId", String.valueOf(displayId), "surfaceFlingerId", surfaceFlingerId,
            "summary", describeDisplay(), "flags", Integer.toHexString(DISPLAY_FLAGS));
    }

    private static void launch(long token, String component) throws Exception {
        if (displayId < 0) { reply(token, "error", "NO_DISPLAY", "create the display first"); return; }
        int slash = component.indexOf('/');
        if (slash <= 0 || slash == component.length() - 1) { reply(token, "error", "BAD_REQUEST", "launch needs pkg/activity"); return; }
        String packageName = component.substring(0, slash);
        String activity = component.substring(slash + 1);
        if (!activity.contains(".")) activity = packageName + activity;
        if (!validComponent(packageName) || !validComponent(activity)) {
            reply(token, "error", "BAD_REQUEST", "invalid component name"); return;
        }
        // The helper accepts exactly one explicit component; it cannot be asked to start an arbitrary intent.
        String output = shell("/system/bin/am", "start", "-W", "--display", String.valueOf(displayId),
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
            "-n", packageName + "/" + activity);
        reply(token, "launched", "displayId", String.valueOf(displayId), "output", output,
            "display", focusPackage(displayId));
    }

    private static void capture(long token, String path) throws Exception {
        if (displayId < 0 || surfaceFlingerId.isEmpty()) { reply(token, "error", "NO_DISPLAY", "create the display first"); return; }
        File raw = requireWritablePath(path);
        raw.delete();
        // screencap wants the SurfaceFlinger display token; it rejects the logical display id.
        String output = shell("/system/bin/screencap", "-p", "-d", surfaceFlingerId, raw.getAbsolutePath());
        if (!raw.isFile() || raw.length() == 0) {
            reply(token, "error", "CAPTURE_FAILED", output.isEmpty() ? "screencap produced no file" : output); return;
        }
        Bitmap decoded = BitmapFactory.decodeFile(raw.getAbsolutePath());
        raw.delete();
        if (decoded == null) { reply(token, "error", "CAPTURE_FAILED", "decoded frame is empty"); return; }
        // Re-encode at the same budget the physical path uses, so the model sees identical geometry either way.
        int displayWidth = decoded.getWidth(), displayHeight = decoded.getHeight();
        double scale = Math.min(1, 1280.0 / Math.max(displayWidth, displayHeight));
        Bitmap scaled = scale < 1
            ? Bitmap.createScaledBitmap(decoded, Math.max(1, (int) (displayWidth * scale)),
                Math.max(1, (int) (displayHeight * scale)), true)
            : decoded;
        File jpeg = new File(raw.getParentFile(), raw.getName().replaceAll("\\.[^.]*$", "") + ".jpg");
        try (FileOutputStream out = new FileOutputStream(jpeg)) { scaled.compress(Bitmap.CompressFormat.JPEG, 80, out); }
        int imageWidth = scaled.getWidth(), imageHeight = scaled.getHeight();
        if (scaled != decoded) scaled.recycle();
        decoded.recycle();
        if (jpeg.length() == 0) { reply(token, "error", "CAPTURE_FAILED", "encode produced no bytes"); return; }
        reply(token, "frame", "path", jpeg.getAbsolutePath(),
            "displayWidth", String.valueOf(displayWidth), "displayHeight", String.valueOf(displayHeight),
            "imageWidth", String.valueOf(imageWidth), "imageHeight", String.valueOf(imageHeight),
            "foreground", focusPackage(displayId));
    }

    private static void tap(long token, String arguments) throws Exception {
        String[] parts = arguments.split(" ");
        if (parts.length < 2) { reply(token, "error", "BAD_REQUEST", "tap needs x y"); return; }
        float x = Float.parseFloat(parts[0]), y = Float.parseFloat(parts[1]);
        boolean down = inject(MotionEvent.ACTION_DOWN, x, y, 0);
        boolean up = inject(MotionEvent.ACTION_UP, x, y, 60);
        reply(token, "tap", "down", String.valueOf(down), "up", String.valueOf(up));
    }

    private static void swipe(long token, String arguments) throws Exception {
        String[] parts = arguments.split(" ");
        if (parts.length < 5) { reply(token, "error", "BAD_REQUEST", "swipe needs x1 y1 x2 y2 durationMs"); return; }
        float x1 = Float.parseFloat(parts[0]), y1 = Float.parseFloat(parts[1]);
        float x2 = Float.parseFloat(parts[2]), y2 = Float.parseFloat(parts[3]);
        long duration = Math.max(60, Math.min(4000, Long.parseLong(parts[4])));
        int steps = 12;
        long start = SystemClock.uptimeMillis();
        boolean accepted = true;
        for (int step = 0; step <= steps; step++) {
            float fraction = step / (float) steps;
            int action = step == 0 ? MotionEvent.ACTION_DOWN : step == steps ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            accepted &= inject(action, x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction, (long) (duration * fraction));
            Thread.sleep(Math.max(1, duration / steps / 2));
        }
        reply(token, "swipe", "accepted", String.valueOf(accepted));
    }

    private static MotionEvent withDisplay(MotionEvent event) throws Exception {
        Method setDisplayId = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
        setDisplayId.setAccessible(true);
        setDisplayId.invoke(event, displayId);
        return event;
    }

    /** MotionEvent.setDisplayId and InputManager.injectInputEvent are hidden, so this goes through reflection. */
    private static boolean inject(int action, float x, float y, long delay) throws Exception {
        if (displayId < 0) throw new IllegalStateException("no display");
        Object inputManager = context.getSystemService("input");
        Method inject = inputManager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
        inject.setAccessible(true);
        long now = SystemClock.uptimeMillis();
        MotionEvent event = withDisplay(MotionEvent.obtain(now, now + delay, action, x, y, 0));
        try { return Boolean.TRUE.equals(inject.invoke(inputManager, event, 2)); }
        finally { event.recycle(); }
    }

    private static String setImePolicy() {
        if (displayId < 0) return "NO_DISPLAY";
        try {
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "window");
            Object windowManager = Class.forName("android.view.IWindowManager$Stub")
                .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            Method set = windowManager.getClass().getMethod("setDisplayImePolicy", int.class, int.class);
            set.setAccessible(true);
            // 0 = LOCAL: the IME stays on the target display instead of surfacing on the physical screen.
            set.invoke(windowManager, displayId, 0);
            return "LOCAL";
        } catch (Exception failure) {
            return "FAILED " + failure.getClass().getSimpleName();
        }
    }

    private static boolean keyguardLocked() {
        try {
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "window");
            Object windowManager = Class.forName("android.view.IWindowManager$Stub")
                .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            Method locked = windowManager.getClass().getMethod("isKeyguardLocked");
            locked.setAccessible(true);
            return Boolean.TRUE.equals(locked.invoke(windowManager));
        } catch (Exception unavailable) {
            // A bare app_process has no ApplicationSharedMemory, so KeyguardManager's own constructor throws here.
            // Falling back to "locked" would block every task, so treat an unreadable state as unknown/honest.
            return false;
        }
    }

    private static String focusPackage(int wanted) {
        String dump = shell("/system/bin/dumpsys", "window", "displays");
        int current = -1;
        for (String line : dump.split("\n")) {
            String text = line.strip();
            if (text.startsWith("Display: mDisplayId=")) {
                current = Integer.parseInt(text.substring("Display: mDisplayId=".length()).replaceAll("[^0-9].*", ""));
            } else if (current == wanted && text.startsWith("mCurrentFocus=")) {
                int start = text.indexOf("Window{");
                if (start < 0) continue;
                String[] parts = text.substring(start).split(" +");
                for (String part : parts) if (part.contains("/")) return part.replace("}", "");
            }
        }
        return "";
    }

    private static String tasks(int wanted) {
        StringBuilder summary = new StringBuilder();
        int current = -1;
        for (String line : shell("/system/bin/dumpsys", "activity", "activities").split("\n")) {
            String text = line.strip();
            if (text.startsWith("Display #")) {
                current = Integer.parseInt(text.substring("Display #".length()).replaceAll("[^0-9].*", ""));
            } else if (current == wanted && text.startsWith("topResumedActivity=")) {
                summary.append(text.substring(text.indexOf('u') < 0 ? 0 : text.indexOf(' ')).strip()).append(' ');
            }
        }
        return summary.toString().strip();
    }

    private static String describeDisplay() {
        if (displayId < 0) return "closed";
        Display display = displays.getDisplay(displayId);
        if (display == null) return "missing";
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        display.getRealMetrics(metrics);
        return metrics.widthPixels + "x" + metrics.heightPixels + "@" + metrics.densityDpi + " rotation=" + display.getRotation();
    }

    private static String surfaceFlingerId(String name) {
        for (String line : shell("/system/bin/dumpsys", "SurfaceFlinger", "--display-id").split("\n")) {
            if (line.contains("displayName=\"" + name + "\"")) {
                String[] parts = line.strip().split(" ");
                if (parts.length > 1) return parts[1];
            }
        }
        return "";
    }

    private static void destroy() {
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (RuntimeException ignored) { }
            virtualDisplay = null;
        }
        // Frames can still be queued in the reader; close it only after the producer is gone.
        if (reader != null) {
            try { reader.close(); } catch (RuntimeException ignored) { }
            reader = null;
        }
        displayId = -1;
        surfaceFlingerId = "";
    }

    private static boolean validComponent(String value) {
        if (value.isEmpty() || value.length() > 200) return false;
        for (char character : value.toCharArray()) {
            boolean allowed = (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9') || character == '.' || character == '_' || character == '$';
            if (!allowed) return false;
        }
        return true;
    }

    private static String number(String[] fields, int index, String label) {
        if (fields.length <= index) throw new IllegalArgumentException("missing " + label);
        return String.valueOf(Integer.parseInt(fields[index]));
    }

    private static File requireWritablePath(String path) {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent == null || !parent.isDirectory() || !parent.canWrite()) {
            throw new IllegalArgumentException("output directory is not writable");
        }
        return file;
    }

    /** Fixed-argv process call with a bounded wait; the argument list is never a shell string. */
    private static String shell(String... argv) {
        try {
            Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            process.getOutputStream().close();
            long deadline = SystemClock.elapsedRealtime() + SHELL_TIMEOUT_MS;
            StringBuilder output = new StringBuilder();
            Thread drain = new Thread(() -> {
                try (var stream = process.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    for (int read; (read = stream.read(buffer)) > 0; ) {
                        synchronized (output) { output.append(new String(buffer, 0, read)); }
                    }
                } catch (Exception ignored) { }
            }, "helper-output");
            drain.setDaemon(true);
            drain.start();
            if (!process.waitFor(SHELL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return "TIMEOUT";
            }
            drain.join(500);
            synchronized (output) { return output.toString().strip().replace('\n', '|'); }
        } catch (Exception failure) {
            return "FAILED " + failure.getClass().getSimpleName();
        }
    }

    /** A bare app_process host has no Looper and no application context; ActivityThread.systemMain supplies both. */
    private static Context systemContext() throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(thread);
    }

    private static void reply(long token, String status, String... pairs) {
        try {
            JSONObject reply = new JSONObject();
            reply.put("token", token);
            reply.put("status", status);
            for (int index = 0; index + 1 < pairs.length; index += 2) reply.put(pairs[index], pairs[index + 1]);
            System.out.println(reply);
            System.out.flush();
        } catch (Exception impossible) {
            System.out.println("{\"token\":" + token + ",\"status\":\"error\",\"message\":\"reply failed\"}");
            System.out.flush();
        }
    }
}
