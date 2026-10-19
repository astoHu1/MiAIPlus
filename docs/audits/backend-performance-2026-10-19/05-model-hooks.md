# 原生模型 Hook 与路由性能审计

- 审计日期：2026-10-19。
- 源码基线：`418267f87e3909fd3b526c51c525bad86b895260`。
- 性质：静态只读审计，没有本轮性能测量；候选项不代表已证明存在用户可见瓶颈，也不代表已实现。
- 审计期间未修改后端代码，未构建、运行测试、访问设备或网络。仅为交接新增本文档。
- 开始及完成源码审阅时 `git status --short` 均为空。本文档写入前再次核对为空。
- 已查阅 `AGENTS.md`、`docs/architecture.md`、`docs/validation.md`。历史 364 项测试与设备记录仅为已有证据，本轮没有复验。
- 下文生产源码位置除特别说明外均相对 `app/src/main/java/io/github/miaiplus/`；位置对应上述基线。

## 覆盖范围

逐行检查指定 6 个生产文件，共 751 行。

| 文件 | 覆盖位置 | 检查范围与结论 |
| --- | --- | --- |
| `NativeModelHook.java` | 1–345 | 配置同步 IPC、工具结果转换、图片附加、客户端复用与释放；存在小优化及跨模块合并 IPC 候选 |
| `NativeResponsesAdapter.java` | 1–184 | 请求/响应适配、流生命周期、回放键与有界缓存；存在无效序列化和锁范围候选 |
| `NativeIntentHook.java` | 1–67 | 精确路由、认证清理、配置、Session 标识、响应转换；存在重复解析 |
| `NativeChatRoutingHook.java` | 1–38 | 提交 fail closed 与原生路由；不建议移除配置刷新 |
| `IntentProtocol.java` | 1–60 | 请求净化、上下文转换、强制 FALLBACK、响应 schema；存在重复解析 |
| `PhoneUseRouting.java` | 1–57 | 工具来源、屏幕选择、续调校验；实现简单，未发现值得单独实施的性能候选 |

辅助阅读了 `ForegroundConsent.java`、`hook/Reflector.java`、`NativePhoneUseTool.java` 的相关入口、`BridgeProvider.java` 的配置与路由入口、`FrameStore.java`、`PhoneUseLog.java` 的相关日志入口及 `ModelProtocol.java`。这些辅助阅读不替代对应模块的完整审计。

## 建议实施顺序

| 顺序 | 候选 | 成本事实置信度 | 收益证据 | 风险判断 |
| --- | --- | --- | --- | --- |
| 1 | 无回放键时跳过 output 序列化 | 高：确有先序列化再丢弃 | 待测 | 低，保持分支语义即可 |
| 2 | Intent 请求三次 JSON 解析合为一次 | 高：同一 body 明确解析三次 | 待测 | 低至中，需要明确局部对象所有权 |
| 3 | 不含目标 key 时跳过 metadata 复制 | 高：无条件 Map 复制 | 待测，预期较小 | 低，需要保留原 Map 不变 |
| 4 | 终止 gate 一次分类与延后正文读取 | 高：重复分类/字符串化 | 待测，预期较小 | 低至中，停止和 observe 顺序敏感 |
| 5 | 回放缓存转换移出全局锁 | 高：锁内有线性转换 | 无并发争用测量 | 中，先测再做 |
| 6 | 合并同请求 flags/model_config IPC | 高：两次同步 Provider 调用 | 无 Binder 时长测量 | 中至高，涉及新鲜配置和安全边界 |

## 1. 无工具回放键时跳过完整 output 序列化

**源码与调用链**：`NativeResponsesAdapter.intercept` 非流式分支 `102–103`，或流式分支 `86` 的 completed 回调，进入 `remember` `161–172`。`164` 先计算 `key(model, output, false)`，随后同一语句无条件执行 `output.toString()`；`165` 才检查 `key == null`。

`key` `149–159` 只收集 `function_call`。纯文字或纯 reasoning 的 completed 响应没有缓存键，却仍产生完整 output 字符串后立即丢弃。长文本与 encrypted reasoning 同样受影响。

