# DisplayHelper 只读性能审计

日期：2026-10-19。源码基线：`418267f87e3909fd3b526c51c525bad86b895260`。本报告为静态审计，无本轮性能测量；未构建、未运行测试、未操作设备或调用网络。所有收益都是待验证假设，不能视为已实现或已证明的提升。

## 覆盖范围与证据

- 完整读取 `app/src/main/java/io/github/miaiplus/helper/DisplayHelper.java` 1–1655 行，覆盖创建/隔离、类型化命令、能力探测、帧消费与截图回退、任务/焦点查询、手势/按键、隐藏 API、子进程输出和退出清理。
- 完整读取 `DisplayHelperFramesAndGesturesTest.java` 1–355、`DisplayHelperTaskTest.java` 1–209、`app/src/test/java/android/media/FrameFixtureImage.java` 1–33。
- 交叉核对 `BackgroundDisplay.java` 118–218、258–308、351–370；读取 `AGENTS.md`、`docs/architecture.md`、`docs/validation.md`、`docs/background-display.md`，相关日志元数据说明见 `docs/phone-use-logging.md` 37–47。
- 以下 H 指 `app/src/main/java/io/github/miaiplus/helper/DisplayHelper.java`，B 指 `app/src/main/java/io/github/miaiplus/BackgroundDisplay.java`。行号均为上述基线。
- 初次及审计结束 `git status --short` 均为空；文档阶段开始时出现其他审计文档目录，未改动其内容。本文件是文档阶段唯一写入文件。

## 优先候选

### H1：同一状态采样共用一份 window dump

**事实与调用链。** H502–505 `mainState()` 分别调用 `focusPackage(0)`、`focusedAppPackage(0)`、`tasks(0)`。前两项 H1464、1492 各执行同一个固定 argv 的 `dumpsys window displays`，第三项 H1510 执行 `dumpsys activity activities`，所以一个 `mainState` 采样是三个子进程。`stableMainState` H514–523 最多采样 12 次，`settledMainState` H532–542 最多 6 次；能力探测 H353、384、391、394、398、400、407 使用这些采样。**这主要是能力探测路径，不能说成每个生产动作都执行三个 dump。**

生产路径的 `stableMainComponent` H1011–1027 会读取 app focus，空时另启动相同 window dump 读取 window focus；它要求连续两次相同 component，并随后通过 H1018 的最新 TaskInfo 验证主屏可见任务。生产 `focus` RPC H209 和 capture 回复 H1074 各自通过 `focusPackage` 执行 window dump；是否出现重复 RPC，须结合上层动作所处的安全时点判断。

**收益假设。** 中等潜力，尤其降低探针与主屏归属读取的固定进程启动、Binder dump 和字符串解析成本。将同一次采样的 app/window focus 从同一份 dump 解析，也减少两个 dump 间切页产生的混合状态。实际耗时比例未知。

**最小方案。** 引入不可变 window focus snapshot；每次采样执行一次原始 dump，分别解析该 display 的 app/window focus。`mainState` 从三个子进程降到两个；`stableMainComponent` 在 app focus 为空时可以复用同次 dump。维持跨时间双样本、TaskInfo 验证和失败语义。不跨动作、确认等待、采样或工具调用缓存。

**风险与回退。** ROM 输出、内联 display header、null focus 都是安全相关解析边界。保留现有解析语义；回退为当前单字段读取即可。不要把字段缺失解释为“无冲突”。不要用旧 capture focus 或 TTL 缓存替换动作前的新鲜检查。组合验证与注入 RPC 应独立设计，不能只为省一次调用搬动安全时点。

**必要验证。** 新增纯解析行为测试：多个 display、行中 display header、`mCurrentFocus=null`、app/window 不一致、系统弹窗、坏输出及主屏焦点变化。现有两份 helper tests 未直接覆盖 dump 解析。设备测每 RPC 的 dump 次数、查询 p50/p95、切页误判、主屏 IME/任务隔离；保留原稳定采样次数和超时。

