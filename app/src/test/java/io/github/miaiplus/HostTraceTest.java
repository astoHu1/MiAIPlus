package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import java.time.Duration;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class HostTraceTest {
    @After public void cleanup() { HostTrace.clear(); }

    @Test public void snapshotKeepsItsOriginalRouteWhenAnotherRequestArrives() {
        String first = HostTrace.route(true);
        HostTrace.Snapshot captured = HostTrace.snapshot();
        ShadowSystemClock.advanceBy(Duration.ofMillis(30));
        String next = HostTrace.route(false);
        assertEquals(first, captured.reference());
        assertTrue(captured.forcedAgent());
        assertNotEquals(first, next);
        assertEquals(next, HostTrace.snapshot().reference());
        assertFalse(HostTrace.snapshot().forcedAgent());
    }

    @Test public void oldRoutesExpireAndReloadClearsAllDiagnosticContext() {
        HostTrace.route(true);
        ShadowSystemClock.advanceBy(Duration.ofMillis(120_001));
        assertEquals("", HostTrace.snapshot().reference());
        assertEquals(-1, HostTrace.snapshot().ageMs());
        assertFalse(HostTrace.snapshot().forcedAgent());
        HostTrace.stage("model");
        assertTrue(HostTrace.changed("state", "ready", 60_000));
        assertFalse(HostTrace.changed("state", "ready", 60_000));
        HostTrace.clear();
        assertEquals("", HostTrace.stage());
        assertEquals("", HostTrace.lastRoute());
        assertTrue(HostTrace.changed("state", "ready", 60_000));
    }

    @Test public void changedStatesAreReportedImmediatelyAndRepeatedStatesAreBounded() {
        assertTrue(HostTrace.changed("gate", "enabled", 60_000));
        assertFalse(HostTrace.changed("gate", "enabled", 60_000));
        assertTrue(HostTrace.changed("gate", "disabled", 60_000));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60));
        assertTrue(HostTrace.changed("gate", "disabled", 60_000));
    }
}
