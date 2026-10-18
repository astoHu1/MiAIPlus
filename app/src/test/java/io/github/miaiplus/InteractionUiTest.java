package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class InteractionUiTest {
    private static final String RETURN_TOKEN = "io.github.miaiplus.RETURN_CONVERSATION";

    @Test public void pendingActionPublishesFullConfirmationDespiteEmptyOrUnchangedPlannerProgress() {
        String message = "确认发送给指定收件人：\n" + "完整操作说明".repeat(50);
        for (String progress : new String[]{"", "点击目标控件"}) {
            Bundle state = new Bundle();
            state.putString("progress", progress);
            state.putString("action_confirmation", "action-token");
            state.putString("confirmation_message", message);
            InteractionUi.TaskNotice notice = new InteractionUi.TaskNotice(state);
            assertEquals("小爱请求确认操作", notice.title);
            assertEquals(message, notice.text);
            assertTrue(notice.confirmation);
            assertTrue("A newly pending decision must bypass unchanged progress", notice.changed(notice.progress, null));
            assertFalse("A delivered decision need not float again on every poll", notice.changed(notice.progress, "action-token"));
        }
    }

    @Test public void notificationRetriesUnpublishedDecisionAndClearsControlsAfterItEnds() {
        Bundle state = new Bundle();
        state.putString("progress", "等待确认");
        state.putString("action_confirmation", "new-token");
        state.putString("confirmation_message", "提交本次操作");
        // Until notify succeeds, both later polls must still see a change from the delivered card.
        for (int poll = 0; poll < 2; poll++) {
            InteractionUi.TaskNotice retry = new InteractionUi.TaskNotice(state);
            assertTrue(retry.changed("等待确认", "previous-token"));
        }
        state.remove("action_confirmation");
        state.remove("confirmation_message");
        InteractionUi.TaskNotice resolved = new InteractionUi.TaskNotice(state);
        assertTrue(resolved.changed("等待确认", "new-token"));
        assertNull(resolved.token);
        assertFalse(resolved.confirmation);
        assertEquals("正在操作手机", resolved.title);
        assertEquals("等待确认", resolved.text);
    }

    @Test public void bothForegroundAndSensitiveActionDecisionsReachReceiverWithoutOpeningAnActivity() {
        Application app = RuntimeEnvironment.getApplication();
        List<Intent> received = new ArrayList<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { received.add(intent); }
        };
        app.registerReceiver(receiver, InteractionUi.consentFilter(), Context.RECEIVER_NOT_EXPORTED);
        try {
            for (boolean confirmAction : new boolean[]{false, true}) {
                for (boolean allow : new boolean[]{true, false}) {
                    Intent answer = InteractionUi.consentIntent(app.getPackageName(), "task-a", "token-a", allow, confirmAction);
                    assertTrue(InteractionUi.consentFilter().hasAction(answer.getAction()));
                    assertTrue(InteractionUi.consentFilter().matchData(null, answer.getScheme(), answer.getData()) >= 0);
                    app.sendBroadcast(answer);
                }
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(4, received.size());
            for (Intent answer : received) {
                assertEquals("task-a", answer.getStringExtra("screen_task"));
                assertEquals("token-a", answer.getStringExtra("token"));
                assertEquals(app.getPackageName(), answer.getPackage());
            }
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());
        } finally { app.unregisterReceiver(receiver); }
    }

    @Test public void separateRequestsAndDecisionsKeepDistinctPendingIntentIdentities() {
        Intent allow = InteractionUi.consentIntent("com.miui.voiceassist", "task-a", "token-a", true);
        Intent deny = InteractionUi.consentIntent("com.miui.voiceassist", "task-a", "token-a", false);
        Intent later = InteractionUi.consentIntent("com.miui.voiceassist", "task-b", "token-b", true);
        Intent confirm = InteractionUi.consentIntent("com.miui.voiceassist", "task-a", "token-a", true, true);
        Intent reject = InteractionUi.consentIntent("com.miui.voiceassist", "task-a", "token-a", false, true);
        Intent nextAction = InteractionUi.consentIntent("com.miui.voiceassist", "task-a", "token-b", true, true);
        Intent[] identities = {allow, deny, later, confirm, reject, nextAction};
        for (int first = 0; first < identities.length; first++) {
            for (int second = first + 1; second < identities.length; second++) {
                assertFalse("PendingIntent must not alias another confirmation", identities[first].filterEquals(identities[second]));
            }
        }
        // Stop controls have no URI and must keep their own filter instead of inheriting the consent scheme.
        Intent stop = new Intent("com.miui.voiceassist.MIAIPLUS_STOP");
        IntentFilter stopFilter = new IntentFilter(stop.getAction());
        assertTrue(stopFilter.matchData(null, stop.getScheme(), stop.getData()) >= 0);
        assertTrue(InteractionUi.consentFilter().matchData(null, stop.getScheme(), stop.getData()) < 0);
    }

    @Test public void terminalResultKeepsHostQuietUntilItsBoundedWindowExpires() {
        InteractionUi.HostQuietWindow window = new InteractionUi.HostQuietWindow();
        assertFalse(window.active(false));
        assertTrue(window.active(true));
        window.finished();
        assertTrue(window.active(false));
        ShadowSystemClock.advanceBy(Duration.ofMillis(9_999));
        assertTrue(window.active(false));
        ShadowSystemClock.advanceBy(Duration.ofMillis(1));
        assertFalse(window.active(false));
    }

    @Test public void userWakeCannotReleaseHostWhileControlStillRuns() {
        InteractionUi.HostQuietWindow window = new InteractionUi.HostQuietWindow();
        window.finished();
        assertFalse(window.userEntered(true));
        assertTrue(window.active(true));
        assertTrue(window.active(false));
    }

    @Test public void onlyCurrentCompletionCardCanReleaseQuietWindow() {
        InteractionUi.HostQuietWindow window = new InteractionUi.HostQuietWindow();
        window.finished();
        for (Intent entry : new Intent[]{null, new Intent(),
            new Intent().putExtra(RETURN_TOKEN, "previous-task")}) {
            if (InteractionUi.isCompletionEntry(false, entry, "current-task")) window.userEntered(false);
            assertTrue(window.active(false));
        }
        Intent current = new Intent().putExtra(RETURN_TOKEN, "current-task");
        assertFalse(InteractionUi.isCompletionEntry(true, current, "current-task"));
        assertFalse(InteractionUi.isCompletionEntry(false, current, null));
        assertTrue(InteractionUi.isCompletionEntry(false, current, "current-task"));
        assertTrue(window.userEntered(false));
        assertFalse(window.active(false));
    }
}
