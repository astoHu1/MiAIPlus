package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Intent;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class InteractionUiQuietWindowTest {
    private static final String RETURN_TOKEN = "io.github.miaiplus.RETURN_CONVERSATION";

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

    @Test public void explicitUserEntryImmediatelyAllowsHostAfterFinish() {
        InteractionUi.HostQuietWindow window = new InteractionUi.HostQuietWindow();
        window.finished();
        assertTrue(window.userEntered(false));
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
        window.userEntered(false);
        assertFalse(window.active(false));
    }
}
