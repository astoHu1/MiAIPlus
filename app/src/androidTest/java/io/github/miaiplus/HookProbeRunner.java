package io.github.miaiplus;

import android.app.*;
import android.os.*;
import java.util.Map;

/** Configure the mock briefly, ask host to trigger the in-process debug smoke, then restore. */
public class HookProbeRunner extends Instrumentation {
    private String api = "chat";
    private boolean reasoning, focus, launch, conversation;
    @Override public void onCreate(Bundle b) { super.onCreate(b); api = b.getString("api", "chat"); reasoning = "true".equals(b.getString("reasoning")); focus = "true".equals(b.getString("focus")); launch = "true".equals(b.getString("launch")); conversation = "true".equals(b.getString("conversation_return")); start(); }
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
        if (!result.startsWith("PROBE PASS")) throw new AssertionError((result.isBlank() ? "probe timeout" : result)
            + (launch ? launchDiagnostics() : ""));
        return result;
    }
    private String launchDiagnostics() {
        StringBuilder out = new StringBuilder();
        runOnMainSync(() -> {
            try {
                ScreenService service = ScreenService.instance;
                if (service == null) return;
                var current = ScreenService.class.getDeclaredMethod("currentRoot"); current.setAccessible(true);
                var root = (android.view.accessibility.AccessibilityNodeInfo)current.invoke(service);
                if (root == null || !"com.miui.securitycenter".contentEquals(root.getPackageName())) return;
                out.append("\nLaunch prompt window: ").append(root.getWindowId());
                promptNodes(root, out, new int[]{0});
            } catch (ReflectiveOperationException failure) { out.append("\nPrompt diagnostic unavailable"); }
        });
        return out.toString();
    }
    private static void promptNodes(android.view.accessibility.AccessibilityNodeInfo node, StringBuilder out, int[] count) {
        if (count[0]++ >= 100) return;
        if (node.isVisibleToUser() && node.getText() != null)
            out.append("\n").append(node.getViewIdResourceName()).append(" clickable=").append(node.isClickable()).append(" text=").append(node.getText());
        for (int i = 0; i < node.getChildCount() && count[0] < 100; i++) {
            var child = node.getChild(i); if (child != null) promptNodes(child, out, count);
        }
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
            if (focus || launch || conversation) {
                // These probes are about the physical screen: the launch probe asserts the Security page is the
                // foreground it observes, which cannot hold if the device is configured for the background display.
                prefs.edit().putBoolean("enabled", true).putBoolean("globalModel", false).putBoolean("backgroundDisplay", false)
                    .putString("planner", "native").putString(Config.PHONE_USE_PERMISSION_MODE_KEY, "manual").commit();
                int confirmations = ScreenService.instance == null ? 0 : ScreenService.instance.launchConfirmationCount;
                report.putString("stream", probe(conversation ? "MIAIPLUS_CONVERSATION_RETURN" : launch ? "MIAIPLUS_LAUNCH_UI" : "MIAIPLUS_FOCUS_UI") + (launch ? "; automatic launch confirmations="
                    + (ScreenService.instance == null ? 0 : ScreenService.instance.launchConfirmationCount - confirmations) : "") + "\n"); code = Activity.RESULT_OK;
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
            // Built through the physical factory rather than the raw constructor: the record carries display and
            // generation fields now, and this probe is about the transport, not about a background display.
            String token = FrameStore.put(ScreenService.Frame.physical(
                android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP),
                "synthetic fixture", 64, 64, "io.github.miaiplus"), targetUid);
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
                else if (e.getValue() instanceof Float v) edit.putFloat(e.getKey(), v);
                else if (e.getValue() instanceof java.util.Set<?> v) {
                    var strings = new java.util.HashSet<String>();
                    for (Object item : v) strings.add((String) item);
                    edit.putStringSet(e.getKey(), strings);
                }
            }
            if (!edit.commit() || !saved.equals(prefs.getAll())) {
                code = Activity.RESULT_CANCELED;
                report.putString("stream", report.getString("stream", "")
                    + "FAIL: Could not restore original settings\n");
            } else report.putString("stream", report.getString("stream", "")
                + "Original settings restored and verified.\n");
            if (focus || launch || conversation) finish(code, report);
        }
        finish(code, report);
    }
}
