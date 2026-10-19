# 反射、Hook 公共层与热重载性能审计

日期：2026-10-19。源码基线：`418267f87e3909fd3b526c51c525bad86b895260`。仅静态审计，无本轮性能测量。

本轮只读分析期间工作树起止均为空；记录阶段仅增加本文件。未修改后端代码，未执行构建、测试、设备操作、网络访问或提交。下列“调用频率”是源码入口与循环结构事实，不是采样统计。所有收益均待测，不能据此宣称当前存在可感知卡顿或已验证加速。

## 覆盖范围与证据

已阅读仓库 AGENTS.md、[架构](../../architecture.md)、[验证摘要](../../validation.md)，并核对 [API 102 迁移说明](../../api102-migration.md) 的重载约束。

| 文件 | 覆盖 | 结论 |
| --- | --- | --- |
| [Reflector.java](../../../app/src/main/java/io/github/miaiplus/hook/Reflector.java) | 全部 127 行：查类、字段、重载选择、构造、异常传播 | 有单调用内减少枚举/分配的机会；通用跨调用缓存风险较高 |
| [Hooks.java](../../../app/src/main/java/io/github/miaiplus/hook/Hooks.java) | 全部 70 行：安装/回调、busy 计数、冻结、卸载、原始调用 | 保持重载互斥；参数附加数据惰性分配优先 |
| [HookCallback.java](../../../app/src/main/java/io/github/miaiplus/hook/HookCallback.java) | 全部 32 行：参数/结果/异常/extra 状态 | 最稳性能候选位于第 22 行 |
| [XiaoAiHook.java](../../../app/src/main/java/io/github/miaiplus/XiaoAiHook.java) | 全部 203 行：精确门禁、安装/回滚、轮询、描述/Prompt Hook、热重载 | flags 已有 2 秒缓存；不应盲目调整轮询 |
| [HostCompatibility.java](../../../app/src/main/java/io/github/miaiplus/HostCompatibility.java) | 全部 88 行：两套精确绑定与 switch 映射 | 无值得优先实施的性能改动 |
| [HotReload.java](../../../app/src/main/java/io/github/miaiplus/HotReload.java) | 全部 48 行：服务集合、单请求 gate、后台线程、结果回调 | 低频操作；线程池替换没有现实优先级 |
| [ModuleApplication.java](../../../app/src/main/java/io/github/miaiplus/ModuleApplication.java) | 全部 16 行：Application 初始化与服务监听 | 低频初始化；未发现明确性能候选 |

另完整核对 [ReflectorTest](../../../app/src/test/java/io/github/miaiplus/hook/ReflectorTest.java)、[HooksTest](../../../app/src/test/java/io/github/miaiplus/hook/HooksTest.java)、[HostCompatibilityTest](../../../app/src/test/java/io/github/miaiplus/HostCompatibilityTest.java)、[ReloadProbeRunner](../../../app/src/androidTest/java/io/github/miaiplus/ReloadProbeRunner.java) 及 [API 102 元数据目录](../../../app/src/main/resources/META-INF/xposed/)。沿调用链抽查 NativeConversationHook、NativeReasoningHook、NativeModelHook、NativePhoneUseTool、NativeResponsesAdapter、InteractionUi；这些抽查不代替相应模块的完整审计。

## 候选 R1：惰性创建每次 Hook 的 extras

建议优先级最高，实施风险低；预期节省分配，实际 CPU/GC 收益待测。

- 位置：[HookCallback.java](../../../app/src/main/java/io/github/miaiplus/hook/HookCallback.java) 第 22 行，读写入口第 29、30 行；创建调用点 [Hooks.java](../../../app/src/main/java/io/github/miaiplus/hook/Hooks.java) 第 34 行。
- 事实：所有进入模块拦截器的调用都会创建 MethodHookParam，并立即 `new HashMap<>()`。主源码中使用 extras 的仅 NativeModelHook 第 175–176、188、191 行与 NativeReasoningHook 第 115–116、129–130 行；其余 Hook 也支付空 Map 对象分配。
- 频率证据：[NativeConversationHook.java](../../../app/src/main/java/io/github/miaiplus/NativeConversationHook.java) 第 153–159 行 Hook Activity.dispatchTouchEvent/dispatchKeyEvent 与浮窗根的同名方法；第 207–220 行在 callback 内才筛选 DOWN。因此 MOVE、UP 等事件同样先经过公共分配。这里只证明按宿主输入事件调用，未测每秒次数。
- 最小方案：extras 初始 null；首次 setObjectExtra 创建普通 HashMap；getObjectExtra 在 null 时返回 null。每次 callback 独立存储，不共享、不池化。
- 收益边界：未使用 extras 的每次 Hook 少分配一个空 HashMap；不会减少参数对象、args 数组或 Map 条目分配。HashMap 构造本来就不会立即分配 table，不应夸大节省。
- 稳定性：保留 null key/value、先 get 后 set 返回 null、before/after 共享同一实例、重入/并发调用各自隔离。避免以 Map.of 替代，其 null 语义不同。对象只属于单次参数实例，无新增长期宿主引用或 ClassLoader 缓存。
- 验证：扩展 HooksTest 第 45 行附近的行为测试，覆盖未设置读取、null key/value、同键覆盖、两个连续/嵌套调用隔离；保留 early result、异常释放计数测试。随后测触摸调用的分配采样或 callback 基准 alloc/op 与 p50/p95。实际宿主输入延迟需经授权设备另行验证。