### H2：缓存任务查询反射元数据，结果仍实时读取

**事实与调用链。** H937–940 每次反射查找 `ActivityTaskManager.getService`；H954–966 每次遍历 service 全部方法选择 3/4 参数 `getTasks`；H971–980 每个 task 约读取七个字段，每次 H943–947 都重新 `getField` 和 `setAccessible`。launch 前 H846、后 H855 各查询一次；`task_state` H992 查询，随后 `stableMainComponent` H1018 又查询。

**收益假设。** 较小至中等，随任务数量增加而放大；Binder 查询可能占主导，不能未测量就承诺明显收益。

**最小方案。** 按实际 service class 缓存已验证的 `getTasks` Method；按 TaskInfo 实际 class 缓存字段访问器。可缓存平台 `getService` Method，但不能把缓存的旧 Binder 或旧任务 snapshot 当实时证据。每次仍重新获取结果并验证 task/display identity、可见性与真实 component 归属，不缩小 1000 的完整性边界，不删除启动前后查询。

**风险与回退。** ROM 签名、代理 class、fixture class 不同，缓存必须按 Class 区分；服务死亡后的重新获取仍要正确失败或恢复，不能返回旧快照。回退仅撤销元数据缓存，不改协议与任务策略。

**必要验证。** `DisplayHelperTaskTest` 102–151 已覆盖 affinity 不冒充归属、其他屏可见任务冲突、启动后任务搬移、查询失败/空列表/无归属。补充 3/4 参数签名、多 service/info class、缺字段、接近完整性上限、缓存元数据后内容变化。测量时分开记录反射查找、Binder 和 TaskState 转换，比较不同任务数量下 p50/p95 与分配量。

### H3：按需解析 SurfaceFlinger token，避免正常 reader 路径同步等待

**事实与调用链。** H318 已启动 reader 消费线程，H320 仍同步执行 `surfaceFlingerId(name)`；H1536–1545 最多执行 20 次 `dumpsys SurfaceFlinger --display-id`，每次失败后 sleep 250ms。每个子进程自身受 H135 的 20 秒上限，因此重试成本不能只按 sleep 的五秒理解。token 用于 H1100、1125 的 screencap 回退及 create/display/probe 诊断字段。全仓引用核对时 `DisplaySession.surfaceFlingerId` 除构造赋值外无其他行为消费者。

**收益假设。** reader 正常路径可省去至少一次 dump，以及 token 异步注册未就绪时的重试等待；实际命中率与建屏耗时未知。

**最小方案。** 保存本次显示名称和身份，正常 reader 路径不阻塞等待 token；reader miss 需要原 screencap 回退前，再按本次显示身份解析。保留回退、重试预算、来源记录及错误语义，不指向物理屏。需明确 create 回复 token 暂空及 DisplaySession 诊断字段的含义。

**风险与回退。** 刚建好的 own-content 空屏可能没有 reader 帧，仍依赖 screencap；不可简单删除 token 查询。延迟解析须拒绝已销毁/重建同名屏的 token，逻辑 display id 可能复用，不能作为唯一身份。回退恢复 H320 同步查询即可；不要与帧消费架构一起改。

**必要验证。** reader 正常、刚建空屏、token 延迟注册/始终缺失、销毁重建、reader 故障回退、同名历史实例、FLAG_SECURE ROM 差异。测 create p50/p95、fallback 总耗时/错误率；不缩短现有超时来获得表面数字。

## 测量后再决定的帧流水线候选

### H4：以帧通知和到期刷新减少静态屏空闲轮询

**事实。** H675–694 无新图像时 sleep 8ms，静态屏理论约 125 次空轮询/秒；H789–808 等首帧时每 20ms 轮询。实际调度、耗电与 CPU 影响未测量。

