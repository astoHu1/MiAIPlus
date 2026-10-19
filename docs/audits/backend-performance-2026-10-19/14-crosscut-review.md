# 后端性能候选交叉复核

日期：2026-10-19。审计基线：`418267f87e3909fd3b526c51c525bad86b895260`。

本报告独立只读复核六项高价值候选，依据当前源码、`docs/architecture.md`、`docs/validation.md` 和已有测试源码。未执行构建、测试、设备操作或网络访问；没有本轮性能测量，排序不是已测收益排名。本报告仅新增文档，不授权实现或设备操作。审计开始时 `git status --short` 为空；文档阶段出现其他审计者的 `docs/audits/` 新文件，均未改动。

源码路径下文以 `app/src/main/java/io/github/miaiplus/` 为根，测试以 `app/src/test/java/io/github/miaiplus/` 为根。行号对应审计基线，后续实施前应重新核对。

## 实施优先级

| 顺序 | 候选 | 风险与处理建议 |
| --- | --- | --- |
| 1 | BridgeProvider 按需创建 Config | 最安全且调用频繁，可首先做成独立小变更；不要改成全局缓存 |
| 2 | HookCallback extras 延迟分配 | 语义容易保持的小改动；实际收益待分配分析 |
| 3 | ScreenService 截图编码移出主线程 | 潜在价值高，但涉及资源所有权和回调生命周期，属于中风险改动 |
| 4 | PageGuard 同次校验复用图片解码 | 只复用本次调用的解码资源，保留各 ROI 原有校验；不能复用不同 ROI 的成功结果 |
| 5 | DisplayHelper 同次采样共用 window dump | 优化完整能力探针，不应宣传成生产每步热点；保留多次稳定采样 |
| 6 | ResponsesStream 分块扫描字符 | 已有 BufferedReader；先确认 CPU 热点，不能称逐字符网络 I/O |

“最安全”只表示相对切口小，不保证未经验证的实现稳定。上表优先考虑稳定性和实施边界；截图处理的潜在绝对价值可以高于小型分配优化。

## 1. BridgeProvider：避免状态轮询读取完整配置

`BridgeProvider.java:80` 在许多无需配置的分支之前创建 `Config`，包括 `screen_state`（114）、`poll`（255）、`screen_ready`、`cancel`、停止及同意响应。`Config.java:31` 调用 `SharedPreferences.getAll()`；43–45 行解析协议和推理设置，间接进入 `ReasoningPolicy.java:66–69` 的 SHA-256 与循环 `String.format`。不能把这些工作描述为每次磁盘读取：SharedPreferences 自身有缓存，源码能确认的是完整配置快照与派生计算。

调用频率可由源码确认：`InteractionUi.java:352,386` 的任务观察器每 700 ms 请求 `screen_state`；`XiaoAiHook.java:138,147` 的兼容工具每 400 ms 轮询任务；`SettingsState.kt:271,276` 的测试页每 500 ms 轮询。

最安全切口：保持 UID 校验、任务所有权和方法分支顺序，只在真正使用配置的路径创建 Config。不要扩大为跨请求缓存、TTL 或异步配置快照，否则必须额外处理设置热更新、一致性和凭证生命周期。

已有测试：`BridgeProviderConversationSettingsTest` 覆盖配置热更新、隐私和忙状态；`BridgeProviderContinuationTest` 覆盖过期续调、任务归属和跨任务结束通知。缺口是无需配置方法不触发配置读取的针对验证。实施后测 IPC CPU/分配量，验证设置变更无需 Provider 重启即生效，并确认拒绝调用仍先经过权限检查。

## 2. HookCallback：延迟创建 extras

`hook/Hooks.java:34` 每次拦截创建 `MethodHookParam`，`hook/HookCallback.java:22` 随之分配 HashMap。当前全仓使用 extras 的调用点仅在 `NativeModelHook.java:175–191` 和 `NativeReasoningHook.java:115–130`；多数拦截不使用它。

最安全切口：map 初始为 null，首次 `setObjectExtra` 时创建，未设置的 `getObjectExtra` 返回 null；保留 null key/value、重复写入和同一次 before/after 共享状态。不要引入对象池、跨调用共享或 ThreadLocal map，也不要顺便改 Hook 忙计数、参数复制和热重载门禁。

`hook/HooksTest.java:45` 已验证 before/after 保存状态；应补未写入读取、null 值和重复写入等行为。先通过分配分析确认有效；不能因为每次少一个对象就称其为主要性能瓶颈。

## 3. ScreenService：截图 callback 内含主线程 CPU 工作

`ScreenService.java:380` 使用主线程 executor 接收截图成功回调；385–388 行执行 hardware→ARGB copy 和 `encodePhysical`，420–428 行缩放、JPEG 压缩及 Base64 编码。这是明确的主线程 CPU 工作来源。

已有优化不可重复认领：350 ms 采样间隔（28、407–414）、最长边 1280（420）、JPEG 质量 80（425）、相应 bitmap 释放。每次观察必须获取新截图，不能为了速度复用旧帧。

移线程不能标低风险。需要明确 HardwareBuffer、hardware bitmap、ARGB bitmap 和缩放 bitmap 的唯一释放责任；当前等待在 396 行有 5 秒上限，超时后的迟到回调仍须安全释放。还要定义取消、服务解绑/销毁、executor 拒绝、连续截图和内存峰值，避免无界编码队列。先决定在哪一线程复制硬件内容、何时关闭 buffer，再安排编码，不能只机械替换 executor。

