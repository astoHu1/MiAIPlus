# 后续验证：上下文变短之后，任务是否更可靠

返回[总览](README.md)。这是未来计划；本轮没有执行下面的测试、mock、设备或真实模型请求。不要修改 [validation.md](../../validation.md) 为“优化通过”。

## 先区分问题

| 假设 | 能区分它的实验 | 不能代替的证据 |
| --- | --- | --- |
| 有效长上下文弱 | 同模型／当前屏幕／schema，在容量以内增加闭合的无关历史；把硬约束放在开头／中段／最新状态 | 厂商窗口大、服务商名称或宣传 benchmark |
| 窗口不足 | 实际 usage、明确超限／截断错误、输出预留和总请求结构 | 字符／Base64 长度 |
| 视觉／协议弱 | 少历史的明确单步任务仍定位或组参数失败 | 多轮摘要是否流畅 |
| 观察／传输不对 | 授权 fixture 核对画面、新鲜度、尺寸／显示来源、树截断与附图链路 | image digest 或宿主附图日志单独通过 |
| 压缩删掉必要信息 | 合成跨页事实、否定条件和已做条目在裁剪前后能否取回 | 数组长度达标或模型说“记住了” |

## 第一层：离线装配与不变量

使用合成且无敏感数据的 transcript／树／图片 fixture，覆盖 0、1、6、7、30、200 轮以及每个阈值前后。此层无需设备或真实模型：

1. reducer 输入只读、输出确定、再次应用幂等；符合声明的消息／字符／图片预算，不污染原历史／缓存。硬预算和 tokenizer estimate 明确分开。
2. 检查真实协议序列，不只检查数组长度：保留的 assistant calls 与 results 全部匹配；多 call、pending、缺结果、未知 item 不被静默截断／伪造成功。
3. retained Responses reasoning／encrypted content／phase／call ID／参数原样回放；整组移除无孤立 output。覆盖 replay hit／miss、淘汰、进程重启、endpoint／model 切换。
4. 最新截图、JPEG 真尺寸、坐标空间、屏幕来源不变；旧图移除仍保留文本外壳和正确结果身份，用户及其他工具图片不被误删。
5. planted facts：首步给出的对象／数量／“不得提交”到第 30 步仍保留；计划、已注入、跳过、待审计、观察到效果、业务完成分开。
6. 页面伪指令不能进入可信目标；token／旧批准／旧坐标不进入可复用记忆。事实冲突／不确定／摘要为空／不合法时安全处理，不以空摘要继续。
7. 12k 临界、超长单节点、中文／emoji／换行转义、密码打码、树不可用及内部读取触顶区分正确。模型投影变化不改变原 Frame 的安全判断。
8. 摘要过程中取消／超时仍中断网络，无不完整响应动作；预算用尽后仍给最后观察，允许 finish／take_over／ask_user，禁止数据动作／自动重启。
9. 原生首轮应用目录被裁剪后仍有可信包名来源；同任务跨 app 需要可恢复查询，不得猜包名。
10. 未知模型／usage 缺失分别记 unknown／missing，不填零、不给猜测 API 参数。不借输入整理修改现有输出 reasoning 预算。
11. 原生状态卡经过实际 continuation parser、ToolResult／TOOL 附图入口、direct-answer 清理和 terminal gate；保留精确 CONTINUE／会话头／frame marker／STOP／PAUSE 与已知 envelope，不能只断言新 JSON 中含 session 或 status。
12. 若引入精简 TSV／分页／按需 details，覆盖超长 WebView 和 resource ID、默认层级祖先闭包、全文返回版本与 cursor 换 epoch；同位 cell 换正文／授权正文变化时旧 node hash 不获得执行效力。WebView 合并需断言中文顺序／否定／重复数量，不只断言 token 集合相同。背景与实验组见[用户补充项目](android-remote-control-mcp.md)。

### 可复用的源码测试入口

下表仅为本轮读过的测试，不是本轮通过记录；位置相对 `app/src/test/java/io/github/miaiplus/`。

