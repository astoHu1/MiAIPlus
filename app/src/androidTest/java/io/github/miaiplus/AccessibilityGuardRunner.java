package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Explicit task-start probe. No screenshots, gestures or personal apps are used. */
final class AccessibilityGuardRunner {
    private AccessibilityGuardRunner() {}
    private static void set(Instrumentation runner, boolean enabled) throws Exception {
        CompletableFuture<Boolean> applied = new CompletableFuture<>();
        AccessibilityGuard.setEnabled(runner.getTargetContext(), enabled, applied::complete);
        if (applied.get(55, TimeUnit.SECONDS) != enabled) throw new AssertionError("root switch failed");
    }
    private static void healthy() throws Exception {
        long until = SystemClock.elapsedRealtime() + 25_000;
        while (SystemClock.elapsedRealtime() < until) {
            if (ScreenService.isHealthy()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("accessibility did not recover");
    }
    private static void signal(Instrumentation runner, String text) {
        Bundle b = new Bundle(); b.putString("stream", text + "\n"); runner.sendStatus(1, b);
    }
    static void runPreflight(Instrumentation runner) { probe(runner, false); }
    static void run(Instrumentation runner) { probe(runner, true); }
    private static void probe(Instrumentation runner, boolean appRoot) {
        var context = runner.getTargetContext();
        var prefs = Config.prefs(context);
        boolean previous = AccessibilityGuard.enabled(context);
        boolean migrated = prefs.getBoolean("accessibility_preflight_migrated", false);
        Bundle report = new Bundle(); int code = Activity.RESULT_CANCELED;
        java.lang.reflect.Field active = null;
        ScreenService before = null;
        boolean ownsRun = false;
        try {
            active = BridgeProvider.class.getDeclaredField("active"); active.setAccessible(true);
            if (active.get(null) != null || NativeSession.hasPendingOperation())
                throw new AssertionError("a real task is active");
            signal(runner, "MIAIPLUS_PREFLIGHT_CONNECT");
            healthy(); before = ScreenService.instance;
            RunState run = new RunState(UUID.randomUUID().toString(), android.os.Process.myUid());
            run.screen = true; run.preparingScreen = true; active.set(null, run); ownsRun = true;
            // A healthy task succeeds without root even with the option disabled.
            prefs.edit().putBoolean("accessibility_guard", false).commit();
            AccessibilityGuard.prepare(context, run);
            if (appRoot) set(runner, true);
            else prefs.edit().putBoolean("accessibility_guard", true).commit();
            // Model Xiaomi's false-on state: settings stay enabled, but no usable connection.
            runner.runOnMainSync(() -> ScreenService.instance = null);
            Thread.sleep(1500);
            if (ScreenService.isHealthy()) throw new AssertionError("unexpected background repair");
            if (appRoot) AccessibilityGuard.prepare(context, run);
            else signal(runner, "MIAIPLUS_PREFLIGHT_STALE " + run.id);
            healthy();
            if (run.cancelled.get()) throw new AssertionError("preflight rebind cancelled the task");
            if (ScreenService.instance == before) throw new AssertionError("service did not rebind");
            // The real Java caller awaits helper exit before ending preparation.
            if (!appRoot) Thread.sleep(4000);
            run.preparingScreen = false;
            if (!appRoot) {
                signal(runner, "MIAIPLUS_PREFLIGHT_EXECUTING " + run.id);
                Thread.sleep(2500);
            }
            report.putString("stream", "PASS: healthy fast path, false-on preflight recovery, task preserved, no resident repair.\n");
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            report.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(failure));
        } finally {
            try { if (ownsRun) active.set(null, null); }
            catch (ReflectiveOperationException failure) { code = Activity.RESULT_CANCELED; }
            if (ScreenService.instance == null && before != null) ScreenService.instance = before;
            prefs.edit().putBoolean("accessibility_guard", previous)
                .putBoolean("accessibility_preflight_migrated", migrated).commit();
        }
        runner.finish(code, report);
    }
}
