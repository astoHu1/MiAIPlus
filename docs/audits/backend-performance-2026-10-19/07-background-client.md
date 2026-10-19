# 后台显示客户端性能审计

日期：2026-10-19。源码基线：`418267f87e3909fd3b526c51c525bad86b895260`。本报告为静态审计，无本轮性能测量；所有收益均为待测假设。未修改后端、未构建、未运行测试、设备或网络操作。

## 范围与证据

开始与初轮审计结束的 `git status --short` 均为空。文档记录阶段存在其他审计文档并行写入，本报告只新增自身文件。已读仓库指南、`docs/architecture.md`、`docs/validation.md`、`docs/background-display.md`。以下源码路径除特别标注外，相对于 `app/src/main/java/io/github/miaiplus/`，行号对应上述基线。

| 文件 | 覆盖 | 结论 |
| --- | --- | --- |
| `BackgroundDisplay.java` | 全文，385 行 | 进程、RPC、观察、动作、关闭；候选 BGC-01/02/03/04，关闭契约前提 BGC-05 |
| `BackgroundActions.java` | 全文，147 行 | 动作前复核、交接、双击、输入、导航；候选 BGC-02 |
| `DisplaySession.java` | 全文，34 行 | 不可变身份与 generation；无值得单独优化的热点 |
| `app/src/test/java/io/github/miaiplus/BackgroundDisplayTest.java` | 全文，146 行 | 缓存目录隔离、输入确认、启动状态；没有异步协议/关闭握手负例 |
| `app/src/test/java/io/github/miaiplus/DisplaySessionTest.java` | 全文，43 行 | generation、图像/逻辑几何分离、导航集合 |

关联核对：`NativeSession.java:175-239,320-390`；`AgentEngine.java:105-185`；`BridgeProvider.java:272-310`；`VirtualScreenState.kt:64-87`；`ScreenService.java:126-170,190-247`；`FrameStore.java:20-49`；`PhoneUseLog.java:129-150`；`NativeSessionLifecycleTest.java`、`BridgeProviderProbeTest.java`、`ForegroundConsentTest.java:61-68`；`app/src/androidTest/java/io/github/miaiplus/BackgroundRunner.java` 相关验收路径。helper 仅核对 RPC 与调用关联，其内部性能由独立审计报告负责。

`docs/validation.md` 的 2026-10-18 摘要记录了诊断阶段 364 项 JVM/Robolectric、双变体构建/Lint及确定性设备验收。这是既有记录，不是本轮执行结果，也不能替代下列优化的性能/稳定性验收。其他 ROM、真实模型长任务与未知授权模板仍需分别验证。

## 静态调用工作量

下表适用于 `NativeSession` 的常规后台步骤：同一会话、无权限审批/审计、无交接、无目标变化跳过、无异常、正常完整执行。只统计源码调用，不代表耗时；`type` 的输入定位和回读另计。初始会话打开、模型时间、固定动作后等待不计入 RPC。

| 动作 | RPC 数 | `focusPackage` 引发的 `dumpsys window displays` 次数 | 后台树读取次数 |
| --- | ---: | ---: | ---: |
| tap / swipe | 5 | 3 | 3 |
| type | 5 | 4 | 至少 3，另含输入定位/回读 |
| double_tap | 8 | 4 | 4 |
| back | 4 | 2 | 2 |
| home | 5 | 3 | 2 |
| wait | 2 | 1 | 1 |

计数链：`NativeSession.java:195-215` 对目标动作进行预观察；`BackgroundActions.java:72,75-76` 做 display/focus 复核；执行动作；`NativeSession.java:224-230` 等待并后观察。每次 capture 在 `helper/DisplayHelper.java:1070-1074` 再调用 `focusPackage`；该函数 `1462-1480` 启动 dumpsys。双击增加 `BackgroundActions.java:87-93` 的第二 display/focus/tap 及真实 120ms 间隔。type 没有 helper 输入 RPC，但额外调用一次 focus，随后走无障碍节点写入/回读。`AgentEngine` 的动作前观察规则更统一，导航计数不同，不能直接套表。

## BGC-01：替换 20ms RPC ready 轮询

优先级：先基准，再独立实现；中等改动风险，潜在收益覆盖所有后台步骤。

位置：`BackgroundDisplay.java:179-218`，核心 `201-216`。当前请求在 `requestLock` 串行执行：flush → `BufferedReader.ready()` → 未就绪 sleep 20ms → 重试。helper 在睡眠期间完成时，客户端最多额外近 20ms 才消费回复；均匀相位下约 10ms 只是数学估计，并非设备测量。每秒等待最多约 50 次唤醒，一条卡死请求的 25 秒上界约可有 1250 次轮询。一个常规动作重复多个 RPC，会累积等待。

最小方案：保留单请求串行，使用绑定这个 helper 的专用 stdout reader，以 token 匹配、有界可取消等待通知请求线程；不要为每条 RPC 创建 executor/thread。先只改变传输等待，独立于语义验证合并。

