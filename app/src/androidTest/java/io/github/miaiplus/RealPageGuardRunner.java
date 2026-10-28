package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit operator-driven real-page probe. No model, app launch, preference write, or automatic target choice. */
final class RealPageGuardRunner {
    private final Instrumentation test;
    private RealPageGuardRunner(Instrumentation test) { this.test = test; }
    static void run(Instrumentation test) { new RealPageGuardRunner(test).run(); }
    private File directory;
    private ScreenService.Frame baseline;
    private String session;
    private final Config config = new Config(Map.of("maxSteps", 30, "backgroundDisplay", false));

    private void run() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            directory = new File(test.getTargetContext().getFilesDir(), "real-page-guard");
            if (!directory.mkdirs() && !directory.isDirectory()) throw new IllegalStateException("probe directory unavailable");
            status("MIAIPLUS_CONNECT_SERVICE");
            long deadline = SystemClock.elapsedRealtime() + 25_000;
            while (!ScreenService.isHealthy() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            if (!ScreenService.isHealthy()) throw new IllegalStateException("accessibility unavailable");
            if (NativeSession.hasPendingOperation()) throw new IllegalStateException("another task is active");
            status("REAL_PAGE_READY");
            File command = new File(directory, "command.json");
            long idleDeadline = SystemClock.elapsedRealtime() + 900_000;
            while (SystemClock.elapsedRealtime() < idleDeadline) {
                if (!command.isFile()) { Thread.sleep(100); continue; }
                JSONObject request = new JSONObject(Files.readString(command.toPath()));
                Files.delete(command.toPath());
                JSONObject response = new JSONObject().put("id", request.getLong("id"));
                boolean stop = request.getString("op").equals("stop");
                try { operate(request, response); }
                catch (Exception failure) { response.put("error", failure.getClass().getSimpleName()); }
                File temporary = new File(directory, "response.tmp");
                Files.writeString(temporary.toPath(), response.toString());
                Files.move(temporary.toPath(), new File(directory, "response.json").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                idleDeadline = SystemClock.elapsedRealtime() + 900_000;
                if (stop) break;
            }
            result.putString("stream", "PASS: operator-driven real-page probe closed; no preferences changed\n");
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure.getClass().getSimpleName() + "\n");
        } finally {
            if (session != null) NativeSession.cancel();
            FrameStore.clear();
            if (directory != null) {
                // Only this probe's known private files; no user app data or other test artifacts.
                for (String name : new String[]{"command.json", "observation.json", "response.tmp"})
                    deleteFile(new File(directory, name));
            }
            test.finish(code, result);
        }
    }

    private void operate(JSONObject request, JSONObject response) throws Exception {
        String op = request.getString("op");
        if (op.equals("stop")) return;
        ScreenService service = ScreenService.instance;
        RunState run = new RunState("real-page-guard", android.os.Process.myUid());
        if (op.equals("observe")) {
            baseline = capture(service, run, request);
            save(baseline);
            response.put("package", baseline.foreground());
            observationMetrics(baseline, response);
        } else if (op.equals("sample")) {
            if (baseline == null) throw new IllegalStateException("observe first");
            JSONArray points = request.getJSONArray("points"), samples = new JSONArray();
            for (int index = 0; index < Math.max(1, Math.min(10, request.optInt("count", 4))); index++) {
                run.pause(Math.max(350, Math.min(3000, request.optInt("delay_ms", 1200))));
                ScreenService.Frame current = capture(service, run, request);
                JSONObject sample = new JSONObject().put("imageChanged", !baseline.image().equals(current.image()))
                    .put("treeChanged", !baseline.tree().equals(current.tree()));
                JSONArray checks = new JSONArray();
                for (int p = 0; p < points.length(); p++) {
                    JSONArray point = points.getJSONArray(p);
                    Action action = new Action(new JSONObject().put("action", "tap").put("element", point));
                    checks.put(new JSONObject().put("allowed", PageGuard.canExecute(action, baseline, current))
                        .put("localStable", VisualRegion.stable(baseline, current,
                            point.getDouble(0) * baseline.width() / 1000,
                            point.getDouble(1) * baseline.height() / 1000,
                            point.getDouble(0) * baseline.width() / 1000,
                            point.getDouble(1) * baseline.height() / 1000)));
                }
                samples.put(sample.put("points", checks));
                save(current);
            }
            response.put("samples", samples);
        } else if (op.equals("begin")) {
            capture(service, run, request); // Require the operator's expected foreground before creating a session.
            String observation = NativeSession.handle(config, "验证动态页面中已人工选定的无害导航目标", android.os.Process.myUid());
            var match = java.util.regex.Pattern.compile("会话=([a-f0-9-]{36})").matcher(observation);
            if (!match.find()) throw new IllegalStateException("session unavailable");
            session = match.group(1);
            String executionStatus = executionStatus(observation, "none");
            if (!executionStatus.equals("initial")) throw new IllegalStateException("unexpected initial execution status");
            FrameStore.clear();
            response.put("continuing", observation.startsWith("CONTINUE："))
                .put("executionStatus", executionStatus);
        } else if (op.equals("tap")) {
            if (session == null) throw new IllegalStateException("begin first");
            capture(service, run, request);
            run.pause(Math.max(0, Math.min(8000, request.optInt("delay_ms", 1500))));
            JSONObject action = new JSONObject().put("action", "tap").put("element", request.getJSONArray("point"));
            String observation = NativeSession.handle(config, "MIAIPLUS:" + new JSONObject().put("session", session)
                .put("coordinate_space", "normalized").put("action", action), android.os.Process.myUid());
            String executionStatus = executionStatus(observation, "tap");
            if (!executionStatus.equals("executed") && !executionStatus.equals("skipped"))
                throw new IllegalStateException("unexpected tap execution status");
            int sessionHeader = observation.indexOf("\n会话=");
            if (sessionHeader < 0 || executionStatus.equals("skipped")
                != observation.substring(0, sessionHeader).contains("本步未执行"))
                throw new IllegalStateException("tap skip status mismatch");
            response.put("skipped", executionStatus.equals("skipped"))
                .put("continuing", observation.startsWith("CONTINUE："))
                .put("executionStatus", executionStatus);
            FrameStore.clear();
            run.pause(400); // Avoid Android's minimum interval immediately after NativeSession's final capture.
            save(capture(service, run, request));
        } else if (op.equals("end")) {
            if (session != null) {
                NativeSession.handle(config, "MIAIPLUS:" + new JSONObject().put("session", session)
                    .put("action", new JSONObject().put("action", "finish").put("message", "页面校验结束")), android.os.Process.myUid());
                session = null;
            }
            FrameStore.clear();
        } else throw new IllegalArgumentException("unknown operation");
    }

    private ScreenService.Frame capture(ScreenService service, RunState run, JSONObject request) throws Exception {
        ScreenService.Frame frame = service.capture(run);
        if (!frame.foreground().equals(request.getString("package"))) throw new IllegalStateException("foreground changed");
        return frame;
    }

    private void save(ScreenService.Frame frame) throws Exception {
        Files.writeString(new File(directory, "observation.json").toPath(), new JSONObject()
            .put("image", frame.image()).put("tree", frame.tree()).put("package", frame.foreground())
            .put("width", frame.width()).put("height", frame.height()).toString(), StandardCharsets.UTF_8);
    }

    /** Compare model views of exactly the same evidence; never serialize page content into probe status. */
    private static void observationMetrics(ScreenService.Frame frame, JSONObject response) throws Exception {
        String tree = frame.tree();
        String legacyNative = tree.isEmpty() && frame.isBackground()
            ? "（本机未提供后台屏的无障碍树，请只依据截图判断）"
            : tree.substring(0, Math.min(tree.length(), 12000));
        String nativeModel = ModelObservation.nativeTree(frame);
        String externalModel = ModelObservation.externalTree(frame);
        textMetrics(response, "tree", tree);
        textMetrics(response, "legacyNativeTree", legacyNative);
        textMetrics(response, "nativeModelTree", nativeModel);
        textMetrics(response, "externalModelTree", externalModel);
        response.put("nativeModelTreeTruncated", nativeModel.equals(externalModel) ? 0 : 1);
    }

    private static void textMetrics(JSONObject response, String prefix, String text) throws Exception {
        response.put(prefix + "Chars", text.length())
            .put(prefix + "Utf8Bytes", text.getBytes(StandardCharsets.UTF_8).length);
    }

    /** Read only the protocol status immediately after CONTINUE, never a matching line in page content. */
    private static String executionStatus(String observation, String action) {
        if (!observation.startsWith("CONTINUE：")) throw new IllegalStateException("native continuation unavailable");
        int firstLineEnd = observation.indexOf('\n');
        int statusEnd = observation.indexOf('\n', firstLineEnd + 1);
        String prefix = "上步动作=" + action + "；执行状态=";
        if (firstLineEnd < 0 || statusEnd < 0 || !observation.startsWith(prefix, firstLineEnd + 1))
            throw new IllegalStateException("native execution status unavailable");
        String status = observation.substring(firstLineEnd + 1 + prefix.length(), statusEnd);
        if (!java.util.Set.of("initial", "executed", "skipped", "waiting_review", "handoff").contains(status))
            throw new IllegalStateException("unknown native execution status");
        return status;
    }

    private static void deleteFile(File file) {
        try { Files.deleteIfExists(file.toPath()); } catch (java.io.IOException ignored) { }
    }

    private void status(String text) {
        Bundle status = new Bundle(); status.putString("stream", text + "\n"); test.sendStatus(1, status);
    }
}
