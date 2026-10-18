package io.github.miaiplus;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Test-only page with an editable field, used to prove that typing reaches the background display.
 *
 * <p>Lives in the test APK rather than the module: a launcher entry in the shipped package would show up in the
 * user's app list, and the point of this fixture is to be launched onto a display the user never sees. It is a
 * separate package from the physical-screen fixture ({@code io.github.miaiplus} vs {@code io.github.miaiplus.test})
 * so that writing into this field and leaving the other one empty is a meaningful assertion rather than an
 * accident of the two sharing a package.
 */
public final class TypingFixtureActivity extends Activity {
    /** Set while this page is visible, so a test can read what the field actually contains. */
    public static volatile EditText input;
    private TextView title;
    private int editorTaps;

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        boolean handled = super.dispatchTouchEvent(event);
        if (handled && event.getActionMasked() == android.view.MotionEvent.ACTION_UP
            && input != null && input.getContext() == this && input.hasFocus()) {
            android.graphics.Rect bounds = new android.graphics.Rect();
            input.getGlobalVisibleRect(bounds);
            if (bounds.contains((int) event.getRawX(), (int) event.getRawY())) {
                editorTaps++;
                title.setText("MiAIPlus background typing fixture; editor taps=" + editorTaps);
            }
        }
        return handled;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(40, 140, 40, 40);
        root.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(this);
        title.setText("MiAIPlus background typing fixture; editor taps=0");
        title.setTextSize(20);
        root.addView(title);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("后台输入测试框");
        root.addView(input);
        setContentView(root);
    }

    @Override public void onDestroy() {
        if (input != null && input.getContext() == this) input = null;
        super.onDestroy();
    }
}
