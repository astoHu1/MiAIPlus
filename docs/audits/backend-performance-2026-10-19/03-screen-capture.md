# 物理屏观察、输入复核与应用启动性能审计

日期：2026-10-19。源码基线：`418267f87e3909fd3b526c51c525bad86b895260`。

仅静态审计，无本轮性能测量。未修改后端代码，未运行构建、测试、设备或网络操作。下文的热点存在性可由源码复核，实际耗时、分配占比和收益均待测，历史验证记录不能算作本轮通过。

## 覆盖与证据

完整阅读的生产文件：

| 文件 | 范围 | 职责 |
| --- | --- | --- |
| [ScreenService.java](../../../app/src/main/java/io/github/miaiplus/ScreenService.java) | 1–714 | 物理截图、物理/后台树、输入回读、应用解析/启动、注入前复核 |
| [FrameStore.java](../../../app/src/main/java/io/github/miaiplus/FrameStore.java) | 1–69 | UID 绑定的一次性内存帧传输 |
| [MainThread.java](../../../app/src/main/java/io/github/miaiplus/MainThread.java) | 1–30 | 有界主线程调用 |
| [AppLaunchTarget.java](../../../app/src/main/java/io/github/miaiplus/AppLaunchTarget.java) | 1–23 | launcher 包名/唯一标签解析 |
| [LaunchPrompt.java](../../../app/src/main/java/io/github/miaiplus/LaunchPrompt.java) | 1–28 | 精确识别本次小米跨应用启动确认 |
| [LaunchVerifier.java](../../../app/src/main/java/io/github/miaiplus/LaunchVerifier.java) | 1–70 | 单次启动、单次确认、稳定前台等待 |

完整阅读核心测试：`ScreenServiceCaptureTest`、`ScreenServiceTextInputTest`、`ScreenServiceTreeTest`、`AppLaunchTargetTest`、`LaunchPromptTest`、`LaunchVerifierTest`。另外核对 `PhoneUseLogTest:111–166`、`SystemDialogPolicy`、`RunState`、`BridgeProvider.openFile:349–361`、`NativeSession:224–241`、`BackgroundDisplay:258–277` 的相关调用。AndroidTest/Debug runner 仅检索调用覆盖，没有逐文件通读全部实现。

已阅读仓库 AGENTS、`docs/architecture.md`、`docs/validation.md` 和 `docs/app-launch.md`。审计起止的 `git status --short` 均为空；这指本分组静态阅读阶段，不代表其他分组后续文档写入状态。

主要调用链：

- 原生观察：`NativeSession.observe → ScreenService.capture → capturePhysical → onMain 读取树 → takeScreenshot → encodePhysical → FrameStore.put → BridgeProvider.openFile → FrameStore.take → 私有管道`。
- 独立外部循环观察：`AgentEngine.observe → ScreenService.capture`，随后 Frame 的 Base64 图片进入模型请求。
- 后台树和文字：`BackgroundDisplay.observe → ScreenService.treeOnDisplay`；`BackgroundActions → ScreenService.setTextOnDisplay → readTextOnDisplay → readBackText → findSameNode`。
- 物理动作：`execute → executePhysical → 前置系统窗口复核 → gesture/type 的主线程闭包 → 新鲜 root/授权/目标/几何复核 → 输入注入`。
- 物理启动：`executePhysical(launch) → launch → launchTargets/AppLaunchTarget.resolve → LaunchVerifier.launch → foreground/start/allowLaunchPrompt`。

## 优先建议

### SC-01：物理截图编码移出主线程

**优先级：高价值候选；收益未测；实施复杂度中等。**

事实：`ScreenService:380` 将 `takeScreenshot` 的 callback executor 设为 `getMainExecutor()`，所以成功回调在主线程执行。回调中的 `hardware.copy(ARGB_8888)`、缩放、JPEG 80 和 Base64 编码见 `381–388`、`418–428`。1440×3200 的 ARGB 软件副本单张约 18.4 MB；峰值还包括硬件图、缩放图、压缩缓冲、`toByteArray()` 副本与 Base64 字符串。此路径每次物理新观察必经，但不能仅凭像素数推断实际停顿或收益。

最小方案：使用受生命周期管理、有界的专用编码 executor 接收截图回调；先保持像素复制、缩放、JPEG 参数和 Frame API。无障碍读取与实际输入继续遵守原主线程顺序。不能把回调提交到正在等待 `f.get()` 的同一个单线程执行器，否则可能自锁。

取消与资源边界：

- 使用 `RunState.onCancel` 让等待及时结束；取消应绑定具体执行步骤，不能影响后续任务。
- 超时、取消、服务销毁后迟到的 callback 仍须关闭 `HardwareBuffer`、回收全部已创建 Bitmap，不能更新后续任务状态。
- executor 拒绝任务、回调异常、`hardware.copy` 抛错均需要明确释放路径；当前包装 Bitmap 的回收也值得纳入异常路径检查。
- 不复用旧截图，不更改350ms请求节流和安全窗口失败语义；保持物理尺寸与JPEG实际尺寸分离。

