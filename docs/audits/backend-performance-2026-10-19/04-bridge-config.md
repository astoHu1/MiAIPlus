# Bridge、配置与原生工具代理性能审计

日期：2026-10-19。源码基线：`418267f87e3909fd3b526c51c525bad86b895260`。

本报告为静态审计，无本轮性能测量。审计阶段起止 `git status --short` 均为空；没有修改后端代码，没有运行构建、测试、设备或网络操作。此文件仅记录候选，实际收益、耗电和端到端延迟均未验证。

## 覆盖与依据

完整阅读以下五个源码文件，并追踪必要调用方与测试：

| 文件 | 审计范围 |
| --- | --- |
| `app/src/main/java/io/github/miaiplus/BridgeProvider.java` | IPC 分发、调用身份、配置构造、JOBS 锁、任务状态、watchdog、一次性帧管道 |
| `app/src/main/java/io/github/miaiplus/Config.java` | SharedPreferences 一致快照、协议与推理策略解析、默认值 |
| `app/src/main/java/io/github/miaiplus/EndpointProfiles.java` | 配置存储、序列化、切换与 flat runtime snapshot |
| `app/src/main/java/io/github/miaiplus/NativePhoneUseTool.java` | 工具代理、注册表查询 Hook、定义追加、错误与重载清理 |
| `app/src/main/java/io/github/miaiplus/NativeTurnLifecycle.java` | 观察解析、请求身份、任务绑定、结束与重载清理 |

辅助调用链：`ReasoningPolicy`、`XiaoAiHook`、`InteractionUi`、`NativeModelHook`、`NativeSession`、`FrameStore`、`SettingsState`。

已阅读 `AGENTS.md`、`docs/architecture.md`、`docs/validation.md`。验证汇总更新于 2026-10-18，其 364 项测试、构建、Lint 和 mock/设备结果均是历史记录，不是本轮执行结果，也不证明本报告候选具有性能收益。

## BC-1：配置无关 IPC 跳过完整 Config 构造

优先级：高。最小方案风险：低。证据强度：代码路径明确，收益未测量。

事实与调用链：

- `BridgeProvider.java:80` 在大部分 IPC 分发之前无条件 `new Config(getContext())`。
- `Config.java:31` 调用 `prefs.getAll()`，随后执行模型协议解析、推理档位规范化及 `ReasoningPolicy.preferenceKey`，即使调用方完全不使用配置。
- `ReasoningPolicy.java:64-70` 每次生成模型身份摘要，包含 SHA-256、正则替换及 32 次 `String.format`。
- `XiaoAiHook.java:135-147` 每 400 ms 调用 `poll`；`InteractionUi.java:349-386` 每 700 ms 调用 `screen_state`。工具执行期间两条轮询链都付出完整配置构造成本。
- `hook_report`、`stop_screen`、`model_exhausted`、`model_finished`、`foreground_consent`、`action_confirmation`、`screen_ready`、`cancel` 也不消费这份 `Config`。

最小方案：先分发上述配置无关方法；仅在 `flags`、`conversation_settings`、`set_reasoning`、`model_config`、`start` 分支需要时构造配置。第一步只整理分支，不同时引入跨请求 TTL 或复杂快照缓存。

可确认减少配置副本、摘要和解析次数。`SharedPreferences.getAll()` 不等于每次读磁盘，不能声称此方案必然减少磁盘 IO。

必须保留：

- `BridgeProvider.java:60` 的 `enforceCaller()`。
- `BridgeProvider.java:249-256` 的任务 UID/owner 校验。
- 单次 `getAll()` 带来的 endpoint/key/model/api 一致快照；不可改为分别读取凭证字段。
- `start` 执行时读取最新配置、`set_reasoning` 的当前模型身份与有效档位校验。
- 全局模型强制原生 planner 的约束。

必要验证：

- 运行现有 `ConfigTest`、`EndpointProfilesTest`、`BridgeProviderConversationSettingsTest`、`BridgeProviderContinuationTest`、`BridgeProviderProbeTest`。
- 验证配置无关 IPC 不触发完整配置解析，同时 owner、过期续调、取消与完成理由保持原样。
- 使用同一组 400 ms/700 ms 轮询做 Perfetto 或 allocation profile，比较 Provider CPU、分配字节与 IPC p50/p95。微基准不能代替端到端收益。