**最小方向。** 评估专用 `ImageReader.OnImageAvailableListener`/HandlerThread 唤醒消费，仍用 `acquireLatestImage`；对 pending 节流截止点安排单次 flush；帧、错误或关闭状态通知等待观察者，同时保留现有 deadline。

**不可改变的语义。** H688–692 即使后续无帧也必须完成最终 pending 帧复制；H793–801 观察必须立即 flush 最新 pending 再返回独立快照；持续消费必须防止 ImageReader buffer 满后生产者停绘；H763–780、1548–1563 的销毁顺序和 Image 释放不能丢失。

**风险与回退。** 中风险：回调合并、到期任务与观察并发、监听移除、reader 关闭及快速建毁可能串帧或持有旧 buffer。将现有 drain 保留为可独立恢复单元。不要靠加大 200ms、减少截图像素/质量或缩短等待获得收益。

**必要验证。** `DisplayHelperFramesAndGesturesTest` 76–119 已覆盖最终节流内重绘、观察主动 flush、停止释放未复制图像。追加回调合并、没有后续回调的最终帧、观察/停止/回调竞争、快速 destroy/create、监听注销无旧 reader 引用。设备比较静态/动画下 CPU、唤醒、native heap/PSS、帧 age、首帧/观察 p95、异常退出时间；睡眠次数下降不等于总收益成立。

### H5：复用完整帧 Bitmap 或 padding scratch，降低大屏分配

**事实。** H740–760 每次保留帧新建 Bitmap；有 row padding 时先建 padded Bitmap 再 crop。H721–726 替换后 recycle 旧帧；H801 观察再复制完整帧，H1059–1063 capture 再缩放。默认 1080×1920 ARGB 一张约 7.91MiB，4096×4096 一张 64MiB，不含 padding；这些只是字节量级，不能当峰值实测。周期复制受 200ms 限制，观察强制 flush 可额外复制。

**最小方向。** 先测 copy、Bitmap allocation、padding 分支占比。如确为热点，按固定几何复用可变 Bitmap/padded scratch；观察仍得到独立快照，在 JPEG 压缩期间不得复用或回收其 backing pixels。像素、旋转、geometry、row stride 结果保持完全一致。

**风险与回退。** 中至高风险：H680/H792 的锁同时保护 Image 与 Bitmap 生命周期，直接将 copy 移出锁会引入 recycle/use-after-close/跨会话串帧。现有 `FrameFixtureImage` 12–20、29–31 永远是 1×1、stride=4，未覆盖 padded 路径。回退恢复分配式 copy；与 H4 分开提交和测量。

**必要验证。** 非零 row padding、多行多色像素、边界几何、最后一行 buffer 长度、frame replacement 与 capture 并发、连续 stop/create、失败释放。设备测 native allocation、GC、锁等待、帧 age 和 JPEG 像素一致性，不降低尺寸或压缩质量。

## 低优先级机会

### H6：探针 digest 避免 PNG 编码后再 hash

H818–832 整帧 PNG 编码到 ByteArrayOutputStream，`toByteArray` 再复制，随后 MD5；probePointer H619/629、probeKey H642/647 多次经过该链路。可按行用固定缓冲读取像素，将 geometry 与像素送 digest，保留“前后画面是否变化”判别。收益仅限开发能力探测，不是生产观察热路径，应低于 H1–H3。

测试相同像素 hash 一致、单像素变化不同、几何可区分、reader/screencap 差异语义；不得把“不变帧=untested”改成“不支持”。原算法可直接回退。

### H7：固定 argv 输出读取减少 chunk String 分配

H1609–1624 每个 8192 字节 chunk `new String` 后 append；完整 dump 随后还 split/strip。可评估 InputStreamReader 加复用 char[] 直接 append，也避免多字节字符跨字节 chunk 解码破碎。收益可能很小，应先测占比。

