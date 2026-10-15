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
                        if (target.getState() == HookedTarget.State.UP_TO_DATE) { status = "小爱已加载当前版本，无需重载"; return; }
                        status = "正在热重载；控屏、模型请求或诊断进行中时会拒绝重载";
                        service.hotReloadModule(target, null, (ignored, result) -> {
                            status = switch (result.status()) {
                                case SUCCEEDED -> "热重载成功，无需重启小爱";
                                case FAILED -> result.message() == null ? "模块正在使用，已拒绝重载；任务结束后重试" : "热重载失败，请重启小爱并检查框架日志";
                                case UNSUPPORTED -> "框架无法热重载此进程，请重启小爱";
                                case IN_PROGRESS -> "框架已有热重载请求，请稍后再检查";
                                case PROCESS_DIED -> "小爱进程已退出，下次启动将加载新版模块";
                            };
                            requesting.set(false);
                        });
                        submitted = true; return;
                    }
                }
                status = "未找到 API 102 已注入的小爱主进程；首次迁移请在 LSPosed 启用模块后重启小爱";
            } catch (RuntimeException failure) { status = "无法请求热重载（" + failure.getClass().getSimpleName() + "），请检查框架状态"; }
            finally { if (!submitted) requesting.set(false); }
        }, "miaiplus-reload-request").start();
    }
}