## BC-2：现代 ToolRegistry 查询重复注册两个工具

优先级：中。兼容风险：中。证据强度：重复调用明确，宿主注册代价待测量。

事实与调用链：

- `NativePhoneUseTool.java:71-74` 为前后台工具分别执行 `installTool`。
- `NativePhoneUseTool.java:126-130` 对现代宿主 `get/getAll/peek/peekAll/searchTools` 的每个方法分别挂 before Hook。
- 每次查询因此执行两个 `register(...)`；`44-47` 反射调用宿主 `register`，并在弱引用集合锁内执行 `registries.add`。
- 此逻辑也作用于与控屏无关的工具查询。

最小方向：先统计普通请求中的查询与注册次数，确认宿主 `register` 工作量和 `evict()` 语义，再设计“已注册且未被淘汰则跳过”的机制。单纯按 registry 身份缓存“注册过一次”不可采用，因为当前逻辑要求淘汰后补回及重载后接管已有 DI registry。

风险与验证：

- 验证小爱 8.3.30 的现存 registry、多个 DI registry、构造时注册、单工具/全量淘汰、搜索、查询及热重载。
- `dispose()` 的 `49-60` 必须只卸载模块两个工具并释放旧代理、schema 与宿主 ClassLoader 关联。
- 不在 Hook 内递归调用已经挂钩的 `get/peek` 检查存在性。
- 本轮相关测试搜索未发现专门覆盖这一代理注册链的 JVM 测试；纯测试桩不能替代最终授权宿主验收。
- 若宿主注册已经廉价幂等，收益可能不足以覆盖新增状态复杂度。

## BC-3：工具定义已存在时避免复制完整列表

优先级：低。风险：低。收益：请求设置路径的小额分配/反射减少，未测量。

`NativePhoneUseTool.java:154-162` 无条件 `new ArrayList<>(old)`，再为两个工具扫描原列表并反射读取名称。当两个定义都已存在时，内容未变却仍替换 `p.args[1]`。

最小方案：先查找缺失定义，首次确认需要追加时才复制；可在一次遍历中检查两个名称。保持原定义顺序、原对象身份、只补缺失工具及不修改原列表。

验证空列表、两个都缺失、只缺一个、两个已存在及宿主不可修改列表。不能据此直接宣称整体控屏更快。

## BC-4：活动 screen_state 重复传输上一条完整观察

优先级：测量后再定。接口风险：中。

- `BridgeProvider.java:122` 对匹配任务始终返回 `screenResult`，其字符串上限由 `227` 限为 24000 字符。
- 原生会话跨步时，`screenResult` 可能仍是上一步完整 `CONTINUE` 观察。700 ms 通知轮询的活动分支 `InteractionUi.java:353-371` 只使用进度与确认状态。
- `NativeTurnLifecycle.observe` 也使用 `screen_state`，只需要 active、native_session、screen_task。

候选是增加明确的轻量状态请求参数/方法，或完整检查调用方后令活动通知轮询省略 `result`。完成、异常与换任务返回仍需终态信息。不得损害 `NativeTurnLifecycle` 的会话核对。

先测 Binder payload/分配量。新增第二种 IPC 及兼容逻辑的维护成本可能超过收益；验证通知结束、失败、任务替换、确认按钮刷新和旧请求收尾。

## BC-5：已取消 watchdog 定时节点的暂留

优先级：测量后再定。规模有限。

`BridgeProvider.java:22,215,236` 使用默认单线程 scheduled executor，每步创建预算定时任务，结束时取消。标准 `ScheduledThreadPoolExecutor` 默认不立即移除取消任务；大量快速步骤可能让取消节点暂留到原期限。

仅在 heap profile 证实累计明显时，评估显式 `ScheduledThreadPoolExecutor(1)` 配合 `setRemoveOnCancelPolicy(true)`。不得改变每步预算、超时回调与取消语义。同任务串行且最多 200 步，不宜凭推测增加实现复杂度。