## 候选 R2：newInstance 在单次调用内复用构造器数组

建议优先级次于 R1，实施风险低，收益较小且范围明确。

- 位置：[Reflector.java](../../../app/src/main/java/io/github/miaiplus/hook/Reflector.java) 第 104–117 行，第 106 与 112 行分别调用 type.getDeclaredConstructors()。
- 事实：同一次解析对同一类枚举两遍构造器，第一遍择优，第二遍歧义校验。可先存局部 Constructor 数组，两遍复用。复杂度仍为 O(C×A)，减少一次枚举及反射返回数组/成员副本产生；不改变重载算法。
- 真实调用：[NativePhoneUseTool.java](../../../app/src/main/java/io/github/miaiplus/NativePhoneUseTool.java) 第 100 行每次 execute 创建服务，第 112 行创建成功结果；[InteractionUi.java](../../../app/src/main/java/io/github/miaiplus/InteractionUi.java) 第 404、428 行实际发布通知时创建原生 builder；NativeModelHook 第 238 行配置构造等。属于每工具执行/请求/通知级，未发现处于每 SSE delta 循环。
- 最小方案仅局部变量，无全局缓存，无额外 ClassLoader 或宿主对象生命周期。不要顺手改变重载排序、去重规则或 public/declared 集合。
- 验证：现有 ReflectorTest 覆盖接口比 Object 更具体及 null；补构造器歧义、primitive/null、不匹配和构造器抛异常测试。行为一致后测重复构造微基准；不要仅报告 lookup 时间作为整个工具加速比例。

## 候选 R3：测量后定点预绑定反射成员

属于有条件候选，风险中等；不建议全局一次铺开。

- 位置：[Reflector.java](../../../app/src/main/java/io/github/miaiplus/hook/Reflector.java) 第 27–44 行每次字段访问向父类查找并 setAccessible；第 81–100 行每次方法调用汇集所有 superclass declared 方法，再加入 type.getMethods()，随后两遍候选评分/歧义判断；getParameterTypes() 在两遍中重复调用。
- 复杂度：单次调用约 O(M×A)，并有继承层枚举、数组和列表分配；M 包含重复的 public 方法条目。源码能证明冗余工作，不能证明它是当前总耗时热点。
- 已确认重复操作：NativePhoneUseTool 第 102–105 行每次工具调用的 controlApp/getText/isError；NativeResponsesAdapter 第 54–114 行每网络请求构造/响应包装；InteractionUi 第 404–425 行每次实际通知发布的多次 builder 调用。
- 重要反证：[NativeResponsesAdapter.java](../../../app/src/main/java/io/github/miaiplus/NativeResponsesAdapter.java) 第 83–89 行取得 InputStream 后交给 ResponsesStream，并非每次读流都经 Reflector.callMethod。InteractionUi 第 355–357 行对未变化通知已有去重，不能把它全部反射标为“每 700 ms 重复执行”。
- 建议顺序：先在开发测试进程对 Reflector.invoke/field/newInstance 按调用方聚合次数、CPU 与分配；优先在真正热点所属类的 install 阶段保存精确 Method/Field/Constructor。XiaoAiHook 第 107–108 行已预解析 success/error/control，可按其模式局部扩展。
- 兼容风险：缓存 key 不能仅用方法名和参数个数；必须区分宿主实际 Class（含 ClassLoader）、static/instance、运行时参数类型及 null，保留 primitive 装箱、私有父成员、歧义拒绝和异常传播。同名类在不同加载器不等价。
- 卸载风险：不要做无界 static Map<Class,Method>。成员值也强引用 declaring Class，普通 WeakHashMap 的弱 key 配合强成员 value 不足以保证卸载。优先绑定当前代，在既有 dispose 中释放，不缓存 receiver、host Context 或 Throwable。缓存属于旧模块类时仍须确认没有逃逸到宿主持有的代理/回调中。
- 并发风险：若采用共享惰性缓存，必须处理并发首次解析、失败是否缓存、重载清理与旧调用竞争；其复杂度可能超过实际收益。定点 install 绑定可避免多数新增共享状态。
- 验证缺口：ReflectorTest 仅四个测试，未完整覆盖覆盖/继承、桥接方法、接口 default、同签名不同层、两 ClassLoader 同名类、并发首次查找及 reload 释放。未补齐前避免改通用 Reflector 行为。

