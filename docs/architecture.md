# 架构与选型

兼容性分析发现，旧 `UIAgentServiceForOSBot.controlApp(String)` 在一次实机请求中等待云端响应十秒后超时。该记录只证明该次响应失败，不能说明全部设备的服务永久停用。原模型的工具范围说明也限制了部分应用，仅替换底层执行器不足以解决路由问题。

`AgentLlmOverride` 只有模型 ID、思考/采样/上下文配置，不能通过它单独增加任意 API 地址。`0.2.5` 将控屏执行、模型选择和模型 API 协议分开。

全局外部模型开启时，`Config` 强制 `planner=native`，控屏沿用小爱当前模型的原生工具循环（其主模型已被全局替换）。设置开启全局开关时同步持久化原生规划选项，并禁用独立外部规划选择；读取旧配置时也执行该约束。关闭全局开关后可再次手动选择独立外部规划，不自动恢复此前选项。

```mermaid
flowchart TD
  U[用户在小爱提出请求] --> D[原生 OSBot Agent / 历史 / 工具调度]
  D <--> M{统一模型入口}
  M <--> O[默认官方模型]
  M <--> X[可选外部 OpenAI 兼容模型]
  D --> T[原搜索 / 设置 / 米家等工具]
  D --> C[voiceassist__controlApp Hook]
  C --> P{控屏模式}
  P --> N[默认：只执行一步并返回观察]
  N --> E[Android 无障碍执行器]
  E --> F[截图 / 树 / 会话状态]
  F --> I[一次性私有管道 → Message.images]
  I --> D
  P --> A[可选独立外部 phone use / AutoGLM 循环]
  A <--> E
```

## 小爱精确版本适配

`HostCompatibility` 同时检查 versionName 与 versionCode，只接受 8.2.65.4714（508002065）和 8.3.30.4420（508003030）。通过门禁后按当前 APK 选择绑定；没有按版本前缀放行、运行时猜测混淆类或跨版本回退。下文未特别标注的旧混淆名称作为 8.2.65 绑定键保留，`Reflector` 对宿主 ClassLoader 查类时转到对应的新名称。

8.3.30 的主要绑定为 `rs.a` PromptBuilder、`dn.c` DynLlmClient、`dn.i/j/b` 三种直接客户端、`hn.k` Taiyi、`fn.a` 模型配置、`sp.j` 记忆、`ur.e0` HTTP getter、`rm1.f0.B/D` 普通文字发送／路由。新模型配置多一个 `sendTopP`，替换时保留原请求的该字段；外部连接仍使用模块独立干净客户端。新版能力分类以 `prompt_source=cloud` 请求小米服务端注入提示；改接外部服务时补充本地 `user_intent`／`required_capabilities` 分类 schema，并移除该私有字段，保留主 Agent 的工具选择与执行边界。

新版 ToolRegistry 由依赖注入管理，不再提供静态 instance、registerSystem 或 systemToolNames。模块通过实例构造与工具查询入口向实际注册表注册两个代理，并在缓存淘汰后查询时补回；重载清理已接触实例的两个工具，不修改宿主其他工具。旧版继续使用原注册方式。新版浮窗使用 `cf1.f1` / HyperPopupMenu，在原可见性处理之后、菜单复制之前替换回答模式子项；其检查数组、选择回调与资源 ID 按版本选择。全屏页面启动签名、浮窗窗口挂载和显示方法也分别绑定。

新版主聊天页为 `MainNavigatorActivity`，普通 `onNewIntent` 不再直接消费旧会话 extra。模块生成的返回 Intent 显式标记自己的导航请求，在页面初始化后调用原生 `navigateToDetail`；无话题入口调用原生主对话导航。任务运行及静默窗口内不导航，完成通知通过本任务 token 解除静默。导航数据在宿主异步消费 Intent 前捕获，页面回调由 `InteractionUi` 跟踪，重载时一并清理；首次对话定位、独立 PendingIntent 和跨工具步绑定保持不变。

Phone Use 的 `maxSteps` 可设置范围为 1–200，默认 30。对话空闲时长由独立 `conversationTimeoutMinutes` 配置管理，默认 15 分钟，范围 1–1440，0 关闭；不依赖控屏开关或模型 API 配置。宿主通过经 UID 校验的 `conversation_settings` 读取时长及控屏忙状态，不接收模型凭证或屏幕内容。全屏与浮窗共用计时，每次有效打开先按上次时间判断续用或新建，再从本次打开重新计时，即使没有输入也会续期；真实用户操作继续更新时间。回复及工具结果不延长时间。超时只在下次打开时新建，不在用户停留或任务执行期间主动换话题。

