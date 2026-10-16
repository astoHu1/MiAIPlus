package io.github.miaiplus;

import android.app.Instrumentation;
import android.os.Bundle;

/** UI-only instrumentation, also usable against the optimized preview variant. */
public final class SettingsUiRunner extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() { EndpointProfilesRunner.run(this); }
}
