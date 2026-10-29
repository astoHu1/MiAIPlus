# 候选设计、实施切口与反证

返回[总览](README.md)。以下均为待实施设计，收益未经验证；不要把示意字段当作当前工具已支持的协议。

2026-10-29 归档说明：以上“待实施”指研究时点。C0、C1、C2 后续已有部分实现，具体范围与限制见 [实施与验证记录](../../validation/phone-use-observation-2026-10-28.md)；本文继续保留原候选设计，不作为当前功能清单。

## C0：先做请求负担分解

覆盖三种组合，分别采样任务早期、现有六组裁剪前后及长任务末期。尽量复用默认关闭的诊断能力；计数应在线程内使用一次请求的确定快照，不增加屏幕采集或网络重试。

| 分项 | 要记录的纯元数据 | 不得由它推导的结论 |
| --- | --- | --- |
| 文本 | system／原始任务／最新观察／历史观察／动作结果／memory 各自字符数 | 字符等于 token，或中文与英文同一比例 |
| 工具 | 工具数、序列化 schema 字节 | 所有工具都可删，或控屏任务不再需要其他系统工具 |
| 图片 | 数量、实际 JPEG 尺寸、字节、来源类别、已允许的摘要关联 | Base64 字节等于视觉 token，或摘要证明画面正确 |
| 协议 | message／item 数、opaque output 字符量、replay hit／miss、完整组数 | reasoning 缓存是可摘要的任务记忆 |
| 请求 | 最终序列化 UTF-8 字节、上下文装配／模型完成耗时、错误类别 | 官方宿主附图数就是服务端实际请求量 |
| usage | 服务商真实 input／output／cached／reasoning，缺失为 missing | mock 常量或 tokenizer estimate 是真实 usage |

当前日志覆盖与缺口见[源码核对](current-state.md)。日志仍不收集目标、输入正文、完整树／图片／请求、凭证、端点或模型名；实验元数据中的模型版本可以另以用户明确选择的非敏感标识记录，不扩大默认日志。

**先回答：**当前瓶颈是否真是历史量？相同单步任务清空干扰历史后是否仍失败？若根因是截图错误、树遗漏或坐标协议，先修观察／协议，不用摘要掩盖。

## C1：最新状态卡与明确结果

第一补丁可只整理原生工具返回的非协议区域，暂不引入额外模型调用、不改动作 schema 或执行安全逻辑。**原样保留** `CONTINUE：控屏尚未完成。` 前缀、严格的 `\n会话=<UUID>；目标=` 头部、`MIAIPLUS_FRAME:<id>` 帧标记、顶层 `STOP：MiAIPlus 控屏已停止。`／`PAUSE：`、已知 CLI envelope，以及 Success／Failure／direct-answer metadata 行为。它们由真实消费者解析，不是可任意改写的文案；新状态卡不能把它们换成 JSON 字段、截断或藏到未知 envelope。若以后改外壳，需一起版本化所有生产者／消费者和兼容测试。

以下是概念结构，不是完整 schema：

```text
current_task              # 程序维护，身份不能由模型摘要恢复
  session / source_tool / current_screen
  original_goal / user_constraints_source
  actions_used / actions_remaining
current_observation
  observation_ref        # 可选的新协议；当前续调尚无此字段
  screen_dimensions / jpeg_dimensions / coordinate_space
  foreground / tree_truncated / tree_unavailable
last_result
  status = initial | executed | skipped | waiting_review
  action_kind / short_intent / reason
  effects_need_verification
working_memory           # 非授权数据；有预算，低于用户目标和安全规则
  observed_facts[]        # 带来源 observation/step，必要时标不确定
  completed_candidates[] # 只有证据支持才可提升完成状态
  pending_subgoals[] / unresolved_questions[]
  recent_failures[] / do_not_repeat_without_new_evidence[]
```

设计要求：

