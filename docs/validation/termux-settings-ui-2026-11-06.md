# Termux 配置二级页（2026-11-06）

插件页保留 Termux MCP 主开关和「Termux 配置」入口，删除页尾的「停止调用」。新页沿用控屏页的 Miuix 设置行和返回层级。「按需启动 Termux」即时保存；「启动服务」和「自动配置／重新配置」仍须手动触发。设置页从后台恢复时重新读取安装状态，配置操作的失败信息在切页后仍可查看。

- 535 项 JVM／Robolectric 测试通过。新增检查覆盖未安装、安装后返回原页面、已配置状态、主开关与按需开关、设置保存、页面重建和返回。对照控屏页目视检查浅色、深色及 320 dp 窄屏的 1.5 倍字号，截图见 [设置界面](../settings-ui.md#截图)。
- Debug／Release 完整 Lint 零错误；Debug、Release、Preview 和 Debug AndroidTest APK 构建通过。核对 Preview 仍包含内置 Termux MCP 程序和启动脚本。
- 在 Xiaomi HyperOS／API 37 安装优化 Preview 更新包。实机核对插件页主开关与两个配置入口、新页的三个设置项、底栏选中态、系统返回及左上角返回；对照控屏页检查布局。沿用原配置，没有触发启动服务或重新配置。

本轮未重新执行小爱模型任务。真实模型多步 shell 及内置服务冷启动的证据分别见 [小爱会话](termux-harness-2026-11-06.md) 和 [内置工具](termux-managed-2026-11-06.md)。