`NativeConversationHook` 在真实语音／启动器唤醒入口判断空闲；语音在宿主完成界面重置、开始识别或提交之前选择话题，桌面图标／原生快捷方式在聊天 Activity 完成恢复后应用，避免旧 PAGE 覆盖新话题。只保存原生话题身份和计时，不复制聊天正文。原生空话题复用保护阻止超时新建时，调用其完整的新会话初始化路径；全屏导航随后回放同一话题时仅同步已补齐的原生身份，不再次清理卡片管理器或重建模型会话，避免旧占位数据取消刚开始的请求并丢失回复。仅当宿主 Activity 的原生 Lifecycle 确为 `RESUMED` 时同步全屏 Navigator，防止退到后台后过渡滞后的屏幕标志将浮窗唤醒改回全屏。

浮窗沿用原生系统窗口，由 `NativeConversationRouting` 同步独立的浮窗卡片控制器、实际话题管理器与结果 View，并设置首次懒初始化读取的主页面导航数据；不能将全屏控制器的身份变化视为浮窗已切换。该同步只绑定浮窗话题与监听，不另建模型执行会话。真实触摸、按键、文字提交及最终识别结果更新计时，显式历史／通知返回优先于自动选择。控屏忙碌、配置关闭／不可读或过期时丢弃待处理导航，重载释放回调；删除所选话题会清除保存身份。2026-10-19 修复的验证范围及旧浮窗探针结论更正见 [会话生命周期记录](validation/conversation-lifecycle-2026-10-19.md)。

`NativeConversationLimitsHook` 对 `osbot.main`／`osbot.chat`／`osbot.overlayassistant` 移除原生 `IterationGuard` 的计数停止及 `beginIteration` 的普通 60／复杂 1000 轮硬门槛。原始轮次、事件和会话 ID 保持真实；超过原门槛时保留事件 channel 的挂起、失败和取消语义。实际强制禁用工具、工具 Failure、取消、时限与上下文压缩继续生效，其他专项 Agent 保留原限制。侧栏创建第 101 个历史话题的检查独立移除，不修改 DAO 计数、分页或删除记录；历史话题数量与单次请求执行轮数是两项不同限制。

## 默认官方模型控屏

`NativeSession` 在模块 UID 维护会话、最近观察、最大步数和停止状态；四分钟预算按单步进展续期，不限制持续推进任务的总时长。首次请求返回 `CONTINUE`、会话号和观察；模型可调用注册到原生 ToolRegistry 的两个函数：`miaiplus_phone_use` 前台控屏、`miaiplus_background_phone_use` 虚拟屏控屏。首次通过所选工具提交 `goal`，后续沿用同一工具提交 `session` 和 `action`；两者不受兼容入口默认屏幕开关影响；兼容入口接收 `MIAIPLUS:{session,action}`。结构化函数默认按实际 JPEG 像素接收坐标，也支持显式物理屏幕像素与0..1000；统一换算后再交给执行器。旧入口默认归一化坐标。结构化函数只在启用本机原生规划时加入模型工具列表，执行时再次校验配置。每步只执行一个数据动作，之后重新观察。对包含模块 CONTINUE 和截图 token 的 ToolResult.Success，清除直接答案标记与内部 CLI 直接答案 metadata，确保原 Agent 把中间观察回送模型；其他工具结果保持原行为。结束/接管显式关闭会话。

无外部端点配置也可工作，`AgentEngine` 在该模式下不调用 `ModelClient`。截图由 `FrameStore` 存入短期内存，以随机、一次性、调用 UID 绑定的 token 经 Provider 管道读取。`NativeModelHook` 优先在 ToolResult.Success 中附加 `ImageContent`，使原生 CLI 与消息历史复制保留图片；TOOL Message 构造 Hook 提供兼容读取。图片元数据采用实际 JPEG 尺寸，执行坐标仍以原屏归一化。截图不以 Base64 进入工具返回字符串或 Binder Bundle。

控屏动作预算在每次观察中显式报告；最后一个动作仍返回新观察，此后只允许 `finish`／`take_over`／`ask_user`，超额动作在解析坐标和注入之前终止。错误不再要求模型重开任务。`NativePhoneUseTool` 保留 `AppResult.isError()`，返回不可重试且 `terminateAgent=true` 的原生 `ToolResult.Failure`；`STOP：MiAIPlus 控屏已停止。` 与所有顶层 `PAUSE：` 都结束原生工具轮次。停止 gate 同时处理 Success／Failure 和精确兼容 CLI 入口，不从页面文字中搜索终止指令；页面变化的 `CONTINUE` 仍回送模型重新规划。

Provider 在创建任务或收起界面前校验续调的会话、UID、期限和来源工具，过期续调不会新建 `screen_task` 或覆盖已结束通知。`NativeTurnLifecycle` 将有效 CONTINUE 的会话绑定到宿主执行身份、请求 ID 和模块任务 token，核对观察会话与 Provider 当前会话一致。宿主硬轮次上限或本轮退出只清理对应的未完成控屏；Provider 再次校验 token，旧请求不能取消新任务。缺少可靠请求身份时不猜测归属，保留原 watchdog。灵动岛收尾后的短暂静默同时覆盖宿主启动和 Activity 恢复；用户主动唤醒或点击有效返回通知立即解除静默。