约束与负例：

- 保留 25 秒上界、`RunState.check()`、EOF/进程退出失败、旧 token 丢弃、closed gate。
- `ready()` 只说明已有字符，当前 `readLine()` 若遇“写半行后挂起”仍可能阻塞，绕过后续超时/取消检查。新设计应覆盖该负例。
- 不应直接在调用线程阻塞 `readLine()`，也不应将 20ms 改为 1ms 用更多唤醒换延迟。
- reader 生命周期和取消必须绑定具体 helper，旧 reader/旧请求不能消费或取消替换后的 helper。
- 使用有界回复容器，失败时唤醒等待者，关闭路径避免 reader/request/全局锁互相等待。

回归：fake helper 延迟 0/1/5/19/21/50ms、分段写行、EOF、错 token 后正确 token、超时、等待中取消/中断、close/request 竞争。可注入超时/clock，避免慢测试。现有 `BackgroundDisplayTest.java:110-145` 仅同步输出完整行，覆盖不到这些情况。

基准：分离 helper 执行时间与客户端收包等待，记录 RPC RTT p50/p95/p99、线程唤醒、整步延迟、取消到动作停止/显示消失延迟。不要用整体模型任务耗时掩盖传输层收益。

## BGC-02：同一复核时点合并只读 display 与 focus RPC

优先级：BGC-01 后评估；中等风险。

位置：`BackgroundActions.java:69-76,126-137`；`BackgroundDisplay.java:245-250,289-290`；helper `209,221-222`。execute 先做 `display` RPC，再做 `focus` RPC 和 `treeOnDisplay`，然后输入。可增加类型化 `state` 回复，同次返回真实显示存在/几何/身份与当前焦点，减少一次串行协议往返。`display` 本身调用 `describeDisplay()`，不额外启动 dumpsys；不能宣称合并会减少一次 dumpsys。

最小方案只合并同一时点的只读证据，仍读取注入前树，仍验证显示身份。不删任一证据，不跨动作/审批缓存，不把系统 display ID 等同于 generation。generation 当前由模块 `DisplaySession` 生成，不能仅凭合并回复中的 display ID 就宣称建立了新的远端 generation 证明。

`type` 和 `home` 在 `BackgroundActions.java:100,107` 的第二次 focus 确实重复启动 dumpsys，但更靠近输入的包边界或主页存在判断有正确性价值。直接复用先前焦点会扩大 TOCTOU 窗口，不建议首批移除。若后续优化，须设计复核与执行边界，并证明新出现窗口不会误收输入，不能只比较 RPC 数。

回归：显示消失/ID 重用、焦点改变、权限窗出现/变化、未知/空树、双击第二击变化、输入节点替换、后台失败不转主屏、所有权与原生会话取消。基准同时检查动作成功率、目标变化拒绝率、主屏触摸/焦点/IME 隔离，不仅比较延迟。

## BGC-03：JPEG 文件与 Base64 的中间分配

优先级：后续，须与 Frame/FrameStore 审计联合；跨模块风险较高。

位置：`BackgroundDisplay.java:261-274`；`FrameStore.java:40-46`；helper `1045-1074`。

已证链：helper JPEG 写独立缓存文件 → 模块 `Files.readAllBytes` → Base64 String → `FrameStore.put` 解码为 JPEG byte[] 读取边界 → 含同一 Base64 的 JSON → UTF-8 byte[]。每观察至少一次文件写/读/删及一次 Base64 编码；原生返回再有一次 Base64 解码与 JSON/UTF-8 分配。helper 已限制 JPEG 长边 1280、质量 80，因此不能直接认定磁盘是瓶颈。

最小候选：为可信 JPEG 路径建立保留验证能力的元数据/字节入口，让 FrameStore 避免重复解码边界；设计必须保留坏图拒绝与实际 JPEG 尺寸检查。缓存字节时须评估 byte[] 与 String 双份持有带来的峰值增长，不要先长期保留两份。二进制 pipe 替换文件协议涉及 framing、背压、取消及诊断通道，风险与改动远大于当前收益证据，暂列探索。

必须保留每 session 私有目录、文件即时删除、UID 绑定一次性 token、逻辑尺寸与 JPEG 实际尺寸分离、图片不进入 Binder 大 Bundle/工具字符串、截图失败停止且不借主屏图像。基准按 320/1080/4096 画布、静态/动画页面、capture source 分组，拆分 JPEG encode/read/Base64/FrameStore 时间、Java/native allocation、GC、峰值内存和文件残留。

## BGC-04：availability 每次检查启动 root helper

优先级：低；当前不是已证高频路径。

位置：`BackgroundDisplay.java:73-79,89-105,151-175,351-370`。每次 availability 创建缓存目录、`su/app_process`、stderr drain daemon、probe，然后关闭/等待进程。调用链为 `MainActivity.kt:517` 按钮 → `VirtualScreenState.kt:64-78` → `BridgeProvider.java:289-292`。注释称 timer refresh，但当前源码仅找到按钮调用，没有周期调用证据，不能按每秒 root 进程估算收益。

