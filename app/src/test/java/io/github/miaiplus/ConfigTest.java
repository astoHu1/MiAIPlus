package io.github.miaiplus;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConfigTest {
    @Test public void globalExternalModelAlwaysUsesNativePlannerIncludingLegacyPreferences() {
        assertEquals("native", new Config(Map.of("globalModel", true, "planner", "external")).planner);
        assertEquals("native", new Config(Map.of("globalModel", true)).planner);
        assertEquals("external", new Config(Map.of("globalModel", false, "planner", "external")).planner);
    }

    @Test public void stepBudgetsKeepDefaultAndClampPersistedValuesToSupportedBounds() {
        assertEquals("missing budget", 30, new Config(Map.of()).maxSteps);
        for (int[] budget : new int[][]{{81, 81}, {200, 200}, {0, 1}, {201, 200}}) {
            assertEquals("maxSteps=" + budget[0], budget[1],
                new Config(Map.of("maxSteps", budget[0])).maxSteps);
        }
    }

    @Test public void conversationTimeoutKeepsDefaultAndNormalizesPersistedMinutes() {
        assertEquals("missing timeout", 15, new Config(Map.of()).conversationTimeoutMinutes);
        for (Object[] timeout : new Object[][]{
            {0, 0}, {1, 1}, {1440, 1440}, {-1, 0}, {1441, 1440}, {"invalid", 15}
        }) {
            assertEquals("conversationTimeoutMinutes=" + timeout[0], (int) timeout[1],
                new Config(Map.of("conversationTimeoutMinutes", timeout[0])).conversationTimeoutMinutes);
        }
    }

    @Test public void displayGeometryKeepsDefaultsAndNormalizesPersistedCanvas() {
        assertGeometry("missing geometry", new Config(Map.of()), 1080, 1920, 320);
        assertGeometry("custom geometry", new Config(Map.of(
            "displayWidth", 1440, "displayHeight", 3200, "displayDensity", 560)), 1440, 3200, 560);
        for (int[] geometry : new int[][]{
            {1, 9999, 1, Config.MIN_DISPLAY_SIZE, Config.MAX_DISPLAY_SIZE, Config.MIN_DISPLAY_DENSITY},
            {9999, 1, 9999, Config.MAX_DISPLAY_SIZE, Config.MIN_DISPLAY_SIZE, Config.MAX_DISPLAY_DENSITY}
        }) {
            String label = "geometry=" + geometry[0] + "x" + geometry[1] + "@" + geometry[2];
            assertGeometry(label, new Config(Map.of("displayWidth", geometry[0],
                "displayHeight", geometry[1], "displayDensity", geometry[2])),
                geometry[3], geometry[4], geometry[5]);
        }
        assertGeometry("non-numeric geometry", new Config(Map.of(
            "displayWidth", "1440", "displayHeight", "3200", "displayDensity", "560")), 1080, 1920, 320);
    }

    @Test public void backgroundDisplayRequiresBooleanOptInAndLeavesOtherOptionsAlone() {
        assertFalse("missing opt-in on fresh install", new Config(Map.of()).backgroundDisplay);
        for (Object optIn : new Object[]{null, false, "true", true}) {
            Map<String, Object> values = new HashMap<>(Map.of("enabled", true, "maxSteps", 12));
            if (optIn != null) values.put("backgroundDisplay", optIn);
            Config config = new Config(values);
            String label = "backgroundDisplay=" + optIn + " ("
                + (optIn == null ? "missing" : optIn.getClass().getSimpleName()) + ")";
            assertEquals(label, Boolean.TRUE.equals(optIn), config.backgroundDisplay);
            assertTrue(label, config.enabled);
            assertEquals(label, 12, config.maxSteps);
        }
    }

    private static void assertGeometry(String label, Config config, int width, int height, int density) {
        assertEquals(label + " width", width, config.displayWidth);
        assertEquals(label + " height", height, config.displayHeight);
        assertEquals(label + " density", density, config.displayDensity);
    }
}