`PageGuard` 按动作目标检查新旧观察：点击、双击和长按比较坐标覆盖的控件、祖先与遮挡节点，输入比较唯一的已聚焦编辑框；同时校验前台应用、显示实例和几何尺寸。目标区域外的动态文字变化不使动作失效。覆盖目标的父容器仅汇总文字变化时，保留其身份、位置、状态和覆盖顺序检查，再由 `VisualRegion` 比较整个目标及周边画面；目标自身文字、资源 ID、启用或焦点变化仍拒绝。无可访问目标的纯图片区域，以及没有非空文字／描述的可点击容器，改为比较点击点附近画面，区域外的广告轮播不要求停止。明确描述为“搜索”或“暂停视频”的控件仍以稳定语义锚定，允许背后视频继续播放。

局部校验按解码后实际 JPEG 尺寸映射坐标，向四周各扩展短边的 6%（至少 32 JPEG 像素），在边缘裁剪。逐个 8×8 像素块检查 RGB 差异：每通道最大差值 16，每块平均差值不超过 3，只容忍轻微编码误差；解码失败或尺寸变化拒绝，不使用全屏平均相似度。图片只在内存解码并立即释放，不新增持久截图或模型参数。此规则识别的是两次观察之间稳定的区域，不证明其业务含义，也不重新定位已移动的目标。

目标改变时不注入输入，原会话返回 `CONTINUE` 和新观察，由模型重新规划；不自动重放旧动作，跳过步骤仍消耗步数预算。前台执行器还在主线程注入前检查一次节点；截图校验和实际输入之间仍存在时间间隔，不保证捕获所有纯像素变化。双击首击后节点目标改变则明确报告部分执行并停止，避免重试首击。无障碍树输出窗口、资源 ID、启用与焦点状态，并转义文本换行，避免文本伪装成节点。

无障碍树在 250 节点／约 24000 字符预算内按广度优先读取，待访问队列也有上限；避免一个广告子树临时展开数百节点后，把另一分支的底部导航从观察中挤掉。按 `AccessibilityNodeInfo` 的来源节点身份去重，阻止真实页面过渡时出现的祖先循环引用耗尽预算；不按文字或边界去重，以免隐藏外观相同但身份不同的遮挡节点。截图观察、后台树和前台注入前复核使用同一读取顺序。预算仍可能截断深层目标；未读到目标时继续保守拒绝，不能把不完整树当成目标未变的证据。

`ConversationReturn` 在首次工具调用时从宿主 `ExecutionContext.getContextVarIdentity()` 提取 agent/chat，以当前匹配页面或只读持久化查询构造原生 `PageNavigationData` 返回入口；旧入口从当前浮窗/全屏页面获取。定位在启动 IPC 前完成，不挤占界面就绪期限。`InteractionUi` 为每个控屏任务创建独立、不可变的通知 PendingIntent，续调与收尾保持首次对话；结束不自动切换页面，用户点击完成岛后进入对应对话。

历史默认官方模型已有截图清零任务闭环证据，但其他官方模型与传输链仍需单独确认；不支持时有无障碍树可用，但纯图片页面受限。该实现没有内置 AutoGLM 模型权重，不能把模型名称与执行器协议混为一谈。

`PhoneUseLog` 提供默认关闭的前后台控屏诊断开关，按会话／步骤／观察编号关联窗口读取、动作、输入回读、helper RPC、一次性帧和宿主附图。元数据保留 Logcat 输出，并由 `DiagnosticLogStore` 在模块私有目录异步轮转保存（最多 2 MiB）；宿主通过受 UID 校验的追加 IPC 汇总到模块，队列满或失败只影响诊断。`DiagnosticLogState` / `DiagnosticLogPage` 提供首页日志入口、筛选展开和 ZIP 保存／分享；读取／快照／清空与写入有序，压缩在写入线程之外执行。FileProvider 仅暴露专用分享缓存目录。外部请求只检查图文结构与图片摘要，不输出正文或凭证。诊断异常被隔离，不更改动作结果和重试策略。开关、收集方式及官方传输的覆盖边界见 [详细日志](phone-use-logging.md)。

## 原聊天入口的外部模型推理档位

`NativeReasoningHook` 只在 `globalModel` 开启时接管三套原生入口：`StandardEditInputView` 的模式 popup、全屏 `FunctionBarView` 的模式 popup，以及电源键唤醒等非全屏界面 `FloatStateMorePopup`（`zd1.b0`）的“＋ → 回答模式”子菜单。浮窗在原菜单可见性处理后、Miuix 复制菜单前替换子项，并按模型档位生成 `preCheckSecondaryItem` 的选中数组；其他附件／输入切换入口沿用原逻辑。原生 AUTO/QUICK/EXPERT 枚举、回调和持久化保持原状；外部选择经 UID 校验的 `set_reasoning` IPC 写入模块私有配置，以 endpoint/model/api 的摘要分别存储。写入前校验当前模型身份与有效档位，拒绝过期菜单写入。关闭外部模式时原菜单照常执行；热重载清理浮窗和弱引用的菜单配置快照。

