# 会话与执行循环性能审计

日期：2026-10-19。基线：`418267f87e3909fd3b526c51c525bad86b895260`。
本次为静态审计，无本轮性能测量；没有执行构建、测试、设备、网络或模型请求，没有修改代码。
开始时 `git status --short` 为空，已阅读 `AGENTS.md`、`docs/architecture.md`、`docs/validation.md`。
本文所有收益均为待验证假设；源码中明确等待的时间也不等于端到端可无条件节省的时间。

## 覆盖

| 文件 | 覆盖结果 |
| --- | --- |
| `NativeSession.java`（398 行） | 全文；原生跨工具步会话、坐标边界、权限审计、复核、固定等待、结果拼接、watchdog、取消和关闭 |
| `AgentEngine.java`（253 行） | 全文；外部规划循环、历史限制、权限审计、复核、重复动作、切屏与 finally 释放 |
| `RunState.java`（79 行） | 全文；预算、取消绑定、连接断开、轮询暂停 |
| `Action.java`（99 行） | 全文；JSON/AutoGLM 数据解析、坐标验证、执行指纹 |
| `Coordinates.java`（30 行） | 全文；声明单位、JPEG/物理几何换算、JSON 拷贝 |
| `PhoneUseResult.java`（41 行） | 全文；顶层 STOP/PAUSE 和已知 CLI envelope |
| `TaskProgress.java`（37 行） | 全文；Unicode 过滤、短文本、私有输入隔离 |

已阅读相关测试：`NativeSessionReplanTest.java`、`NativeSessionLifecycleTest.java`、`AgentEnginePermissionTest.java`、`RunStateCancellationTest.java`、`ActionTest.java`、`CoordinatesTest.java`、`TaskProgressTest.java`、`PhoneUseResultTest.java`。
为确认调用链另读 `ActionPermissionReview.java` 全文，以及 `PageGuard.java`、`VisualRegion.java`、`ScreenService.java:352-432`、`BackgroundDisplay.java:258-285`、`BridgeProvider.java:187-208` 的相关实现。

## 优先候选 S1：先区分无输入分支的固定 800 ms 等待

事实与位置：

- `NativeSession.java:93` 首次准备完成后固定暂停 800 ms；`:224` 所有非结束续调固定暂停 800 ms，随后 `:230` 再观察。
- `:224` 同时覆盖真正动作完成、`:161-163` 刚发出 Auto 审计挑战而未执行、`:140-143` 审计过期目标跳过、`:204` 动作前目标变化、`:216-218` 注入前拒绝，以及 `:182-192` 前后台交接。
- `AgentEngine.java:72` 首次暂停 800 ms；`:181` 每个非结束循环固定暂停 800 ms，包括 `:158-161` 复核失败和 `:173-175` 注入前拒绝。下一循环 `:80` 才重新观察。
- `ScreenService.java:28/:365-369/:407-414` 已对物理截图保持 350 ms 调用间隔；该节流仅约束截图节奏，不证明页面动画结束，也不能直接代替 settle 等待。

最小可评估方案：先给分支记录“是否实际注入/改变屏幕、是否确认刚结束、是否切屏、是否等待审计”，只在明确无输入的审计挑战与目标拒绝分支缩短或省去额外 800 ms，保留末尾的新观察与取消检查。实际动作、启动、手势、显式 wait、首次界面收起、后台交接暂时沿用现有等待。不要先改全局常量。

收益假设：无输入分支每次显式等待为 800 ms，上界可见；总节省还受截图节流、helper、页面稳定和模型耗时影响。没有当前分支频率及 p50/p95，不能声称总体提升百分比。

稳定性限制：

- 正常动作前的第二次 observe 并非无用重复：第一张供模型选择，推理或确认后第二张供 `PageGuard` 验证目标，不能删除。
- `ObservationChanged` 可能发生在复合动作内部；必须核对执行器“部分执行即停止”的约定，不得以异常名称笼统推断完全没有输入，更不能重放首击。
- “用户确认后目标改变”也可能伴随正在消退的确认 UI；保留末尾 capture，并以动态页面回归确认缩短等待不会增加重规划循环。
- 不建议复用前一次 screenshot 代替 `:230` 的新观察。它会改变返回给模型的观察时刻，须独立证明才可另立方案。
- 跳过动作仍消耗原有步数，发出 Auto 审计挑战仍不消耗；不得为提升吞吐改动这些规则。

验证计划：在 RunState shadow 中记录 pause 次数和实际毫秒，覆盖审计挑战、过期审计、前置目标变化、注入前拒绝、真实成功、交接和最后一步。设备另行授权后比较同一动态页面任务的总耗时、额外重规划次数、实际输入次数与观察时间；提高循环速度却增加重复动作或抖动即拒绝方案。

## 优先候选 S2：同一步内对同一观察对的重复解析与图像校验

原生 Auto 审计续调在匹配挑战且未人工确认的分支，可对相同 candidate/action/current 做三次验证：