1. `executed` 只表达执行器实际完成对应动作，不等于业务目标成功；`skipped` 只用于真实未执行。部分执行以真实终止结果说明，不能吞掉失败细节；双击部分执行必须停止，不得归类为可重试的零注入。
2. 目标、会话工具、预算、实际屏幕和坐标是程序状态，不让模型自行改写。当前 `NativeSession.goal` 固定；若未来接收可信用户追加约束，需另行定义来源和会话策略，不从屏幕或摘要推导用户授权。
3. 模型只提出子目标／事实候选。程序可验证字段自行填入；不能客观确认的页面含义标为 `unverified`，不因模型自报 DONE 即升级为完成。
4. 最近失败保留“做了什么／是否执行／看到了什么／下次要核实什么”，不要求保存冗长思维链。警告重复不自动改变现有硬停止阈值，也不自动重放动作。
5. 不存可复用批准、审计 token、旧执行坐标或原始工具参数到长期摘要。当前 pending 审计挑战作为独立临时协议数据原样保留，不走自然语言压缩。
6. 第一期可只做 `last_result` 和现有确定状态；语义 facts／subgoals 另开实验，避免把一个易验收的格式补丁变成摘要系统。

## C2：模型观察与执行安全证据分离

建议引入只读 `ModelObservation` 投影视图，输入为不可变的完整 Frame；内部 `Frame.tree`、截图、PageGuard 和注入前复核继续沿用完整证据。不得把压缩后的模型树写回原 Frame。

- 先做整节点／整行预算与 `truncated` 标记，区分“模型视图裁剪”“内部树读取触顶”“后台树不可用”。内部读取到但没展示的数量，与压根没读到的数量不同，不虚构总节点数。
- 再研究类名缩写、重复属性省略、焦点编辑框／弹窗／标签／主要交互项优先。保留窗口、边界、resource ID、enabled／focus、遮挡提示及必要正文语义。标签和不可点击正文常含任务事实，不能一概丢掉。
- 文本继续转义，密码继续打码；限制单节点超长文本，保留截断提示，不能拼出伪节点。
- 稳定规则可尽量放在稳定提示／工具说明；每轮仍给短的结束信号、当前工具／屏幕／坐标、预算和当步审计说明。必须先确认宿主压缩后这些稳定规则仍能抵达模型。
- 不同时降低 JPEG 质量／尺寸、树安全预算或 PageGuard 阈值，以免把信息减少、视觉质量下降和序列化收益混在一起。

**反证：**如果精简后输入更小但同名按钮、纯图页面、底部导航或正文采集更容易失败，该策略不通过。相同完整帧和动作，在模型视图压缩前后的安全判据应完全一致；不能“没展示目标”就放宽执行。

用户补充的 [Android Remote Control MCP 源码核对](android-remote-control-mcp.md)给出更直接的 formatter／TSV／截断＋全文查询参考。建议 C2 先独立验证短类名、明确 flags、整行预算和缺失提示；WebView 归并另开实验，其 token 集合消重不证明中文顺序／否定／重复数量语义无损。

## C3：完整历史组＋有预算任务事实

优先在 `AgentEngine` 实验，因为它真正拥有完整任务消息数组；保持现有最新图／六组历史为 baseline。不要把它的数组裁剪直接套到小爱任意聊天历史。

建议每次请求构建为：**稳定规则＋原始任务／硬约束＋任务内有界事实／进度＋少量近期闭合动作组＋最新观察**。原始诊断不等于要全部发给模型；也不因此新增全量正文／截图持久化。

### 历史组与预算规则

1. 建立显式逻辑组，认识 function tool 和 AutoGLM 的不同结果形式。assistant 多调用及其所有 results 要共同处理；未闭合组不能从中间截断，也不能伪造成功结果补齐。
2. 保留组的 call ID、工具名、参数、Responses opaque reasoning／phase 原样；淘汰老组时整体淘汰。不要摘要加密 reasoning，也不要修改 retained 参数后期待 replay 命中。
3. 预算覆盖 system、原始任务、schema、当前图文、工具结果、opaque output 和输出预留。只有模型容量／tokenizer 已有可靠配置时才做 token 级判定；未知 alias 不猜窗口。
4. 已知容量下，可研究 `输入上限 = min(已验证容量 - 输出预留 - 安全余量, 任务实测有效预算)`；有效预算是待测 profile，不是厂商广告窗口。图片与 provider overhead 无法准确计数时说明估计及余量，不能宣称严格 fit。
5. 删除顺序按重要性分项：可确认无用旧图／重复观察 → 老的已闭合低价值组 → 去重、合并已消费事实。保持原始目标、最新状态、未完成约束、当前挑战和最新观察。
6. 事实需独立限条目／总量，含来源、任务相关性、不确定性及覆盖规则；不能无限 `memory += ...`，也不能仅在 prompt 写“请简短”。阈值由 pilot 决定，不先写通用 8K／16K 默认。
7. 单次压缩后重新按最终格式计量；不足时有界处理。必须保留的状态仍超限时停止／询问，不无限递归摘要、不把完整组截成碎片、不发 fallback 零值／空状态继续执行。