`ScreenServiceCaptureTest` 已覆盖尺寸、节流及等待时取消，但没有覆盖异步编码所有权、超时迟到、销毁/拒绝执行。实现需补这些失败路径，再进行设备主线程 trace，比较编码前后主线程阻塞、端到端观察延迟、峰值堆及未释放 bitmap；JVM 图像正确性不能单独证明设备稳定性。

## 4. PageGuard：复用解码，不能合并不同 ROI 的成功结果

`PageGuard.java:56` 调用 `stableCover`；当父容器聚合文字改变时，75–76 行可能检查整个目标 ROI。若目标无标签，61 行还会检查点击点附近 ROI，因此一次调用可能两次解码同一对 JPEG。

关键否决：即使整个目标区域包含点击点附近区域，也不能直接把第一次 `stable=true` 用作第二次成功证据。`VisualRegion.java:31–45` 的 8×8 分块平均阈值依赖 ROI 起点和边缘裁剪；ROI 变化会重排块或改变边缘块面积，成功不具区域包含意义上的单调性。改变 ROI 或仅检查并集会改变拒绝条件，属于安全语义修改。

最安全切口是本次 `canExecute` 内懒解码一次，两个 ROI 分别按现有算法、原顺序检查，结束立即 recycle；保留字符串相等短路、实际解码尺寸映射、像素上限、阈值与失败关闭。只有 `target == null` 且所有 ROI 参数完全相同的重复调用，才可在同次校验内精确复用结果。不应跨动作或跨帧长期缓存 Bitmap。

另一个重要去重：`ScreenService.java:485` 的最后主线程节点复核直接复用 `planned.image()`；`VisualRegion.java:15` 已以相等字符串短路。因此不能将该复核统计为一次额外 JPEG 解码，也不应删掉它来获得所谓收益。

`VisualRegionTest` 已覆盖匿名控件、父文本变化、tiny icon、边缘坐标、尺寸和编码误差。缺口是“父文本改变 + 匿名目标 + 不同 ROI”的针对用例，特别是差异落在两种 ROI 的不同分块边缘时仍保持原拒绝结果。应测触发两次视觉比较的实际比例、每次 decode 次数和峰值内存，避免把条件路径的收益外推到全部动作。

## 5. DisplayHelper：同次采样共用 dump，但不要取消稳定采样

`helper/DisplayHelper.java:502–504` 的 `mainState()` 通过 `focusPackage` 和 `focusedAppPackage` 各执行一次相同 `dumpsys window displays`，再执行 `dumpsys activity activities`。对应 shell 调用在 1464、1492、1510 行。

`mainState` 仅被 `stableMainState` 和 `settledMainState` 调用；这些采样用于完整能力探针 baseline 与各步骤（353、384–407），不是生产动作每步都执行该三次组合。因此这一候选应标为探针性能优化。生产中其他 focus 查询有各自的调用路径，不能由此推定它们都有相同重复。

最安全切口是提取纯解析函数，让一次 MainState 的 window focus 和 app focus 共用一份 dump 文本；保留两次连续稳定采样、窗口焦点/app 焦点/task 三项、空值规则及各步骤归属。不要跨采样 TTL 缓存，不要用 app/task 相同取代窗口焦点。单次 dump 更一致也会改变原本两个时刻的观测，应视作需要验证的语义变化。

`DisplayHelperFramesAndGesturesTest` 覆盖缺 baseline、无窗口焦点和丢失物理窗口焦点时不能报告隔离成功，但缺 dump 解析同等性。应增加多 display、空字段、字段顺序和已记录 ROM 文本样本的解析验证。测每次采样的子进程数和探针阶段耗时，不声称生产任务速度同步提升。

## 6. ResponsesStream：逐字符扫描不是逐字符网络读取

`ResponsesStream.java:23` 已用 BufferedReader；40 行逐字符执行的是 buffered-reader 方法调用。30–35 行已实现批量输出读取。已知路径为 `NativeResponsesAdapter.java:85–91` 的原生外部 Responses SSE 适配，不是全部模型请求。

直接替换为 `readLine()` 并非无风险：当前总 2,000,000 字符计数包含 CR/LF，循环丢弃任意 CR，并能在超长行尚未结束时拒绝；`readLine()` 的行终止规则和分配方式不同。分块读取也不能为合并输出而额外等待下一事件，因为非内容帧需及时转成 heartbeat 维持宿主 idle watchdog。

只有 CPU profile 证明扫描明显占比时，才考虑有界分块扫描；必须保留限制、CR 行为、UTF-8 解码、及时心跳、提前 EOF、失败不缓存和完成前不发可执行工具调用。已有 `ResponsesStreamTest` 保护完成前工具调用、终止错误及非内容 heartbeat；仍需补分片 UTF-8、CRLF/单 CR、阈值前后、超长行、heartbeat 后无后续数据、关闭和流分片边界。不得将 CPU 微优化假定为模型首 token 或网络延迟的大幅改善。

## 交接验收约束

- 每项单独实现、单独评估，不把安全语义重写与性能小改动捆绑。
- 保留 UID/任务/token/generation、取消、超时、最新观察、主线程最终节点复核及后台失败停止规则。
- 本报告引用的测试仅为已存在测试源码，未在本轮执行；历史验证数量不能写成本轮通过数。
- 后续测量记录构建身份、设备/ROM、前后台路径、日志开关、输入样本、重复次数和分布；不要填无来源的耗时或收益百分比。
- 没有明确授权时继续采用只读分析，不因本文包含测量方法就执行设备探针或真实模型请求。
