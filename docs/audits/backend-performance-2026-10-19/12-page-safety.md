# 页面安全、权限和同意链性能审计

2026-10-19；源码基线 `418267f87e3909fd3b526c51c525bad86b895260`。本报告为静态审计，无本轮性能测量，没有执行构建、测试、设备、root 或网络操作。所有收益均是假设；文中测试名称表示已阅读的回归入口，不表示本轮已运行。仅新增本审计文档，没有修改后端代码。

## 覆盖范围与证据

已阅读 `AGENTS.md`、`docs/architecture.md`、`docs/validation.md`。生产文件均位于 `app/src/main/java/io/github/miaiplus/`；测试均位于 `app/src/test/java/io/github/miaiplus/`，另行标明的设备 runner 除外。

| 生产文件 | 已读范围与结论 |
| --- | --- |
| `PageGuard.java:1-146` | 全文；同一步重复树解析和图像校验上下文可研究复用，安全检查本身应保留 |
| `VisualRegion.java:1-65` | 全文；JPEG 解码及逐 8×8 块 JNI 读取是需要测量的计算路径 |
| `PhoneUsePermissionPolicy.java:1-167` | 全文；重复目标解析、固定正则编译和常量分配可优化 |
| `SystemDialogPolicy.java:1-130` | 全文；循环 lowercase、重复拆行和模板扫描可优化 |
| `ActionPermissionReview.java:1-95` | 全文；可参与步骤内派生事实复用；JSON 深复制属于隔离边界 |
| `ActionConfirmation.java:1-83` | 全文；100 ms 轮询可研究事件唤醒，优先级低 |
| `ForegroundConsent.java:1-92` | 全文；同意等待轮询可研究，任务交接语义不能改变 |
| `AccessibilityGuard.java:1-99` | 全文；健康快路径已经直接返回，未发现值得优先实施的低风险性能修改 |

调用链补充阅读：`NativeSession.java:105-205,279-284`、`AgentEngine.java:123-175`、`ScreenService.java:385-430,479-488,650-712`、`BackgroundActions.java:110-137`、`RunState.java:1-79`。相关 JVM 测试：PageGuard、VisualRegion、PhoneUsePermissionPolicy、SystemDialogPolicy、ActionPermissionReview、ActionConfirmation、ForegroundConsent；另阅读 `app/src/androidTest/java/io/github/miaiplus/AccessibilityGuardRunner.java:1-83`。

既有验证文档区分了确定性 mock、真实设备执行和真实模型质量。不能以旧测试数量、设备 runner 的存在或本报告的静态分析替代当前修改后的验收。

## 候选 1：固定正则、固定集合及循环归一化

优先级：建议先做；源码事实置信度高，预期绝对收益小，稳定性风险低。

- `PhoneUsePermissionPolicy.java:64-72,83-88` 每次调用创建固定词数组或集合；`:75` 创建固定支付词集合；`:96` 创建固定点动作集合。移为私有静态常量，保持词项和匹配方式。
- `PhoneUsePermissionPolicy.java:76` 的 `String.matches` 每次编译相同支付金额正则。改为静态 `Pattern` 后调用 `.matcher(text).matches()`；不能换成 `.find()`，不能改变币种、空白、数字或匹配旗标。
- `SystemDialogPolicy.java:33-36` 在约 13 个词的循环内反复对同一授权标题执行 `toLowerCase(Locale.ROOT)`。循环前归一化一次即可。`:33-35` 固定词数组、`:47-53` 固定资源 ID 数组、`:84` 固定动作集合也可静态化。

收益假设：减少 Pattern 编译、对象与归一化字符串分配；不是模型/截图端到端时延的主要收益来源。ART/JIT 对部分分配可能已有优化，最终应以分配实测为准。

回归：完整保留 `PhoneUsePermissionPolicyTest.java:21-159`、`SystemDialogPolicyTest.java:22-80` 语义；重点核对支付金额完整匹配、英文大小写、普通导航、未知 Xiaomi 授权模板、重复 text/description、与实际目标无关的风险文字。测量以合成帧循环评估 policy CPU time 和分配，使用相同输入比较新旧输出。

## 候选 2：单次权限判断复用目标与系统窗口事实

优先级：测量后做；重复扫描事实明确，改动面中等。

