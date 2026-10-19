# 后端性能审计与 Agent 交接

日期：2026-10-19。审计基线：`418267f87e3909fd3b526c51c525bad86b895260`。开始时 `git status --short` 为空。

后续同日新增 [Phone Use 性能专项](../phone-use-performance-2026-10-19/README.md)：重新按端到端关键路径排序，并深化树读取、完成轮询、后台快照、应用标签与请求负载；做 Phone Use 优化时优先读该专项。

本轮由 13 个模块 Subagent 与 1 个独立交叉复核 Subagent 完成静态审计。**只新增审计文档和索引，未修改后端代码、配置、测试或构建脚本；未运行构建、测试、mock、设备操作或真实模型请求。** 本文中的成本是源码可见工作量，收益均未测量；“低风险”表示改动容易保持语义，不代表已经证明无回归。既有验证以 [validation.md](../../validation.md) 为准，历史测试数不能当成本轮结果。

最适合先验证的是：让无配置需求的高频 IPC 跳过完整 Config 构造、按需分配 Hook extras、避免无用的 Responses 序列化与 Intent 重复解析。截图主线程编码、后台 RPC 等待和帧流水线可能更有价值，但涉及资源与取消生命周期，需要先定位再实施。

## 给接手 Session 的阅读路线

1. 先执行 `git status --short`，读 [AGENTS.md](../../../AGENTS.md)、[架构](../../architecture.md)、[验证边界](../../validation.md)。本报告是上述提交的快照；代码变动后按函数名重新定位，不能盲用旧行号。
2. 读本页的分批候选、否决项、测量计划，再读 [独立交叉复核](14-crosscut-review.md)。不需要先重读全部源码。
3. 第一项建议只做 A01；然后按数据挑选后续项。每次只改一个成本来源，保留小 diff 和可独立回退范围。用户当前请求只有审计；本文不授权实施、运行设备、提交或发布。
4. 按目标模块打开下面的详细报告。报告提供准确源码锚点、调用链、相关测试、缺口及方案风险。跨报告重复线索以本页 A/B 编号归并，避免重复设计缓存或两次计算收益。

## 完整范围与报告导航

后端范围为 `app/src/main/java` 中全部 **58 个 Java 文件（9,915 行）**，以及三个 Kotlin 状态/数据层文件 **580 行**；另审阅全部 7 个 Python 支持脚本、生产 preflight shell、Gradle/ProGuard/Manifest 和相关服务配置。测试按受影响行为读取，不声称全部测试/runner 源码逐行审计。

纯前端 `MainActivity.kt`、`DiagnosticLogPage.kt`、`SettingsDropdown.kt`、`SettingsIcons.kt` 不做 Compose 绘制审计；涉及后端的调用点和日志刷新已核对。第三方库、反编译材料、构建产物、`work/`、`recon/`、`.tools/` 不作为自研生产后端纳入。不存在对未列出的二进制/第三方实现的全面性能保证。

下表名称均相对于 `app/src/main/java/io/github/miaiplus/`，子目录已注明；同一文件跨域阅读只计一次覆盖。

