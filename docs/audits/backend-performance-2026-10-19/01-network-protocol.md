# 网络与协议性能审计

日期：2026-10-19。审计基线：`418267f87e3909fd3b526c51c525bad86b895260`。本报告来自静态只读源码审计，无本轮性能测量，无 benchmark 收益数字；未构建、运行测试、调用网络或操作设备。记录阶段仅新增本文件，不修改后端代码。开始及静态审计结束的 `git status --short` 均为空。

已阅读仓库 `AGENTS.md`、`docs/architecture.md`、`docs/validation.md`。历史验证记录不作为本轮通过证据。下文“高置信”指重复工作可由源码确定，不表示已证实用户可感知收益。

## 覆盖清单

| 文件 | 覆盖与结论 |
| --- | --- |
| [ModelClient.java](../../../app/src/main/java/io/github/miaiplus/ModelClient.java#L1) 1–78 | 全文件；固定长度上传、读取上限、取消与 finally 清理已存在；完整 JSON/UTF-8 表示与传输是待测成本，不建议直接替换网络栈 |
| [ModelProtocol.java](../../../app/src/main/java/io/github/miaiplus/ModelProtocol.java#L1) 1–142 | 全文件；线性协议转换；schema 存在序列化后重新解析，列为 profile 后机会 |
| [ResponsesStream.java](../../../app/src/main/java/io/github/miaiplus/ResponsesStream.java#L1) 1–112 | 全文件；逐字符调用、心跳重复编码、完成帧反复拼接与工具调用 JSON round-trip |
| [ModelHeaders.java](../../../app/src/main/java/io/github/miaiplus/ModelHeaders.java#L1) 1–44 | 全文件；历史全量扫描及摘要生成随历史增长；别名缓存有界，不推荐先改身份算法 |
| [TransportBoundary.java](../../../app/src/main/java/io/github/miaiplus/TransportBoundary.java#L1) 1–27 | 全文件；成功路径直接执行，异常路径才展开包装；无优先性能修改建议 |
| [Endpoint.java](../../../app/src/main/java/io/github/miaiplus/Endpoint.java#L1) 1–26 | 全文件；少量正则分配，不是已证主热点；安全校验不能删减 |
| [ReasoningPolicy.java](../../../app/src/main/java/io/github/miaiplus/ReasoningPolicy.java#L1) 1–95 | 全文件；重复能力解析、动态正则编译、逐字节 String.format 可避免 |

相关测试已读未运行：`app/src/test/java/io/github/miaiplus/` 下的 `ModelClientHeadersTest.java`、`ModelProtocolTest.java`、`ResponsesStreamTest.java`、`ModelHeadersTest.java`、`TransportBoundaryTest.java`、`EndpointTest.java`、`ReasoningPolicyTest.java`。

调用链补读：`Config`、`BridgeProvider`、`InteractionUi`、`AgentEngine`、`RunState`、`NativeResponsesAdapter`、`NativeModelHook` 的相关区段；不宣称这些文件由本分报告完整覆盖。

## 候选 N01：状态 IPC 延迟构建完整 Config

**优先级：高；重复工作高置信；跨域项，由主报告与 Provider 审计归并，避免重复实施。**

- 调用链：[InteractionUi.java:350](../../../app/src/main/java/io/github/miaiplus/InteractionUi.java#L350)–386 每 700 ms 调用 `screen_state` → [BridgeProvider.java:80](../../../app/src/main/java/io/github/miaiplus/BridgeProvider.java#L80) 无条件 `new Config(getContext())` → [Config.java:31](../../../app/src/main/java/io/github/miaiplus/Config.java#L31) `SharedPreferences.getAll()` → 43–45 协议、能力识别及 `preferenceKey`。
- 事实：`BridgeProvider.java:114–132` 的 `screen_state` 不使用局部 `config`；249–257 的 `poll`、`cancel`、`screen_ready` 也不使用。它们仍支付配置快照、摘要格式化与模型正则成本。每次 preferenceKey 又有 32 次 String.format，见 N02。
- 最小方案：把确定不使用配置的分支放到局部 Config 构造之前；保留原入口 UID 门禁、任务身份校验与同步范围。采用分支延迟构造，避免先引入跨请求配置缓存及失效机制。
- 收益边界：重复工作和明确轮询频率已证；CPU、电量、Binder 延迟改善幅度未测。
- 风险：分支移动不能越过 UID/调用者检查，不能放松旧任务与新任务隔离；配置相关分支仍须即时读取新配置。
- 回归：旧任务不能读取/取消新任务；UID 拒绝不变；screen_state 正常、跨步、结束状态一致；配置写入后 flags、conversation_settings、模型请求立即读取新配置。
- 测量：同样持续时长与任务状态下，对比 Provider 分配量、Config 构造次数、CPU 时间、IPC 延迟。

## 候选 N02：摘要十六进制输出避免通用格式化器

**优先级：中；重复工作高置信；改动局部。**

- 位置：[ReasoningPolicy.java:64](../../../app/src/main/java/io/github/miaiplus/ReasoningPolicy.java#L64)–70。SHA-256 摘要的 32 个字节分别执行 `String.format(Locale.ROOT, "%02x", …)`。
- 调用链：每次 Config 构造必经该函数；配置保存与 reasoning IPC 也使用它。
- 最小方案：保持 SHA-256、输入拼接、小写十六进制和 `reasoning.` 前缀，用固定十六进制字符表写入 char[64] 或预设容量 builder。
- 已证事实：每次可移除 32 个通用格式化操作；未证明摘要本身是端到端主瓶颈。
- 风险：key 格式任何变化会令已有 reasoning 偏好失联。不能改 trim、端点尾斜杠处理、摘要算法；不能共享非线程安全 MessageDigest。
- 回归：现有 `persistenceIsScopedToEndpointModelAndApi`；增加旧实现固定输入/输出黄金值、非 ASCII、空模型、多个尾斜杠、并发一致性。
- 测量：比较真实 Config 构造及 preferenceKey 的 CPU/分配，分别报告摘要计算与 hex 格式化成本。

## 候选 N03：一次 apply 复用模型能力识别

**优先级：中；重复解析高置信。**

- 位置：[ReasoningPolicy.java:74](../../../app/src/main/java/io/github/miaiplus/ReasoningPolicy.java#L74)–93：77 经 normalize→choices；78 再 canonical；87 再 choices。choices 的 named（12–13）对非精确匹配使用 String.matches。
- 调用链：[ModelClient.java:43](../../../app/src/main/java/io/github/miaiplus/ModelClient.java#L43) 与 [NativeResponsesAdapter.java:65](../../../app/src/main/java/io/github/miaiplus/NativeResponsesAdapter.java#L65) 均使用 apply。未知模型一次 choices 最多检查 12 个名称日期正则，同次 apply 会再做一次；列表和 canonical 也重复产生。
- 最小方案：一次调用内保留 canonical/choices 结果，完成 normalize 与 sampling 判断；其次考虑预编译日期后缀匹配或拆分公共日期后缀。无需先做全局 model cache。
- 收益边界：动态正则编译及重复解析真实存在；模型推理/传输可能远大于此成本，优先级低于 N01。
- 风险：不能把 gpt-5.1-chat-latest、未知 fast 后缀、服务别名识别为已知能力；Qwen 只有 Chat 开关；默认档位仍须去除宿主非法采样字段。
- 回归：现有 ReasoningPolicyTest 全部；补同模型 Chat/Responses 对照、日期后缀有效/无效、大小写/namespace、未知模型行为。
- 测量：未知模型、列表末端模型、前端匹配模型分别测 apply/Config CPU 与分配，不只选择最有利 case。

## 候选 N04：固定心跳复用 UTF-8 字节

**优先级：中；低风险分配机会。**

- 位置：[ResponsesStream.java:59](../../../app/src/main/java/io/github/miaiplus/ResponsesStream.java#L59) 每空帧编码 HEARTBEAT；67 默认心跳最终在 100 再编码。reasoning/progress 事件也经过该路径。
- 最小方案：私有 static final byte[] 预编码心跳；无内容帧直接令 pending 指向它并重置 offset。数组不对外暴露，read 只复制不修改。
- 已证事实：每个非内容事件可避免相同常量编码与 byte[] 分配；流量收益依实际事件密度，尚未测量。
- 风险：每个原本产生心跳的帧仍须及时产出；不能合并/跳过心跳破坏宿主逐行 idle watchdog；不能输出 reasoning 内容。
- 回归：`nonContentFramesReturnOnlyHeartbeatBeforeCompletion`、终止错误测试；新增连续心跳与 1 字节读取，验证 offset 不污染下一帧。
- 测量：低/高密度 reasoning 和 progress fixture，对比分配、CPU、每个心跳返回时机。

## 候选 N05：完成帧避免累计字符串复制与工具 JSON round-trip

**优先级：中；复杂度/重复工作高置信，常见单工具收益可能小。**

- 位置：[ResponsesStream.java:82](../../../app/src/main/java/io/github/miaiplus/ResponsesStream.java#L82)–95。循环 `wire += chunk(...)` 反复复制累计字符串；87 stringify→parse 复制 tool call 仅为增加 index。
- 所有权证据：[ModelProtocol.java:108](../../../app/src/main/java/io/github/miaiplus/ModelProtocol.java#L108)–109 的 tool call 是本次 message 转换新建对象，并非 response 原 function_call 对象。
- 最小方案：完成分支使用单个 StringBuilder；直接在本地新 call 增加 index，或明确复制 id/type/function 三字段。不能修改原 response，因为完成回调可能保存它。
- 收益边界：多工具调用时存在累计重复复制；工具通常单个且上游禁并行，真实收益可能小。批量工具/长 arguments 时先测。
- 风险：必须先由 ModelProtocol.message 完整验证后才生成可执行工具帧；回调、usage、finish_reason、DONE 顺序不变。
- 回归：多调用、大 arguments、usage、无 delta 最终文本、已有 delta 不重复文本；转换前后输入 response 相等；部分流绝不释放调用。
- 测量：wire 字节一致性，完成帧 CPU/分配；工具数与 arguments 长度递增时扩展性。

## 候选 N06：SSE 逐字符读取改有界分块扫描

**优先级：低；先 profile；回归风险高于前述局部分配修改。**

- 位置：[ResponsesStream.java:37](../../../app/src/main/java/io/github/miaiplus/ResponsesStream.java#L37)–45。对全部 UTF-16 字符逐次调用 BufferedReader.read、计数、CR/LF 判断和 append。
- 事实/边界：每字符 API 调用真实存在；没有证据其显著影响真实网络流。
- 最小探索：私有有界 char 缓冲与游标，分块扫描行。不能直接无界 readLine 后才检查长度，否则无换行巨行会在拒绝前分配大字符串。
- 风险：当前 2,000,000 上限计解码后字符（含 CR/LF），不是 UTF-8 字节；不得无意改变。保留 CR 过滤、跨缓冲 CRLF、多字节解码、EOF 部分行、即时 delta/心跳、close 与 IOException。已有帧不能因缓冲等待额外事件才返回。
- 回归：1 字节/随机短读输入、emoji 跨字节边界、CRLF 跨块、恰好/超过上限、无换行巨行、EOF/close/read 失败；受控阻塞输入验证首 delta 不等待 completed。
- 测量：密集小 delta 与大 completed 分别测 CPU、分配、首字节/事件延迟。内存 benchmark 不替代原生宿主流验证。

## 待 profile 项与不建议优先实施的方向

### N07：协议 schema 深复制

[ModelProtocol.java:77](../../../app/src/main/java/io/github/miaiplus/ModelProtocol.java#L77)、91–92 对 tools schema/response_format stringify→parse；每请求线性复制。可研究结构化复制或固定工具不可变模板，但浅复制会共享可变子树。`ModelProtocolTest.java:43` 明确检查转换不改输入，不能为省复制修改原 schema。先测 schema 占转换 CPU/分配比例，再设计所有权与回归测试。

### N08：原生历史 ID 扫描与摘要

[ModelHeaders.java:20](../../../app/src/main/java/io/github/miaiplus/ModelHeaders.java#L20)–27 每轮全量遍历非 SYSTEM 历史，反射读取 role/id 并生成 UUID 摘要；30–39 再全量更新别名 LRU，复杂度 O(H)。原生历史可长于独立循环历史。先记录 profile，不草率改成仅首/尾消息，避免破坏部分历史裁剪后会话连续性。任何缓存必须有界、并发安全、重载清理，不保留无界原始 ID，不让请求头泄露原始身份。回归需历史裁剪、并发会话、相同正文不同消息、空历史、缓存淘汰与重载。

### N09：端点正则

[Endpoint.java:16](../../../app/src/main/java/io/github/miaiplus/Endpoint.java#L16)–24 的 IPv4 与尾斜杠正则可预编译/简单扫描，但无足够调用量或收益证据单独立项。优先 N01 去除无关配置链工作。不得删减公网 HTTP、userinfo、query、fragment 等安全拒绝。

### N10：独立传输完整 JSON 表示

[ModelClient.java:55](../../../app/src/main/java/io/github/miaiplus/ModelClient.java#L55)、63–73 存在上传 String→UTF-8、下载 ByteArrayOutputStream→String→JSONObject 的并存内存。已有 2 MB 下载上限与固定长度上传；换流式 JSON/网络库兼容与资源风险较高。先测峰值内存与分配，不能凭感觉重写。网络库变更必须单独验证取消、超时、认证隔离、重定向拒绝、错误脱敏、连接/流关闭和不同协议。

## 已有优化与必须保留行为

- [ModelClient.java:48](../../../app/src/main/java/io/github/miaiplus/ModelClient.java#L48)–76：禁重定向、固定长度上传、8 KiB 读取缓冲、2 MB 上限、run.check 与 finally disconnect。不能为连接复用删除取消/清理。Android HttpURLConnection 可能已有底层连接池，不能仅凭每次 openConnection 宣称每轮必重建 TCP/TLS。
- [RunState.java:32](../../../app/src/main/java/io/github/miaiplus/RunState.java#L32)–47：取消/期限检查及当前连接 disconnect。不能将传输优化与放松预算混在一起。
- [AgentEngine.java:85](../../../app/src/main/java/io/github/miaiplus/AgentEngine.java#L85)–98：只保留最新截图、有限历史；113–117 保留 `_responses_output` 的 encrypted reasoning/phase，不能为省内存全部删除。
- [ModelProtocol.java:36](../../../app/src/main/java/io/github/miaiplus/ModelProtocol.java#L36)–40 回放原 Responses output；strict:false、禁并行、store:false 与输出 token 预算是兼容/稳定性行为。
- ResponsesStream 只在 completed 且完整校验后释放工具调用；failed/incomplete/EOF 必须失败；心跳不代表完成，不泄露 reasoning。
- [TransportBoundary.java:11](../../../app/src/main/java/io/github/miaiplus/TransportBoundary.java#L11)–24：成功路径几乎无额外工作，异常展开上限 16 层；保留原 IOException 身份、VM 致命异常上抛和错误脱敏，不能为了省分支削弱。
- Endpoint 保留 URL userinfo/query/fragment、公网 HTTP 与私网地址边界校验。
- ModelHeaders 的 4096 别名上限、空历史随机身份、相同提示的不同对话不合并、重载 clear 都要保持。

## 跨域补充：原生 Responses replay 锁内 JSON 工作

本项供主报告与 NativeResponsesAdapter 审计负责人归并，避免重复实施；这里只确认调用链读到的区段，不宣称全文件覆盖。

[NativeResponsesAdapter.java:161](../../../app/src/main/java/io/github/miaiplus/NativeResponsesAdapter.java#L161)–183 的 remember/restore 是 static synchronized。锁内包含 arguments JSON 解析与 key 生成、完整 output 序列化、命中 replay 的 JSONArray 解析。可研究锁外计算/解析，锁内只 map get/put、计数和淘汰；从 map 取出的不可变字符串快照可锁外解析。须先核对 clear/reload 代际、调用追踪、缓存上限精确性及多请求并发，不能直接机械拆锁。原缓存 64 组/约 2,000,000 字符与可选 replay 失败不影响完成响应的语义必须保持。

## 建议执行顺序

先确认并实施跨域 N01，再做局部 N02、N03、N04；N05 视完成帧 profile 决定。N06 与 N07–N10 先补测量，避免重构收益小于兼容风险。每个实施项分别提交、记录实际测试与测量结果；本报告的静态判断不构成构建、设备或真实模型验收。
