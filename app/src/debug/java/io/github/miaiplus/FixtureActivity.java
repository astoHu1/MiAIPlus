package io.github.miaiplus;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Debug-only fixture; device tests never need to touch personal apps. */
public final class FixtureActivity extends Activity {
    public static volatile FixtureActivity current;
    public static volatile EditText input;
    public static volatile String lastTouch = "none";
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        lastTouch = event.getActionMasked() + ":" + event.getX() + "," + event.getY();
        return super.dispatchTouchEvent(event);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setPadding(40,100,40,40); root.setOrientation(LinearLayout.VERTICAL);
        // API 35+ enforces edge-to-edge. Keep test targets below the status bar/touch region.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(40 + bars.left, 100 + bars.top, 40 + bars.right, 40 + bars.bottom);
            return insets;
        });
        TextView title = new TextView(this); title.setText("MiAIPlus device test fixture"); root.addView(title);
        input = new EditText(this); input.setSingleLine(true); input.setHint("设备测试输入框"); root.addView(input);
        setContentView(root);
        root.requestApplyInsets();
        lastTouch = "none"; current = this;
    }
    @Override public void onDestroy() { if (current == this) { current = null; input = null; } super.onDestroy(); }
}
