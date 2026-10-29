# 基线源码：上下文在哪里增长、信息在哪里丢失

返回[总览](README.md)。全部为 2026-10-28 静态核对；基线见总览，行号变动后按函数名重新定位。下表不代表实际请求 token 或真实模型故障已测量。

2026-10-29 归档说明：下文的“当前”均指研究基线，不是最新 main。请求计量、动作结果与模型树展示的后续变更见 [实施与验证记录](../../validation/phone-use-observation-2026-10-28.md)。

源码目录：[app/src/main/java/io/github/miaiplus](../../../app/src/main/java/io/github/miaiplus/)。

## 三种运行组合，不是同一个上下文所有者

| 组合 | 规划与历史所有者 | 模块能直接控制的部分 | 本轮不可断言的事 |
| --- | --- | --- | --- |
| 原生规划＋官方模型 | 小爱原生 Agent／消息管理 | 工具定义、`NativeSession` 返回观察、附图与任务生命周期 Hook | 官方最终线上历史、实际视觉 token、服务端是否理解图片 |
| 原生规划＋全局外部模型 | 原生 Agent 管历史；模块替换受控模型传输 | 上述工具返回及干净外部客户端／Chat→Responses 适配 | 网络层裁剪天然等价于原生历史裁剪，或所有同路径请求都是 Phone Use |
| 独立外部规划 | `AgentEngine` 自己拥有任务消息数组 | 每轮消息、历史组、图像保留、模型请求 | 当前独立循环质量已成熟，或其策略可原样移植到原生聊天 |

入口依据：`AgentEngine.runLogged():42–44,66–99`；`NativeModelHook.install():125–155,158–200`；[架构](../../architecture.md)。全局模型开启时强制原生规划；不要为规避历史问题偷偷更换规划路线。README 对独立外部规划仍有未完善提示。

## 独立循环已经做了什么

依据：[AgentEngine.java](../../../app/src/main/java/io/github/miaiplus/AgentEngine.java)，`runLogged():66–117,179–180`。

- `system` 保留安全／动作规则、所选权限策略、后台模式说明和应用目录，第二条保留原始用户任务。
- 每轮移除所有旧观察图片，旧观察文本最多 4000 字符，明确写“历史截图已移除，以最新截图为准”。
- 按三条消息删除最早 observation → assistant action → result，保留约六组；加当前观察后，模型请求可有 21 条消息。不是无限累积所有截图和文本。
- 当前观察仍是完整内部树＋最新一张 JPEG，旧文本窗口可容纳约六份 4000 字符观察，此外还有 system、目录、动作和 opaque output。**消息数有界不等于输入 token 小。**
- `outcome` 明确写上步已执行或因目标变化未执行，既进入 tool result，也进入下一观察开头；尚无长期的事实／进度摘要。
- `_responses_output` 单独保存在 assistant 历史，不能按可见文字长度推算它的成本。

潜在问题：跨页收集事实、滚动过的条目、不可重做事项会随六组窗口移走；仅缩小窗口可能恶化。AutoGLM 的结果条目为 user message，function tool 模式为带 call ID 的 tool message，历史策略应认识这两类闭合组，而不是永远假设任意轨迹都恰好三条。

## 原生每步返回的负担

依据：[NativeSession.java](../../../app/src/main/java/io/github/miaiplus/NativeSession.java)，`State:19–43`、`handleStep()` 首次观察 `64–95`、结果拼接 `235–268`。

| 内容 | 当前行为 | 可研究切口／注意 |
| --- | --- | --- |
| 目标 | 每步重发 `s.goal` | 已存在目标保持；长目标本身也有成本，不能摘要后无来源地改写用户硬约束 |
| 状态 | session、已用／剩余动作、实际屏幕、来源工具 | 应保留为当前权威状态，不能由语言摘要重建身份 |
| 规则 | 每步重发坐标、动作全集、launch、确认、安全和权限说明 | 与工具 schema 有重复；弱模型仍可能需要短提醒，不能一次删光 |
| 当前树 | 前 12000 字符，未显式标明截断，可能截半行 | 完整行预算和遗漏提示是明确的候选，不代表缺失目标可安全执行 |
| 应用目录 | 只在首次结果附加，目录自身约 12000 字符后结束 | 只留最新观察会丢可用包名；需要保留已验证的目标 app 或可查询候选 |
| 普通动作结果 | 泛 CONTINUE＋新观察；skip／审计另有提示 | 缺统一的 `last_action / execution_status`，可减少“提出了／执行了／成功了”的混淆 |
| 进度记忆 | 保存 goal、frame、预算、重复判据和审计状态，没有完成子目标／事实账本 | 适合增加有界、带来源、非授权的任务工作记忆 |

[NativePhoneUseTool.java](../../../app/src/main/java/io/github/miaiplus/NativePhoneUseTool.java):15–40 已有启动／续调／结束规则、坐标说明和动作 schema，并注册前后台两工具。不要为了省 schema 合并工具并破坏会话来源／屏幕选择语义。

