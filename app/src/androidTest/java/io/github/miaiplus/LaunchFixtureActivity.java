package io.github.miaiplus;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Button;
import android.widget.LinearLayout;

/** Separate test-APK launcher: opening it never accesses personal app content. */
public final class LaunchFixtureActivity extends Activity {
    private static int creates;
    private TextView text;
    private int instance, touchDowns;
    private final android.content.BroadcastReceiver cleanup = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
            finishAndRemoveTask();
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        registerReceiver(cleanup, new android.content.IntentFilter("io.github.miaiplus.CLOSE_LAUNCH_FIXTURE"),
            "android.permission.DUMP", null, android.content.Context.RECEIVER_EXPORTED);
        instance = ++creates;
        text = new TextView(this);
        updateText();
        text.setTextSize(24); text.setPadding(40, 120, 40, 40);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.addView(text);
        Button chooser = new Button(this); chooser.setText("Open owned chooser"); chooser.setAllCaps(false);
        chooser.setContentDescription("MiAIPlus owned chooser trigger");
        chooser.setOnClickListener(view -> {
            // Real framework UI, with only explicit targets from this test APK and no personal content.
            android.content.Intent first = new android.content.Intent(this, ChoiceA.class)
                .setAction(android.content.Intent.ACTION_VIEW);
            android.content.Intent second = new android.content.Intent(this, ChoiceB.class)
                .setAction(android.content.Intent.ACTION_VIEW);
            android.content.Intent intent = android.content.Intent.createChooser(first, "选择应用");
            intent.putExtra(android.content.Intent.EXTRA_INITIAL_INTENTS, new android.os.Parcelable[]{second});
            startActivity(intent);
        });
        root.addView(chooser); setContentView(root);
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) { touchDowns++; updateText(); }
        return super.dispatchTouchEvent(event);
    }
    private void updateText() { text.setText("MiAIPlus launch fixture, instance " + instance + "; touch downs " + touchDowns); }
    @Override public void onDestroy() {
        unregisterReceiver(cleanup);
        super.onDestroy();
    }

    /** Both picker destinations are inert and are removed by the launch runner's existing cleanup. */
    public static class ChoiceA extends Activity {
        private final android.content.BroadcastReceiver cleanup = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
                finishAndRemoveTask();
            }
        };
        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            registerReceiver(cleanup, new android.content.IntentFilter("io.github.miaiplus.CLOSE_LAUNCH_FIXTURE"),
                "android.permission.DUMP", null, android.content.Context.RECEIVER_EXPORTED);
            TextView label = new TextView(this); label.setText("MiAIPlus owned choice "
                + (getClass() == ChoiceA.class ? "A" : "B") + " selected");
            label.setTextSize(24); label.setPadding(40, 120, 40, 40); setContentView(label);
        }
        @Override public void onDestroy() { unregisterReceiver(cleanup); super.onDestroy(); }
    }

    public static final class ChoiceB extends ChoiceA { }
}
