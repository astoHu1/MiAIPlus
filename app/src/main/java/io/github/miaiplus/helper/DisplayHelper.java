package io.github.miaiplus.helper;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
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
    // AOSP DisplayManager flags, hidden from the public SDK stubs. Each is resolved from the running platform so
    // the bits are this ROM's own values instead of numbers copied from a source tree that may not match it; the
    // AOSP literal is only a fallback for a platform that does not define the name at all.
    private static final int FLAG_PUBLIC = romFlag("VIRTUAL_DISPLAY_FLAG_PUBLIC", 1);
    private static final int FLAG_PRESENTATION = romFlag("VIRTUAL_DISPLAY_FLAG_PRESENTATION", 1 << 1);
    private static final int FLAG_OWN_CONTENT_ONLY = romFlag("VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY", 1 << 3);
    private static final int FLAG_SUPPORTS_TOUCH = romFlag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH", 1 << 6);
    private static final int FLAG_ROTATES_WITH_CONTENT = romFlag("VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT", 1 << 7);
    private static final int FLAG_DESTROY_CONTENT_ON_REMOVAL =
        romFlag("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL", 1 << 8);
    private static final int FLAG_TRUSTED = romFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED", 1 << 10);
    private static final int FLAG_OWN_DISPLAY_GROUP = romFlag("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP", 1 << 11);
    private static final int FLAG_TOUCH_FEEDBACK_DISABLED =
        romFlag("VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED", 1 << 13);
    private static final int FLAG_DEVICE_DISPLAY_GROUP = romFlag("VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP", 1 << 15);

    /**
     * ALWAYS_UNLOCKED remains absent: creating a display still requires an unlocked device.
     * OWN_FOCUS and STEAL_TOP_FOCUS_DISABLED are required separately at runtime. Together they let targeted
     * input reach the background window without making it the top-focused display. IME client ownership is
     * global on supported Android versions, so LOCAL alone cannot preserve the user's foreground keyboard.
     */
    private static final int BASE_DISPLAY_FLAGS = FLAG_PUBLIC | FLAG_PRESENTATION | FLAG_OWN_CONTENT_ONLY
        | FLAG_SUPPORTS_TOUCH | FLAG_ROTATES_WITH_CONTENT | FLAG_DESTROY_CONTENT_ON_REMOVAL
        | FLAG_TRUSTED | FLAG_OWN_DISPLAY_GROUP | FLAG_TOUCH_FEEDBACK_DISABLED
        | FLAG_DEVICE_DISPLAY_GROUP;
    private static final int IME_POLICY_HIDE = 2;

    private static final class IsolationFailure extends Exception {
        final String code;
        IsolationFailure(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
    }

    /** No numeric fallback: Android 13 lacks these flags unless the ROM backports the feature. */
    static int[] requiredFocusFlags(Class<?> displayManagerClass, Class<?> displayClass) throws Exception {
        int own = requiredFlag(displayManagerClass, "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS");
        int noSteal = requiredFlag(displayManagerClass, "VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED");
        int effectiveOwn = requiredFlag(displayClass, "FLAG_OWN_FOCUS");
        int effectiveNoSteal = requiredFlag(displayClass, "FLAG_STEAL_TOP_FOCUS_DISABLED");
        if (own == noSteal || effectiveOwn == effectiveNoSteal)
            throw new IllegalStateException("focus flags overlap");
        return new int[] { own | noSteal, effectiveOwn | effectiveNoSteal };
    }

    private static int requiredFlag(Class<?> owner, String name) throws Exception {
        java.lang.reflect.Field field = owner.getField(name);
        field.setAccessible(true);
        int value = field.getInt(null);
        if (value <= 0 || (value & (value - 1)) != 0)
            throw new IllegalStateException("invalid platform flag " + name);
        return value;
    }

    static void verifyFocusFlags(int actual, int required) {
        if ((actual & required) != required)
            throw new IllegalStateException("platform ignored independent focus or no-steal flag");
    }

    /** Reads a hidden DisplayManager constant from the running platform, falling back to the AOSP value. */
    private static int romFlag(String name, int fallback) {
        try {
            java.lang.reflect.Field field = DisplayManager.class.getField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Exception absent) {
            return fallback;
        }
    }

    /** Every virtual-display flag this platform defines, so the probe records what the ROM actually declares. */
    private static JSONObject romFlags() {
        JSONObject flags = new JSONObject();
        for (java.lang.reflect.Field field : DisplayManager.class.getFields()) {
            if (!field.getName().startsWith("VIRTUAL_DISPLAY_FLAG_")) continue;
            try { flags.put(field.getName(), field.getInt(null)); } catch (Exception unreadable) { }
        }
        return flags;
    }

    /** Bound so a stuck reader never pins the helper; the module has its own timeout on top. */
    private static final long SHELL_TIMEOUT_MS = 20_000;
    private static Context context;
    private static DisplayManager displays;
    private static VirtualDisplay virtualDisplay;
    private static ImageReader reader;
    private static int displayId = -1;
    private static String surfaceFlingerId = "";

    /**
     * Frame consumer state.
     *
     * <p>An {@link ImageReader} hands out a fixed number of buffers. If nothing acquires them, the producer blocks
     * as soon as the queue is full, which freezes the display's own drawing. This helper created a reader from the
     * start but never read it, so the display could stall after its first few frames. The drain thread below
     * acquires and releases continuously to keep the queue moving, retaining the latest pending image until its
     * pixels are copied. A final redraw must survive the copy throttle even when the screen becomes static.
     */
    private static final Object FRAME_LOCK = new Object();
    /**
     * Minimum spacing between retained frames.
     *
     * <p>Retaining every frame would mean copying this display's full pixel buffer sixty times a second. The
     * consumer keeps at most one frame and refreshes it no faster than this, which bounds the copy cost while
     * keeping the retained frame close enough to the current screen that an observation taken after an action
     * still shows that action's effect.
     */
    private static final long FRAME_COPY_INTERVAL_MS = 200;
    private static volatile Thread frameDrain;
    private static volatile boolean drainRunning;
    /** Most recent retained frame; only touched under {@link #FRAME_LOCK}, which capture also copies under. */
    private static Bitmap frameLatest;
    private static long frameLatestAt;
    /** Newest acquired image, kept until copied or replaced; protected by {@link #FRAME_LOCK}. */
    private static Image framePending;
    private static volatile long frameCount;
    /** Set when the drain thread stops with an error, so capture can report the real reason instead of "empty". */
    private static volatile String frameError = "";

    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 0) {
            // Fail closed: an unprivileged run cannot create a trusted display, so do not pretend to.
            error(0, "PERMISSION_DENIED", "helper requires root");
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
        // Split into token / verb / remainder only: verbs like create and swipe take several space-separated
        // numbers, and a narrower split silently truncates them to the first argument.
        String[] fields = line.split(" +", 3);
        long token;
        try { token = Long.parseLong(fields[0]); }
        catch (RuntimeException malformed) { error(0, "BAD_REQUEST", "missing request token"); return; }
        if (fields.length < 2) { error(token, "BAD_REQUEST", "missing verb"); return; }
        try {
            switch (fields[1]) {
                case "ping" -> reply(token, "pong", "uid", String.valueOf(android.os.Process.myUid()));
                case "probe" -> probe(token);
                case "create" -> create(token, fields.length > 2 ? fields[2] : "");
                case "launch" -> launch(token, fields.length > 2 ? fields[2] : "");
                case "main_focus" -> mainFocus(token);
                case "task_state" -> taskState(token, fields.length > 2 ? fields[2] : "");
                case "capability" -> capability(token, fields.length > 2 ? fields[2] : "");
                case "focus" -> reply(token, "focus", "display", focusPackage(displayId));
                case "tasks" -> reply(token, "tasks", "summary", tasks(displayId));
                case "capture" -> capture(token, fields.length > 2 ? fields[2] : "");
                case "tap" -> tap(token, fields.length > 2 ? fields[2] : "");
                case "longpress" -> longpress(token, fields.length > 2 ? fields[2] : "");
                case "swipe" -> swipe(token, fields.length > 2 ? fields[2] : "");
                case "key" -> key(token, number(fields, 2, "key code"));
                // Text reaches the target display's own editable node from the module; this verb is only the
                // injection path probe, and a background app with no field on screen will ignore it.
                case "text" -> reply(token, "text", "output", shell("/system/bin/input", "-d", String.valueOf(displayId),
                    "text", fields.length > 2 ? fields[2] : ""));
                case "ime" -> setImePolicy(token);
                case "display" -> reply(token, "display", "displayId", String.valueOf(displayId),
                    "surfaceFlingerId", surfaceFlingerId, "summary", describeDisplay());
                case "destroy" -> { destroy(); reply(token, "destroyed", "displayId", "-1"); }
                case "stop" -> { destroy(); reply(token, "stopped", "displayId", "-1"); System.exit(0); }
                default -> error(token, "BAD_REQUEST", "unknown verb " + fields[1]);
            }
        } catch (IsolationFailure failure) {
            error(token, failure.code, failure.getMessage());
        } catch (Exception failure) {
            error(token, "HELPER_FAILED", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    /**
     * Reports a failure as {@code code} plus {@code message}.
     *
     * <p>The code is what the module switches on and the message is what it shows, so both travel as named
     * fields. An earlier shape used the code itself as the key, which the module could not read, so every
     * distinct failure surfaced as one generic error and a locked device never said to unlock it.
     */
    private static void error(long token, String code, String message) {
        try {
            JSONObject reply = new JSONObject();
            reply.put("token", token);
            reply.put("status", "error");
            reply.put("code", code);
            reply.put("message", message);
            System.out.println(reply);
            System.out.flush();
        } catch (Exception impossible) {
            System.out.println("{\"token\":" + token + ",\"status\":\"error\",\"code\":\"REPLY_FAILED\"}");
        }
    }

    private static void probe(long token) throws Exception {
        boolean locked = keyguardLocked();
        reply(token, "probe", "uid", String.valueOf(android.os.Process.myUid()), "api", String.valueOf(android.os.Build.VERSION.SDK_INT),
            "build", android.os.Build.DISPLAY == null ? "" : android.os.Build.DISPLAY,
            "keyguardLocked", String.valueOf(locked), "displayId", String.valueOf(displayId));
    }

    @SuppressLint("WrongConstant") // The trusted/own-focus/device-group bits this display needs are hidden from the SDK.
    private static void create(long token, String arguments) throws Exception {
        String[] parts = arguments.split(" ");
        if (parts.length < 3) { error(token, "BAD_REQUEST", "create needs width height density"); return; }
        if (virtualDisplay != null) { error(token, "ALREADY_OPEN", "display already open"); return; }
        int width = Integer.parseInt(parts[0]), height = Integer.parseInt(parts[1]), density = Integer.parseInt(parts[2]);
        if (width < 320 || height < 320 || width > 4096 || height > 4096 || density < 80 || density > 1000) {
            error(token, "BAD_REQUEST", "unsupported geometry"); return;
        }
        if (keyguardLocked()) {
            // Creating a screen the user believes is locked would let the agent work behind the lock screen.
            error(token, "DEVICE_LOCKED", "device is locked"); return;
        }
        int flags = createIsolatedDisplay("miaiplus-agent", width, height, density);
        reply(token, "created", "displayId", String.valueOf(displayId), "surfaceFlingerId", surfaceFlingerId,
            "summary", describeDisplay(), "flags", Integer.toHexString(flags), "imePolicy", "HIDE");
    }

    /** Shared by production and the capability probe; no activity can launch before isolation is verified. */
    @SuppressLint("WrongConstant")
    private static int createIsolatedDisplay(String name, int width, int height, int density) throws Exception {
        int[] focusFlags;
        try {
            focusFlags = requiredFocusFlags(DisplayManager.class, Display.class);
        } catch (Exception failure) {
            throw new IsolationFailure("FOCUS_ISOLATION_FAILED",
                "platform does not provide independent background focus without stealing top focus", failure);
        }
        if (keyguardLocked()) throw new IllegalStateException("device is locked");
        int flags = BASE_DISPLAY_FLAGS | focusFlags[0];
        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            virtualDisplay = displays.createVirtualDisplay(name, width, height, density, reader.getSurface(), flags);
            if (virtualDisplay == null) throw new IllegalStateException("createVirtualDisplay returned null");
            Display display = virtualDisplay.getDisplay();
            displayId = display.getDisplayId();
            if (displayId == Display.DEFAULT_DISPLAY) throw new IllegalStateException("returned the default display");
            try {
                verifyFocusFlags(display.getFlags(), focusFlags[1]);
            } catch (Exception failure) {
                throw new IsolationFailure("FOCUS_ISOLATION_FAILED",
                    "platform did not retain independent background focus and no-steal flags", failure);
            }
            try {
                configureImePolicy(windowManager());
            } catch (IsolationFailure failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IsolationFailure("IME_ISOLATION_FAILED", "cannot access display IME policy", failure);
            }
            startFrameConsumer(name);
            // Retained for diagnostics; capture reads this display's own ImageReader surface.
            surfaceFlingerId = surfaceFlingerId(name);
            return flags;
        } catch (Exception failure) {
            destroy();
            throw failure;
        }
    }

    /**
     * Development capability probe. Runs every step it can actually perform and reports one entry per item as
     * supported / unsupported / untested, with the evidence that produced the verdict.
     *
     * <p>Nothing is assumed: a step that cannot be measured here is reported {@code untested} with the reason and
     * where it must be measured instead. In particular the window tree belongs to the module's accessibility
     * service, so this process reports it untested rather than inferring it from a screenshot. The probe measures
     * the physical display's focus and top task around its own work, so a change there is real evidence of leakage
     * rather than a guess.
     *
     * <p>Output contains build facts, flags and window owners only; no page content, text or credentials.
     */
    @SuppressLint("WrongConstant") // Same hidden trusted/own-focus bits as the production create path.
    private static void capability(long token, String arguments) throws Exception {
        if (virtualDisplay != null) { error(token, "ALREADY_OPEN", "close the display before probing"); return; }
        String[] parts = arguments.split(" +", 4);
        if (parts.length < 3) { error(token, "BAD_REQUEST", "capability needs width height density [component]"); return; }
        int width = Integer.parseInt(parts[0]), height = Integer.parseInt(parts[1]), density = Integer.parseInt(parts[2]);
        if (width < 320 || height < 320 || width > 4096 || height > 4096 || density < 80 || density > 1000) {
            error(token, "BAD_REQUEST", "unsupported geometry"); return;
        }
        String component = parts.length == 4 ? parts[3] : "";
        JSONArray items = new JSONArray();
        // The physical display must settle before it can serve as a baseline. A phone the user is holding never
        // settles, and in that case the isolation items are reported untested rather than blamed on the probe.
        MainState baseline = stableMainState();
        JSONArray samples = new JSONArray();
        sample(samples, "baseline", baseline);
        int probeDisplayId = -1;
        boolean created = false, captured = false, launched = false;
        try {
            JSONObject create = new JSONObject();
            create.put("item", "createOwnDisplay");
            try {
                int flags = createIsolatedDisplay("miaiplus-probe", width, height, density);
                Display display = displays.getDisplay(displayId);
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                if (display != null) display.getRealMetrics(metrics);
                boolean distinct = displayId != Display.DEFAULT_DISPLAY;
                boolean geometry = metrics.widthPixels == width && metrics.heightPixels == height;
                created = distinct && geometry;
                // A frame count is deliberately not required here. A freshly created own-content display with
                // nothing launched into it composites nothing, so zero frames is the expected reading rather
                // than a defect; requiring one would fail a display that is working. What the display draws is
                // measured by the capture item below, once there is something to draw.
                create.put("status", created ? "supported" : "unsupported")
                    .put("displayId", displayId).put("surfaceFlingerId", surfaceFlingerId)
                    .put("frames", frameCount)
                    .put("geometry", metrics.widthPixels + "x" + metrics.heightPixels + "@" + metrics.densityDpi)
                    .put("flags", Integer.toHexString(flags)).put("imePolicy", "HIDE")
                    .put("reason", created ? "trusted own-content display accepted"
                        : !distinct ? "returned the default display" : "reported geometry differs from the request");
            } catch (Exception failure) {
                create.put("status", "unsupported").put("reason", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
            items.put(create);
            sample(samples, "afterCreate", settledMainState());
            if (!created) {
                items.put(untested("capture", "no display was created"));
                items.put(untested("launch", "no display was created"));
            } else {
                items.put(probeCapture(width, height));
                captured = items.optJSONObject(items.length() - 1).optString("status").equals("supported");
                sample(samples, "afterCapture", settledMainState());
                items.put(probeLaunch(component));
                launched = items.optJSONObject(items.length() - 1).optString("status").equals("supported");
                sample(samples, "afterLaunch", settledMainState());
            }
            items.put(untested("tree", "the window tree belongs to the module accessibility service; measure it there"));
            items.put(probePointer(captured));
            sample(samples, "afterPointer", settledMainState());
            items.put(probeKey(captured));
            sample(samples, "afterInput", settledMainState());
            // Kept for the teardown check below, because destroy() clears the live id.
            probeDisplayId = displayId;
        } finally {
            // Always tear down, even when a step threw, so a failed probe leaves no screen behind.
            destroy();
        }
        sample(samples, "afterTeardown", settledMainState());
        items.put(probeFocusIsolation(baseline, samples));

        JSONObject teardown = new JSONObject().put("item", "teardownIsolation");
        boolean gone = displayId < 0 && displays.getDisplay(probeDisplayId) == null;
        JSONObject last = samples.getJSONObject(samples.length() - 1);
        boolean restored = baseline != null
            && !baseline.windowFocus().isEmpty()
            && sampleField(last, "windowFocus").equals(baseline.windowFocus())
            && sampleField(last, "appFocus").equals(baseline.appFocus())
            && sampleField(last, "task").equals(baseline.task());
        if (!created) teardown.put("status", "untested").put("reason", "no display was created");
        else if (!gone) teardown.put("status", "unsupported").put("reason", "the display still exists after release");
        else if (baseline == null || baseline.windowFocus().isEmpty()) teardown.put("status", "untested")
            .put("reason", "the display is gone, but the physical display had no readable baseline for comparison");
        else if (restored)
            teardown.put("status", "supported")
                .put("reason", "the display is gone and the physical display sits where it started, so no task migrated");
        else teardown.put("status", "unsupported")
            .put("reason", "the physical display differs from the baseline after teardown; that is either a migration or the user's own activity");
        items.put(teardown);

        int supported = 0, tested = 0;
        for (int index = 0; index < items.length(); index++) {
            String status = items.getJSONObject(index).optString("status");
            if (status.equals("untested")) continue;
            tested++;
            if (status.equals("supported")) supported++;
        }
        reply(token, "capability", "items", items.toString(), "supported", String.valueOf(supported),
            "tested", String.valueOf(tested), "total", String.valueOf(items.length()),
            "launched", String.valueOf(launched),
            // Reported here as well as by the probe verb: a caller that only runs the capability probe still has
            // to see which identity produced it, otherwise it cannot tell the result from an unprivileged run.
            "uid", String.valueOf(android.os.Process.myUid()),
            // The flags this ROM actually defines, so the record shows what the platform declares rather than
            // only the bits this build happened to request.
            "romFlags", romFlags().toString(),
            "api", String.valueOf(android.os.Build.VERSION.SDK_INT),
            "fingerprint", android.os.Build.FINGERPRINT == null ? "" : android.os.Build.FINGERPRINT,
            "build", android.os.Build.DISPLAY == null ? "" : android.os.Build.DISPLAY);
    }

    private static JSONObject probeFocusIsolation(MainState baseline, JSONArray samples) throws Exception {
        // Each reading is attributed to the step it followed, so a change points at the action that caused it
        // instead of merely being noticed somewhere in the run.
        String appFocusChanged = "", windowFocusChanged = "", taskChanged = "";
        for (int index = 0; baseline != null && index < samples.length(); index++) {
            JSONObject entry = samples.getJSONObject(index);
            String step = entry.optString("step");
            if (step.equals("baseline") || step.equals("afterTeardown")) continue;
            if (!baseline.windowFocus().isEmpty() && windowFocusChanged.isEmpty()
                    && !sampleField(entry, "windowFocus").equals(baseline.windowFocus()))
                windowFocusChanged = step;
            if (appFocusChanged.isEmpty() && !sampleField(entry, "appFocus").equals(baseline.appFocus()))
                appFocusChanged = step;
            if (taskChanged.isEmpty() && !sampleField(entry, "task").equals(baseline.task())) taskChanged = step;
        }
        JSONObject focus = new JSONObject().put("item", "focusIsolation").put("samples", samples.toString());
        if (baseline == null) {
            focus.put("status", "untested")
                .put("reason", "the physical display never settled into a readable baseline; rerun while the phone is idle");
        } else if (!taskChanged.isEmpty()) {
            focus.put("status", "unsupported").put("taskChangedDuring", taskChanged)
                .put("reason", "the physical display's top task changed during " + taskChanged
                    + ", so work reached the user's screen or the user acted on the phone; rerun while it is idle");
        } else if (!appFocusChanged.isEmpty()) {
            focus.put("status", "unsupported").put("appFocusChangedDuring", appFocusChanged)
                .put("reason", "the physical display lost app focus during " + appFocusChanged
                    + ", so something else took over the user's screen");
        } else if (!windowFocusChanged.isEmpty()) {
            focus.put("status", "unsupported").put("windowFocusChangedDuring", windowFocusChanged)
                .put("reason", "the physical display's window focus changed during " + windowFocusChanged
                    + "; unchanged app focus and task cannot prove foreground keyboard isolation");
        } else if (baseline.windowFocus().isEmpty()) {
            focus.put("status", "untested")
                .put("reason", "the baseline has no focused physical window; keyboard focus isolation is untested");
        } else {
            focus.put("status", "supported")
                .put("reason", "the physical display kept its window focus, app focus and top task through every step; "
                    + "this probe does not test an active foreground keyboard");
        }
        return focus;
    }

    /** Physical window focus is required evidence even when app ownership and task order remain unchanged. */
    private record MainState(String windowFocus, String appFocus, String task) {
        boolean readable() { return !appFocus.isEmpty() || !task.isEmpty(); }
    }

    /** Reads one field of a recorded sample, which is stored unjoined so each can be compared on its own. */
    private static String sampleField(JSONObject sample, String field) {
        return sample.optString(field);
    }

    private static MainState mainState() {
        return new MainState(focusPackage(Display.DEFAULT_DISPLAY), focusedAppPackage(Display.DEFAULT_DISPLAY),
            tasks(Display.DEFAULT_DISPLAY));
    }

    /**
     * Reads the physical display until two consecutive dumps agree, so the baseline is a settled state.
     *
     * <p>Returns null when the phone never settles. That is the honest answer on a device someone is actively
     * using: without a stable starting point there is no way to attribute a later change to the probe, and
     * guessing would turn the user's own tap into a false pass or a false failure.
     */
    private static MainState stableMainState() {
        MainState previous = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            MainState current = mainState();
            if (current.readable() && current.equals(previous)) return current;
            previous = current;
            try { Thread.sleep(400); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
        }
        return null;
    }

    /**
     * Reads the physical display after letting a transition finish, for the per-step comparisons.
     *
     * <p>Two agreeing samples reduce transient dump noise. A stable missing or changed physical window focus
     * still fails isolation; an unchanged app/task cannot excuse losing the user's keyboard focus. If the phone
     * never settles, return the last reading so instability remains visible in the result.
     */
    private static MainState settledMainState() {
        MainState previous = null, latest = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            MainState current = mainState();
            if (current.readable()) latest = current;
            if (current.readable() && current.equals(previous)) return current;
            previous = current;
            try { Thread.sleep(300); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return latest; }
        }
        return latest;
    }

    private static void sample(JSONArray samples, String step, MainState state) throws Exception {
        samples.put(new JSONObject().put("step", step)
            .put("windowFocus", state == null ? "unreadable" : state.windowFocus())
            .put("appFocus", state == null ? "unreadable" : state.appFocus())
            .put("task", state == null ? "unreadable" : state.task()));
    }

    private static JSONObject untested(String item, String reason) throws Exception {
        return new JSONObject().put("item", item).put("status", "untested").put("reason", reason);
    }

    private static JSONObject probeCapture(int width, int height) throws Exception {
        JSONObject entry = new JSONObject().put("item", "capture");
        // Measured on the production path, not on a stand-in: whatever acquireFrame returns is what the agent sees.
        String[] source = { "imagereader" };
        Bitmap frame = acquireFrame(3000, source);
        if (frame == null) {
            return entry.put("status", "unsupported")
                .put("reason", frameError.isEmpty() ? "neither capture path produced a frame" : "capture failed: " + frameError);
        }
        int capturedWidth = frame.getWidth(), capturedHeight = frame.getHeight();
        frame.recycle();
        // A frame of the requested geometry proves the bytes came from this display, not the physical screen.
        boolean matches = capturedWidth == width && capturedHeight == height;
        return entry.put("status", matches ? "supported" : "unsupported")
            .put("source", source[0]).put("frames", frameCount)
            .put("capturedGeometry", capturedWidth + "x" + capturedHeight)
            .put("reason", matches ? "this display's own output produced a frame at its geometry"
                : "captured geometry differs from the created display");
    }

    private static JSONObject probeLaunch(String component) throws Exception {
        JSONObject entry = new JSONObject().put("item", "launch");
        if (component.isEmpty()) return entry.put("status", "untested")
            .put("reason", "no component was supplied; pass pkg/activity to measure this on device");
        int slash = component.indexOf('/');
        if (slash <= 0 || slash == component.length() - 1)
            return entry.put("status", "untested").put("reason", "the component must be pkg/activity");
        String packageName = component.substring(0, slash);
        String activity = component.substring(slash + 1);
        if (!activity.contains(".")) activity = packageName + activity;
        if (!validComponent(packageName) || !validComponent(activity))
            return entry.put("status", "untested").put("reason", "invalid component name");
        // An app already on the physical screen is the case this probe most needs to report honestly: starting
        // it would move the user's own task onto the background display instead of creating a second instance.
        int conflict = visibleTaskDisplay(packageName);
        if (conflict >= 0 && conflict != displayId) {
            return entry.put("status", "untested").put("conflictDisplay", conflict)
                .put("reason", "the package already has a visible task on another display; launching it could move that task");
        }
        String output = amStart(packageName, activity);
        // The start command returning is not proof; the window has to report that package on this display.
        String focused = awaitFocus(packageName);
        boolean onDisplay = focused.startsWith(packageName + "/");
        int landed = onDisplay ? displayId : visibleTaskDisplay(packageName);
        return entry.put("status", onDisplay ? "supported" : "unsupported")
            .put("foreground", focused).put("output", output)
            .put("landedDisplay", landed)
            .put("reason", onDisplay ? "the requested package holds focus on this display"
                : landed >= 0 && landed != displayId
                    ? "the package's task ended up on display " + landed + " instead of the background display"
                    : "the requested package never became the display's focused window");
    }

    /**
     * Proves that a pointer reaches the background display, rather than only that the event was accepted.
     *
     * <p>Acceptance by the input manager is not delivery: an event can be taken and then dropped. The check is a
     * frame comparison around a tap. A changed frame is proof the target app reacted. An unchanged frame is
     * reported as untested rather than as a failure, because a tap can land on an inert area and prove nothing
     * either way; claiming a failure there would be as unjustified as claiming success.
     */
    private static JSONObject probePointer(boolean captured) throws Exception {
        JSONObject entry = new JSONObject().put("item", "pointer");
        if (!captured) return entry.put("status", "untested").put("reason", "no captured frame to aim a pointer at");
        String before = frameDigest();
        if (before.isEmpty()) return entry.put("status", "untested").put("reason", "the display could not be captured for comparison");
        boolean[] tap = injectHold(10, 10, 60);
        if (!tap[0] || !tap[1]) return entry.put("status", "unsupported")
            .put("reason", "the input manager rejected the display-bound event");
        // Digits sit on the lower half of a keypad; these are inert on most layouts and safe to press.
        for (int[] point : new int[][] { { 270, 1500 }, { 810, 1500 }, { 270, 1750 } }) {
            injectHold(point[0], point[1], 60);
            Thread.sleep(400);
        }
        String after = frameDigest();
        boolean delivered = !after.isEmpty() && !after.equals(before);
        return entry.put("status", delivered ? "supported" : "untested")
            .put("frameBefore", before).put("frameAfter", after)
            .put("reason", delivered
                ? "the display's own frame changed after a display-bound tap, so the target app received it"
                : "taps were accepted but the frame did not change; the taps may have landed on inert areas, "
                    + "which proves neither delivery nor its absence");
    }

    private static JSONObject probeKey(boolean captured) throws Exception {
        JSONObject entry = new JSONObject().put("item", "text/key");
        if (!captured) return entry.put("status", "untested").put("reason", "no captured frame to send input to");
        String before = frameDigest();
        String key = shell("/system/bin/input", "-d", String.valueOf(displayId), "keyevent", "4");
        boolean accepted = !key.startsWith("FAILED") && !key.equals("TIMEOUT");
        if (!accepted) return entry.put("status", "unsupported")
            .put("keyOutput", key).put("reason", "display-directed key injection failed");
        String afterKey = frameDigest();
        boolean delivered = !before.isEmpty() && !afterKey.isEmpty() && !afterKey.equals(before);
        // Text goes through the display's own editable node in the module; here only the injection path is probed,
        // and a background app with no field on screen will simply ignore it.
        String text = shell("/system/bin/input", "-d", String.valueOf(displayId), "text", "miaiplus");
        return entry.put("status", delivered ? "supported" : "untested")
            .put("keyOutput", key).put("textOutput", text)
            .put("reason", delivered
                ? "the display's frame changed after a display-directed BACK, so the key reached it"
                : "the key was accepted but the frame did not change; the app may have nothing to go back to, "
                    + "which proves neither delivery nor its absence. Chinese input is not covered here.");
    }

    /**
     * Starts the frame consumer for the display that was just created.
     *
     * <p>Two jobs in one loop. It keeps the reader's buffer queue drained, without which the producer stalls and
     * the background display stops drawing; and it retains the most recent frame so a capture is a copy of a frame
     * the display already produced, rather than a separate request that has to travel out through
     * {@code screencap}, look the display up again by name and shell out for a file this process then re-decodes.
     */
    private static void startFrameConsumer(String tag) {
        stopFrameConsumer();
        ImageReader source = reader;
        if (source == null) return;
        frameCount = 0;
        frameError = "";
        Thread drain = new Thread(() -> {
            while (drainRunning && reader == source) {
                Image image = null;
                try {
                    image = source.acquireLatestImage();
                    boolean idle = image == null;
                    synchronized (FRAME_LOCK) {
                        if (!drainRunning || reader != source) return;
                        if (image != null) {
                            frameCount++;
                            if (framePending != null) framePending.close();
                            framePending = image;
                            image = null;
                        }
                        long now = SystemClock.uptimeMillis();
                        // Flush even on an idle poll: otherwise a redraw inside the throttle window could be
                        // discarded forever when it is the last frame the app draws.
                        if (frameLatest == null || now - frameLatestAt >= FRAME_COPY_INTERVAL_MS)
                            retainPendingFrame(now);
                    }
                    if (idle) Thread.sleep(8);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception failure) {
                    // Recorded so a later capture says why it had nothing, instead of reporting an empty frame.
                    frameError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                    return;
                } finally {
                    if (image != null) {
                        try { image.close(); } catch (RuntimeException ignored) { }
                    }
                }
            }
        }, tag + "-frames");
        drain.setDaemon(true);
        drainRunning = true;
        frameDrain = drain;
        drain.start();
    }

    /** Called with FRAME_LOCK held; at most one acquired image survives between drain iterations. */
    private static void retainPendingFrame(long now) {
        if (framePending == null) return;
        Image pending = framePending;
        framePending = null;
        try {
            Bitmap copy = copyImage(pending);
            if (copy == null) throw new IllegalStateException("latest image has no readable pixel plane");
            Bitmap previous = frameLatest;
            frameLatest = copy;
            frameLatestAt = now;
            if (previous != null) previous.recycle();
        } finally {
            pending.close();
        }
    }

    /**
     * Copies one image's pixels into a bitmap of the display's geometry.
     *
     * <p>The producer's rows are usually padded, so the buffer is wider than the visible frame. Copying the whole
     * buffer into a bitmap of the requested width skews every row progressively, which would look like a
     * screenshot with a diagonal smear and would misplace any coordinate read off it. The padding is included in
     * the intermediate bitmap and then cropped away.
     */
    private static Bitmap copyImage(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return null;
        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int width = image.getWidth(), height = image.getHeight();
        int rowPadding = rowStride - pixelStride * width;
        buffer.rewind();
        if (rowPadding == 0 && pixelStride == 4) {
            Bitmap exact = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            exact.copyPixelsFromBuffer(buffer);
            return exact;
        }
        int paddedWidth = width + rowPadding / Math.max(1, pixelStride);
        Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        padded.recycle();
        return cropped;
    }

    private static void stopFrameConsumer() {
        drainRunning = false;
        Thread drain = frameDrain;
        frameDrain = null;
        if (drain != null && drain != Thread.currentThread()) {
            try { drain.join(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        synchronized (FRAME_LOCK) {
            if (framePending != null) {
                framePending.close();
                framePending = null;
            }
            if (frameLatest != null) {
                frameLatest.recycle();
                frameLatest = null;
            }
            frameLatestAt = 0;
        }
    }

    /**
     * The retained frame, or null when none was produced.
     *
     * <p>Waits briefly for a first frame after creation: the display needs a moment to draw, and failing an
     * immediate capture would report a broken display that is merely still starting up.
     */
    private static Bitmap awaitFrameCopy(long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        do {
            synchronized (FRAME_LOCK) {
                // An observation may arrive inside the throttle window. Copy the latest acquired image now
                // instead of returning an older bitmap merely because 200ms has not elapsed yet.
                try { retainPendingFrame(SystemClock.uptimeMillis()); }
                catch (RuntimeException failure) {
                    frameError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                    return null;
                }
                if (frameError != null && !frameError.isEmpty()) return null;
                if (frameLatest != null) return frameLatest.copy(Bitmap.Config.ARGB_8888, false);
            }
            if (frameError != null && !frameError.isEmpty()) return null;
            try { Thread.sleep(20); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (SystemClock.uptimeMillis() < deadline);
        return null;
    }

    /**
     * Content digest of the background display's current frame, or empty when it cannot be read.
     *
     * <p>Goes through {@link #acquireFrame} so the probe's pointer and key checks measure the same pipeline
     * production uses, including whichever capture path that pipeline fell back to.
     */
    private static String frameDigest() {
        Bitmap frame = acquireFrame(1500, null);
        if (frame == null) return "";
        try {
            java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
            if (!frame.compress(Bitmap.CompressFormat.PNG, 100, encoded)) return "";
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            digest.update(encoded.toByteArray());
            StringBuilder text = new StringBuilder();
            for (byte value : digest.digest()) text.append(String.format("%02x", value));
            return text.toString();
        } catch (Exception unavailable) {
            return "";
        } finally {
            frame.recycle();
        }
    }

    private static void launch(long token, String component) throws Exception {
        if (displayId < 0) { error(token, "NO_DISPLAY", "create the display first"); return; }
        int slash = component.indexOf('/');
        if (slash <= 0 || slash == component.length() - 1) { error(token, "BAD_REQUEST", "launch needs pkg/activity"); return; }
        String packageName = component.substring(0, slash);
        String activity = component.substring(slash + 1);
        if (!activity.contains(".")) activity = packageName + activity;
        if (!validComponent(packageName) || !validComponent(activity)) {
            error(token, "BAD_REQUEST", "invalid component name"); return;
        }
        int conflict = visibleTaskDisplay(packageName);
        if (conflict >= 0 && conflict != displayId) {
            error(token, "TASK_IN_USE", "the package already has a visible task on display " + conflict);
            return;
        }
        String output = amStart(packageName, activity);
        String placement = awaitFocus(packageName);
        if (placement.isEmpty()) {
            int landed = visibleTaskDisplay(packageName);
            boolean moved = landed >= 0 && landed != displayId;
            error(token, moved ? "TASK_STOLEN" : "LAUNCH_FAILED", moved
                ? "the package's task is on display " + landed + " instead of the background display"
                : "the package never became visible on the background display");
            return;
        }
        reply(token, "launched", "displayId", String.valueOf(displayId), "output", output, "display", placement);
    }

    /** Starts exactly one explicit component; the helper cannot be asked to start an arbitrary intent. */
    private static String amStart(String packageName, String activity) {
        return shell("/system/bin/am", "start", "-W", "--display", String.valueOf(displayId),
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
            "-n", packageName + "/" + activity);
    }

    /** Waits for the package to hold focus on the background display, or returns empty if it never does. */
    private static String awaitFocus(String packageName) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String focused = focusPackage(displayId);
            if (focused.startsWith(packageName + "/")) return focused;
            try { Thread.sleep(250); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return ""; }
        }
        return "";
    }

    /**
     * The display holding a currently visible task of this package, or -1 when it has none.
     *
     * <p>This guards the failure that matters most here. Most apps declare {@code singleTask}, so asking the
     * system to start one on the background display is satisfied by <em>moving</em> the task that already exists
     * rather than by creating a second instance. The command then reports success while the app the user was
     * looking at disappears from their screen and they are dropped back to the launcher. Forcing a new task does
     * not help, because the reuse is decided by the activity's own launch mode; on the test ROM a launch with
     * MULTIPLE_TASK still relocated the existing task. The only safe answer is to refuse before starting.
     */
    private static int visibleTaskDisplay(String packageName) throws Exception {
        return visibleTaskDisplay(queryTasks(), packageName, displayId);
    }

    static int visibleTaskDisplay(List<TaskState> tasks, String packageName, int ownDisplay) {
        for (TaskState task : tasks) {
            if (task.visible && task.hasPackage(packageName) && task.display != ownDisplay) return task.display;
        }
        return -1;
    }

    /** A platform TaskInfo keeps component ownership separate from its unrelated task affinity. */
    record TaskState(int id, int display, boolean visible, List<String> packages) {
        boolean hasPackage(String packageName) { return packages.contains(packageName); }
    }

    @SuppressLint("BlockedPrivateApi") // Root app_process has no app targetSdk; this lookup never runs in the module/host process.
    private static Object activityTasks() throws Exception {
        Method get = Class.forName("android.app.ActivityTaskManager").getDeclaredMethod("getService");
        get.setAccessible(true);
        return get.invoke(null);
    }

    private static Object taskField(Object task, String name) throws Exception {
        java.lang.reflect.Field field = task.getClass().getField(name);
        field.setAccessible(true);
        return field.get(task);
    }

    /** Permission, reflection, partial and malformed query failures are failures, never "no conflict". */
    private static List<TaskState> queryTasks() throws Exception {
        return queryTasks(activityTasks());
    }

    static List<TaskState> queryTasks(Object service) throws Exception {
        Method get = null;
        for (Method method : service.getClass().getMethods()) {
            if (!method.getName().equals("getTasks")) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length >= 3 && types.length <= 4 && types[0] == int.class
                    && types[1] == boolean.class && types[2] == boolean.class
                    && (types.length == 3 || types[3] == int.class)) { get = method; break; }
        }
        if (get == null) throw new IllegalStateException("task query API unavailable");
        get.setAccessible(true);
        Object raw = get.getParameterCount() == 4
            ? get.invoke(service, 1000, false, false, -1) : get.invoke(service, 1000, false, false);
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() >= 1000)
            throw new IllegalStateException("task query incomplete");
        List<TaskState> tasks = new ArrayList<>();
        for (Object info : list) {
            int id = (Integer) taskField(info, "taskId"), display = (Integer) taskField(info, "displayId");
            boolean visible = (Boolean) taskField(info, "isVisible");
            if (id < 0 || display < 0) throw new IllegalStateException("task identity unavailable");
            List<String> packages = new ArrayList<>();
            for (String name : new String[] { "realActivity", "baseActivity", "topActivity" }) {
                Object component = taskField(info, name);
                if (component instanceof ComponentName owner && !packages.contains(owner.getPackageName()))
                    packages.add(owner.getPackageName());
            }
            Object intent = taskField(info, "baseIntent");
            if (intent instanceof android.content.Intent base && base.getComponent() != null
                    && !packages.contains(base.getComponent().getPackageName()))
                packages.add(base.getComponent().getPackageName());
            if (visible && packages.isEmpty()) throw new IllegalStateException("visible task ownership unavailable");
            tasks.add(new TaskState(id, display, visible, packages));
        }
        return tasks;
    }

    private static void taskState(long token, String packageName) throws Exception {
        if (!validComponent(packageName)) { error(token, "BAD_REQUEST", "invalid package name"); return; }
        List<TaskState> tasks = queryTasks();
        int conflict = -1, taskId = -1;
        for (TaskState task : tasks) {
            if (task.visible && task.display != displayId && task.hasPackage(packageName)) {
                conflict = task.display; taskId = task.id; break;
            }
        }
        reply(token, "task_state", "package", packageName, "conflictDisplay", String.valueOf(conflict),
            "taskId", String.valueOf(taskId),
            // Runtime locking is allowed for an existing background task. With the keyguard showing there is
            // no main app to request control of; this does not bypass the real-task conflict query above.
            "mainPackage", keyguardLocked() ? "" : stableMainComponent().getPackageName());
    }

    private static void mainFocus(long token) throws Exception {
        ComponentName component = stableMainComponent();
        reply(token, "main_focus", "package", component.getPackageName(), "component", component.flattenToShortString());
    }

    private static ComponentName stableMainComponent() throws Exception {
        String previous = "";
        for (int attempt = 0; attempt < 6; attempt++) {
            String focused = focusedAppPackage(Display.DEFAULT_DISPLAY);
            if (focused.isEmpty()) focused = focusPackage(Display.DEFAULT_DISPLAY);
            ComponentName component = ComponentName.unflattenFromString(focused);
            if (component != null && focused.equals(previous)) {
                for (TaskState task : queryTasks()) {
                    if (task.display == Display.DEFAULT_DISPLAY && task.visible && task.hasPackage(component.getPackageName())) {
                        return component;
                    }
                }
            }
            previous = focused;
            Thread.sleep(100);
        }
        throw new IllegalStateException("physical display focus is unreadable or changing");
    }

    /**
     * Captures the background display into a JPEG.
     *
     * <p>The frame comes from the display's own output, which this process already owns, instead of from
     * {@code screencap -d <SurfaceFlinger token>}. That indirection needed the display to be looked up by name in
     * a system dump and identified by a token that the ROM registers asynchronously, and it re-decoded a file that
     * this process had effectively just produced. Reading the reader's retained frame removes the shell round
     * trip, the token lookup and the dependency on that lookup succeeding at all.
     *
     * <p>The {@code screencap} path is kept as a fallback and reported in {@code source}, because the two paths
     * are not equivalent — {@code screencap} applies the display's own secure-window policy, so the fallback tells
     * us whether a frame difference came from the capture path or from the display itself. Both paths target
     * <em>this</em> display by its own identity; neither can reach the physical screen. When both fail the error
     * says so rather than the capture succeeding against the wrong display.
     */
    private static void capture(long token, String path) throws Exception {
        if (displayId < 0) { error(token, "NO_DISPLAY", "create the display first"); return; }
        File raw = requireWritablePath(path);
        raw.delete();
        String[] source = { "imagereader" };
        Bitmap frame = acquireFrame(2500, source);
        if (frame == null) {
            error(token, "CAPTURE_FAILED", frameError.isEmpty()
                ? "neither the display's own output nor screencap produced a frame"
                : "the display's frame reader failed: " + frameError);
            return;
        }
        // Re-encode at the same budget the physical path uses, so the model sees identical geometry either way.
        int displayWidth = frame.getWidth(), displayHeight = frame.getHeight();
        double scale = Math.min(1, 1280.0 / Math.max(displayWidth, displayHeight));
        Bitmap scaled = scale < 1
            ? Bitmap.createScaledBitmap(frame, Math.max(1, (int) (displayWidth * scale)),
                Math.max(1, (int) (displayHeight * scale)), true)
            : frame;
        File jpeg = new File(raw.getParentFile(), raw.getName().replaceAll("\\.[^.]*$", "") + ".jpg");
        try (FileOutputStream out = new FileOutputStream(jpeg)) { scaled.compress(Bitmap.CompressFormat.JPEG, 80, out); }
        int imageWidth = scaled.getWidth(), imageHeight = scaled.getHeight();
        if (scaled != frame) scaled.recycle();
        frame.recycle();
        if (jpeg.length() == 0) { error(token, "CAPTURE_FAILED", "encode produced no bytes"); return; }
        reply(token, "frame", "path", jpeg.getAbsolutePath(), "source", source[0],
            "frames", String.valueOf(frameCount),
            "displayWidth", String.valueOf(displayWidth), "displayHeight", String.valueOf(displayHeight),
            "imageWidth", String.valueOf(imageWidth), "imageHeight", String.valueOf(imageHeight),
            "foreground", focusPackage(displayId));
    }

    /**
     * One frame of this display, preferring its own output over a shell capture.
     *
     * <p>The retained reader frame is the primary path: the display's pixels already arrive in this process, so
     * taking them directly avoids spawning {@code screencap}, waiting for it, and decoding a file this process
     * effectively just produced — and it removes the dependency on the ROM registering a SurfaceFlinger token for
     * the display at all.
     *
     * <p>An own-content display with nothing launched into it composites no frames, so a reader miss is a normal
     * state rather than a fault. {@code screencap} still returns that empty screen, which is why it remains as a
     * fallback: it answers "what is on this display" even when the answer is nothing. {@code source} is set to
     * whichever path produced the frame, because the two are not equivalent — {@code screencap} applies the
     * display's secure-window policy, so a difference between them is worth being able to see.
     *
     * <p>Both paths address <em>this</em> display by its own identity. When neither produces a frame this returns
     * null; it never substitutes the physical display, which would hand the agent the user's own screen.
     */
    private static Bitmap acquireFrame(long timeoutMs, String[] source) {
        Bitmap frame = awaitFrameCopy(timeoutMs);
        if (frame != null) {
            if (source != null) source[0] = "imagereader";
            return frame;
        }
        if (surfaceFlingerId.isEmpty()) return null;
        File raw;
        try {
            raw = File.createTempFile("miaiplus-frame", ".png");
        } catch (Exception unavailable) {
            frameError = "temp file unavailable: " + unavailable.getMessage();
            return null;
        }
        try {
            Bitmap captured = captureByScreencap(raw);
            if (captured != null && source != null) source[0] = "screencap";
            return captured;
        } finally {
            raw.delete();
        }
    }

    /**
     * Fallback capture through {@code screencap}, addressed by this display's SurfaceFlinger token.
     *
     * <p>Returns null when it produced nothing usable. The token is this display's own; an empty token means the
     * ROM never published one, and returning null is the honest answer there rather than substituting the
     * physical display's token, which would hand the agent a screenshot of the user's screen.
     */
    private static Bitmap captureByScreencap(File raw) {
        String output = shell("/system/bin/screencap", "-p", "-d", surfaceFlingerId, raw.getAbsolutePath());
        if (!raw.isFile() || raw.length() == 0) {
            if (!output.isEmpty() && !output.equals("TIMEOUT")) frameError = output;
            return null;
        }
        Bitmap decoded = BitmapFactory.decodeFile(raw.getAbsolutePath());
        raw.delete();
        return decoded;
    }

    /**
     * Sends one key to the background display.
     *
     * <p>Prefers the in-process injection path and falls back to the {@code input} command, reporting which one
     * answered in {@code source}. The fallback is kept because the two do not necessarily agree on a given ROM,
     * and a key that the direct path refuses is still worth trying the other way — but it is reported rather than
     * substituted silently, so a test can tell which path a result came from.
     */
    private static void key(long token, String code) throws Exception {
        if (displayId < 0) { error(token, "NO_DISPLAY", "create the display first"); return; }
        int keyCode = Integer.parseInt(code);
        try {
            if (injectKey(keyCode)) {
                reply(token, "key", "source", "inputmanager", "delivered", "true");
                return;
            }
        } catch (Exception rejected) {
            error(token, "INPUT_REJECTED", "display-bound key injection failed: " + rejected.getClass().getSimpleName());
            return;
        }
        // The input CLI returning empty output does not prove injection. Do not turn a definite rejection
        // into an ordinary successful response through that unverified fallback.
        error(token, "INPUT_REJECTED", "input manager rejected the display-bound key");
    }

    private static void tap(long token, String arguments) throws Exception {
        String[] parts = arguments.split(" ");
        if (parts.length < 2) { error(token, "BAD_REQUEST", "tap needs x y"); return; }
        float x = Float.parseFloat(parts[0]), y = Float.parseFloat(parts[1]);
        boolean[] result = injectHold(x, y, 60);
        reply(token, "tap", "down", String.valueOf(result[0]), "up", String.valueOf(result[1]));
    }

    /**
     * Presses and holds at one point, which is a distinct gesture rather than a slow tap.
     *
     * <p>A long press is recognised by the duration the pointer stays down, not by the size of the gap between
     * the down and up events. Sending the pair with a delay field set but no real wait between them is delivered
     * as an ordinary tap, so a "long press" written that way silently becomes a tap on any menu that opens on
     * release — the agent asks to hold, the item gets clicked instead. The hold here waits until the requested
     * uptime deadline; both events share a downTime and carry their actual injection time.
     */
    private static void longpress(long token, String arguments) throws Exception {
        String[] parts = arguments.split(" ");
        if (parts.length < 2) { error(token, "BAD_REQUEST", "longpress needs x y [durationMs]"); return; }
        float x = Float.parseFloat(parts[0]), y = Float.parseFloat(parts[1]);
        long duration = parts.length > 2 ? Long.parseLong(parts[2]) : 800;
        duration = Math.max(300, Math.min(5000, duration));
        long started = SystemClock.uptimeMillis();
        boolean[] result = injectHold(x, y, duration);
        reply(token, "longpress", "down", String.valueOf(result[0]), "up", String.valueOf(result[1]),
            "heldMs", String.valueOf(result[0] ? SystemClock.uptimeMillis() - started : 0));
    }

    private static boolean[] injectHold(float x, float y, long duration) throws Exception {
        resolveInput();
        long downTime = SystemClock.uptimeMillis();
        boolean down = inject(MotionEvent.ACTION_DOWN, x, y, downTime);
        if (!down) return new boolean[] { false, false };
        try {
            waitUntil(downTime + duration);
            boolean up = inject(MotionEvent.ACTION_UP, x, y, downTime);
            if (!up) inject(MotionEvent.ACTION_CANCEL, x, y, downTime);
            return new boolean[] { true, up };
        } catch (Exception failure) {
            try { inject(MotionEvent.ACTION_CANCEL, x, y, downTime); }
            catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw failure;
        }
    }

    /** Schedules against one gesture origin, so injection latency does not accumulate into per-step sleeps. */
    private static void waitUntil(long targetUptime) throws InterruptedException {
        long remaining;
        while ((remaining = targetUptime - SystemClock.uptimeMillis()) > 0) Thread.sleep(remaining);
    }

    private static void swipe(long token, String arguments) throws Exception {
        String[] parts = arguments.split(" ");
        if (parts.length < 5) { error(token, "BAD_REQUEST", "swipe needs x1 y1 x2 y2 durationMs"); return; }
        float x1 = Float.parseFloat(parts[0]), y1 = Float.parseFloat(parts[1]);
        float x2 = Float.parseFloat(parts[2]), y2 = Float.parseFloat(parts[3]);
        long duration = Math.max(60, Math.min(4000, Long.parseLong(parts[4])));
        int steps = 12;
        resolveInput();
        long downTime = SystemClock.uptimeMillis();
        boolean accepted = true;
        float x = x1, y = y1;
        boolean down = false;
        try {
            for (int step = 0; step <= steps; step++) {
                waitUntil(downTime + duration * step / steps);
                float fraction = step / (float) steps;
                x = x1 + (x2 - x1) * fraction;
                y = y1 + (y2 - y1) * fraction;
                int action = step == 0 ? MotionEvent.ACTION_DOWN
                    : step == steps ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
                boolean delivered = inject(action, x, y, downTime);
                accepted &= delivered;
                if (action == MotionEvent.ACTION_UP && !delivered)
                    inject(MotionEvent.ACTION_CANCEL, x, y, downTime);
                if (step == 0) {
                    down = delivered;
                    if (!down) break;
                }
            }
        } catch (Exception failure) {
            if (down) {
                try { inject(MotionEvent.ACTION_CANCEL, x, y, downTime); }
                catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            }
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw failure;
        }
        reply(token, "swipe", "accepted", String.valueOf(accepted));
    }

    /**
     * Cached handles for the hidden input APIs.
     *
     * <p>Resolved once instead of per event: a swipe is a dozen-odd injected events, and each one re-resolving
     * three methods by name through reflection costs more than the injection itself. The handles are only ever
     * set when all three lookups succeed, so a platform that lacks one is detected once and reported, rather than
     * failing partway through a gesture with some events already delivered.
     */
    private static volatile boolean inputResolved;
    private static Object inputService;
    private static Method injectInputEvent;
    private static Method motionSetDisplayId;
    private static Method keySetDisplayId;
    private static String inputFailure = "";

    // BlockedPrivateApi: runs under app_process, which carries no app targetSdk, so the hidden-API blocklist does
    // not apply. WrongConstant: "input" is the real service name; Context.INPUT_SERVICE resolves to the same string.
    @SuppressLint({"BlockedPrivateApi", "WrongConstant"})
    private static synchronized void resolveInput() {
        if (inputResolved) return;
        inputResolved = true;
        try {
            Object service = context.getSystemService("input");
            if (service == null) { inputFailure = "the input service is unavailable"; return; }
            Method inject = service.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
            inject.setAccessible(true);
            Method motion = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
            Method key = KeyEvent.class.getDeclaredMethod("setDisplayId", int.class);
            motion.setAccessible(true);
            key.setAccessible(true);
            inputService = service;
            injectInputEvent = inject;
            motionSetDisplayId = motion;
            keySetDisplayId = key;
        } catch (Exception failure) {
            inputFailure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
    }

    /**
     * Sends one motion event to this display.
     *
     * <p>The display id is attached to the event itself, which is what makes this safe: the event is routed to the
     * background display by identity rather than to whatever the user's screen currently has focused.
     * {@code SOURCE_TOUCHSCREEN} is set because apps and the input pipeline treat an event with no source as an
     * unknown device and some reject it; a source-less event is not the same thing as a synthetic touch.
     */
    @SuppressLint("WrongConstant") // The "input" service name and the hidden per-event display id are both non-SDK here.
    private static boolean inject(int action, float x, float y, long downTime) throws Exception {
        if (displayId < 0) throw new IllegalStateException("no display");
        resolveInput();
        if (injectInputEvent == null) throw new IllegalStateException("input injection unavailable: " + inputFailure);
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(downTime, now, action, x, y, 0);
        try {
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            motionSetDisplayId.invoke(event, displayId);
            return Boolean.TRUE.equals(injectInputEvent.invoke(inputService, event, 2));
        }
        finally { event.recycle(); }
    }

    /**
     * Sends one key to this display, so BACK and HOME act on the background screen rather than the user's.
     *
     * <p>Done through the input manager instead of the {@code input} command the helper previously spawned. The
     * shell route re-linked a process per key press and reported only that the command exited; this reports
     * whether the system actually accepted the event, and it keeps a key press on the same path as touch, so a
     * display that can receive one can receive the other.
     */
    @SuppressLint("WrongConstant") // Same hidden injection entry point as the touch path.
    private static boolean injectKey(int keyCode) throws Exception {
        if (displayId < 0) throw new IllegalStateException("no display");
        resolveInput();
        if (injectInputEvent == null) throw new IllegalStateException("key injection unavailable: " + inputFailure);
        long now = SystemClock.uptimeMillis();
        boolean delivered = true;
        for (int action : new int[] { KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP }) {
            KeyEvent event = new KeyEvent(now, now, action, keyCode, 0);
            event.setSource(InputDevice.SOURCE_KEYBOARD);
            keySetDisplayId.invoke(event, displayId);
            delivered &= Boolean.TRUE.equals(injectInputEvent.invoke(inputService, event, 2));
        }
        return delivered;
    }

    private static Object windowManager() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "window");
        return Class.forName("android.view.IWindowManager$Stub")
            .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
    }

    private static void setImePolicy(long token) {
        if (displayId < 0) { error(token, "NO_DISPLAY", "create the display first"); return; }
        try {
            setImePolicy(token, windowManager());
        } catch (Exception failure) {
            error(token, "IME_ISOLATION_FAILED", "cannot verify hidden background IME policy: " + failure.getClass().getSimpleName());
        }
    }

    /** HIDE is defense in depth; the no-steal display flag prevents global foreground IME client changes. */
    static void configureImePolicy(Object windowManager) throws IsolationFailure {
        try {
            Method get = windowManager.getClass().getMethod("getDisplayImePolicy", int.class);
            get.setAccessible(true);
            // Rechecking a ready session must not reconfigure the display or disturb an active foreground IME.
            if (Integer.valueOf(IME_POLICY_HIDE).equals(get.invoke(windowManager, displayId))) return;
            Method set = windowManager.getClass().getMethod("setDisplayImePolicy", int.class, int.class);
            set.setAccessible(true);
            set.invoke(windowManager, displayId, IME_POLICY_HIDE);
            if (!Integer.valueOf(IME_POLICY_HIDE).equals(get.invoke(windowManager, displayId)))
                throw new IllegalStateException("HIDE policy did not persist");
        } catch (Exception failure) {
            throw new IsolationFailure("IME_ISOLATION_FAILED",
                "cannot verify hidden background IME policy: " + failure.getClass().getSimpleName(), failure);
        }
    }

    static void setImePolicy(long token, Object windowManager) {
        try {
            configureImePolicy(windowManager);
            reply(token, "ime", "policy", "HIDE", "verified", "true");
        } catch (IsolationFailure failure) {
            error(token, failure.code, failure.getMessage());
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

    private static final String DISPLAY_HEADER = "Display: mDisplayId=";
    private static final String ACTIVITY_HEADER = "Display #";
    private static final String FOCUS_MARKER = "mCurrentFocus=";

    /** Handles one fragment of a dump, already attributed to the display it belongs to. Returns true to stop. */
    private interface DisplayFragment {
        boolean accept(int display, String fragment);
    }

    /**
     * Walks a per-display dump, attributing every fragment to the display it describes.
     *
     * <p>These dumps do not guarantee that a display header starts a line; the newline is often omitted, so a
     * header turns up at the end of the previous section's last line, as in {@code mImeWindow=null  Display:
     * mDisplayId=0 (organized)}. Requiring the header to begin the line drops that display entirely and
     * attributes its content to whichever display was parsed last, which reads an empty virtual display as
     * focused while the user's own screen holds the window. Cutting each line at every header keeps both halves
     * with the display they actually describe.
     */
    private static void eachDisplayFragment(String dump, String header, DisplayFragment sink) {
        int current = -1;
        for (String line : dump.split("\n")) {
            String text = line.strip();
            int at = text.indexOf(header);
            if (at < 0) {
                if (sink.accept(current, text)) return;
                continue;
            }
            if (sink.accept(current, text.substring(0, at))) return;
            String rest = text.substring(at + header.length());
            current = leadingNumber(rest);
            if (sink.accept(current, rest)) return;
        }
    }

    /** Leading integer of a fragment, or -1 when it does not begin with one. */
    private static int leadingNumber(String text) {
        int index = 0;
        while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
        if (index == 0) return -1;
        try { return Integer.parseInt(text.substring(0, index)); } catch (NumberFormatException malformed) { return -1; }
    }

    /**
     * The focused window owner on one display, or empty when nothing there holds focus.
     *
     * <p>Attributing this to the wrong display makes an empty background display look focused and hides what the
     * user's screen is really showing, so the answer is only taken from a fragment already known to belong to
     * the requested display.
     */
    private static String focusPackage(int wanted) {
        String[] found = { "" };
        eachDisplayFragment(shellRaw("/system/bin/dumpsys", "window", "displays"), DISPLAY_HEADER,
            (display, fragment) -> {
                if (display != wanted) return false;
                int marker = fragment.indexOf(FOCUS_MARKER);
                if (marker < 0) return false;
                String tail = fragment.substring(marker + FOCUS_MARKER.length());
                int start = tail.indexOf("Window{");
                if (start < 0) return false;
                int end = tail.indexOf('}', start);
                for (String part : (end < 0 ? tail.substring(start) : tail.substring(start, end)).split(" +")) {
                    if (!part.contains("/")) continue;
                    found[0] = part;
                    return true;
                }
                return false;
            });
        return found[0];
    }

    /**
     * The focused app on one display, or empty when none.
     *
     * <p>This is the reading that decides whether the user's screen is still the active surface. Android updates
     * {@code mFocusedApp} for the display that owns app focus even when the window-level focus is momentarily
     * empty, so it is unaffected by the null window focus that a second display produces on this ROM.
     */
    private static String focusedAppPackage(int wanted) {
        String[] found = { "" };
        eachDisplayFragment(shellRaw("/system/bin/dumpsys", "window", "displays"), DISPLAY_HEADER,
            (display, fragment) -> {
                if (display != wanted) return false;
                int marker = fragment.indexOf("mFocusedApp=");
                if (marker < 0) return false;
                for (String part : fragment.substring(marker).split(" +")) {
                    if (!part.contains("/")) continue;
                    found[0] = part.replace("}", "");
                    return true;
                }
                return false;
            });
        return found[0];
    }

    /** The resumed activity on one display, or empty when that display has no resumed task. */
    private static String tasks(int wanted) {
        StringBuilder summary = new StringBuilder();
        eachDisplayFragment(shellRaw("/system/bin/dumpsys", "activity", "activities"), ACTIVITY_HEADER,
            (display, fragment) -> {
                if (display == wanted && fragment.startsWith("topResumedActivity="))
                    summary.append(fragment).append(' ');
                return false;
            });
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

    /**
     * The SurfaceFlinger token for the virtual display, or empty when this ROM never publishes one.
     *
     * <p>SurfaceFlinger registers a new virtual display asynchronously, so an immediate lookup after creation
     * usually misses it and a retry is required. Returning empty is honest: the caller must not fall back to the
     * physical token, because that would capture the user's own screen while the agent believes it is reading the
     * background display.
     */
    private static String surfaceFlingerId(String name) {
        for (int attempt = 0; attempt < 20; attempt++) {
            for (String line : shellRaw("/system/bin/dumpsys", "SurfaceFlinger", "--display-id").split("\n")) {
                if (!line.contains("displayName=\"" + name + "\"")) continue;
                String[] parts = line.strip().split(" ");
                if (parts.length > 1) return parts[1];
            }
            try { Thread.sleep(250); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        return "";
    }

    private static void destroy() {
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (RuntimeException ignored) { }
            virtualDisplay = null;
        }
        // The consumer must stop before the reader closes, and both must wait for the producer to be released
        // above: a thread blocked in acquireLatestImage on a closed reader throws instead of noticing the display
        // is gone, and leaving it running would keep a reference to this display's buffers after teardown.
        stopFrameConsumer();
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
        // Single-line reply: callers that only report or match short fragments use this form.
        return shellRaw(argv).replace('\n', '|');
    }

    /**
     * Raw variant that keeps line breaks.
     *
     * <p>Anything parsing a multi-line dump must use this. Collapsing newlines first fuses the whole dump into one
     * line, after which a per-line scan matches the first line that mentions the wanted token and reads its
     * columns from the wrong entry. For display lookup that means silently returning the physical screen's
     * identifier, so the failure mode is a capture of the user's own screen rather than of the background one.
     */
    private static String shellRaw(String... argv) {
        try {
            Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            process.getOutputStream().close();
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
            synchronized (output) { return output.toString().strip(); }
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
