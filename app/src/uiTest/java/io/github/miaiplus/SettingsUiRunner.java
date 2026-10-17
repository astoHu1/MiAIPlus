package io.github.miaiplus;

import android.app.Instrumentation;
import android.os.Bundle;

/** UI-only instrumentation, also usable against the optimized preview variant. */
public final class SettingsUiRunner extends Instrumentation {
    private boolean virtual;
    private boolean virtualIdle;
    private boolean direct;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        virtual = arguments != null && arguments.getString("virtual", "false").equals("true");
        virtualIdle = arguments != null && arguments.getString("virtualIdle", "false").equals("true");
        direct = arguments != null && arguments.getString("direct", "false").equals("true");
        start();
    }
    @Override public void onStart() {
        if (direct) EndpointProfilesRunner.runDirect(this);
        else if (virtualIdle) EndpointProfilesRunner.runVirtualIdle(this);
        else if (virtual) EndpointProfilesRunner.runVirtual(this);
        else EndpointProfilesRunner.run(this);
    }
}
