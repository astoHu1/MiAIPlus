package io.github.miaiplus;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ConfigStepsTest {
    @Test public void defaultBudgetRemainsThirty() {
        assertEquals(30, new Config(Map.of()).maxSteps);
    }

    @Test public void acceptsBudgetsBeyondPreviousLimit() {
        assertEquals(81, new Config(Map.of("maxSteps", 81)).maxSteps);
        assertEquals(200, new Config(Map.of("maxSteps", 200)).maxSteps);
    }

    @Test public void persistedBudgetIsClampedToSupportedBounds() {
        assertEquals(1, new Config(Map.of("maxSteps", 0)).maxSteps);
        assertEquals(200, new Config(Map.of("maxSteps", 201)).maxSteps);
    }
}