## 观察并非无界原始截图／XML

依据：[ScreenService.java](../../../app/src/main/java/io/github/miaiplus/ScreenService.java):406–471,490–507。

- JPEG 已缩放到最长边不超过 1280，质量 80。它与逻辑屏幕像素不是同一尺寸。
- 树是逐节点文本，不是完整 XML；读取已使用 BFS、最多 250 节点、约 24000 字符、队列上限 1000，按真实节点身份去重。单个长节点可让最终字符数超出前置检查的近似阈值。
- 树含类名、bounds、窗口、resource ID、状态、文本／描述，已有换行／反斜杠转义和密码打码。
- 模型展示裁剪与安全读取必须分离。父容器、不可点击标签、透明遮挡及系统弹窗都可能是安全或语义证据，不能直接把内部 `dump()` 改成“只留可点击”。
- 原生结构化工具默认 JPEG `image_pixels`；支持显式 `screen_pixels`／`normalized`，旧兼容入口和独立循环默认归一化。精简提示需清楚保留当前坐标语义，不能静默统一旧接口含义。

执行安全依据：[PageGuard.java](../../../app/src/main/java/io/github/miaiplus/PageGuard.java):32–84；`ScreenService.verifyActionTarget():479–488`。模型视图可以短，实际注入前的完整目标复核不能变短。

## 图片存储、历史、压缩不是同一件事

[NativeModelHook.java](../../../app/src/main/java/io/github/miaiplus/NativeModelHook.java):45–103 将一次性截图附到原生 ToolResult／TOOL Message，没有在此裁旧历史。仅据这段不能证明最终线上每轮发送全部旧图。

[FrameStore.java](../../../app/src/main/java/io/github/miaiplus/FrameStore.java):12–66 的四张待消费上限、60 秒和一次性 UID 绑定，是 IPC 帧传输生命周期，不是模型图片窗口。`NativeSession` 只留最近 Frame 也不意味着宿主只留最近一张模型图片。

[NativeConversationLimitsHook.java](../../../app/src/main/java/io/github/miaiplus/NativeConversationLimitsHook.java):14–75 移除特定原生会话的计数门槛，保留宿主上下文压缩／取消／工具失败；这不是“长任务记忆已经解决”，也不是应继续取消其他保护的理由。

时限也有路径差异：[RunState.java](../../../app/src/main/java/io/github/miaiplus/RunState.java):9–34 默认约四分钟预算；原生 `NativeSession` 在有效续调／步骤完成续期并更新 watchdog，独立 `AgentEngine` 循环未调用 `extend()`。长任务实验需记录 deadline 失败原因，不能把该差异当成模型记忆退化，或为上下文实验顺手改超时语义。

## 协议与日志底线

- 原生返回的部分自然语言其实是机器协议：`NativeModelHook:81–103` 识别 `CONTINUE：控屏尚未完成。` 才走附图／清 direct-answer；[NativeTurnLifecycle.java](../../../app/src/main/java/io/github/miaiplus/NativeTurnLifecycle.java):99–108 解析同前缀和严格 `\n会话=<UUID>；目标=`；`FrameStore:50` 生成 `MIAIPLUS_FRAME:<id>`；[PhoneUseResult.java](../../../app/src/main/java/io/github/miaiplus/PhoneUseResult.java):15–37 识别顶层 STOP／PAUSE 与已知 CLI envelope。状态卡不能直接替换这些外壳。
- [ModelProtocol.java](../../../app/src/main/java/io/github/miaiplus/ModelProtocol.java):16–40,100–122 保留 Responses 原始 output，含 opaque reasoning、assistant phase、function call。它不是普通自然语言笔记。
- [NativeResponsesAdapter.java](../../../app/src/main/java/io/github/miaiplus/NativeResponsesAdapter.java):149–182 按 endpoint／model＋完整 call ID／工具名／参数组恢复回放；最多 64 组、约 200 万字符，是进程内协议缓存，不是持久任务记忆。
- [ResponsesStream.java](../../../app/src/main/java/io/github/miaiplus/ResponsesStream.java):57–102 仅在完整终态验证后释放动作；不完整 JSON／EOF／incomplete／failed 不能因摘要或重试变成可执行调用。
- [PhoneUseLog.java](../../../app/src/main/java/io/github/miaiplus/PhoneUseLog.java):97–108,140–145,178–215 已有会话／步骤／观察关联、图片尺寸、树字符数、请求消息与图片计数；没有可靠的逐请求 token／全阶段模型延迟统计。
- [ModelClient.java](../../../app/src/main/java/io/github/miaiplus/ModelClient.java):73–75 返回 message，会丢弃顶层 usage；`ModelProtocol.usage():133–140` 有转换能力，不等于执行器已采集统计。[mock](../../../scripts/mock_model.py):33–41 的 usage 为常量 fixture，不能拿来报告节省量。

研究可将“原生历史负担大、重复规则／树干扰目标、结果语义含混”列为合理假设，但没有真实请求和同模型 A/B，就不能写成用户问题已定位或优化已有效。