## BC-6：帧管道写入借用公共 CompletableFuture 执行器

优先级：测量后再定。资源与错误处理风险：中。

`BridgeProvider.java:349-360` 创建 pipe 后使用 `CompletableFuture.runAsync` 写帧字节；读取端慢或不读取时写线程可能阻塞。本轮没有公共池已成为瓶颈的证据。

若 profile 显示影响同进程公共池任务，可考虑专用、受限的帧 IO 执行器，同时设计队列界限、拒绝后的两端 FD 关闭与异常清理。不可仅换成无限线程池。

`FrameStore.take` 在 `BridgeProvider.java:353` 已消费一次性 token，不得为重试性能允许重复读取、跨 UID 读取或延长 token 寿命。验证正常读取、并发消费、错误 UID、已过期 token、消费者提前关闭和执行器拒绝。

## 已有优化与不值得优先重写的部分

- `EndpointProfiles.java:12,102-104` 已用 flat snapshot 保存运行态配置，`Config` 不会每次解析 profile 列表。
- `EndpointProfiles.java:89-104` 将集合与 active snapshot 放入调用方同一个 editor，避免配置切换产生混合凭证。`EndpointProfilesTest.java:125-136` 验证请求中旧快照与下一请求新快照各自一致。
- `EndpointProfiles` 的双层 JSON 字符串编码、线性 `find` 仅在配置设置读取/保存路径。没有数量或频率证据时，不值得迁移格式。
- `NativePhoneUseTool.java:71-77` 安装阶段已复用解析后的 schema 和工具定义。
- `NativeTurnLifecycle.java:17` 已预编译 session 正则；`132-146` 的绑定表上限 64，锁内仅少量 Map 操作，IPC 在锁外。
- `NativeTurnLifecycle.java:111-121` 只对以 `{` 开头的 envelope 解析 JSON；普通 CONTINUE 走字符串头部解析，不能描述为每次全量 JSON 重解析。
- `BridgeProvider` 已串行执行任务、回收 JOBS 历史、使用小 Bundle 和图像 pipe，避免 Binder Bundle 直接传图像。
- `BridgeProvider.java:269-315` 已区分定时状态与真实后台 probe，不得为了复用代码将 probe 放进状态查询。
- `NativeSession.java:379-385` 的取消清理异步排队等待执行退出；Provider 持有 JOBS 调用取消不等于直接等待整个控屏执行。不可未经锁追踪就声称存在长锁或死锁。

## 禁止为性能放宽的边界

- 不缓存“曾通过 UID 校验”以跳过 `enforceCaller`；不降低 root/module 专用方法权限。
- 不移除 `verifyContinuation` 的 UID、session、来源工具及期限检查，也不能先分配任务再验证过期续调。
- 不把 screen_task、session、request identity 合并成宽泛的当前会话；旧请求不得取消新任务。
- 不合并每步观察、跳过重新观察/确认或自动复用旧坐标。
- 不将错误改成可重试，不弱化 STOP、PAUSE、PERMANENT Failure、terminateAgent。
- 不用延长缓存掩盖配置变化：下一模型请求必须获取新 endpoint/key/model/api，不可静默回退其他提供者。
- 不为省锁拆散 JOBS 下的任务生命周期更新。`BridgeProviderContinuationTest` 与 `BridgeProviderConversationSettingsTest` 覆盖执行结束但清理未结束、旧任务不能影响新任务等约束。
- 不为清理内存提前清除在途帧；现有帧独立过期是明确设计。

## 后续 Session 执行顺序

1. 首先做 BC-1 的最小分发改动及行为验证，再测 Provider 分配与 CPU。
2. 将 BC-3 作为独立的小改动验证，不与注册表状态改写混在一起。
3. BC-2 先测宿主实际注册代价及淘汰语义，再决定是否实现。
4. BC-4 至 BC-6 只在对应 Binder/heap/thread profile 显示成本后推进。

所有候选均应各自提交或独立审查；保持 mock、JVM、设备与真实模型证据分离，不把历史测试数作为新改动通过证明。