| 报告 | 全文覆盖的生产代码 | 主要入口 |
| --- | --- | --- |
| [01 网络与协议](01-network-protocol.md) | ModelClient.java、ModelProtocol.java、ResponsesStream.java、ModelHeaders.java、TransportBoundary.java、Endpoint.java、ReasoningPolicy.java | 请求转换、SSE、推理档位 |
| [02 反射与重载](02-reflection-reload.md) | hook/Reflector.java、hook/Hooks.java、hook/HookCallback.java、XiaoAiHook.java、HostCompatibility.java、HotReload.java、ModuleApplication.java | 公共 Hook 分配、成员解析、freeze/dispose |
| [03 截图与前台执行](03-screen-capture.md) | ScreenService.java、FrameStore.java、MainThread.java、AppLaunchTarget.java、LaunchPrompt.java、LaunchVerifier.java | 截图编码、树读取、启动、输入回读 |
| [04 IPC 与配置](04-bridge-config.md) | BridgeProvider.java、Config.java、EndpointProfiles.java、NativePhoneUseTool.java、NativeTurnLifecycle.java | 高频状态 IPC、工具注册、配置快照 |
| [05 模型 Hook](05-model-hooks.md) | NativeModelHook.java、NativeResponsesAdapter.java、NativeIntentHook.java、NativeChatRoutingHook.java、IntentProtocol.java、PhoneUseRouting.java | 传输隔离、回放缓存、JSON 转换 |
| [06 交互桥接](06-interaction-ui.md) | InteractionUi.java | 通知观察器、确认、返回和收尾 |
| [07 后台客户端](07-background-client.md) | BackgroundDisplay.java、BackgroundActions.java、DisplaySession.java | helper RPC、输入复核、实例清理 |
| [08 Root helper](08-display-helper.md) | helper/DisplayHelper.java 全部 1,655 行 | 帧流水线、TaskInfo、焦点、固定 argv 子进程 |
| [09 对话生命周期](09-conversation-lifecycle.md) | NativeConversationHook.java、NativeConversationLimitsHook.java、ConversationReturn.java、ConversationPolicy.java、NativeReasoningHook.java | 元数据持久化、菜单、唤醒导航 |
| [10 诊断](10-diagnostics.md) | PhoneUseLog.java、DiagnosticLogStore.java、DiagnosticLogState.kt | 关闭/开启开销、摘要、轮转、快照 |
| [11 支持设施](11-support-infrastructure.md) | SettingsState.kt、VirtualScreenState.kt；7 个 scripts/*.py；accessibility-preflight.sh；构建/Manifest/服务配置 | 设置 IO、开发脚本、R8/事件订阅实验 |
| [12 页面与权限](12-page-safety.md) | PageGuard.java、VisualRegion.java、PhoneUsePermissionPolicy.java、SystemDialogPolicy.java、ActionPermissionReview.java、ActionConfirmation.java、ForegroundConsent.java、AccessibilityGuard.java | 树/像素派生数据、安全规则和等待 |
| [13 会话与执行循环](13-session-engine.md) | NativeSession.java、AgentEngine.java、RunState.java、Action.java、Coordinates.java、PhoneUseResult.java、TaskProgress.java | 单步循环、固定等待、指纹、取消/超时 |
| [14 独立复核](14-crosscut-review.md) | 对主要候选再次追踪调用方、测试与反例 | 修正热点范围、风险排序和否决依据 |

## A：先做小范围、易保持语义的候选

排序综合调用频率、修改范围与稳定性风险，并非实测收益排名。不要求全部实施；没有可辨别收益且使代码更复杂的改动应放弃。

| 编号 | 源码证据与成本 | 最小切口 | 验收重点 / 详细报告 |
| --- | --- | --- | --- |
| A01 | BridgeProvider.java:80 在 screen_state/poll 等不使用配置的分支前构造 Config；Config.java:31、43–45 做 getAll、协议/档位识别和摘要。任务观察器 700ms、兼容工具 poll 400ms，设置测试 poll 500ms | 仅在使用配置的分支创建 Config；保持分支副作用顺序、UID/owner gate 和每次配置单一快照，不新增全局缓存 | 高频 IPC 的 Config 次数/分配下降；配置热切换、旧任务、取消和隐私行为不变。[04 BC-1](04-bridge-config.md)、[14](14-crosscut-review.md) |
| A02 | hook/HookCallback.java:22 每次 Hook 都创建空 HashMap；输入事件 Hook 也先经该公共层 | extras 首次 set 才分配，未设置 get 返回 null；不池化参数对象 | null key/value、before/after、嵌套/并发隔离。少的是一个空 Map，不是其尚未分配的 table。[02 R1](02-reflection-reload.md) |
| A03 | NativeResponsesAdapter.java:164 在 replay key 为空之前已 output.toString，纯文本/纯 reasoning 结果序列化后丢弃 | key 为空先返回，其余缓存与锁策略不变 | 完成结果、optional replay、容量/计数保持；纯文本分配下降。[05 候选 1](05-model-hooks.md) |
| A04 | IntentProtocol.java:10、12 与 NativeIntentHook.java:46 对同一请求 body 合计三次完整 JSON 解析 | 请求局部解析一次，复用转换与 rid 读取；保持现有公开 API | 私有字段/认证净化、v2/cloud 优先级、失败不部分改写宿主请求。[05 候选 2](05-model-hooks.md) |
| A05 | ReasoningPolicy.java:64–70 用 32 次 String.format 输出 SHA-256；apply:74–93 重复能力识别 | 固定字符表输出相同小写 hex；能力结果只在本调用内复用，两个改动可分开 | 旧偏好 key 黄金值完全一致；未知模型、日期/namespace、Chat/Responses 规则不变。[01 N02/N03](01-network-protocol.md) |
| A06 | hook/Reflector.java:106、112 单次 newInstance 枚举构造器两遍 | 函数局部保存一次数组，两轮选择仍照旧 | 歧义、null、primitive、继承/异常行为；不引入 ClassLoader 缓存。[02 R2](02-reflection-reload.md) |
| A07 | ScreenService.java:641–643、679–687 在同一同步注入闭包对同一新鲜 root 分别 dump 两次 | 同闭包构造一次 live tree，交给系统授权检查与目标检查 | 保留前置 gate、最终注入复核、双击第二击重新采样、锁屏/旋转/遮挡拒绝。[03 候选 2](03-screen-capture.md) |
| A08 | ScreenService.java:563–578 启动轮询每250ms对普通应用也 dump；SystemDialogPolicy.java:67–80 对普通包可直接分类 | 先按新鲜 package 分类，仅相关系统候选包读树，复用本次 permission title | 500ms稳定期/10s上限、单次启动/允许、chooser和未知授权路径保持。[03 候选 3](03-screen-capture.md) |
| A09 | PhoneUseLog.java:110–112 模块同事件序列化两次；frame:139–152 对同图摘要两次 | 各自只在当前调用内复用字符串/digest | 仅详细日志开启时受益；宿主 Logcat 与存储字段差异、32项上限、隐私/Trace保持。[10 候选一/二](10-diagnostics.md) |
| A10 | PhoneUsePermissionPolicy 固定词表/集合/金额 Pattern，SystemDialogPolicy 循环内 lowercase | 固定常量静态化、预编译 Pattern、单次 lowercase | matches 仍是完整匹配，精确模板与“未知授权”分支不变；收益小。[12 候选 1](12-page-safety.md) |
| A11 | ResponsesStream.java:59/67/100 重复心跳编码，82–95累计字符串拼接与本地新工具对象JSON往返 | 复用私有常量 bytes；completed 用 builder；仅修改本次拥有的对象 | 心跳及时性、offset、完整校验后才发工具、DONE/usage/文本不重复。[01 N04/N05](01-network-protocol.md) |
| A12 | NativeModelHook.java:98–102无目标key仍复制metadata；217–226重复phone分类；NativePhoneUseTool.java:154–162定义齐全仍复制列表 | 仅确实需改内容时复制，一次严格分类/扫描 | 不原地改宿主容器；保持CONTINUE、observe→STOP顺序、工具定义顺序。[05 候选3/4](05-model-hooks.md)、[04 BC-3](04-bridge-config.md) |

A07/A08 也涉及安全分类，需专门反例测试，风险高于 A01/A02/A03；不能为了打包“首批优化”而扩大成统一重写树读取或授权策略。

## B：先 profile，再决定是否值得改架构或增加状态

| 编号 | 候选与证据 | 最安全起点及风险 | 详细报告 |
| --- | --- | --- | --- |
| B01 | ScreenService.java:380–388 截图 callback 明确使用主线程，并做 hardware→ARGB、缩放、JPEG80、Base64 | 潜在价值高、**中风险**。受控有界编码 executor，不能用正在 f.get 的同一单线程队列；处理取消、5s超时、迟到callback、拒绝/解绑及所有Buffer/Bitmap释放 | [03 候选1](03-screen-capture.md)、[14](14-crosscut-review.md) |
| B02 | 同次 PageGuard.java:56/61可能两次解码；NativeSession.java:138/152/197在Auto续调可能三次检查同Frame对 | 先复用步骤内只读树解析，再考虑局部懒解码；每种ROI仍按原规则检查。action公开可变，须快照。人工确认后重新observe不命中旧上下文 | [12 候选3/4](12-page-safety.md)、[13 S2](13-session-engine.md) |
| B03 | VisualRegion.java:30–46每8×8块两次getPixels，数组已仅64个int | 测稳定/首块变/末块变；若JNI成本显著，按8行条带读取，保持分块起点/裁剪/阈值和早拒绝 | [12 候选5](12-page-safety.md) |
| B04 | BackgroundDisplay.java:201–216串行RPC以20ms ready轮询；常规NativeSession后台tap通常5RPC | 每helper一个stdout reader＋token路由＋有界可取消等待；保留deadline/EOF/迟到token/closed，不改成调用线程裸readLine。覆盖半行挂起及close竞争 | [07 BGC-01与计数表](07-background-client.md) |
| B05 | display与focus复核分别往返；helper部分焦点字段重复dumpsys，TaskInfo字段反射重复查找 | 同一安全时点可组合只读状态；只缓存Class绑定的反射元数据，不缓存结果。mainState双window dump主要属于完整probe，不能算每个生产步收益 | [07 BGC-02](07-background-client.md)、[08 H1/H2](08-display-helper.md) |
| B06 | DisplayHelper.java:318启动reader后，320仍同步解析SF token；1536–1545有重试dump | 测正常reader建屏耗时后，考虑仅回退前按本显示身份解析；保留空屏screencap回退、错误语义和同名/复用id隔离 | [08 H3](08-display-helper.md) |
| B07 | helper帧drain无帧时sleep8ms，首帧等待20ms轮询 | 帧通知＋到期flush，独立变更；最终pending帧200ms后仍须复制，观察主动flush、持续drain防堵、销毁释放必须保持 | [08 H4](08-display-helper.md) |
| B08 | helper反复完整Bitmap copy/padding/crop；FrameStore.java:40–47再次Base64解码与大JSON序列化 | 先分摊native/Java分配；Bitmap复用、Frame表示、token延迟序列化分开实验。不能长期双存String/byte[]或丢JPEG实际尺寸校验 | [08 H5](08-display-helper.md)、[03 候选5](03-screen-capture.md)、[07 BGC-03](07-background-client.md) |
| B09 | NativeConversationHook.java:117–128、444–490对不变身份重复getter/JSON/Editor.apply，capture还可能重复save | 按完整身份值去重，再拆时间戳与身份序列化；不节流最后用户活动，不仅比较引用/topic id | [09 C1](09-conversation-lifecycle.md) |
| B10 | PageGuard/权限策略重复树解析，SystemDialogPolicy重复permissionGroup与精确hasText扫描 | 单次(action快照,Frame)短命PolicyFacts；不把两种语义不同的解析器全部合并 | [12 候选2](12-page-safety.md) |
| B11 | Responses replay全局synchronized内做历史扫描/JSON转换 | A03后先测锁等待；锁内仅map/计数/淘汰，锁外转换须核对clear/在途/代际顺序 | [05 候选5](05-model-hooks.md) |
| B12 | 同请求addDefinition查flags，随后requestConfig又查model_config；现代registry每种查询又register两个工具 | 两个独立设计。请求局部新鲜配置快照可合并IPC；registry去重必须理解evict/DI多实例/reload，不可只记“注册过” | [05 候选6](05-model-hooks.md)、[04 BC-2](04-bridge-config.md) |
| B13 | NativeSession.java:224/AgentEngine.java:181所有非终止分支固定800ms，含未注入的审计挑战/目标拒绝 | 先测分支频率；只研究有确证无输入的分支。真实动作/首次收起/交接继续保留；ObservationChanged可能含部分执行，不能批量删等待 | [13 S1](13-session-engine.md) |
| B14 | 日志页可见时每2s全量snapshot/解析最多约2MiB；宿主日志开关失败可能连续同步IPC | 两个独立变更：版本token覆盖append/clear/dropped/errors；开关fail-closed冷却/非阻塞single-flight，防旧true或迟到回复复活 | [10 候选三/四](10-diagnostics.md) |
| B15 | InteractionUi发布时反射/模板JSON/渠道注册；worker每700ms仍post状态，主线程才去重 | 先测真实publish；局部绑定成员/仅缓存成功建渠道；若真积压再设计单槽mailbox，不能吞确认、失败重试、锁屏重发 | [06 IU-1/2/3](06-interaction-ui.md) |
| B16 | PM枚举/loadLabel在前台执行器主线程；SettingsState保存和reasoning菜单同步commit/IPC | PM先无缓存移到worker；设置写入串行commit成功再更新UI。保留新鲜启动校验、旧菜单身份、失败结果；不能直接commit→apply | [03 候选4](03-screen-capture.md)、[11 S1](11-support-infrastructure.md)、[09 C2](09-conversation-lifecycle.md) |
| B17 | 固定后端keep规则禁止R8方法体优化；无障碍typeAllMask而应用callback为空 | **兼容实验，后置**。逐类证明ABI或先测空闲事件成本；框架缓存可能依赖事件，不允许全局松绑/删除事件 | [11 S2/S3](11-support-infrastructure.md) |
| B18 | ResponsesStream逐字符BufferedReader方法调用；ModelHeaders遍历历史；执行循环将动作指纹与整树拼接；确认100ms轮询等 | 仅profile指向时处理。SSE已有缓冲不是逐字网络I/O；指纹保持完整相等/深复制；等待保持取消/到期；不要堆积微优化 | [01 N06–N10](01-network-protocol.md)、[13 S3](13-session-engine.md)、[12 候选6](12-page-safety.md) |

其余低优先项及已有优化见模块报告：FrameStore过期条目惰性释放、watchdog取消节点、pipe专用有界IO、日志有序批写、MainThread Handler局部复用、LaunchPrompt正则等。规模和调用频率有限，未证明收益前不增加常驻线程、复杂缓存或存储迁移。

## 正确性澄清，不能包装成性能快修

**Q01：后台正常退出握手。** BackgroundDisplay.java:355先设closed，再357发送destroy；requestLocked:195会拒绝该请求。已证显式destroy未发出。但关闭stdin后helper EOF会destroy，client也有进程回收，**不能断言必泄漏**。若改传输/取消，先明确closing/closed和独立有界teardown握手；分别验证正常ACK/退出与强制回收、旧会话/新会话隔离，不能仅删3秒等待。[07 BGC-05](07-background-client.md)、[08 退出语义](08-display-helper.md)。

**Q02：外部循环时限。** RunState默认240000ms；NativeSession按步extend，AgentEngine循环不extend。是否意图为外部总任务预算需先确认。改变续期是功能语义，不是性能优化；profile应区分deadline停止、maxSteps停止与正常完成，避免把提前终止当吞吐提升。[13 超时问题](13-session-engine.md)。

**Q03：回放缓存身份。** NativeResponsesAdapter.java:62的replayIdentity是URL＋模型，再进入149–159的工具调用组key；不是单独model，也不以API key/账号作键。clear仅随dispose调用。锁优化必须保持现有准确字段；账号/凭证切换隔离如需变化，单独澄清契约，不能顺带改变不透明reasoning生命周期。[05 候选5](05-model-hooks.md)。

## 已复核并否决的捷径

- **大ROI通过不能代替小ROI校验。** VisualRegion的8×8块起点随ROI变化，块均值不具区域包含单调性。可复用解码资源，两个ROI仍各自验证。相同Base64本来就有快路径；ScreenService末次复核复用planned.image，不应计为又一次JPEG解码成本。
- **不能减少新鲜观察和晚期复核。** 模型规划前、推理/批准后、实际注入前、双击第二击前、后台交接批准后处于不同安全时点；不能以调用相同函数为由合并。type/home较晚的第二次focus不能直接删掉。
- **不能把launcher resumed gate前移来省配置IPC。** NativeConversationPendingTest明确要求设置关闭、busy、不可读时立即丢弃pending；延后读取会重放失效导航。
- **不能拉长配置TTL替代重复读取整理。** 下一请求endpoint/key/model/api切换、新菜单身份、官方/外部路由和fail-closed必须保持。
- **不能把日志关闭时scope/bind变no-op。** 现有测试要求任务中途开启仍可关联。失败冷却必须原子发布false与时间，不能让旧true在缓存期复活。
- **不能取消限额、深复制、同步与生命周期。** UID/token/来源工具、generation、完整动作相等、候选深复制、Hooks.freeze原子门禁、busy计数、响应body跟踪、有界队列/历史、超时和资源释放均有用途。
- **不以牺牲安全能力换指标。** 不降低JPEG/树预算/像素比较质量，不删密码/锁屏/安全窗/CAPTCHA检查，不放宽未知授权，不让后台故障回落物理屏，不缩短真实手势/稳定性等待。

热点范围也须准确：通知不是每700ms重建；availability目前是手动按钮，不是已证定时启动root；helper mainState多dump主要是能力probe；SharedPreferences.getAll不等于每次磁盘读取；HttpURLConnection.openConnection不证明每次新建TCP/TLS；有synchronized不证明已发生争用。

## 测量、回归与完成标准

### 先建立可归因的基线

保持同源码/相同优化构建类型、设备/ROM/宿主版本、画布、页面内容与模型/mock脚本，记录构建身份、轮次和失败原因。不能比较Debug与Preview后将差异归因于某个源码改动。先用fixture/mock隔离本地开销，再独立评估真实模型端到端，不将mock成功冒充模型任务质量。

优先复用已有默认关闭的 `helper_reply.durationMs`、`capture_complete`、session/step/observation、frames/source；它们粒度有限，不足以单独证明JPEG、JSON或锁耗时。必要的开发期阶段计时只记录数值与枚举，不输出页面/输入/正文/凭证/原token/图片。正式测量要比较日志关闭与开启，避免新增诊断开销主导结果。

| 维度 | 最小场景 | 记录指标 |
| --- | --- | --- |
| IPC/分配 | 任务活跃、跨步等待、结束；配置热切换；Provider不可用 | 调用/Config次数、分配字节、CPU、Binder时长与payload、p50/p95 |
| 前台观察 | 静态文本/照片/动态页面；横竖屏、1080p/1440p | 树/copy/scale/JPEG/Base64分段、主线程最长slice、观察总耗时、峰值Java/native heap |
| 页面保护 | labeled/匿名容器/canvas、祖先文字改变、ROI边缘/分块偏移、Auto续调 | parse/decode/getPixels次数、拒绝结果等价、动作次数/目标完全一致 |
| 后台 | 建屏/空屏/静态/动画、默认与大画布、任务冲突/交接、helper死亡 | RPC分项RTT、dumpsys次数、线程唤醒、frame age、PSS/内存、主屏IME与焦点 |
| 取消/回收 | 任意等待取消、半行RPC、迟到截图/回复、快速旧会话结束→新会话 | 停止延迟、正常/强制退出分别统计、无残留显示/FD/线程、旧状态不影响新任务 |
| 诊断/设置 | 日志关/开×页面关/开×空/近2MiB；连续保存/重建/写失败 | writer队列/丢弃、快照/解析次数、主线程等待、配置成功边界 |

### 按变更范围验收

每项详细报告已经给出相关测试类与新增行为反例。纯分配改动只补必要语义测试；涉及线程/资源/取消的改动必须补超时、迟到、关闭竞争，不以“实现调用次数正确”代替行为正确。JVM/Shadow能证明部分状态逻辑，不能证明真实ROM焦点、动画、硬件缓冲或主线程性能。

在未来获得实施授权并确实改代码后，遵循仓库工具链运行适当检查；仓库完整Debug检查为：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

影响两变体、ProGuard、发布ABI或helper时，还按仓库要求覆盖Release Lint/构建、AndroidTest和优化Preview；设备、root helper、mock server、安装/重启等必须有当时的授权并避免与其他Agent竞争。本审计没有执行以上命令。

接受改动的条件是：行为/安全结果不回退；失败和取消路径可控；收益在相同条件下可重复且大于测量噪声；性能没有从主线程转成更大的内存/排队/停止延迟。若收益仅微小且状态复杂度上升，保留现实现。提交或发布仅在用户要求时进行。

### 建议下一轮只交付的内容

先完成A01的单独补丁、所需行为验证、前后分配/IPC测量及剩余边界；确认值得后再选择A02/A03/A04。如用户更关注界面响应，再优先定位B01；更关注后台吞吐/功耗，再定位B04/B07。不要把30余条候选一次打包实现，尤其不同时改变截图线程、Frame表示与帧消费架构。

本轮仅执行工作区/源码/文档静态读取与文档差异、覆盖、链接检查；检查结果随本次交付说明，不新增任何功能或性能通过记录到validation.md。

交付前静态检查：58/58 个生产 Java 文件均在覆盖清单；15 份审计文档和文档索引的 141 个本地链接均存在，显式 `#L` 行号未越界；所有新增文档无尾随空白；`git diff --check` 通过。工作区差异仅为 `docs/README.md` 索引及本审计目录，未暂存、提交或推送。链接/覆盖检查证明文档可定位，不证明候选提速或行为正确。