保留并行输出 drain，不能改成先 waitFor 后读取，否则管道满会死锁；不能无声截断 dump 再把缺失字段当安全结果；固定 argv 与 20秒 timeout 保留。测试长输出、分段多字节、超时、进程退出仍排空、stderr 合并；现有 helper tests 未覆盖实际 subprocess drain。回退当前读取器即可。

## 已有优化和不应做的改动

- 输入隐藏 API 已缓存：H1253–1293；不能将 swipe 描述为每事件重新解析三个反射方法。H1306–1328 每事件仍检查目标 display、NO_FOCUS_CHANGE 并绑定 display id，这些检查不可删除。
- 手势已使用同一 downTime、真实 eventTime 和绝对 uptime deadline：H1189–1210、1225–1248。不要削减 13 个事件、缩短真实持按/滑动时长或改成不等待接受；UP 被拒后的 CANCEL 必须保留。测试 121–174、207–228 覆盖时序、路由、NO_FOCUS_CHANGE 和取消。
- H1373–1381 的 IME recheck 已避免对就绪 HIDE 重复写配置；不能缓存上次成功从而跳过实时回读。`DisplayHelperTaskTest` 171–200 覆盖策略拒绝/忽略/缺失与平台焦点能力。
- launch 前 H846 和后 H855 是不同安全时点，不能共用旧 TaskInfo snapshot；查询失败、部分/空结果不可变成“没有冲突”。不能将 taskAffinity 当包名。
- reader 帧复制已有 200ms 节流，观察强制刷新最终 pending 帧；不能删除空闲 flush 或以帧降质获得性能数字。
- 两条截图路径必须寻址同一后台显示；任何失败不得回退物理屏。源路径与 FLAG_SECURE 差异需按 ROM 验证，不能假定统一。
- 探针样本、输入确认、取消/超时、任务/工具归属、焦点隔离和新旧 display generation 均不属于可删冗余。

## 退出语义澄清

B355 先设置 `closed=true`，B357 随后的 `request("destroy")` 必被 B195 gate 拒绝，显式 destroy RPC 没有发送。这是正常退出确认缺口，但不能断言必然泄漏。

B359 关闭 helper stdin，helper H183–189 在 EOF 后调用 `destroy()`；H224 stop 也 destroy 后退出。H1548–1563 释放 VirtualDisplay，再停止帧消费并关闭 ImageReader。B361 随即 `process.destroy()`、B363 最多等三秒再强杀，使 EOF 自然清理是否完成缺少确认；进程死亡也不等于一定留下后台屏。

后续与 client 清理改进统一设计：在阻止新业务请求后保留受控清理通道或明确 EOF 握手，观察正常 stopped/销毁确认、无残留屏/进程、取消、新旧实例隔离及退出延迟。保留有界最终强制回收，但强杀后无残留不能代替正常退出验收。

## 下一 Session 的执行顺序

1. 从 H1 单次采样共用 dump 与 H2 元数据缓存开始，先固定解析及实时查询行为，再独立测量。
2. 统计 create 阶段时间、SF token 查询次数和回退频率，再决定 H3；确保空屏回退和显示身份完整。
3. 仅在静态 CPU/唤醒或 native allocation 数据证明值得时推进 H4/H5，两个方向分开实验。
4. H6/H7 放在证据证明其占比值得后处理。

复用现有 `helper_reply.durationMs`、`capture_complete`、`frames/source` 元数据；必要时添加不含页面内容的阶段耗时。比较静态/动画、大几何、空屏、启动系统弹窗、任务冲突、取消清理和主屏键盘隔离。用 p50/p95、错误率、CPU/唤醒、PSS/native allocation 和帧新鲜度衡量，不只比较单个局部方法。

`docs/validation.md` 9–14 的 345/364 项测试和设备结果是既有记录，不是本次通过结果。设备证据主要 Xiaomi API 37、小爱 8.3.30；不能外推所有 ROM。既有帧节流、焦点和 TaskInfo 修复应作为行为基线；每个候选需要后续授权环境下的实际验证，本报告不把静态推断写成性能结论。
