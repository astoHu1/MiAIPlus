# 会话生命周期、返回导航与推理菜单性能审计

审计日期：2026-10-19。源码基线：`418267f87e3909fd3b526c51c525bad86b895260`。

本报告为静态审计，无本轮性能测量；所有性能收益均为待验证假设，不是已测加速结论。审计未修改后端代码、未构建、未运行测试、未访问网络或操作设备。初始及只读审计结束时 `git status --short` 均为空；文档阶段有其他审计文档并行写入，仅新建本文件。

## 范围与阅读依据

完整逐行阅读的生产文件，以下路径均相对 `app/src/main/java/io/github/miaiplus/`：

| 文件 | 行数 | 检查范围 |
| --- | ---: | --- |
| `NativeConversationHook.java` | 531 | 安装、触摸/按键、唤醒、身份刷新、待处理导航、保存、删除及重载清理 |
| `NativeConversationLimitsHook.java` | 129 | 每轮计数门禁、事件与 Continuation 桥接、专项 Agent 隔离 |
| `ConversationReturn.java` | 194 | 原始身份、当前页面快路径、后台持久化查询、挂起超时、返回导航 |
| `ConversationPolicy.java` | 40 | 活动时间、超时、忙碌、显式路由判定及同步 |
| `NativeReasoningHook.java` | 349 | 三套模式菜单、配置快照、保存 IPC、标签、弱引用及卸载 |

相关读取：`hook/Reflector.java`、`MainThread.java`、`NativeModelHook.java:18–38`、`BridgeProvider.java:140–169`、`InteractionUi.java:251–270`、调试夹具 `ConversationReturnProbe.java`。

测试阅读：`NativeConversationRoutingTest`、`NativeConversationPendingTest`、`ConversationReturnTest`、`ConversationPolicyTest`、`NativeConversationLimitsHookTest`、`NativeReasoningHookTest`；检索 `BridgeProviderConversationSettingsTest` 的行为覆盖。

已读仓库指导与证据：`AGENTS.md`、`docs/architecture.md`、`docs/validation.md`、`docs/validation/preview-1.0.1.md` 的会话连续性、菜单与返回专项。历史通过记录不作为本轮执行结果。

## 总体判断与实施顺序

本分区最值得测量的是会话身份保存的重复工作。源码可以证明同值身份会重复反射、构造 JSON 和提交 Editor，但尚不知道真实调用频率以及对帧耗时的贡献。优先做计数与主线程 profile，再做完整值未变时的保存去重；其后再拆分身份保存与时间戳保存。

菜单选择的同步 IPC/commit 可能影响点击延迟，但它是低频路径，只有测出卡顿后才值得增加异步状态机。有限反射绑定应与全局反射审计协调。其余位置未发现足以抵偿稳定性风险的优先性能修改。

## C1：重复保存会话身份，优先级为先测量、后小改

### 源码事实与调用链

- `NativeConversationHook.java:153–159` 安装 Activity 和浮窗触摸、按键 Hook；`207–220` 仅在 DOWN 时进入 `userActivity()`，没有对 MOVE 全量保存。
- `userActivity():444–451` 每次输入更新时间，非 pending 时调用 `save()`。
- `save():475–490` 每次创建 Editor，读取策略值，对会话身份执行 `TopicId/AgentId/ChatId/Type` 四个反射 getter，创建 `JSONObject` 和字符串，最后 `apply()`。
- `getTopicGroupInfo` 的 after-hook 位于 `117–128`：只要在主线程、已有身份且返回 topic 相同，就直接更新对象并 `save()`，没有检查 agent/chat/type 或完整持久化值是否变化。
- `capture():465–472` 自己读取 `getTopicGroupInfo()`。同 topic 时该读取可能先触发上述 after-hook 的保存，随后 `capture()` 再保存一次，是源码可见的同链重复机会。
- `Reflector.invoke():81–100` 为每次 getter 调用枚举类与继承方法、构造集合、选择并检查重载。因此本链的额外成本不能只按直接 Java getter 估计。

`SharedPreferences.apply()` 异步落盘，不能表述为每个输入都同步磁盘写。Android 也可能合并或跳过磁盘工作；但反射解析、JSON、Editor 创建和调度已经发生于调用线程。

### 收益假设与最小方案

先在性能构建中分别记录输入、打字按键、流式回复、历史切换场景下的 getter-hook 命中次数、`save()` 次数、实际身份变化次数、主线程 CPU/分配、SharedPreferences 排队。只有重复比例和耗时显著时才实施。

