package io.github.miaiplus;

import java.util.concurrent.CancellationException;
import java.util.function.LongFunction;
import org.junit.Test;
import static org.junit.Assert.*;

public class LaunchVerifierTest {
    private static final String TARGET = "fixture.target";
    private static final class Fake implements LaunchVerifier.Driver {
        long time;
        int starts;
        boolean cancelled, consent;
        int consentClicks;
        LongFunction<String> windows = t -> "fixture.origin";
        @Override public String foreground() { return windows.apply(time); }
        @Override public void start() { starts++; }
        @Override public boolean allowLaunchPrompt() { consentClicks++; return consent; }
        @Override public void check() { if (cancelled) throw new CancellationException(); }
        @Override public long now() { return time; }
        @Override public void pause(long millis) { time += millis; }
    }
    @Test public void ordinaryWindowsRequireStableTargetAndAtMostOneStart() throws Exception {
        for (long readyAt : new long[]{0, 1000, 4000}) {
            Fake f = new Fake();
            f.windows = t -> t >= readyAt ? TARGET : readyAt == 1000
                ? LaunchVerifier.classifyForeground("com.miui.securitycenter", "") : "fixture.origin";
            LaunchVerifier.launch(TARGET, f);
            assertEquals("starts when target ready at " + readyAt, readyAt == 0 ? 0 : 1, f.starts);
            assertEquals("stable window when target ready at " + readyAt, readyAt + 500, f.time);
            assertEquals("ordinary window must not approve consent", 0, f.consentClicks);
        }
    }
    @Test public void briefTargetWindowDoesNotCountAsSuccess() throws Exception {
        Fake f = new Fake(); f.windows = t -> t == 250 ? TARGET : "fixture.origin";
        try { LaunchVerifier.launch(TARGET, f); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().startsWith("PAUSE：")); }
        assertEquals(1, f.starts); assertEquals(10000, f.time);
    }
    @Test public void blockedLaunchAndPermissionPromptPauseWithoutRetry() throws Exception {
        for (String blocker : new String[]{"fixture.origin", "com.lbe.security.miui", LaunchVerifier.XIAOMI_CONFIRMATION,
            LaunchVerifier.XIAOMI_AUTHORIZATION, "com.android.permissioncontroller"}) {
            Fake f = new Fake(); f.windows = t -> t >= 250 ? blocker : "fixture.origin";
            try { LaunchVerifier.launch(TARGET, f); fail(); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().startsWith("PAUSE：")); }
            assertEquals(1, f.starts);
            if (!blocker.equals("fixture.origin")) assertEquals(250, f.time);
        }
    }
    @Test public void launchConsentIsAllowedOnceThenTargetIsVerified() throws Exception {
        for (String target : new String[]{TARGET, "com.miui.securitycenter"}) {
            for (boolean existingPrompt : new boolean[]{false, true}) {
                String scenario = target + ", existing prompt=" + existingPrompt;
                Fake f = new Fake(); f.consent = true;
                f.windows = t -> t < 250 && !existingPrompt ? "fixture.origin"
                    : t < (existingPrompt ? 250 : 1000) ? LaunchVerifier.XIAOMI_CONFIRMATION : target;
                LaunchVerifier.launch(target, f);
                assertEquals(scenario, existingPrompt ? 0 : 1, f.starts);
                assertEquals(scenario, 1, f.consentClicks);
                assertEquals(scenario, existingPrompt ? 750 : 1500, f.time);
            }
        }
    }
    @Test public void clickingConsentDoesNotCountAsSuccessfulLaunch() throws Exception {
        Fake f = new Fake(); f.consent = true;
        f.windows = t -> t < 250 ? "fixture.origin" : LaunchVerifier.XIAOMI_CONFIRMATION;
        assertTrue(assertThrows(IllegalStateException.class, () -> LaunchVerifier.launch(TARGET, f))
            .getMessage().startsWith("PAUSE："));
        assertEquals(1, f.starts); assertEquals(1, f.consentClicks); assertEquals(10000, f.time);
    }
    @Test public void existingPermissionPromptsBlockBeforeAnotherLaunch() throws Exception {
        for (String blocker : new String[]{"com.lbe.security.miui",
            LaunchVerifier.classifyForeground("com.miui.securitycenter", "相机")}) {
            Fake f = new Fake(); f.consent = true; f.windows = t -> blocker;
            assertTrue(blocker, assertThrows(IllegalStateException.class, () -> LaunchVerifier.launch(TARGET, f))
                .getMessage().startsWith("PAUSE："));
            assertEquals(blocker, 0, f.starts);
            assertEquals(blocker, 0, f.consentClicks);
        }
    }
    @Test public void anotherSecurityPermissionAfterLaunchConsentStillPauses() throws Exception {
        Fake f = new Fake(); f.consent = true;
        f.windows = t -> t < 250 ? "fixture.origin" : LaunchVerifier.classifyForeground("com.miui.securitycenter",
            t < 1000 ? "启动应用" : "相机");
        try { LaunchVerifier.launch(TARGET, f); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().startsWith("PAUSE：")); }
        assertEquals(1, f.starts); assertEquals(1, f.consentClicks); assertEquals(1000, f.time);
    }
    @Test public void cancellationStopsBeforeLaunch() throws Exception {
        Fake f = new Fake(); f.cancelled = true;
        try { LaunchVerifier.launch(TARGET, f); fail(); } catch (CancellationException expected) { }
        assertEquals(0, f.starts);
    }
}