### 不能随窗口丢掉的东西

- 原生首轮应用目录中的必要 app→包名来源。保留已验证目标 app，或增加受限查询；不让模型猜包名／任意 Intent。
- 跨页尚未消费的信息、列表已处理项、数量／收件对象／否定条件、不可重复提交事项。
- 最近失败与“尚未执行／授权已失效／待用户决策”，不能变成泛泛“已处理”。
- 当前屏幕／display generation 与来源工具关系。旧屏坐标、旧批准无效，即使摘要还写着前一步做法。

## C4：原生历史图片，只在证实后处理

如果 C0 确认受控外部原生请求不断重传旧图，可先研究**发送副本中剥离可明确识别的旧 Phone Use 图片**，保留 tool result 文本／ID／其他消息，不改宿主持久历史或完整用户会话。

识别不能仅靠“图片处于 tool role”或通用 URL；需能绑定本任务／观察来源，并覆盖首次 ToolResult 和兼容 TOOL Message 两种附图路径。现有摘要关联缓存有限，跨压缩／进程重启不一定保留来源，不能假定已具备可靠标记。

必须隔离普通 chat、用户图片、其他工具、intent／router、原生 memory summary 和权限审计。`NativeResponsesAdapter` 拦截的外部 `/chat/completions` 不天然等于 Phone Use。官方私有传输没有同等可见度，不能写成这一补丁覆盖官方线上历史。

对照 latest-only 与“最新＋一张确有任务价值的历史证据”；历史图标明只供事实参考，不能据它复用坐标。保留张数、间隔和缓存影响待测，不照抄别项目默认。

## C5／C6：第二阶段探索

| 候选 | 可能价值 | 代价与最低保护 |
| --- | --- | --- |
| 按需 app／正文／节点详情 | 常规请求较短，复杂页再扩展 | 新工具轮次和预算；缺失可恢复；不开放任意 shell／Intent |
| 临时 target reference／observation ID | 显式表达动作基于哪帧，减少坐标混淆 | 当前协议尚无；需版本化，绑定 session／display generation，过期只返回新观察，不自动重新定位执行 |
| 树 diff | 减少重复树 | baseline 缺失时不可解，需完整快照／恢复协议；弱模型可能更难重建页面 |
| 局部裁图 | 增强小字／密集区域可见度 | 明确 crop→全屏变换，保留完整最新来源；不能偷偷更换默认坐标系 |
| 事件触发摘要／反思 | 失败或子目标切换时整理事实 | 额外调用、幻觉、摘要漂移；保留 provenance，错误时安全回退有效 baseline 或停止 |
| planner／executor | 全局目标与局部 grounding 分离 | planner 只给子目标／验收条件，executor 每步重新看最新屏幕；不重置实际 session／预算，不放宽一个动作边界 |
| 跨任务经验 | 复用一般操作经验 | 隐私、过时流程、错误强化与删除性；不列首期，不恢复任务身份／坐标／授权 |

## 一票否决的捷径

- 减少取消、超时、锁屏、安全窗、密码、CAPTCHA、UID／task／来源工具／屏幕所有权检查来换成功率。
- 后台异常静默切物理屏；仅原有显式 app-conflict handoff 可按规则交接，之后旧观察、坐标和授权失效。
- 摘要批准操作、跳过人工确认、给未完成 Responses 流补出可执行动作。
- 删除所有安全说明、默认扩大并行动作、自动重试 STOP／PAUSE 或重提 goal 以规避失败。
- 缩短输出预算冒充输入优化；当前 reasoning 与输出上限兼容逻辑应保持。
- 用缓存命中／输入 token 降低／更早 finish 单独宣称优化成功，或按供应商名称假定具体模型长上下文强。

所有候选都须按[验证方案](validation-plan.md)检查实际质量与失败恢复。优先验证无额外 LLM 调用的方案，再决定复杂架构是否值得。

用户补充项目使 C5 的按需全文／分页／selector 方向更具体，但不改变第一期范围。分页需标记原 observation 并绑定任务／屏幕；details 重取当前树时必须明确版本；selector 歧义应回候选而不猜；node hash／cursor 命中不能替代 PageGuard。按需给模型发图不等于省掉内部安全截图，复合 scroll 也需单独评审动作预算与取消语义。
