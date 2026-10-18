package io.github.miaiplus;

import android.app.Instrumentation;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

/** Latches the first loss of the user's keyboard, including losses recovered before the next action finishes. */
final class PhysicalImeGuard implements AutoCloseable {
    private final Instrumentation test;
    private final FixtureActivity activity;
    private final EditText input;
    private final View decor;
    private final InputMethodManager ime;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile String stage = "baseline";
    private volatile String failure;
    private volatile int samples;
    private boolean observing;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!observing) return;
            sample();
            main.postDelayed(this, 16);
        }
    };
    private final ViewTreeObserver.OnPreDrawListener draw = () -> { sample(); return true; };
    private final ViewTreeObserver.OnWindowFocusChangeListener windowFocus = focused -> {
        if (!focused) lost("window-focus event");
    };
    private final ViewTreeObserver.OnGlobalFocusChangeListener fieldFocus = this::fieldFocusChanged;

    private PhysicalImeGuard(Instrumentation test, FixtureActivity activity, EditText input) {
        this.test = test;
        this.activity = activity;
        this.input = input;
        decor = activity.getWindow().getDecorView();
        ime = activity.getSystemService(InputMethodManager.class);
    }

    static PhysicalImeGuard start(Instrumentation test) throws Exception {
        PhysicalImeGuard[] result = new PhysicalImeGuard[1];
        test.runOnMainSync(() -> {
            if (FixtureActivity.current == null || FixtureActivity.input == null)
                throw new AssertionError("physical IME fixture is unavailable");
            result[0] = new PhysicalImeGuard(test, FixtureActivity.current, FixtureActivity.input);
            FixtureActivity.input.requestFocus();
        });
        PhysicalImeGuard guard = result[0];
        if (guard.ime == null) throw new AssertionError("physical fixture has no input method service");
        long deadline = SystemClock.uptimeMillis() + 10_000, stableSince = 0;
        boolean[] ready = new boolean[1], requested = new boolean[1];
        while (SystemClock.uptimeMillis() < deadline) {
            test.runOnMainSync(() -> {
                if (!requested[0] && guard.activity.hasWindowFocus() && guard.input.hasFocus()) {
                    requested[0] = true;
                    guard.ime.showSoftInput(guard.input, InputMethodManager.SHOW_IMPLICIT);
                }
                ready[0] = guard.problem() == null;
            });
            long now = SystemClock.uptimeMillis();
            if (!ready[0]) stableSince = 0;
            else if (stableSince == 0) stableSince = now;
            else if (now - stableSince >= 500) {
                test.runOnMainSync(() -> {
                    guard.observing = true;
                    var observer = guard.decor.getViewTreeObserver();
                    observer.addOnPreDrawListener(guard.draw);
                    observer.addOnWindowFocusChangeListener(guard.windowFocus);
                    observer.addOnGlobalFocusChangeListener(guard.fieldFocus);
                    guard.poll.run();
                });
                try {
                    guard.assertHeld("baseline-ready");
                    return guard;
                } catch (AssertionError failure) {
                    guard.close();
                    throw failure;
                }
            }
            Thread.sleep(25);
        }
        String[] problem = new String[1];
        test.runOnMainSync(() -> problem[0] = guard.problem());
        throw new AssertionError("could not establish a stable, visible physical keyboard: " + problem[0]);
    }

    void assertHeld(String nextStage) {
        test.runOnMainSync(this::sample);
        if (failure != null) throw new AssertionError(failure);
        stage = nextStage;
    }

    private String problem() {
        if (FixtureActivity.current != activity || FixtureActivity.input != input || !input.isAttachedToWindow())
            return "fixture replaced/detached";
        if (!activity.hasWindowFocus()) return "window lost focus";
        if (!input.hasFocus()) return "editable lost focus";
        WindowInsets insets = decor.getRootWindowInsets();
        if (insets == null || !insets.isVisible(WindowInsets.Type.ime())) return "IME became invisible";
        if (ime == null || !ime.isActive(input)) return "IME stopped serving physical editable";
        return null;
    }

    private void sample() {
        if (!observing) return;
        samples++;
        String problem = problem();
        if (problem != null) lost(problem);
    }

    private void lost(String reason) {
        if (observing && failure == null)
            failure = "physical keyboard isolation failed during " + stage + ": " + reason
                + " (sample " + samples + ")";
    }

    private void fieldFocusChanged(View oldFocus, View newFocus) {
        if (newFocus != input) lost("editable-focus event");
    }

    String evidence() { return "physicalImeSamples=" + samples + "; physicalImeLoss=" + failure + "; "; }

    @Override public void close() {
        test.runOnMainSync(() -> {
            sample();
            observing = false;
            main.removeCallbacks(poll);
            var observer = decor.getViewTreeObserver();
            if (!observer.isAlive()) return;
            observer.removeOnPreDrawListener(draw);
            observer.removeOnWindowFocusChangeListener(windowFocus);
            observer.removeOnGlobalFocusChangeListener(fieldFocus);
        });
    }
}
