package io.github.miaiplus;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;

/** Application-owned service callbacks never retain an Activity. Configuration uses the bridge. */
final class HotReload {
    private static final java.util.Set<XposedService> services = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicBoolean requesting = new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile String status = "框架服务尚未连接；需要支持 API 102 的 LSPosed";
    private HotReload() {}
    static void bind(XposedService service) {
        services.add(service);
        status = "框架服务已连接，可在安装模块更新后重载小爱中的模块";
    }
    static void unbind(XposedService service) { services.remove(service); status = "框架服务已断开，请重新打开设置页"; }
    static String status() { return status; }
    private static volatile String lastResult = "";
    private static volatile long lastResultAt;
    private static void record(String code) {
        lastResult = code; lastResultAt = System.currentTimeMillis();
        PhoneUseLog.event("hot_reload_result", "status", code);
    }
    private interface Read { Object get(); }
    private static void field(org.json.JSONObject target, String key, Read read) throws org.json.JSONException {
        // One framework call failing must not blank the others.
        try { target.put(key, read.get()); }
        catch (RuntimeException failure) { target.put(key + "Error", failure.getClass().getSimpleName()); }
    }
    /**
     * Whether a framework service is bound, whether XiaoAi is in scope, and which module version each XiaoAi
     * process has loaded. A stale target is a XiaoAi still running the code from before an update.
     */
    static org.json.JSONObject snapshot() throws org.json.JSONException {
        org.json.JSONObject result = new org.json.JSONObject().put("serviceBound", !services.isEmpty())
            .put("moduleVersionCode", BuildConfig.VERSION_CODE);
        if (!lastResult.isEmpty()) result.put("lastReload", lastResult).put("lastReloadAt", lastResultAt);
        org.json.JSONArray bound = new org.json.JSONArray();
        for (XposedService service : services) {
            org.json.JSONObject entry = new org.json.JSONObject();
            field(entry, "name", service::getFrameworkName);
            field(entry, "version", service::getFrameworkVersion);
            field(entry, "versionCode", service::getFrameworkVersionCode);
            field(entry, "api", service::getApiVersion);
            field(entry, "scopeHasHost", () -> service.getScope().contains("com.miui.voiceassist"));
            field(entry, "targets", () -> {
                org.json.JSONArray targets = new org.json.JSONArray();
                if (service.getApiVersion() < XposedService.API_102) return targets;
                for (HookedTarget target : service.getRunningTargets()) {
                    // Only XiaoAi: the other apps this module's scope may list are not this export's business.
                    if (!target.getProcessName().startsWith("com.miui.voiceassist")) continue;
                    try {
                        targets.put(new org.json.JSONObject().put("process", target.getProcessName())
                            .put("pid", target.getPid()).put("state", String.valueOf(target.getState()))
                            .put("loadedVersionCode", target.getLoadedVersionCode()));
                    } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
                }
                return targets;
            });
            bound.put(entry);
        }
        return result.put("services", bound);
    }
    static void request() {
        if (!requesting.compareAndSet(false, true)) return;
        status = "正在检查小爱中的模块版本…";
        new Thread(() -> {
            boolean submitted = false;
            try {
                for (XposedService service : services) {
                    if (service.getApiVersion() < XposedService.API_102) continue;
                    for (HookedTarget target : service.getRunningTargets()) {
                        if (!target.getProcessName().equals("com.miui.voiceassist")) continue;
                        if (target.getState() == HookedTarget.State.UP_TO_DATE) { status = "小爱已加载当前版本，无需重载"; record("UP_TO_DATE"); return; }
                        status = "正在热重载；控屏、模型请求或诊断进行中时会拒绝重载";
                        service.hotReloadModule(target, null, (ignored, result) -> {
                            status = switch (result.status()) {
                                case SUCCEEDED -> "热重载成功，无需重启小爱";
                                case FAILED -> result.message() == null ? "模块正在使用，已拒绝重载；任务结束后重试" : "热重载失败，请重启小爱并检查框架日志";
                                case UNSUPPORTED -> "框架无法热重载此进程，请重启小爱";
                                case IN_PROGRESS -> "框架已有热重载请求，请稍后再检查";
                                case PROCESS_DIED -> "小爱进程已退出，下次启动将加载新版模块";
                            };
                            // The framework's own message is not exported; only whether it gave one.
                            record(result.status() + (result.message() == null ? "" : "+message"));
                            requesting.set(false);
                        });
                        submitted = true; return;
                    }
                }
                status = "未找到 API 102 已注入的小爱主进程；首次迁移请在 LSPosed 启用模块后重启小爱";
                record("NO_TARGET");
            } catch (RuntimeException failure) {
                status = "无法请求热重载（" + failure.getClass().getSimpleName() + "），请检查框架状态";
                record("REQUEST_FAILED:" + failure.getClass().getSimpleName());
            }
            finally { if (!submitted) requesting.set(false); }
        }, "miaiplus-reload-request").start();
    }
}
