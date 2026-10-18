package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class BridgeProviderContinuationTest {
    private BridgeProvider provider;
    private Map<String, RunState> jobs;
    private RunState completed;

    @Before public void setUp() throws Exception {
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public String getPackageCodePath() { return ""; }
        };
        Config.prefs(context).edit().clear().putBoolean("enabled", true)
            .putString("planner", "native").commit();
        provider = new BridgeProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = "io.github.miaiplus.bridge";
        provider.attachInfo(context, info);
        jobs = jobs();
        resetState();
        completed = new RunState("finished-job", android.os.Process.myUid());
        completed.screen = true;
        completed.status = "error";
        completed.result = PhoneUseResult.stopped("达到最大步数");
        jobs.put(completed.id, completed);
        field(BridgeProvider.class, "screenTaskId").set(null, "finished-task");
        field(BridgeProvider.class, "screenResult").set(null, completed.result);
    }

    @After public void tearDown() throws Exception { resetState(); }

    @Test public void repeatedContinuationAfterTerminalDoesNotReopenTaskOrOverwriteResult() throws Exception {
        Bundle before = provider.call("screen_state", null, null);
        assertTrue(before.getBoolean("completed"));
        for (int attempt = 0; attempt < 5; attempt++) {
            Bundle result = start(query("expired-session", ""));
            assertTerminalRejection(result);
            assertNull(field(BridgeProvider.class, "active").get(null));
            assertNull(field(NativeSession.class, "active").get(null));
            assertUnchangedTask(before);
            assertSame(completed, jobs.get(completed.id));
            assertEquals(1, jobs.size());
        }
    }

    @Test public void wrongSessionOwnerOrToolCannotReserveJobOrDisturbCurrentSession() throws Exception {
        int owner = android.os.Process.myUid();
        for (String mismatch : new String[]{"session", "owner", "tool"}) {
            Object session = session(mismatch.equals("owner") ? owner + 1 : owner);
            RunState run = (RunState) field(session.getClass(), "run").get(session);
            String id = (String) field(session.getClass(), "id").get(session);
            field(NativeSession.class, "active").set(null, session);
            field(BridgeProvider.class, "screenTaskId").set(null, "current-task");
            field(BridgeProvider.class, "screenTaskActive").setBoolean(null, true);
            field(BridgeProvider.class, "screenResult").set(null, "last observation");
            Bundle before = provider.call("screen_state", null, null);
            long deadline = run.deadline;
            for (int attempt = 0; attempt < 3; attempt++) {
                String source = mismatch.equals("tool") ? PhoneUseRouting.BACKGROUND_TOOL : PhoneUseRouting.FOREGROUND_TOOL;
                Bundle result = start(query(mismatch.equals("session") ? "expired-session" : id, source));
                assertTerminalRejection(result);
                assertSame(session, field(NativeSession.class, "active").get(null));
                assertNull(field(BridgeProvider.class, "active").get(null));
                assertFalse(run.cancelled.get());
                assertEquals(deadline, run.deadline);
                assertEquals(0, field(session.getClass(), "steps").getInt(session));
                assertUnchangedTask(before);
                assertEquals(1, jobs.size());
                assertSame(completed, jobs.get(completed.id));
            }
        }
    }

    @Test public void validContinuationPreflightDoesNotConsumeActionOrRenewDeadline() throws Exception {
        int owner = android.os.Process.myUid();
        Object session = session(owner);
        RunState run = (RunState) field(session.getClass(), "run").get(session);
        String id = (String) field(session.getClass(), "id").get(session);
        field(NativeSession.class, "active").set(null, session);
        long deadline = run.deadline;
        for (int attempt = 0; attempt < 3; attempt++) {
            NativeSession.verifyContinuation(query(id, PhoneUseRouting.FOREGROUND_TOOL), owner,
                PhoneUseRouting.FOREGROUND_TOOL);
            assertSame(session, field(NativeSession.class, "active").get(null));
            assertFalse(run.cancelled.get());
            assertEquals(deadline, run.deadline);
            assertEquals(0, field(session.getClass(), "steps").getInt(session));
            assertNull(field(BridgeProvider.class, "active").get(null));
            assertEquals(1, jobs.size());
        }
    }

    @Test public void modelExhaustionForMissingOrReplacedTaskCannotCancelCurrentSession() throws Exception {
        Object session = activateSession("current-task");
        RunState run = (RunState) field(session.getClass(), "run").get(session);
        Bundle before = provider.call("screen_state", null, null);
        for (String task : new String[]{null, "", "finished-task", "other-task"}) {
            assertFalse(provider.call("model_exhausted", task, null).getBoolean("accepted"));
            assertSame(session, field(NativeSession.class, "active").get(null));
            assertFalse(run.cancelled.get());
            assertUnchangedTask(before);
            assertEquals("", field(BridgeProvider.class, "screenStopReason").get(null));
            assertEquals(1, jobs.size());
        }
    }

    @Test public void matchingModelExhaustionClosesSessionAndPreservesPreciseTerminalReasonAcrossRetries() throws Exception {
        Object session = activateSession("current-task");
        RunState run = (RunState) field(session.getClass(), "run").get(session);

        assertTrue(provider.call("model_exhausted", "current-task", null).getBoolean("accepted"));
        assertTrue(run.cancelled.get());
        ((ExecutorService) field(NativeSession.class, "CLEANUP").get(null)).submit(() -> { }).get(5, TimeUnit.SECONDS);

        assertNull(field(NativeSession.class, "active").get(null));
        Bundle completedState = provider.call("screen_state", "current-task", null);
        assertFalse(completedState.getBoolean("active"));
        assertTrue(completedState.getBoolean("completed"));
        String result = completedState.getString("result", "");
        assertTrue(result, PhoneUseResult.endsNativeTurn(result));
        assertTrue(result, result.contains("小爱主模型已达到最大执行轮次"));
        assertTrue(result, result.contains("任务未确认完成"));
        assertEquals("current-task", completedState.getString("screen_task"));
        for (int attempt = 0; attempt < 3; attempt++) {
            assertFalse(provider.call("model_exhausted", "current-task", null).getBoolean("accepted"));
            assertUnchangedTask(completedState);
            assertEquals(1, jobs.size());
        }
    }

    @Test public void lateModelExhaustionDoesNotReplaceAnAlreadyCompletedTaskResult() throws Exception {
        Bundle before = provider.call("screen_state", null, null);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertFalse(provider.call("model_exhausted", "finished-task", null).getBoolean("accepted"));
            assertUnchangedTask(before);
            assertEquals(completed.result, provider.call("screen_state", null, null).getString("result"));
            assertNull(field(NativeSession.class, "active").get(null));
            assertEquals("", field(BridgeProvider.class, "screenStopReason").get(null));
        }
    }

    @Test public void delayedHostCleanupCannotCancelChatAfterScreenExecutionHasExited() throws Exception {
        // The notification observer has not refreshed the old screen flag yet, but a chat is now running.
        field(BridgeProvider.class, "screenTaskActive").setBoolean(null, true);
        RunState chat = new RunState("unrelated-chat", android.os.Process.myUid());
        field(BridgeProvider.class, "active").set(null, chat);
        for (String method : new String[]{"model_exhausted", "model_finished"}) {
            assertFalse(provider.call(method, "finished-task", null).getBoolean("accepted"));
            assertFalse(chat.cancelled.get());
            assertSame(chat, field(BridgeProvider.class, "active").get(null));
            assertEquals(completed.result, field(BridgeProvider.class, "screenResult").get(null));
        }
    }

    private Object activateSession(String taskId) throws Exception {
        Object session = session(android.os.Process.myUid());
        field(NativeSession.class, "active").set(null, session);
        field(BridgeProvider.class, "screenTaskId").set(null, taskId);
        field(BridgeProvider.class, "screenTaskActive").setBoolean(null, true);
        field(BridgeProvider.class, "screenResult").set(null, "CONTINUE：最后一次观察");
        return session;
    }

    private Bundle start(String query) {
        Bundle request = new Bundle();
        request.putString("mode", "screen");
        request.putString("query", query);
        request.putBoolean("screen_ui", true);
        return provider.call("start", null, request);
    }

    private static String query(String id, String tool) throws Exception {
        JSONObject request = new JSONObject().put("session", id)
            .put("action", new JSONObject().put("action", "wait"));
        if (!tool.isEmpty()) request.put("origin_tool", tool);
        return "MIAIPLUS:" + request;
    }

    private static Object session(int owner) throws Exception {
        Class<?> type = Class.forName(NativeSession.class.getName() + "$State");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class, int.class,
            ScreenService.class, boolean.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance("test", owner, 20, null, false, PhoneUseRouting.FOREGROUND_TOOL);
    }

    private void assertUnchangedTask(Bundle before) {
        Bundle after = provider.call("screen_state", null, null);
        assertEquals(before.getString("screen_task"), after.getString("screen_task"));
        assertEquals(before.getString("result"), after.getString("result"));
        assertEquals(before.getBoolean("active"), after.getBoolean("active"));
        assertEquals(before.getBoolean("completed"), after.getBoolean("completed"));
    }

    private static void assertTerminalRejection(Bundle result) {
        assertTrue(result.getString("error", ""), PhoneUseResult.endsNativeTurn(result.getString("error")));
        assertFalse(result.containsKey("id"));
        assertFalse(result.containsKey("screen_task"));
    }

    @SuppressWarnings("unchecked") private static Map<String, RunState> jobs() throws Exception {
        return (Map<String, RunState>) field(BridgeProvider.class, "JOBS").get(null);
    }

    private static void resetState() throws Exception {
        jobs().clear();
        field(BridgeProvider.class, "backgroundProbeRunning").setBoolean(null, false);
        field(BridgeProvider.class, "active").set(null, null);
        field(BridgeProvider.class, "screenTaskId").set(null, null);
        field(BridgeProvider.class, "screenTaskActive").setBoolean(null, false);
        field(BridgeProvider.class, "screenResult").set(null, "");
        field(BridgeProvider.class, "screenStopReason").set(null, "");
        field(NativeSession.class, "active").set(null, null);
        field(NativeSession.class, "executing").setBoolean(null, false);
        field(NativeSession.class, "closing").setBoolean(null, false);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
