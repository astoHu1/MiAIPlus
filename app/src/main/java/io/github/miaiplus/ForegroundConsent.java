package io.github.miaiplus;

import android.os.Bundle;
import android.os.SystemClock;
import java.util.UUID;

/** A short-lived, owner/task-scoped grant surfaced exclusively through XiaoAi's focus notification. */
final class ForegroundConsent {
    static final long TIMEOUT_MS = 60_000;
    private static final String TERMINAL_PREFIX = "PAUSE：当前应用使用请求已结束。";
    private static Request pending;
    static final class Paused extends IllegalStateException {
        Paused(String message) { super(TERMINAL_PREFIX + (message.startsWith("PAUSE：") ? message.substring(6) : message)); }
    }
    static boolean endsNativeTurn(String text) {
        if (text == null) return false;
        if (text.startsWith(TERMINAL_PREFIX)) return true;
        // The compatibility CLI unwraps only its known response envelope, never arbitrary page/tree fields.
        if (!text.startsWith("{")) return false;
        try {
            org.json.JSONObject value = new org.json.JSONObject(text);
            Object data = value.opt("data");
            if (value.optInt("__envelope__") == 1 && value.optBoolean("ok")) {
                if (data instanceof String plain) return plain.startsWith(TERMINAL_PREFIX);
                if (data instanceof org.json.JSONObject result) return terminalTextResult(result);
            }
            return terminalTextResult(value);
        } catch (org.json.JSONException invalid) { return false; }
    }
    private static boolean terminalTextResult(org.json.JSONObject result) {
        return result.optString("type").equals("text") && result.opt("text") instanceof String plain
            && plain.startsWith(TERMINAL_PREFIX);
    }
    static boolean endsNativeToolTurn(String name, String arguments, String text) {
        if (!endsNativeTurn(text)) return false;
        if (NativePhoneUseTool.NAME.equals(name) || NativePhoneUseTool.BACKGROUND_NAME.equals(name)
            || "voiceassist__controlApp".equals(name)) return true;
        if (!"cli".equals(name)) return false;
        try {
            String command = new org.json.JSONObject(arguments).optString("command").stripLeading();
            String entry = "voiceassist__controlApp";
            return command.equals(entry) || (command.startsWith(entry) && command.length() > entry.length()
                && Character.isWhitespace(command.charAt(entry.length())));
        } catch (Exception invalid) { return false; }
    }

    static final class Request {
        final String token, task, packageName, label;
        final int owner;
        final long expires;
        private Boolean accepted;
        Request(String task, int owner, String packageName, String label, long now) {
            token = UUID.randomUUID().toString();
            this.task = task; this.owner = owner; this.packageName = packageName; this.label = label;
            expires = now + TIMEOUT_MS;
        }
        synchronized boolean respond(String task, int owner, String token, boolean allow, long now) {
            if (accepted != null || now >= expires || this.owner != owner || !this.task.equals(task)
                || !this.token.equals(token)) return false;
            accepted = allow;
            return true;
        }
        synchronized Boolean answer() { return accepted; }
    }

    static void await(RunState run, String packageName, String label) throws Exception {
        Request request = new Request(BridgeProvider.screenTaskFor(run.owner), run.owner, packageName, label,
            SystemClock.elapsedRealtime());
        synchronized (ForegroundConsent.class) {
            if (pending != null) throw new Paused("PAUSE：已有前台应用使用请求，任务已暂停");
            pending = request;
        }
        run.progress = "等待你同意使用当前应用：" + TaskProgress.compact(label);
        try {
            while (request.answer() == null) {
                run.check();
                if (SystemClock.elapsedRealtime() >= request.expires)
                    throw new Paused("PAUSE：未收到使用当前应用的同意，任务已暂停，本轮已结束");
                run.pause(100);
            }
            run.check();
            if (!request.answer())
                throw new Paused("PAUSE：你已拒绝使用当前应用，任务已暂停，本轮已结束；不要继续调用控屏工具");
        } finally {
            synchronized (ForegroundConsent.class) { if (pending == request) pending = null; }
        }
    }

    static synchronized void describe(Bundle result, String task, int owner) {
        Request request = pending;
        if (request == null || request.owner != owner || !request.task.equals(task)
            || request.answer() != null || SystemClock.elapsedRealtime() >= request.expires) return;
        result.putString("foreground_consent", request.token);
        result.putString("foreground_package", request.packageName);
        result.putString("foreground_label", TaskProgress.compact(request.label));
    }

    static synchronized boolean respond(String task, int owner, Bundle extras) {
        Request request = pending;
        return request != null && extras != null && request.respond(task, owner,
            extras.getString("token", ""), extras.getBoolean("allow"), SystemClock.elapsedRealtime());
    }

    private ForegroundConsent() {}
}
