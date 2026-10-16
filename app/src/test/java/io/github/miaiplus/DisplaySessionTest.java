package io.github.miaiplus;

import org.junit.Test;
import static org.junit.Assert.*;

public class DisplaySessionTest {
    @Test public void aGenerationOnlyMatchesItsOwnDisplay() {
        DisplaySession session = new DisplaySession(7, "sf-7", 1080, 1920, 320, 0);
        assertTrue(session.matches(7, session.generation));
        // The system can reuse a display id for an unrelated display, so the id alone must never be trusted.
        assertFalse(session.matches(8, session.generation));
        assertFalse(session.matches(7, new DisplaySession(7, "sf-7", 1080, 1920, 320, 0).generation));
        assertFalse(session.matches(7, ""));
    }

    @Test public void everySessionGetsItsOwnGeneration() {
        assertNotEquals(new DisplaySession(1, "a", 1, 1, 1, 0).generation,
            new DisplaySession(1, "a", 1, 1, 1, 0).generation);
    }

    @Test public void physicalAndBackgroundFramesStayDistinguishable() {
        ScreenService.Frame physical = ScreenService.Frame.physical("img", "tree", 1440, 3200, "com.example");
        assertFalse(physical.isBackground());
        assertEquals(1440, physical.imageWidth());
        assertEquals(3200, physical.imageHeight());
        assertEquals(android.view.Display.DEFAULT_DISPLAY, physical.displayId());

        ScreenService.Frame background = ScreenService.Frame.background("img", "tree", 1080, 1920, 576, 1024,
            "com.example", 7, "generation-7");
        assertTrue(background.isBackground());
        assertEquals(7, background.displayId());
        assertEquals("generation-7", background.generation());
        // The logical display size and the encoded JPEG size must stay separate; mixing them misplaces taps.
        assertEquals(1080, background.width());
        assertEquals(1920, background.height());
        assertEquals(576, background.imageWidth());
        assertEquals(1024, background.imageHeight());
    }

    @Test public void navigationActionsAreTheOnesThatSkipPreActionReobservation() {
        assertTrue(BackgroundActions.NAVIGATION.contains("launch"));
        assertTrue(BackgroundActions.NAVIGATION.contains("back"));
        assertTrue(BackgroundActions.NAVIGATION.contains("home"));
        assertFalse(BackgroundActions.NAVIGATION.contains("tap"));
        assertFalse(BackgroundActions.NAVIGATION.contains("type"));
    }

    @Test public void backgroundCapabilityIsOffByDefaultAndOnlyOptInEnablesIt() {
        // A fresh install has no key stored, and must keep sending every task to the physical screen.
        assertFalse(new Config(java.util.Map.of()).backgroundDisplay);
        assertFalse(new Config(java.util.Map.of("backgroundDisplay", false)).backgroundDisplay);
        // A non-boolean value must not enable a root-backed feature by accident.
        assertFalse(new Config(java.util.Map.of("backgroundDisplay", "true")).backgroundDisplay);
        assertTrue(new Config(java.util.Map.of("backgroundDisplay", true)).backgroundDisplay);
    }

    @Test public void backgroundSettingDoesNotDisturbExistingKeys() {
        Config physical = new Config(java.util.Map.of("enabled", true, "maxSteps", 12));
        assertTrue(physical.enabled);
        assertEquals(12, physical.maxSteps);
        assertFalse(physical.backgroundDisplay);
    }
}