| 测试 | 可复用边界 |
| --- | --- |
| `ModelProtocolTest.java:16–89` | 图文、工具结果、opaque output／phase、失败和 usage 映射 |
| `ResponsesStreamTest.java:35–94` | 完整终态前零可执行动作、EOF／incomplete／heartbeat |
| `NativeSessionReplanTest.java:64–301` | 目标变化、审计原观察／坐标、最后观察、预算与非法续调 |
| `AgentEnginePermissionTest.java:51–180` | 目标变化、权限、取消与后台隔离 |
| `NativeSessionLifecycleTest.java:33–121` | watchdog、旧 IPC／新任务、helper 清理所有权 |
| `NativeTurnLifecycleTest.java:10–66` | 可信 header、伪造页面 session、宿主退出归属 |
| `ScreenServiceTreeTest.java:18–55` | 节点爆炸／循环、真实节点身份、密码与转义 |
| `PageGuardTest.java:32–116` | 动态文字、遮挡／焦点／disabled、屏幕身份与纯图目标 |
| `ScreenServiceCaptureTest.java:21–42`、`CoordinatesTest.java:9–25` | JPEG 尺寸与各坐标语义 |
| `ActionConfirmationTest.java`、`ForegroundConsentTest.java`、`DisplaySessionTest.java` | 一次性批准、交接授权与 display generation |
| `PhoneUseLogTest.java:52–69,111–156` | 隐私、一次性帧、Chat／Responses 图片关联 |

新增测试要检查压缩边界前后相同安全与生命周期结果，而非照抄实现常量。未来代码改动按 AGENTS 运行相关 JVM／Lint／构建；这轮文档不触发构建。

## 第二层：本地 mock 协议回归

[scripts/mock_model.py](../../../scripts/mock_model.py):43–77,121–194 可复用 Chat／Responses、reasoning、原生 tool roundtrip、图像和 session 连续性。

但独立 screen fixture 只有 tap／type／finish，且 `:174–180` 按历史 assistant 数计步；真实裁剪 assistant 之后这个计步方式会失效。后续应以受控 testcase ID／显式状态或服务端脚本状态推进，加入跨裁剪阈值的长循环、重试、摘要空／错／超时等情形；不为保住旧 mock 保留无用历史。

mock usage 固定为 fixture，不能计算 token 节省。mock 成功证明协议和不变量，不能证明模型记忆或业务任务质量。

## 第三层：另行授权的确定性设备验收

优先使用项目自有页面，重用 `scripts/device_test.py` 与 `PhoneUseTerminalRunner`，在压缩边界前后覆盖：

- 动态目标 skip 后同 session 新观察重规划，旧坐标零注入；双击部分执行仍终止。
- 审计／人工确认中目标改变、旧／错／过期 token、严重风险人工批准规则不变。
- 最后一动作后的 finish／接管／询问；STOP／PAUSE 不重试、不自动重开任务。
- 取消／watchdog／旧请求迟到、热重载 busy 拒绝、任务清理与新任务互斥。
- 后台 helper 故障停止；generation 改变、显式前台交接后旧观察／坐标／授权失效；物理屏焦点／输入不被越权影响。
- 树被精简但 PageGuard／系统授权／锁屏／secure／password／CAPTCHA 证据仍完整。

脚本会修改设置、启动 Activity／Service，部分 hook 检查会重启小爱；文档引用及历史实测不等于本轮授权。不要默认使用会清空日志／缓存的选项。保持原日志开关，实验后按既有要求恢复环境及 reverse mapping。

## 第四层：真实模型同条件 A/B

### 对照组与任务

三种运行组合分别报告，不合并平均；默认原生不能只用独立外部实验替代。固定服务商／确切 model ID 与版本、API、reasoning、采样、输出预算、maxSteps、超时、权限模式、JPEG 参数、工具能力、初始状态。记录日期、commit／dirty 状态、APK SHA-256、OS／ROM／小爱版本和 fixture 版本。

