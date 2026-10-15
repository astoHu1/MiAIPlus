package io.github.miaiplus;

import android.app.*;
import android.os.*;
import java.util.Map;

/** Configure the mock briefly, ask host to trigger the in-process debug smoke, then restore. */
public class HookProbeRunner extends Instrumentation {
    @Override public void onCreate(Bundle b) { super.onCreate(b); start(); }
    @Override public void onStart() {
        var prefs = Config.prefs(getTargetContext()); Map<String, ?> saved = prefs.getAll();
        Bundle report = new Bundle(); int code = Activity.RESULT_CANCELED;
        try {
            prefs.edit().putBoolean("enabled", true).putBoolean("globalModel", true).putString("planner", "native")
                .putString("endpoint", "http://127.0.0.1:18765/v1").putString("apiKey", "test-key").putString("model", "fixture-model")
                .remove("hook_report").commit();
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bytes); bitmap.recycle();
            int targetUid = getTargetContext().getPackageManager().getPackageUid("com.miui.voiceassist", 0);
            String token = FrameStore.put(new ScreenService.Frame(android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP), "synthetic fixture", 64, 64, "io.github.miaiplus"), targetUid);
            Bundle status = new Bundle(); status.putString("stream", "MIAIPLUS_RESTART_XIAOAI " + token + "\n"); sendStatus(1, status);
            long deadline = SystemClock.elapsedRealtime() + 80000;
            String result = "";
            while (SystemClock.elapsedRealtime() < deadline) {
                result = prefs.getString("hook_report", "");
                if (result.startsWith("PROBE ")) break;
                Thread.sleep(200);
            }
            if (!result.startsWith("PROBE PASS")) throw new AssertionError(result.isBlank() ? "probe timeout" : result);
            report.putString("stream", result + "\n"); code = Activity.RESULT_OK;
        } catch (Throwable e) { report.putString("stream", "PROBE FAIL: " + android.util.Log.getStackTraceString(e)); }
        finally {
            BridgeProvider.cancelActive();
            var edit = prefs.edit().clear();
            for (var e : saved.entrySet()) {
                if (e.getValue() instanceof String v) edit.putString(e.getKey(), v);
                else if (e.getValue() instanceof Boolean v) edit.putBoolean(e.getKey(), v);
                else if (e.getValue() instanceof Long v) edit.putLong(e.getKey(), v);
                else if (e.getValue() instanceof Integer v) edit.putInt(e.getKey(), v);
            }
            edit.commit();
        }
        finish(code, report);
    }
}
