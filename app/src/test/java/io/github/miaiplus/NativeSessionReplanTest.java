package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.graphics.Bitmap;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
    shadows = {NativeSessionReplanTest.ScreenShadow.class, NativeSessionReplanTest.RunShadow.class,
        NativeSessionReplanTest.BackgroundShadow.class, NativeSessionReplanTest.BackgroundActionShadow.class})
public final class NativeSessionReplanTest {
    private static final int OWNER = 1000;
    private static final String ROOT = "android.widget.FrameLayout [0,0][1000,1000] window=3 id=root enabled=true\n";
    private static final String BUTTON = "android.widget.Button [100,100][300,300] window=3 id=submit enabled=true clickable text=提交\n";
    private Object session;
    private RunState sessionRun;
    private String image;

    @Before public void setUp() throws Exception {
        ScreenShadow.reset();
        BackgroundShadow.closes = 0;
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
        String movedButton = BUTTON.replace("[100,100][300,300]", "[400,100][600,300]");
        ScreenShadow.current = frame(movedButton);
        assertSkipped(step(200, 200));
        assertEquals(0, ScreenShadow.attempts);
        assertEquals(0, ScreenShadow.executions);
        assertSame(ScreenShadow.current, field(session.getClass(), "frame").get(session));
        assertExecuted(step(500, 200));
        assertEquals(1, ScreenShadow.executions);
        assertArrayEquals(new double[]{500, 200}, ScreenShadow.lastPoint, 0);
    }

    @Test public void initialObservationDoesNotReportAnExecutedAction() throws Exception {
        ScreenService previous = ScreenService.instance;
        ScreenService.instance = (ScreenService) field(session.getClass(), "screen").get(session);
        field(NativeSession.class, "active").set(null, null);
        try {
            String result = NativeSession.handle(new Config(Map.of()), "查看当前页面", OWNER);
            session = field(NativeSession.class, "active").get(null);
            sessionRun = (RunState) field(session.getClass(), "run").get(session);
            assertContinuing(result);
            assertTrue(result, result.contains("上步动作=none；执行状态=initial\n"));
            assertFalse(result, result.contains("执行状态=executed"));
            assertEquals(0, ScreenShadow.attempts);
        } finally {
            ScreenService.instance = previous;
        }
    }

    @Test public void foregroundHandoffReportsOldActionUnexecutedAndInvalidatesOldCoordinates() throws Exception {
        field(session.getClass(), "background").setBoolean(session, true);
        field(session.getClass(), "frame").set(session, ScreenService.Frame.background(image, ROOT + BUTTON,
            1000, 1000, 8, 8, "example.app", 7, "fixture-generation"));
        Constructor<BackgroundDisplay> constructor = BackgroundDisplay.class.getDeclaredConstructor(Process.class, File.class);
        constructor.setAccessible(true);
        field(session.getClass(), "display").set(session, constructor.newInstance(null, null));
        String result = step(new JSONObject().put("action", "launch").put("app", "example.app"));
        assertContinuing(result);
        assertTrue(result, result.contains("上步动作=launch；执行状态=handoff\n"));
        assertTrue(result, result.contains("本步未执行旧动作；旧后台截图和坐标已作废"));
        assertTrue(result, result.contains("执行屏幕=foreground"));
        assertFalse(result, result.contains("执行状态=executed"));
        assertEquals(0, ScreenShadow.attempts);
        assertEquals(1, BackgroundShadow.closes);
    }

    @Test public void manualConfirmationAndCriticalYoloUseConfirmationBeforeOneInput() throws Exception {
        for (String mode : new String[]{"manual", "yolo"}) {
            field(session.getClass(), "permissionMode").set(session, mode);
            JSONObject action = sensitiveAction();
            if (mode.equals("yolo")) action.put("risk_level", "critical");
            int confirmations = ScreenShadow.confirmations, executions = ScreenShadow.executions;
            assertExecuted(step(action));
            assertEquals(confirmations + 1, ScreenShadow.confirmations);
            assertEquals(executions + 1, ScreenShadow.executions);
            assertTrue(ScreenShadow.lastApproved);
        }
    }

