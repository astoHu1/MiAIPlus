package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Catalog/cache and serialized policy checks on Android; never sends a model request. */
final class ModelsDevRunner {
    private static final String ENDPOINT = "https://catalog-fixture.invalid/v1";
    private static final String MODEL = "future-effort-fixture";
    private final Instrumentation instrumentation;

    private ModelsDevRunner(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    static void run(Instrumentation instrumentation, boolean remote) {
        new ModelsDevRunner(instrumentation).execute(remote);
    }

    private void execute(boolean remote) {
        Bundle report = new Bundle();
        int result = Activity.RESULT_CANCELED;
        SharedPreferences prefs = Config.prefs(instrumentation.getTargetContext());
        Map<String, Object> saved = new LinkedHashMap<>();
        ModelsDevCatalog previous = null;
        boolean changed = false;
        try {
            Bundle activity = instrumentation.getTargetContext().getContentResolver().call(
                BridgeProvider.URI, "conversation_settings", null, null);
            check(activity != null && !activity.getBoolean("screenBusy"), "Active phone-use task");
            if (remote) awaitRemoteCache();
            previous = ModelsDevCatalog.current();
            for (String key : List.of("globalModel", "endpoint", "apiKey", "model", "api",
                ReasoningPolicy.preferenceKey(ENDPOINT, MODEL, "chat"),
                ReasoningPolicy.preferenceKey(ENDPOINT, MODEL, "responses"))) {
                saved.put(key, prefs.getAll().get(key));
            }
            changed = true;
            ModelsDevCatalog.publish(ModelsDevCatalog.parse("""
                {"fixture":{"api":"https://catalog-fixture.invalid/v1",
                "npm":"@ai-sdk/openai-compatible","models":{"future-effort-fixture":{
                "reasoning":true,"temperature":false,"reasoning_options":[
                {"type":"effort","values":["low","max"]}]}}}}
                """));
            for (String api : List.of("chat", "responses")) verifyPolicy(prefs, api);
            report.putString("stream", "MODELS_DEV PASS: " + (remote
                ? "public catalog cached; " : "")
                + "provider policy, Bundle parcel round trip, legal/stale/invalid selections, "
                + "Chat/Responses final wire parameters; no model request\n");
            result = Activity.RESULT_OK;
        } catch (Throwable failure) {
            report.putString("stream", "MODELS_DEV FAIL: " + android.util.Log.getStackTraceString(failure));
        } finally {
            if (changed) {
                ModelsDevCatalog.publish(previous);
                SharedPreferences.Editor editor = prefs.edit();
                for (var entry : saved.entrySet()) {
                    Object value = entry.getValue();
                    if (value == null) editor.remove(entry.getKey());
                    else if (value instanceof String text) editor.putString(entry.getKey(), text);
                    else if (value instanceof Boolean flag) editor.putBoolean(entry.getKey(), flag);
                    else throw new AssertionError("Unexpected saved fixture preference type");
                }
                boolean restored = editor.commit();
                Map<String, ?> current = prefs.getAll();
                for (var entry : saved.entrySet()) {
                    restored &= java.util.Objects.equals(entry.getValue(), current.get(entry.getKey()));
                }
                if (!restored) {
                    result = Activity.RESULT_CANCELED;
                    report.putString("stream", report.getString("stream", "") + "FAIL: Fixture settings restore failed\n");
                } else report.putString("stream", report.getString("stream", "")
                    + "Fixture settings and catalog restored and verified.\n");
            }
        }
        instrumentation.finish(result, report);
    }

    private void awaitRemoteCache() throws Exception {
        ModelsDevCache.start(instrumentation.getTargetContext());
        long deadline = SystemClock.elapsedRealtime() + 90_000;
        long nextProgress = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            ModelsDevCache.Status status = ModelsDevCache.status();
            if (status.loaded() && !status.refreshing() && status.modelCount() > 0) {
                check(status.checkedAt() > 0, "Cache has no successful validation time");
                check(new File(instrumentation.getTargetContext().getFilesDir(), "models-dev/catalog.bin").isFile(),
                    "Catalog snapshot was not persisted");
                ModelsDevCache.Engine restored = new ModelsDevCache.Engine(
                    new File(instrumentation.getTargetContext().getFilesDir(), "models-dev"),
                    (etag, modified) -> { throw new AssertionError("Fresh disk cache attempted network access"); },
                    status::checkedAt, (event, models, providers, age, detail) -> { });
                restored.refresh();
                check(restored.status().modelCount() == status.modelCount()
                    && restored.status().providerCount() == status.providerCount()
                    && restored.status().checkedAt() == status.checkedAt(), "Disk snapshot round trip failed");
                Bundle progress = new Bundle();
                progress.putString("stream", "Public catalog ready: providers=" + status.providerCount()
                    + ", models=" + status.modelCount() + ", checkedAt=" + status.checkedAt() + "\n");
                instrumentation.sendStatus(1, progress);
                return;
            }
            if (SystemClock.elapsedRealtime() >= nextProgress) {
                Bundle progress = new Bundle();
                progress.putString("stream", "Waiting for public models.dev catalog cache\n");
                instrumentation.sendStatus(1, progress);
                nextProgress = SystemClock.elapsedRealtime() + 15_000;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Public models.dev cache did not become ready");
    }

    private void verifyPolicy(SharedPreferences prefs, String api) throws Exception {
        check(prefs.edit().putBoolean("globalModel", true).putString("endpoint", ENDPOINT)
            .putString("apiKey", "").putString("model", MODEL).putString("api", api)
            .remove(ReasoningPolicy.preferenceKey(ENDPOINT, MODEL, api)).commit(), "Fixture save failed");
        Bundle config = modelConfig();
        ReasoningPolicy.Plan plan = ReasoningPolicy.Plan.decode(config.getString("reasoningPolicy"));
        check(plan != null && plan.choices().equals(List.of("default", "low", "max")),
            "Remote policy lost in model_config");
        check("default".equals(config.getString("reasoning")), "Initial effort is not default");
        Bundle selection = new Bundle();
        selection.putString("endpoint", ENDPOINT);
        selection.putString("model", MODEL);
        selection.putString("api", api);
        selection.putString("reasoning", "max");
        Bundle selected = instrumentation.getTargetContext().getContentResolver().call(
            BridgeProvider.URI, "set_reasoning", null, selection);
        check(selected != null && "max".equals(selected.getString("reasoning")), "Valid remote effort rejected");
        config = modelConfig();
        check("max".equals(new Config(instrumentation.getTargetContext()).reasoning), "Config normalized remote effort away");
        check("max".equals(config.getString("reasoning")), "Remote effort not persisted");
        Bundle delivered = parcel(config);
        ReasoningPolicy.Plan transported = ReasoningSettings.plan(delivered);
        check(transported != null && transported.equals(plan), "Parcel changed policy descriptor");
        check(java.util.Arrays.equals(NativeReasoningHook.floatingChecks(delivered),
            new Boolean[]{false, false, true}), "Native menu did not consume remote choices");
        Bundle wrongIdentity = new Bundle(delivered);
        wrongIdentity.putString("reasoningPolicyIdentity", "stale");
        check(ReasoningSettings.plan(wrongIdentity).choices().equals(List.of("default")),
            "Unbound descriptor was trusted");
        JSONObject body = new JSONObject("{\"temperature\":0,\"top_p\":1,\"thinking\":{},\"enable_thinking\":false}");
        transported.apply(body, MODEL, api, "max");
        check(!body.has("temperature") && !body.has("top_p"), "Remote sampling contract ignored");
        check(!body.has("thinking") && !body.has("enable_thinking"), "Stock thinking parameters leaked");
        check("max".equals("responses".equals(api)
            ? body.getJSONObject("reasoning").getString("effort") : body.getString("reasoning_effort")),
            "Wrong final wire reasoning field");
        selection.putString("reasoning", "high");
        try {
            instrumentation.getTargetContext().getContentResolver().call(BridgeProvider.URI, "set_reasoning", null, selection);
            throw new AssertionError("Unsupported remote effort accepted");
        } catch (IllegalArgumentException expected) { }
        selection.putString("reasoning", "low");
        selection.putString("model", "stale-model");
        try {
            instrumentation.getTargetContext().getContentResolver().call(BridgeProvider.URI, "set_reasoning", null, selection);
            throw new AssertionError("Stale remote model accepted");
        } catch (IllegalStateException expected) { }
        check("max".equals(modelConfig().getString("reasoning")), "Rejected selection changed effort");
    }

    private Bundle modelConfig() {
        Bundle result = instrumentation.getTargetContext().getContentResolver().call(BridgeProvider.URI, "model_config", null, null);
        check(result != null, "model_config unavailable");
        return result;
    }

    private static Bundle parcel(Bundle original) {
        Parcel data = Parcel.obtain();
        try {
            data.writeBundle(original);
            data.setDataPosition(0);
            return data.readBundle(ModelsDevRunner.class.getClassLoader());
        } finally { data.recycle(); }
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
