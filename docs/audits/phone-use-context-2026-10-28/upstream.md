# 开源机制对照与可移植边界

返回[总览](README.md)。检索／只读源码核查日期：2026-10-28。下面固定的是本轮实际读取的 commit，不声称永远是最新版本；无本项目适配、运行或收益实测。仅记录机制，不复制上游实现／提示词／模型权重或增加依赖。

## 速查

| 项目／核查版本 | 值得借鉴 | 不应照搬 |
| --- | --- | --- |
| Open-AutoGLM `86f55382982fb054e8fc98ca80609dff8a2cdc3c` | 最新图片与历史文本分开 | 旧图删除后文字仍可增长；本项目独立循环已做同类裁图 |
| DroidRun `11dcdebdec9ea99f172dd7e80af7ba1eadbeb6c8` | 可选 stateless manager、每步重建、短动作窗＋进度／记忆 | memory 追加无硬上限；前后 UI state 仍可很大 |
| MobileAgent v3.5 `81fa28b564ef7a695521500bc15a0e1324b2b394` | 事实 notes 与进度／动作分开，局部动作反思 | 多角色调用和长 plan；prompt 的“简短”不等于硬预算 |
| Browser Use `2cc71890e65056b67fdb29d98bb407749c15bb08` | 单个当前 state message，短历史描述、一次性详情 | 历史上限／摘要并非默认启用；多动作能力不适合直接移植 |
| OpenHands SDK `46b1fa5cb91ada17e6ec938c7e8624a4fa19fa98` | 只读事件投影、按实际 LLM 格式计量、合法裁剪边界 | SDK 容错丢 orphan 不能替代本项目安全失败处理 |
| Anthropic quickstarts `930ad10bb2e21a9ef97fe1a88d2037e18a24465c` | 保留 tool result 外壳剥旧图、分批裁图考虑前缀缓存 | 同仓旧 demo 有不裁图分支；缓存省计算不代表上下文更短 |
| Agent-S `0fff0e634869dab72027435b4586e62300185d41` | S2 子任务边界重规划、worker 局部窗口、任务 text buffer | S2／S3 架构不同；不能按 provider 名判断有效长上下文 |
| Android Remote Control MCP `892d1afbc2241176fc456ab4438c30909e84e0c7`（用户补充） | 紧凑节点 TSV、200 节点分页、按需全文／截图、模型树与动作缓存分离 | 是观察／执行服务器；cursor 无屏变检测，结构 node hash 不是授权身份；GPL-3.0-or-later |

## 1. Open-AutoGLM：删除历史图，不等于限制历史文字

