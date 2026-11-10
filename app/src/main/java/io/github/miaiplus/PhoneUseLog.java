package io.github.miaiplus;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in metadata only. Logging failures must never change a phone-use result. */
final class PhoneUseLog {
    static final String TAG = "MiAIPlusPhoneUse";
    private static volatile SharedPreferences preferences;
    private static volatile DiagnosticLogStore store;
    private static final AtomicLong hostDropped = new AtomicLong();
    private static final AtomicLong hostErrors = new AtomicLong();
    private static final AtomicLong remoteGeneration = new AtomicLong();
    private static final java.util.concurrent.ThreadPoolExecutor delivery = new java.util.concurrent.ThreadPoolExecutor(
        0, 1, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(256), runnable -> {
            Thread thread = new Thread(runnable, "MiAIPlus-log-delivery"); thread.setDaemon(true); return thread;
        });
    private static volatile java.lang.ref.WeakReference<Context> remoteContext = new java.lang.ref.WeakReference<>(null);
    private static volatile boolean remoteEnabled;
    private static volatile long remoteCheckedAt = -1;
    private static final AtomicLong sequence = new AtomicLong();
    private static final String writer = java.util.UUID.randomUUID().toString();
    private static final AtomicLong observations = new AtomicLong();
    private static final AtomicLong requests = new AtomicLong();
    private static final ThreadLocal<Trace> current = new ThreadLocal<>();
    private static final ThreadLocal<HostStamp> hostStamp = new ThreadLocal<>();
    private static final java.util.LinkedHashMap<String, Trace> images = new java.util.LinkedHashMap<>();
    record Trace(String session, int step, String observation) { }
    private record HostStamp(long time, long elapsed, long generation, Trace trace) { }

