package io.github.miaiplus.helper;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.FrameFixtureImage;
import android.media.Image;
import android.media.ImageReader;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowImageReader;
import org.robolectric.shadows.ShadowMotionEvent;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
    DisplayHelperFramesAndGesturesTest.ReaderShadow.class,
    DisplayHelperFramesAndGesturesTest.UptimeShadow.class,
    DisplayHelperFramesAndGesturesTest.DisplayRoutingMotionShadow.class
})
public class DisplayHelperFramesAndGesturesTest {
    private static final Method REAL_NANO_TIME = realNanoTimeMethod();
    private static final long CLOCK_ORIGIN = realNanoTime();
    private final InputSink sink = new InputSink();
    private int noFocusChangeFlag;
    private ImageReader reader;

    @Before public void setUp() throws Exception {
        ReaderShadow.clear();
        set("displayId", 19);
        set("inputResolved", true);
        set("inputService", sink);
        set("injectInputEvent", InputSink.class.getMethod("injectInputEvent", InputEvent.class, int.class));
        Method display = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
        display.setAccessible(true);
        set("motionSetDisplayId", display);
        noFocusChangeFlag = DisplayHelper.requiredNoFocusChangeFlag(MotionEvent.class);
        set("motionNoFocusChangeFlag", noFocusChangeFlag);
    }

    @After public void tearDown() throws Exception {
        invoke("stopFrameConsumer", new Class<?>[0]);
        set("reader", null);
        if (reader != null) reader.close();
        ReaderShadow.clear();
        set("displayId", -1);
        set("inputResolved", false);
        set("inputService", null);
        set("injectInputEvent", null);
        set("motionSetDisplayId", null);
        set("motionNoFocusChangeFlag", 0);
    }

    @Test public void finalRedrawInsideThrottleIsCopiedEvenWhenNoMoreFramesArrive() throws Exception {
        startReader(new FrameFixtureImage(Color.BLACK));
        await(() -> latestColor() == Color.BLACK);
        FrameFixtureImage last = new FrameFixtureImage(Color.WHITE);
        synchronized (frameLock()) { set("frameLatestAt", SystemClock.uptimeMillis()); }
        ReaderShadow.offer(last);

        await(() -> latestColor() == Color.WHITE);

        assertTrue(last.closed);
        assertEquals(2L, field("frameCount").getLong(null));
        assertEquals("", field("frameError").get(null));
    }

    @Test public void observationFlushesNewestPendingImageBeforeReadingRetainedBitmap() throws Exception {
        startReader(new FrameFixtureImage(Color.BLACK));
        await(() -> latestColor() == Color.BLACK);
        // Keep the periodic copy out of this assertion: an explicit observation must itself flush pending.
        synchronized (frameLock()) { set("frameLatestAt", SystemClock.uptimeMillis() + 10_000); }
        FrameFixtureImage last = new FrameFixtureImage(Color.WHITE);
        ReaderShadow.offer(last);
        await(() -> pendingIs(last));

        Bitmap observed = (Bitmap) invoke("awaitFrameCopy", new Class<?>[] { long.class }, 200L);
        try { assertEquals(Color.WHITE, observed.getPixel(0, 0)); }
        finally { if (observed != null) observed.recycle(); }
        assertTrue(last.closed);
        synchronized (frameLock()) { assertNull(field("framePending").get(null)); }
    }

    @Test public void stoppingConsumerReleasesAnUncopiedFinalImage() throws Exception {
        startReader(new FrameFixtureImage(Color.BLACK));
        await(() -> latestColor() == Color.BLACK);
        synchronized (frameLock()) { set("frameLatestAt", SystemClock.uptimeMillis() + 10_000); }
        FrameFixtureImage last = new FrameFixtureImage(Color.WHITE);
        ReaderShadow.offer(last);
        await(() -> pendingIs(last));

        invoke("stopFrameConsumer", new Class<?>[0]);

        assertTrue(last.closed);
        assertNull(field("framePending").get(null));
        assertNull(field("frameLatest").get(null));
    }

    @Test public void tapActuallyHoldsAndAllEventsShareOneGestureOrigin() throws Exception {
        invoke("tap", new Class<?>[] { long.class, String.class }, 1L, "10 20");
        assertGesture(60, 2);
    }

    @Test public void longPressDoesNotAddDurationToTheRealUpEventTime() throws Exception {
        invoke("longpress", new Class<?>[] { long.class, String.class }, 1L, "10 20 300");
        assertGesture(300, 2);
    }

    @Test public void swipeSchedulesFullRequestedDurationInsteadOfHalfDurationSleeps() throws Exception {
        sink.latencyMs = 3;
        invoke("swipe", new Class<?>[] { long.class, String.class }, 1L, "10 20 110 220 240");
        assertGesture(240, 13);
        assertEquals(110f, sink.events.get(12).x, 0f);
        assertEquals(220f, sink.events.get(12).y, 0f);
    }