    @Test public void yoloApprovesOrdinarySensitiveActionAndManualReplansWhenConfirmationChangesTarget() throws Exception {
        field(session.getClass(), "permissionMode").set(session, "yolo");
        assertExecuted(step(sensitiveAction()));
        assertEquals(0, ScreenShadow.confirmations);
        assertEquals(1, ScreenShadow.executions);
        field(session.getClass(), "permissionMode").set(session, "manual");
        ScreenShadow.afterConfirmation = frame(BUTTON.replace("提交", "另一个操作"));
        assertSkipped(step(sensitiveAction()));
        assertEquals(1, ScreenShadow.confirmations);
        assertEquals(1, ScreenShadow.executions);
    }

    @Test public void autoReviewUsesSeparateBoundStepWithoutSpendingLastActionBudget() throws Exception {
        field(session.getClass(), "permissionMode").set(session, "auto");
        field(session.getClass(), "steps").setInt(session, 19);
        JSONObject action = sensitiveAction();
        String challengeResult = step(action);
        assertContinuing(challengeResult);
        assertTrue(challengeResult, challengeResult.contains("上步动作=tap；执行状态=waiting_review\n"));
        assertFalse(challengeResult, challengeResult.contains("执行状态=executed"));
        assertTrue(challengeResult, challengeResult.contains("审计挑战="));
        assertEquals(19, field(session.getClass(), "steps").getInt(session));
        assertEquals(0, ScreenShadow.executions);
        JSONObject challenge = (JSONObject) field(session.getClass(), "reviewChallenge").get(session);
        action.put("permission_review", new JSONObject().put("token", challenge.getString("token"))
            .put("decision", "allow").put("reason", "用户目标已授权该次提交，操作范围一致"));
        assertExecuted(step(action));
        assertEquals(20, field(session.getClass(), "steps").getInt(session));
        assertEquals(0, ScreenShadow.confirmations);
        assertEquals(1, ScreenShadow.executions);
    }

    @Test public void missingOrForgedAutoReviewFallsBackToIslandAndSettingsChangeDoesNotChangeSessionMode() throws Exception {
        field(session.getClass(), "permissionMode").set(session, "auto");
        assertContinuing(step(sensitiveAction()));
        JSONObject action = sensitiveAction().put("permission_review", new JSONObject()
            .put("token", "wrong-token").put("decision", "allow").put("reason", "approved"));
        assertContinuing(step(action));
        assertEquals(1, ScreenShadow.confirmations);
        assertEquals(1, ScreenShadow.executions);
        // step() supplies default Manual configuration, but this already-started task retains Auto.
        assertEquals("auto", field(session.getClass(), "permissionMode").get(session));
    }

    @Test public void autoChallengeKeepsOriginalTargetWhenReturnedObservationAlreadyChanged() throws Exception {
        field(session.getClass(), "permissionMode").set(session, "auto");
        ScreenShadow.current = frame(BUTTON.replace("提交", "另一个操作"));
        JSONObject action = sensitiveAction();
        assertContinuing(step(action));
        JSONObject challenge = (JSONObject) field(session.getClass(), "reviewChallenge").get(session);
        action.put("permission_review", new JSONObject().put("token", challenge.getString("token"))
            .put("decision", "allow").put("reason", "原始操作已授权"));
        assertSkipped(step(action));
        assertEquals(0, ScreenShadow.confirmations);
        assertEquals(0, ScreenShadow.executions);
        assertNull(field(session.getClass(), "reviewChallenge").get(session));
    }

