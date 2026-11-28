# 验证索引

更新日期：2026-11-25。下列结论对应各记录中的构建和日期；测试命令、完整结果与设备恢复信息见原记录。发布包范围见 [发布说明](releases/)，不按版本名推断构建相同。

## 控屏、模型与会话

| 日期／记录 | 主要结果与未覆盖项 |
| --- | --- |
| [2026-11-25：Anthropic Messages](validation/anthropic-messages-2026-11-25.md) | 小爱原生流式文本、工具续调、截图与非流式辅助请求通过 ADB mock，独立规划及 Chat／Responses 回归通过。CC Switch 的两个真实服务均返回 HTTP 错误。 |
| [2026-11-18：target 引用与重规划](validation/phone-use-targets-2026-11-18.md) | 完整 756 项本地测试及 7 项 guard 测试通过，双变体完整 Lint 与 Debug／Release／AndroidTest 构建通过。Xiaomi 25019PNF3C／API 37 fixture 的 `--robust-screen` 严格核对 3 次物理触摸与按钮点击；移动／替换目标后拒绝旧引用并重新观察。使用无真实模型的宿主探针，未测第三方页面与真实模型任务质量。 |
| [2026-11-17：应用启动](validation/app-launch-2026-11-17.md) | JVM 与 Debug 检查通过；ADB root shell 两次启动及正式包 root 探针正常。独立测试 UID 的 su 被拒（errno 13），完整模块链路尚未通过。 |
| [2026-11-16：Eta](validation/eta-agent-2026-11-16.md) | 真实 Eta 3.4.4 的状态、取消、遗留结果确认通过；官方模型点名 Eta 时委派，Eta 动作协议完成计算器与设置搜索。未点名的多网页调研未委派；锁屏、敏感工具、冷绑定与热重载拒绝仅有单测。 |
| [2026-11-14：models.dev 缓存](validation/models-dev-2026-11-14.md) | 实机下载 236 个 provider／9,135 个模型，磁盘重载及宿主 Chat／Responses mock 通过；未调用生产模型。新请求协议仍需代码适配。 |
| [2026-11-11：语音与非简中入口](validation/menu-speech-language-2026-11-11.md) | 深浅色菜单、三档语音请求／流式门禁、英文与繁体入口通过；英文系统下真实模型完成计算器任务。语音 fixture 不测播放与声学，旧小爱未降级复测。 |
| [2026-11-08：前台入口与截图归属](validation/native-foreground-grounding-2026-11-08.md) | 实机原生及两种独立协议的无 app `finish`、前台信息、清理通过；真实模型完成图片宿主→计算器。保留路由修复，已撤回 finish 包名硬门槛。抖音原视频未确认，微信图片查看器未复现；官方模型因积分上限未通过。 |
| [2026-11-08：官方后台入口](validation/native-background-routing-2026-11-08.md) | `mimo-omni` 经真实文字、合成最终 ASR 及 API 102 热重载后的 ASR 均完成后台计算器，图片匹配、主屏隔离与清理通过。未测声学唤醒和最终聊天回复正文。 |
| [2026-10-28：模型观察与执行状态](validation/phone-use-observation-2026-10-28.md) | 真实应用导航、Chat／Responses mock、宿主附图与续调通过。48 帧树 UTF-8 体积减少约 4.53%／6.43%，未测整体请求收益或真实模型质量 A/B；部分后台／su 专项受限。 |
| [2026-10-19：会话生命周期](validation/conversation-lifecycle-2026-10-19.md) | API 37／小爱 8.3.30.4420 的全屏与实际浮窗连续性、超时新建、通知返回及焦点通过；回复来自本地 mock。未测真实模型工具一体链路、实体键／热词、进程重启、多窗口及旧版设备。自动新建对话已移除；此条为历史验证。 |

## 插件与 Termux

| 日期／记录 | 主要结果与未覆盖项 |
| --- | --- |
| [2026-11-11：Termux 执行](validation/termux-ux-2026-11-11.md) | 真实 Termux、模块桥接及小爱代理的命令原文、Bash、超时、后台任务、取消、大输出与策略拒绝通过。报告中的 P0 原故障未在旧服务直接调用中复现。 |
| [2026-11-06：并行与恢复](validation/termux-parallel-2026-11-06.md) | 当前真实模型一批执行 6 条命令，峰值 4 条并发；退出码 7 后同轮执行后备命令。使用已运行的 Termux，未测并行冷启动或小米官方模型。 |
| [2026-11-06：一键配置与按需启动](validation/termux-managed-2026-11-06.md) | 实机一键配置、关闭时冷态拒绝、开启后后台启动并回读随机值通过。使用已有 Python，未测空白 Termux 初始化。 |
| [2026-11-06：真实模型 MCP 会话](validation/termux-harness-2026-11-06.md) | 当前配置的模型完成身份核验、按文件生成并校验报表、大输出引用接力。上游 `isError=true` 会停止该轮，预期退出码作为普通结果可继续。未测小米官方模型、root shell 或持久终端。 |
| [2026-10-28：Termux MCP 协议](validation/termux-mcp-2026-10-28.md) | 开源 `termuxgpt/termux-mcp` 以普通 Termux UID 运行，Provider 与小爱代理的 shell／文件管道／错误语义通过；显式选择 `2025-06-18`。未测模型自主任务、持久终端或 root shell。 |
| [2026-10-28：Plugin 首阶段](validation/plugin-platform-2026-10-28.md)／[独立包实机](validation/plugin-platform-device-2026-10-28.md) | API 37／小爱 8.3.30 的注册表、MCP proxy、合成 HTTP、前台及空后台屏观察／wait／finish 通过。覆盖 `2025-11-25` tools 子集，未测真实模型选择或第三方生产 MCP。 |
| [2026-10-29：主线整合](validation/settings-copy-main-2026-10-29.md)／[分支收尾](validation/pluginify-main-cleanup-2026-10-29.md) | main `8e9ff66` 与 Pluginify `4293f3e` 整合后本地检查及两组设置实机回归通过；收尾确认提交均进入 main 并删除分支，仅做 Git、文档与 CI 配置检查。 |

