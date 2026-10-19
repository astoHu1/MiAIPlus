# 诊断日志后端性能静态审计

日期：2026-10-19。源码基线：`418267f87e3909fd3b526c51c525bad86b895260`。本轮只做静态审计，无本轮测量、构建、测试、设备或网络操作；以下收益是待验证假设，不是实测结论。本文件是交接记录，不授权执行设备测试。

## 覆盖与调用链

全文检查 `app/src/main/java/io/github/miaiplus/` 下的 `PhoneUseLog.java`、`DiagnosticLogStore.java`、`DiagnosticLogState.kt`，以及对应的 `PhoneUseLogTest.java`、`DiagnosticLogStoreTest.java`。补充检查 `DiagnosticLogPage.kt` 刷新/筛选、`BridgeProvider.java` 日志 IPC、`FrameStore.java` 与 `NativeModelHook.java` 日志调用；阅读仓库指南、`docs/architecture.md`、`docs/validation.md`、`docs/phone-use-logging.md`。

主要链路及锚点（行号对应上述基线）：

- 模块事件：`PhoneUseLog.event():97-128` → Logcat → `DiagnosticLogStore.append():51-71` → 独立 worker 轮转写盘。
- 宿主事件：同一 `event()` → delivery 有界队列 → `BridgeProvider.call():60-78` 验证 UID 并重新标记 source → 模块 store。宿主开关由 `PhoneUseLog.enabled():52-65` 同步 IPC 查询，成功结果缓存 1 秒。
- 观察摘要：`PhoneUseLog.frame():139-146` → `rememberImage():147-153` → 最近 32 个摘要/Trace；`request():178-216` 重新检查序列化请求并关联图片。
- 页面数据：`DiagnosticLogPage.kt:100-104` 可见前台每 2 秒 → `DiagnosticLogState.refresh():38-46` → `load():48-64` → `DiagnosticLogStore.snapshot():75-85`。
- 导出：`DiagnosticLogState.save()/share():75-96` 在 IO dispatcher → `DiagnosticLogStore.exportZip():95-123`；先有序快照，再在 writer 之外压缩。

最初 `git status --short` 无输出；文档阶段看到共享的 `docs/audits/` 未跟踪目录，未触碰其他 Agent 文件。下列建议均未实施。

## 候选一：模块事件复用 JSON 序列化结果

**事实与位置。** `PhoneUseLog.java:110-112` 在模块路径对同一个 record 连续调用两次 `toString()`，分别交给 Logcat 和本地 append。宿主路径先输出基础 record，再添加 `hostDropped`/`hostDeliveryErrors` 字段并序列化（`:115`），具有不同内容。

**最小改动。** 仅模块路径复用一个局部字符串；宿主保持基础 Logcat 字段与持久记录累计计数字段的现有区别。不要顺便重排日志流程。

**收益假设。** 详细日志开启时每个模块事件少一次 JSON 序列化与字符串分配；关闭时无收益。优先级 A，改动小，但还没有吞吐或分配数据。

**风险与回归。** 保留 Logcat 失败时的现有异常隔离，不改变 action 结果；不要为了复用字符串而把宿主计数提前加入原 Logcat 输出。复核两来源字段、隐私字段排除和存储内容；沿用 `PhoneUseLogTest.java:52-70,158-166`。

## 候选二：同一观察只计算一次图片摘要

**事实与位置。** `PhoneUseLog.frame():139-146` 先调用 `rememberImage(frame.image())`，后者在 `:150` 计算 `reference(image)`；随后 `frame()` 在 `:145` 对同一 Base64 再次计算 SHA-256。`reference():129-137` 每次将整个字符串转为 UTF-8 byte[]，再生成 SHA-256 摘要。

**最小改动。** `frame()` 先计算 digest，私有 `rememberDigest(digest, trace)` 负责插入和淘汰；保留现有 `rememberImage()` 供宿主附图链路使用。只复用本次 digest，不缓存 Base64 或图片。

**收益假设。** 每个观察少一次与 Base64 长度成正比的编码/哈希及临时字节数组；图片越大越可能有可见收益。优先级 A。

**风险与回归。** 保持 SHA-256 前 8 字节的 16 位十六进制格式、相同 Trace、插入/淘汰语义和 32 项上限。不得通过缓存原始图片突破“只保留摘要、不保留图片副本”的隐私/内存边界。沿用 `PhoneUseLogTest.java:111-166`，加覆盖同一 frame 的 observation 与 request_image 摘要/Trace 一致、超过 32 项淘汰顺序不变。