第一步提取完整不可变身份值快照，比较四个持久字段；同值刷新仍更新最新宿主对象引用，但不重新生成 JSON 或提交无变化 Editor。第二步区分“身份变化”与“活动时间变化”，普通输入只更新内存计时和对应持久字段，不重新序列化不变身份。首阶段不增加时间节流、不延迟落盘。

### 稳定性与兼容风险

不能仅比较对象引用或 topic ID。`124–125` 明确空 topic 的首次接受请求会填充 agent/chat IDs；宿主可能同 topic 返回新实例，也可能原地更新。需要比较完整持久化值，而不是把“同 topic”当“身份不变”。

必须覆盖主对话 null、删除、状态重建、失效 JSON、首次填充、显式通知/历史返回。保持“模型回复只更新身份、不延长用户活动时间”的行为，不得因缓存漏存最后用户时间。若后续引入延迟写，需要另行设计 dispose/generation/进程退出刷新，风险明显高于同值去重，本报告不建议首轮实施。

### 回归与性能验收

保留现有策略、路由、pending 测试；新增同值身份不重复提交、同 topic 的 agent/chat/type 变化可持久化、原地修改可发现、模型身份刷新不改变时间、用户时间重建行为测试。使用计数或依赖注入验证副作用次数，避免只复刻实现。

后续在另行授权的设备上回归 `--conversation-continuity`、`--conversation-return` 和 `--focus`，比较主线程 p95/p99、每次操作分配和可见帧耗时。减少调用次数不自动等于用户可感知收益。

## C2：菜单选择同步保存，次级、需要慢盘证据

### 源码事实与调用链

现代全屏 `NativeReasoningHook.java:173`、浮窗 `231–235`、旧 popup `329–334` 的选择回调调用 `save():264–268`。该方法同步 `ContentResolver.call("set_reasoning")`。`BridgeProvider.java:149–159` 校验当前 endpoint/model/api 和有效档位后调用同步 `commit()`，随后 `161–168` 构造配置结果。回调得到成功结果后才更新标签或关闭菜单。

Provider 或磁盘竞争可能使该 UI 回调等待；这是假设，不是本轮实测。用户手动低频路径不应当作模型执行吞吐主瓶颈。

### 最小方案与风险

先测点击至保存返回/dismiss 的 p50/p95、Binder 等待与 Provider commit 时长。只有存在可见延迟时，考虑将持久化放到受控工作线程，完成后回主线程更新标签、关闭或提示失败。

该方案需要处理中状态、防重复选择、菜单先关闭、anchor detach、模型切换、官方模式切换、热重载 generation 与晚回调隔离。必须成功落盘后才显示成功，不能简单将 `commit()` 改成 `apply()`；不得移除 UID、endpoint/model/api 身份和档位合法性校验。`NativeReasoningHookTest.java:72–80` 已覆盖旧菜单不能写到新模型或官方模式。

工作线程与回调必须归属卸载生命周期，避免长期保留代理、View、Context 或旧 ClassLoader。若实现成本超过已测点击延迟，保留同步设计更合适。

### 验证

沿用 `NativeReasoningHookTest` 的其他入口保持、模型档位、配置隔离和旧菜单拒绝；补充延迟成功/失败、双击、关闭后完成、切换模型后完成、reload 后晚回调。设备分别回归三个入口、官方模式恢复、键盘焦点和原生全屏动画。旧版与现代宿主的实现路径不同，不能只测现代全屏。

## C3：仅对已测热点做版本绑定的反射解析复用

C1 保存链的无参数 getter，以及 `NativeConversationLimitsHook.java:21–30,100–105` 每模型轮的 agent/extras getter，可以在确认频率和成本后考虑安装期解析精确 Method，按实际类与 ClassLoader 绑定。

这不是把所有反射判为热点。长网络请求中每轮几次 getter 通常不值得优先优化；`ConversationReturn` 的低频反射也不应先改。共享 `Reflector` 的优化由全局分区统一评估，避免本分区引入第二套不兼容缓存。

缓存必须保留当前重载选择、歧义拒绝、异常展开及两个精确宿主版本绑定；不能使用只有方法名而没有类身份/参数形状的全局键。卸载要释放 Method/Class/ClassLoader 引用。优先减少无用上层调用，再讨论其余解析复用。

## 已有优化与应保留边界

### 会话与唤醒