**最小改法**：先计算 key，若为空立即返回，再计算 value。预期精确减少该路径的一次完整 output 序列化及字符串分配；实际时间和内存收益待测。不要改变已有调用参数规范化、缓存命中或淘汰逻辑。

**风险与不变量**：optional replay 失败仍不能改变已完成响应；只缓存完整输出；不放宽 64 组、200 万字符及单组 100 万字符限制。

**回归建议**：纯文本/纯 reasoning 不入缓存；工具调用仍入缓存；畸形 arguments 保持 optional replay 失败隔离；边界大小与淘汰计数保持一致。

**测量建议**：output 10 KiB、100 KiB、1 MiB；分别包含 0、1、32 个 function call。比较 ns/op、分配字节、GC；先验证 key 为 null 时确实没有 output 序列化。

## 2. Intent 请求三次完整 JSON 解析合为一次

**源码与调用链**：`NativeIntentHook` HttpStatement 构造 Hook `35–37` → `IntentProtocol.request` `9–38` → 返回 Hook `46`。

`IntentProtocol` `10`、`12` 对相同 body 分别执行 `new JSONObject(body)`；Hook `46` 又解析一次，只为提取 rid。路由上下文可能包含历史，重复成本随 body 大小线性增长。

**最小改法**：保留公开 `request(String, model)` API，在内部增加接收请求局部 JSONObject 的转换入口。Hook 解析一次，先提取 rid，再转换。同一局部对象可原地净化，但必须在删除字段前保存 intent_protocol、prompt_source、context、messages。仅允许处理当前方法拥有、没有共享逃逸的对象；不要为省复制修改调用方共享对象。

**风险与不变量**：保留 v2 优先于 cloud；未明确删除的原字段仍保留；rid、私有路由字段与旧认证不能流向外部。先验证和创建干净 transport，之后才改原请求的顺序保持；失败不可半改写后继续走宿主旧服务。

**已有测试**：`app/src/test/java/io/github/miaiplus/IntentProtocolTest.java:8–44` 覆盖原提示保留、v2 context、cloud schema、rid 清理、强制 FALLBACK 与错误响应。

**补充回归**：输入 String 不变；原生消息/未知字段保留；rid Session hash 与缺失 rid UUID；v2+cloud 同时存在；畸形 body 时宿主 request 未被部分改写；默认官方模式完全放行。

**测量建议**：1/20/100 条历史，1/64/256 KiB 请求；对比解析次数、ns/op、分配字节。不得用真实聊天或凭证作为基准数据。

## 3. 不含目标 key 时跳过 metadata Map 复制

**源码事实**：`NativeModelHook.java:98–102` 对每个 CONTINUE 控屏结果，只要 metadata 为 Map 就 `new HashMap<>(metadata)`，即使没有 `cli_internal_direct_answer`。

**最小改法**：先 `containsKey`，仅存在时复制并删除；`param.args[4] = false` 始终执行。不能原地 mutate 宿主 Map，也不能取消 CONTINUE 特殊处理。

**风险与不变量**：其他 metadata 必须完整保留；宿主原 Map 不变。需确认构造器不依赖新 Map 的对象身份；这属于精确宿主版本适配验证边界。

**回归建议**：空 Map、不含 key、key 为 true/false/null、不可变 Map；校验原 Map 不变。其他工具结果保持原行为。

**测量建议**：按实际宿主 metadata 大小与 key 命中率测分配；预期收益较小，不宜因此引入额外通用抽象。

## 4. 终止 gate 一次分类，非控屏工具延后正文读取

**源码与调用链**：`NativeModelHook.installTurnStop` `217–226`。`220` 在识别工具之前读取所有 Success/Failure 文本；`223` 调用 `ForegroundConsent.isPhoneTool`；`226` 调用 `endsNativeToolTurn`，其 `ForegroundConsent.java:18–20` 在 terminal 文本下再次 `isPhoneTool`。CLI 分类 `ForegroundConsent.java:27` 会 JSON parse，`String.valueOf(arguments)` 在调用点也出现两次。

**最小改法**：读取 name/arguments，保存 argumentsText，调用一次原有严格分类；非控屏立即返回；控屏才读取 text。继续先执行 `NativeTurnLifecycle.observe`，然后直接用 `ForegroundConsent.endsNativeTurn(text)` 决定 STOP。

