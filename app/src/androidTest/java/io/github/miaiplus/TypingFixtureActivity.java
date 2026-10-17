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
    /** Records every touch this page receives, to show input addressed here never lands elsewhere. */
    public static volatile String lastTouch = "none";

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        lastTouch = event.getActionMasked() + ":" + event.getX() + "," + event.getY();
        return super.dispatchTouchEvent(event);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(40, 140, 40, 40);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("MiAIPlus background typing fixture");
        title.setTextSize(20);
        root.addView(title);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("后台输入测试框");
        root.addView(input);
        setContentView(root);
        lastTouch = "none";
    }

    @Override public void onDestroy() {
        if (input != null && input.getContext() == this) input = null;
        super.onDestroy();
    }
}
