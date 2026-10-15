package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

/** Explicit host-driven live regression helper. No model credentials or personal UI logging. */
public class LiveProbeRunner extends HookProbeRunner {
    private Bundle arguments;
    @Override public void onCreate(Bundle b) { arguments = b; super.onCreate(b); }
    @Override public void onStart() {
        if (!"true".equals(arguments.getString("live"))) { super.onStart(); return; }
        Bundle result = new Bundle(); int code = Activity.RESULT_CANCELED;
        try {
            if ("true".equals(arguments.getString("configure"))) {
                Config.prefs(getTargetContext()).edit().putBoolean("enabled", true).putBoolean("globalModel", false)
                    .putString("planner", "native").putString("endpoint", "").putString("apiKey", "").putString("model", "").commit();
            }
            Bundle signal = new Bundle(); signal.putString("stream", "MIAIPLUS_CONNECT_SERVICE\n"); sendStatus(1, signal);
            long end = SystemClock.elapsedRealtime() + 15000;
            while (ScreenService.instance == null && SystemClock.elapsedRealtime() < end) Thread.sleep(100);
            if (ScreenService.instance == null) throw new IllegalStateException("service unavailable");
            String text = arguments.getString("text");
            if (text != null) {
                boolean[] ok = {false};
                runOnMainSync(() -> {
                    var root = ScreenService.instance.getRootInActiveWindow();
                    if (root == null || !"com.miui.voiceassist".contentEquals(root.getPackageName())) return;
                    var input = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (input == null || !input.isEditable() || input.isPassword()) return;
                    Bundle b = new Bundle(); b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    ok[0] = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
                });
                if (!ok[0]) throw new IllegalStateException("editable focus unavailable");
                result.putString("stream", "LIVE INPUT SET\n");
            } else result.putString("stream", "LIVE CONFIG READY\n");
            code = Activity.RESULT_OK;
        } catch (Throwable e) { result.putString("stream", "LIVE FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
        finish(code, result);
    }
}
