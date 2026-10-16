package io.github.miaiplus;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Separate test-APK launcher: opening it never accesses personal app content. */
public final class LaunchFixtureActivity extends Activity {
    private static int creates;
    private final android.content.BroadcastReceiver cleanup = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
            finishAndRemoveTask();
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        registerReceiver(cleanup, new android.content.IntentFilter("io.github.miaiplus.CLOSE_LAUNCH_FIXTURE"),
            "android.permission.DUMP", null, android.content.Context.RECEIVER_EXPORTED);
        TextView text = new TextView(this);
        text.setText("MiAIPlus launch fixture, instance " + ++creates);
        text.setTextSize(24); text.setPadding(40, 120, 40, 40); setContentView(text);
    }
    @Override public void onDestroy() {
        unregisterReceiver(cleanup);
        super.onDestroy();
    }
}
