package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Direct, isolated launch regression; the host owns service connection and current-prompt handling. */
public final class LaunchRegressionRunner extends Instrumentation {
    private static final Pattern INSTANCE = Pattern.compile("MiAIPlus launch fixture, instance (\\d+); touch downs (\\d+)");
    private String homePackage;

    @Override public void onCreate(Bundle arguments) {
        homePackage = arguments == null ? null : arguments.getString("home_package");
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle report = new Bundle();
        int result = Activity.RESULT_CANCELED;
        RunState run = new RunState("isolated-launch-regression", android.os.Process.myUid());
        try {
            Config.prefs(getTargetContext()).edit().putBoolean(Config.PHONE_USE_LOG_KEY, true).commit();
            PhoneUseLog.init(getTargetContext());
            String target = getContext().getPackageName();
            signal("MIAIPLUS_LAUNCH_CONNECT", "Enable only this test target's ScreenService; preserve existing services.");
            long connectDeadline = SystemClock.elapsedRealtime() + 20_000;
            while (!ScreenService.isHealthy() && SystemClock.elapsedRealtime() < connectDeadline) run.pause(100);
            check(ScreenService.isHealthy(), "test accessibility service did not connect");
            ScreenService service = ScreenService.instance;
            if (homePackage == null || homePackage.isBlank()) {
                var home = getTargetContext().getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
                check(home != null && home.activityInfo != null && !"android".equals(home.activityInfo.packageName),
                    "cannot identify default home; supply home_package instrumentation argument");
                homePackage = home.activityInfo.packageName;
            }
            signal("MIAIPLUS_LAUNCH_HOME", "Press HOME; waiting for foreground=" + homePackage);
            if (!awaitForeground(service, homePackage, run, 10_000)) {
                ScreenService.Frame frame = service.capture(run);
                signal("MIAIPLUS_LAUNCH_CHECK", diagnostic(frame, "home_not_foreground"));
                throw new AssertionError("home did not become foreground: " + frame.foreground());
            }
            ScreenService.Frame initial = service.capture(run);
            check(homePackage.equals(initial.foreground()), "foreground changed after home handshake");
            Action launch = new Action(new JSONObject().put("action", "launch").put("app", target));
            int confirmations = service.launchConfirmationCount;
            long started = SystemClock.elapsedRealtime();
            service.execute(launch, initial, run);
            long launchMillis = SystemClock.elapsedRealtime() - started;
            check(launchMillis <= 12_000, "launch did not open or return an observation within its confirmation grace budget: " + launchMillis);
            ScreenService.Frame current = service.capture(run);
            boolean returnedObservation = !target.equals(current.foreground());
            signal("MIAIPLUS_LAUNCH_CHECK", diagnostic(current, returnedObservation
                ? "launch_returned_observation; host must inspect and handle only this current launch prompt"
                : "target_opened; no host confirmation needed"));
            if (returnedObservation) {
                check(awaitForeground(service, target, run, 30_000),
                    "launch returned readable system UI, but target never opened after host current-prompt check");
                current = service.capture(run);
            }
            check(target.equals(current.foreground()), "target is not foreground after launch");
            String instance = fixtureInstance(current);
            started = SystemClock.elapsedRealtime();
            service.execute(launch, current, run);
            long repeatMillis = SystemClock.elapsedRealtime() - started;
            ScreenService.Frame repeated = service.capture(run);
            check(target.equals(repeated.foreground()), "repeat launch lost the target foreground");
            check(instance.equals(fixtureInstance(repeated)), "repeat launch recreated the owned fixture");
            report.putString("stream", "PASS: direct isolated launch; launchMs=" + launchMillis
                + "; completion=" + (returnedObservation ? "host_current_prompt_handling" : "automatic")
                + "; observationReturned=" + returnedObservation
                + "; automaticConfirmations=" + (service.launchConfirmationCount - confirmations)
                + "; repeatMs=" + repeatMillis + "; fixtureInstance=" + instance
                + "; repeat preserved instance and zero physical touches\n");
            result = Activity.RESULT_OK;
        } catch (Throwable failure) {
            String observation = "";
            if (ScreenService.isHealthy()) {
                try {
                    observation = "\nCURRENT: " + diagnostic(ScreenService.instance.capture(run), "failure_observation");
                } catch (Throwable observationFailure) {
                    observation = "\nCURRENT observation unavailable: " + observationFailure;
                }
            }
            report.putString("stream", "FAIL: " + failure + "\n" + android.util.Log.getStackTraceString(failure) + observation);
        } finally {
            run.cancel("launch_regression_cleanup");
            // This fixture's cleanup receiver requires DUMP, so the authorized shell host sends it.
            signal("MIAIPLUS_LAUNCH_CLEANUP", "Broadcast io.github.miaiplus.CLOSE_LAUNCH_FIXTURE to "
                + getContext().getPackageName() + "; restore the original enabled accessibility services.");
            finish(result, report);
        }
    }

    private boolean awaitForeground(ScreenService service, String wanted, RunState run, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        long stableSince = -1;
        while (SystemClock.elapsedRealtime() < deadline) {
            run.check();
            if (wanted.equals(foreground(service))) {
                if (stableSince < 0) stableSince = SystemClock.elapsedRealtime();
                if (SystemClock.elapsedRealtime() - stableSince >= 500) return true;
            } else stableSince = -1;
            run.pause(100);
        }
        return false;
    }

    private String foreground(ScreenService service) {
        String[] foreground = {""};
        runOnMainSync(() -> {
            for (AccessibilityWindowInfo window : service.getWindows()) {
                if (!window.isFocused() || window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    || window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null && root.getPackageName() != null) foreground[0] = root.getPackageName().toString();
                return;
            }
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) foreground[0] = root.getPackageName().toString();
        });
        return foreground[0];
    }

    private static String fixtureInstance(ScreenService.Frame frame) {
        var matcher = INSTANCE.matcher(frame.tree());
        check(matcher.find(), "owned launch fixture instance text is unreadable");
        check("0".equals(matcher.group(2)), "owned launch fixture received an unexpected physical touch");
        return matcher.group(1);
    }

    private static String diagnostic(ScreenService.Frame frame, String outcome) {
        String tree = frame.tree();
        return outcome + "; foreground=" + frame.foreground() + "; tree="
            + tree.substring(0, Math.min(tree.length(), 8_000));
    }

    private void signal(String marker, String detail) {
        Bundle status = new Bundle();
        status.putString("stream", marker + " " + detail + "\n");
        sendStatus(0, status);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
