package io.github.miaiplus;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Module-private, stale-while-revalidate models.dev metadata. No model credentials enter this transport. */
final class ModelsDevCache {
    static final String TAG = "MiAIPlusModelsDev";
    static final String SOURCE = "https://models.dev/api.json";
    static final int MAX_BYTES = 16 * 1024 * 1024;
    static final long REFRESH_MS = TimeUnit.HOURS.toMillis(24);
    static final long RETRY_MS = TimeUnit.HOURS.toMillis(1);
    private static final int MAGIC = 0x4d444331;
    private static final ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
        new java.util.concurrent.LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "MiAIPlus-models-dev"); thread.setDaemon(true); return thread;
        });
    private static volatile Engine shared;

    private ModelsDevCache() { }

    static void start(Context context) {
        Engine engine = shared;
        if (engine == null) {
            synchronized (ModelsDevCache.class) {
                engine = shared;
                if (engine == null) {
                    Context application = context.getApplicationContext();
                    File directory = new File((application == null ? context : application).getFilesDir(), "models-dev");
                    engine = new Engine(directory, ModelsDevCache::download, System::currentTimeMillis,
                        (event, models, providers, age, detail) -> {
                            Log.i(TAG, event + " models=" + models + " providers=" + providers
                                + " ageMs=" + age + " detail=" + detail);
                            PhoneUseLog.event("models_dev_" + event, "models", models,
                                "providers", providers, "ageMs", age, "detail", detail);
                        });
                    shared = engine;
                }
            }
        }
        engine.request();
    }

    static void refreshIfDue(Context context) {
        Engine engine = shared;
        if (engine != null) engine.request();
    }

    static Status status() {
        Engine engine = shared;
        return engine == null ? new Status(false, false, 0, 0, 0) : engine.status();
    }

    record Status(boolean loaded, boolean refreshing, long checkedAt, int modelCount, int providerCount) { }
    interface Transport { Response fetch(String etag, String lastModified) throws Exception; }
    interface EventSink { void log(String event, int models, int providers, long age, String detail); }

    static final class Response implements AutoCloseable {
        final int code;
        final String etag, lastModified;
        final long contentLength;
        final InputStream body;
        final Runnable cleanup;
        Response(int code, String etag, String lastModified, long contentLength, InputStream body) {
            this(code, etag, lastModified, contentLength, body, () -> { });
        }
        Response(int code, String etag, String lastModified, long contentLength, InputStream body, Runnable cleanup) {
            this.code = code; this.etag = etag; this.lastModified = lastModified;
            this.contentLength = contentLength; this.body = body; this.cleanup = cleanup;
        }
        @Override public void close() throws IOException {
            try { if (body != null) body.close(); }
            finally { cleanup.run(); }
        }
    }

    private record Snapshot(byte[] body, String etag, String lastModified, long checkedAt, ModelsDevCatalog catalog) { }

    /** All disk/network work runs on the worker. Tests inject a local transport and clock. */
    static final class Engine {
        final AtomicFile file;
        private final Transport transport;
        private final LongSupplier clock;
        private final EventSink events;
        private final AtomicBoolean queued = new AtomicBoolean();
        private volatile Snapshot snapshot;
        private volatile boolean loaded;
        private volatile long retryAt;

        Engine(File directory, Transport transport, LongSupplier clock, EventSink events) {
            file = new AtomicFile(new File(directory, "catalog.bin"));
            this.transport = transport; this.clock = clock; this.events = events;
        }

        Status status() {
            Snapshot current = snapshot;
            return new Status(loaded, queued.get(), current == null ? 0 : current.checkedAt(),
                current == null ? 0 : current.catalog().modelCount(),
                current == null ? 0 : current.catalog().providerCount());
        }

        private boolean due(long now) {
            if (retryAt > now && retryAt - now <= RETRY_MS) return false;
            Snapshot current = snapshot;
            // A clock moving backwards must not suppress refresh indefinitely.
            return current == null || now < current.checkedAt() || now - current.checkedAt() >= REFRESH_MS;
        }

        void request() {
            if (loaded && !due(clock.getAsLong())) return;
            if (!queued.compareAndSet(false, true)) return;
            worker.execute(() -> {
                try { refresh(); }
                finally { queued.set(false); }
            });
        }

        synchronized void refresh() {
            if (!loaded) {
                try {
                    snapshot = read();
                    ModelsDevCatalog.publish(snapshot.catalog());
                    emit("cache_loaded", "disk");
                } catch (java.io.FileNotFoundException absent) {
                    emit("cache_missing", "builtin");
                } catch (Exception invalid) {
                    emit("cache_invalid", invalid.getClass().getSimpleName());
                } finally { loaded = true; }
            }
            long now = clock.getAsLong();
            if (!due(now)) return;
            Snapshot previous = snapshot;
            emit("refresh_started", previous == null ? "unconditional" : "conditional");
            try (Response response = transport.fetch(previous == null ? "" : previous.etag(),
                previous == null ? "" : previous.lastModified())) {
                Snapshot next;
                if (response.code == 304) {
                    if (previous == null) throw new IOException("Unexpected not-modified response");
                    next = new Snapshot(previous.body(), header(response.etag, previous.etag(), 1024),
                        header(response.lastModified, previous.lastModified(), 256), clock.getAsLong(), previous.catalog());
                } else if (response.code == 200) {
                    if (response.contentLength > MAX_BYTES) throw new IOException("Catalog too large");
                    byte[] body = bounded(response.body, MAX_BYTES);
                    ModelsDevCatalog catalog = ModelsDevCatalog.parse(decode(body));
                    next = new Snapshot(body, header(response.etag, "", 1024),
                        header(response.lastModified, "", 256), clock.getAsLong(), catalog);
                } else {
                    emit("http_failure", Integer.toString(response.code));
                    throw new IOException("Catalog HTTP failure");
                }
                write(next);
                snapshot = next;
                ModelsDevCatalog.publish(next.catalog());
                retryAt = 0;
                emit(response.code == 304 ? "not_modified" : "refreshed", Integer.toString(response.code));
            } catch (Exception failure) {
                retryAt = clock.getAsLong() + RETRY_MS;
                emit("refresh_failed", failure.getClass().getSimpleName());
            }
        }

        private Snapshot read() throws Exception {
            try (DataInputStream input = new DataInputStream(file.openRead())) {
                if (file.getBaseFile().length() > MAX_BYTES + 4096L || input.readInt() != MAGIC)
                    throw new IOException("Invalid catalog snapshot");
                long checked = input.readLong();
                if (checked <= 0) throw new IOException("Invalid catalog timestamp");
                String etag = input.readUTF(), modified = input.readUTF();
                if (!etag.equals(header(etag, "", 1024)) || !modified.equals(header(modified, "", 256)))
                    throw new IOException("Invalid catalog validators");
                int length = input.readInt();
                if (length <= 0 || length > MAX_BYTES) throw new IOException("Invalid catalog length");
                byte[] body = new byte[length]; input.readFully(body);
                if (input.read() != -1) throw new IOException("Invalid catalog trailing bytes");
                ModelsDevCatalog catalog = ModelsDevCatalog.parse(decode(body));
                return new Snapshot(body, etag, modified, checked, catalog);
            }
        }

        private void write(Snapshot next) throws IOException {
            File directory = file.getBaseFile().getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Catalog directory unavailable");
            FileOutputStream stream = null;
            try {
                stream = file.startWrite();
                DataOutputStream output = new DataOutputStream(stream);
                output.writeInt(MAGIC); output.writeLong(next.checkedAt());
                output.writeUTF(next.etag()); output.writeUTF(next.lastModified());
                output.writeInt(next.body().length); output.write(next.body()); output.flush();
                file.finishWrite(stream);
            } catch (IOException failure) {
                if (stream != null) file.failWrite(stream);
                throw failure;
            }
        }

        private void emit(String event, String detail) {
            Snapshot current = snapshot;
            try {
                events.log(event, current == null ? 0 : current.catalog().modelCount(),
                    current == null ? 0 : current.catalog().providerCount(),
                    current == null ? -1 : Math.max(0, clock.getAsLong() - current.checkedAt()), detail);
            } catch (RuntimeException ignored) { /* Diagnostics do not control cache availability. */ }
        }
    }

    private static String decode(byte[] bytes) throws java.nio.charset.CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String header(String value, String fallback, int limit) {
        if (value == null || value.isEmpty()) return fallback;
        if (value.length() > limit) return fallback;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c > 126) return fallback;
        }
        return value;
    }

    static byte[] bounded(InputStream input, int limit) throws IOException {
        if (input == null) throw new IOException("Missing catalog body");
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() > limit - count) throw new IOException("Catalog too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static final ScheduledThreadPoolExecutor deadlines = deadlineExecutor();
    private static ScheduledThreadPoolExecutor deadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "MiAIPlus-models-deadline"); thread.setDaemon(true); return thread;
        });
        executor.setKeepAliveTime(10, TimeUnit.SECONDS); executor.allowCoreThreadTimeOut(true);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static Response download(String etag, String lastModified) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(SOURCE).openConnection();
        var deadline = deadlines.schedule(connection::disconnect, 60, TimeUnit.SECONDS);
        boolean returned = false;
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000); connection.setReadTimeout(15_000);
            connection.setRequestProperty("User-Agent", ModelHeaders.USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            if (!etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);
            if (!lastModified.isEmpty()) connection.setRequestProperty("If-Modified-Since", lastModified);
            int code = connection.getResponseCode();
            InputStream body = code == 200 ? connection.getInputStream() : null;
            Response response = new Response(code, connection.getHeaderField("ETag"),
                connection.getHeaderField("Last-Modified"), connection.getContentLengthLong(), body,
                () -> { deadline.cancel(false); connection.disconnect(); });
            returned = true;
            return response;
        } finally {
            if (!returned) { deadline.cancel(false); connection.disconnect(); }
        }
    }
}
