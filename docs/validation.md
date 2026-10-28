# 实现与验证状态

更新日期：2026-10-28。这里汇总当前源码的验证依据与限制；具体操作、失败尝试和恢复情况保留在分阶段记录中。源码、正式发布包和本地测试安装包应分别判断，不能因版本名相同而视为同一构建。

## 当前源码与发布基线

| 范围 | 已有证据 | 边界与详细记录 |
| --- | --- | --- |
| `v1.0.1-preview` 发布基线 | 345 项 JVM／Robolectric 测试，Debug／Release Lint 与发布相关构建；Xiaomi API 37／小爱 8.3.30 的权限、chooser、动态页面、后台输入隔离确定性验收 | 审计回复为 fixture；不是最新日志功能的发布证据。见 [发布基线记录](validation/preview-1.0.1.md) 与 [发布说明](releases/v1.0.1-preview.md) |
| 可选详细日志与私有轮转存储、ZIP 导出 | 364 项 JVM／Robolectric 测试、Debug／Release 构建与完整 Lint、Debug AndroidTest 构建；本地 mock 驱动真实前台／后台及宿主采集，核对双来源持久化与导出隐私 | 未调用真实模型，未复现原先间歇性故障；诊断能力不等于故障修复。见 [诊断验证记录](validation/diagnostics-2026-10-18.md) |
| 最新日志页布局 | 同为 364 项测试，双变体构建与 Lint；真实设备完成搜索／筛选、菜单、ZIP 保存读回、分享面板及清空 | 此轮 UI 测试使用合成元数据，不重新证明宿主采集或控屏链路。见 [日志页验证](validation/diagnostics-2026-10-18.md#日志页精简与-lsposed-布局参考2026-10-18) |
| 当前模块图标 | 矢量资源生成静态预览并核对 | 最新指针调整未编译或安装验证；见 [图标资源](module-icon.md) |
| 全屏／浮窗自动新建对话修复 | 375 项 JVM／Robolectric 测试，Debug／Release 完整 Lint 与构建、Debug AndroidTest 构建通过；API 37／小爱 8.3.30.4420 的全屏／实际浮窗连续性、四轮超时新建／重开后的原生回复及屏幕正文、通知返回与焦点回归通过 | 回复来自本地 mock，与工具返回专项独立，未验证真实模型工具一体链路；未测实体键／热词、进程重启、多窗口及旧版设备。旧探针局限及本轮实际浮窗身份、唯一正文 OCR 验证见 [会话生命周期修复](validation/conversation-lifecycle-2026-10-19.md) |
| 模型 API 列表底部空白修复 | 376 项 JVM／Robolectric 测试，Debug／Release 完整 Lint 与构建、Debug AndroidTest 构建通过；深浅色及未保存状态的布局断言与渲染检查 | 本轮未安装或操作手机，未实测系统导航栏与软键盘。见 [底部布局记录](validation/settings-bottom-layout-2026-10-19.md) |
| 首页概览卡字号与等高布局 | 376 项 JVM／Robolectric 测试，Debug／Release 完整 Lint、Debug／Release／Preview APK 与 Debug AndroidTest 构建通过；API 37 实机覆盖安装后核对首页、配置恢复及无障碍连接 | 深色使用 Robolectric 渲染；未运行控屏、真实模型或宿主 Hook 回归。见 [首页字号记录](validation/settings-home-typography-2026-10-28.md) |
| 底栏切页动画 | 377 项 JVM／Robolectric 测试，Debug／Release 完整 Lint 与各 APK 构建；深浅色和中断过渡断言、API 37 优化 Preview 设置回归与性能采样通过 | 底栏采样 P95 19 ms、1/660 帧超时，仍有 150 ms 长帧；未重做真实模型或 Hook 回归。见 [底栏动画记录](validation/settings-tab-animation-2026-10-28.md) |
| `Pluginify` 分支的统一 Plugin 层、独立包与 MCP tools 首阶段 | 506 项 JVM／Robolectric、双变体 Lint 零错误及各 APK 构建；独立包并装，API 37／小爱 8.3.30 实际注册表／MCP proxy、Android 合成 MCP HTTP、前台及空后台屏截图／wait／finish 通过 | 固定 MCP `2025-11-25` tools 子集；未测真实模型选择、第三方生产 MCP 或复杂应用任务。测试后恢复主线作用域、撤销新包临时 root；保留 Pluginify 优化安装包但模块停用。见 [本地首轮](validation/plugin-platform-2026-10-28.md) 与 [独立包实机记录](validation/plugin-platform-device-2026-10-28.md) |
| 现有 Termux MCP 与显式协议兼容 | 513 项本地回归、双变体 Lint 零错误及各构建；真实开源 `termuxgpt/termux-mcp` 在手机 Termux 非 root UID 运行，Android Provider 和小爱实际代理的 shell／文件管道／错误语义通过 | 用户显式选择 `2025-06-18`，默认仍为 `2025-11-25`，不自动降级；隔离 HOME，只操作自有文件，未测模型自主任务、持久终端或 root shell。见 [Termux 实机记录](validation/termux-mcp-2026-10-28.md) |

各行按其日期和验证范围独立判断；2026-10-19 会话修复重跑了本地测试、Lint 与构建，并安装测试包完成上述设备专项，未运行 CI。详细日志、日志页及会话修复属于发布基线之后的源码改动，未据此创建新的正式签名发布版本。

## 持续有效的限制

- 支持 Android 13+、LSPosed API 102，以及精确匹配的小爱 8.2.65.4714／8.3.30.4420。近期设备验收使用 Xiaomi／API 37／小爱 8.3.30；旧小爱绑定保留不等于本轮已降级复测，其他 ROM／语言仍需独立验证。
- 确定性 mock、JVM 模拟、真实设备执行与真实模型任务质量是不同证据。复杂多步任务准确率、全部应用／动态页面、任意供应商兼容和长期任务成功率仍无全面保证。历史真实模型失败及代表性成功见 [基线记录](validation/preview-1.0.1.md)。
- 官方私有传输的诊断只确认到宿主附图边界；外部请求图片摘要也不能证明服务端收到、模型理解或画面正确。详见 [日志说明](phone-use-logging.md)。
- 后台探针各项能力与端到端任务分开验收；helper 不测无障碍树，独立服务侧的树证据不可回填成探针通过。后台故障停止任务，不静默回退物理屏；仅明确的应用冲突交接允许按规则切换。详见 [后台虚拟屏](background-display.md)。
- 锁屏、安全窗口、密码、验证码／CAPTCHA、任务所有权、取消、确认和超时限制持续生效。Manual／Auto／YOLO 不等于授予 Android 权限；Auto 审计不保证独立安全判断。详见 [权限与连续性](phone-use-permissions.md)。
- 外部模型替换覆盖已适配的 OSBot 入口，不代表原语音云端 NLP、实时会话或 APK 内所有模型流量均被替换。默认官方模式仍联网推理，APK 不包含 AutoGLM 模型权重。

## 证据与维护

- [诊断功能与日志页记录](validation/diagnostics-2026-10-18.md)：采集、存储、导出与最新布局的分轮本地／设备结果。
- [会话生命周期修复](validation/conversation-lifecycle-2026-10-19.md)：每次打开重新计时、全屏会话保留、浮窗实际路由与旧探针结论更正。
- [1.0.1-preview 基线与历史记录](validation/preview-1.0.1.md)：发布构建、权限、协议、会话、显示与更早版本验证。
- [动态页面实测](phone-use-dynamic-pages.md)、[设置性能记录](settings-performance.md)：专项方法及范围。

新增行为先更新对应功能文档；新验证按阶段记录构建／提交身份、检查命令与结果、mock 或真实模型、设备范围及未覆盖项，再更新本页摘要。没有新执行结果时只注明文档核对，不复制历史测试数作为本轮通过结论。发布说明保持发布时范围，后续源码改动不追加为旧发布包功能。正式分发与签名要求见 [发布流程](releasing.md)。