- `NativeConversationHook.java:207–220` 已仅记录触摸/按键 DOWN。不能粗时间节流吞掉最后活动时间。
- `475–502` 仅保存身份元数据，不保存正文；`ConversationPolicy` 是常数时间、无后台轮询的短同步策略，没有证据支持删锁。
- `324–351,431–449,506–524` 保留 10 秒 pending 期限、输入序列、generation 和 disposal；不应为去重省略。
- `399–403` 用真实 Lifecycle RESUMED 判断导航，不以可能过渡滞后的 foreground 标志代替。`NativeConversationRoutingTest.java:99–127` 覆盖 HOME pause、finish 和缺失 lifecycle。
- `124–125` 的首次 agent/chat 身份填充与回复不延时语义是身份缓存设计的关键。

### 明确否决：前移 launcher gate 以跳过 settings IPC

`onUserWake()` 在 `327` 读取设置，`applyPending()` 在 `346` 再读。两次读取跨越 UI 生命周期，期间控屏可能开始、超时配置被关闭或 Provider 不可读，它们不能直接合并为一次或用旧忙碌值替代。

看似可以将 `launcherPending && !launcherResumed`（`356`）提前到第二次 IPC 之前，但 `NativeConversationPendingTest.java:47–80` 明确要求 launcher pending 时的 busy/disable/unavailable 立即丢弃导航。前移会绕过失效检查、保留原应丢弃的 pending，并可能影响显式路由优先和后续回放。因此该优化已否决，不应作为低风险候选交给实现 Agent。

### 推理菜单

- `NativeModelHook.java:32–38` 已有两秒 UI 配置缓存；菜单入口显式 invalidate 为及时反映外部配置。不能未经测量删除失效点。网络 `requestConfig()` 的即时刷新由模型分区评估。
- `NativeReasoningHook.java:27–52` 用弱引用/弱键跟踪菜单与标签，卸载关闭菜单并恢复标签。
- `113–132,149–189` 现代全屏只在工厂同步作用域用 ThreadLocal，保留原生菜单、动画、键盘与定位；不应以自制 popup 换取局部省事。
- 固定少数档位上的 stream、Boolean 数组和 label 遍历规模小；没有 profile 支持时不值得先改成循环或复用 View。

### 原始对话返回

- `ConversationReturn.capture():41–72` 当前页面精确 identity 匹配优先，未命中才持久化查询。`InteractionUi.java:265–270` 仅新 task 捕获，续步不重新查询。
- `ConversationReturn.java:113–152` 查询拒绝主线程，挂起等待上限三秒。不能缩短失败策略后转到错误对话，不能用当前可见页面猜归属，不能跨任务缓存返回 Intent。
- `Scope:22–38` 仅保留 identity 字符串，嵌套恢复和并行隔离已有 `ConversationReturnTest.java:26–81` 覆盖；不是可随意移除的小分配。
- `navigation():170–191` 捕获原始页面后异步导航；`InteractionUi.java:251–263` 为待导航回调保有清理所有权。缓存或异步优化不能绕过 quiet/generation/Activity 存活规则。

### 原生会话轮次限制

- `NativeConversationLimitsHook.java:54–55` 只有 count > 60、非恢复空 context、属于三个聊天 Agent 时进入替代路径。
- 每轮事件 ID、UUID 和 Continuation proxy 保持真实身份与挂起桥接，不建议池化或复用。
- `sendIteration():122–125` 保留立即/挂起、失败/取消语义。`NativeConversationLimitsHookTest.java:9–68` 覆盖 Agent 范围、话题创建 gate、立即发送、挂起返回、取消、发送失败。旁路事件发送或伪造轮次会破坏语义，未见值得优先实施的低风险优化。

## 历史证据与后续验收限制

`docs/validation/preview-1.0.1.md:94–109` 记录会话连续性的真实宿主路由、真实浮窗、Launcher、状态重建与焦点回归，同时注明不是实体按键/热词、进程重启或任意多窗口验证。`136–140` 记录现代全屏菜单原生动画与 mock 参数回归；`178–186` 记录旧版浮窗菜单与 IPC。`224` 记录完成通知精确返回最初对话及每任务独立 PendingIntent。

这些是既有文档记录；本轮没有重跑，不能将历史测试数写作当前优化通过。性能改动应保持 Android 13+、API 102、两个精确小爱版本门禁、UID/任务/会话身份、取消、超时及卸载规则，且需要独立验证其他 ROM 或未复测旧绑定。

推荐实施者先做 C1 的计数与 profile，单独提交同值保存去重，再在数据证明后拆分时间戳保存。C2 仅对已测点击卡顿推进，C3 与全局反射审计合并规划。不得将本报告的建议视作设备操作、构建、提交或发布授权。
