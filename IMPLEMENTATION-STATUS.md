# MiAIPlus 0.3.3 实现与验证状态

## 虚拟屏前端（2026-10-16）

独立前端分支新增「控屏 → 虚拟屏」二级页，接入既有 `background_display_status` 与 `background_display_probe`。包含执行模式、root/解锁前置检查、测试应用选择、检测确认与逐项结果详情。操作前检查控屏任务；不自动启动 helper 或重复探测；不修改后台源码。95 项本地 JVM/Robolectric 测试通过，原有后台三处 Lint 错误仍保留。先推送 PR，再进行实机验证，结果后续补充。

## 配置界面导航（2026-10-16）

本轮只在 `ui/miuix-settings` 的独立工作区调整前端。底栏按首页、模型 API、控屏排列；首页改为 KernelSU 式状态总览，历史注入报告标注时间。二级页进入/返回使用 260 ms 绘制层过渡，结束移除旧页面，不恢复整页尺寸动画。草稿切页确认、滚动位置、深浅色与必要数据发送确认保留，文案精简。

Debug、AndroidTest 与优化 Preview 构建通过（Preview 显式跳过基线阻塞的 lintVital）；92 项 JVM / Robolectric 测试通过，包括底栏顺序、草稿保存/放弃/取消、二级页双向过渡、报告状态与按需后台屏查询。截图见 [配置界面](docs/settings-ui.md)。本轮没有同步 main 工作区、安装手机、重启小爱或请求模型；旧实机性能数据属于此前版本。完整 Lint 仍有原有后台 `DisplayHelper.java` 的三处阻塞。

## 配置界面性能（2026-10-16）

前端分支已同步远程 main `095156c`。新增优化的 Preview 构建，移除整页尺寸过渡，限制状态轮询范围，模型列表按可见项组合。Xiaomi 25019PNF3C / Android API 37 的独立测试包 12 次滚动对比：原 Debug 进程 CPU 173.71%、帧耗时 P95 93 ms；优化 Preview 为 27.75%、7 ms，0/794 帧错过截止时间。89 项测试通过。该对比包含构建模式变化，不代表单独去掉动画的收益。后台源码未修改；完整基线 Lint 仍有 3 处原有后台错误。详见 [性能与验证记录](docs/settings-performance.md)。

以下为此前实现与验证记录。

## 配置界面分支（2026-10-16）

`ui/miuix-settings` 将模块设置改为 Miuix 0.9.4 Compose 界面，含配置首页、模型服务、控屏设置、运行状态和直接测试。仅接入原有配置与服务接口；不包含 main 工作区未提交的后台屏修改。交互与集成说明见 [配置界面](docs/settings-ui.md)。

Debug、Release、AndroidTest 构建与 88 项 JUnit（含 5 项 Robolectric Compose 测试）通过，并生成深浅色及各设置页面截图。兼容检查将后台屏检查入口从旧 Java 页面迁至运行状态页，仅点击时查询；修正设备 runner 的旧截图构造调用。与 main 工作区后台屏新代码合并的隔离副本也通过上述构建、测试及两套 Lint（0 errors），该后台代码未包含在 UI 分支中。本次没有安装实体设备或验证 XiaoAI、root 与模型服务。UI 基线的 Lint 仍被未修改的 `helper/DisplayHelper.java` 阻塞：第 144、238 行 `WrongConstant`，第 229 行 `BlockedPrivateApi`；新增界面没有 Lint 错误。以下正文保留原分支的历史验证记录。

本轮复核修复（2026-10-16）：新任务在旧任务终态轮询前开始时替换观察器并保留返回入口，避免灵动岛和停止按钮消失；区分安全中心普通页面与授权窗口，普通页面可启动其他应用，其他授权仍暂停。68 项 JUnit、debug/release/AndroidTest 构建和两套 Lint 通过；本次设备专项与历史验证分列如下。

日期：2026-10-16（Asia/Shanghai）。预发布测试版；下列“通路通过”不代表真实模型任务成功率保证。

