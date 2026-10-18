package io.github.miaiplus;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ConfigDisplayGeometryTest {
    @Test public void existingInstallationsKeepDefaultCanvas() {
        Config defaults = new Config(Map.of());
        assertEquals(1080, defaults.displayWidth);
        assertEquals(1920, defaults.displayHeight);
        assertEquals(320, defaults.displayDensity);
    }

    @Test public void customCanvasIsPreserved() {
        Config config = new Config(Map.of("displayWidth", 1440, "displayHeight", 3200, "displayDensity", 560));
        assertEquals(1440, config.displayWidth);
        assertEquals(3200, config.displayHeight);
        assertEquals(560, config.displayDensity);
    }

    @Test public void outOfRangePersistedGeometryIsClampedToHelperLimits() {
        Config config = new Config(Map.of("displayWidth", 1, "displayHeight", 9999, "displayDensity", 1));
        assertEquals(Config.MIN_DISPLAY_SIZE, config.displayWidth);
        assertEquals(Config.MAX_DISPLAY_SIZE, config.displayHeight);
        assertEquals(Config.MIN_DISPLAY_DENSITY, config.displayDensity);
        assertEquals(Config.MAX_DISPLAY_DENSITY,
            new Config(Map.of("displayDensity", 9999)).displayDensity);
    }

    @Test public void malformedPersistedGeometryUsesDefaults() {
        Config config = new Config(Map.of("displayWidth", "1080", "displayHeight", "1920", "displayDensity", "320"));
        assertEquals(1080, config.displayWidth);
        assertEquals(1920, config.displayHeight);
        assertEquals(320, config.displayDensity);
    }
}