`PhoneUsePermissionPolicy.needsReview` 在 `:26,28-29` 可调用 `critical` 和 `target`；调用者随后在 `NativeSession.java:148` 或 `AgentEngine.java:140` 调 `requiresManual`，又经 `PhoneUsePermissionPolicy.java:34,56` 解析目标；生成确认说明时 `:44` 再次解析。每次 target 都在 `:108-133` 拆行、正则匹配和解析整树。

`SystemDialogPolicy.java:74,78-80` 对同一安全中心/系统 chooser 树可能重复调用 `permissionGroup`，再由最多 5 次 `hasText` 各自拆行扫描；`:28-36` 会另取授权标题。普通应用会被 owner 早退出，因此这项主要影响系统授权与 chooser 路径，不能把理论最坏扫描次数当所有步骤的成本。

最小方案：先在 `isAppSelection` 单次调用内只求一次 permissionGroup；用一次遍历收集现有精确 chooser 文本结果。下一步才考虑为当前 action 快照和 Frame 生成短生命周期 `PolicyFacts`，共享 target、critical、系统 owner/permissionGroup/title 等派生结果。各公开判断仍返回原结果。

风险与边界：

- PageGuard 节点结构、遮挡判断和权限策略标签抽取并不完全相同，不建议为了复用而直接统一全部解析器。
- `hasText` 现有规则是 `text=` 后内容精确相等，不是任意子串；不要借优化放宽模板。
- `permissionGroup` 对重复、缺少 text、空白标题保守返回“未知授权”，这些分支须保持。
- 不将整页文字作为 critical 目标；不把普通安全中心清理页当授权，也不能反向放行未知授权。
- action 的 JSONObject 可变；不能以 action 对象身份跨修改缓存风险等级或目标。

测量：记录每步 target、permissionGroup、authorizationTitles、hasText 的调用次数与分配；普通应用、chooser、普通系统授权和 critical 授权分别测。回归补重复 group、空标题、title 带 description、大小写和额外字段的差分数据。

## 候选 3：同一步重复 PageGuard 派生计算

优先级：值得测量；源代码重复路径置信度高，安全改造风险中等。

在已有 Auto 审计候选且动作相符的路径中：

1. `NativeSession.java:132-138` 取 candidateFrame 和新 reviewedObservation，执行 `canExecuteReviewed`。
2. `NativeSession.java:150-152` 调 `ActionPermissionReview.consume`，后者在 `ActionPermissionReview.java:53` 再调用 `PageGuard.canExecute`。
3. `NativeSession.java:195-197` 在实际执行前可能再次以同一 planned/current 帧对调用 `canExecuteReviewed`。

`canExecuteReviewed` 入口在 `NativeSession.java:279-283`。每次 PageGuard 在 `PageGuard.java:40-41,100-120` 都解析两棵树；需要像素证明时可能再次进入 `VisualRegion.java:18-19,57-63` 的 Base64/尺寸/完整位图解码。只有实际满足相同帧对、动作和像素分支的路径才能得到重复 JPEG 收益，不能把所有三次调用都算为必然解码。

最小方案：建立只活在本次步骤、绑定具体不可变 Frame 实例和归一化 action 快照的派生上下文；先复用 parsed nodes，再单独评估按需解码图像对。保留每一道原本的检查和拒绝结果；不缓存最终“允许”布尔值，不用全局缓存，不仅按文本哈希或 action 名称缓存。

关键边界：

- 人工确认后 `NativeSession.java:171` 明确丢弃 reviewedObservation，必须重新观察，不能复用旧批准/帧对。
- 新 Frame、generation、前台、几何或 action 相关字段改变不能复用旧判断。
- `ScreenService.java:479-488` 主线程注入前新树复核必须保留；`BackgroundActions.java:126-137` 的新前台/新树复核亦须保留。
- 上述末次复核 Frame 复用 `planned.image()`，因此 `VisualRegion.java:15` 已走图像字符串相等快路径。此处可能仍重复解析新旧树，但不能把这些末次复核计作可节省的 JPEG 解码。
- 消费审计 token、timeout、critical 升级和完整系统授权树比较仍需执行，派生数据复用不等于复用授权。
- 位图共享的生命周期要由单个调用上下文拥有，在 `finally` 释放，防止跨线程提前 recycle 或长期持有截图。