8.3.30 的全屏入口保留原生 `FunctionModePopup`／`HyperPopupMenu`，仅在工厂同步调用期间替换档位、选中状态与保存回调。继续使用宿主原生定位、不抢键盘焦点、弹出／收回动画及配置变更清理；热重载先释放外部选择回调并收起菜单。8.2.65 的全屏入口和普通输入栏仍使用原有弹窗路径。

`ReasoningPolicy` 为已确认模型提供不同档位，未知名称只允许默认，不推测任意网关能力。独立 `ModelClient` 与原生隔离客户端均在最终线上请求阶段注入参数；Responses 用 `reasoning.effort`，Chat 使用已确认的标准或服务扩展字段。隔离客户端按协议及档位缓存，每次新建请求按当时选择取得客户端，不在协程异步发送期间读取共享线程局部状态。清除原生 enableThinking 布尔值的影响；模型默认不继承小爱原模式所产生的关闭思考参数。原 Responses 适配器同时承担 Chat 请求的思考参数注入；Chat 响应保持原样解析。

## 聊天页普通文字发送路由

实机确认 AUTO/QUICK 下的普通文字聊天会走 AIVS `Nlp.Request`，不经过 OSBot 客户端；仅替换推理档位菜单不能替换这条链路。`NativeChatRoutingHook` 在精确版本的 `ll1.f0.C()` 入口，仅在全局外部模型开启时选择原生 MiClaw 分支。原发送函数继续完成设备支持、附件、停止旧会话和卡片/历史处理，然后进入已适配的 OSBot 模型客户端。默认模式执行原判断，不改原枚举或持久化。配置读取失败在文字提交入口提示并停止，防止 Hook 异常被框架忽略后发送到旧后端。该修复覆盖聊天页文字发送，不能外推到所有语音、图片和独立 SDK 入口。

## 可选全局主模型替换

`EndpointProfiles` 在模块私有 SharedPreferences 内保存命名配置和当前配置 ID；旧单套配置首次打开作为“默认配置”，第一次保存时写入集合。每套只包含地址、Key、模型及 API 协议，新增配置使用 UUID 区分同地址的不同账号/模型。保存、切换和删除通过同一个 Editor 提交集合、当前 ID 与现有平铺运行时字段，保持 Provider 和独立循环的读取兼容。`Config` 用 `getAll()` 取得同一次快照，避免并发切换时将不同配置的地址和密钥混合。重名与非法外部配置在提交前拒绝，损坏集合不自动覆盖；推理强度继续按 endpoint/model/api 独立存储。

设置页切换未保存内容时提供保存、放弃和取消；另存新配置保留原记录，最后一套禁止删除。页面重建保留编辑内容，旧服务异步模型列表不会覆盖新配置。原生提交/模型/记忆/路由请求开始时刷新配置，不复用 UI 两秒缓存；记忆请求将本次配置快照传到同步 HTTP 构造阶段，避免并发切换后模型与协议不一致。独立循环在任务开始时固定 Config。

在 8.2.65 绑定中，`qm.c` 为 DynLlmClient，负责根据 `sm.a` 选择 Taiyi / OpenAI / Anthropic；`um.h` 是直接 Taiyi 客户端；同时接入 `qm.i`（OpenAI Chat Completions）、`qm.j`（OpenAI Responses）、`qm.b`（Anthropic）直接传输入口，覆盖绕开分发器构造客户端的调用。
开关关闭时不改推理配置；打开时为这些调用构造临时 OpenAI 配置，保留原消息列表、工具列表、systemPrompt、协程 Continuation 和原生 SSE 事件解析。使用原生 `qm.i` 作为消息/工具序列化和 Agent 事件前端，保留小爱原工具调用结果处理；它不再决定外部服务的线上协议。为外部请求单独创建干净的 Ktor/OkHttp 客户端，仅安装 JSON 序列化与超时插件，关闭重定向，不使用原应用的 ServiceToken、设备签名、账号认证与网络日志拦截器。

API Key 仅在显式启用全局替换时经 UID 校验 IPC 交给小爱进程内存，不改小爱配置文件。该范围覆盖通过上述 OSBot 入口的聊天、Agent 与辅助推理；语音识别/合成、独立系统服务、独立云端推理入口不在这个 Hook 的声明范围内。启用状态变化和工具定义更新后需重启小爱或按热重载要求重载；切换已保存的 Endpoint 无需重启。

内部干净 OpenAI 委托使用同步线程重入保护，避免其被再次重定向；保护在原生 Flow 创建后即释放，不抑制其他调用。记忆摘要另走 `ep.j.o` 的非流式请求：同样替换临时模型配置，在同步构造请求期间通过线程局部标记替换 `er.a0.getClient()`，保留原重试、隐私编码、响应解析与协程取消机制；`finally` 对应的 Hook 后置阶段恢复标记。

