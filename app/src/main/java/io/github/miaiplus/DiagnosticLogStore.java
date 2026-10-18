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

    /** Snapshot first, then compress outside the writer so export never holds up new records. */
    void exportZip(OutputStream output, boolean enabled) throws IOException {
        Snapshot snapshot = snapshot();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("phone-use.jsonl"));
            for (String line : snapshot.lines()) zip.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("environment.json"));
            try {
                JSONObject info = new JSONObject().put("appVersion", BuildConfig.VERSION_NAME)
                    .put("versionCode", BuildConfig.VERSION_CODE).put("androidApi", Build.VERSION.SDK_INT)
                    .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                    .put("exportedAt", System.currentTimeMillis()).put("detailedLogEnabled", enabled)
                    .put("records", snapshot.lines().size()).put("bytes", snapshot.bytes())
                    .put("moduleDropped", snapshot.dropped()).put("moduleWriteErrors", snapshot.errors());
                zip.write(info.toString(2).getBytes(StandardCharsets.UTF_8));
            } catch (org.json.JSONException failure) { throw new IOException("Cannot encode log environment", failure); }
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write(("MiAIPlus Phone Use / virtual display diagnostic metadata, oldest first.\n"
                + "Up to 4 x 512 KiB retained; old records rotate. No screenshots, page text, inputs or credentials.\n"
                + "Disabling logging stops new records; retained records remain until cleared.\n"
                + "moduleDropped/moduleWriteErrors are since process start or last clear. Host delivery drops/errors\n"
                + "are cumulative per host process on subsequent delivered records; absence does not prove completeness.\n"
                + "A snapshot includes records received before export, not pending host IPC.\n")
                .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private File file(int index) { return new File(directory, "events." + index + ".jsonl"); }

    private <T> T ordered(Callable<T> action) throws IOException {
        try { return worker.submit(action).get(5, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("Log operation interrupted", failure); }
        catch (ExecutionException | java.util.concurrent.TimeoutException | java.util.concurrent.RejectedExecutionException failure) {
            throw new IOException("Log storage busy or unavailable", failure);
        }
    }
}