一次仅变一个维度，建议依次做：baseline → 仅结果状态 → 仅模型观察整理 → 短历史窗口 → 窗口＋有界任务事实。语义摘要／双角色／按需工具各自另开消融；不能把多个改动同时上线后将收益归因于某一个。

使用没有真实支付或外发消息的 owned fixture：

| 类别 | 建议规模，不是新默认限制 | 核心任务依赖 |
| --- | --- | --- |
| 短任务 | 3–5 个必要 UI 转移 | 单步定位、type 替换、正确结束，检查压缩开销 |
| 中任务 | 10–20 个必要转移 | 筛选／返回／临时弹窗，早期否定条件和同名对象 |
| 长任务 | 30–60 个必要转移或跨多个裁剪窗口 | 跨页读数后填表、去重列表、多个待做项、返回早期页面 |
| 恢复任务 | 在上述任务注入受控失败 | 动态目标、连续无效动作、详情缺失、确认失效、树缺失 |

“长”应包含远距离事实／目标依赖，不能只循环点击凑步数。弱长上下文模型以固定模型实测定义，另设较强基线；不按 provider 或上下文广告值分类。

超过默认 30 动作的实验需明确设置双方相同的 maxSteps，并单列原生续期与独立外部既有预算差异。到达 deadline、maxSteps 与模型主动 finish 分开计数，不把较早停止当省成本或长程理解失败。

同初态成对运行、交替 A/B 顺序，先小样本 pilot，再根据方差决定样本量。报告成功数／总数与区间，少样本不宣称稳定百分比提升；费用和设备操作另行授权。

### 指标口径

| 指标 | 定义与防误读 |
| --- | --- |
| 输入负担 | 每请求及整任务 serialized UTF-8 bytes、分项 chars、message／item／完整组、图像数和尺寸；不与 token 混用 |
| token | 服务端实际 usage 原值与其定义；cached／reasoning 单列；缺失为 missing，估算单列 estimate |
| 总成本 | 包含规划、审计、摘要、反思、重试所有调用，不只算 executor 单请求 |
| 延迟 | 单调时钟分段：采集、装配／压缩、请求开始→完整可执行响应、确认等待、注入→新观察、端到端 |
| p50／p95 | 同类分布、样本数和分位算法；步骤存在任务内相关，另报任务级分布；小 pilot 的 p95 不当稳定结论 |
| 完成率 | 用独立 fixture／app 状态谓词，不能用模型 finish／action_complete 代替 |
| 错误完成 | finish 时目标未达成、遗漏约束／条目；不得当“更快” |
| 记忆与恢复 | 早期约束保留、目标漂移、已做项重做、重复失败、stale 坐标提议、skip 后正确继续；提议与实际注入分开 |
| 安全 | 错目标注入、旧批准复用、错误屏幕、取消后动作、硬边界绕过、后台故障回退；关键事件阻止发布 |
| 额外代价 | 摘要遗漏／矛盾／失败率、额外调用数、重规划／接管率及原因 |

日志异步接收顺序不等于事件发生顺序；关联 session／step／observation 与原始单调时钟，跨进程时钟口径先校验，不能按文件行序直接减耗时。

## 验收与停止规则

协议／安全不变量必须全部满足；实际成功率和关键约束遵守不能以 token 降低换取。成功率非劣、成本／延迟和输入收益的数值门槛在 pilot 后预先登记，不伪造通用阈值。证据不足则保持实验开关／不更改默认策略。

出现旧坐标实际注入、批准复用、任务身份污染、错误完成增加、后台越权等立即停止候选。纯质量退化时定位是必要事实丢失、观察过短、摘要漂移还是额外模型延迟；不通过放宽 PageGuard、延长授权或增加 maxSteps 掩盖。

后续记录应列“已执行／未执行／失败／未覆盖”，分别说明离线、mock、设备、真实模型证据；更新对应行为文档与新的 dated validation，不回填历史发布记录。