## 本次实现

- 2026-10-16 API 多配置：保存命名的 Endpoint / Key / 模型 / API 协议，支持另存新增、重命名、删除和下拉快捷切换；旧单套配置保留为默认配置。未保存修改切换前确认，重名拒绝，至少保留一套；全局开关、控屏规划和步数独立保存。异步模型列表丢弃已切换服务的结果，页面重建保留编辑内容。集合和运行时字段一并提交，Config 读取单次快照防止地址/密钥混配。
- 2026-10-16 命名：界面使用 phone use，独立工具为 `phone_use`，原生工具为 `miaiplus_phone_use` / `NativePhoneUseTool`；旧 `computer` 协议设置兼容读取。通知渠道 ID 保留以沿用已有系统设置。原生新请求刷新 Endpoint，记忆 HTTP 构造沿用本次配置快照。

- 2026-10-16 复核：任务快速衔接时切换 `screen_task`、观察器和停止令牌；旧回调失效，沿用原始隐藏入口，不在主线程等待旧 IPC 退出。安全中心可见权限标题区分启动确认、其他授权和普通页面，避免仅凭包名误拦截；点过启动允许后出现其他授权仍暂停。
- 2026-10-16 测试残留清理：收起小爱时会露出后台上一任务，先前残留的 launch fixture 因此短暂显示。已移除设备旧测试任务；测试 launcher 不保留历史/最近任务，主机脚本结束时通过 shell 专用广播关闭仍在前台的 fixture，Runner 结束移除后台模块测试任务。日常 launch 仍直接使用所请求包名的显式 launcher，不经过 fixture。

- 0.3.3：修正无障碍 onInterrupt 仅代表反馈中断、并非连接丢失；只有实际连接失效才取消任务，旧实例销毁不取消新实例的工作。
- 0.3.3：统一控屏 launch，提供本机应用列表、只解析合法 launcher、消除同包多个入口的名称歧义；目标已在前台时不重复启动。匹配小米安全中心启动确认后自动允许本次，再等待目标稳定前台；未打开、其他授权或超时明确 PAUSE，不把 startActivity 返回当成功。详见 [启动行为](docs/app-launch.md)。

- 0.3.2：原生和独立循环均支持 action.intent；岛右侧每 700 毫秒检查变化，等待模型时保留最近意图，缺省按动作回退。普通意图不触发敏感确认，不显示原始推理或输入字段；安全构造 JSON、限制文本长度、更新不反复弹卡片。intent 不参与重复执行指纹。
- 0.3.2：“root 控屏前检查无障碍”只检查新任务；健康时直接执行，失效才执行有界 root helper，确认 Binder 重连后开始。后续 CONTINUE 不再 root 检查，执行中断线只停止任务。迁移时清理旧 daemon 与 service.d，旧恢复接口不再取消任务。详见 [任务前检查](docs/accessibility-guard.md)。

- 0.3.1：移除常驻无障碍停止悬浮键；展开焦点卡片通过 `param_v2.actions` 引用 `miui.focus.actions` 中的同 UID 不可变广播 PendingIntent。标准通知保留同一停止动作，敏感操作确认窗保留。
- 0.3.1：Provider 用 `screen_task` 标识整个任务，连续工具返回和规划等待保持同一生命周期；仅在会话已终止、执行线程退出后确认结束。绑定任务 ID 防止旧观察线程恢复新任务；控屏期间阻止宿主界面更新将小爱重新拉到前台。无坐标的 wait 允许页面更新后重新观察。
- 0.3.1 历史实现：曾使用常驻 root daemon；0.3.2 已按任务前检查替代。

- 迁移到 libxposed API/service 102：使用现代 `XposedModule` 生命周期、API 102 元数据和 `XposedService` 热重载；清理旧 Hook、原生客户端、ToolRegistry 代理、诊断广播和 Activity 回调后再重新安装 Hook。控屏或异步模型工作进行中时拒绝热重载。
- 控屏开始时在小爱 UID 发布 HyperOS 原生焦点通知岛“正在操作手机”，保存原始 Activity/任务入口并在完成后恢复全屏页面；快捷键系统浮窗隐藏并放开输入，完成时自动展开通知岛，点击打开小爱查看结果，PAUSE 提示接管，锁屏不强行拉起界面。

