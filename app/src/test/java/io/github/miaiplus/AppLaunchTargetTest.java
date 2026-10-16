package io.github.miaiplus;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppLaunchTargetTest {
    @Test public void exactPackageTakesPriorityOverLabel() {
        var exact = new AppLaunchTarget("fixture.first", "Main", "First");
        var other = new AppLaunchTarget("fixture.other", "Main", "fixture.first");
        assertEquals(exact, AppLaunchTarget.resolve(" fixture.first ", List.of(other, exact)));
    }
    @Test public void multipleLauncherEntriesInOnePackageAreNotAmbiguous() {
        var first = new AppLaunchTarget("fixture.first", "Main", "First");
        assertEquals(first, AppLaunchTarget.resolve("First", List.of(first,
            new AppLaunchTarget("fixture.first", "Alias", "First"))));
    }
    @Test public void duplicateAppLabelsRequirePackageName() {
        try {
            AppLaunchTarget.resolve("First", List.of(new AppLaunchTarget("fixture.first", "Main", "First"),
                new AppLaunchTarget("fixture.other", "Main", "First"))); fail();
        } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("不唯一")); }
    }
    @Test public void unknownOrNonLauncherTargetIsRejected() {
        for (String app : new String[]{"missing.app", "intent://fixture", "", "fixture.Main"}) {
            try { AppLaunchTarget.resolve(app, List.of()); fail(); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("启动入口")); }
        }
    }
}