**风险与不变量**：不能扩大 CLI 名称匹配；不能在普通页面正文搜 STOP；observe 必须仍先于停止。保持 Success/Failure 与混合并行工具批次的原终止语义。

**回归建议**：非控屏工具返回 PAUSE 不停止；前后台工具；旧 `voiceassist__controlApp`；CLI 精确入口、空白分隔、伪造前缀、malformed JSON；Success/Failure；混合批次；observe 调用顺序。参考现有 `PhoneUseResultTest`、`ForegroundConsentTest`、`NativeTurnLifecycleTest`。

**测量建议**：对比非 phone、结构化 phone、CLI 非 terminal、CLI terminal 四条路径的反射调用次数、JSON parse 次数与分配；此项预期小收益，不能代替主要瓶颈测量。

## 5. Responses 回放缓存缩短全局锁范围

**源码事实**：`NativeResponsesAdapter.remember:161` 与 `restore:174` 都为 static synchronized。remember 在锁内遍历 output、解析 arguments、序列化 key/output（`149–164`）；restore 在锁内扫描完整历史、构造每个 assistant call group 的 key，再为命中值 `new JSONArray(value)`（`175–181`）。所有 effort-specific clients 共享此锁。

源码可以证明锁内有 O(历史+回放大小) 转换工作，但不能证明当前有显著争用。并发辅助推理或多请求是否产生用户可见排队，必须测量。

**方案**：先做候选 1，再测锁等待；必要时把纯 key/value 构造移到锁外。restore 在短锁内取 immutable String 值快照，锁外 parse/附加；remember 仅 map、字符计数、淘汰留在同一锁。

**风险与不变量**：`clear` 当前由 `NativeModelHook.dispose:301` 调用，不能假定切换配置会清缓存。调整锁范围时核对 dispose/reload/in-flight 写回时序，避免 clear 后旧结果重新填入；必要的 generation 机制需单独证明，不能凭感觉添加。不得共享可变 JSONArray，不得改成无界缓存。

**缓存键的确切字段**：`intercept:62` 构造 `replayIdentity = url + "\n" + model`，`63` 传给 restore，`86/103` 传给 remember；因此 `key:149–159` 中虽然形参名为 model，实际首元素是这个复合字符串。其后为有序工具调用组，每项包含 call_id、工具名及 `new JSONObject(arguments).toString()` 后的参数。这里的 URL 是改写成 `/responses` 之前的完整请求 URL；它不是单独的配置 endpoint 字段。键没有显式包含 API key、账号或 session。应保持这些确切匹配语义，不据此泛称已经实现所有供应商、账号或 endpoint 安全隔离。

**回归建议**：并发 restore/remember/clear；用实际构造的 URL 验证不同 URL 不命中、相同 URL+模型+调用组命中；模型、call_id、name、参数规范化字符串任一变化不命中；跨 reasoning intensity 命中；字符计数与淘汰不漂移；opaque reasoning 和 assistant phase 保留。另将同 URL/模型但不同 API key/账号是否应隔离列为待澄清契约，不声称当前键已区分此情形；单纯拆锁优化不应顺便改变该契约。

**测量建议**：1/4/8 并发，长历史与多 call group，记录锁等待、p50/p95、分配。不要因为源码有 synchronized 就直接改成 ConcurrentHashMap；复合字符计数与淘汰仍需原子性。

## 6. 合并同一请求 flags 与 model_config 同步 IPC

**源码与调用链**：`NativeModelHook` stream Hook `133` → `NativePhoneUseTool.addDefinition:154–162` → `available:62–66` 同步调用 Provider `flags`；随后 Hook `135` → `requestConfig:27–38` 同步调用 `model_config`。`requestConfig` 故意每次 invalidate，`config` 在类 monitor 内做 Binder 调用。

**候选方案**：与 NativePhoneUseTool/BridgeProvider 审计合并处理。研究一次 Provider 返回经过同样 UID 校验的新鲜 enabled/nativePlanner/globalModel/model 配置快照，仅在当前请求局部复用。真实工具执行处的二次授权与配置检查仍保留。

