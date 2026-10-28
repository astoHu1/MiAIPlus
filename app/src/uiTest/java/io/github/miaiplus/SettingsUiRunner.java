package io.github.miaiplus;

import android.app.Instrumentation;
import android.os.Bundle;

/** UI-only instrumentation, also usable against the optimized preview variant. */
public final class SettingsUiRunner extends Instrumentation {
    private boolean virtual;
    private boolean virtualIdle;
    private boolean direct;
    private boolean performance;
    private boolean logs;
    private boolean plugins;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        logs = arguments != null && arguments.getString("logs", "false").equals("true");
        plugins = arguments != null && arguments.getString("plugins", "false").equals("true");
        virtual = arguments != null && arguments.getString("virtual", "false").equals("true");
        virtualIdle = arguments != null && arguments.getString("virtualIdle", "false").equals("true");
        direct = arguments != null && arguments.getString("direct", "false").equals("true");
        performance = arguments != null && arguments.getString("performance", "false").equals("true");
        start();
    }
    @Override public void onStart() {
        if (plugins) EndpointProfilesRunner.runPlugins(this);
        else if (logs) EndpointProfilesRunner.runLogs(this);
        else if (performance) EndpointProfilesRunner.runPerformance(this);
        else if (direct) EndpointProfilesRunner.runDirect(this);
        else if (virtualIdle) EndpointProfilesRunner.runVirtualIdle(this);
        else if (virtual) EndpointProfilesRunner.runVirtual(this);
        else EndpointProfilesRunner.run(this);
    }
}
