package io.github.miaiplus;

import android.app.Instrumentation;
import android.os.Bundle;

/** UI-only instrumentation, also usable against the optimized preview variant. */
public final class SettingsUiRunner extends Instrumentation {
    private boolean virtual;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        virtual = arguments != null && arguments.getString("virtual", "false").equals("true");
        start();
    }
    @Override public void onStart() {
        if (virtual) EndpointProfilesRunner.runVirtual(this);
        else EndpointProfilesRunner.run(this);
    }
}