回归：callback 成功/失败、5秒超时、取消、迟到成功、服务销毁、executor拒绝、编码失败。保留 `ScreenServiceCaptureTest:21–44` 的横竖屏/小屏/JPEG尺寸/图像像素坐标测试，以及 `58–77` 的节流与取消测试。

测量：授权设备上分开记录树读取、hardware copy、scale、JPEG、Base64 的 P50/P95、主线程最长 slice、Java/native heap峰值；至少覆盖1080p/1440p、文本/照片页面。比较总观察延迟及取消收尾延迟，不能只报告后台线程局部耗时。

### SC-02：同一注入闭包只序列化一次新鲜树

**优先级：优先实施；收益未测；风险较低。**

事实：手势闭包在 `ScreenService:679` 获取同一个 root；`verifySystemDialog`（682，内部665–672）先 dump，随后 `verifyActionTarget`（687，内部479–488）再次 dump。输入闭包在641–643同样连续读取两次。动作前置gate（617–623）还有一次复核，但其与实际注入属于不同主线程消息，不能直接删除最终复核。

最小方案：每个实际注入主线程闭包从刚取得的root构造一个live snapshot/tree，分别传给系统授权策略与PageGuard。只合并同一个同步闭包内的重复序列化；保留前置gate、最终几何读取和双击第二击前的全新复核。

待测收益：每个普通单击/type的最终注入阶段减少一次最多250节点的遍历和字符串构建。实际跨进程读取是否已被Android节点缓存覆盖、对总延迟影响多大，需要测量。

风险：不得复用上个工具步、批准前或双击第一击前的树。snapshot必须限制在一个同步注入闭包内；前台包、显示identity/generation、几何、锁屏、批准绑定均保留。不能把一次构造树误写成跨动作缓存。

回归：普通点击/输入、目标变化、审批期间新授权窗口、旋转、双击首击后变化、password/验证码拒绝。增加dump调用计数验证工作减少，并同时证明原有拒绝/允许结果不变。测量每动作的dump次数、主线程耗时和总执行延迟。

### SC-03：启动等待先按包分类，普通包不构建完整树

**优先级：优先实施；收益未测；风险较低。**

事实：`LaunchVerifier:44–63` 每250ms轮询；`ScreenService:563–578` 的每次foreground无条件dump至多250节点。`SystemDialogPolicy.isAppSelection:67–80` 对普通应用包在75–76已经返回false，树文本只影响android/intentresolver/securitycenter等系统候选。目标普通包进入前台后500ms稳定期仍重复dump。

最小方案：从新鲜focused root读取package，普通包直接返回package；系统候选包保留所需的树/标题读取和原分类。`permission_group_title` 在574和578可能重复查询，可只在同一次foreground闭包中读取一次。

保留500ms稳定前台、10秒总超时、单次启动、单次“本次允许”、可读chooser/授权返回观察、root refresh和锁屏/取消检查。不能缩短稳定期或加快轮询来换取表面提速。

回归：`LaunchVerifierTest` 普通包、目标已前台、短暂闪现、chooser、普通安全中心页、启动确认转其他授权、取消。真实设备需验证focused root转移及真实chooser。测成功启动和10秒失败等待的tree遍历次数、主线程占用、总等待时间。

### SC-04：PackageManager枚举与标签加载移出主线程

**优先级：中等价值候选；先不加缓存；风险较低。**

事实：`ScreenService.launchTargets:490–498` 每次 `queryIntentActivities` 后对所有可用条目 `loadLabel`。`apps:500–509`、`launch:559–560`、后台 `resolveLaunchTarget:108–115` 均把整个操作放到 `MainThread.call`。首次应用列表及后续launch可能重复枚举。12000字符上限仅控制最终输出，在截断前完整entries已建好、全部label已加载。

最小方案：PackageManager查询及纯AppLaunchTarget解析放worker，每次仍取新结果；最终锁屏/任务检查与显式startActivity保持主线程。保留被卸载、停用、组件失效时明确失败，不猜测任意Intent/组件。

二期可选：只有profile证明重复枚举明显时，再考虑package/version/locale失效的短生命周期immutable launcher snapshot。启动时仍需处理状态变化；不得把旧列表中的组件当作仍启用/可导出的证据。

回归：包名优先、同包多launcher、跨包同名歧义、未知目标，新增枚举后卸载/停用、语言变化及组件失效。测不同安装应用数下query/label分项时长、首次观察和后续launch主线程耗时。

### SC-05：FrameStore延迟传输JSON序列化

**优先级：中等风险备选；收益依读取比例而定。**

事实：`NativeSession:230–232` 每次新观察调用put。`FrameStore:40–47` 将Frame Base64解码成JPEG、bounds-only解码验证实际尺寸，再将同一Base64放入JSON、序列化为String、转UTF-8 byte[]。宿主不读取token也已支付这些分配；主会话仍持有原Frame.image，entry另外持有大payload byte[]。