    @Test public void rejectedTapReleaseStillAttemptsToCancelThePressedPointer() throws Exception {
        sink.rejectUp = true;
        invoke("tap", new Class<?>[] { long.class, String.class }, 1L, "10 20");
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL), actions());
        assertEquals(sink.events.get(0).downTime, sink.events.get(2).downTime);
        assertFocusSafeEvents();
    }

    @Test public void rejectedSwipeReleaseStillAttemptsToCancelThePressedPointer() throws Exception {
        sink.rejectUp = true;
        invoke("swipe", new Class<?>[] { long.class, String.class }, 1L, "10 20 110 220 60");
        assertEquals(14, sink.events.size());
        assertEquals(MotionEvent.ACTION_CANCEL, sink.events.get(13).action);
        assertEquals(sink.events.get(0).downTime, sink.events.get(13).downTime);
        assertFocusSafeEvents();
    }

    @Test public void touchesStopBeforeInjectionWhenFocusIsolationIsUnavailable() throws Exception {
        set("motionNoFocusChangeFlag", 0);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
            () -> invoke("tap", new Class<?>[] { long.class, String.class }, 1L, "10 20"));
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertTrue(failure.getCause().getMessage().contains("focus isolation unavailable"));
        assertTrue(sink.events.isEmpty());
    }

    @Test public void focusIsolationUsesThePlatformFlagWithoutNumericFallback() throws Exception {
        assertEquals(AlternateMotionFlags.FLAG_NO_FOCUS_CHANGE,
            DisplayHelper.requiredNoFocusChangeFlag(AlternateMotionFlags.class));
        assertThrows(NoSuchFieldException.class,
            () -> DisplayHelper.requiredNoFocusChangeFlag(MissingMotionFlags.class));
        assertThrows(IllegalStateException.class,
            () -> DisplayHelper.requiredNoFocusChangeFlag(InvalidMotionFlags.class));
    }

    @Test public void unreadableBaselineReportsUntestedEvenWithLaterReadableSamples() throws Exception {
        JSONArray samples = new JSONArray()
            .put(new JSONObject().put("step", "baseline"))
            .put(new JSONObject().put("step", "afterCreate").put("appFocus", "com.example/.Main").put("task", "42"));
        Class<?> mainState = Class.forName(DisplayHelper.class.getName() + "$MainState");
        JSONObject verdict = (JSONObject) invoke("probeFocusIsolation",
            new Class<?>[] { mainState, JSONArray.class }, null, samples);
        assertEquals("untested", verdict.getString("status"));
        assertEquals("focusIsolation", verdict.getString("item"));
        assertTrue(verdict.getString("reason").contains("baseline"));
    }

    @Test public void losingPhysicalWindowFocusFailsEvenWhenAppAndTaskStayUnchanged() throws Exception {
        Class<?> mainState = Class.forName(DisplayHelper.class.getName() + "$MainState");
        var constructor = mainState.getDeclaredConstructor(String.class, String.class, String.class);
        constructor.setAccessible(true);
        Object baseline = constructor.newInstance("com.example/.Main", "com.example/.Main", "42");
        JSONArray samples = new JSONArray().put(new JSONObject().put("step", "afterLaunch")
            .put("windowFocus", "").put("appFocus", "com.example/.Main").put("task", "42"));
        JSONObject verdict = (JSONObject) invoke("probeFocusIsolation",
            new Class<?>[] { mainState, JSONArray.class }, baseline, samples);
        assertEquals("unsupported", verdict.getString("status"));
        assertEquals("afterLaunch", verdict.getString("windowFocusChangedDuring"));
    }

    @Test public void missingBaselineWindowFocusCannotClaimKeyboardIsolation() throws Exception {
        Class<?> mainState = Class.forName(DisplayHelper.class.getName() + "$MainState");
        var constructor = mainState.getDeclaredConstructor(String.class, String.class, String.class);
        constructor.setAccessible(true);
        Object baseline = constructor.newInstance("", "com.example/.Main", "42");
        JSONArray samples = new JSONArray().put(new JSONObject().put("step", "afterCreate")
            .put("windowFocus", "").put("appFocus", "com.example/.Main").put("task", "42"));
        JSONObject verdict = (JSONObject) invoke("probeFocusIsolation",
            new Class<?>[] { mainState, JSONArray.class }, baseline, samples);
        assertEquals("untested", verdict.getString("status"));
    }

    private void assertGesture(long duration, int count) {
        assertEquals(count, sink.events.size());
        assertFocusSafeEvents();
        Event first = sink.events.get(0), last = sink.events.get(count - 1);
        assertEquals(MotionEvent.ACTION_DOWN, first.action);
        assertEquals(MotionEvent.ACTION_UP, last.action);
        assertTrue("UP must wait for the requested real hold", last.observedAt - first.downTime >= duration);
        long previous = first.downTime;
        for (Event event : sink.events) {
            assertEquals(first.downTime, event.downTime);
            assertTrue(event.eventTime >= previous);
            assertTrue("event time must never be in the future", event.eventTime <= event.observedAt);
            assertTrue("event time must describe injection, not a synthetic future", event.observedAt - event.eventTime < 100);
            previous = event.eventTime;
        }
    }
    private void assertFocusSafeEvents() {
        for (Event event : sink.events) {
            assertEquals("background gestures must suppress pointer-down-outside-focus",
                noFocusChangeFlag, event.flags & noFocusChangeFlag);
            assertEquals("focus isolation must retain target display routing", 19, event.displayId);
            assertEquals(InputDevice.SOURCE_TOUCHSCREEN, event.source);
        }
    }
    private List<Integer> actions() {
        List<Integer> actions = new ArrayList<>();
        for (Event event : sink.events) actions.add(event.action);
        return actions;
    }
    private void startReader(FrameFixtureImage initial) throws Exception {
        reader = ImageReader.newInstance(1, 1, PixelFormat.RGBA_8888, 3);
        set("reader", reader);
        ReaderShadow.offer(initial);
        invoke("startFrameConsumer", new Class<?>[] { String.class }, "unit-test");
    }
    private static int latestColor() {
        try {
            synchronized (frameLock()) {
                Bitmap latest = (Bitmap) field("frameLatest").get(null);
                return latest == null ? Color.TRANSPARENT : latest.getPixel(0, 0);
            }
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static boolean pendingIs(Image image) {
        try {
            synchronized (frameLock()) { return field("framePending").get(null) == image; }
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static Object frameLock() throws Exception { return field("FRAME_LOCK").get(null); }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = realUptimeMillis() + 3000;
        while (!condition.getAsBoolean() && realUptimeMillis() < deadline)
            Thread.sleep(5);
        assertTrue("condition did not become true", condition.getAsBoolean());
    }
    private static Field field(String name) throws Exception {
        Field field = DisplayHelper.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static void set(String name, Object value) throws Exception { field(name).set(null, value); }
    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = DisplayHelper.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private static Method realNanoTimeMethod() {
        try { return System.class.getMethod("nanoTime"); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static long realNanoTime() {
        try { return (Long) REAL_NANO_TIME.invoke(null); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static long realUptimeMillis() {
        return 10_000 + (realNanoTime() - CLOCK_ORIGIN) / 1_000_000;
    }

    private record Event(int action, long downTime, long eventTime, long observedAt, float x, float y,
            int flags, int displayId, int source) { }

    public static final class AlternateMotionFlags { public static final int FLAG_NO_FOCUS_CHANGE = 1 << 20; }
    public static final class MissingMotionFlags { }
    public static final class InvalidMotionFlags { public static final int FLAG_NO_FOCUS_CHANGE = 3; }

    public static final class InputSink {
        final List<Event> events = new ArrayList<>();
        boolean rejectUp;
        long latencyMs;
        public boolean injectInputEvent(InputEvent input, int mode) throws Exception {
            MotionEvent event = (MotionEvent) input;
            events.add(new Event(event.getAction(), event.getDownTime(), event.getEventTime(),
                SystemClock.uptimeMillis(), event.getX(), event.getY(), event.getFlags(),
                (Integer) MotionEvent.class.getMethod("getDisplayId").invoke(event), event.getSource()));
            if (latencyMs != 0) Thread.sleep(latencyMs);
            return !rejectUp || event.getAction() != MotionEvent.ACTION_UP;
        }
    }

    /**
     * Robolectric 4.17's sole MotionEvent shadow discards displayId in nativeInitialize and implements neither
     * nativeGetDisplayId nor nativeSetDisplayId. GraphicsMode.NATIVE has no native MotionEvent implementation.
     * Supply only that missing property; flags, source, coordinates and timing retain ShadowMotionEvent behavior.
     * Reset it on recycle so an earlier event cannot hide a missing per-event display-id setter.
     */
    @Implements(MotionEvent.class)
    public static class DisplayRoutingMotionShadow extends ShadowMotionEvent {
        @RealObject private MotionEvent event;
        private int displayId;
        @Implementation protected void setDisplayId(int value) { displayId = value; }
        @Implementation protected int getDisplayId() { return displayId; }
        @Implementation protected void recycle() {
            displayId = 0;
            Shadow.directlyOn(event, MotionEvent.class, "recycle");
        }
    }

    /** Keeps Thread.sleep and Android uptime on one real clock, including the production drain thread. */
    @Implements(SystemClock.class)
    public static class UptimeShadow {
        @Implementation public static long uptimeMillis() {
            // Reflective invocation bypasses Robolectric's direct System.nanoTime call-site interception.
            return realUptimeMillis();
        }
    }

    /** Supplies acquired image buffers; all scheduling, retention, copying and release runs production code. */
    @Implements(ImageReader.class)
    public static class ReaderShadow extends ShadowImageReader {
        private static final Queue<Image> QUEUE = new ArrayDeque<>();
        static synchronized void offer(Image image) { QUEUE.add(image); }
        static synchronized void clear() {
            Image image;
            while ((image = QUEUE.poll()) != null) image.close();
        }
        @Implementation protected synchronized Image acquireLatestImage() {
            synchronized (ReaderShadow.class) {
                Image latest = null;
                Image next;
                while ((next = QUEUE.poll()) != null) {
                    if (latest != null) latest.close();
                    latest = next;
                }
                return latest;
            }
        }
    }
}