- 补齐普通文字聊天的自定义 API 路由：原 AUTO/QUICK 会经 AIVS Nlp.Request 绕开 OSBot。仅在全局外部模式开启时，聊天页发送选择原生 MiClaw 分支，保留支持校验、历史、卡片与现有工具链；默认模式执行原判断，不改原模式或存储。配置不可读时停止提交并提示。

- 修复外部模型请求取消/断连导致的小爱闪退：Xposed 将 IOException 包装为 InvocationTargetError，原拦截器让该 Error 逃出 OkHttp 异步线程。新增 TransportBoundary，展开反射/代理包装并保留原 IOException；适配错误转换为不含请求内容的 IOException，虚拟机致命错误不吞掉。Responses 转换失败关闭原 body。
- 聊天页原“自动 / 快速 / 专家”入口在全局外部模型开启时替换为模型对应的思考档位；普通输入栏和全屏功能栏分别适配。默认官方模式保留原生菜单、枚举、回调及存储。
- 新增 `ReasoningPolicy`：已确认 OpenAI 模型按各自有效 effort 集合显示（GPT‑6.1 Sol 无 none/minimal）；DeepSeek Flash 使用独立开关/effort，Qwen 已确认混合模型使用开关。未识别模型/协议组合仅显示模型默认；不推断任意网关扩展。
- 选择经 UID 校验 IPC 按 endpoint/model/api 单独保存，拒绝过期菜单与非法档位。新请求读取当时选择，已在途请求参数不变；外部默认不继承原生 enableThinking 布尔值。原生与独立模型请求均注入正确线上字段，Responses 回放缓存按服务/模型共享于不同档位。

- 新增独立 API 协议选择（auto/responses/chat）。自动将 `gpt-6` 前缀模型转至 Responses；可手动选择以支持服务别名。
- 全局主模型、记忆、gate/能力判断/路由、独立文字/控屏循环均支持 Responses；实际发送 input/input_image/扁平函数工具/call_id，接收 output 与 Responses SSE。原生前端仍使用原消息和 Agent 事件解析。
- Responses 不发送固定 temperature/top_p，使用 store:false；独立历史保留原始 output，原生路径使用有界内存缓存回放加密 reasoning/phase。工具调用仅在完整 completed 输出验证后释放，异常/不完整响应停止。独立输出预算 16384；GPT-6 原生小额预算至少 8192（含推理 token）。

- 原生结构化 `miaiplus_computer` 函数接入原 ToolRegistry；显式支持截图像素、屏幕像素和归一化坐标，旧 controlApp 保留。
- 默认“小爱当前模型”逐步规划：`controlApp` 返回会话号、截图、无障碍树与 CONTINUE；原主模型继续提交单步动作。不要求填写外部地址、Key 或模型。
- 可选全局外部主模型：在精确支持版本的 DynLlmClient / 直接 Taiyi、OpenAI、Responses、Anthropic、记忆摘要非流式请求以及 intent 路由/gate 入口替换模型配置，保留原消息、提示词、工具、原生 SSE 与工具执行。仅覆盖 OSBot 推理入口，不能宣称所有独立云服务都已替换。
- 外部推理使用独立 Ktor/OkHttp 网络实例，不复用小米账号、ServiceToken、设备签名和日志拦截器；关闭重定向。
- CONTINUE 观察清除原直接答案标记，图片在 ToolResult 阶段附加，避免后续消息复制丢失；JPEG 元数据使用编码后尺寸。
- 一次性截图管道绑定调用 UID，60 秒失效；原生 TOOL 消息构造时附加 ImageContent。图片加入小爱后，历史保存行为由原应用控制。
- 保留独立外部 computer / Open-AutoGLM 协议循环与第三方文字对话；默认并不运行第二个模型。
- 本机截图、无障碍树、点击/双击/长按/滑动、中文输入、启动应用、返回/主页、停止、步数/超时/重复动作限制。
- 精确适配小爱 8.2.65.4714 / 508002065；Hook 安装失败回滚；不改小爱 APK 或其磁盘配置。
- 默认服务地址、API Key、模型名均为空，全局外部开关默认关闭。源码包采用发布文件白名单，不打包逆向资料、原 APK、实机日志、工具链和签名密钥。

