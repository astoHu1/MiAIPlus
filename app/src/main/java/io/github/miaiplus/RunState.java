package io.github.miaiplus;

import android.os.SystemClock;
import java.net.HttpURLConnection;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RunState {
    public final String id;
    public final int owner;
    public final AtomicBoolean cancelled = new AtomicBoolean();
    public final long deadline = SystemClock.elapsedRealtime() + 240_000;
    public volatile String status = "running", result = "", progress = "准备执行";
    public volatile HttpURLConnection connection;
    public boolean screen;
    public volatile boolean preparingScreen;
    final java.util.concurrent.CountDownLatch screenReady = new java.util.concurrent.CountDownLatch(1);
    public RunState(String id, int owner) { this.id = id; this.owner = owner; }
    public void check() {
        if (cancelled.get()) throw new CancellationException("任务已停止");
        if (SystemClock.elapsedRealtime() >= deadline) { cancel(); throw new CancellationException("任务超过 4 分钟，已停止"); }
    }
    public void cancel() {
        cancelled.set(true);
        screenReady.countDown();
        HttpURLConnection c = connection;
        if (c != null) c.disconnect();
    }
    public void pause(long millis) throws InterruptedException {
        long until = SystemClock.elapsedRealtime() + millis;
        while (SystemClock.elapsedRealtime() < until) {
            check(); Thread.sleep(Math.min(100, Math.max(1, until - SystemClock.elapsedRealtime())));
        }
    }
}