    @Test public void structuredImagePixelActionCanReplayNormalizedAuditChallengeWithoutSecondConversion() throws Exception {
        field(session.getClass(), "permissionMode").set(session, "auto");
        field(session.getClass(), "sourceTool").set(session, NativePhoneUseTool.NAME);
        JSONObject pixelAction = sensitiveAction().put("element", new JSONArray().put(1.6).put(1.6));
        JSONObject request = new JSONObject().put("session", sessionRun.id).put("action", pixelAction);
        String encoded = PhoneUseRouting.encode(NativePhoneUseTool.NAME, request);
        Config config = new Config(Map.of());
        assertContinuing(NativeSession.handle(config, encoded, new RunState("proposal", OWNER), false, NativePhoneUseTool.NAME));
        JSONObject challenge = (JSONObject) field(session.getClass(), "reviewChallenge").get(session);
        assertEquals("normalized", challenge.getString("coordinate_space"));
        JSONObject reviewed = new JSONObject(challenge.getJSONObject("action").toString())
            .put("permission_review", new JSONObject().put("token", challenge.getString("token"))
                .put("decision", "allow").put("reason", "用户目标授权该表单提交"));
        JSONObject continuation = new JSONObject().put("session", sessionRun.id).put("action", reviewed)
            .put("coordinate_space", challenge.getString("coordinate_space"));
        assertContinuing(NativeSession.handle(config, PhoneUseRouting.encode(NativePhoneUseTool.NAME, continuation),
            new RunState("reviewed", OWNER), false, NativePhoneUseTool.NAME));
        assertEquals(0, ScreenShadow.confirmations);
        assertEquals(1, ScreenShadow.executions);
        assertArrayEquals(new double[]{200, 200}, ScreenShadow.lastPoint, 0.001);
    }

    @Test public void manualSystemApprovalCannotAuthorizeChangedPromptWithSameAllowButton() throws Exception {
        String permission = "android.widget.TextView [0,400][1000,500] window=3 "
            + "id=com.miui.securitycenter:id/permission_group_title enabled=true text=读取联系人\n";
        ScreenService.Frame before = new ScreenService.Frame(image, ROOT + BUTTON + permission,
            1000, 1000, "com.miui.securitycenter", 8, 8, 0, "");
        field(session.getClass(), "frame").set(session, before);
        ScreenShadow.current = before;
        ScreenShadow.afterConfirmation = new ScreenService.Frame(image,
            before.tree().replace("读取联系人", "读取短信"), 1000, 1000, "com.miui.securitycenter", 8, 8, 0, "");
        assertSkipped(step(sensitiveAction()));
        assertEquals(1, ScreenShadow.confirmations);
        assertEquals(0, ScreenShadow.executions);
    }

    private JSONObject sensitiveAction() throws Exception {
        return new JSONObject().put("action", "tap").put("element", new JSONArray().put(200).put(200))
            .put("requires_confirmation", true).put("message", "提交当前表单");
    }

    @Test public void repeatedPreflightSkipsOnAFreshSessionStillAllowCancellationWithoutExecution() throws Exception {
        assertEquals("", field(session.getClass(), "previous").get(session));
        for (int attempt = 0; attempt < 6; attempt++) {
            ScreenShadow.current = frame(BUTTON.replace("提交", "新目标" + attempt));
            assertSkipped(step(200, 200));
            assertSame(ScreenShadow.current, field(session.getClass(), "frame").get(session));
            assertEquals("Preflight skip must not reach injection, attempt " + attempt, 0, ScreenShadow.attempts);
            assertEquals("Preflight skip must not execute an action, attempt " + attempt, 0, ScreenShadow.executions);
            assertEquals("Preflight skip must not record an executed action, attempt " + attempt,
                "", field(session.getClass(), "previous").get(session));
        }
        NativeSession.cancel();
        ((ExecutorService) field(NativeSession.class, "CLEANUP").get(null)).submit(() -> { }).get(5, TimeUnit.SECONDS);
        assertClosed();
        assertEquals("Cancellation after replan must not execute a skipped action", 0, ScreenShadow.executions);
    }

    @Test public void repeatedInjectionSkipsPreserveSessionAndAllowReplanWithoutRepeatLimit() throws Exception {
        ScreenShadow.rejectInjection = true;
        for (int attempt = 0; attempt < 6; attempt++) assertSkipped(step(200, 200));
        assertEquals("All skipped actions must reach the injection boundary", 6, ScreenShadow.attempts);
        assertEquals("Injection rejection must not execute an action", 0, ScreenShadow.executions);
        ScreenShadow.rejectInjection = false;
        assertExecuted(step(200, 200));
        assertEquals(1, ScreenShadow.executions);
    }

