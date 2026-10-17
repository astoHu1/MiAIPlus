package io.github.miaiplus;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Debug-only activity that draws with {@code FLAG_SECURE} set.
 *
 * <p>Exists to answer one question about the background display's capture path: whether reading the display's own
 * output honours the secure-window policy the same way {@code screencap} does. The two paths are not obviously
 * equivalent — {@code screencap} asks the compositor for a screenshot subject to that policy, whereas the reader
 * consumes the display's output surface directly — so if the reader returned secure content, the agent could see
 * pixels the user's own screen protects.
 *
 * <p>Never shipped: this lives in the debug source set. The marker text is deliberately unique so a test can tell
 * whether the frame it captured contains the secure content or the blank surface the policy should produce.
 */
public final class SecureFixtureActivity extends Activity {
    /** Present in the frame only if the secure-content policy was not applied to this display's output. */
    public static final String MARKER = "SECURE-CONTENT-LEAK-CANARY";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        TextView text = new TextView(this);
        text.setText(MARKER);
        text.setTextSize(48);
        text.setPadding(40, 200, 40, 40);
        setContentView(text);
    }
}
