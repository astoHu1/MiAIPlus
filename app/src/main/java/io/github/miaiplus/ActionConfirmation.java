package io.github.miaiplus;

import android.os.Bundle;
import android.os.SystemClock;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/** One sensitive action, authorized only by its task's focus-notification buttons. */
final class ActionConfirmation {
    static final long TIMEOUT_MS = 60_000;
    private static Request pending;

    static String message(Action action) {
        String message = action.data.optString("message");
        return message.isBlank() ? TaskProgress.intent(action) : message;
    }

    static final class Request {
        final String task, token, message;
        final RunState run;
        final long expires;
        private Boolean answer;

        Request(String task, RunState run, String message, long now) {
            this.task = task; this.run = run; this.message = message;
            token = UUID.randomUUID().toString();
            expires = now + TIMEOUT_MS;
        }
        synchronized boolean respond(String task, int owner, String token, boolean allow, long now) {
            if (answer != null || run.cancelled.get() || now >= expires || now >= run.deadline
                || run.owner != owner || !this.task.equals(task) || !this.token.equals(token)) return false;
            answer = allow;
            return true;
        }
        synchronized Boolean answer() { return answer; }
    }

    static boolean await(String message, RunState run) throws Exception {
        run.check();
        // Never silently truncate the operation that the user is asked to authorize.
        if (message == null || message.isBlank() || message.length() > 2000)
            throw new IllegalArgumentException("操作说明为空或过长，无法请求确认");
        Request request = new Request(BridgeProvider.screenTaskFor(run.owner), run, message.strip(),
            SystemClock.elapsedRealtime());
        synchronized (ActionConfirmation.class) {
            if (pending != null) throw new IllegalStateException("已有操作等待确认");
            pending = request;
        }
        try {
            while (request.answer() == null) {
                run.check();
                if (SystemClock.elapsedRealtime() >= request.expires) {
                    run.cancel();
                    throw new CancellationException("操作确认超时，任务已停止");
                }
                run.pause(100);
            }
            run.check();
            if (!request.answer()) run.cancel();
            return request.answer();
        } finally {
            synchronized (ActionConfirmation.class) { if (pending == request) pending = null; }
        }
    }

    static synchronized void describe(Bundle result, String task, int owner) {
        Request request = pending;
        long now = SystemClock.elapsedRealtime();
        if (request == null || request.run.owner != owner || !request.task.equals(task)
            || request.run.cancelled.get() || request.answer() != null || now >= request.expires
            || now >= request.run.deadline) return;
        result.putString("action_confirmation", request.token);
        result.putString("confirmation_message", request.message);
    }

    static synchronized boolean respond(String task, int owner, Bundle extras) {
        Request request = pending;
        return request != null && extras != null && request.respond(task, owner,
            extras.getString("token", ""), extras.getBoolean("allow"), SystemClock.elapsedRealtime());
    }

    private ActionConfirmation() { }
}