1. `NativeSession.java:136-138` 已捕获 current，调用 `canExecuteReviewed`。
2. `:151-152` 将相同 reviewedObservation 交给 `ActionPermissionReview.consume`；该函数 `:53` 再调用 `PageGuard.canExecute`，`:54-55` 再检查系统授权锚点。
3. `NativeSession.java:196-198` 再使用 reviewedObservation，经 `canExecuteReviewed:281-283` 重做检查。

`PageGuard.java:40-41` 每次重新解析两份树；未标注语义的容器或纯图区域还可经 `:61` 调用 `VisualRegion.stable`。后者 `:18-19/:57-63` 对两张 Base64/JPEG 再解码，`:51-53` 当次释放。图像字符串完全相同时 `VisualRegion.java:15` 已提前返回，不能把所有验证调用都算成 JPEG 解码。

最小方案：优先缓存 step-local、不可变的树解析结果或图像解码资源，仍逐次执行授权逻辑；仅限同一 Frame 对象/内容、action 快照、displayId/generation 和同一次观察上下文。另一种方案是验证证据对象，但改动面更大，必须把挑战匹配、token/60 秒期限、critical 检查和 PageGuard 结果分开，不能让一个 true 绕开 token 消费与权限判断。手工确认后 `NativeSession.java:171` 显式清空 reviewedObservation，必须保留。

风险与边界：不得跨 observe、跨动作、跨会话缓存成功；不得删除动作前新观察、执行器注入前重新读树/锁屏检查。缓存 bitmap 必须在当前步 finally 立即回收，不能延长截图生命周期。`Action.data` 是公开可变 JSONObject，不能仅用 Action 引用作为等价性证明。

测量：分别统计每步 PageGuard 调用次数、树解析次数、VisualRegion 解码次数/耗时/分配，在有语义按钮、匿名可点击容器、纯 canvas、系统授权和图片完全相同五种 fixture 下对比。收益依赖 Auto 使用比例与图像比较频率；没有数据，优先级高于普通 JSON 微优化但不保证高于截图/模型成本。

回归：保留挑战原始观察、challenge 返回时页面已变化、同一“允许”按钮但授权正文改变、过期/伪造 token、critical 转人工、确认后新 capture、跨 display/generation 失败、注入边界拒绝以及取消/超时。

## 候选 S3：执行指纹与 JSON 往返的分配

事实：`NativeSession.java:209` 与 `AgentEngine.java:163` 每次把 action 指纹、foreground、完整无障碍树拼成一个新字符串；树读取预算约 24000 字符，故会重复复制整树。`Action.java:36-40` 为排除 intent 和 permission_review，先 `data.toString()`，再 JSON 解析，再序列化。`Coordinates.java:18` 为不修改原请求也做完整序列化/解析；`AgentEngine.java:213` 审计回填再做相同拷贝。审计挑战另有 `ActionPermissionReview.java:22/:27` 的防变更快照拷贝。

最小方案：先 profile 指纹路径，再用保留完整相等语义的组件快照替代大字符串拼接，或者用经过差分测试的递归 JSON 拷贝减少文本往返。不要改成只有弱哈希的比较；哈希碰撞可能误触发“没有进展”停止。更不能为省分配直接删除 deep copy，使原请求/挑战持有可变嵌套对象。

兼容性风险：现指纹是序列化字符串相等，忽略顶层 intent/review，不等于重新定义后的“语义动作相等”。JSON key 顺序、数字格式、未知字段、别名与嵌套对象都可能改变重复动作计数。若改为语义等价或 canonicalization，需作为单独行为变化评审。Action.data 可变，当前代码中审计候选明确 remove review，因此盲目缓存 fingerprint 会过期。

验证：用大文本 type、嵌套 review、不同 key 顺序、1/1.0、Unicode、未知字段、intent/review 改变、同动作不同树/前台做旧新实现差分。记录每步分配字节与 GC，而非只测纳秒。保持连续第四次相同动作停止及“未注入不记录新指纹”的既有规则。

## 低优先候选与不建议修改项