## 候选三：无变化刷新跳过文件读取和 JSON 解析

**事实与位置。** `DiagnosticLogPage.kt:100-104` 在 RESUMED 且 follow 开启时每 2 秒刷新。`DiagnosticLogStore.snapshot():75-84` 无条件在唯一 writer worker 上重读所有保留文件，最多 4×512 KiB；`DiagnosticLogState.load():48-57` 无条件解析全部 JSON 并构造记录列表。日志关闭、日志没有新增时仍重复该工作。解析已经在 IO dispatcher，不能描述为当前在主线程解析。

**最小改动。** 增加有序 `snapshotIfChanged(previousToken)` 或同等接口；未变化时不读文件、不解析、不替换 entries。token 必须覆盖成功写入、clear、dropped/errors 变化，并保留进程启动后首次完整读取。导出仍取完整有序 snapshot。

**收益假设。** 日志静止时减少 IO、JSON 分配、列表构造和 writer 队列占用；日志持续追加时不保证明显受益。优先级 B，先测接近 2 MiB 时的刷新耗时与分配。

**风险与回归。** 不可仅用长度/mtime 判断：轮转后可能长度相同，丢弃计数不改文件。不可只取最近 500 条到 State，否则改变“全保留记录筛选、最近 500 条显示”的现有查询语义（`DiagnosticLogPage.kt:122-124,154`）。clear 必须使缓存失效；写失败/队列满的 health 仍更新；新实例首次读取必须看到已有日志；不能让旧缓存覆盖新 clear 后的状态。

## 候选四：宿主开关失败冷却与并发查询合并

**事实与位置。** `PhoneUseLog.enabled():52-65` 在调用线程同步 `ContentResolver.call()`。成功结果缓存 1 秒；异常在 `:65` 直接返回 false，没有更新检查时刻，故 Provider 持续不可用时后续调用会立即重试。多个线程同时看到缓存到期也可以重复发起 IPC。正常关闭仍可能每秒同步查询一次；当前代码不能保证调用线程总为后台线程。

**最小改动。** 首先评估失败冷却和非阻塞 single-flight；保留现有最多 1 秒开关缓存范围。若进一步改异步刷新，需要单独设计“查询未完成/上次启用/重载作废”等状态，不能当作无风险小修。

**收益假设。** Provider 不可用时减少重复 IPC/异常；并发 miss 时减少冗余查询。优先级 B，需要实测失败路径频率和线程分布。

**强制限制。** 不能仅在异常路径写 `remoteCheckedAt=now`：旧 `remoteEnabled` 可能仍为 true，后续缓存命中会误开启。必须原子发布 fail-closed 的 `(enabled=false, checkedAt)` 状态，并防止并发迟到成功覆盖 dispose/reload 后的状态。single-flight 等待者不应在宿主主线程等待刷新锁或 Binder 完成；保持日志失败不影响控屏结果。`disposeRemote():47-50` 的 generation/队列/弱引用清理仍有效。

**回归。** 扩展 `PhoneUseLogTest.java:72-96`：先启用成功，再查询异常，冷却期内连续调用均为 false 且查询次数有界；冷却到期恢复；多线程 miss 查询合并；dispose 与迟到回复并发不重新启用；开关关闭后不能继续传送新日志。

## 候选五：写盘批处理仅在吞吐数据支持时考虑

**事实与位置。** `DiagnosticLogStore.append():58-67` 每条事件检查目录、读取当前文件长度、打开/写入/关闭 FileOutputStream。每条任务都在单线程 worker 内，动作调用线程不会等待写盘。

**最小探索。** 先测 syscall/吞吐与 queue 深度。只有确认这是瓶颈，才考虑 worker 内维护当前文件长度，或有限度 drain 连续 append 批写；遇到 snapshot/clear barrier 必须停止 drain。初期不建议引入长期打开流和延迟 flush。

**收益假设与风险。** 只对日志开启且高事件率有价值，优先级 C。批写容易改变 clear/导出顺序、丢失范围、容量轮转和故障恢复。必须维持 256 项队列有界、调用线程不阻塞、8 KiB 完整单行、4×512 KiB 总量及失败后下一条可恢复，不能扩大后台吞吐而让动作线程承担同步 flush。

## 关闭日志仍存在的开销及不可省略语义