固定来源：[agent.py:147–223](https://github.com/zai-org/Open-AutoGLM/blob/86f55382982fb054e8fc98ca80609dff8a2cdc3c/phone_agent/agent.py#L147-L223)、[model/client.py:261–275](https://github.com/zai-org/Open-AutoGLM/blob/86f55382982fb054e8fc98ca80609dff8a2cdc3c/phone_agent/model/client.py#L261-L275)。

每步把当前 app／截图加入 context 后请求模型，返回后删除本条观察的图片；历史 assistant 仍保存 thinking／answer 文本。图片移除函数仅保留 text blocks。这能压住正常连续执行的图片数量，不能据此认定文字窗口有界或任务事实不会丢失。

适配：独立 `AgentEngine` 已有最新图和窗口，新增价值主要在任务事实与完整协议组；原生链先确认最终请求。不要因本项目参考其动作格式就把上游上下文管理视为已经完整照搬。

## 2. DroidRun：工作状态与交互日志分离

固定来源：仓库名称仍为 `droidrun/droidrun`，所核查源码包为 `mobilerun/`。

- [stateless_manager_agent.py:78–110](https://github.com/droidrun/droidrun/blob/11dcdebdec9ea99f172dd7e80af7ba1eadbeb6c8/mobilerun/agent/manager/stateless_manager_agent.py#L78-L110)：最近最多五次 action／summary／outcome／error，另带 original instruction、plan、previous／current state、memory、progress。
- [同文件:215–275](https://github.com/droidrun/droidrun/blob/11dcdebdec9ea99f172dd7e80af7ba1eadbeb6c8/mobilerun/agent/manager/stateless_manager_agent.py#L215-L275)：每次新建 user message、可附当前图，更新 plan／subgoal／progress；不是不断转发原始聊天。
- [executor_agent.py:87–150](https://github.com/droidrun/droidrun/blob/11dcdebdec9ea99f172dd7e80af7ba1eadbeb6c8/mobilerun/agent/executor/executor_agent.py#L87-L150)：executor 同样短动作窗＋当前计划和状态。
- [state.py:122–130](https://github.com/droidrun/droidrun/blob/11dcdebdec9ea99f172dd7e80af7ba1eadbeb6c8/mobilerun/agent/droid/state.py#L122-L130)：`append_memory` 字符串追加，未在此实施硬预算／去重／冲突管理。[droid_agent.py:458–462](https://github.com/droidrun/droidrun/blob/11dcdebdec9ea99f172dd7e80af7ba1eadbeb6c8/mobilerun/agent/droid/droid_agent.py#L458-L462) 说明 stateless 是可选分支，不是所有模式唯一实现。

适配：学习 TaskState／facts／recent actions／latest observation 的分离，先做单模型确定性装配。不能复制五步这个数值就认为适配弱模型，也不能忽略 plan、memory、前后 UI state 的总量。

## 3. MobileAgent v3.5：事实便签不等于动作日志

固定文件：[mobile_agent_v3_agent.py](https://github.com/X-PLUG/MobileAgent/blob/81fa28b564ef7a695521500bc15a0e1324b2b394/Mobile-Agent-v3.5/android_world_v3.5/android_world/agents/mobile_agent_v3_agent.py)。

- [InfoPool:6–43](https://github.com/X-PLUG/MobileAgent/blob/81fa28b564ef7a695521500bc15a0e1324b2b394/Mobile-Agent-v3.5/android_world_v3.5/android_world/agents/mobile_agent_v3_agent.py#L6-L43) 分开 instruction、动作结果／错误、important notes、plan／completed plan、progress、subgoal。
- [executor:220–300](https://github.com/X-PLUG/MobileAgent/blob/81fa28b564ef7a695521500bc15a0e1324b2b394/Mobile-Agent-v3.5/android_world_v3.5/android_world/agents/mobile_agent_v3_agent.py#L220-L300) 收到最近五次动作及成败／错误，并提示别重复失败动作，但仍有完整计划／详细 thought 成本。
- [reflector／notetaker:311–380](https://github.com/X-PLUG/MobileAgent/blob/81fa28b564ef7a695521500bc15a0e1324b2b394/Mobile-Agent-v3.5/android_world_v3.5/android_world/agents/mobile_agent_v3_agent.py#L311-L380) 反思聚焦上次动作与前后图；notetaker 要求仅记任务相关事实，不记低层动作、不重复用户目标／进度、不编造不确定事实。

适配：把跨页需携带的值／对象／已处理项，与短动作窗口分开；来源和预算由本项目显式保证。上游提示要求合并旧 notes，不构成有界性或真实性证明；文件有 benchmark-specific 规则，不直接移植为通用 Phone Use 判断。

## 4. Browser Use：当前状态单独装配，摘要保持低信任

固定来源：[message_manager/views.py:66–99](https://github.com/browser-use/browser-use/blob/2cc71890e65056b67fdb29d98bb407749c15bb08/browser_use/agent/message_manager/views.py#L66-L99)、[service.py:153–300](https://github.com/browser-use/browser-use/blob/2cc71890e65056b67fdb29d98bb407749c15bb08/browser_use/agent/message_manager/service.py#L153-L300)、[service.py:304–506](https://github.com/browser-use/browser-use/blob/2cc71890e65056b67fdb29d98bb407749c15bb08/browser_use/agent/message_manager/service.py#L304-L506)。

system、单个 state message、临时 context 与结构化 HistoryItem 分开。每步清理临时内容；提取详情／动作返回图可以只传一次，再用短 long-term memory 表达需要保留的事实。当前 state 组合任务、历史描述、当前 browser state／图和 plan。

可选历史上限保留首项和最近项，明确说明中间历史已省略；可选 compaction 按间隔和字符条件触发。**[agent/views.py:76–77](https://github.com/browser-use/browser-use/blob/2cc71890e65056b67fdb29d98bb407749c15bb08/browser_use/agent/views.py#L76-L77) 两项默认均为 None，不能写成默认总在裁历史／摘要。** 摘要提示明确将内容视为未验证上下文，开始不等于完成，需要成功证据。

适配：工具结果分“即时详情”和“跨步事实”，少重发大树。原生宿主管历史，不能直接照抄单 state message 架构；也不移植其多动作默认，继续每步一个数据动作。

## 5. OpenHands SDK：压缩只影响请求视图，边界由协议约束

固定来源：

- [condenser/base.py:36–55](https://github.com/OpenHands/software-agent-sdk/blob/46b1fa5cb91ada17e6ec938c7e8624a4fa19fa98/openhands-sdk/openhands/sdk/context/condenser/base.py#L36-L55)：输入 view 只读，返回新 view／Condensation，避免污染缓存。
- [utils.py:8–50](https://github.com/OpenHands/software-agent-sdk/blob/46b1fa5cb91ada17e6ec938c7e8624a4fa19fa98/openhands-sdk/openhands/sdk/context/condenser/utils.py#L8-L50) 和 [llm_summarizing_condenser.py:119–171](https://github.com/OpenHands/software-agent-sdk/blob/46b1fa5cb91ada17e6ec938c7e8624a4fa19fa98/openhands-sdk/openhands/sdk/context/condenser/llm_summarizing_condenser.py#L119-L171)：使用实际 agent LLM 格式计量，包含工具 schema／security 内容，不假设摘要模型就是执行模型。
- [condenser:285–356](https://github.com/OpenHands/software-agent-sdk/blob/46b1fa5cb91ada17e6ec938c7e8624a4fa19fa98/openhands-sdk/openhands/sdk/context/condenser/llm_summarizing_condenser.py#L285-L356)、[view.py:38–50](https://github.com/OpenHands/software-agent-sdk/blob/46b1fa5cb91ada17e6ec938c7e8624a4fa19fa98/openhands-sdk/openhands/sdk/context/view/view.py#L38-L50)、[tool_call_matching.py:15–95](https://github.com/OpenHands/software-agent-sdk/blob/46b1fa5cb91ada17e6ec938c7e8624a4fa19fa98/openhands-sdk/openhands/sdk/context/view/properties/tool_call_matching.py#L15-L95)：裁剪范围与合法 manipulation boundary 对齐，action／observation 配对、batch／loop 原子性共同限制边界。

适配：学习不可变请求投影和完整调用组，不把数组任意位置当合法边界。本项目还需单独保护 Responses opaque reasoning／phase；通用 tool-call 匹配本身不保证其合法回放。上游半预算附近是范围选择目标，不是摘要后必然精确低于半预算的承诺；hard reset／丢孤立事件也不应直接成为控屏容错策略。

## 6. Anthropic quickstarts：旧图策略与缓存目标可能冲突

固定来源：[best-practices/formatters.py:1–94](https://github.com/anthropics/anthropic-quickstarts/blob/930ad10bb2e21a9ef97fe1a88d2037e18a24465c/computer-use-best-practices/computer_use/formatters.py#L1-L94)、[best-practices/loop.py:382–439](https://github.com/anthropics/anthropic-quickstarts/blob/930ad10bb2e21a9ef97fe1a88d2037e18a24465c/computer-use-best-practices/computer_use/loop.py#L382-L439)、[旧 demo/loop.py:118–146](https://github.com/anthropics/anthropic-quickstarts/blob/930ad10bb2e21a9ef97fe1a88d2037e18a24465c/computer-use-demo/computer_use_demo/loop.py#L118-L146)。

新 formatter 只定位 tool_result.content 内 image block，以文字占位替换旧图，保留外壳／ID；按间隔成批裁图以少改旧前缀，并可因序列化 MB 超限强制裁图。新 loop 先处理 compaction／图像，再设缓存断点；未执行的中断工具也需真实错误结果来维持协议。

同仓旧 demo 在直连 Anthropic 且启用缓存时将裁图数量置零，即不裁图，理由是缓存成本。因此不能写“Anthropic 一律只留最近 N 图”。缓存可降低重算／计费，却不让模型不再面对旧图；对弱长上下文任务，成功率和旧图误用仍要独立测量。

## 7. Agent-S：按子任务隔离有参考价值，架构没有唯一答案

固定来源：[S2 agent_s.py:173–314](https://github.com/simular-ai/Agent-S/blob/0fff0e634869dab72027435b4586e62300185d41/gui_agents/s2/agents/agent_s.py#L173-L314)、[S2 manager.py:100–254](https://github.com/simular-ai/Agent-S/blob/0fff0e634869dab72027435b4586e62300185d41/gui_agents/s2/agents/manager.py#L100-L254)、[S2 worker.py:81–210](https://github.com/simular-ai/Agent-S/blob/0fff0e634869dab72027435b4586e62300185d41/gui_agents/s2/agents/worker.py#L81-L210)。

S2 显式维护 completed／remaining／failed 与当前 subtask，在开始、失败、子任务完成后重规划；子任务结束／失败时重置 executor 的局部历史。worker 默认八步局部窗口，带总目标、当前／未来／完成任务；额外 reflection、计划及 DAG 转换都有 LLM 调用成本。

[同 commit 的 S3 worker.py:24–206](https://github.com/simular-ai/Agent-S/blob/0fff0e634869dab72027435b4586e62300185d41/gui_agents/s3/agents/worker.py#L24-L206) 明确直接执行主任务、无需 hierarchical planning；其长上下文分支按 `anthropic/openai/gemini` engine type 保全部文本、只滚动旧图，其他分支删完整轮。**不能把 S2 manager 写成 Agent-S 所有版本统一方案，也不能照抄 provider 名称分类**；OpenAI-compatible endpoint 可能实际接弱长上下文模型。

适配：先借鉴本任务状态账本与子目标切换，不重置真正 NativeSession／预算／授权。completed 必须与观察证据／不确定性分开，外部经验／摘要不拼入可信安全指令。跨任务 narrative／episodic 记忆涉及隐私、过时经验和删除性，不列首期。

## 8. Android Remote Control MCP：先让每次观察更容易读

用户补充项目，详见[专门核对与适配笔记](android-remote-control-mcp.md)。固定 commit 的源码确认紧凑 TSV、普通节点文字截断＋按需全文、分页与可选截图；这些机制更直接支撑 C2／C5，而不是模型长期记忆。

需要保留几个反例：默认 `include_offscreen`／`include_hierarchy` 都是 true，非最小输出；WebView 合并节点正文不适用普通 100 字符上限，200 节点并非 token 硬上限；分页 snapshot 无自然屏变检测；结构 node hash 不包含页面文字和观察 epoch。详情里的固定源码引用、默认值和独立中文／stale target 测试建议，应与机制一起阅读，不只抄短格式。

## 研究动机与证据范围

[Liu 等，TACL 2024，Lost in the Middle](https://aclanthology.org/2024.tacl-1.9/) 在多文档问答／键值检索中研究相关信息的位置效应；支持“大窗口不能单独代表有效利用能力”这个研究动机，**不直接证明 GUI 操作、中文长任务、2026 年任一模型或本项目压缩方案的提升**。

这些源码共同展示了几种可检验机制，不构成本项目质量保证。最值得先试的交集是：最新可执行观察独立、短动作窗、有预算的跨页事实、完整工具调用组、真实失败状态；多角色／每步 reflection／长期 memory 都需另计成本。

## 许可证核对

下列仅为固定 snapshot 根许可证的核对；以后复制具体文件／提示／依赖时仍须逐项检查并维护 notices。

- Apache-2.0：[Open-AutoGLM LICENSE](https://github.com/zai-org/Open-AutoGLM/blob/86f55382982fb054e8fc98ca80609dff8a2cdc3c/LICENSE)、[Agent-S LICENSE](https://github.com/simular-ai/Agent-S/blob/0fff0e634869dab72027435b4586e62300185d41/LICENSE)。
- MIT：[DroidRun LICENSE](https://github.com/droidrun/droidrun/blob/11dcdebdec9ea99f172dd7e80af7ba1eadbeb6c8/LICENSE)、[MobileAgent LICENSE](https://github.com/X-PLUG/MobileAgent/blob/81fa28b564ef7a695521500bc15a0e1324b2b394/LICENSE)、[Browser Use LICENSE](https://github.com/browser-use/browser-use/blob/2cc71890e65056b67fdb29d98bb407749c15bb08/LICENSE)、[OpenHands SDK LICENSE](https://github.com/OpenHands/software-agent-sdk/blob/46b1fa5cb91ada17e6ec938c7e8624a4fa19fa98/LICENSE)、[Anthropic quickstarts LICENSE](https://github.com/anthropics/anthropic-quickstarts/blob/930ad10bb2e21a9ef97fe1a88d2037e18a24465c/LICENSE)。
- GPL-3.0-or-later：[Android Remote Control MCP LICENSE.md](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/LICENSE.md#L4-L20)；额外许可与适配范围见补充笔记，不能视为 MIT／Apache 项目。
