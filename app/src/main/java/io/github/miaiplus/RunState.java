package io.github.miaiplus;

import android.os.SystemClock;
import java.net.HttpURLConnection;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RunState {
    /**
     * Budget for a single step, not for the whole task.
     *
     * <p>A control session is many tool calls, each its own run, and the native planner keeps one session across
     * all of them. A fixed deadline measured from the first run therefore expired in the middle of a long task and
     * every later call was rejected as an expired session — the reported "会话反复过期" — even though the display
     * and the app were still healthy. The deadline is now reserved per step and extended by each accepted call, so
     * a slow but progressing task keeps its session while a wedged one still stops.
     */
    public static final long STEP_BUDGET_MS = 240_000;
    public final String id;
    public final int owner;
    public final AtomicBoolean cancelled = new AtomicBoolean();
    public volatile long deadline = SystemClock.elapsedRealtime() + STEP_BUDGET_MS;
    public volatile String status = "running", result = "", progress = "准备执行";
    public volatile HttpURLConnection connection;
    public boolean screen;
    public volatile boolean preparingScreen;
    final java.util.concurrent.CountDownLatch screenReady = new java.util.concurrent.CountDownLatch(1);
    private final java.util.Set<CancellationBinding> cancellationBindings = new java.util.HashSet<>();
    public RunState(String id, int owner) { this.id = id; this.owner = owner; }
    /** Extends the budget from now; called when the caller shows the session is still in use. */
    public void extend() { deadline = SystemClock.elapsedRealtime() + STEP_BUDGET_MS; }
    public void check() {
        if (cancelled.get()) throw new CancellationException("任务已停止");
        if (SystemClock.elapsedRealtime() >= deadline) { cancel(); throw new CancellationException("单步超过 4 分钟没有进展，已停止"); }
    }
    public void cancel() {
        cancelled.set(true);
        screenReady.countDown();
        CancellationBinding[] bindings;
        synchronized (cancellationBindings) {
            bindings = cancellationBindings.toArray(new CancellationBinding[0]);
            cancellationBindings.clear();
        }
        for (CancellationBinding binding : bindings) binding.fire();
        HttpURLConnection c = connection;
        if (c != null) c.disconnect();
    }
    /** Links only the currently executing step, including cancellation before that step could bind. */
    CancellationBinding onCancel(Runnable callback) {
        CancellationBinding binding = new CancellationBinding(callback);
        boolean alreadyCancelled;
        synchronized (cancellationBindings) {
            alreadyCancelled = cancelled.get();
            if (!alreadyCancelled) cancellationBindings.add(binding);
        }
        if (alreadyCancelled) binding.fire();
        return binding;
    }
    /** Closing the binding retires a completed step, so its old IPC id cannot cancel later work. */
    final class CancellationBinding implements AutoCloseable {
        private Runnable callback;
        private CancellationBinding(Runnable callback) { this.callback = java.util.Objects.requireNonNull(callback); }
        private void fire() {
            Runnable current;
            synchronized (this) { current = callback; callback = null; }
            if (current != null) current.run();
        }
        @Override public void close() {
            synchronized (this) { callback = null; }
            synchronized (cancellationBindings) { cancellationBindings.remove(this); }
        }
    }
    public void pause(long millis) throws InterruptedException {
        long until = SystemClock.elapsedRealtime() + millis;
        while (SystemClock.elapsedRealtime() < until) {
            check(); Thread.sleep(Math.min(100, Math.max(1, until - SystemClock.elapsedRealtime())));
        }
    }
}
