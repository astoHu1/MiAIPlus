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
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
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
    private static final String BUTTON = "android.widget.Button [100,100][300,300] enabled=true clickable text=发送\n";
    private BridgeProvider provider;
    private RunState run;
    private FutureTask<Boolean> waiting;

    @Before public void setUp() throws Exception {
        field(ActionConfirmation.class, "pending").set(null, null);
        run = new RunState("step-a", Process.myUid());
        run.screen = true;
        field(BridgeProvider.class, "active").set(null, run);
        field(BridgeProvider.class, "screenTaskId").set(null, "task-a");
        field(BridgeProvider.class, "screenTaskActive").setBoolean(null, true);
        provider = new BridgeProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = BridgeProvider.URI.getAuthority();
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

    @Test public void upstreamParsingPreservesCoordinatesLiteralTextAndTerminalActions() throws Exception {
        Action tap = Action.parseAutoGlm("<think>点击搜索</think><answer>do(action=\"Tap\", element=[500, 125])</answer>");
        assertEquals("tap", tap.name); assertArrayEquals(new double[]{500,125}, tap.point("element"), .001);
        assertEquals("finish", Action.parseAutoGlm("finish(message=\"已打开\")").name);
        Action a = Action.parseAutoGlm("do(action=\"Tap\", element=[1,999], message=\"发送消息\")");
        assertEquals("发送消息", a.data.getString("message"));
        assertEquals("take_over", Action.parseAutoGlm("do(action='Take_over', message='请登录')").name);
        for (String[] sample : new String[][]{
            {"do(action='Type', text='你好,世界（A=B）')", "你好,世界（A=B）"},
            {"do(action=\"Type\", text=\"a, [b] \\\"c\\\" = d\")", "a, [b] \"c\" = d"}
        }) {
            assertEquals(sample[0], sample[1], Action.parseAutoGlm(sample[0]).data.getString("text"));
        }
    }

    @Test public void invalidCoordinatesCodeOrUnknownActionAreRejected() throws Exception {
        for (String s : new String[]{
            "do(action='Tap', element=[-1,20])", "do(action='Tap', element=[1001,20])",
            "do(action='Tap', element=[1])", "do(action='Type', text=__import__('os').system('id'))",
            "do(action='Type', text='hello'); evil()", "do(action='Type', text='hello' + 'world')",
            "do(action='Tap', action='Home')", "do(action='Run', command='rm -rf /')",
            "do(action='Type', text='unterminated)", "do(action='Swipe', start=[1,2])"
        }) {
            try { Action.parseAutoGlm(s); fail("Accepted invalid action: " + s); }
            catch (IllegalArgumentException | org.json.JSONException expected) { }
        }
        assertEquals("swipe", new Action(new JSONObject("{\"action\":\"swipe\",\"start\":[0,1000],\"end\":[1000,0]}")).name);
        try { new Action(new JSONObject("{\"action\":\"launch\"}")); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void reviewIsBoundToTheConcreteTargetKeepsItsDecisionAndIsConsumedOnce() throws Exception {
        ActionPermissionReview review = new ActionPermissionReview();
        Action candidate = candidate();
        JSONObject challenge = review.issue(candidate, frame(BUTTON));
        assertTrue(review.pending());
        assertEquals(BUTTON, review.observation().tree());
        assertTrue(review.matches(candidate));
        assertEquals("normalized", challenge.getString("coordinate_space"));
        assertEquals("allow", review.consume(answerWith(candidate, challenge, "allow"), frame(BUTTON)));
        assertFalse(review.pending());
        assertNull(review.observation());
        assertEquals("ask_user", review.consume(answerWith(candidate, challenge, "allow"), frame(BUTTON)));
        for (String decision : new String[]{"allow", "ask_user", "deny"}) {
            ActionPermissionReview fresh = new ActionPermissionReview();
            Action action = candidate();
            assertEquals(decision, fresh.consume(answerWith(action, fresh.issue(action, frame(BUTTON)), decision), frame(BUTTON)));
        }
        ActionPermissionReview reordering = new ActionPermissionReview();
        Action original = candidate();
        JSONObject reorderedChallenge = reordering.issue(original, frame(BUTTON));
        Action reordered = new Action(new JSONObject().put("risk_level", "sensitive").put("message", original.data.getString("message"))
            .put("requires_confirmation", true).put("element", new JSONArray("[200.0,200]"))
            .put("action", "tap").put("intent", "发送草稿"));
        assertTrue(reordering.matches(reordered));
        assertEquals("allow", reordering.consume(answerWith(reordered, reorderedChallenge, "allow"), frame(BUTTON
            + "android.widget.TextView [700,0][1000,80] enabled=true text=新广告\n")));
    }

    @Test public void changesToCoordinatesMessageOrRiskCannotReuseApproval() throws Exception {
        for (String name : new String[]{"element", "message", "risk_level", "extra"}) {
            ActionPermissionReview review = new ActionPermissionReview();
            Action candidate = candidate();
            Action answer = answerWith(candidate, review.issue(candidate, frame(BUTTON)), "allow");
            answer.data.put(name, name.equals("element") ? new JSONArray("[250,200]") : "changed");
            assertFalse(name, review.matches(answer));
            assertEquals(name, "ask_user", review.consume(answer, frame(BUTTON)));
            assertFalse(review.pending());
        }
        ActionPermissionReview review = new ActionPermissionReview();
        Action candidate = candidate();
        JSONObject challenge = review.issue(candidate, frame(BUTTON));
        candidate.data.put("message", "发送另一段内容");
        assertFalse(review.matches(candidate));
        challenge.getJSONObject("action").put("message", "发送另一段内容");
        assertFalse(review.matches(candidate));
    }

    @Test public void missingMalformedForgedExpiredOrReplacedReviewFallsBackToUser() throws Exception {
        for (String scenario : new String[]{"missing", "wrong_token", "no_reason", "blank_reason", "long_reason", "bad_decision", "extra", "not_string"}) {
            ActionPermissionReview review = new ActionPermissionReview();
            Action candidate = candidate();
            Action answer = answerWith(candidate, review.issue(candidate, frame(BUTTON)), "allow");
            JSONObject data = answer.data.getJSONObject("permission_review");
            switch (scenario) {
                case "missing" -> answer.data.remove("permission_review");
                case "wrong_token" -> data.put("token", "forged");
                case "no_reason" -> data.remove("reason");
                case "blank_reason" -> data.put("reason", " \n ");
                case "long_reason" -> data.put("reason", "x".repeat(2001));
                case "bad_decision" -> data.put("decision", "approved");
                case "extra" -> data.put("other", true);
                case "not_string" -> data.put("reason", 42);
            }
            assertEquals(scenario, "ask_user", review.consume(answer, frame(BUTTON)));
            assertFalse(scenario, review.pending());
        }
        AtomicLong now = new AtomicLong(1_000);
        ActionPermissionReview timed = new ActionPermissionReview(now::get);
        Action candidate = candidate();
        JSONObject challenge = timed.issue(candidate, frame(BUTTON));
        now.addAndGet(ActionPermissionReview.TIMEOUT_NANOS);
        assertEquals("ask_user", timed.consume(answerWith(candidate, challenge, "allow"), frame(BUTTON)));
        JSONObject old = timed.issue(candidate, frame(BUTTON));
        JSONObject fresh = timed.issue(candidate, frame(BUTTON));
        assertNotEquals(old.getString("token"), fresh.getString("token"));
        assertEquals("ask_user", timed.consume(answerWith(candidate, old, "allow"), frame(BUTTON)));
    }

    @Test public void changedTargetDisplayAuthorizationTextOrSevereRiskCannotUseTheReview() throws Exception {
        Action candidate = candidate();
        for (ScreenService.Frame current : new ScreenService.Frame[]{frame(BUTTON.replace("发送", "删除")),
            new ScreenService.Frame("image", BUTTON, 1000, 1000, "app", 1000, 1000, 1, "background")}) {
            ActionPermissionReview review = new ActionPermissionReview();
            JSONObject challenge = review.issue(candidate, frame(BUTTON));
            assertEquals("ask_user", review.consume(answerWith(candidate, challenge, "allow"), current));
        }
        ActionPermissionReview review = new ActionPermissionReview();
        ScreenService.Frame old = new ScreenService.Frame("image", BUTTON
            + "android.widget.TextView [600,100][900,300] enabled=true text=获取相机权限\n",
            1000, 1000, "com.android.permissioncontroller", 1000, 1000, 0, "");
        ScreenService.Frame current = new ScreenService.Frame("image", old.tree().replace("获取相机权限", "获取麦克风权限"),
            1000, 1000, "com.android.permissioncontroller", 1000, 1000, 0, "");
        JSONObject challenge = review.issue(candidate, old);
        assertEquals("ask_user", review.consume(answerWith(candidate, challenge, "allow"), current));
        ActionPermissionReview severe = new ActionPermissionReview();
        Action critical = candidate();
        critical.data.put("risk_level", "critical");
        assertEquals("ask_user", severe.consume(answerWith(critical, severe.issue(critical, frame(BUTTON)), "allow"), frame(BUTTON)));
    }

    @Test public void eachDecisionRequiresMatchingOwnerTaskAndTokenAndCannotBeReplayedOrExpired() {
        for (boolean allow : new boolean[]{true, false}) {
            String scenario = "allow=" + allow;
            ActionConfirmation.Request request = request();
            long now = SystemClock.elapsedRealtime();
            assertFalse(scenario, request.respond("task-a", run.owner + 1, request.token, allow, now));
            assertFalse(scenario, request.respond("task-b", run.owner, request.token, allow, now));
            assertFalse(scenario, request.respond("task-a", run.owner, "old-token", allow, now));
            assertNull(scenario, request.answer());
            assertTrue(scenario, request.respond("task-a", run.owner, request.token, allow, now));
            assertFalse(scenario, request.respond("task-a", run.owner, request.token, !allow, now));
            assertEquals(scenario, Boolean.valueOf(allow), request.answer());
            assertNotEquals(scenario, request.token, request().token);
        }
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

    @Test public void providerApprovalUnblocksOnlyThePendingActionAndClearsIt() throws Exception {
        ActionConfirmation.Request request = startWaiting("发送这条消息");
        try {
            ActionConfirmation.await("提交第二项操作", run);
            fail("Concurrent confirmation must not replace the pending action");
        } catch (IllegalStateException expected) {
            assertSame(request, pending());
        }
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

    @Test public void operationTextFallsBackToIntentOrActionAndRejectsEmptyOrOverlongText() throws Exception {
        Action action = new Action(new JSONObject().put("action", "back")
            .put("requires_confirmation", true).put("message", " \n ").put("intent", "返回订单页"));
        assertEquals("返回订单页", ActionConfirmation.message(action));
        action.data.remove("intent");
        assertEquals("返回上一页", ActionConfirmation.message(action));
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

    private static Action candidate() throws Exception {
        return new Action(new JSONObject().put("action", "tap").put("element", new JSONArray("[200,200]"))
            .put("requires_confirmation", true).put("message", "发送当前草稿给指定联系人").put("risk_level", "sensitive"));
    }

    private static ScreenService.Frame frame(String tree) {
        return new ScreenService.Frame("image", tree, 1000, 1000, "app", 1000, 1000, 0, "");
    }

    private static Action answerWith(Action action, JSONObject challenge, String decision) throws Exception {
        Action copy = new Action(new JSONObject(action.data.toString()));
        copy.data.put("permission_review", new JSONObject().put("token", challenge.getString("token"))
            .put("decision", decision).put("reason", "用户明确要求给该联系人发送当前草稿"));
        return copy;
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
