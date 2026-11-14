package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.os.Bundle;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public class ReasoningSettingsTest {
    @After public void clearCatalog() { ModelsDevCatalog.publish(null); }

    @Test public void remotePlanSurvivesIpcWithoutHostCatalogAndStaysBoundToIdentity() throws Exception {
        ModelsDevCatalog.publish(ModelsDevCatalog.parse("""
            {"openai":{"id":"openai","npm":"@ai-sdk/openai","models":{
              "future-model":{"id":"future-model","reasoning":true,"temperature":false,
                "reasoning_options":[{"type":"effort","values":["low","high"]}]}}}}
            """));
        String endpoint = "https://gateway.test/v1";
        String model = "openai/future-model";
        Config config = new Config(Map.of("endpoint", endpoint, "model", model, "api", "responses",
            ReasoningPolicy.preferenceKey(endpoint, model, "responses"), "high"));
        assertEquals("high", config.reasoning);
        Bundle bundle = new Bundle();
        bundle.putString("endpoint", endpoint); bundle.putString("model", model); bundle.putString("api", "responses");
        ReasoningSettings.attach(bundle, config);
        ModelsDevCatalog.publish(null);
        ReasoningPolicy.Plan received = ReasoningSettings.plan(bundle);
        assertEquals(List.of("default", "low", "high"), received.choices());
        JSONObject body = new JSONObject().put("temperature", 0);
        received.apply(body, model, "responses", "high");
        assertEquals("high", body.getJSONObject("reasoning").getString("effort"));
        assertFalse(body.has("temperature"));
        bundle.putString("model", "unrelated-alias");
        assertEquals(List.of("default"), ReasoningSettings.plan(bundle).choices());
        bundle.putString("model", model); bundle.putString("api", "chat");
        assertEquals(List.of("default"), ReasoningSettings.plan(bundle).choices());
    }

    @Test public void missingOrMalformedDescriptorFallsBackToBuiltin() {
        Bundle bundle = new Bundle(); bundle.putString("model", "gpt-6.1-sol"); bundle.putString("api", "responses");
        assertEquals(ReasoningPolicy.builtin("gpt-6.1-sol", "responses"), ReasoningSettings.plan(bundle));
        bundle.putString("reasoningPolicyIdentity", ReasoningPolicy.preferenceKey("", "gpt-6.1-sol", "responses"));
        bundle.putString("reasoningPolicy", "{broken");
        assertEquals(ReasoningPolicy.builtin("gpt-6.1-sol", "responses"), ReasoningSettings.plan(bundle));
    }
}
