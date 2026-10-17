package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class ForegroundConsentBroadcastTest {
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
        assertFalse(allow.filterEquals(deny));
        assertFalse(allow.filterEquals(later));
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
}
