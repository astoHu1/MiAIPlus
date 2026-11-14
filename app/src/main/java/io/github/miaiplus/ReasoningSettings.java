package io.github.miaiplus;

import android.os.Bundle;

/** The module resolves a policy once; the host consumes only this identity-bound IPC snapshot. */
final class ReasoningSettings {
    private ReasoningSettings() {}
    static void attach(Bundle bundle, Config config) {
        bundle.putString("reasoningPolicy", config.reasoningPolicy.encode());
        bundle.putString("reasoningPolicyIdentity", ReasoningPolicy.preferenceKey(config.endpoint, config.model, config.api));
    }
    static ReasoningPolicy.Plan plan(Bundle bundle) {
        String model = bundle.getString("model", "");
        String api = bundle.getString("api", "chat");
        String identity = ReasoningPolicy.preferenceKey(bundle.getString("endpoint", ""), model, api);
        if (identity.equals(bundle.getString("reasoningPolicyIdentity"))) {
            ReasoningPolicy.Plan plan = ReasoningPolicy.Plan.decode(bundle.getString("reasoningPolicy"));
            if (plan != null) return plan;
        }
        return ReasoningPolicy.builtin(model, api);
    }
}
