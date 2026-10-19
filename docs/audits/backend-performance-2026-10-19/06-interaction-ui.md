# InteractionUi 运行后端性能静态审计

审计日期：2026-10-19。基线：`418267f87e3909fd3b526c51c525bad86b895260`。静态审计，无本轮性能测量；没有构建、测试、设备或网络操作。本文记录优化候选，不表示已实施或已证实性能收益。

## 覆盖与证据边界

完整阅读 `app/src/main/java/io/github/miaiplus/InteractionUi.java`（631 行）、`app/src/test/java/io/github/miaiplus/InteractionUiTest.java`（144 行）、`ScreenTaskNotificationTest.java`（106 行），并检查相关 `MainThread`、`TaskProgress`、`hook/Reflector`、`XiaoAiHook` 工具链和 `BridgeProvider.screen_state`。已读仓库 `AGENTS.md`、`docs/architecture.md`、`docs/validation.md` 及 `docs/api102-migration.md`。下文源代码行号以此基线为准。

开始和只读审计结束时 `git status --short` 均为空；随后由根代理授权新增本篇审计文档。未修改任何后端代码。

此类虽负责交互桥接，但运行在宿主 UID，涉及通知 Binder、任务状态 IPC、观察线程、主线程与控屏生命周期。性能判断必须保留 task ownership、确认、取消、屏幕握手和原对话返回语义。

## 调用链与当前工作量

1. 兼容工具入口 `XiaoAiHook:122–133` 在工具 worker 上先 capture 原对话，再调用 Provider start，随后 `interaction.begin` → `screen_ready` → `interaction.started`。捕获对话不挤占 ready 超时。
2. `InteractionUi.begin:266–332` 在主线程建立本任务不可变返回 PendingIntent、发布通知、收起宿主全屏或系统浮窗；之后执行器才通过 ready 握手继续。
3. `started:345–386` 为当前任务建立一个单线程 ScheduledExecutor，初始延迟和固定延迟均为 700 ms。每轮调用 Provider `screen_state`（352），active 时构造 `TaskNotice` 并向主线程 post（354–355），主线程比较已发布状态（356–357）。正常无慢 IPC 时约每分钟 85.7 次轮询，固定延迟实际频率随 IPC 耗时下降。
4. **publish 不是每 700 ms 发生。** 只有进度或确认 token 与已发布状态不同才更新通知（87–89、356–357）。开始、确认状态变化和结束另有发布入口。
5. `publish:396–461` 执行权限/渠道检查、宿主反射模板与图标读取、JSON 构造、PendingIntent/action 构造及 `NotificationManager.notify`。当前调用均在主线程。
6. 只有 Provider 对相同 `screen_task` 返回 `completed=true && active=false` 才正常收尾（389–391）；Provider `refreshScreenTask:341–347` 等执行线程退出且 NativeSession 不再有 pending operation。

## IU-1：通知主线程反射查找与模板构造

优先级：测量后再决定，中等候选；置信度：重复工作事实高，性能收益未知。

### 源码事实

- `InteractionUi.publish:404` 每次创建宿主通知 builder；406–410 反射读取三个模板，406/408/411 解析 JSON，411 将 focus/large 序列化后交给宿主 `buildJsonParams`，442 再序列化整体参数。
- 425 读取 builder 图标资源；ongoing 时 428–429 再创建 controls 并读取图标资源。
- `Reflector.invoke:81–100` 每次收集整个类层级的 declared methods 及 public methods，然后两轮匹配。`newInstance:104–118` 每次两轮扫描构造器。当前没有缓存。
- 发布仅在内容变化时发生，因此这里不能按固定 700 ms 频率估算重复通知开销，优先级应低于经测量确认的截图、树、模型网络热点。

### 最小安全方案

先测分段耗时。如果 method resolution 在主线程耗时中占有可见比例，为每代 `InteractionUi` 保存精确宿主 Class/Constructor/Method binding，复用解析结果并保留当前异常传播和重载门禁。局部缓存较新增进程级全局缓存更容易释放 ClassLoader。

只有证实宿主模板/图标 getter 不含时间、实例状态或资源配置相关字段后，才考虑缓存不可变模板原文或静态图标快照。每次发布仍创建独立可变 JSON/Bundle；不要缓存整张通知、共享可变 JSONObject 或跨任务复用 PendingIntent。未核对宿主线程约束前，不把 native getter 全部移到后台。

### 收益假设与稳定性风险

潜在降低 publish 的主线程 CPU 和临时对象分配，量级未知。全局反射缓存可能保留旧 ClassLoader；模板缓存可能忽略主题、语言、资源配置变化；共享 pictures Bundle 被 `putAll`（429）修改会污染其他发布。缓存键若仅含方法名，可能改变现有重载选择与歧义拒绝行为。

### 基准和回归

以至少 100 次不同进度与确认状态发布，记录总 publish、反射解析、模板 JSON、notify 的墙钟/CPU p50/p95/p99、分配和主线程长帧；区分冷首次、热更新、敏感确认、完成卡，两版受支持宿主分别测。回归所有通知 actions、锁屏重发、热重载、主题变化、原对话返回。

## IU-2：每次发布重复注册通知渠道

优先级：低；置信度：重复 Binder 调用事实高，收益未知。

`publish:397–402` 每次检查通知权限，构建 NotificationChannel，设置声音/振动，调用 `createNotificationChannel`，然后读 importance。渠道已存在时仍走相同链。

可缓存“本代已成功注册渠道”状态，仅从后续更新路径去掉重复 create。每次仍查 `areNotificationsEnabled` 和当前 importance，保留用户运行中关闭权限/渠道后的原有失败行为。成功创建后才置缓存；channel ID 必须保持不变。渠道名称/语言/资源配置变化需有重新注册策略。