`ModelProtocol` 按独立的 API 配置选择 Chat Completions 或 Responses；自动模式将 `gpt-6` 前缀路由到 Responses，服务别名可显式选择。`NativeResponsesAdapter` 只安装到模块创建的独立 OkHttp 客户端，把原生 Chat 格式转换为 `/responses`：system/user/assistant 消息、input_text/input_image、扁平 function 工具、function_call 与 function_call_output。这样记忆摘要及路由/gate 的非流式请求也遵循同一选择，而不必替换各自协程、重试和解析代码。转换采用允许字段，丢弃原私有请求字段及 Chat 专用采样参数；显式 `store:false`，保留原可选参数 schema（strict:false），禁并行工具调用。

`ResponsesStream` 逐帧读取 Responses SSE，文字 delta 即时转为原生流；仅在 response.completed 且完整输出通过检查后释放工具调用及结束事件。failed、incomplete、拒绝和提前 EOF 作为失败，不释放未完成调用。ResponseBody 包装继续使用原网络读取/关闭机制，保留取消与超时。OkHttp/Okio 反射绑定是该精确小爱版本的混淆类名。

上游注释、空帧和未显示的推理／进度事件会转成固定无内容 SSE 心跳，避免持续活跃的 Responses 流被宿主 30 秒逐行空闲计时误判为断流，导致正文出现后操作按钮缺失。心跳不传递推理正文、不代表完成；完全静默、失败和不完整输出仍按原规则停止。空文字 delta 不抑制 completed 中的最终正文。

`TransportBoundary` 在整个自定义拦截器边界展开 Xposed/Java 反射与代理包装，保留原始 IOException。否则取消、断连产生的 IOException 被 Xposed 包装成 InvocationTargetError 后，会逃出 OkHttp 异步线程并杀死小爱进程。其他适配异常转为不附带请求内容的 IOException，虚拟机致命错误仍上抛；响应转换失败会关闭原 body。

独立 `ModelClient` 将原始 Responses output 附到内存历史，下轮连同工具结果回传，以保留加密 reasoning 和 assistant phase。原生历史不支持这些字段，网络适配器按服务地址/模型/完整 call_id+工具名+参数组暂存最近输出（最多 64 组、约 200 万字符），命中时回放；超限或进程重启后仅重建函数调用上下文。缓存只在进程内存在，不写盘。独立请求预留 16384 输出 token；GPT-6 原生请求至少 8192，避免沿用小额纯文本预算导致推理耗尽。未完成时仍停止。

全局模式复用原消息与 SSE 解析，但隔离网络认证；消息历史/会话缓存仍由原应用管理，与模块独立 `ModelClient` 不同。

外部 Chat／Responses 请求统一发送 `User-Agent: MiAIPlus/<version>` 和 `x-opencode-session`。独立循环以 RunState 的任务 ID 生成不含原始身份的不透明标识，多步／重试复用。原生 OpenAI 委托按非 SYSTEM 消息 ID 关联会话，重复请求和共享历史保持标识，不以提示词文字分组；最多保留 4096 个消息摘要别名，以支持部分历史裁剪，重载时清理。标识在委托实例绑定，在原生构造请求头时写入，不依赖跨协程 ThreadLocal；Chat／Responses 共用网络层保留它。记忆和 intent 辅助推理分别按原请求 ID 分组，缺少 ID 时为单次请求生成 UUID，不冒用当前前台聊天身份。因此辅助请求尚未与主对话统一分组，全部已知历史别名被裁剪／淘汰后也可能建立新会话标识；不宣称覆盖任意压缩与并发辅助推理的完整对话归属。

## OpenCode Go 接入

