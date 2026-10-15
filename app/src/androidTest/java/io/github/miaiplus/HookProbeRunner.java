package io.github.miaiplus;

import android.app.*;
import android.os.*;
import java.util.Map;

/** Configure the mock briefly, ask host to trigger the in-process debug smoke, then restore. */
public class HookProbeRunner extends Instrumentation {
    private String api = "chat";
    private boolean reasoning, focus;
    @Override public void onCreate(Bundle b) { super.onCreate(b); api = b.getString("api", "chat"); reasoning = "true".equals(b.getString("reasoning")); focus = "true".equals(b.getString("focus")); start(); }
    private String probe(String token) throws Exception {
        Config.prefs(getTargetContext()).edit().remove("hook_report").commit();
        Bundle status = new Bundle(); status.putString("stream", "MIAIPLUS_RESTART_XIAOAI " + token + "\n"); sendStatus(1, status);
        long deadline = SystemClock.elapsedRealtime() + 80000;
        String result = "";
        while (SystemClock.elapsedRealtime() < deadline) {
            result = Config.prefs(getTargetContext()).getString("hook_report", "");
            if (result.startsWith("PROBE ")) break;
            Thread.sleep(200);
        }
        if (!result.startsWith("PROBE PASS")) throw new AssertionError(result.isBlank() ? "probe timeout" : result);
        return result;
    }
    private void checkReasoningWrites(String model) {
        Bundle selection = new Bundle(); selection.putString("endpoint", "http://127.0.0.1:18765/v1");
        selection.putString("model", model); selection.putString("api", api); selection.putString("reasoning", "unsupported");
        try {
            getTargetContext().getContentResolver().call(BridgeProvider.URI, "set_reasoning", null, selection);
            throw new AssertionError("unsupported effort accepted");
        } catch (IllegalArgumentException expected) { }
        selection.putString("reasoning", "default"); selection.putString("model", "stale-model");
        try {
            getTargetContext().getContentResolver().call(BridgeProvider.URI, "set_reasoning", null, selection);
            throw new AssertionError("stale model menu accepted");
        } catch (IllegalStateException expected) { }
    }
    @Override public void onStart() {
        var prefs = Config.prefs(getTargetContext()); Map<String, ?> saved = prefs.getAll();
        Bundle report = new Bundle(); int code = Activity.RESULT_CANCELED;
        try {
            if (focus) {
                prefs.edit().putBoolean("enabled", true).putBoolean("globalModel", false).putString("planner", "native").commit();
                report.putString("stream", probe("MIAIPLUS_FOCUS_UI") + "\n"); code = Activity.RESULT_OK;
                return;
            }
            if (reasoning) {
                prefs.edit().putBoolean("globalModel", false).commit();
                String stock = probe("MIAIPLUS_STOCK_REASONING");
                Bundle status = new Bundle(); status.putString("stream", stock + "\n"); sendStatus(1, status);
            }
            String model = reasoning ? ("responses".equals(api) ? "gpt-6.1-sol" : "qwen3.8-max")
                : ("responses".equals(api) ? "fixture-responses-model" : "fixture-model");
            prefs.edit().putBoolean("enabled", true).putBoolean("globalModel", true).putString("planner", "native")
                .putString("api", api).putString("endpoint", "http://127.0.0.1:18765/v1").putString("apiKey", "test-key").putString("model", model)
                .remove(ReasoningPolicy.preferenceKey("http://127.0.0.1:18765/v1", model, api))
                .remove("hook_report").commit();
            if (reasoning) checkReasoningWrites(model);
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bytes); bitmap.recycle();
            int targetUid = getTargetContext().getPackageManager().getPackageUid("com.miui.voiceassist", 0);
            String token = FrameStore.put(new ScreenService.Frame(android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP), "synthetic fixture", 64, 64, "io.github.miaiplus"), targetUid);
            String result = probe(token);
            if (reasoning) result += "; native reasoning menu and selected wire effort";
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
            if (focus) finish(code, report);
        }
        finish(code, report);
    }
}
