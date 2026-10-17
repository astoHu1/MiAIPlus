# root 控屏前无障碍检查

默认关闭。在设置页打开“root 控屏前检查无障碍”，在 root 管理器中允许 MiAIPlus。开关立即生效，不依赖保存 API 配置。授权失败保留原开关并提示；ADB shell 的 root 授权与应用 UID 的授权相互独立。

## 检查时机

只在新控屏任务开始、界面就绪之后、首次截图和操作之前检查一次。同一任务的后续 CONTINUE 调用和模型规划等待都不重新运行 root 检查。连接正常时直接执行，不调用 su；失效且开关已开启时，才执行有时限的 root helper，等待服务重新连接后开始控屏。开关关闭且连接失效时直接提示用户开启服务。

不安装常驻 daemon、轮询定时器或开机修复任务；打开开关也不会立即重绑服务。无障碍 onInterrupt 只是反馈中断；连接仍正常时不取消任务，旧服务销毁也不干扰新实例。运行期间遇到真正的服务断开仍停止任务，不在中途重新授予权限或继续执行旧动作。

## 小米“假开启”的处理

`ScreenService.isHealthy()` 检查服务实例并通过 `getServiceInfo()` 查询系统 Binder 连接。不是只看设置 UI 或 `enabled_accessibility_services` 中是否列出组件，也不依赖页面产生无障碍事件。因此服务设置仍开启但实际连接已丢失时，也会进入修复。

`AccessibilityGuard` 从 APK assets 读取 `accessibility-preflight.sh`，经 `su` 临时执行。只传递本机生成的 Android 用户 ID 和任务 UUID；模型文本不进入 shell 命令。脚本要求 root、当前前台用户和已解锁用户存储，并通过 root 专用 IPC 校验开关、任务 UUID、准备阶段及没有正在执行的任务。

修复先读取当前无障碍列表，移除 MiAIPlus 的短/完整组件名，等待系统解绑；再次读取列表后加入模块的完整组件名并确保全局开关为 1，保留其他服务，不关闭全局无障碍开关。设置变更前重复核对任务票据，取消、过期或已经开始执行的任务不再重绑。最多等待十轮连接检查；Java 调用另有 45 秒 root 等待上限，并响应取消。

root helper 不截图、不发送手势、不处理系统授权弹窗。控屏执行器对当前 launch 的小米“启动应用”确认自动选择“本次允许”，见 [应用启动](app-launch.md)；这不使用 root。用户存储已解锁不等于屏幕锁已解除，实际控屏仍遵守锁屏、安全窗口与四分钟截止限制。

## 旧版本升级

沿用原开关偏好。旧版已启用用户打开模块后，一次性清理本模块的 `/data/adb/service.d/miaiplus-accessibility.sh`、旧启用标记，并在 PID 命令行匹配本模块 daemon 时终止旧进程。任务前修复也会执行清理。旧健康 IPC 的 `guard_enabled` 固定返回 false，旧 daemon 会退出；旧 `accessibility_recover` 不再取消任务。

关闭开关只关闭任务前自动修复，保留当前服务设置。完成旧版清理后关闭无需 root。若迁移时应用 root 被拒绝，需要重新授权后再启用；不能保证旧 root 进程在已撤权状态下立即被杀死。

## 验证

```sh
python3 scripts/test_accessibility_guard.py
# 已安装 debug 和 AndroidTest APK、启用模块无障碍；需要 adb root 授权
python3 scripts/accessibility_guard_device_test.py
```

主机测试运行实际脚本并替换 Android 命令，覆盖假开启、短组件名、空设置、健康连接快速退出、执行中拒绝修复、开关关闭、过期/取消票据、解绑期间取消和等待上限。

设备 runner 使用合成的任务准备状态，清除可用服务引用、保留系统启用列表，验证没有后台自愈，再由 ADB-root 执行实际 helper 重绑，检查准备任务未被取消、其他服务保留；随后校验执行阶段不能再次调用 helper。测试不截图、不发送手势。它模拟假开启，不等于复现了所有小米 ROM 的实际掉线原因；ADB-root 成功也不等于应用 UID 的 su 授权已通过。最新实测范围见 [验证状态](validation.md)。
