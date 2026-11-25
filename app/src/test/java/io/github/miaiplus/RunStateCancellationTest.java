package io.github.miaiplus;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public final class RunStateCancellationTest {
    @Test public void interruptedIpcCancelsTheBoundNativeStepOnce() {
        RunState ipc = new RunState("ipc", 1000);
        RunState nativeStep = new RunState("session", 1000);
        AtomicInteger cleanup = new AtomicInteger();
        try (var ignored = ipc.onCancel(() -> { nativeStep.cancel(); cleanup.incrementAndGet(); })) {
            ipc.cancel();
            ipc.cancel();
            assertTrue(nativeStep.cancelled.get());
            assertEquals(1, cleanup.get());
        }
        // Cancellation before native session setup is not lost.
        RunState early = new RunState("ipc", 1000);
        RunState lateStep = new RunState("session", 1000);
        early.cancel();
        try (var ignored = early.onCancel(lateStep::cancel)) {
            assertTrue(lateStep.cancelled.get());
        }
    }

    @Test public void theFirstStopReasonIsVisibleToCleanupAndCannotBeReplaced() {
        RunState run = new RunState("cancel-source", 1000);
        AtomicInteger cleanup = new AtomicInteger();
        try (var ignored = run.onCancel(() -> {
            assertTrue(run.cancelled.get());
            assertEquals("user_stop", run.cancelSource());
            // A cleanup callback may itself reach another cancellation path.
            run.cancel("accessibility_disconnected");
            cleanup.incrementAndGet();
        })) {
            run.cancel("user_stop");
            run.cancel("step_watchdog");
            assertEquals("user_stop", run.cancelSource());
            assertEquals(1, cleanup.get());
        }
        // A later named stop must not invent a cause for an earlier unnamed one.
        RunState unnamed = new RunState("cancel-source", 1000);
        unnamed.cancel();
        unnamed.cancel("step_watchdog");
        assertTrue(unnamed.cancelled.get());
        assertEquals("", unnamed.cancelSource());
    }

    @Test public void cancelledOldIpcCannotStopTheNextStepOrReplacement() {
        RunState oldIpc = new RunState("old-ipc", 1000);
        RunState nativeSession = new RunState("session", 1000);
        oldIpc.onCancel(nativeSession::cancel).close();
        RunState nextIpc = new RunState("next-ipc", 1000);
        RunState replacement = new RunState("replacement", 1000);
        try (var ignored = nextIpc.onCancel(replacement::cancel)) {
            oldIpc.cancel();
            assertFalse(nativeSession.cancelled.get());
            assertFalse(replacement.cancelled.get());
            nextIpc.cancel();
            assertTrue(replacement.cancelled.get());
        }
    }

    @Test public void progressIntentIsBoundedSanitizedPrivateAndIgnoredByRepeatDetection() throws Exception {
        Action action = new Action(new JSONObject().put("action", "type").put("text", "private message")
            .put("intent", "填写搜索框"));
        assertEquals("填写搜索框", TaskProgress.intent(action));
        assertFalse(action.data.has("message"));
        assertFalse(action.data.optBoolean("requires_confirmation"));
        assertFalse(TaskProgress.intent(new Action(new JSONObject().put("action", "type").put("text", "private message")))
            .contains("private message"));
        assertEquals("等待页面加载", TaskProgress.intent(new Action(new JSONObject().put("action", "wait")
            .put("intent", new JSONObject()))));
        String quoted = "查找\"订单\"与\\记录";
        assertEquals(quoted, TaskProgress.intent(new Action(new JSONObject().put("action", "wait").put("intent", quoted))));
        assertEquals("点击搜索框 · 正在判断下一步", TaskProgress.waiting("点击搜索框"));
        assertEquals("😀".repeat(60), TaskProgress.compact("\n\u202e" + "😀".repeat(70)));
        Action first = new Action(new JSONObject().put("action", "wait").put("intent", "检查页面"));
        Action second = new Action(new JSONObject().put("action", "wait").put("intent", "继续检查"));
        assertEquals(first.fingerprint(), second.fingerprint());
    }
}