资料查阅日期：2026-10-18。[OpenCode Go 官方文档](https://opencode.ai/docs/go/#where-can-i-use-it) 要求客户端使用自己的 User-Agent，并为每个对话发送稳定的 `x-opencode-session`，供路由和提示词缓存使用。此次实现只增加客户端兼容，不代表已用真实订阅验收。

模块外部模型请求发送 `User-Agent: MiAIPlus/<version>`，不伪装成 OpenCode。独立任务在多次 Chat Completions／Responses 请求中复用任务标识；原生聊天按消息 ID 关联会话，同一历史内重试／续轮保持一致，不同对话中的相同提问不合并。原生记忆／路由辅助请求按各自请求 ID 标识；缺少 ID 时使用单次 UUID。它们暂未与主对话合并成同一标识；全部关联历史被压缩掉或缓存淘汰后也可能产生新标识。

当前官网列出的 OpenAI 兼容 Base URL 为 `https://opencode.ai/inference/go/openai/v1`，对应模型按官网选择 Chat Completions 或 Responses，模型名填写服务端的 Model ID。模型列表位于单独的 `https://opencode.ai/inference/go/v1/models`，与模块通用的 Base URL + `/models` 不同，目前可手动填写模型。Anthropic／Gemini 专属协议不属于模块现有两种协议的适配范围。

以上地址和要求来自当日官方文档，不保证服务端后续维持不变。测试范围与本地验证结果见 [验证记录](validation.md)。

## 独立 intent 请求

`NativeIntentHook` 仅处理路径严格为 `/osbot/api/intent/v2/chat/completions` 的 Ktor HttpStatement；全局开关关闭时原样放行。开启后复制原 JSON，更换模型、URL、认证与独立网络实例，并清除旧查询参数、认证头、计费 ID 和私有路由请求字段。

能力判断和记忆 gate 保留原 system/user 提示。intent-v2 路由把上下文转换成标准消息，要求 JSON 判断，并在原 router.t 响应解析入口把 message.content 转换回原格式。外部路由不直接分发未经注册校验的工具；统一 FALLBACK 给主 Agent，由其保留的工具链选择并执行。无有效 JSON 时保留原解析器的失败/回退行为。该适配使用原请求的超时、协程和重试机制。

## Open-AutoGLM 参考

选择 [Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM)，参考 commit `86f55382982fb054e8fc98ca80609dff8a2cdc3c`，Apache-2.0。采用截图观察→模型规划→原子动作→重新观察的循环和 0..1000 坐标、`do(...)` / `finish(...)` 动作协议，以 Android Java 和 AccessibilityService 实现本机执行器。

未打包上游 Python/ADB 运行时、模型权重或任何小爱 APK/反编译源码。普通视觉模型可用模块自定义的标准 function tool `phone use`，无需模仿 AutoGLM 文本格式。默认模式把同样的原子动作交给小爱当前主模型规划。

## 改动边界

Hook 仅安装在精确校验的小爱版本：工具入口、描述构造、PromptBuilder 路由、原生工具图片消息构造以及分发器和四个具体模型传输入口。安装失败撤销已安装 Hook；未改 APK、磁盘 osbot 提示词或系统服务。

原主模型/工具选择、无障碍支持、动态页面、模型图片能力、系统权限及原生 Agent 的回合预算都会影响结果。支付/发送等操作由模型标记、可读动作目标与系统授权分类共同触发权限策略；未知模板/纯图片仍依赖模型判断。不绕过锁屏、安全窗口、密码框、验证码。无应用白名单不等于任意页面都可成功操作。

独立外部循环只保留最新截图与有限历史；默认原生循环的历史与图片生命周期由小爱原消息管理控制。具体通过及待验证项目见 `docs/validation.md`。

## API 102 和控屏交互

现代入口为 `XiaoAiHook extends XposedModule`，元数据位于 `META-INF/xposed`。`Hooks` 对所有拦截及外部代理执行计数，重载先冻结新工作，空闲时释放 UI/广播/客户端/工具代理，新一代主动重新安装 Hook。设置进程通过 service 102 请求重载，配置 IPC 仍经过 BridgeProvider UID 检查。

`InteractionUi` 在小爱 UID 发布 HyperOS 原生焦点模板；`screen_ready` 握手成功之前执行器不触摸屏幕。保存交互入口只发生于整个控屏会话开始；多步 CONTINUE 不重置入口。全屏移动原任务到后台，快捷键系统浮窗隐藏并关闭触摸/焦点；独立观察线程确认执行已经退出才恢复全屏或发布展开的完成岛。停止请求等待实际执行退出，避免恢复页面与末尾动作竞争。详见 [迁移与专项验证](api102-migration.md)。

展开岛通过 `param_v2.actions` / `miui.focus.actions` 提供停止控制。前后台需要人工批准的敏感操作都通过该模板展示完整操作说明及“确认本次操作／停止任务”，不创建无障碍悬浮确认窗，不拉起小爱界面；普通通知展开区保留完整说明。`ActionConfirmation` 经 Provider 传递绑定 UID、screen_task 与一次性 token 的请求，60 秒未确认则停止；取消、超时、旧任务或旧按钮均不能授权后续动作，确认前后继续检查锁屏与页面目标。确认卡隐藏锁屏敏感内容，批准按钮要求解锁；若 ROM 在重新锁屏时仍递送批准广播，则拒绝此次批准并刷新按钮。需要确认但说明为空时，使用已有操作意图及可读目标标签，不把输入正文写入派生说明。该确认独立于当前应用使用授权。确认或审计期间目标变化，在原会话返回新观察，不复用旧批准。权限预设与两阶段审计见 [Phone Use 权限](phone-use-permissions.md)。Provider 用独立 `screen_task` 生命周期连接各次工具请求，宿主页面的中间更新在任务结束前保持后台。

设置页独立模型测试没有小爱 UID 的灵动岛观察器与动作接收器，因此遇到敏感提交会立即 PAUSE，提示从小爱对话发起；不会等待一张无法显示的确认卡，也不放宽跨 UID 授权。

通知观察器只接受相同 `screen_task` 的 `completed=true` 且 `active=false` 作为正常收尾确认；单步工具返回、跨步等待和重新规划均保留进行中通知。只有执行器收到明确 `finish` 后的“模型报告任务完成”结果显示完成文案；失败或取消显示“手机操作已停止／任务未完成”，接管继续使用 PAUSE 文案。

`TaskProgress` 将 action.intent 转为有界短文本；没有该字段时按动作生成说明，独立于触发确认的 message。`InteractionUi` 每 700 毫秒读取任务进度，仅在变化时更新岛右侧，使用 JSONObject 构造参数并关闭更新时的重复浮动提醒。执行指纹排除 intent，防止仅修改文案绕过重复动作检测。

可选 `AccessibilityGuard` 只在新控屏任务的准备阶段查询 Binder 连接；失效时执行有界 root helper，只重绑模块组件。后续工具调用、任务中途和空闲时没有自动修复。root 专用健康 IPC 只返回连接、开关、准备阶段、任务票据与忙碌状态，不暴露配置、树或截图；旧 daemon 的取消请求已失效。详见 [任务前检查与边界](accessibility-guard.md)。

## 后台虚拟屏

可选的第二条执行后端：控屏任务在一块独立屏幕运行，用户继续使用主屏。模型通过独立后台控屏工具显式选择此后端；前台工具固定从物理屏开始。模块私有默认屏幕开关只影响旧兼容入口与直接测试的新任务。

屏幕不能由模块进程创建。LSPosed 只把代码注入小爱进程，既不把该进程提到 system/shell 身份，也不授予平台权限；而能承载任意第三方应用的屏幕必须是受信任屏，overlay 显示（`settings put global overlay_display_devices`）产生的是非受信任、不可聚焦的屏，`am start` 会在权限检查处被拒。因此另起一个 `app_process`，以 root 身份持有屏幕，模块通过受控 IPC 使用它。

分工：`helper/DisplayHelper` 是 root 侧、独占持有屏幕的进程，只接受类型化 RPC（`<token> <verb> <args>`），唯一的子进程是固定 argv 的 `am`/`screencap`/`input`/`dumpsys`，任何输入都不会被拼成 shell 字符串。`BackgroundDisplay` 是模块侧会话，启动 helper、收发命令、读回帧。`DisplaySession` 记录一次运行的屏幕身份（逻辑 id、SurfaceFlinger token、几何、generation），generation 用于拒绝旧屏的迟到请求——系统会复用 display id。`BackgroundActions` 只在该屏上执行动作，不做全局动作、不查默认屏。

虚拟屏生产会话与能力探测的宽、高和密度来自模块私有配置（默认 1080×1920@320），随对应 RPC 传给 root helper。设置页可读取默认物理屏当前的完整像素尺寸与密度，一键填入编辑框后点击「保存」生效；参数仅用于后续新建的虚拟屏，不调整进行中的会话。

后台屏使用 `TRUSTED | OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED`：保有自己的窗口焦点以接收按屏定向的输入，同时禁止启动应用和触摸把该屏提升为系统顶层焦点。输入法设为 `HIDE` 并回读验证；后台文字仍经该屏可编辑节点的 `ACTION_SET_TEXT` 写入，不争用主屏输入法。生产建屏和能力探测共用同一初始化路径，在启动应用前完成校验；平台缺少标志、未落实焦点属性或拒绝输入法策略时停止并销毁后台屏。无相关回移植的 AOSP Android 13 不具备这两个焦点标志，只拒绝后台屏能力，模块前台功能仍支持 Android 13。

后台触摸另外要求并携带平台的 `MotionEvent.FLAG_NO_FOCUS_CHANGE`，跳过 InputDispatcher 的外部窗口点击焦点通知。点击、长按、滑动及取消事件仍绑定同一 display id；不通过事后重新弹出主屏键盘掩盖焦点丢失。设备回归使用自有测试页，检查后台编辑框实际收到点击／输入时主屏输入法持续可见、保持原编辑框服务。

此前只比较主屏 app 焦点和栈顶任务，并把 `mCurrentFocus=null` 当作上报形态，不能证明用户的键盘保留；键盘隔离需要另外检查窗口焦点、输入连接及输入法持续可见。另一个独立问题是多数应用使用 `singleTask`，后台“启动”可能搬走主屏任务，因此仍在启动前查询并保护其他屏的可见任务。

2026-10-17 按用户要求调整冲突处理：任务查询读取系统 TaskInfo 的真实组件与 task identity；首次目标就是主屏当前应用时转前台，中途则由 `ForegroundConsent` 经 Provider 暴露绑定 UID/任务/token 的请求，`InteractionUi` 仅以灵动岛按钮收集同意或拒绝。同意后重新验证主屏归属、释放后台屏并作废旧观察，下一步从新的主屏观察规划；拒绝/超时暂停并结束本轮。后台故障仍停止，不触发此交接。完整探针与准备、执行、原生跨步间隔及收尾互斥，状态查询单独持有缓存目录。

取消清理绑定具体 NativeSession/BackgroundDisplay 实例，异步等待执行退出；步骤续期同步更新 deadline 与 watchdog。后台输入定位原焦点节点并精确回读原节点值，helper 拒绝输入或不能验证 HIDE 输入法策略时停止。帧节流保留最终待复制图像，手势使用同一 downTime 与实际注入时刻。2026-10-18 的确定性设备验收已覆盖后台中文输入、主屏键盘隔离、前后台确认与正常回收；其他 ROM、未知授权模板和真实模型长任务仍未全面验证，见 [验证摘要](validation.md)。

`NativeSession` 和 `AgentEngine` 共用同一后端与 Manual／Auto／YOLO 策略。后台人工确认通过小爱灵动岛等待，批准后在原后台屏复核并继续，不切换物理屏；具体行为、错误码与已验证项见 [后台虚拟屏](background-display.md)。

## 应用启动

`AppLaunchTarget` 从可见的 MAIN/LAUNCHER 列表解析包名或唯一名称；`ScreenService` 提交标准显式 launcher Intent。`LaunchPrompt` 只匹配小米安全中心当前发起方/目标的启动确认，执行器最多点击一次“本次允许”；`LaunchVerifier` 等待稳定前台，遇可读应用选择或授权窗口则返回同会话观察，由模型选应用或进入权限策略。真正启动超时仍 PAUSE，点击确认本身不是成功依据。原生首次观察提供应用列表，控屏规划引导模型统一用 launch。详细边界与设备验证见 [应用启动](app-launch.md)。

## 统一 Plugin 层（Pluginify 分支，2026-10-28）

本分支将既有 Phone Use 的代理注册、工具定义注入与清理提取为 `NativePluginTools`。前后台 Phone Use 作为 `PhoneUsePlugin` 的两个第一方插件，与用户配置的 HTTP MCP 插件由同一 `PluginToolSpec`／`PluginSettings`／`PluginRuntime` 目录驱动；原生主 Agent、模型与历史管理仍由小爱持有。上文「两个代理」描述原 Phone Use 结构；本分支的统一注册层还按当前启用目录管理 MCP 代理。

模块进程持有 MCP 地址、认证、发现结果及有界远程任务，宿主经 UID 校验 IPC 仅获得脱敏工具目录和调用结果。工具使用稳定 namespace，配置 revision 约束发现提交与新调用准入；已接收的 MCP 调用只重验自身插件的启停、连接身份与所调用工具契约，不因 Phone Use、其他插件或本插件纯显示名称变更而取消。注册表查询复用快照，不做网络发现；配置提交前原子校验最终目录预算，超限启用拒绝写入。远程队列与控屏 `active`／`screen_task` 分离，取消绑定 UID 与请求 ID，热重载等待实际退出。远端正文不能冒充 Phone Use 帧 token 或终止语义，且不会获得 root／无障碍执行接口。

统一层只按对象身份移除本代创建的工具定义，保留来源不明的宿主缓存副本，避免跨 DI 注册表误删；旧定义可能在当前模型请求中继续显示，停用后旧代理仍重验拒绝执行。两版原生 `Tool.onCancel` 尚无可靠调用归属，MCP 保持 `isStoppable=false`，不保证小爱原生停止按钮取消远端。插件页提供模块本地「停止 MCP 调用」，只取消远程请求；线程中断、超时与自身配置失效也可中止本地等待。取消不回滚远端副作用，worker 实际退出前继续阻止重载。上述为源码及本地元数据核查边界，非设备验收结论。

Phone Use 继续沿用原 `controlApp`、会话来源、确认、PageGuard、截图、后台交接与原对话返回路径；内置插件开关也在兼容入口和直接测试入口校验。旧控屏总开关仍只约束 Phone Use，MCP 不依赖外部模型配置或开启控屏。

首轮远程适配为 MCP `2025-11-25` 的 tools-only Streamable HTTP 子集；后续增加 `2025-06-18` 显式兼容选项，默认保持前者。初始化响应必须严格匹配插件所选版本，不自动降级；改变协议身份会清除该插件旧目录。两版均只支持已实现的初始化、分页工具发现、JSON／SSE 和文本／结构化结果，不包含 stdio、OAuth、新协议形态或任意代码加载。

2026-10-28 独立包在小爱 8.3.30.4420 实测通过统一工具注册、受控合成 MCP 服务调用，以及前台／空后台屏的截图、同会话续调与结束，详见 [独立包实机验证](validation/plugin-platform-device-2026-10-28.md)。随后固定上游 `termuxgpt/termux-mcp` 服务由真实 Termux UID 运行，模块 Provider 完成 shell 环境、中文文件管道及失败语义验证；小爱当前模块工具代理另行执行固定身份命令，详见 [Termux 实机验证](validation/termux-mcp-2026-10-28.md)。服务代码不打包进模块，测试独立 HOME 仅隔离状态，不限制 Termux UID 的 shell 能力；模块对 MCP `isError` 返回脱敏失败，不透传错误正文。这些结果不代表任意生产 MCP、真实模型自主选择、复杂应用任务或其他小爱版本已经验收。架构、操作步骤、能力限制与后续适配原则见 [Plugin 平台](plugin-platform.md) 和 [MCP 调研](plugin-platform-mcp-research.md)。