    @Test public void unrelatedDynamicTextKeepsStableTargetExecutable() throws Exception {
        field(session.getClass(), "frame").set(session, frame(BUTTON + clock("10:00")));
        ScreenShadow.current = frame(BUTTON + clock("10:01"));
        String result = step(200, 200);
        assertExecuted(result);
        assertFalse(result.contains("本步未执行"));
        assertEquals(1, ScreenShadow.executions);
    }

    @Test public void finalAllowedActionReturnsLastObservationAndStillAllowsFinish() throws Exception {
        field(session.getClass(), "steps").setInt(session, 19);
        String result = step(200, 200);
        assertExecuted(result);
        assertTrue(result, result.contains("步数=20/20；剩余动作步数=0"));
        assertTrue(result, result.contains("下一次只允许finish、take_over或ask_user"));
        assertSame(ScreenShadow.current, field(session.getClass(), "frame").get(session));
        assertEquals(1, ScreenShadow.executions);
        assertEquals("模型报告任务完成：已从最后观察确认完成",
            step(new JSONObject().put("action", "finish").put("message", "已从最后观察确认完成")));
        assertEquals(20, field(session.getClass(), "steps").getInt(session));
        assertEquals(1, ScreenShadow.executions);
        assertClosed();
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
        assertTrue(result, result.contains("上步动作=tap；执行状态=skipped\n"));
        assertFalse(result, result.contains("执行状态=executed"));
    }
    private void assertExecuted(String result) throws Exception {
        assertContinuing(result);
        assertTrue(result, result.contains("上步动作=tap；执行状态=executed\n"));
        assertTrue(result, result.contains("动作效果和任务是否完成仍须依据本次新观察核实"));
        assertFalse(result, result.contains("执行状态=skipped"));
    }
    private void assertContinuing(String result) throws Exception {
        assertTrue(result, result.startsWith("CONTINUE："));
        assertTrue(result, result.contains("会话=" + sessionRun.id + "；"));
        assertTrue(result, result.contains("MIAIPLUS_FRAME:"));
        assertEquals(sessionRun.id, NativeTurnLifecycle.continuationSession(result));
        assertFalse(PhoneUseResult.endsNativeTurn(result));
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
        static boolean rejectInjection, lastApproved;
        static ScreenService.Frame afterConfirmation;
        static Exception failure;
        static int attempts, executions, confirmations;
        static double[] lastPoint;
        static void reset() {
            current = afterConfirmation = null; rejectInjection = lastApproved = false; failure = null;
            attempts = executions = confirmations = 0; lastPoint = null;
        }
        @Implementation protected static boolean isHealthy() { return true; }
        @Implementation protected String apps() { return "示例 = example.app\n"; }
        @Implementation protected ScreenService.Frame capture(RunState run) { run.check(); return current; }
        @Implementation protected boolean confirm(String message, RunState run) {
            run.check(); confirmations++;
            if (afterConfirmation != null) current = afterConfirmation;
            return true;
        }
        @Implementation protected void execute(Action action, ScreenService.Frame frame, RunState run, boolean approved) throws Exception {
            run.check();
            lastApproved = approved;
            attempts++;
            if (rejectInjection) throw new ScreenService.ObservationChanged();
            if (failure != null) throw failure;
            executions++;
            lastPoint = action.point("element");
        }
    }

    /** Handoff result fixture only; no helper process or actual display is created. */
    @Implements(value = BackgroundDisplay.class, isInAndroidSdk = false)
    public static final class BackgroundShadow {
        static int closes;
        @Implementation protected void __constructor__(Process helper, File frameDirectory) { }
        @Implementation public void close() { closes++; }
    }

    @Implements(value = BackgroundActions.class, isInAndroidSdk = false)
    public static final class BackgroundActionShadow {
        @Implementation protected static String foregroundHandoff(BackgroundDisplay display, Action action,
            RunState run, BackgroundActions.LaunchPolicy policy) throws Exception {
            assertNotNull(display);
            assertEquals("launch", action.name);
            return action.data.getString("app");
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