    static void init(Context context) {
        try {
            preferences = Config.prefs(context.getApplicationContext());
            store = DiagnosticLogStore.get(context);
        }
        catch (RuntimeException ignored) { /* Diagnostics are optional. */ }
    }
    static void initRemote(Context context) {
        // Hooks install during Application.attach, before ActivityThread publishes getApplicationContext().
        Context application = context instanceof android.app.Application ? context : context.getApplicationContext();
        remoteContext = new java.lang.ref.WeakReference<>(application);
    }
    static void disposeRemote() {
        remoteGeneration.incrementAndGet(); delivery.getQueue().clear();
        remoteContext.clear(); remoteEnabled = false; remoteCheckedAt = -1;
        synchronized (images) { images.clear(); }
    }
    static boolean enabled() {
        try {
            SharedPreferences prefs = preferences;
            if (prefs != null) return prefs.getBoolean(Config.PHONE_USE_LOG_KEY, false);
            Context context = remoteContext.get();
            if (context == null) return false;
            long now = SystemClock.elapsedRealtime();
            if (remoteCheckedAt < 0 || now - remoteCheckedAt >= 1000) {
                // Stamp first: an unreachable module must not turn every later event into another blocking call.
                remoteCheckedAt = now;
                boolean value = false;
                try {
                    Bundle flags = context.getContentResolver().call(BridgeProvider.URI, "phone_use_log_settings", null, null);
                    value = flags != null && flags.getBoolean("enabled");
                } finally { remoteEnabled = value; }
            }
            return remoteEnabled;
        } catch (RuntimeException ignored) { return false; }
    }
    static Scope scope(String session, int step) { return use(new Trace(reference(session), step, "")); }
    static void bind(String session, int step) { current.set(new Trace(reference(session), step, "")); }
    private static Scope use(Trace trace) {
        Trace previous = current.get();
        if (trace == null) current.remove(); else current.set(trace);
        return () -> { if (previous == null) current.remove(); else current.set(previous); };
    }
    interface Scope extends AutoCloseable { @Override void close(); }
    static <T> Callable<T> propagate(Callable<T> callable) {
        Trace trace = current.get();
        return () -> { try (Scope ignored = use(trace)) { return callable.call(); } };
    }
    static void observation() {
        if (!enabled()) return;
        Trace trace = current.get();
        current.set(new Trace(trace == null ? "" : trace.session(), trace == null ? 0 : trace.step(),
            android.os.Process.myPid() + "-" + observations.incrementAndGet()));
    }
    static JSONObject metadata() {
        if (!enabled()) return null;
        try {
            Trace trace = current.get();
            return new JSONObject().put("session", trace == null ? "" : trace.session())
                .put("step", trace == null ? 0 : trace.step())
                .put("observation", trace == null ? "" : trace.observation());
        } catch (Exception ignored) { return null; }
    }
    static Scope fromMetadata(JSONObject data) {
        return use(data == null ? null : new Trace(data.optString("session"), data.optInt("step"), data.optString("observation")));
    }
    static void event(String event, Object... fields) {
        if (!enabled()) return;
        write(event, System.currentTimeMillis(), SystemClock.elapsedRealtime(), current.get(), fields);
    }
    /**
     * For host hooks that run on XiaoAi's UI thread. Asking the module whether logging is on is a blocking call,
     * so the question and the write move to the delivery thread; the record keeps the caller's time.
     */
    static void hostInspect(Runnable inspect) {
        if (preferences != null) {
            if (enabled()) try { inspect.run(); } catch (Throwable ignored) { }
            return;
        }
        if (remoteContext.get() == null) return;
        HostStamp inherited = hostStamp.get();
        if (inherited != null) {
            if (inherited.generation() == remoteGeneration.get())
                try { inspect.run(); } catch (Throwable ignored) { }
            return;
        }
        long elapsed = SystemClock.elapsedRealtime();
        HostStamp stamp = new HostStamp(System.currentTimeMillis(), elapsed, remoteGeneration.get(), current.get());
        try {
            delivery.execute(() -> {
                if (stamp.generation() != remoteGeneration.get()) return;
                // Queued observers are discarded on reload; one already inspecting host objects must finish
                // before its generation can be retired, just like a synchronous hook callback.
                try { io.github.miaiplus.hook.Hooks.enter(); }
                catch (IllegalStateException retiring) { return; }
                try {
                    if (!enabled()) return;
                    hostStamp.set(stamp);
                    try (Scope ignored = use(stamp.trace())) { inspect.run(); }
                    catch (Throwable ignored) { /* Read-only diagnostics cannot affect the host. */ }
                    finally { hostStamp.remove(); }
                } finally { io.github.miaiplus.hook.Hooks.leave(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException full) { hostDropped.incrementAndGet(); }
    }
    static void hostEvent(String event, Object... fields) {
        if (preferences != null) { event(event, fields); return; }
        if (remoteContext.get() == null) return;
        hostInspect(() -> {
            HostStamp stamp = hostStamp.get();
            if (stamp != null) write(event, stamp.time(), stamp.elapsed(), stamp.trace(), fields);
        });
    }
    private static void write(String event, long time, long elapsed, Trace trace, Object[] fields) {
        try {
            // writer changes on classloader reload, even when pid/version stay the same; seq orders its emitted
            // records. ver is the module code that wrote
            // the record, so a XiaoAi process still running an older module after an update is visible.
            JSONObject record = new JSONObject().put("event", event).put("elapsedMs", elapsed)
                .put("time", time).put("pid", android.os.Process.myPid())
                .put("source", preferences == null ? "host" : "module")
                .put("seq", sequence.incrementAndGet()).put("writer", writer).put("ver", BuildConfig.VERSION_CODE);
            if (trace != null) record.put("session", trace.session()).put("step", trace.step()).put("observation", trace.observation());
            for (int i = 0; i + 1 < fields.length; i += 2) {
                String key = String.valueOf(fields[i]);
                if (java.util.Set.of("event", "time", "elapsedMs", "pid", "source", "seq", "writer", "ver").contains(key)) continue;
                Object value = fields[i + 1];
                if (value instanceof String text) value = text.replace('\n', ' ').replace('\r', ' ').substring(0, Math.min(160, text.length()));
                record.put(key, value);
            }
            Log.i(TAG, record.toString());
            DiagnosticLogStore local = store;
            if (preferences != null && local != null) local.append(record.toString());
            else {
                long generation = remoteGeneration.get();
                String line = record.put("hostDropped", hostDropped.get()).put("hostDeliveryErrors", hostErrors.get()).toString();
                try {
                    // Already on the delivery thread: do not requeue behind newer events or fill our own queue.
                    if (hostStamp.get() != null) deliver(line, generation);
                    else delivery.execute(() -> deliver(line, generation));
                } catch (java.util.concurrent.RejectedExecutionException failure) { hostDropped.incrementAndGet(); }
            }
        } catch (Exception | LinkageError ignored) { /* Never fail an action because logging failed. */ }
    }
    private static void deliver(String line, long generation) {
        Context context = remoteContext.get();
        if (generation != remoteGeneration.get() || context == null) return;
        try {
            Bundle data = new Bundle(); data.putString("record", line);
            context.getContentResolver().call(BridgeProvider.URI, "phone_use_log_append", null, data);
        } catch (RuntimeException | LinkageError failure) { hostErrors.incrementAndGet(); }
    }
    static String reference(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) hex.append(Character.forDigit((digest[i] & 255) >>> 4, 16))
                .append(Character.forDigit(digest[i] & 15, 16));
            return hex.toString();
        } catch (Exception ignored) { return "unavailable"; }
    }
    static void frame(ScreenService.Frame frame) {
        if (!enabled()) return;
        rememberImage(frame.image());
        event("observation", "displayId", frame.displayId(), "generation", reference(frame.generation()),
            "foreground", frame.foreground(), "displayWidth", frame.width(), "displayHeight", frame.height(),
            "imageWidth", frame.imageWidth(), "imageHeight", frame.imageHeight(), "imageChars", frame.image().length(),
            "imageDigest", reference(frame.image()), "treeChars", frame.tree().length());
    }
    static void rememberImage(String image) {
        if (!enabled()) return;
        synchronized (images) {
            images.put(reference(image), current.get());
            while (images.size() > 32) images.remove(images.keySet().iterator().next());
        }
    }
    static void action(String event, Action action, ScreenService.Frame frame) {
        if (!enabled()) return;
        // Deliberately omit text, message, intent, permission tokens and arbitrary model fields.
        event(event, "action", action.name, "displayId", frame == null ? -1 : frame.displayId(),
            "textLength", action.data.optString("text").length(), "confirmation", action.data.optBoolean("requires_confirmation"));
        for (String key : new String[]{"element", "start", "end"}) {
            JSONArray point = action.data.optJSONArray(key);
            if (point != null && point.opt(0) instanceof Number x && point.opt(1) instanceof Number y)
                event("action_coordinates", "point", key, "x", x, "y", y, "space", "normalized");
        }
    }
    static void failure(String stage, Throwable failure, Object... extra) {
        if (!enabled()) return;
        event("failure", concat(new Object[]{"stage", stage}, concat(describe(failure), extra)));
    }
    static void hostFailure(String stage, Throwable failure, Object... extra) {
        hostInspect(() -> hostEvent("failure", concat(new Object[]{"stage", stage}, concat(describe(failure), extra))));
    }
    static Object[] concat(Object[] first, Object[] second) {
        Object[] all = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
    /**
     * What failed and where, without what it said: exception classes, the module's own code locations and host
     * symbol names. Messages stay out because they can carry page text, typed input or a server response.
     *
     * <p>Class and method names are kept as written in every build. Optimized builds renumber lines, so only
     * debug builds cite one; messageRef locates the throw site in the others.
     */
    static Object[] describe(Throwable failure) {
        Throwable top = failure;
        // A future or a reflective call wraps the real failure; the wrapper's class says nothing.
        for (int depth = 0; depth < 8 && top.getCause() != null && top.getCause() != top
            && (top instanceof java.util.concurrent.ExecutionException
            || top instanceof java.util.concurrent.CompletionException
            || top instanceof java.lang.reflect.InvocationTargetException
            || top instanceof java.lang.reflect.UndeclaredThrowableException); depth++) top = top.getCause();
        StringBuilder causes = new StringBuilder();
        String binding = binding(top);
        Throwable deepest = top;
        int depth = 0;
        for (Throwable cause = top.getCause(); cause != null && cause != deepest && depth < 6; cause = cause.getCause(), depth++) {
            if (causes.length() > 0) causes.append('>');
            causes.append(cause.getClass().getSimpleName());
            if (binding.isEmpty()) binding = binding(cause);
            deepest = cause;
        }
        java.util.List<Object> fields = new java.util.ArrayList<>(java.util.List.of(
            "exception", top.getClass().getSimpleName(), "site", site(top, 3)));
        String message = top.getMessage();
        StackTraceElement[] stack = top.getStackTrace();
        // Only for exceptions this module constructed. Their messages begin with a fixed sentence or code from
        // the source, so a digest of that part finds the throw site in any build without storing words. What
        // follows the first colon can name an app or an action and is left out; a platform exception's message
        // can quote data and gets no digest at all.
        if (message != null && !message.isEmpty() && stack.length > 0
            && stack[0].getClassName().startsWith("io.github.miaiplus.")) {
            String fixed = fixedPart(message);
            fields.addAll(java.util.List.of("messageRef", reference(fixed)));
        }
        if (causes.length() > 0) fields.addAll(java.util.List.of("causes", causes.toString()));
        String causeSite = deepest == top ? "" : site(deepest, 1);
        if (!causeSite.isEmpty()) fields.addAll(java.util.List.of("causeSite", causeSite));
        if (!binding.isEmpty()) fields.addAll(java.util.List.of("binding", binding));
        return fields.toArray();
    }
    /** The part of a module-authored message before its first colon; scripts/diagnose_log.py hashes the same part. */
    static String fixedPart(String message) {
        int wide = message.indexOf('：'), narrow = message.indexOf(':');
        int end = wide < 0 ? narrow : narrow < 0 ? wide : Math.min(wide, narrow);
        return end > 0 ? message.substring(0, end) : message;
    }
    private static String site(Throwable failure, int frames) {
        StringBuilder site = new StringBuilder();
        try {
            for (StackTraceElement frame : failure.getStackTrace()) {
                String type = frame.getClassName();
                if (!type.startsWith("io.github.miaiplus.")) continue;
                if (site.length() > 0) site.append('<');
                site.append(type.substring("io.github.miaiplus.".length())).append('.').append(frame.getMethodName());
                if (BuildConfig.DEBUG) site.append(':').append(frame.getLineNumber());
                if (--frames == 0) break;
            }
        } catch (RuntimeException unavailable) { /* A location is optional. */ }
        return site.toString();
    }
    /** The host class, method or field a version-pinned lookup did not find. Identifiers only. */
    private static String binding(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isEmpty()) return "";
        // Reflection messages can quote arbitrary arguments too. Retain only bounded symbol signatures or
        // the fixed lookup labels authored by Reflector; URLs, paths and free text are never bindings.
        if (message.length() > 160) return "";
        if ((failure instanceof ClassNotFoundException || failure instanceof NoSuchFieldException
            || failure instanceof NoClassDefFoundError) && message.matches("[A-Za-z_$][A-Za-z0-9_.$]*"))
            return message;
        if (failure instanceof NoSuchMethodException
            && message.matches("[A-Za-z_$][A-Za-z0-9_.$]*\\([A-Za-z0-9_.$, \\[\\]]*\\)")) return message;
        if (message.matches("(Missing host (class|method|field)|Missing compatible host (method|constructor)|Ambiguous host (overload|constructor)): [A-Za-z_$][A-Za-z0-9_.$]*"))
            return message;
        return "";
    }
    static void window(String stage, android.view.accessibility.AccessibilityWindowInfo window,
        android.view.accessibility.AccessibilityNodeInfo root, String state) {
        if (!enabled()) return;
        try {
            event("window", "stage", stage, "windowId", window.getId(), "type", window.getType(),
                "focused", window.isFocused(), "active", window.isActive(), "state", state,
                "package", root == null ? "" : String.valueOf(root.getPackageName()));
        } catch (RuntimeException ignored) { event("window_inspection_failed", "stage", stage); }
    }
    /** Inspect only known multimodal content fields, never print prompts, URLs or request bodies. */
    static void request(JSONObject body, String api) {
        if (!enabled()) return;
        try { request(body, api, body.toString()); }
        catch (Exception ignored) { event("request_inspection_failed"); }
    }
    static void request(JSONObject body, String api, String serialized) {
        if (!enabled()) return;
        try { request(body, api, serialized.getBytes(StandardCharsets.UTF_8).length); }
        catch (Exception ignored) { event("request_inspection_failed"); }
    }
    /** requestBytes comes from the final serialized transport body, not a token estimate. */
    static void request(JSONObject body, String api, long requestBytes) {
        if (!enabled()) return;
        try {
            String requestRef = android.os.Process.myPid() + "-" + requests.incrementAndGet();
            JSONArray messages = body.optJSONArray("messages");
            if (messages == null) messages = body.optJSONArray("input");
            RequestCounts counts = new RequestCounts();
            counts.text("system", body.opt("instructions"));
            if (messages == null) counts.text("user", body.opt("input"));
            int count = 0, associatedImages = 0;
            long imageBase64Chars = 0;
            boolean phoneUse = false;
            if (messages != null) for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null) continue;
                String role = message.optString("role");
                counts.text(role, message.opt("content"));
                if ("function_call_output".equals(message.optString("type"))) {
                    counts.functionResults++;
                    counts.text("tool", message.opt("output"));
                    if (message.optString("output").contains("CONTINUE：控屏尚未完成。")) phoneUse = true;
                } else if ("tool".equals(role)) counts.functionResults++;
                if ("function_call".equals(message.optString("type"))) {
                    counts.functionCalls++;
                    counts.argumentChars += message.optString("arguments").length();
                }
                JSONArray calls = message.optJSONArray("tool_calls");
                if (calls != null) for (int j = 0; j < calls.length(); j++) {
                    JSONObject call = calls.optJSONObject(j);
                    JSONObject function = call == null ? null : call.optJSONObject("function");
                    if (function != null) {
                        counts.functionCalls++;
                        counts.argumentChars += function.optString("arguments").length();
                    }
                }
                if ("reasoning".equals(message.optString("type"))) counts.opaqueChars += message.optString("encrypted_content").length();
                JSONArray opaque = message.optJSONArray("_responses_output");
                if (opaque != null) for (int j = 0; j < opaque.length(); j++) {
                    JSONObject item = opaque.optJSONObject(j);
                    if (item != null && "reasoning".equals(item.optString("type")))
                        counts.opaqueChars += item.optString("encrypted_content").length();
                }
                JSONArray content = message.optJSONArray("content");
                if (message.opt("content") instanceof String text
                    && text.contains("CONTINUE：控屏尚未完成。")) phoneUse = true;
                if (content == null) continue;
                for (int j = 0; j < content.length(); j++) {
                    JSONObject part = content.optJSONObject(j);
                    if (part == null) continue;
                    String type = part.optString("type");
                    if (type.equals("text") || type.equals("input_text") || type.equals("output_text")) {
                        counts.text(role, part.opt("text"));
                        if (part.optString("text").contains("CONTINUE：控屏尚未完成。")) phoneUse = true;
                    }
                    if (!type.equals("image_url") && !type.equals("input_image")) continue;
                    count++;
                    Object image = part.opt("image_url");
                    String url = image instanceof JSONObject object ? object.optString("url") : String.valueOf(image);
                    int comma = url.indexOf(',');
                    boolean inline = url.startsWith("data:") && comma >= 0;
                    if (inline) imageBase64Chars += url.length() - comma - 1;
                    String digest = inline ? reference(url.substring(comma + 1)) : "non_inline";
                    Trace trace;
                    boolean known;
                    synchronized (images) { known = images.containsKey(digest); trace = images.get(digest); }
                    if (known) {
                        associatedImages++;
                        phoneUse = true;
                        try (Scope ignored = use(trace)) {
                            event("request_image", "requestRef", requestRef, "index", count, "messageIndex", i, "api", api, "imageDigest", digest);
                        }
                    }
                }
            }
            if (phoneUse || current.get() != null) {
                JSONArray tools = body.optJSONArray("tools");
                event("model_request", "requestRef", requestRef, "api", api,
                    "messages", messages == null ? 0 : messages.length(), "images", count,
                    "associatedImages", associatedImages, "imageBase64Chars", imageBase64Chars,
                    "requestBytes", requestBytes, "tools", tools == null ? 0 : tools.length(),
                    "toolsBytes", tools == null ? 0 : tools.toString().getBytes(StandardCharsets.UTF_8).length,
                    "textChars", counts.textChars, "systemTextChars", counts.systemTextChars,
                    "userTextChars", counts.userTextChars, "assistantTextChars", counts.assistantTextChars,
                    "toolTextChars", counts.toolTextChars, "otherTextChars", counts.otherTextChars,
                    "functionCalls", counts.functionCalls, "functionResults", counts.functionResults,
                    "argumentChars", counts.argumentChars, "opaqueChars", counts.opaqueChars);
            }
        } catch (Exception ignored) { event("request_inspection_failed"); }
    }
    /** Text lengths are UTF-16 code units by protocol role, not tokens or inferred task facts. */
    private static final class RequestCounts {
        long textChars, systemTextChars, userTextChars, assistantTextChars, toolTextChars, otherTextChars;
        long argumentChars, opaqueChars;
        int functionCalls, functionResults;
        void text(String role, Object value) {
            if (!(value instanceof String text)) return;
            int length = text.length();
            textChars += length;
            switch (role) {
                case "system", "developer" -> systemTextChars += length;
                case "user" -> userTextChars += length;
                case "assistant" -> assistantTextChars += length;
                case "tool" -> toolTextChars += length;
                default -> otherTextChars += length;
            }
        }
    }
}