- `Action.java:85` 的 `String.matches` 每个数字字面量可重新编译同一正则，预编译 static Pattern 可减小分配；每个 AutoGLM 动作参数很少、文本参数短路，不应在没有 profile 时优先。保留仅数据字面量、不允许额外代码和重复参数。
- Provider `BridgeProvider.java:199` 的续调预检和 `NativeSession.java:96` 的执行解析对同一短 query 解析两次。预检用于在 UI/job 保留前拒绝过期/外来会话，执行前仍须重新验证状态；不要为省解析删除任一校验。若未来传已解析不可变 envelope，应保持 TOCTOU 状态重检，收益预计低。
- `AgentEngine.java:86-94` 历史扫描和删除已被约 20 条消息限制；不是无界 O(steps²) 历史增长，不建议先引入 deque/重写协议历史。`:85-91` 已移除旧截图，Responses output 仍需保持协议上下文。
- `TaskProgress.java:9-10` 已限制最多 60 个有效 code point；正常输入很短，stream 改 for-loop 收益低，不能破坏 emoji、双向格式字符和隐私隔离。
- `PhoneUseResult.java:17-19` 已先检查普通顶层信号/非 JSON 前缀；不要改成在任意树/页面文本中搜索 STOP/PAUSE 来“加速”终止判定。
- `RunState.pause:73-77` 最长约 100 ms 间隔检查取消/期限，是停止响应保证；不要用一次长 sleep 替换以减少 wakeup。事件式取消若另立方案，需覆盖丢失唤醒、deadline 到期及线程中断。
- `NativeSession.java:45-49` 的全步 class monitor 串行化保证会话执行/清理互斥。取消 `:379-385` 与进度/存活读取不等该 monitor，异步清理 `:341-348` 等实际执行退出。不能为“锁性能”放宽并发动作或提前恢复 UI。
- watchdog `:311-339` 每次 accepted step 和完成再次重排；取消任务队列壳的滞留可另行检查，但不能未经 heap evidence 宣称 ScheduledFuture 必然保留 State/截图，常见 FutureTask 实现会清空 callable。当前 maxSteps 和低事件率下不是已证内存瓶颈。
- `RunState.cancel:36-46` 对回调做快照并在锁外触发，binding 自身只触发一次；不要随意 CAS 早退，连接可能在第一次取消后仍被其他路径赋值，改变重复 disconnect 语义需另查网络生命周期。

## 与性能测量分开的超时语义问题

`RunState.java:18/:22/:31` 定义 240000 ms 预算与 extend。原生路径在 `NativeSession.java:103/:228` 续期，watchdog generation 防止旧定时器取消新步骤。外部 `AgentEngine.java:75-184` 循环没有调用 extend，因此复用的同一 RunState 在本次任务开始约四分钟后仍会停止，即使此前多步持续进展。

这与“外部是否也应按单步续期”的预期尚未确认；架构明确描述的是原生续期。不能把延长外部任务时限包装成无风险性能优化。交接 Agent 应先确认产品意图，若调整则单独补丁，仍让单步卡住四分钟停止。性能基准要区分 deadline 终止和达到 maxSteps，避免把较早被取消的任务误判为资源更省或吞吐更高。

## 已有优化与必须保持的行为

- 每个原生会话只保留最近 Frame；外部循环只保留最新截图、有限文字与动作历史。
- 原生已在 Auto 审计 consume 与注入前检查间复用 reviewedObservation，人工批准后则强制新观察。
- 锁外取消、具体 State 绑定异步清理、旧 IPC binding 退休、watchdog generation 与 closing 标志覆盖后台释放窗口；不是待重建机制。
- 第 maxSteps 个动作仍返回最后观察；预算耗尽前先允许 finish/take_over/ask_user，超额数据动作在坐标解析前停止。
- 动作目标变化返回同会话重新规划；不重放旧动作；原生注入失败回滚重复动作指纹，外部成功后才记录指纹。
- 后台关闭/故障不读取物理屏；仅明确 foregroundHandoff 会切屏并丢弃旧坐标，终止时 finally/close 释放显示。
- STOP/PAUSE 仅顶层和已知 envelope 为控制信号；页面文字不能终止任务。

## 下一 Session 的最小执行顺序与验收

1. 先使用已有默认关闭诊断中的 capture_complete duration、session/step/observation 关联，补充只含阶段耗时与分支计数的基准，不记录正文/凭证/图片。确定模型、截图、固定等待、树解析和视觉比较的占比。
2. 先试 S1 的纯审计挑战分支，再扩至有明确无输入证据的目标跳过分支；每个分支单独评审。S2 可独立处理，先解析复用再考虑验证证据对象。S3/正则仅在分配数据支持时实施。
3. 运行本次读取的八个相关 JVM/Robolectric 测试类，以及 PageGuard、VisualRegion、ActionPermissionReview、屏幕节流/手势和后台动作相关测试；此处是未来验证计划，本次没有运行。
4. 现有 `AgentEnginePermissionTest` 将 pause 整体 shadow 掉；`NativeSessionReplanTest` 只推进虚拟时钟。它们能验证控制流但不能证明真实动画稳定、350 ms 节流、helper 或端到端耗时；必须新增分支计时断言并补真实环境测量。
5. 实机只在取得相应授权后执行，用前后台自有测试页覆盖慢动画、动态无关文字、纯图目标、前后锁屏、确认超时/取消、helper 死亡、旧 session/token、第四次相同动作及后台交接。验收输入次数与动作目标不变、停止延迟不回退、完整任务成功率不回退，再比较 p50/p95、CPU、分配和峰值内存。
6. 记录构建身份、fixture/mock/真实模型、设备与 ROM、运行次数、失败及超时原因。不能复用 `docs/validation.md` 的既有测试数作为本轮结果，也不能把 mock 成功作为真实模型质量证明。
