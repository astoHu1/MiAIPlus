# 候选、最小切口与稳定性边界

返回[总览](README.md)。源码锚点相对 `app/src/main/java/io/github/miaiplus/`，适用基线见总览；这里没有已实现或已验证的优化。先读[关键路径](critical-path.md)，再按编号挑单项实验。

## P01：高频无配置 IPC 延迟构造 Config

BridgeProvider:80 在 `poll`、`screen_state`、`screen_ready`、取消等分支前创建 Config；Config:31,43–45 包含偏好快照、协议/推理能力识别与摘要。SharedPreferences 自带内存缓存，不能称为每次磁盘读取。

最小改动是只在真正使用配置的分支构造本次快照。保留 UID/owner gate、分支副作用顺序和任务锁；不加全局缓存、不拉长 TTL。量每种 IPC 的 Config 次数、分配与 Binder p95，回归实时开关/endpoint/key/model/API切换和失败关闭。详见前轮[IPC报告](../backend-performance-2026-10-19/04-bridge-config.md)。

## P02：省掉策略不会使用的树 dump

ScreenService:665–672 `verifySystemDialog` 无条件 dump；SystemDialogPolicy:90 对 back/home 直接返回，:18–25 对普通 owner 无须树即可判 false。可将“可能授权 owner”判断集中在策略类，只有 planned/current **都**可确定不属于授权候选（包括特殊 SecurityCenter）才跳过构树，避免复制两份包名规则。

ScreenService:479–488 的 `verifyActionTarget` 对 swipe 仍 dump，而 PageGuard:38–39 在 sameScreen 后即返回。可保留新 root/前台/几何构造及 sameScreen，只省该动作不会消费的树。

普通应用 tap/type 的**执行阶段**目前有初始 dialog、末次 dialog、末次 target 三次 dump，候选负向短路后可降一次；swipe 可降零。这个口径不含执行前后观察的树，也不代表删除任何新鲜观察。需要树的授权页面仍可在同一主线程闭包内共享一次 live tree（ScreenService:641–643,679–687），但不能跨初始gate与末次注入闭包复用旧root。

验证重点：全部授权包、SecurityCenter普通/启动/权限页、普通页→授权页变化、旋转、密码/disabled/遮挡、双击第二击变化。记录 dump入口/实际节点/主线程CPU；不要将“零dump”推广到整步或后台。

## P03：相同树只解析一次

PageGuard:40–41 无条件解析两棵树；完全相同的字符串可复用不可变 Node 列表，继续执行所有焦点、password、disabled、遮挡和视觉检查。**不是树相同就通过**。这比跨函数 PolicyFacts 缓存更局部；命中率待测。

以旧新算法同输入差分验证，特别覆盖同帧仍拒绝的 disabled/password/多焦点，以及相同匿名树但图片改变。不能删除 Coordinates 的深复制来凑分配收益，Action.data 的可变所有权仍有用途。

## P04：工具完成发现从轮询走向有界唤醒

XiaoAiHook:135–147 的400ms poll直接阻塞工具返回。先量 Provider终态可续调→宿主消费的残余延迟/IPC次数，再研究任务绑定、owner检查的完成等待或通知触发poll，保留丢信号回退。

