package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import io.github.libxposed.service.XposedService;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** API 102 service smoke test; does not read or modify model configuration. */
public final class ReloadProbeRunner extends Instrumentation {
    private boolean request;
    @Override public void onCreate(Bundle args) { super.onCreate(args); request = args.getBoolean("request", false) || "true".equals(args.getString("request")); start(); }
    @Override public void onStart() { run(this, request); }
    @SuppressWarnings("unchecked") static void run(Instrumentation runner, boolean request) {
        Bundle report = new Bundle(); int code = Activity.RESULT_CANCELED;
        try {
            var field = HotReload.class.getDeclaredField("services"); field.setAccessible(true);
            Set<XposedService> services = (Set<XposedService>)field.get(null);
            long deadline = SystemClock.elapsedRealtime() + 10000;
            while (services.isEmpty() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            StringBuilder text = new StringBuilder(); boolean found = false;
            for (XposedService service : services) {
                text.append("API ").append(service.getApiVersion()).append('\n');
                for (var target : service.getRunningTargets()) {
                    if (!target.getProcessName().equals("com.miui.voiceassist")) continue;
                    found = true;
                    text.append("target pid=").append(target.getPid()).append(" state=").append(target.getState()).append('\n');
                    if (request) {
                        var result = new CompletableFuture<String>();
                        service.hotReloadModule(target, null, (ignored, reloaded) -> result.complete(reloaded.status() + " " + reloaded.message()));
                        String status = result.get(20, TimeUnit.SECONDS);
                        text.append("reload=").append(status).append('\n');
                        if (!status.startsWith("SUCCEEDED ")) throw new AssertionError(text.toString());
                    }
                }
            }
            if (!found) throw new AssertionError("no API 102 XiaoAi target: " + text);
            report.putString("stream", text.toString()); code = Activity.RESULT_OK;
        } catch (Throwable failure) { report.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(failure)); }
        runner.finish(code, report);
    }
}