最小候选：Entry保存immutable图片String引用、已验证的实际JPEG bounds及put时刻diagnostics snapshot；通过合法take消费后再生成传输JSON。保持跨进程协议不变。未取/被淘汰的帧可省去序列化，正常读取只是将部分成本后移。

风险与要求：

- 保留JPEG实际bounds验证，不能直接信任Frame.imageWidth/imageHeight。
- diagnostics在put时snapshot，不能读取时关联后续步骤。
- 保留UID、一次性消费、60秒TTL和四未读token淘汰；定义序列化失败后的消费语义。
- 更大范围将Frame改存JPEG byte[]涉及模型请求、VisualRegion和诊断等调用，不能包装成局部小改动。

现有 `PhoneUseLogTest:111–136,158–166` 覆盖图片/尺寸、日志开关、一次性、错误UID、过期、日志异常；需补未读淘汰、并发put/take、延迟序列化失败与diagnostics归属。

测量：正常读取、完全不读、慢读三种负载，对比每步allocation、峰值heap、put/take延迟、GC和端到端附图时间；不能以JSON微基准代表总收益。

### SC-06：输入回读精确身份快速路径与取消

**优先级：仅profile指向此处时考虑；不得破坏原字段身份验证。**

事实：后台setText在 `ScreenService:234–245` 每80ms重新枚举窗口回读，`263–290` 保留新鲜窗口；`300–307,341–349` 用最多1000节点递归DFS查找原节点。重新枚举和原节点身份都是已有正确性修复，不能缓存旧window或直接读取新focus。

候选：新鲜root中先取FOCUS_INPUT，仅当 `equals(original)` 时直接回读，否则保留原身份搜索。后续若需防循环/深递归，可评估有identity seen的迭代有界搜索；遍历次序与预算可见性变化必须单独验证，不能顺手更改。

取消候选：当前方法没有RunState参数，回读上限1.2秒，单次MainThread.call可等5秒。可显式传run并在每次回读/等待前check，用RunState.pause保持等待上限。取消不等于输入未发生；不可把已接受输入自动重试。

回归：自动焦点移到下一框、原节点删除但另一框恰有相同文字、中文/清空/不可读/精确文本、password、display/package/window不匹配、循环图、深树。`ScreenServiceTextInputTest` 已覆盖原节点/新focus及精确文本多数关键语义，需保留。测每次回读访问节点数、主线程占用和取消响应。

## 低收益项和资源边界

- `MainThread.call:13–20` 每次创建Handler，可复用绑定主Looper的Handler；这是小项，优先级低于编码/树/PM。保留finally cancel/removeCallbacks及异常解包；不复用future/runnable，不去掉timeout。
- `LaunchPrompt:24–26` 每次replaceAll编译regex，12–15双循环重复normalize caller/target。预编译Pattern和一次性normalize小数组可减少少量分配，但仅启动确认分支调用。不能放宽精确完整匹配及防伪规则。
- FrameStore过期清理是惰性的：put清理（23–27），过期take拒绝但不删除（53–58）。检索未发现生产clear调用，因此闲置时最多四个payload可继续被静态map持有。最低风险是在确认过期的take做compare-remove；真正闲置TTL主动释放需要生命周期/调度设计。不得为此先加常驻高频清理线程。
- FrameStore的size检查/淘汰/put不是一个原子操作，并发put下四条上界未被容器机制严格保证；正常会话串行约束须结合总审计确认，不能据此宣称生产必然越界。若加强边界，应将大图校验/序列化置于短临界区之外并复核并发消费语义。

## 已有优化与不可削弱的边界

- `ScreenService:352–415` 共用350ms单调节流、可取消等待，每次观察取新截图，不缓存旧画面。
- `380–430` 最长边1280、JPEG80，区分物理尺寸和实际编码尺寸；缩放图/软件图回收，HardwareBuffer关闭。
- `433–471` 树预算250节点/约24000字符、待访问上界1000、BFS保留浅层导航、来源identity去重防祖先循环；password脱敏及换行转义。
- `84–102,125–170` focused window优先；后台只查指定display，没有物理屏fallback。
- `188–349` 后台输入等待在worker、重新枚举新鲜窗口、精确原window/node身份回读、不追随新focus、不猜测格式。
- FrameStore及 `BridgeProvider:349–361` 使用一次性UID绑定token、60秒TTL、四未读帧淘汰、私有管道；图片不进入Binder Bundle。
- `LaunchVerifier` 和 `ScreenService:559–603` 只启动一次、确认一次、稳定500ms/最多10秒；可读系统弹窗交还模型；普通安全中心页不会仅凭包名被误判为授权。
- MainThread具备主线程直调、5秒上限、异常解包、结束时cancel及移除排队回调。

建议实施顺序：普通启动包分类、同闭包树复用、截图编码线程迁移、PM线程迁移，最后评估Frame表示与传输延迟序列化。每项独立补丁和回归，避免一次性重构整个观察路径。

所有方案必须保留安全窗口、锁屏、password/CAPTCHA、任务所有权、取消、确认、几何与display generation限制；不得复用跨动作旧观察、降低截图质量或减少树预算来获得未经验证的表面加速。
