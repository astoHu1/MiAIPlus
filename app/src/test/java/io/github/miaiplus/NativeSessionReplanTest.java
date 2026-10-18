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

    @Test public void cancellationAfterReplanStillClosesSession() throws Exception {
        ScreenShadow.current = frame(BUTTON.replace("提交", "新目标"));
        assertSkipped(step(200, 200));
        NativeSession.cancel();
        ((ExecutorService) field(NativeSession.class, "CLEANUP").get(null)).submit(() -> { }).get(5, TimeUnit.SECONDS);
        assertTrue(sessionRun.cancelled.get());
        assertNull(field(NativeSession.class, "active").get(null));
        assertFalse(NativeSession.hasActiveSession());
        assertEquals(0, ScreenShadow.executions);
    }

    @Test public void finalAllowedActionReturnsItsObservationAndZeroRemainingBudget() throws Exception {
        field(session.getClass(), "steps").setInt(session, 19);
        String result = step(200, 200);
        assertContinuing(result);
        assertTrue(result, result.contains("步数=20/20；剩余动作步数=0"));
        assertTrue(result, result.contains("下一次只允许finish、take_over或ask_user"));
        assertSame(ScreenShadow.current, field(session.getClass(), "frame").get(session));
        assertEquals(1, ScreenShadow.executions);
    }

    @Test public void exhaustedBudgetRejectsMalformedNextActionWithoutParsingOrExecutingIt() throws Exception {
        field(session.getClass(), "steps").setInt(session, 20);
        try {
            step(new JSONObject().put("action", "tap"));
            fail("Expected exhausted budget");
        } catch (IllegalStateException stopped) {
            assertTrue(stopped.getMessage(), stopped.getMessage().startsWith("达到最大步数，任务未确认完成"));
            assertTrue(stopped.getMessage(), stopped.getMessage().contains("不要重试或重新发起任务"));
        }
        assertEquals(20, field(session.getClass(), "steps").getInt(session));
        assertEquals(0, ScreenShadow.attempts);
        assertClosed();
    }

    @Test public void finishRemainsAllowedAfterFinalAction() throws Exception {
        field(session.getClass(), "steps").setInt(session, 19);
        assertContinuing(step(200, 200));
        String result = step(new JSONObject().put("action", "finish").put("message", "已从最后观察确认完成"));
        assertEquals("模型报告任务完成：已从最后观察确认完成", result);
        assertEquals(20, field(session.getClass(), "steps").getInt(session));
        assertEquals(1, ScreenShadow.executions);
        assertClosed();
    }

    @Test public void takeoverRemainsAllowedAtExhaustedBudget() throws Exception {
        field(session.getClass(), "steps").setInt(session, 20);
        assertEquals("PAUSE：尚有步骤未完成", step(new JSONObject().put("action", "take_over").put("message", "尚有步骤未完成")));
        assertEquals(0, ScreenShadow.attempts);
        assertClosed();
    }

    @Test public void skippedFinalActionConsumesBudgetAndPreservesLastObservation() throws Exception {
        field(session.getClass(), "steps").setInt(session, 19);
        ScreenShadow.current = frame(BUTTON.replace("提交", "新目标"));
        String result = step(200, 200);
        assertSkipped(result);
        assertTrue(result, result.contains("剩余动作步数=0"));
        assertEquals(0, ScreenShadow.attempts);
        assertSame(ScreenShadow.current, field(session.getClass(), "frame").get(session));
        assertEquals("PAUSE：目标已变化，尚未完成", step(new JSONObject().put("action", "ask_user")
            .put("message", "目标已变化，尚未完成")));
        assertClosed();
    }

    @Test public void fatalExecutionFailureClosesSessionWithoutOfferingRetry() throws Exception {
        ScreenShadow.failure = new IllegalStateException("控屏执行失败");
        try {
            step(200, 200);
            fail("Expected execution failure");
        } catch (IllegalStateException stopped) {
            assertEquals("控屏执行失败", stopped.getMessage());
        }
        assertEquals(1, ScreenShadow.attempts);
        assertEquals(0, ScreenShadow.executions);
        assertClosed();
    }

    @Test public void staleOrForeignContinuationCannotCloseCurrentSession() throws Exception {
        String query = "MIAIPLUS:" + new JSONObject().put("session", "expired-session")
            .put("action", new JSONObject().put("action", "wait"));
        for (int owner : new int[]{OWNER, OWNER + 1}) {
            try {
                NativeSession.handle(new Config(Map.of()), query, owner);
                fail("Expected stale session");
            } catch (IllegalStateException stopped) {
                assertTrue(stopped.getMessage(), stopped.getMessage().startsWith("控屏会话不存在或已过期，本轮已结束"));
                assertFalse(stopped.getMessage(), stopped.getMessage().contains("请重新提交"));
            }
            assertSame(session, field(NativeSession.class, "active").get(null));
            assertFalse(sessionRun.cancelled.get());
        }
        assertEquals(0, ScreenShadow.attempts);
    }

    @Test public void continuationPreflightIsReadOnlyAndRejectsExpiredOrCancelledSessions() throws Exception {
        String query = "MIAIPLUS:" + new JSONObject().put("session", sessionRun.id)
            .put("action", new JSONObject().put("action", "wait"));
        NativeSession.verifyContinuation(query, OWNER, "");
        assertEquals(0, field(session.getClass(), "steps").getInt(session));
        long deadline = sessionRun.deadline;
        long expired = android.os.SystemClock.elapsedRealtime();
        sessionRun.deadline = expired;
        assertPreflightRejected(query);
        assertFalse(sessionRun.cancelled.get());
        assertEquals(expired, sessionRun.deadline);
        sessionRun.deadline = deadline;
        sessionRun.cancelled.set(true);
        assertPreflightRejected(query);
        assertSame(session, field(NativeSession.class, "active").get(null));
        assertEquals(0, ScreenShadow.attempts);
    }

    private void assertPreflightRejected(String query) throws Exception {
        try {
            NativeSession.verifyContinuation(query, OWNER, "");
            fail("Expected expired session");
        } catch (IllegalStateException stopped) {
            assertTrue(stopped.getMessage(), stopped.getMessage().startsWith("控屏会话不存在或已过期，本轮已结束"));
        }
    }

    private String step(int x, int y) throws Exception {
        JSONObject action = new JSONObject().put("action", "tap").put("element", new JSONArray().put(x).put(y));
        return step(action);
    }
    private String step(JSONObject action) throws Exception {
        String query = "MIAIPLUS:" + new JSONObject().put("session", sessionRun.id)
            .put("coordinate_space", "normalized").put("action", action);
        return NativeSession.handle(new Config(Map.of("maxSteps", 20)), query, OWNER);
    }
    private void assertClosed() throws Exception {
        assertTrue(sessionRun.cancelled.get());
        assertNull(field(NativeSession.class, "active").get(null));
        assertFalse(NativeSession.hasActiveSession());
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
        static Exception failure;
        static int attempts, executions;
        static double[] lastPoint;
        static void reset() { current = null; rejectInjection = false; failure = null; attempts = executions = 0; lastPoint = null; }
        @Implementation protected ScreenService.Frame capture(RunState run) { run.check(); return current; }
        @Implementation protected void execute(Action action, ScreenService.Frame frame, RunState run) throws Exception {
            run.check();
            attempts++;
            if (rejectInjection) throw new ScreenService.ObservationChanged();
            if (failure != null) throw failure;
            executions++;
            lastPoint = action.point("element");
        }
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
