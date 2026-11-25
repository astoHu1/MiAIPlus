package io.github.miaiplus;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * A fake Eta sits behind the Link seam. Most cases drive the relay internals (receive, await, finish, lost) by
 * reflection against a Run registered the way run() registers it; the run() entry itself is covered through
 * EtaAgent's signer seam, with the fake answering on the reply Messenger like the real service.
 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class EtaBridgeTest {
    private Context context;
    private HandlerThread eta;
    private final BlockingQueue<int[]> sentWhat = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> sentRunIds = new LinkedBlockingQueue<>();
    private final AtomicInteger closed = new AtomicInteger();
    private final AtomicInteger opened = new AtomicInteger();
    private EtaBridge.Connector previousConnector;
    private long previousAdmit;
    private EtaBridge.Link fakeLink;
    private Runnable lostCallback;
    /** Plays Eta's answer to MSG_START_RUN on the run's own reply Messenger. */
    private volatile java.util.function.Consumer<Messenger> onStart;
    /** Plays Eta's pending-result page for MSG_DRAIN_RESULTS. */
    private volatile java.util.function.Consumer<Messenger> onDrain;
    private long previousCancelGrace;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Config.prefs(context).edit().clear().commit();
        previousConnector = EtaBridge.connector;
        previousAdmit = EtaBridge.admitMs;
        previousCancelGrace = EtaBridge.cancelGraceMs;
        eta = new HandlerThread("fake-eta"); eta.start();
        Messenger service = new Messenger(new Handler(eta.getLooper(), message -> {
            sentWhat.add(new int[]{message.what});
            sentRunIds.add(message.getData().getString("run_id", ""));
            if (message.what == EtaBridge.MSG_START_RUN && onStart != null) onStart.accept(message.replyTo);
            if (message.what == EtaBridge.MSG_DRAIN_RESULTS && onDrain != null) onDrain.accept(message.replyTo);
            return true;
        }));
        fakeLink = new EtaBridge.Link() {
            @Override public Messenger service() { return service; }
            @Override public void close() { closed.incrementAndGet(); }
        };
        EtaBridge.connector = (c, lost) -> { opened.incrementAndGet(); lostCallback = lost; return fakeLink; };
        EtaBridge.dispose();
    }

    @After public void tearDown() {
        EtaBridge.connector = previousConnector;
        EtaBridge.admitMs = previousAdmit;
        EtaBridge.cancelGraceMs = previousCancelGrace;
        EtaAgent.signer = EtaAgent.SIGNER_SHA256;
        EtaBridge.dispose();
        eta.quitSafely();
    }

    @Test public void runRejectsMissingBlankAndOversizeTasksBeforeAnyConnection() throws Exception {
        for (String json : new String[]{"{}", "{\"task\":\"   \"}"}) {
            EtaBridge.Outcome outcome = EtaBridge.run(context, new JSONObject(json), false);
            assertFalse(outcome.observation());
            assertTrue(outcome.text().contains("缺少 task"));
        }
        EtaBridge.Outcome big = EtaBridge.run(context,
            new JSONObject().put("task", "x".repeat(EtaBridge.MAX_TASK_CHARS + 1)), false);
        assertFalse(big.observation());
        assertTrue(big.text().contains("8000"));
        assertEquals(0, opened.get());
    }

    @Test public void runRefusesWithoutAnInstalledTrustedEtaAndNeverConnects() throws Exception {
        EtaBridge.Outcome outcome = EtaBridge.run(context, new JSONObject().put("task", "整理通知"), false);
        assertFalse(outcome.observation());
        assertTrue(outcome.text().contains("未安装 Eta"));
        assertTrue(outcome.text().contains("不要自动重试"));
        assertEquals(0, opened.get());
        assertFalse(EtaBridge.busy());
    }

    @Test public void runSendsTheRequestToEtaAndReturnsItsAnswer() throws Exception {
        EtaAgentTest.installReadyEta(context);
        onStart = reply -> {
            try {
                reply.send(ingested());
                reply.send(tool("tool_started", "calendar_read"));
                reply.send(result(true, "本周有 2 个日程", ""));
            } catch (android.os.RemoteException e) { throw new AssertionError(e); }
        };
        EtaBridge.Outcome outcome = EtaBridge.run(context, new JSONObject().put("task", "汇总本周日程"), false);
        assertTrue(outcome.text(), outcome.observation());
        assertTrue(outcome.text().startsWith("DONE："));
        assertTrue(outcome.text().contains("本周有 2 个日程"));
        assertTrue(outcome.text().contains("工具调用 1 次"));
        assertEquals(EtaBridge.MSG_DRAIN_RESULTS, nextSent());
        assertEquals(EtaBridge.MSG_START_RUN, nextSent());
        assertEquals(EtaBridge.MSG_ACK_RESULT, nextSent());
        assertEquals("", sentRunIds.poll(1, TimeUnit.SECONDS));
        assertEquals(sentRunIds.poll(1, TimeUnit.SECONDS), sentRunIds.poll(1, TimeUnit.SECONDS));
        assertFalse(EtaBridge.busy());
        assertEquals(1, closed.get());
    }

    @Test public void newConnectionAcknowledgesOnlyThisRelaysLeftoverResults() throws Exception {
        EtaAgentTest.installReadyEta(context);
        onDrain = reply -> {
            ArrayList<Bundle> page = new ArrayList<>();
            page.add(pending("old-1", "eta_voice", "miaiplus:old-1"));
            page.add(pending("theirs-1", "eta_voice", "overlay:42"));
            page.add(pending("theirs-2", "agent_ui", "miaiplus:not-ours"));
            Bundle data = new Bundle(); data.putParcelableArrayList("results", page);
            Message response = Message.obtain(null, EtaBridge.MSG_DRAIN_RESULTS_RESPONSE);
            response.setData(data);
            try { reply.send(response); } catch (android.os.RemoteException e) { throw new AssertionError(e); }
        };
        onStart = reply -> {
            try { reply.send(ingested()); reply.send(result(true, "完成", "")); }
            catch (android.os.RemoteException e) { throw new AssertionError(e); }
        };
        assertTrue(EtaBridge.run(context, new JSONObject().put("task", "任务"), false).text().startsWith("DONE："));
        java.util.List<String> acknowledged = new ArrayList<>();
        String own = "";
        for (int[] what; (what = sentWhat.poll(1, TimeUnit.SECONDS)) != null;) {
            String id = sentRunIds.poll(1, TimeUnit.SECONDS);
            if (what[0] == EtaBridge.MSG_START_RUN) own = id;
            if (what[0] == EtaBridge.MSG_ACK_RESULT) acknowledged.add(id);
        }
        assertEquals(2, acknowledged.size());
        assertTrue(acknowledged.contains("old-1"));
        assertTrue(acknowledged.contains(own));
    }

    @Test public void interruptedConnectionAttemptStopsWithoutBlamingEtaAndKeepsTheInterrupt() throws Exception {
        EtaAgentTest.installReadyEta(context);
        EtaBridge.connector = (c, lost) -> { throw new InterruptedException(); };
        EtaBridge.Outcome outcome = EtaBridge.run(context, new JSONObject().put("task", "任务"), false);
        assertTrue(Thread.interrupted());
        assertFalse(outcome.observation());
        assertTrue(outcome.text().contains("调用被中断"));
        assertFalse(EtaBridge.busy());
    }

    @Test public void cancelledRunThatEtaNeverAnswersStopsHoldingLaterTasksAndReloads() throws Exception {
        EtaBridge.cancelGraceMs = 100;
        Object run = register();
        receive(run, ingested());
        assertEquals(Boolean.TRUE, call("requestCancel", new Class<?>[]{runClass()}, run));
        assertTrue(EtaBridge.busy());
        Thread.sleep(200);
        assertFalse(EtaBridge.busy());
        EtaBridge.Outcome outcome = EtaBridge.status(new JSONObject().put("run_id", id(run)).put("wait_seconds", 0));
        assertFalse(outcome.observation());
        assertTrue(outcome.text().startsWith("STOP："));
        assertTrue(outcome.text().contains("没有确认"));
    }

    @Test public void runRefusesOnTheLockScreenWithoutConnecting() throws Exception {
        EtaAgentTest.installReadyEta(context);
        shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        EtaBridge.Outcome outcome = EtaBridge.run(context, new JSONObject().put("task", "汇总本周日程"), false);
        assertFalse(outcome.observation());
        assertTrue(outcome.text().contains("锁屏"));
        assertEquals(0, opened.get());
    }

    @Test public void secondRunWhileOneIsActiveIsRefusedAsBusyAndNotSent() throws Exception {
        EtaAgentTest.installReadyEta(context);
        AtomicReference<Messenger> replyTo = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        onStart = reply -> {
            replyTo.set(reply);
            try { reply.send(ingested()); } catch (android.os.RemoteException e) { throw new AssertionError(e); }
            started.countDown();
        };
        AtomicReference<EtaBridge.Outcome> first = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try { first.set(EtaBridge.run(context, new JSONObject().put("task", "第一个任务"), false)); }
            catch (org.json.JSONException e) { throw new AssertionError(e); }
        });
        caller.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        EtaBridge.Outcome second = EtaBridge.run(context, new JSONObject().put("task", "第二个任务"), false);
        assertTrue(second.observation());
        assertTrue(second.text().startsWith("BUSY："));
        replyTo.get().send(result(true, "完成", ""));
        caller.join(5000);
        assertTrue(first.get().text().startsWith("DONE："));
        assertEquals(EtaBridge.MSG_DRAIN_RESULTS, nextSent());
        assertEquals(EtaBridge.MSG_START_RUN, nextSent());
        assertEquals(EtaBridge.MSG_ACK_RESULT, nextSent());
        assertTrue(sentWhat.isEmpty());
    }

    @Test public void startRequestCarriesTaskBriefHandoffAndPrivilegedFlagsFromTheArgument() throws Exception {
        Bundle data = EtaBridge.startRequest("run-1", "整理  本周\n通知", false);
        assertEquals("run-1", data.getString("run_id"));
        assertTrue(data.getString("prompt").contains("[委派任务]"));
        assertTrue(data.getString("prompt").endsWith("整理  本周\n通知"));
        assertEquals("chat", data.getString("operation"));
        assertTrue(data.getBoolean("browser_tools"));
        assertTrue(data.getBoolean("device_direct_tools"));
        assertTrue(data.getParcelableArrayList("history").isEmpty());
        assertTrue(data.getParcelableArrayList("images").isEmpty());
        Bundle handoff = data.getBundle("handoff");
        assertEquals("run-1", handoff.getString("handoff_id"));
        assertEquals("eta_voice", handoff.getString("handoff_source"));
        assertFalse(handoff.getBoolean("handoff_dismiss_entry_surface_on_foreground_operation", true));
        JSONObject payload = new JSONObject(handoff.getString("handoff_payload"));
        assertEquals("external_archive", payload.getString("type"));
        assertEquals(1, payload.getInt("version"));
        assertEquals("整理  本周\n通知", payload.getString("userText"));
        assertEquals("miaiplus:run-1", payload.getString("conversationKey"));
        assertEquals("整理 本周 通知", payload.getString("title"));
        for (boolean privileged : new boolean[]{false, true}) {
            Bundle flags = EtaBridge.startRequest("r", "t", privileged);
            assertEquals(privileged, flags.getBoolean("terminal_tools"));
            assertEquals(privileged, flags.getBoolean("device_sensitive_read_tools"));
            assertEquals(privileged, flags.getBoolean("device_sensitive_action_tools"));
        }
        String title = new JSONObject(EtaBridge.startRequest("r", "长".repeat(500), false).getBundle("handoff")
            .getString("handoff_payload")).getString("title");
        assertEquals(40, title.length());
    }

    @Test public void statusForAnUnknownRunFailsWithoutContactingEta() throws Exception {
        EtaBridge.Outcome outcome = EtaBridge.status(new JSONObject().put("run_id", "nope").put("cancel", true));
        assertFalse(outcome.observation());
        assertTrue(outcome.text().contains("没有找到这个 Eta 任务"));
        assertEquals(0, opened.get());
        assertTrue(sentWhat.isEmpty());
        assertFalse(EtaBridge.status(new JSONObject()).observation());
    }

    @Test public void failedResultReturnsStopWithEtasErrorAndIgnoresALateResult() throws Exception {
        Object run = register();
        Thread feeder = feed(run, 50, ingested(), result(false, "", "模型额度不足"));
        EtaBridge.Outcome outcome = await(run, 5000);
        feeder.join();
        assertFalse(outcome.observation());
        assertTrue(outcome.text().startsWith("STOP："));
        assertTrue(outcome.text().contains("模型额度不足"));
        assertTrue(outcome.text().contains("不要自动重试"));
        // A late result after the terminal state must not overwrite it.
        receive(run, result(true, "第二次", ""));
        String text = await(run, 100).text();
        assertTrue(text.startsWith("STOP："));
        assertTrue(text.contains("模型额度不足"));
    }

    @Test public void silenceBeyondAdmissionWindowStopsTheRunAndRequestsCancel() throws Exception {
        EtaBridge.admitMs = 300;
        Object run = register();
        EtaBridge.Outcome outcome = await(run, 10_000);
        assertFalse(outcome.observation());
        assertTrue(outcome.text().startsWith("STOP："));
        assertTrue(outcome.text().contains("没有接收"));
        assertEquals(EtaBridge.MSG_CANCEL, nextSent());
        assertEquals(id(run), sentRunIds.poll(1, TimeUnit.SECONDS));
        assertFalse(EtaBridge.busy());
    }

    @Test public void waitTimeoutReturnsRunningThenStatusReturnsDoneAfterTheResult() throws Exception {
        Object run = register();
        receive(run, ingested());
        receive(run, tool("tool_started", "calendar_write"));
        receive(run, tool("hosted_tool_started", "web_search"));
        EtaBridge.Outcome running = await(run, 0);
        assertTrue(running.observation());
        assertTrue(running.text().startsWith("RUNNING："));
        assertTrue(running.text().contains(id(run)));
        assertTrue(running.text().contains("calendar_write、web_search"));
        assertTrue(EtaBridge.busy());
        Thread feeder = feed(run, 100, result(true, "已完成", ""));
        EtaBridge.Outcome done = EtaBridge.status(new JSONObject().put("run_id", id(run)).put("wait_seconds", 10));
        feeder.join();
        assertTrue(done.text().startsWith("DONE："));
        assertFalse(EtaBridge.busy());
        // A finished run stays queryable and still reports its result.
        assertTrue(EtaBridge.status(new JSONObject().put("run_id", id(run))).text().startsWith("DONE："));
    }

    @Test public void statusWithoutRunIdMeansTheMostRecentRun() throws Exception {
        Object first = register();
        receive(first, result(true, "一", ""));
        Object second = register();
        receive(second, ingested());
        receive(second, result(true, "二", ""));
        EtaBridge.Outcome outcome = EtaBridge.status(new JSONObject());
        assertTrue(outcome.text().contains(id(second)));
        assertTrue(outcome.text().contains("二"));
    }

    @Test public void cancelRequestSendsMsgCancelWithTheRunIdAndWaitsForTheEnd() throws Exception {
        Object run = register();
        receive(run, ingested());
        Thread feeder = feed(run, 300, result(false, "", "已取消"));
        EtaBridge.Outcome outcome = EtaBridge.status(new JSONObject().put("run_id", id(run)).put("cancel", true));
        feeder.join();
        assertEquals(EtaBridge.MSG_CANCEL, nextSent());
        assertEquals(id(run), sentRunIds.poll(1, TimeUnit.SECONDS));
        assertTrue(outcome.text().startsWith("STOP："));
    }

    @Test public void interruptedWaitRequestsCancelAndReturnsAFailure() throws Exception {
        Object run = register();
        receive(run, ingested());
        AtomicReference<EtaBridge.Outcome> outcome = new AtomicReference<>();
        AtomicReference<Boolean> flag = new AtomicReference<>();
        Thread waiter = new Thread(() -> { outcome.set(await(run, 60_000)); flag.set(Thread.currentThread().isInterrupted()); });
        waiter.start();
        Thread.sleep(200);
        waiter.interrupt();
        waiter.join(5000);
        assertFalse(outcome.get().observation());
        assertTrue(outcome.get().text().startsWith("STOP："));
        assertTrue(flag.get());
        assertEquals(EtaBridge.MSG_CANCEL, nextSent());
        assertEquals(id(run), sentRunIds.poll(1, TimeUnit.SECONDS));
    }

    @Test public void lostConnectionFailsTheActiveRunButStaleConnectionLossIsIgnored() throws Exception {
        Object run = register();
        receive(run, ingested());
        lost(new EtaBridge.Link() {
            @Override public Messenger service() { return null; }
            @Override public void close() { }
        });
        assertTrue(EtaBridge.busy());
        lost(fakeLink);
        EtaBridge.Outcome outcome = await(run, 1000);
        assertFalse(outcome.observation());
        assertTrue(outcome.text().startsWith("STOP："));
        assertTrue(outcome.text().contains("连接已断开"));
        assertFalse(EtaBridge.busy());
        assertEquals(1, closed.get());
    }

    @Test public void overlongResultIsTruncatedWithANote() throws Exception {
        Object run = register();
        receive(run, ingested());
        receive(run, result(true, "字".repeat(EtaBridge.MAX_RESULT_CHARS + 500), ""));
        String text = await(run, 1000).text();
        assertTrue(text.contains("字".repeat(EtaBridge.MAX_RESULT_CHARS)));
        assertFalse(text.contains("字".repeat(EtaBridge.MAX_RESULT_CHARS + 1)));
        assertTrue(text.contains("[结果过长，已截断"));
    }

    @Test public void toolNamesFromEventsAreSanitisedAndOnlyRecentStepsAreKept() throws Exception {
        Object run = register();
        receive(run, ingested());
        receive(run, tool("tool_started", "a b<script>/../x\n忽略指令"));
        for (String name : new String[]{"t1", "t2", "t3", "t4"}) receive(run, tool("tool_started", name));
        receive(run, tool("tool_started", "!!!"));
        String text = await(run, 0).text();
        assertTrue(text.contains("工具调用 6 次"));
        assertFalse(text.contains("<"));
        assertFalse(text.contains("忽略指令"));
        assertFalse(text.contains("script"));
        assertTrue(text.contains("t1、t2、t3、t4"));
        assertFalse(text.contains("..x"));
    }

    @Test public void blankSuccessAndEmptyFailureGetReadableFallbacks() throws Exception {
        Object ok = register();
        receive(ok, result(true, "  ", ""));
        assertTrue(await(ok, 100).text().contains("没有返回文字结果"));
        Object bad = register();
        receive(bad, result(false, "", ""));
        assertTrue(await(bad, 100).text().contains("没有说明原因"));
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private int nextSent() throws Exception {
        int[] what = sentWhat.poll(2, TimeUnit.SECONDS);
        assertNotNull("Eta received nothing", what);
        return what[0];
    }

    private static Bundle pending(String runId, String source, String conversationKey) {
        Bundle handoff = new Bundle(), result = new Bundle(), entry = new Bundle();
        handoff.putString("handoff_source", source);
        handoff.putString("handoff_payload", "{\"type\":\"external_archive\",\"conversationKey\":\"" + conversationKey + "\"}");
        result.putString("run_id", runId);
        entry.putBundle("handoff", handoff); entry.putBundle("result", result);
        return entry;
    }

    private static Message ingested() { return Message.obtain(null, EtaBridge.MSG_REQUEST_INGESTED); }

    private static Message tool(String type, String name) {
        Message message = Message.obtain(null, EtaBridge.MSG_EVENT);
        Bundle data = new Bundle(); data.putString("type", type); data.putString("name", name);
        message.setData(data);
        return message;
    }

    private static Message result(boolean ok, String content, String error) {
        Message message = Message.obtain(null, EtaBridge.MSG_RESULT);
        Bundle data = new Bundle();
        data.putBoolean("ok", ok); data.putString("content", content); data.putString("error", error);
        message.setData(data);
        return message;
    }

    private Thread feed(Object run, long delayMs, Message... messages) {
        Thread thread = new Thread(() -> {
            try { Thread.sleep(delayMs); for (Message message : messages) receive(run, message); }
            catch (InterruptedException ignored) { }
        });
        thread.start();
        return thread;
    }

    private static Class<?> runClass() throws Exception { return Class.forName("io.github.miaiplus.EtaBridge$Run"); }

    private static Field field(Class<?> type, String name) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /** Registers a run exactly like run() does, with the fake link already open (as after connect()). */
    @SuppressWarnings("unchecked")
    private Object register() throws Exception {
        Constructor<?> constructor = runClass().getDeclaredConstructor();
        constructor.setAccessible(true);
        Object run = constructor.newInstance();
        ((Map<String, Object>)field(EtaBridge.class, "RUNS").get(null)).put(id(run), run);
        field(EtaBridge.class, "link").set(null, fakeLink);
        return run;
    }

    private static String id(Object run) throws Exception { return (String)field(runClass(), "id").get(run); }

    private static Object call(String name, Class<?>[] types, Object... args) {
        try {
            Method method = EtaBridge.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static EtaBridge.Outcome await(Object run, long waitMs) {
        try { return (EtaBridge.Outcome)call("await", new Class<?>[]{runClass(), long.class}, run, waitMs); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private static void receive(Object run, Message message) {
        try { call("receive", new Class<?>[]{runClass(), Message.class}, run, message); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private static void lost(EtaBridge.Link dead) { call("lost", new Class<?>[]{EtaBridge.Link.class}, dead); }
}
