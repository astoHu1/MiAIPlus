# Pluginify 代码审查修复（2026-10-28）

本轮按用户要求修复三个已确认 P2，并将独立 Pluginify 分支推送与实机体验作为后续验收目标。共享工作区的 Phone Use 观察／计量变更单独保留，不混入本次插件提交。

## 修复

- SSE：CR 立即结束当前行；下一次读取再跳过可选 LF。完整结果后服务保持连接时不再为判断 CRLF 多读一字节。新增 CR-only／CRLF 持续连接回归，包含通知和最终响应；结果后任何额外读取立即失败。
- 工具发现：移除重复、非原子的启用目录预算预检，统一由配置提交锁内校验最终启用组合。停用插件可缓存发现结果，启用超限仍拒绝且不写偏好。新增工具数量与描述体积两个边界回归。
- Termux 宿主清理：按 nonce 追踪真实 worker；退出确认必须发生在 worker join 之后。取消未知 nonce 也封存身份，防止迟到启动。Runner 先确认宿主退出再恢复报告；无法确认时显式失败，不声称报告已恢复。新增六项并发回归。

## 本地验证

共享工作区完整执行 JVM／Robolectric、Debug／Release Lint、Debug／Release／Preview APK 与 AndroidTest 构建：71 个 suite，523 项测试，0 failures／errors／skipped；构建成功。该数字包含另行保留的观察／计量工作，不冒充已隔离插件提交的 CI 测试数量。

本轮新增 10 项回归。未跳过 Lint 或现有测试。源码与最终部署结果应分别判断；CI 结果以对应 Pluginify 提交的 GitHub Actions 记录为准，不能将本地构建冒充 CI 产物。

## 体验部署原则

使用现有 termuxgpt/termux-mcp 的固定上游源码，不将其打包进 APK。服务以真实 Termux UID、手机 loopback 和随机 Bearer token 运行，私有配置不上传 GitHub；提供独立启动／停止入口，不改 shell 启动配置或安装开机任务。MCP shell 具有 Termux UID 的正常权限，独立 HOME 仅隔离服务状态。

安装 CI 的优化产物时保留既有开发签名和应用数据；签名材料不上传仓库或 Actions。主线与 Pluginify 不同时对小爱注入。实际体验是否完成，应以分支 Actions 和设备验证结果为依据。