前提是[完成发布顺序](critical-path.md#结果更快发布之前要先处理完成顺序)。还须覆盖 start前后注册、注册前完成、即时续调、旧任务迟到、error/cancel、线程中断、模块死亡和245秒截止。禁止持 JOBS 锁等待；有界long-poll也要评估Binder线程占用，不能用无界等待把轮询成本转成线程耗尽。

## P05：物理截图编码移出主线程并处理无效请求

ScreenService:380–388 指定主executor，执行 hardware→完整ARGB copy、scale、JPEG80、Base64（编码函数:418–430）。1440×3200单张ARGB副本约18.4MB，另有缩放和编码缓冲；这是工作量估算，不是实测峰值/卡顿。

最小实验保留Frame接口、图像参数和树/输入的主线程顺序，使用服务代际内独立有界编码executor，不能使用正在 `f.get` 的同一单线程队列。:396–397 等future最多5秒且无取消绑定，取消/超时后迟到回调仍可能做昂贵工作；线程迁移必须一起明确请求级取消、迟到拒绝、队列拒绝、解绑和HardwareBuffer/所有Bitmap所有权。编码不一定能安全中断，至少避免尚未开始的昂贵阶段。

性能主目标先是主线程最长slice/峰值分配和取消响应，不保证总观察时长一定下降。保留350ms限频、安全窗拒绝和真实JPEG尺寸。不要先加Bitmap池或GPU改写。

## P06：后台直接生成独立的最终缩放快照

DisplayHelper:789–801 `awaitFrameCopy` 克隆全尺寸Bitmap，:1059–1068 capture随即缩至1280长边并回收克隆。可为生产capture增加专用snapshot：持FRAME_LOCK主动flush pending，从frameLatest生成独立的最终缩放图；不需缩放时仍copy，绝不返回共享frameLatest。原始display几何/source保留，JPEG留在锁外，probe digest保持原分辨率。

默认1080×1920可少一张约7.91MiB全尺寸临时clone，**不是峰值下降保证**。缩放可能比copy持锁更久，先比frame drain锁等待、frame age、capture p95和native峰值。补非方形多色图、row padding、1280边界、无缩放、停止重建/并发测试；现有1×1 fixture不足以证明缩放行为。

## P07：只研究已证无输入分支的800ms

NativeSession:161–164 创建审计挑战、:138–143 审计目标过期、:204 目标拒绝都经过:224的800ms，再:230新观察。先量分支频率；优先第一种完全未发动作的挑战。保留末尾新观察、取消和步数/审计规则。

真实动作、首次UI收起、用户确认恢复、交接继续保留；`ObservationChanged` 可能涉及部分执行，不可按异常类型批量删等待。AgentEngine:158–161 的拒绝也需单独证明。现有pause shadow能测控制流，不能证明真实动画稳定。

## P08：PageGuard 单次调用复用派生资源

PageGuard:56,61,75–76 对同一图像对可能校验两个ROI；VisualRegion:18–19,57–63重复Base64/JPEG解码。先采用调用局部懒解码，结束finally recycle，再考虑Auto步骤内上下文（NativeSession:138,152,197；ActionPermissionReview:53）。动作须固定快照，人工确认后重新observe不可命中旧上下文。

各ROI仍独立检查，保留先拒绝顺序。VisualRegion:31–45当前逐8×8块两图getPixels调用为 `2×ceil(W/8)×ceil(H/8)`；8行条带可降调用次数，但增加预读/数组。固定宽分段可限制内存。保持块起点、边缘面积和阈值，覆盖首块/末块变化与非8倍数尺寸。相同Base64本来就短路；末次节点复核复用planned.image，不可计成又一次JPEG解码收益。

## P09：后台RPC、帧等待与状态采样

- BackgroundDisplay:194–218 每RPC 20ms `ready()` 轮询。每helper独立reader＋token路由＋有界取消等待值得测；`ready`仅说明有字符，接着readLine遇半行仍可能挂起。需半行/EOF/错token/迟到/超时/close竞争测试。
- close:355先设closed，:357 destroy被requestLocked:195拒绝；明确握手未发送，但EOF/进程回收仍在，不能称必泄漏。reader改造须先定义closing/closed和实例隔离，不能删3秒回收上界。
- BackgroundActions:69–76,126–137 同点display/focus可组合，最多省1 RPC，不省dumpsys；type/home较晚focus、双击第二击、确认后任务检查不删。
- DisplayHelper:1462,1490同采样重复window dump可复用输出；:937–980只缓存Class绑定反射成员，不缓存TaskInfo结果。稳定双样本与最新任务归属复核保持。
- :675–694的8ms drain与:789–808首帧20ms轮询可研究事件＋到期flush；必须持续drain、防堵、保留最终pending、观察主动flush和销毁释放。
- :318–320同步SF token查询可考虑仅回退前解析；显示身份/同名/复用id复杂，后置。`task_state`省掉主屏稳定查询可能改变冲突交接时序，不能列低风险项。

## P10：先确认原生历史图片负载

NativeModelHook:54–103附图不负责裁宿主历史；AgentEngine:85–98已只留最新图和六组文本历史。原生旧图是否每轮发送是**待证问题**。对受控外部主模型量第1/5/10/20步images、digest/messageIndex、body bytes、input tokens；官方附图日志不能证明线上请求内容。

若确认增长，再评估仅在线请求副本裁去可明确识别的旧Phone Use图，保留宿主历史、最新图、用户其他附件、动作/工具关联和安全上下文，另做真实模型成功率回归。不能直接套独立AgentEngine策略。

NativeResponsesAdapter:58–74整body物化/parse/serialize及:149–181 replay恢复可能放大负载，先测分段CPU/分配/锁等待。保持opaque reasoning、完整工具组key、缓存容量与clear代际；不异步remember、不引入previous_response_id或修改store/reasoning预算。无工具输出不序列化主要帮助纯文本，并非Phone Use首要候选。

## P11：帧传输减少中间表示

详见[路径](critical-path.md#帧-payload-的真实路径)。FrameStore:10,44–47持有完整JSON UTF-8 bytes，当前Session同时持Base64 Frame。可实验Entry仅存image引用、**已验证**实际JPEG尺寸和捕获时diagnostics，消费时按原JSON协议流式写pipe；不要存整个Frame以免钉住tree。宿主受限JsonReader可减少BAOS→完整String→JSONObject物化，但最终Base64 String仍需存在。

这是中风险跨Provider/Hook改造，分别做发送/接收实验。保持整个JSON的UTF-8传输上限3,000,000字节（含Base64和diagnostics，并非解码JPEG大小）、one-shot原子消费、UID、60秒TTL、4 pending、坏图拒绝、消费者提前关闭和会话结束后在途帧。可信工厂可以提前验证JPEG尺寸，但不能直接信任公开Frame字段；不要永久双存JPEG与Base64。

## P12：启动与首观察应用列表

ScreenService:490–498每次全量query和loadLabel；AppLaunchTarget:9–20本来优先精确包名。可在同一新鲜query上先过滤并找包名，命中只load目标标签，失败才加载全量标签走原唯一名称规则。后台普通launch两次resolve（BackgroundActions:37,113），N个有效入口的标签调用可由2N降2，query仍2次；Android资源缓存影响真实收益。

apps():500–509先全量loadLabel才按包去重/12000截断。展示列表可流式过滤→去重→首有效入口loadLabel→相同截断，保留顺序。不能全局去重launcher入口，否则损害alias名称解析。

先做这两种减工作量，再profile是否需要PM查询/纯处理离开主线程；真正start、解锁和无障碍复核仍按原顺序。不能无条件合并后台两次target解析：AgentEngine:123–168之间可能跨模型/人工确认，目标可能变；NativeSession:180–213虽近也需生命周期证明。启动前后真实任务冲突和LaunchVerifier稳定500ms/10s上限保持。

## P13：日志、通知与其他较小成本

- PhoneUseLog:139–152每帧同Base64 hash两次，rememberImage还在images锁内做UTF-8/SHA。可局部只算一次、锁内仅更新Trace/32项淘汰，保持dispose竞态与关联语义，不新增原图缓存。
- :110–112模块侧同record序列化两次可复用；宿主:115增补字段后内容不同，不可盲合并。日志关闭无整图hash，但scope/bind仍必须支持中途开启。
- 日志开启时request():178–216每次遍历全部历史图片并hash，单独测日志成本，不把关闭态也算进去。
- InteractionUi:350–386通知已去重；仅实际publish:397–460的反射、渠道/模板值得profile。缓存精确binding不缓存动态权限；失败后须重试，锁屏后按钮须刷新。
- 可显式新增轻量screen_state供观察器，active时不携带它不用的上步长result（BridgeProvider:122；InteractionUi:353–380）；旧契约/终态结果与完成规则不变，先测parcel bytes。
- ActionConfirmation:50–56、ForegroundConsent:62–66答复等待可事件化；RunState:73–77的100ms切片可用取消信号有界await，保留完整稳定时长。分别做独立实验，覆盖先答后等、取消先于绑定、interrupt、deadline/extend和monitor锁序。
- NativePhoneUseTool:123–130每registry查询重注册两个工具，需先量宿主register成本；不能永久registered bool，evict/多registry/reload必须恢复。
- NativeModelHook:133–135同一请求先经addDefinition读取flags，再requestConfig读取model_config；可研究一次请求内的新鲜快照复用，不延长TTL、不删工具执行时再检查。
- :154–162定义齐全时可不复制列表；PhoneUseRouting:42、NativeSession:290,96模块内三次解析可局部复用语法结果，不能删预检/执行时状态重验。固定反射binding与短ID摘要属后置微优化。

NativeSession全步monitor、异步cleanup、watchdog generation和closing互斥是稳定性设计，不以缩锁并行动作为优化。外部循环是否逐步续期属于产品超时语义，单独澄清。
