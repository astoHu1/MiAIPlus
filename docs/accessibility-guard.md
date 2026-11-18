# root 控屏前无障碍检查

默认关闭。在设置页打开“root 控屏前检查无障碍”，并在 root 管理器中允许 MiAIPlus。开关立即生效；授权失败时保留原值并提示。应用 UID 与 ADB shell 的 root 授权独立。

## 任务前检查

新任务在界面就绪、首次截图和操作前检查一次。`ScreenService.isHealthy()` 通过服务实例及 `getServiceInfo()` 检查 Binder 连接，可识别系统设置仍开启、实际连接已丢失的情况。

- 连接正常：直接执行，不调用 su。
- 连接失效、开关开启：运行限时 root helper，连接恢复后执行。
- 连接失效、开关关闭：提示开启服务。

后续 CONTINUE 和规划等待不重复检查。运行期间服务断开会停止任务。`onInterrupt` 仅中断反馈；旧服务销毁不影响新实例。此功能没有常驻 daemon 或定时修复，打开开关也不立即重绑。

## 修复过程

`AccessibilityGuard` 从 assets 读取 `accessibility-preflight.sh`，经 su 临时执行。参数仅含本机生成的 Android 用户 ID 和任务 UUID。脚本要求 root、当前前台用户、已解锁用户存储，并通过 root 专用 IPC 核对开关、任务票据和准备阶段。

脚本读取无障碍列表，移除模块的短／完整组件名并等待解绑，再次读取后加入完整组件名、将全局开关设为 1，保留其他服务。每次设置变更前复核任务票据；任务取消、过期或开始执行后停止重绑。最多检查十轮连接，Java 侧 root 等待上限为 45 秒，支持取消。

helper 只修复连接。小米启动确认由控屏执行器处理，见 [应用启动](app-launch.md)。锁屏、安全窗口与任务截止限制仍适用。

## 旧版迁移

保留原开关偏好。旧版已启用用户打开模块或执行任务前修复时，会清理 `/data/adb/service.d/miaiplus-accessibility.sh`、旧启用标记及命令行匹配的旧 daemon。旧健康 IPC 的 `guard_enabled` 固定返回 false，旧 `accessibility_recover` 不再取消任务。

关闭开关保留当前服务设置；旧版清理完成后无需 root。若迁移时 root 被拒，需重新授权才能清理遗留进程并启用功能。

## 验证

```sh
python3 scripts/test_accessibility_guard.py
# 已安装 Debug / AndroidTest、启用无障碍且获 adb root 授权的设备
python3 scripts/accessibility_guard_device_test.py
```

主机测试以 5 个契约测试覆盖 10 个输入场景：列表规范化、健康连接、准备票据、解绑期间取消及等待上限。设备 runner 合成“假开启”准备状态，通过 ADB-root 执行 helper，检查重绑、其他服务保留、任务未取消及执行阶段拒绝重绑；不截图或注入手势。它验证修复流程，未复现各 ROM 掉线原因或验证应用 UID 的 su 授权。结果见 [验证状态](validation.md)。
