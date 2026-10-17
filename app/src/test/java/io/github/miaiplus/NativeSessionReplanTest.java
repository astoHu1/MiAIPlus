package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.graphics.Bitmap;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class,
    shadows = {NativeSessionReplanTest.ScreenShadow.class, NativeSessionReplanTest.RunShadow.class})
public final class NativeSessionReplanTest {
    private static final int OWNER = 1000;
    private static final String ROOT = "android.widget.FrameLayout [0,0][1000,1000] window=3 id=root enabled=true\n";
    private static final String BUTTON = "android.widget.Button [100,100][300,300] window=3 id=submit enabled=true clickable text=提交\n";
    private Object session;
    private RunState sessionRun;
    private String image;

    @Before public void setUp() throws Exception {
        ScreenShadow.reset();
        FrameStore.clear();
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bytes));
        bitmap.recycle();
        image = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
        ScreenService service = Robolectric.buildService(ScreenService.class).create().get();
        Class<?> type = Class.forName(NativeSession.class.getName() + "$State");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class, int.class,
            ScreenService.class, boolean.class);
        constructor.setAccessible(true);
        session = constructor.newInstance("点击目标", OWNER, 20, service, false);
        sessionRun = (RunState) field(type, "run").get(session);
        field(type, "frame").set(session, frame(BUTTON));
        field(NativeSession.class, "active").set(null, session);
        ScreenShadow.current = frame(BUTTON);
    }

    @After public void tearDown() throws Exception {
        NativeSession.cancel();
        ((ExecutorService) field(NativeSession.class, "CLEANUP").get(null)).submit(() -> { }).get(5, TimeUnit.SECONDS);
        FrameStore.clear();
    }

    @Test public void changedTargetReturnsFreshObservationWithoutExecutingAndNewCoordinatesWork() throws Exception {
        ScreenShadow.current = frame(BUTTON.replace("[100,100][300,300]", "[400,100][600,300]"));
        assertSkipped(step(200, 200));
        assertEquals(0, ScreenShadow.attempts);
        assertEquals(0, ScreenShadow.executions);
        assertSame(ScreenShadow.current, field(session.getClass(), "frame").get(session));
        assertContinuing(step(500, 200));
        assertEquals(1, ScreenShadow.executions);
        assertArrayEquals(new double[]{500, 200}, ScreenShadow.lastPoint, 0);
    }

    @Test public void changeAtTheInjectionBoundaryPreservesSessionAndAllowsReplan() throws Exception {
        ScreenShadow.rejectInjection = true;
        assertSkipped(step(200, 200));
        assertEquals(1, ScreenShadow.attempts);
        assertEquals(0, ScreenShadow.executions);
        ScreenShadow.rejectInjection = false;
        assertContinuing(step(200, 200));
        assertEquals(1, ScreenShadow.executions);
    }

    @Test public void unrelatedDynamicTextKeepsStableTargetExecutable() throws Exception {
        field(session.getClass(), "frame").set(session, frame(BUTTON + clock("10:00")));
        ScreenShadow.current = frame(BUTTON + clock("10:01"));
        String result = step(200, 200);
        assertContinuing(result);
        assertFalse(result.contains("本步未执行"));
        assertEquals(1, ScreenShadow.executions);
    }

    @Test public void repeatedPreflightSkipsDoNotCountAsExecutedNoProgressActions() throws Exception {
        for (int attempt = 0; attempt < 6; attempt++) {
            ScreenShadow.current = frame(BUTTON.replace("提交", "新目标" + attempt));
            assertSkipped(step(200, 200));
        }
        assertEquals(0, ScreenShadow.attempts);
        assertEquals(0, ScreenShadow.executions);
    }

    @Test public void repeatedInjectionSkipsDoNotTriggerExecutedActionRepeatLimit() throws Exception {
        ScreenShadow.rejectInjection = true;
        for (int attempt = 0; attempt < 6; attempt++) assertSkipped(step(200, 200));
        assertEquals(6, ScreenShadow.attempts);
        assertEquals(0, ScreenShadow.executions);
    }

    @Test public void cancellationAfterReplanStillClosesSessionAndHidesControls() throws Exception {
        ScreenShadow.current = frame(BUTTON.replace("提交", "新目标"));
        assertSkipped(step(200, 200));
        NativeSession.cancel();
        ((ExecutorService) field(NativeSession.class, "CLEANUP").get(null)).submit(() -> { }).get(5, TimeUnit.SECONDS);
        assertTrue(sessionRun.cancelled.get());
        assertNull(field(NativeSession.class, "active").get(null));
        assertEquals(1, ScreenShadow.hidden);
        assertEquals(0, ScreenShadow.executions);
    }

    private String step(int x, int y) throws Exception {
        JSONObject action = new JSONObject().put("action", "tap").put("element", new JSONArray().put(x).put(y));
        String query = "MIAIPLUS:" + new JSONObject().put("session", sessionRun.id)
            .put("coordinate_space", "normalized").put("action", action);
        return NativeSession.handle(new Config(Map.of("maxSteps", 20)), query, OWNER);
    }
    private void assertSkipped(String result) throws Exception {
        assertContinuing(result);
        assertTrue(result, result.contains("本步未执行"));
    }
    private void assertContinuing(String result) throws Exception {
        assertTrue(result, result.startsWith("CONTINUE："));
        assertTrue(result, result.contains("会话=" + sessionRun.id + "；"));
        assertTrue(result, result.contains("MIAIPLUS_FRAME:"));
        assertSame(session, field(NativeSession.class, "active").get(null));
        assertTrue(NativeSession.hasActiveSession());
        assertFalse(sessionRun.cancelled.get());
        assertEquals(0, ScreenShadow.hidden);
    }
    private ScreenService.Frame frame(String nodes) {
        return new ScreenService.Frame(image, ROOT + nodes, 1000, 1000, "example.app", 8, 8, 0, "");
    }
    private static String clock(String time) {
        return "android.widget.TextView [700,0][1000,80] window=3 enabled=true text=" + time + "\n";
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Implements(value = ScreenService.class, isInAndroidSdk = false)
    public static final class ScreenShadow {
        static ScreenService.Frame current;
        static boolean rejectInjection;
        static int attempts, executions, hidden;
        static double[] lastPoint;
        static void reset() { current = null; rejectInjection = false; attempts = executions = hidden = 0; lastPoint = null; }
        @Implementation protected ScreenService.Frame capture(RunState run) { run.check(); return current; }
        @Implementation protected void execute(Action action, ScreenService.Frame frame, RunState run) throws Exception {
            run.check();
            attempts++;
            if (rejectInjection) throw new ScreenService.ObservationChanged();
            executions++;
            lastPoint = action.point("element");
        }
        @Implementation protected void hideControls() { hidden++; }
    }

    /** Advance only the test clock while retaining the real cancellation/deadline checks. */
    @Implements(value = RunState.class, isInAndroidSdk = false)
    public static final class RunShadow {
        @RealObject private RunState real;
        @Implementation protected void pause(long millis) {
            real.check();
            ShadowSystemClock.advanceBy(Duration.ofMillis(millis));
            real.check();
        }
    }
}