预计省一次渠道注册 Binder 调用和一次对象构造，可能收益很小。不要缓存“权限已允许”或 importance，它们是动态状态。单独测 NotificationManager Binder 次数及主线程耗时，避免与 IU-1 合并后无法归因。任务中关闭权限或渠道、更新安装保留原渠道设置应加入回归。

## IU-3：观察器投递主线程状态的有界合并

优先级：仅慢主线程积压被证实后考虑；稳定性风险高于前两项。

### 源码事实

`started:352–355` 对每轮 active 状态均构造 TaskNotice 并 post 主线程；去重发生于主线程回调（356–357）。主线程长期阻塞可能积压多个旧状态 Runnable。

Provider 已在 `BridgeProvider:126` compact progress，TaskNotice 在 `InteractionUi:78` 再 compact。重复扫描的是最多 60 code point 的短文本，单独优化该扫描优先级很低，不能因此删除宿主侧输入边界检查。

### 最小安全方案与不能改变的行为

若确有积压，可尝试单槽 pending snapshot、至多一个 queued main drain。主线程仍是已发布状态唯一拥有者，仍检查 `screenTask == watchedTask`。不能简单用 worker 的“上轮 raw state 相同”代替已发布状态：当前 publish 失败会在下次轮询重试（365–368）；锁屏误递送一次性批准按钮后会清空 shownProgress 强制刷新（118–124）。

收益是限制慢主线程期间 Runnable、旧状态分配和通知滞后，普通场景可能无明显收益。合并中间确认状态、迟到 token、瞬间失败/恢复、完成状态顺序都有风险。不要直接提高 700 ms 间隔或长退避，它同时决定确认出现、正常收尾和连接失败显示延迟。

### 基准和回归

人为阻塞主线程 2–5 秒，统计 queued callback 上限、确认出现 p95、最终进度新鲜度与结束通知延迟。覆盖 task-A→task-B、确认 A→B、确认解除、发布失败后相同状态重试、锁屏重发、停止后迟到 active 状态、完成前后台清理未退出。

## 跨模块候选：高频 screen_state 不必要的 Config 构造

请与 BridgeProvider 审计归并，避免重复实施。

`InteractionUi:352` 每轮调用 screen_state，而 `BridgeProvider:80` 在进入分支前总会 `new Config(getContext())`，`screen_state:114–132` 不使用 config。可把 Config 延迟至真正使用配置的分支，保留 `enforceCaller:60` 顺序以及 `synchronized(JOBS)` 生命周期检查。这覆盖固定频率调用，可能比通知微优化更普遍；仍需测 Config 构造、Prefs 读取和总 IPC 成本。

## 已有优化：不应重复实施或撤销

- `watcher != null` 保证单观察器（347）；固定延迟使定时任务自身不重叠（350、386）；stopWatching shutdownNow 并清空（544–548）。目前无证据需要改成长驻共享线程池。
- 仅显示内容变化才发布（87–89、356–357）。只有 notify 成功才更新 shownProgress/shownConsentToken（363、461），发布失败可以重试。
- `updating` 关闭 enableFloat/islandFirstFloat，并启用 onlyAlertOnce（413–414、454），避免进度更新反复浮动。
- 原对话 capture 在 worker、进入主线程之前（266–270）；兼容工具链在 Provider start 之前 capture（XiaoAiHook:122–133）。不要移回主线程或提前放行 screen_ready。
- 返回 PendingIntent 独立 immutable，identifier 含随机 return token（334–343）；跨步不重捕获；现代页面导航为受跟踪 decor callback，dispose 清理（251–264、610–614）。
- 观察器绑定 watchedTask，并在主线程应用前再检查任务 ID（348、356、379、384）；completedTask 只接受同任务 completed 且 inactive（389–391）。单个工具返回不等于任务终止。
- 确认 PendingIntent one-shot + immutable，URI/token 区分授权类型与请求（463–484）；接收时核对 running/token/screen_task（112–114），批准时重新查锁屏（118）。通知缓存与状态合并不能削弱这些约束。
- finish 后 10 秒静默窗口及用户主动入口例外（91–103、535–542、558–573）阻止宿主最终工具结果拉前页面。不要当成无用延迟删除。
- stop/confirm 广播接收器中有主线程同步 Provider IPC（127–133）。只确认其线程位置；取消实际耗时需跨 NativeSession.cancel 实测，不能仅凭位置宣称 ANR 或立即异步化。

## 已有回归与下一 Session 执行建议

`InteractionUiTest:26–59` 覆盖空/不变进度下确认展示、未发布 token 的重试判断；62–107 覆盖广播过滤和 PendingIntent identity；109–142 覆盖静默窗口与当前完成 token。`ScreenTaskNotificationTest:31–67` 覆盖跨步等待、清理未完成、旧任务和缺失状态；70–77 区分模型确认完成、停止、接管文案。

这些主要是状态逻辑证据，不能证明 native HyperOS 模板耗时、真实主线程 backlog、跨代缓存释放或 notify Binder 性能。设备 fixture 入口已有 `HookProbeRunner:66–73` 的 focus/conversation_return，以及 ActionConfirmation、ForegroundConsent、Reload runners；本次均未运行。

建议下一 Session 先与桥接审计合并 Config 候选，获取 release/preview 下真实热点分布，再独立实现及测量 IU-1/IU-2。只有确认主线程积压才做 IU-3。实现后运行受影响 JVM/完整 Lint/构建检查；设备与模型运行需遵守用户当时授权和仓库规则，既有文档中的命令不授权设备操作。任何优化都必须保留 screen_task 绑定、ready 握手、旧 token 拒绝、锁屏、确认超时、取消等待实际退出及用户点击后返回原对话。