## 已通过的验证

- 2026-10-16 多配置最终包：77 项 JUnit 通过（新增 9 项配置迁移、切换、重名、删除、空配置、校验和并发快照测试）；debug/release/AndroidTest 构建通过，Lint 均 0 errors（debug 16 / release 13 warnings）。最终 debug 包已安装；设置页专项及原生 Responses Hook 重新验证通过，原用户配置已恢复。
- 2026-10-16 多 Endpoint 专项：设置页实机验证旧配置及密钥保留、保存修改、下拉切换和 Config 的地址/密钥/模型/API 整套恢复通过，测试结束恢复原配置，无真实模型请求。Chat / Responses 模拟模型控屏循环及原生 Responses Hook（模型、记忆、gate、能力和路由请求）回归通过；Chat 首次因测试输入框未获得焦点失败，重跑通过。
- 2026-10-16 复核：68 项 JUnit 全通过（新增安全中心普通页面启动、其他授权阻止启动、启动确认后出现其他授权的边界）；debug/release/AndroidTest 构建通过，Lint 均 0 errors（debug 16 / release 13 warnings）。
- 2026-10-16 测试清理验证：debug/AndroidTest 构建与 Debug Lint 通过；启动 Hook 探针（自动确认计数 1）及全屏焦点/停止/任务切换探针通过，分别结束后检查 Activity 任务栈，均无 LaunchFixtureActivity 或 FixtureActivity 残留。修复包已安装到测试机。
- 2026-10-16 实机：全屏与快捷键浮窗入口通过确定性快速任务衔接探针（暂缓旧观察器的终态读取，再结束旧任务并开始新任务），验证观察器替换、原返回入口保留、实时意图和展开停止；全屏恢复同一 Activity，浮窗保持隐藏并通过完成岛返回原生会话入口。从安全中心普通页面实际 Hook 启动独立测试 APK 通过，自动启动确认计数为 1，目标前台与重复启动保留实例通过。探针直接调用工具后消费一次性截图管道，避免多次观察耗尽未读帧额度。