## 设置、诊断与发布

| 日期／记录 | 主要结果与未覆盖项 |
| --- | --- |
| [2026-11-16：数字输入](validation/number-input-2026-11-16.md) | Compose 回归与 API 37 专项确认逐键 `2`→`20`、光标和取消后配置不变；独立 Preview 测试包，未跑完整 Lint。 |
| [2026-11-14：提示词与人设交互](validation/prompt-persona-ux-2026-11-14.md)／[注入初版](validation/prompt-persona-2026-11-14.md) | 实机设置交互、明暗菜单与宿主配置回读通过；人设深色／1.5 倍字号由 Robolectric 覆盖。交互轮未请求模型，注入证据见初版记录。 |
| [2026-11-10：通用日志与拒绝诊断](validation/diagnostics-2026-11-10.md) | API 37 mock 的宿主、前后台、双工具、日志开关与真实 ZIP 导出通过，73 个 Hook 及三个可选观察器就绪；未复现用户报告的 HyperOS 3 故障，根因未定。 |
| [2026-11-06：设置 UI](validation/settings-ui-rewrite-2026-11-06.md) | 本地检查和实机插件导航通过，主图更新为合成截图；完整设备表单 Runner 未正常结束。 |
| [2026-11-06：Termux 二级页](validation/termux-settings-ui-2026-11-06.md) | 实机入口、选中态和返回，以及深浅色、320 dp、1.5 倍字号检查通过；范围为设置界面。 |
| [MCP 编辑输入顺序](settings-ui.md#插件页) | 输入与插件 UI 回归通过；完整套件 528 项中有 1 项首页无障碍恢复失败（找不到「自动拉起无障碍」），未测设备输入法。 |
| [2026-10-29：MCP 添加与发布检查](validation/plugin-add-release-2026-10-29.md) | 320 dp 对齐与 API 37 插件实机回归通过；签名发布产物单独核验。 |
| [2026-10-28：首页字号](validation/settings-home-typography-2026-10-28.md) | 本地布局与实机首页、配置恢复、无障碍连接通过；深色由 Robolectric 渲染。 |
| [2026-10-28：插件底栏](validation/settings-plugins-tab-2026-10-28.md) | 实机入口、选中态、返回、重建与草稿确认通过。 |
| [2026-10-28：切页动画](validation/settings-tab-animation-2026-10-28.md) | 明暗色、中断过渡及实机设置回归通过；底栏 P95 19 ms、1/660 帧超时，仍有 150 ms 长帧。 |
| [2026-10-19：列表底部空白](validation/settings-bottom-layout-2026-10-19.md) | 深浅色及未保存状态的布局断言与渲染通过，未测实机导航栏与软键盘。 |
| [2026-10-18：日志与导出](validation/diagnostics-2026-10-18.md) | mock 驱动实机前后台与宿主采集，持久化、导出隐私通过；日志页使用合成元数据验证搜索、筛选、保存读回、分享与清空。原间歇故障未复现。 |
| [模块图标](module-icon.md) | 已核对矢量静态预览；最新指针调整未编译或安装验证。 |
| [1.0.1-preview 基线](validation/preview-1.0.1.md)／[发布说明](releases/v1.0.1-preview.md) | 构建、Lint、345 项本地测试及 Xiaomi API 37／小爱 8.3.30 的权限、chooser、动态页面、后台输入隔离验收通过；审计回复为 fixture。 |

## 验证范围

- 近期设备为 Xiaomi／API 37／小爱 8.3.30；Android 13+ 与旧小爱 8.2.65 绑定的支持范围不代表已在这些环境重新验收。
- 小爱 8.2.66.8912 仅经 APK 符号表比对复用 8.2.65 绑定，未实机验证。
- 表中分别标注 mock、设备和真实模型证据。复杂多步任务、第三方应用覆盖及长期成功率尚无系统评测；代表性成功与失败见发布基线。
- 诊断只观察到 [日志说明](phone-use-logging.md) 所列边界；后台能力及故障策略见 [后台虚拟屏](background-display.md)，操作限制见 [权限与连续性](phone-use-permissions.md)。
- 动态页面与性能方法见 [动态页面实测](phone-use-dynamic-pages.md)、[设置性能](settings-performance.md)。

维护时优先更新已有功能文档与本页对应条目；只有独立、值得复用的验证证据才新增阶段记录。发布构建与签名流程见 [发布流程](releasing.md)。
