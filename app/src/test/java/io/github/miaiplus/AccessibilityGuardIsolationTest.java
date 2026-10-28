package io.github.miaiplus;

import static org.junit.Assert.*;
import org.junit.Test;

public final class AccessibilityGuardIsolationTest {
    @Test public void onlyMainlineInstallCanRetireItsLegacyRootGuard() {
        String mainline = AccessibilityGuard.legacyRetirementScript("io.github.miaiplus");
        assertTrue(mainline.contains("rm -f /data/adb/service.d/miaiplus-accessibility.sh"));
        assertTrue(mainline.contains("/data/adb/miaiplus-accessibility/user*"));
        assertTrue(mainline.contains("kill -TERM"));
        for (String applicationId : new String[]{"io.github.miaiplus.pluginify", "other.module.test"}) {
            String script = AccessibilityGuard.legacyRetirementScript(applicationId);
            assertTrue(script.contains("id -u"));
            assertFalse(script.contains("/data/adb/"));
            assertFalse(script.contains("rm -f"));
            assertFalse(script.contains("kill"));
        }
    }

    @Test public void preflightReceivesTheInstalledPackageAsAnExplicitArgument() {
        String command = AccessibilityGuard.preflightCommand("Zml4dHVyZQ==", 7, "01234567-89ab-cdef-0123-456789abcdef");
        assertTrue(command.endsWith("/system/bin/sh -s -- 7 01234567-89ab-cdef-0123-456789abcdef " + BuildConfig.APPLICATION_ID));
        if (!"io.github.miaiplus".equals(BuildConfig.APPLICATION_ID)) assertFalse(command.contains("/data/adb/"));
    }
}
