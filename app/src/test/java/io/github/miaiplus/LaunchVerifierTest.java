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
    @Test public void slowStartupWaitsForStableTargetWindow() throws Exception {
        Fake f = new Fake(); f.windows = t -> t >= 4000 ? TARGET : "fixture.origin";
        LaunchVerifier.launch(TARGET, f);
        assertEquals(1, f.starts); assertEquals(4500, f.time);
    }
    @Test public void alreadyOpenDoesNotRestartApp() throws Exception {
        Fake f = new Fake(); f.windows = t -> TARGET;
        LaunchVerifier.launch(TARGET, f);
        assertEquals(0, f.starts); assertEquals(500, f.time);
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
    @Test public void existingPermissionPromptIsNotCoveredByAnotherLaunch() throws Exception {
        Fake f = new Fake(); f.windows = t -> "com.lbe.security.miui";
        try { LaunchVerifier.launch(TARGET, f); fail(); } catch (IllegalStateException expected) { }
        assertEquals(0, f.starts);
    }
    @Test public void matchingLaunchConsentIsAllowedOnceThenTargetIsVerified() throws Exception {
        Fake f = new Fake(); f.consent = true;
        f.windows = t -> t < 250 ? "fixture.origin" : t < 1000 ? LaunchVerifier.XIAOMI_CONFIRMATION : TARGET;
        LaunchVerifier.launch(TARGET, f);
        assertEquals(1, f.starts); assertEquals(1, f.consentClicks); assertEquals(1500, f.time);
    }
    @Test public void clickingConsentDoesNotCountAsSuccessfulLaunch() throws Exception {
        Fake f = new Fake(); f.consent = true;
        f.windows = t -> t < 250 ? "fixture.origin" : LaunchVerifier.XIAOMI_CONFIRMATION;
        try { LaunchVerifier.launch(TARGET, f); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, f.consentClicks); assertEquals(10000, f.time);
    }
    @Test public void existingMatchingPromptCanCompleteWithoutAnotherStart() throws Exception {
        Fake f = new Fake(); f.consent = true;
        f.windows = t -> t < 250 ? LaunchVerifier.XIAOMI_CONFIRMATION : TARGET;
        LaunchVerifier.launch(TARGET, f);
        assertEquals(0, f.starts); assertEquals(1, f.consentClicks);
    }
    @Test public void securityAppConfirmationDoesNotCountAsSecurityAppOpened() throws Exception {
        Fake f = new Fake(); f.consent = true;
        f.windows = t -> t < 250 ? "fixture.origin" : t < 1000 ? LaunchVerifier.XIAOMI_CONFIRMATION : "com.miui.securitycenter";
        LaunchVerifier.launch("com.miui.securitycenter", f);
        assertEquals(1, f.consentClicks); assertEquals(1500, f.time);
    }
    @Test public void normalSecurityAppPageAllowsLaunchAndWaitsForTarget() throws Exception {
        Fake f = new Fake();
        f.windows = t -> t < 1000 ? LaunchVerifier.classifyForeground("com.miui.securitycenter", "") : TARGET;
        LaunchVerifier.launch(TARGET, f);
        assertEquals(1, f.starts); assertEquals(0, f.consentClicks); assertEquals(1500, f.time);
    }
    @Test public void securityAppPermissionPageStillBlocksBeforeLaunch() throws Exception {
        Fake f = new Fake(); f.consent = true;
        f.windows = t -> LaunchVerifier.classifyForeground("com.miui.securitycenter", "相机");
        try { LaunchVerifier.launch(TARGET, f); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().startsWith("PAUSE：")); }
        assertEquals(0, f.starts);
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