`PhoneUseLog.scope()/bind():67-68` 无条件计算 session 摘要和 Trace；`propagate():75-77` 保留上下文包装。不能把关闭状态的 scope/bind 简单改为 no-op：`PhoneUseLogTest.java:43-50` 明确验证既有 Scope 中即时开启日志，当前任务不必重开便有正确关联。

Java 参数在 `event()` 内部 `enabled()` guard 之前求值。`FrameStore.java:25,37,48,56,61,64`、`NativeModelHook.java:271,284,288`，以及 `NativeSession.java:78,100,366` 的 token/session `reference()` 在日志关闭时仍可能执行；外部 varargs、装箱与简单字段读取也不由内部 guard 消除。

若实际数据支持，可以只在明确纯日志的重参数调用点增加 gate，或增加接收未摘要值的专用内部日志函数；不输出原 token，不改变 scope/propagate 与中途开启关联。上述多数摘要是短 token，尚无证据值得大面积修改调用点。

## 其他待测热点：请求历史图片检查

`PhoneUseLog.request():178-216` 每次请求遍历所有 message/content，并对内联图片执行 Base64 子串提取、UTF-8 编码和 SHA-256；含历史图片及最终没有匹配最近 32 项关联表的图片。长图片历史下成本随本次请求中所有图片总字节增长，而不只随最新观察增长。

先用合成请求测“图片数、总 Base64 长度、检查耗时和分配”，再与协议 Agent 讨论能否复用既有序列化流程摘要。不要增加以原 Base64 为键的缓存；它会延长图片/正文数据寿命，且绕过现有仅保存摘要的约束。`request()` 遍历正文仅判断已知 CONTINUE 标记，不应扩展为保存正文或输出 body。

## 已有优化与必须保留的边界

- 默认关闭；JSON/图片检查入口早退；模块开关读 SharedPreferences，宿主成功结果缓存 1 秒。
- delivery 与 store 各用独立单线程、256 项有界队列；队列满丢弃，不同步阻塞动作；空闲 30 秒线程退出。
- 单条最多 8 KiB UTF-8，拒绝多行/过长记录，不截断成无效 JSON；4×512 KiB 整条轮转。
- append/snapshot/clear 共用 worker 保序；ZIP 在取得快照后于 writer 之外压缩。`ordered():127-132` 最多等待 5 秒且可能拒绝/超时，应保留错误可见性。
- State IO 与解析在 `Dispatchers.IO`；只在日志页 RESUMED 时轮询；loading/busy 限制通常重入。
- 最近 32 项只保存图片摘要/Trace；重载清队列、generation 作废旧发送、清弱引用和关联表。
- IPC 先验 UID，Provider 重写 source；不允许宿主读/清空/导出私有日志。ZIP 白名单三文件；FileProvider 限定分享缓存；分享最多保留 3 包。
- 不记录目标/页面正文、输入、图片、凭证、原 token、异常消息/堆栈。动作执行、权限、确认、超时、重试和屏幕模式不因日志变化。
- snapshot 只包含模块已经接收的记录，不等待未送达宿主队列；“最新”按接收顺序，不改成事件墙钟排序。

## 测试证据及下一轮验证

本轮只阅读测试，未执行；`docs/validation.md` 中既有 364 项测试/设备记录不能写成本轮通过，也不能证明性能改进。

现有可复用测试：

- `PhoneUseLogTest.java:43-50` 开关/既有 Scope；`:52-70` 隐私、关闭后保留；`:72-96` 宿主开关与失败关闭；`:98-109` 跨线程 Trace；`:111-166` 帧一次性、摘要关联、日志失败隔离。
- `DiagnosticLogStoreTest.java:29-59` 轮转容量、跨实例、多字节拒绝、写盘恢复；`:62-78` 队列满不阻塞；`:81-101` ZIP 白名单、双来源及 clear 保序。
- `BridgeProviderConversationSettingsTest.java:77-99` 日志开关和追加 IPC 边界。

建议测量矩阵：日志关闭/开启 × 日志页关闭/打开 × 空日志/接近 2 MiB × Provider 正常/不可用。记录控屏工作线程与宿主主线程 p50/p95 时长、分配量、IPC 次数、writer 队列深度、丢弃量和快照耗时。用合成图片长度/历史条数梯度隔离摘要与请求检查成本；不用真实凭证/私人页面填充测试资料。

下一 Agent 可先独立提交候选一、二并验证同内容/同摘要，再测量候选三、四；批写与图片历史缓存保持后置。设备或真实模型验证需另获当前任务授权，并遵守仓库既有独占设备/不竞争构建约束。