- 0.3.3：65 项 JUnit 全通过；debug/release/AndroidTest 构建通过，Lint 均 0 errors（debug 16 / release 13 warnings）。新增覆盖应用名称歧义、同包多入口、启动超时、短暂前台、取消、同目标已打开、匹配/不匹配启动确认、只点击一次及点击后仍需前台验证。
- 0.3.3：小米 15 Ultra 实机复现安全中心 ConfirmStartActivity。真实 Hook 探针验证自动“本次允许”计数为 1，随后另一个测试 APK 稳定前台、返回新观察并继续同一会话；按应用名再次打开保留原实例。最终 0.3.3 安装包重装独立测试 APK 后再次验证通过，自动确认计数为 1。未修改系统授权数据库或使用 root 启动。
- 0.3.3：最终包快捷键浮窗、实时意图、连续 CONTINUE 不提前恢复、完成岛和展开停止按钮回归通过。
- 0.3.3：实机模拟模型回归通过 computer / AutoGLM / native 截图、点击、中文输入、观察完成、取消及一次性图片管道，并主动触发 onInterrupt 验证会话继续；它验证执行链，不等于真实模型总会选择正确工具和包名。
- 0.3.2：47 项 JUnit 全通过；9 项实际任务前 helper 脚本主机测试通过；debug/release/AndroidTest 构建通过，Lint 均 0 errors（debug 16 / release 13 warnings）。
- 0.3.2：小米 15 Ultra 实机全屏和快捷键浮窗专项通过。两次 wait/CONTINUE 分别携带不同 intent，断言焦点岛右侧及时变化、onlyAlertOnce 和关闭重复浮动；中间不恢复入口，finish 后恢复同一 Activity / 完成岛；实际发送展开岛绑定的停止 PendingIntent 后任务结束。调试探针改为异步工作但不占用广播队列，避免阻塞它自己测试的停止广播。
- 0.3.2：ADB-root 实机任务前检查通过。Runner 保持系统组件启用但模拟连接不可用，健康路径不调用 root；准备阶段 helper 重绑后 Binder 恢复、准备任务未取消、其他服务保留，执行阶段再次调用 helper 被拒绝且设置不变。测试后恢复原开关。此项模拟“假开启”，不声称复现所有 ROM 掉线原因。
- 0.3.0：43 项 JUnit、debug/release/AndroidTest 构建及两套 Lint 通过。实机 API 102 Hook/Responses 回归通过；专项实机截图确认“正在操作手机”及完成通知岛自动展开；全屏恢复同一个 Activity，快捷键通过原生 VoiceService 唤起真实系统浮窗，检查隐藏/触摸放行及完成通知点击入口。安装新 APK 后小爱 PID 不变，API 102 服务重载回调 SUCCEEDED，重载后继续验证 Hook。其他 ROM 仍需单独回归。
- 0.2.7 普通聊天页端到端模拟服务验证：从全新聊天输入短提示，页面显示服务端唯一标记 `UI_ROUTE_OK_027`；服务端校验 `/v1/responses`、指定模型、Bearer 认证及无小米账号/设备认证头。该测试证明实际页面发送→自定义协议→原生流解析→页面回复，不是真实模型能力证明；测试后恢复原 API 配置。
- 修复前按用户原文输入糖果题，普通聊天回答 36，日志只有官方 Nlp.Request，没有自定义模型调用；另一次短提示回复 OK 也走旧链路。这些答案不构成自定义模型身份或能力证据。
- 0.2.7 普通聊天页使用实际 API 发“只回复 OK”：已出现 OSBot 主模型调用及自定义端点请求，未再走旧 Nlp.Request 回答；服务等待响应头超时，进程存活，停止后续重试。尚无真实 Sol 成功回复，不能验证网关背后的模型身份。

- 0.2.6：35 项 JUnit 全通过；debug/release/AndroidTest 构建通过；debug/release Lint 均 0 errors（11/8 原有 warnings）。新增测试覆盖取消、嵌套反射/代理包装、错误信息脱敏及致命错误保留。
- 0.2.6 实机异常回归：在真实小爱异步 OkHttp 客户端中分别以 Chat/Responses 注入连接重置、主动取消和异常 JSON，共六项均收到 IOException 失败回调，小爱进程存活。loopback 测试不读取或修改实际 API 配置，不调用付费模型。
- 0.2.6 经用户授权，以已配置的 `gpt-6.1-sol` / Responses / low 做短提示词实测：原生客户端请求已不再崩溃，但服务返回 HTTP 400，明确表示当前账号仅支持 Chat Completions、Sol 工具调用要求 Responses。无工具的最小 Responses 请求在 `/responses` 与 `/v1/responses` 均返回 HTTP 502 / Upstream access forbidden。因此尚未收到真实模型成功回复，服务账号/上游兼容仍待解决；未改动用户 API 配置。手机锁屏期间未进行聊天页面输入验证。
- 0.2.5：31 项 JUnit 全通过；debug/release/AndroidTest 构建通过，debug/release Lint 均 0 errors（11/8 原有 warnings）。新增测试覆盖各模型有效档位、Sol 6.1 与 6.0 的 none 差异、未知别名/思考专用模型、Chat/Responses/Qwen/DeepSeek 请求参数、默认参数清理与按模型隔离持久化。
- 0.2.5 实机原生菜单检查：真实普通输入栏与可见全屏功能栏分别打开原菜单；官方模式仍为 AUTO/QUICK/EXPERT，原标签不变。外部菜单选择、重新打开恢复选择与服务端参数断言通过，选择未改变原生模式；非法/过期写入拒绝。
- 0.2.5 原生请求回归：`--hook --responses --reasoning`（GPT‑6.1 Sol 模拟模型）和 `--hook --reasoning`（Qwen 模拟模型）均通过；流式文字/工具、工具结果/图片第二轮、记忆摘要、gate/能力判断/intent 请求均由模拟服务检查所选思考参数，Responses 同时检查加密 reasoning 回放。
- 0.2.5 独立循环回归：Responses 与 Chat 均通过原生空配置会话、一次性截图管道、纯文字、computer/AutoGLM 实际截图→点击→中文输入→观察确认→结束，以及取消；各项结束恢复模块原配置。