## 候选 R4：flags 刷新并发合并

当前不建议优先实施，仅在 IPC 采样证明重复峰值后研究。

- 位置：[XiaoAiHook.java](../../../app/src/main/java/io/github/miaiplus/XiaoAiHook.java) 第 94–101 行。
- 当前已有 volatile enabled、flagsAt 及 2000 ms 缓存。多个 Hook 构造/Prompt 调用同时过期时可能各自发同一个 ContentResolver.call("flags")；源码只支持“存在竞态重复 IPC 的可能”，没有本轮观测。
- 最小研究方向：以短暂刷新协调及不可变 snapshot 合并请求。Binder 调用不要置于覆盖全部 Hook 的全局锁内，不要把读取失败默认为开启。
- 风险：设置生效时延、异常缓存语义、旧刷新覆盖新刷新以及重载时状态。相对 R1/R2，风险与收益比更差。
- 验证：先测过期时的并发 Binder 次数与耗时，再补并发刷新、IPC 失败、开关切换及重载测试；不能凭“缓存缺锁”直接修改。

## 已有优化与不要改动项

1. Hooks 第 28 行同步检查+计数与第 59–61 行 freeze 共同保证没有新调用越过冻结点。不能仅把 retiring 改 volatile 并移除锁，否则 check/increment 与 freeze 可竞态，卸载活跃回调。第 43 行 finally 递减及 enter/leave 外部代理计数必须保留，不以少一次 AtomicInteger 操作削弱 busy gate。
2. 不池化 MethodHookParam，不全局复用 args。before/after、嵌套调用、并发与异常隔离风险远高于已证收益。
3. Hooks 第 55–56 行 invokeOriginalMethod 的唯一调用者是 debug HookSmokeProbe 第 778 行；生产路径没有调用证据，不值得为它设计生产缓存。
4. XiaoAiHook 第 135–148 行同步任务等待使用 400 ms 轮询和 245 s 截止。改变周期影响 IPC、结束延迟与取消响应，属于跨 Provider/任务生命周期设计，不能单文件“加速”。未来若改事件通知仍需保留有界超时、通知丢失/进程死亡处理及 busy guard。
5. XiaoAiHook 第 65–92 行热重载先 freeze、检查 interaction/model idle，再逐项 dispose；精确版本不兼容必须拒绝。不得跳过清理、失败回滚、主线程禁用及 Provider 边界。
6. HostCompatibility 第 8–15、26–86 行精确版本检查和字符串 switch 已简单直接；额外 HashMap 缓存增加状态/对象，没有测量依据。
7. HotReload 第 19–46 行有 AtomicBoolean 单请求合并，线程只在用户请求时创建。改常驻线程池可能反而保留线程。services 使用并发集合和 callback 状态发布，保留其并发语义。
8. ModuleApplication.onCreate 只注册 Application 级监听，不持有 Activity。不能以启动优化为由推迟 AccessibilityGuard.restore，需由相应行为负责人核实需求。
9. 元数据 min/targetApiVersion=102、staticScope=true、exceptionMode=passthrough、autoHotReload=true 及主进程范围与当前设计一致，无性能理由更改。保持 compileOnly API、打包 service 与现代 META-INF/xposed 入口，不恢复旧资产入口。
10. InteractionUi 已每 700 ms 检查状态、仅变更时发布通知；XiaoAiHook flags 已有 2 秒缓存，success/error/control 已预解析。不要重复把这些现有优化列作待实现成果。

## 交接执行顺序与验收门槛

建议接手 Session 先独立实施 R1，再实施 R2；各自保持小 diff、行为测试与前后分配测量。R3/R4 仅在测量证明值得后推进。先核对最新 git status 和差异，避免覆盖其他 Agent 工作；不因本文存在测试方案而推定已授权设备或网络操作。

适合的统一验收包括：现有 Reflector/Hooks/HostCompatibility 测试，候选对应新增边界用例，以及仓库要求的 JVM 测试、Debug Lint 与 Debug 构建。影响两变体或发布时按 AGENTS.md 补 Release Lint/构建。每个候选分别记录构建身份、基线与变更后的分配/CPU、测量场景、样本数和局限；不要只以一次微基准证明端到端收益。

[验证摘要](../../validation.md) 的近期设备证据为 Xiaomi/API 37/小爱 8.3.30；旧版本与其他 ROM 不应因保留绑定而视为已通过本轮验证。本次仅做静态审计，没有复用历史测试数宣称本轮通过。
