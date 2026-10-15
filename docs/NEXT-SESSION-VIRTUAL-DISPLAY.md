# 可直接发送给下一个 Agent Session 的任务

请在当前 MiAIPlus LSPosed 模块上实现“同一台手机的后台虚拟屏控屏”：用户继续操作物理主屏，Agent 在独立 display 启动已有 App、截图、点击、滑动、中文输入并完成任务。

先读 `AGENTS.md`、`docs/virtual-display-implementation-spec.md`、`IMPLEMENTATION-STATUS.md`、`docs/architecture.md`，再审阅核心源码。以规格文档为本轮调查结论，直接开展 P0 能力探测和 P1 本地后端，不要重新只输出泛泛调研。仓库源码当前 0.2.3，状态文档版本可能滞后；没有 Git 元数据。

源码复核补充：主线已包含 NativeIntentHook/IntentProtocol 的意图 v2 路由适配，以及 NativeModelHook 的非流式记忆摘要替换。保留这些入口；原生工具已有三坐标空间、真实 JPEG 元数据和执行前页面重观察，外部循环仍主要使用 normalized，不能假定两者行为完全相同。取消已有非阻塞/HTTP 断连，缺少的是显示资源释放。详见规格 §2.2。

必须复用现有小爱官方模型逐步工具/截图管道，并让 NativeSession 和可选外部 AgentEngine 共用 display-aware backend。默认官方模式不需要外部 Key/模型。保留精确小爱版本校验及现有动作/坐标校验。

当前 ScreenService 仍全部指向默认物理屏，LSPosed scope 仅小爱；没有特权 helper 和虚拟屏。建议首期为本机 root/shell helper + own-content VirtualDisplay + 目标屏观察/输入；scrcpy new-display 用于 PoC 和源码参考，不作为产品必须依赖的桌面进程。先验证创建、捕获、第三方 App 启动、树、输入、焦点、IME、销毁隔离；全部通过才称后台可用。GestureDescription.Builder.setDisplayId 自 API30 可用，但不能据此跳过隔离验证。

关键约束：

- 后台失败绝不静默回退点击主屏；back/home/launch/type/窗口树也不能走全局默认路径。
- frame/action 绑定 session、display generation、最新序号和坐标空间；display 由服务端所有者固定，不给模型任意跨屏控制权。
- 用户前台输入、焦点、IME、应用任务不得被抢；虚拟屏关闭/崩溃不能把后台任务迁回主屏。
- 只允许一个 active run；取消、超时、helper/服务死亡幂等释放资源；不自动重放结果未知的动作。
- 不绕过锁屏、安全窗口、密码/验证码/授权；保留单动作确认。任务预览由用户主动打开，通知提供停止，后台不要弹主屏悬浮窗。
- 同一包的主屏与后台并发不保证独立；首期明确拒绝冲突，虚拟屏不是应用克隆。
- 只有看到目标 ROM 的明确阻塞才考虑窄范围 system_server 适配，不先广泛关闭系统校验。

按规格分阶段交付：P0 capability JSON → P1 双屏 fixture 确定性闭环/故障清理 → P2 官方真实模型及可选外部循环 → P3 版本矩阵/文档。单元测试、Lint、构建按 AGENTS.md，设备测量避免现有脚本初始化/重启小爱对主屏的干扰。分别报告 mock 通路、真实模型和未测兼容性。

本请求授权实现本仓库代码和测试；涉及设备/root/系统作用域变更时按本 Session 的设备授权与实际工具约束执行。不要把上一 Session 的文档调研当成已实施或已完成设备授权。交付可构建代码、probe、前台隔离证据、清理测试及准确限制；若阻塞则保留已完成成果和可定位错误，不宣称后台功能成功。