- 0.2.4：23 项 JUnit 全通过；debug/release/AndroidTest 构建通过；debug/release Lint 均 0 errors（11/8 warnings，与原版本相同）。新增测试覆盖 API 选择、图文消息/工具/结果转换、reasoning/phase 回放、结构化输出、token usage、文字流式输出，以及 incomplete/failed/拒绝/提前 EOF 拒绝执行。
- 0.2.4 原生 Hook 实机模拟服务验证：Responses 与 Chat Completions 两种协议均通过 Dyn/直接 OpenAI、Responses、Anthropic 入口的流式文字和工具调用；第二轮实际原生消息携带工具结果和截图回传，Responses 同时校验 encrypted reasoning。记忆摘要、gate、能力判断、intent 路由均经各自原生解析器通过；模拟服务按测试模型名严格绑定 API 路径，错误使用旧端点会失败。Responses token usage 也经原生流解析通过。

- 0.2.4 独立循环实机模拟服务验证：`--responses` 和 Chat 两套测试均通过纯文字无截图、computer/AutoGLM 截图→点击→中文输入→新观察确认→结束，以及取消；默认 native 空配置会话及一次性截图管道回归通过。测试配置在结束后恢复。
- 0.2.3 历史验证：Gradle debug / release 构建、14 项 JUnit、debug / release Lint（均 0 errors；debug 11 warnings、release 8 warnings，主要为静态服务引用、界面文案和导出 Provider；Provider 有运行时 UID 检查）。
- 小米 15 Ultra / Android 17 / LSPosed 2.2.1 实机：真实 controlApp Hook、原 getCurrentPackage 工具、锁屏拒绝操作、原生 TOOL 消息图片附加。
- 空外部地址/Key/模型配置时的本机逐步会话：测试 Runner 模拟主模型提交动作，完成截图→实际点击→中文输入→新观察确认→结束；截图管道二次读取拒绝。
- 独立 computer / AutoGLM 两种协议：模拟模型驱动真实截图、手势、中文输入、新观察与结束；纯文字对话不带截图；取消测试通过。0.2.3 最终设备测试在重新连接无障碍服务后通过。
- 全局替换真实 Hook：分发器的原 Taiyi 配置及直接 OpenAI、Responses、Anthropic 客户端被换为测试模型/地址/Key，模拟服务验证原 systemPrompt、工具 schema、stream 参数、精确 Authorization；原生解析得到 SSE 文字和 tool-call 事件；记忆摘要非流式请求经干净客户端完成并返回可解析的原生响应。
- **官方默认模型无额外 API 的实机自然语言闭环通过：** 外部地址/Key/模型为空，全局外部关闭；由小爱真实模型调用 `miaiplus_computer`，启动计算器→用 JPEG 像素坐标点击 AC→新观察确认 0→调用 finish→给出完成回复。执行过程有真实 scoped Hook 调用记录与屏幕核对，模型步骤未由 Runner 代答。
- 原功能代表性回归：常规对话返回 123+456=579；主 Agent 实际调用 web_search 搜索 Open-AutoGLM 并返回项目链接；按原 settings CLI 打开系统蓝牙设置页，未改动开关。分享输入入口有时仅回复说明，主 Agent 输入才能验证工具执行。
- 独立 gate、能力判断和 intent 路由：在小爱进程调用真实 go.c.chat、router.a.b、router.t.b，通过临时模拟服务验证模型、提示词、精确认证及无小米头/计费参数，结果经原生解析器成功返回。intent-v2 转成标准消息，并把 content JSON 转回原响应结构；不确定路由使用 FALLBACK，实际工具执行留给原主 Agent。
- 坐标换算测试覆盖 1440×3200 原屏与 576×1280 JPEG 同一点、滑动端点、旧归一化协议、不修改原参数以及越界拒绝。
- 模拟服务拒绝小米账号/设备相关认证头；干净网络实例通过此检查。测试不记录截图、请求正文或认证值。
- 实机曾发现完整应用列表查询触发小米权限弹窗；已改为 launcher intent 查询，补齐聚焦窗口与无障碍事件处理。