**不能采用的捷径**：延长两秒 TTL、直接删除提交刷新、跨请求复用不可靠快照。`NativeModelHook:27–30` 明确要求 endpoint 切换影响下一个请求；`NativeChatRoutingHook:23–27` 配置失败必须阻止提交，避免 Hook 异常被忽略后发送旧后端。普通 dispatcher 是否多次进入 stream Hook 需要计数实测，不能仅凭类列表推断重复次数。

**风险与不变量**：Provider UID 校验、globalModel fail closed、工具定义开启条件、执行时复核、endpoint/key/model/api 新鲜性、reload busy 状态必须全部保持。

**回归建议**：快速切换 endpoint/model/API/key；全局开关切换；Provider null/异常；reload 冻结；enabled+nativePlanner 才加工具；真实执行仍检查；同请求正确调用计数。

**测量建议**：UI send trace、Provider 调用次数和 Binder 时长，整段 Hook p95。没有本轮测量，不预估毫秒收益。

## 已有优化与应保持的不变量

- FRAME regex 已 static 预编译（`NativeModelHook:17`），不存在每次编译问题。
- Message 仅 Hook canonical constructor，避免 Kotlin synthetic constructor 重复附图（`50–52`）。
- 图片只在严格 TOOL/CONTINUE+token 路径读取；走一次性 UID 绑定管道；读入有 3 MB 限制（`270–288`）。不能缓存 token 内容绕过一次性消费，也不能异步提前返回造成历史缺图。
- Constructor/nativeStream 等安装阶段已绑定；`cleanHttp:319–343` 按 api+reasoning 复用客户端。不能改为每请求创建或无限缓存 endpoint/key。通用反射解析优化应与 `Reflector` 审计统一评估。
- Responses 流式逐步读取，不整体缓冲 streaming body；非流式有 2 MB 上限（`NativeResponsesAdapter:94–100`）。
- 回放缓存有 64 组、约 200 万字符以及单组 100 万字符限制（`165–170`），只在进程内保存；不能持久化 encrypted reasoning。
- `track`/`requests` 将响应 body 生命周期计入 reload busy，EOF/异常/close 只释放一次（`119–147`）。原子计数与同步 release 承担稳定性职责，不应为减锁删除。
- 独立客户端隔离宿主账号认证，关闭 HTTP/SSL redirect，保持超时、IOException/取消传播和失败关闭 body。
- `PhoneUseRouting` 的严格工具来源、禁止会话内覆写执行屏幕都是信任边界；不建议用 substring 或手写 JSON 字段提取替换。
- `NativeChatRoutingHook` 复用原生 MiClaw send path，保留设备、附件、历史与卡片处理；不能为减少调用绕过原生检查。
- 锁屏、安全窗口、密码、验证码、任务所有权、取消、确认和时限限制不在性能优化的可减省范围。

## 测试覆盖与交接缺口

本轮阅读了下列测试，但没有执行：

- `app/src/test/java/io/github/miaiplus/IntentProtocolTest.java` 全文。
- `app/src/test/java/io/github/miaiplus/PhoneUseRoutingTest.java` 全文。
- `ModelProtocolTest.java`、`ResponsesStreamTest.java`、`TransportBoundaryTest.java`、`NativeReasoningHookTest.java`。

`PhoneUseRoutingTest` 已覆盖显式工具覆盖默认设置、origin_tool/image 坐标续调、畸形/未知 start、禁止执行屏幕覆写，以及交接后错误工具不能取消或续期原会话。`ModelProtocolTest` 覆盖图文/工具转换、opaque reasoning/phase、未完成输出拒绝与 usage。`ResponsesStreamTest` 覆盖流式早返回、完整调用释放、心跳、提前 EOF 和失败终止。`TransportBoundaryTest` 覆盖原始 IOException 身份、隐私净化和致命错误传播。

搜索测试目录未发现直接覆盖 `NativeResponsesAdapter` 的 replay cache、track 或 isIdle 的测试入口；现有协议/流测试不能替代这些集成和并发行为的验证。这是覆盖缺口，并不等价于已经发现功能 bug。

实施时应先完成小而可证明等价的候选 1、2，再按测量决定后续项。所有新验证记录都应注明构建/提交身份、mock 或真实模型、设备范围；不能复制历史测试数为本轮通过结论。
