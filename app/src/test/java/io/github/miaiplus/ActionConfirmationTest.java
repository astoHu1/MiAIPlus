package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class ActionConfirmationTest {
    private BridgeProvider provider;
    private RunState run;
    private FutureTask<Boolean> waiting;

    @Test public void explicitConfirmationWithBlankMessageKeepsAnOperationDescription() throws Exception {
        Action action = new Action(new org.json.JSONObject().put("action", "back")
            .put("requires_confirmation", true).put("message", " \n ").put("intent", "返回订单页"));
        assertEquals("返回订单页", ActionConfirmation.message(action));
        action.data.remove("intent");
        assertEquals("返回上一页", ActionConfirmation.message(action));
        ActionConfirmation.Request request = startWaiting(ActionConfirmation.message(action));
        assertFalse(waiting.isDone());
        assertTrue(answer("task-a", request.token, false));
        finishWaiting();
        assertFalse(waiting.get(1, TimeUnit.SECONDS));
    }

    @Test public void suppliedConfirmationDescriptionIsPreservedInFull() throws Exception {
        String message = "确认发送给指定收件人：\n" + "完整操作说明".repeat(50);
        Action action = new Action(new org.json.JSONObject().put("action", "back").put("message", message));
        assertEquals(message, ActionConfirmation.message(action));
    }

    @Before public void setUp() throws Exception {
        field(ActionConfirmation.class, "pending").set(null, null);
        run = new RunState("step-a", Process.myUid());
        run.screen = true;
        field(BridgeProvider.class, "active").set(null, run);
        field(BridgeProvider.class, "screenTaskId").set(null, "task-a");
        field(BridgeProvider.class, "screenTaskActive").setBoolean(null, true);
        provider = new BridgeProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = "io.github.miaiplus.bridge";
        provider.attachInfo(RuntimeEnvironment.getApplication(), info);
    }

    @After public void tearDown() throws Exception {
        run.cancel();
        if (waiting != null) {
            finishWaiting();
            try { waiting.get(1, TimeUnit.SECONDS); }
            catch (ExecutionException expected) { /* Cancellation belongs to the test's task. */ }
        }
        field(ActionConfirmation.class, "pending").set(null, null);
        field(BridgeProvider.class, "active").set(null, null);
        field(BridgeProvider.class, "screenTaskId").set(null, null);
        field(BridgeProvider.class, "screenTaskActive").setBoolean(null, false);
    }

    @Test public void approvalRequiresMatchingOwnerTaskAndTokenAndCannotBeReplayed() {
        ActionConfirmation.Request request = request();
        long now = SystemClock.elapsedRealtime();
        assertFalse(request.respond("task-a", run.owner + 1, request.token, true, now));
        assertFalse(request.respond("task-b", run.owner, request.token, true, now));
        assertFalse(request.respond("task-a", run.owner, "old-token", true, now));
        assertNull(request.answer());
        assertTrue(request.respond("task-a", run.owner, request.token, true, now));
        assertFalse(request.respond("task-a", run.owner, request.token, false, now));
        assertEquals(Boolean.TRUE, request.answer());
    }

    @Test public void expiryCancellationAndStepDeadlineEachRejectApproval() {
        ActionConfirmation.Request expired = request();
        assertFalse(expired.respond("task-a", run.owner, expired.token, true, expired.expires));
        ActionConfirmation.Request deadline = request();
        run.deadline = SystemClock.elapsedRealtime();
        assertFalse(deadline.respond("task-a", run.owner, deadline.token, true, run.deadline));
        run.extend();
        ActionConfirmation.Request cancelled = request();
        run.cancel();
        assertFalse(cancelled.respond("task-a", run.owner, cancelled.token, true, SystemClock.elapsedRealtime()));
        assertNull(expired.answer());
        assertNull(deadline.answer());
        assertNull(cancelled.answer());
    }

    @Test public void denialCannotBeReplacedByAReplayedApproval() {
        ActionConfirmation.Request request = request();
        long now = SystemClock.elapsedRealtime();
        assertTrue(request.respond("task-a", run.owner, request.token, false, now));
        assertFalse(request.respond("task-a", run.owner, request.token, true, now));
        assertEquals(Boolean.FALSE, request.answer());
        assertNotEquals(request.token, request().token);
    }

    @Test public void providerApprovalUnblocksOnlyThePendingActionAndClearsIt() throws Exception {
        ActionConfirmation.Request request = startWaiting("发送这条消息");
        assertTrue(answer("task-a", request.token, true));
        finishWaiting();
        assertTrue(waiting.get(1, TimeUnit.SECONDS));
        assertFalse(run.cancelled.get());
        assertNull(pending());
        assertFalse(answer("task-a", request.token, true));
    }

    @Test public void providerRejectsOldTaskMissingActiveAndWrongTokenWithoutConsumingConfirmation() throws Exception {
        ActionConfirmation.Request request = startWaiting("删除指定文件");
        assertFalse(answer("old-task", request.token, true));
        assertFalse(answer("task-a", "old-token", true));
        field(BridgeProvider.class, "screenTaskId").set(null, "task-b");
        assertFalse(answer("task-a", request.token, true));
        assertFalse(answer("task-b", request.token, true));
        field(BridgeProvider.class, "screenTaskId").set(null, "task-a");
        RunState otherOwner = new RunState("other-step", run.owner + 1);
        otherOwner.screen = true;
        field(BridgeProvider.class, "active").set(null, otherOwner);
        assertFalse(answer("task-a", request.token, true));
        field(BridgeProvider.class, "active").set(null, null);
        assertFalse(answer("task-a", request.token, true));
        field(BridgeProvider.class, "active").set(null, run);
        field(BridgeProvider.class, "screenTaskActive").setBoolean(null, false);
        assertFalse(answer("task-a", request.token, true));
        field(BridgeProvider.class, "screenTaskActive").setBoolean(null, true);
        assertNull(request.answer());
        assertTrue(answer("task-a", request.token, true));
        finishWaiting();
        assertTrue(waiting.get(1, TimeUnit.SECONDS));
    }

    @Test public void refusingThroughProviderStopsTheTaskAndClearsPendingConfirmation() throws Exception {
        ActionConfirmation.Request request = startWaiting("提交本次操作");
        assertTrue(answer("task-a", request.token, false));
        finishWaiting();
        assertFalse(waiting.get(1, TimeUnit.SECONDS));
        assertTrue(run.cancelled.get());
        assertNull(pending());
        assertFalse(answer("task-a", request.token, true));
    }

    @Test public void anotherConfirmationCannotReplaceTheActionAlreadyAwaitingUserDecision() throws Exception {
        ActionConfirmation.Request request = startWaiting("提交第一项操作");
        try {
            ActionConfirmation.await("提交第二项操作", run);
            fail("Concurrent confirmation must not replace the pending action");
        } catch (IllegalStateException expected) {
            assertSame(request, pending());
        }
        assertTrue(answer("task-a", request.token, true));
        finishWaiting();
        assertTrue(waiting.get(1, TimeUnit.SECONDS));
    }

    @Test public void sixtySecondTimeoutCancelsTaskAndRemovesNotificationState() throws Exception {
        ActionConfirmation.Request request = startWaiting("提交本次操作");
        ShadowSystemClock.advanceBy(Duration.ofMillis(ActionConfirmation.TIMEOUT_MS));
        assertFalse(answer("task-a", request.token, true));
        finishWaiting();
        assertCancelledWait();
        assertTrue(run.cancelled.get());
        assertNull(pending());
        assertFalse(provider.call("screen_state", "task-a", null).containsKey("action_confirmation"));
    }

    @Test public void cancellationWhileWaitingStopsWithoutAuthorizingAction() throws Exception {
        ActionConfirmation.Request request = startWaiting("提交本次操作");
        run.cancel();
        assertFalse(answer("task-a", request.token, true));
        finishWaiting();
        assertCancelledWait();
        assertNull(request.answer());
        assertNull(pending());
    }

    @Test public void originalStepDeadlineStillAppliesWhileWaitingForConfirmation() throws Exception {
        run.deadline = SystemClock.elapsedRealtime() + 1_000;
        ActionConfirmation.Request request = startWaiting("提交本次操作");
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_000));
        assertFalse(answer("task-a", request.token, true));
        finishWaiting();
        assertCancelledWait();
        assertTrue(run.cancelled.get());
        assertNull(pending());
    }

    @Test public void fullOperationTextIsPublishedOnlyToItsTaskAndOwner() throws Exception {
        String message = "操作".repeat(1_000);
        ActionConfirmation.Request request = startWaiting(message);
        Bundle state = provider.call("screen_state", "task-a", null);
        assertEquals(message, state.getString("confirmation_message"));
        assertEquals(request.token, state.getString("action_confirmation"));
        Bundle otherOwner = new Bundle();
        ActionConfirmation.describe(otherOwner, "task-a", run.owner + 1);
        assertTrue(otherOwner.isEmpty());
        assertFalse(provider.call("screen_state", "task-b", null).containsKey("confirmation_message"));
        assertTrue(answer("task-a", request.token, true));
        finishWaiting();
        assertTrue(waiting.get(1, TimeUnit.SECONDS));
    }

    @Test public void emptyOrOverlongOperationTextIsRejectedInsteadOfTruncated() throws Exception {
        for (String message : new String[]{null, "", "   ", "操".repeat(2_001)}) {
            try {
                ActionConfirmation.await(message, run);
                fail("Invalid operation text must not enter confirmation wait");
            } catch (IllegalArgumentException expected) {
                assertNull(pending());
                assertFalse(run.cancelled.get());
            }
        }
    }

    @Test public void standaloneSettingsTaskPausesInsteadOfWaitingForAnUnobservedIsland() throws Exception {
        ScreenService service = new ScreenService();
        try {
            service.confirm("提交本次操作", run);
            fail("Standalone tests cannot publish host-UID confirmations");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().startsWith("PAUSE："));
            assertTrue(expected.getMessage().contains("从小爱对话"));
            assertNull(pending());
        }
    }

    private ActionConfirmation.Request request() {
        return new ActionConfirmation.Request("task-a", run, "提交本次操作", SystemClock.elapsedRealtime());
    }

    private ActionConfirmation.Request startWaiting(String message) throws Exception {
        waiting = new FutureTask<>(() -> ActionConfirmation.await(message, run));
        Thread worker = new Thread(waiting, "action-confirmation-test");
        worker.setDaemon(true);
        worker.start();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        ActionConfirmation.Request request;
        while ((request = pending()) == null && !waiting.isDone() && System.nanoTime() < until) Thread.sleep(5);
        assertNotNull("Confirmation must become visible while the caller waits", request);
        return request;
    }

    private boolean answer(String task, String token, boolean allow) {
        Bundle extras = new Bundle();
        extras.putString("token", token);
        extras.putBoolean("allow", allow);
        return provider.call("action_confirmation", task, extras).getBoolean("accepted");
    }

    private void finishWaiting() throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!waiting.isDone() && System.nanoTime() < until) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(100));
            Thread.sleep(5);
        }
        assertTrue("Confirmation waiter must terminate", waiting.isDone());
    }

    private void assertCancelledWait() throws Exception {
        try { waiting.get(1, TimeUnit.SECONDS); fail("Cancelled confirmation must not complete successfully"); }
        catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof CancellationException); }
    }

    private static ActionConfirmation.Request pending() throws Exception {
        synchronized (ActionConfirmation.class) {
            return (ActionConfirmation.Request) field(ActionConfirmation.class, "pending").get(null);
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
