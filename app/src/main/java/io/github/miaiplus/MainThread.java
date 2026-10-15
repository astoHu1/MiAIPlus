package io.github.miaiplus;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class MainThread {
    private MainThread() {}
    static <T> T call(Callable<T> action) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return action.call();
        CompletableFuture<T> result = new CompletableFuture<>();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable posted = () -> {
            if (result.isCancelled()) return;
            try { result.complete(action.call()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        };
        handler.post(posted);
        try { return result.get(5, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException wrapped) {
            Throwable failure = wrapped.getCause();
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw wrapped;
        }
        finally { result.cancel(false); handler.removeCallbacks(posted); }
    }
}