## 尚未验证，发布时必须注明

- 0.3.3 自动启动确认目前适配实测中文小米安全中心模板；其他 ROM/语言/模板，以及真实模型是否遵循统一 launch 指引仍需验证。未知确认不会盲点，启动未确认成功就暂停。
- 应用 UID 的 su 先前被 KernelSU 拒绝（Permission denied），本轮 ADB-root 测试不能等同于应用 root 授权与旧 daemon 清理链已完成实机验证。0.3.2 不再提供 service.d 或长时间巡检，因此不适用旧版的开机/待机验证。
- 0.3.1 的展开动作停止验证此前受锁屏限制；0.3.2 已在解锁设备上完成全屏和浮窗实测。其他 ROM、真实模型是否持续生成准确意图仍需单独验证。

- 0.2.5 的 Responses 与思考档位验证使用本地模拟服务；0.2.6 的实际 `gpt-6.1-sol` 请求被服务端账号/上游拒绝，尚无成功回复。Qwen/DeepSeek 真实服务及长推理耗时仍未验证。通路回归不是模型任务质量证明。

- **复杂多步任务准确率仍未通过。** 官方模型在多步计算器任务中出现选点错误，不能宣称任意应用都可可靠自动完成。
- 所有官方模型/入口的图片支持。当前实际默认模型已利用截图完成清除任务；其他官方模型及独立传输链不能据此视为全部兼容。
- 手机设置、搜索和米家等全部功能的完整回归。已完成常规文字对话、实际 web_search 搜索及打开系统蓝牙设置页的代表性实测；这不代表所有原生功能均已回归。
- 当前版本新增的路由/gate 在锁屏状态下通过真实请求和解析验证；控屏动作回归及官方模型自然语言任务曾在 0.2.1/0.2.2 通过，0.2.3 最终设备测试也已通过；复杂任务准确率仍未证明。不能将这些通路证据视为任意应用任务的成功保证。
- 原语音云端 NLP/实时会话及其他 SDK 的独立推理未证明全部替换；已覆盖的 OSBot 接口不等于 APK 全部模型流量。
- 用户真实服务的模型名称、密钥、视觉/工具能力和连续任务质量。未使用用户服务作为默认值，模拟服务通过不代表任意供应商兼容。
- 全应用覆盖、动态页面稳定性、Agent 原生回合预算与长期任务成功率。无额外应用白名单不等于所有页面都能自动化。

## 运行限制

不绕过锁屏、安全窗口、密码输入或验证码。当前 launch 对应的小米启动确认自动允许本次；其他系统授权仍接管。敏感操作确认依赖模型标注。原模型是否选工具、是否正确理解观察与预算限制，都会影响实际效果。

APK 内置的是参考 Open-AutoGLM 的本机执行器和动作协议，没有 AutoGLM 模型权重。默认官方模式仍按小爱原模型网络路径推理，不等于完全离线。

正式分发需要发布者自己的长期签名；preview 使用开发签名，unsigned APK 需要签名才能安装。不要把预发布通路测试表述为正式功能保证。