测量：针对普通带标签 tap、匿名容器、聚合祖先文字更新和 Auto 审计续调，记录每步 nodes()/JPEG decode 调用数、CPU time、分配与峰值内存。校验收益来自重复 CPU/分配，不宣称减少模型或截图等待。

## 候选 4：一次 canExecute 内共享图像解码，保留两个 ROI

优先级：可随候选 3 的图像上下文独立验证。

`PageGuard.java:56` 调 `stableCover`。覆盖节点结构和动作目标身份保持，但非目标祖先聚合文字变化时，`:75-76` 会比较目标全矩形加 margin。目标若是没有非空标签的可点击容器，`:61` 又比较点击点附近加 margin。两次均可能在 `VisualRegion.java:18-19` 完整解码同一图像对。

最小方案：单次 canExecute 中惰性解码一次图像对，两个 ROI 仍各自执行原规则；或只 memoize 完全相同 ROI 和分块参数的结果。解码失败仍拒绝，不把失败当未知后允许。

不能以“大 ROI 已通过”删除小 ROI 校验。当前每个 ROI 的 8×8 块起点随其左上角改变；每块平均差异的分组不同，大 ROI 通过未经证明不等价于小 ROI 通过。两个不同 ROI 的成功结果不能互相复用。不要改变 6%/最少 32 像素 margin、逐通道 16、每块平均差异 3，不能改全屏平均相似度。

新增回归：父容器内容变化但匿名目标不变、两个 ROI 左上角相差非 8 倍数、一个 ROI 通过另一个失败的阈值临界图。统计单次 canExecute 的 decoder 次数，确认只减少重复解码而不减少独立 ROI 比较。

## 候选 5：按 8 行条带获取像素

优先级：先测再做；算法结果必须逐例等价。

`VisualRegion.java:30-46` 每个 8×8 块对两张 Bitmap 各调用一次 `getPixels`。稳定 ROI 的调用次数为 `2 × ceil(ROI宽/8) × ceil(ROI高/8)`。现有数组仅各 64 个 int，数组本身已很小；主要候选是 Java/native 调用数，不能描述为大量小数组分配。

可实验按 8 行条带一次读取整个 ROI 宽，再按原 x/y 块起点处理。数组限于本次调用复用，不创建全屏 int 数组；保持末行/末列不足 8 像素的处理、逐通道阈值和每块总和。更大条带会增加临时内存且损害早拒绝优势，不默认越大越快。

现有 `VisualRegionTest.java:24-81` 覆盖匿名容器、局部小图标、父容器聚合文字、替换节点、无效和尺寸不同图片、JPEG 舍入、远边坐标与全部点动作。补 ROI 宽高不是 8 倍数、图像边缘、delta 恰好 16/17、块总和恰好阈值/超 1、分块起点偏移。对旧新算法执行同输入布尔结果差分；分别测稳定 ROI、首块变化、末块变化的 CPU time、取像素次数和分配，避免只测全稳定最有利情况。

## 候选 6：确认等待的事件唤醒

优先级：后续研究；预计收益局限于等待阶段，不建议立即引入同步复杂度。

`ActionConfirmation.java:50-56` 与 `ForegroundConsent.java:62-66` 每 100 ms 检查 answer、取消、超时；完整 60 秒最多约 600 次唤醒。`RunState.java:73-76` 本身也最多 100 ms 轮询。ActionConfirmation 结束时 `:58-60` 仅多读几次 synchronized answer，次数有限，无需单独优化。

可研究由有效 respond 发条件信号，等待线程结合 `RunState.onCancel`，以 request.expires 和 run.deadline 较早者作等待边界。唤醒后必须重新 run.check，处理虚假唤醒、取消前已绑定/绑定前已取消、到期与批准同刻等竞争；finally 解除 cancellation binding。class monitor 与 Request monitor 的锁序不得反转，避免 describe/respond/cleanup 死锁。

通知请求本身仍由宿主状态轮询发现，这项不会自动去掉宿主 IPC。只有测得等待唤醒/电量或批准到执行延迟值得关注时再做，且不能延长 60 秒、降低取消响应或让 timeout 默认同意。

