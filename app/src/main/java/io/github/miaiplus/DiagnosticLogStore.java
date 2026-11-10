package io.github.miaiplus;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;

/** Module-private, bounded metadata journal. Its worker never runs phone-use operations. */
final class DiagnosticLogStore {
    static final int MAX_LINE_BYTES = 8192;
    static final int FILE_COUNT = 4;
    static final int FILE_BYTES = 512 * 1024;
    private static volatile DiagnosticLogStore instance;
    private final File directory;
    private final int fileBytes;
    private final ThreadPoolExecutor worker;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    static synchronized DiagnosticLogStore get(Context context) {
        if (instance == null) instance = new DiagnosticLogStore(new File(context.getFilesDir(), "phone-use-logs"), FILE_BYTES);
        return instance;
    }

    DiagnosticLogStore(File directory, int fileBytes) {
        this.directory = directory;
        this.fileBytes = fileBytes;
        worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256), runnable -> {
            Thread thread = new Thread(runnable, "MiAIPlus-log-store");
            thread.setDaemon(true);
            return thread;
        });
        worker.allowCoreThreadTimeOut(true);
    }

    void append(String line) {
        if (line == null || line.length() > MAX_LINE_BYTES || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            dropped.incrementAndGet(); return;
        }
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Math.min(MAX_LINE_BYTES, fileBytes)) { dropped.incrementAndGet(); return; }
        try {
            worker.execute(() -> {
                try {
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Log directory unavailable");
                    if (file(0).length() + bytes.length > fileBytes) {
                        Files.deleteIfExists(file(FILE_COUNT - 1).toPath());
                        for (int i = FILE_COUNT - 2; i >= 0; i--) {
                            if (file(i).exists()) Files.move(file(i).toPath(), file(i + 1).toPath());
                        }
                    }
                    try (FileOutputStream out = new FileOutputStream(file(0), true)) { out.write(bytes); }
                } catch (IOException | RuntimeException failure) { errors.incrementAndGet(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) { dropped.incrementAndGet(); }
    }

    record Snapshot(List<String> lines, long bytes, long dropped, long errors) { }

    Snapshot snapshot() throws IOException {
        return ordered(() -> {
            List<String> lines = new ArrayList<>();
            long bytes = 0;
            for (int i = FILE_COUNT - 1; i >= 0; i--) {
                File file = file(i);
                if (file.exists()) { lines.addAll(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)); bytes += file.length(); }
            }
            return new Snapshot(List.copyOf(lines), bytes, dropped.get(), errors.get());
        });
    }

    void clear() throws IOException {
        ordered(() -> {
            for (int i = 0; i < FILE_COUNT; i++) Files.deleteIfExists(file(i).toPath());
            dropped.set(0); errors.set(0);
            return null;
        });
    }

    void exportZip(OutputStream output, boolean enabled) throws IOException { exportZip(output, enabled, null); }

    /**
     * Snapshot first, then compress outside the writer so export never holds up new records.
     *
     * @param environment sections from {@link DiagnosticEnvironment}; null keeps only what this store knows.
     */
    void exportZip(OutputStream output, boolean enabled, JSONObject environment) throws IOException {
        Snapshot snapshot = snapshot();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("phone-use.jsonl"));
            for (String line : snapshot.lines()) zip.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("environment.json"));
            try {
                JSONObject info = new JSONObject().put("schema", DiagnosticEnvironment.SCHEMA)
                    .put("appVersion", BuildConfig.VERSION_NAME)
                    .put("versionCode", BuildConfig.VERSION_CODE).put("androidApi", Build.VERSION.SDK_INT)
                    .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                    .put("exportedAt", System.currentTimeMillis()).put("detailedLogEnabled", enabled)
                    .put("records", snapshot.lines().size()).put("bytes", snapshot.bytes())
                    .put("moduleDropped", snapshot.dropped()).put("moduleWriteErrors", snapshot.errors())
                    .put("log", summary(snapshot.lines()));
                if (environment != null) for (var keys = environment.keys(); keys.hasNext();) {
                    String key = keys.next();
                    if (!info.has(key)) info.put(key, environment.get(key));
                }
                zip.write(info.toString(2).getBytes(StandardCharsets.UTF_8));
            } catch (org.json.JSONException failure) { throw new IOException("Cannot encode log environment", failure); }
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write(README.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    /**
     * What the journal covers, so a reader can tell "nothing was recorded" from "nothing went wrong": the time
     * range, how many records each process contributed, and how often each event occurs.
     */
    static JSONObject summary(List<String> lines) throws org.json.JSONException {
        long first = Long.MAX_VALUE, last = 0, hostDropped = 0, hostErrors = 0;
        int module = 0, host = 0, unknown = 0, unreadable = 0;
        java.util.TreeMap<String, Integer> events = new java.util.TreeMap<>();
        java.util.TreeSet<Long> hostPids = new java.util.TreeSet<>(), modulePids = new java.util.TreeSet<>();
        java.util.TreeSet<Long> hostVersions = new java.util.TreeSet<>();
        for (String line : lines) {
            JSONObject record;
            try { record = new JSONObject(line); } catch (org.json.JSONException invalid) { unreadable++; continue; }
            long time = record.optLong("time");
            if (time > 0) { first = Math.min(first, time); last = Math.max(last, time); }
            boolean fromHost = "host".equals(record.optString("source"));
            if (fromHost) { host++; hostPids.add(record.optLong("pid")); }
            else if ("module".equals(record.optString("source"))) { module++; modulePids.add(record.optLong("pid")); }
            else unknown++;
            if (fromHost && record.has("ver")) hostVersions.add(record.optLong("ver"));
            hostDropped = Math.max(hostDropped, record.optLong("hostDropped"));
            hostErrors = Math.max(hostErrors, record.optLong("hostDeliveryErrors"));
            events.merge(record.optString("event", "?"), 1, Integer::sum);
        }
        JSONObject counts = new JSONObject();
        for (var entry : events.entrySet()) counts.put(entry.getKey(), entry.getValue());
        JSONObject summary = new JSONObject().put("moduleRecords", module).put("hostRecords", host)
            .put("unknownSourceRecords", unknown).put("unreadable", unreadable).put("modulePids", new org.json.JSONArray(modulePids))
            .put("hostPids", new org.json.JSONArray(hostPids))
            // More than one value, or one that differs from versionCode, is a XiaoAi running older module code.
            .put("hostModuleVersions", new org.json.JSONArray(hostVersions))
            .put("hostDroppedMax", hostDropped).put("hostDeliveryErrorsMax", hostErrors).put("events", counts);
        if (last > 0) summary.put("firstTime", first).put("lastTime", last);
        return summary;
    }

    private static final String README = """
        MiAIPlus diagnostic export. Metadata only: no screenshots, page text, typed input, goals, prompts,
        URLs, model names or credentials.

        FILES
          environment.json  State at export time. Read this first.
          phone-use.jsonl   One JSON record per line, in the order the module received them (oldest first).
                            Up to 4 x 512 KiB are kept; older records rotate out.

        READ environment.json FIRST
          host.profile        "unsupported" = this XiaoAi version is not one the module binds to; no hooks run.
          hook.report         Last install report from XiaoAi. An absent or old report can mean hooks were not
                              loaded, reporting failed, or XiaoAi has not restarted; check framework and bridge.
                              hook.status has the recorded stage/binding; it is not proof of a live hook.
          hook.versionSkew    true = the last recorded XiaoAi module version differs from the installed one.
          config.enabled      false = the master switch is off and XiaoAi behaves as stock.
          plugins.catalog*    Tools available at export time; XiaoAi may still hold an older cached catalog.
          bridge.hostCalls    0 = XiaoAi has not reached the module process since it started.
          accessibility       connected=false = no screen can be read or operated.
          log.events          Event counts. detailedLogEnabled is the switch at export time only; an empty
                              journal cannot show whether logging was enabled during the reproduction.

        RECORD FIELDS
          event, time (wall clock ms), elapsedMs (monotonic), pid, source (module | host = inside XiaoAi),
          writer (random logger generation; changes on reload), seq (emission order within writer),
          ver (module versionCode that wrote the record),
          session / step / observation (correlate one task; hashed, not usable as tokens).
          Host records arrive asynchronously: compare time, pid and seq, not line order alone.
          seq is not a global clock or proof of event occurrence order.

        WHERE TO LOOK
          XiaoAi answered with its own refusal or error sentence
              host_error (code = XiaoAi UI error code, upstream = service code, httpStatus),
              host_agent_error, host_agent_support, and the chat_route / voice_route just before it.
              code -1009 maps to XiaoAi's "当前功能暂未对你开放。"; this identifies its error path, but does
              not establish why the feature is unavailable for this request, account or device.
          The assistant chats instead of operating the phone
              chat_route / voice_route (rule = why the request was or was not sent to the agent),
              model_call (which tools were offered), plugin_catalog, prompt_policy.
          A task stops, hangs or is refused
              start_rejected (reason), run_end (phase, cancelSource), run_cancel (cancelSource),
              continuation_rejected, session_close, host_control_call, prompt_wait / prompt_result.
          Any failure
              failure: stage, exception, causes, site (module Class.method, innermost first; debug builds add
              the line), binding (the XiaoAi class, method or field that a version-pinned lookup did not find),
              messageRef (digest of the module's own error sentence; never the sentence itself).
              python3 scripts/diagnose_log.py <this zip> resolves messageRef to the source line.

        LIMITS
          Disabling logging stops new records; kept records remain until cleared.
          moduleDropped / moduleWriteErrors count since process start or the last clear. hostDropped /
          hostDeliveryErrors are cumulative per XiaoAi process and appear on later delivered records.
          The export holds what the module had received; records still in transit from XiaoAi are missing.
          An empty journal or zero counters do not prove that the whole chain worked.
          Full event reference: docs/phone-use-logging.md in the MiAIPlus repository.
        """;

    private File file(int index) { return new File(directory, "events." + index + ".jsonl"); }

    private <T> T ordered(Callable<T> action) throws IOException {
        try { return worker.submit(action).get(5, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("Log operation interrupted", failure); }
        catch (ExecutionException | java.util.concurrent.TimeoutException | java.util.concurrent.RejectedExecutionException failure) {
            throw new IOException("Log storage busy or unavailable", failure);
        }
    }
}
