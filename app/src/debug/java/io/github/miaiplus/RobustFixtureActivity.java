package io.github.miaiplus;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Self-owned physical page for dynamic-tree and stale-coordinate device acceptance. */
public final class RobustFixtureActivity extends Activity {
    public static volatile RobustFixtureActivity current;
    public static volatile int clicks, touchDowns, ticks;
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private Button target;
    private TextView counter, ticker;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            ticker.setText("无关刷新计数 " + (++ticks));
            main.postDelayed(this, 100);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        clicks = 0; touchDowns = 0; ticks = 0;
        root = new FrameLayout(this);
        root.setBackgroundColor(0xfff5f5f5);
        TextView title = new TextView(this);
        title.setText("MiAIPlus 稳健控屏自有测试页");
        title.setTextColor(0xff111111);
        title.setTextSize(18);
        root.addView(title, position(330, 48, 48));
        ticker = new TextView(this);
        ticker.setId(android.R.id.text1);
        ticker.setTextColor(0xff111111);
        root.addView(ticker, position(330, 48, 112));
        counter = new TextView(this);
        counter.setId(android.R.id.text2);
        counter.setTextColor(0xff111111);
        counter.setText("已点击 0 次");
        root.addView(counter, position(330, 48, 176));
        addTarget(false, 300);
        setContentView(root);
        current = this;
        main.post(refresh);
    }

    private FrameLayout.LayoutParams position(int width, int height, int top) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(width), dp(height), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = dp(top);
        return params;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void addTarget(boolean replacement, int top) {
        target = new Button(this);
        target.setId(replacement ? android.R.id.button2 : android.R.id.button1);
        target.setText(replacement ? "替换后的目标" : "稳健点击目标");
        target.setAllCaps(false);
        target.setOnClickListener(view -> { clicks++; counter.setText("已点击 " + clicks + " 次"); });
        root.addView(target, position(240, 64, top));
    }
    /** Called only by instrumentation in the module process while the host waits at an acknowledged stage. */
    static void shiftTarget() {
        RobustFixtureActivity fixture = current;
        if (fixture == null) throw new AssertionError("owned dynamic fixture disappeared");
        fixture.target.setLayoutParams(fixture.position(240, 64, 460));
    }
    static void replaceTarget() {
        RobustFixtureActivity fixture = current;
        if (fixture == null) throw new AssertionError("owned dynamic fixture disappeared");
        fixture.root.removeView(fixture.target);
        fixture.addTarget(true, 460);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) touchDowns++;
        return super.dispatchTouchEvent(event);
    }
    @Override public void onDestroy() {
        main.removeCallbacks(refresh);
        if (current == this) current = null;
        super.onDestroy();
    }
}