回归：`ActionConfirmationTest.java:98-211` 覆盖 Provider token、并发确认、超时、取消、原 deadline、完整说明和隐私范围；`ForegroundConsentTest.java:8-29` 主要覆盖 Request，若更改 await，需补等待线程取消、deadline、cleanup 和锁竞争。测每个等待请求的线程唤醒数与批准/取消到返回的 p50/p95，不记录说明正文。

## 已有优化及不建议更改的边界

- `PageGuard.java:38-39` 先验证屏幕再对非目标动作短路；`:66-67` 覆盖节点相等/数量不等提前返回；有稳定语义标签时 `:61` 避免不必要图像比较。不要为统一流水线而使所有动作都解码图片。
- `VisualRegion.java:14-15` 空图拒绝和完全相同字符串快路径已存在，`:39,45` 差异超限提前返回，`:52-53` 回收位图，`:62` 有像素上限。无效图、解码异常和尺寸变化继续拒绝。默认完整 ARGB 位图可能较大，但不能未经行为证明通过降低分辨率、RGB_565 或抽样少量像素削弱检测。
- `ScreenService.java:418-428` 已把物理截图长边缩至最多 1280，再以 JPEG 80 编码。不能把物理屏原始分辨率当 VisualRegion 的常规解码尺寸；坐标仍必须使用解码后 JPEG 实际尺寸，不能恢复依赖 Frame.imageWidth 的旧行为。
- `ActionPermissionReview.java:22,27` 两份 JSON 深复制分别隔离调用方动作和对外 challenge。`ActionPermissionReviewTest.java:74-81` 明确验证两方 mutation 不能改变内部 candidate，不可简单共享 JSONObject 省复制；敏感 Auto 挑战频率有限，优先级低。
- `ActionPermissionReview.java:45-62,67-93` 的超时、严格三字段 schema、字符串类型、token、reason 长度、一次性 clear、对象顺序无关但额外字段不等价、数字等价、页面/授权复核和 critical 强制人工都应保留。
- `PageGuard.java:79-84` 的前台、物理和编码尺寸、displayId、generation；`:44` 的唯一焦点、密码和 disabled；`:53-76` 的全部覆盖节点、顺序、结构和目标自身语义均不可降级。不能用只比较最小按钮来忽略遮挡容器。
- `SystemDialogPolicy.java:88-92` 对系统授权保持新旧完整 anchor 相同，不能替换成仅按钮标签相同。说明授权对象改变但按钮不变时应拒绝，相关回归在 `ActionPermissionReviewTest.java:144-153`。
- `ActionConfirmation.java:29-31,66-79`、`ForegroundConsent.java:44-48,76-88` 的任务、UID、token、到期和一次性响应保持；ActionConfirmation 另校验取消和步骤 deadline。旧批准不能跨步骤使用，等待不能等价于同意。
- `ScreenService.java:705-711` 附近的确认前后锁屏检查必须保留；没有宿主通知观察能力的设置测试不得等待无法显示的确认卡。
- `ForegroundConsent.java:22-31` 对工具名和兼容 CLI 的精确前缀及空白边界识别不宜改为 contains，否则其他工具和页面文本可能误触发任务结束。
- `AccessibilityGuard.java:64-80` 已先 check、健康立即返回；读 asset/Base64 只发生在掉线恢复，root 启动与修复等待通常更值得测量，尚无证据支持缓存脚本。`:82-97` 的输出 drain、45 秒上限、100 ms 取消检查、进程退出码和 destroy 不能移除。
- AccessibilityGuard 的单 worker、任务准备阶段检查和 healthy fast path 是已有边界；不得改为常驻修复或任务中自动重绑。`AccessibilityGuardRunner.java:49-70` 的现有探针意图包括健康时不需 root、false-on 任务前恢复、任务保留和无常驻修复，本轮未运行。

## 给实施 Session 的顺序

先做候选 1 的常量和 Pattern 小改动，并跑相关语义测试；建立合成数据的调用数/分配测量后做候选 2、候选 3 的树派生数据复用；图像上下文复用与条带取像素拆为独立变更，逐项对比旧算法。最后根据等待阶段实测决定是否开展事件唤醒。任何修改不得把已有安全复核删除当作性能优化。

本审计没有新增运行结果；实施后仍须按仓库要求执行相关测试和所需 lint/build。设备、root、真实模型与网络验证需要实际授权和明确范围，文档中的测试方案本身不是执行授权。
