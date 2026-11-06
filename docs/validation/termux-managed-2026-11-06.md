# 内置 Termux MCP 一键配置和按需启动（2026-11-06）

本轮将 [手动接入](termux-harness-2026-11-06.md) 的同一版上游程序作为独立资产打包。安装已签名的本地 Preview 更新包，保留原应用数据和模型配置。设备为 HyperOS、Android API 37，小爱 `8.3.30.4420`，Termux `0.118.3`，使用设备原有的 MiAIPlus root 授权和 Termux Python。

| 实机检查 | 观察结果 |
| --- | --- |
| 一键配置 | 从「插件」点击内置 Termux MCP，应用生成回环地址和私有 Token，启动服务并发现 3 个内置工具；未手工输入地址或凭证。首次配置在 Debug 通过，优化 Preview 在冷态验证了「重新配置」。按需开关配置后默认为关。 |
| 按需启动关闭 | Termux 服务和进程均不存在；小爱调用返回「Termux MCP 未运行…本次命令未执行」，设备仍无 Termux 进程。 |
| 按需启动开启 | 切开关本身没有启动进程。小爱真实模型调用 `miaiplus_termux_run_digest` 后，Termux 和 Python 进程出现，服务只监听回环。最终回复包含普通 Termux UID 及提示中未给出的随机测试文件内容；当前界面仍为小爱。 |
| HyperOS 冷启动拦截 | 实机观察到 `WakePathChecker` 拒绝原始冷态服务请求。只为一次固定启动请求临时放行系统后台自启动，启动后核对设置恢复原值。系统「强行停止」与进程被回收不同，前者不走按需启动。 |

设备保留新版、内置 Termux MCP 和开启的按需开关。上一轮手动配置仍在，为避免同一套工具重复出现在小爱中已停用，未删除其配置。移除了本轮随机测试文件和临时界面探针；未加 ADB 转发或开机任务。未验证未初始化的全新 Termux、自动装 Python、其他 ROM／root 实现或小米官方模型。

本地执行 `:app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assemblePreview :app:assembleDebugAndroidTest`，共 533 项 JVM／Robolectric 测试通过。核对上游资产可从固定源码重建、实际 APK 资产路径、启动脚本语法及 `git diff --check`。未发布。