若手动检查基准明显慢，可分开静态系统信息和动态 root/锁屏事实，或合并同时发起的检查、短 TTL 并标注查询时间。不要复用活动任务 helper 使设置查询竞争动作锁，不把缓存的 root granted 当作新任务实时授权证据。保留 status 不建屏、probe 互斥和独立帧目录。每次重新查询事实本身有正确性价值。

## BGC-05：关闭握手的正确性前提

位置：`BackgroundDisplay.java:351-357` 对照 `194-195`。静态确定：close 先设 `closed=true`，随后 `request(null, "destroy", null)` 被 requestLocked 的 closed gate 拒绝，异常被忽略，因此显式 destroy RPC 不会发出。后续仍会关闭 stdin/stdout、`helper.destroy()`、最多等待 3 秒再 force；helper `173-189` 也有 EOF 后 destroy。

这不能直接证明泄漏：当前还有管道 EOF、进程终止、系统资源随进程释放等实际回收机制，设备验收也检查显示消失。应准确记为“显式 destroy 握手未执行，正常退出方式与时序缺少专门证据”。不得把该现象直接写为确定资源泄漏，不得从最终没有屏幕反推显式握手成功。client 关闭 stdin 后紧接 process destroy，EOF 正常清理是否完成、su/app_process 子孙是否均正常退出仍需设备核对。

这不是删除等待的性能快修。若澄清契约，可设计 closing/closed 状态或私有 teardown 请求，关闭时拒绝新业务却允许本实例的短、有界终止握手。不能简单将 `closed=true` 移到可能等待 25 秒的请求之后，也不能删掉 3 秒退出等待而声称降低延迟；须保持退出期间 busy 与新任务/探针互斥。

现有 `NativeSessionLifecycleTest.java:33-135` 覆盖取消不阻塞调用方、旧取消不关新会话、旧 watchdog 不误停、清理期间 busy；其 `HelperProcess` 不检查 destroy 协议。`BackgroundDisplayTest.java:28-43` 只证明关闭 status 不删除其他 session 帧，`46-84` 覆盖输入/启动状态。`BackgroundRunner.java:232-248` 检查显示消失、主屏/键盘隔离；不能替代正常退出与强制回收分开验收。

建议负例：fake helper 验证 destroy 真正收到且只一次、ack 后退出、无响应有界强制回收、RPC 等待中取消/close、旧 session 关闭与新 session 创建竞争。设备记录 helper/父子 PID、destroy ack、正常退出与强制 kill，分别报告而不合并为一个 PASS。

## 已有优化与不可简化边界

- `BackgroundDisplay.java:93-95` 每 helper 独立目录；`281-285` 观察 finally 删除文件；`374-378` 清理残留。不能将所有 helper 合并同一帧目录。
- helper 已有 ImageReader 最新帧、200ms 复制节流及最终 pending 保留；不能停止 drain，否则缓冲填满后显示不再绘制。
- `BackgroundDisplay.java:133-136` 创建已落实焦点/HIDE 后只回读，不在每动作重新配置 IME。
- `BackgroundActions.java:33-47` 交接前与审批后重查主屏任务，后者不可作为重复读删掉；授权绑定 app/任务/token，变化须暂停。
- 双击 `87-93` 第二击前复核和部分执行失败语义不可删除；长按/滑动的真实时长是动作语义，不是无用 sleep。
- `ScreenService.java:229-245` 非主线程、每次重新枚举窗口、最多 1.2 秒精确回读是已有正确性修复，不能改成 ACTION_SET_TEXT 被接受即成功，或复用旧句柄。
- `DisplaySession` UUID/generation、逻辑/JPEG 像素分离、故障停止、OWN_FOCUS/NO_FOCUS_CHANGE/HIDE、输入确认、旧 session 清理隔离不是性能冗余。UUID 只在建屏时生成，没有改为弱标识的收益依据。
- `BackgroundActions.java:130-132` 的 current 使用 `planned.image()`，更新的是树/焦点，不是新截图像素证据。之前完整 observe 与注入前树复核承担不同职责，不能认为两者完全等价而删掉完整观察。
- 后台不借物理树/图/焦点；失败不静默转前台。只有文档规定的应用冲突交接，经审批、重新核对、释放旧屏、作废旧观察后才能切换。

## 推荐实施顺序

先补可分摊基准和异步协议负例，再单独推进 BGC-01。BGC-05 作为生命周期契约澄清另行处理；其结果也影响后续取消/传输设计。之后独立评估 BGC-02，只有 allocation 证据支持时推进 BGC-03。BGC-04 保持低优先级。每个改变分别记录 mock、Robolectric、确定性设备和真实模型证据，不能将静态工作量减少写成已证性能提升。
