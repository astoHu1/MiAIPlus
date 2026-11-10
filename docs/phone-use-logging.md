# 详细日志与故障诊断

从「首页 → 日志 → 更多」进入，开启或关闭「详细日志」；原「控屏 → 诊断」开关仍同步可用。默认关闭，保存后无需重启小爱；模块进程每条记录读取当前开关，宿主 Hook 通过受现有 UID 校验保护的 IPC 查询，最多缓存 1 秒。可在任务期间切换，不取消或重开任务。开关不改变执行模式、权限、窗口选择、重试次数、输入回读期限、帧有效期或动作结果。

日志同时覆盖控屏执行前的文字／语音路由、插件工具目录、模型入口和小爱自身错误路径。宿主的新增诊断读取在有界工作队列中检查开关后执行，避免在 UI 线程新增日志 IPC 或读取错误正文。诊断观察器只读取状态，不改变小爱的支持检查、错误返回或模块安全限制。

## 配置与收集

无需电脑：

1. 在「首页 → 日志 → 更多」开启「详细日志」，返回小爱复现问题。
2. 回到日志页，可搜索事件、会话摘要或任意元数据，按「模块／小爱」筛选，点击记录展开并复制详情。最新记录在前，显示筛选后最近 500 条；压缩包包含所有保留记录。自动刷新只在日志页可见且 App 在前台时每 2 秒读取，可在右上角「更多」中关闭或手动刷新。
3. 点击右上角保存图标，通过系统文件选择器选择位置；或点击分享图标，生成 ZIP 后由系统分享面板选择接收应用。导出和查看不发起模型请求，也不启动控屏。
4. 复现后关闭开关。关闭只停止新记录，旧日志仍能查看与导出；「更多 → 清空」需确认，会删除 App 内日志和分享缓存，已保存到其他位置的压缩包不受影响。开启时清空后仍会继续记录。

## 存储、导出与隐私

ZIP 包含 `phone-use.jsonl`（按模块收到的顺序、旧记录在前）、`environment.json`（环境与保留日志摘要，当前 `schema=2`）和 `README.txt`，不包含完整配置、设备唯一标识、系统全量 Logcat 或截图。分享只授予所选应用对该 ZIP 的临时读取权限，不公开其他私有文件；最多保留最近 3 个分享包，下次分享清理旧包。

环境快照包括模块／小爱版本、精确适配结果、Android API 与 ROM 的公开 build 版本、机型、语言和显示参数，配置的开关／固定选项／是否已填写端点与凭据，插件和工具数量，无障碍连接、模块进程及桥接状态。框架部分记录服务是否绑定、作用域是否含小爱及可读的已加载模块版本；退出记录只保留系统原因、状态和时间，不包含自由文本描述。各部分独立读取，失败时保留异常类型，避免一个损坏配置阻断整个导出。

`hook.status` 和 `hook.lastReload` 保存宿主最近上报的结构化安装／重载结果，包括阶段、模块／宿主版本、可选诊断 Hook 缺失项及受限绑定标识。它们在详细日志关闭时也会保存，供无事件记录时排查安装失败；报告缺失也可能是上报未送达。应核对报告年龄、安装更新时间和框架状态，历史 `ready` 不能证明当前 Hook 仍正常运行。环境中的配置和工具目录是导出时快照，不能代替复现时的运行记录。

日志保存在模块私有目录 `files/phone-use-logs/`，4 个文件各最多 512 KiB，总计最多 2 MiB，超限自动轮转。单条最多 8 KiB（UTF-8，含行尾）；超长或包含换行的整条记录丢弃，不截成无效 JSON。模块写盘和宿主 IPC 使用独立、有界的 256 项异步队列，不在控屏执行线程等待写盘或新增的日志 IPC。宿主经现有 UID 校验向模块追加，不能读取／清空／导出私有日志。热重载清除待传送队列及宿主引用。

队列满、进程退出或存储不可用可能丢失记录；模块丢弃／写入失败计数仅在非零时显示于页面，环境文件始终保留计数，只统计本进程启动或上次清空以来。后续送达的宿主记录带 `hostDropped`／`hostDeliveryErrors` 累计计数，进程重启归零；宿主不能送达时也无法保证留下最后的失败计数。导出取得模块已接收记录的快照，不等待尚未送达的宿主队列。空日志或零计数不能单独证明完整链路成功。

### 额外收集 Logcat

开发者仍可通过已授权 ADB 额外收集 Logcat：

```sh
adb logcat -v threadtime 'MiAIPlusPhoneUse:I' '*:S' > phone-use.log
```

复现结束后用 Ctrl-C 停止收集，并关闭开关。不要按单个 PID 过滤：模块与小爱宿主会分别输出记录。日志使用 `MiAIPlusPhoneUse` 标签和 JSON 行，仍写入系统 Logcat；清空 App 日志不会清除系统 Logcat 或已在电脑保存的文件。

## 记录内容

| 事件 | 用途 |
| --- | --- |
| `chat_route` / `voice_route` | 文字或最终 ASR 结果命中的规则、目标工具及转交／沿用宿主／停止的原因；只记文本长度，不记请求内容 |
| `host_error` / `host_agent_error` / `host_agent_support` | 小爱显示错误前的受限错误码、上游码、可用的 HTTP 状态，以及宿主自身的 Agent 支持检查结果；不改变原检查 |
| `host_hooks` / `module_gate` / `bridge_recovered` / `hot_reload_result` | Hook 安装就绪、模块开关或桥接不可达、桥接恢复及重载结果；安装／重载的最近结构化报告另存于环境快照 |
| `plugin_catalog` / `prompt_policy` / `model_call` / `tool_call_rejected` | 工具目录是否可读、提示策略是否注入、模型走官方或外部入口、请求工具数量和两个内置控屏工具是否存在，以及调用被拒绝的阶段 |
| `start_rejected` / `continuation_rejected` / `host_control_call` / `bridge_task_missing` | 区分任务尚未启动、续调校验失败、宿主桥接调用失败及内存任务丢失 |
| `run_start` / `session_start` / `step_start` / `step_continue` / `session_close` | 关联入口、原生或外部规划、实际前后台模式、步数、重新规划和等待审计状态 |
| `run_end` / `run_cancel` / `host_turn_stop` / `prompt_wait` / `prompt_result` | 结束阶段、首次记录的取消来源、宿主回合终止及确认等待／结果；不记录确认内容或 token |
| `observation` / `capture_complete` | displayId、显示实例摘要、前台组件、屏幕与 JPEG 尺寸、图片摘要、树字符数、采集来源、helper 帧计数和耗时 |
| `tree_windows` / `window` / `tree_selected` / `tree_empty` | 区分服务缺失、无窗口、根节点缺失／刷新失败、包名不匹配，以及 focused 或包名兜底选择；保留现有选择顺序 |
| `action_proposed` / `action_begin` / `action_complete` / `action_coordinates` | 动作类型、归一化坐标、输入长度、确认标记；`action_complete` 仅表示执行器返回，不证明业务目标达成 |
| `input_candidate` / `input_set_text` / `input_readback` / `input_failed` | 定位输入焦点、写入是否被接受、原节点是否存在、回读是否可读／匹配、超时或异常；前台保留原先仅校验写入接受的行为，不额外添加回读 |
| `helper_request` / `helper_reply` / `helper_error` / `screen_handoff` | helper 命令类型、耗时、错误码，以及原有规则允许的屏幕切换；不输出 RPC 参数或整份响应 |
| `frame_stored` / `frame_read` / `frame_read_failed` / `frame_expired` / `frame_evicted` | 帧摘要编号、有效期、读取年龄、排队淘汰、过期、UID 不匹配、缺失或已消费 |
| `image_read_begin` / `image_read_complete` / `image_attached` / `image_attach_failed` | 宿主读取截图及向 ToolResult.Success 或 TOOL Message 附图的路径与数量 |
| `request_image` / `model_request` / `model_http_response` | 模块外部模型及受控原生外部适配器序列化后请求中的图片摘要与数量；可将发送图片关联回观察 |
| `failure` | 失败阶段、解包后的异常类型、有限 cause 类型链、模块自身调用点 `site`／`causeSite`、受限反射绑定和 `messageRef`；不输出异常消息或完整堆栈 |

同一任务使用 `session` 摘要和 `step` 关联；每次真正采集观察生成新的 `observation` 编号，并通过帧管道传给宿主附图日志。主线程无障碍读取沿用调用线程的诊断上下文。记录另含墙上时钟 `time`、单调时间 `elapsedMs`、`pid`、`source=module|host`、日志实例发出记录的递增 `seq`、热重载时变化的随机 `writer` 和写记录的模块版本码 `ver`。`seq` 不是跨进程全局顺序，也不保证异步事件的发生顺序；应结合时间、PID、writer 和观察编号判断。页面的“最新”按模块收取顺序排列，宿主异步传送可能晚于模块后续事件。

宿主 `route` 是最近一次路由决策的本进程编号，`sinceRouteMs` 表示记录时该上下文的年龄，两分钟后失效。它提供时间线索，不能证明异步错误属于同一个请求；新请求、并发回调和宿主内部路径都可能影响归属。`frameRef` 是一次性 token 的 SHA-256 前 8 字节摘要，不是可用于读取截图的 token。`imageDigest` 同样是图片 Base64 的 SHA-256 前 8 字节摘要。最近 32 个图片摘要只在内存用于关联外部请求，不保留图片副本。

`site` 只保留模块类／方法，Debug 构建额外带行号；不包含平台完整堆栈。`messageRef` 是模块构造异常消息首个中英文冒号之前部分的摘要，可帮助在相应构建源码中寻找抛出点；它可能没有匹配或匹配多处，不能当作唯一错误码。外部异常不以正文生成此摘要。

`source` 只表示模块／小爱进程；采集方式使用 `captureSource`／`helperSource`，取消来源使用 `cancelSource`。旧日志中被采集方式覆盖的来源保留为未知，导出摘要单列 `unknownSourceRecords`，不猜测其进程归属。

不记录用户目标、页面正文／完整树、输入内容、操作说明、确认／审计 token、图片数据、服务地址、模型名、API Key、HTTP 头或完整请求。会记录目标应用包名／组件、窗口状态、动作类型、坐标及长度等诊断元数据。

### 请求负担计量

受控外部传输的 `model_request` 增加纯长度／数量字段：`requestBytes` 为实际发送 JSON 的 UTF-8 字节；`tools`／`toolsBytes` 为工具数和工具 schema JSON 字节；`textChars` 及 `systemTextChars`／`userTextChars`／`assistantTextChars`／`toolTextChars`／`otherTextChars` 按协议角色统计文本。`functionCalls`／`functionResults`、`argumentChars` 和 `opaqueChars` 分别统计调用／结果、参数字符串和 encrypted reasoning 长度，不输出其内容。`requestRef` 关联同一次请求的汇总与 `request_image`，不作为任务授权或帧身份。

所有 `*Chars` 均为 Java UTF-16 code units，不能当作 token。`imageBase64Chars` 是内联图片 Base64 字符数，不是 JPEG 解码字节或视觉 token；`associatedImages` 仅代表命中最近 32 个图片摘要，既不保证最新帧，也不证明服务端收图。Responses 的 `function_call_output.output` 同样参与观察文本识别；普通聊天不会仅因新增计数就生成控屏请求日志。关闭详细日志时不增加请求序列化或诊断 UTF-8 副本。

这些字段没有提供服务端 usage、缓存／推理 token 或模型耗时；缺失不能按零处理。官方私有传输仍只覆盖宿主附图边界，不能用外部请求计量代替官方线上历史。

## 判断故障

先检查环境和请求进入了哪个阶段，再对比同一会话中最后正常与首次异常的观察。可以在仓库中离线读取导出 ZIP 或 JSONL：

```sh
python3 scripts/diagnose_log.py /path/to/diagnostic.zip
python3 scripts/diagnose_log.py /path/to/phone-use.jsonl
```

脚本不上传数据，也不把 ZIP 解压到磁盘；限制读取容量，并只展示固定元数据字段。它汇总环境、来源／版本／时间范围、丢失计数和最近诊断事件；`messageRef` 匹配只给出本地源码候选，仍须核对导出中的构建版本。缺失字段表示未报告，不应按零或正常处理。

### 小爱回复“当前功能暂未对你开放”

例如 HyperOS 用户请求在屏幕上的普通学习练习中提供帮助，但小爱立即回复这句话，尚未出现 `session_start`：

1. 先看 `environment.json` 中 `host.profile`、`hook.status`、报告时间与版本、`framework`、`config.enabled` 和 `bridge`，排除尚未适配版本、安装回滚、旧进程和桥接不可达等线索。不能只凭报告缺失断言模块未加载。
2. 看 `chat_route`／`voice_route`：规则是否识别到控屏请求、工具当时是否可用、是否实际转交 Agent；再看 `plugin_catalog`、`prompt_policy`、`model_call`，区分未路由、没有工具和已经到达模型入口。
3. 查 `host_agent_support.supported=false`、`host_error` 与 `host_agent_error`。本地支持检查拒绝使用的是“当前设备暂不支持该功能”，与下面的服务错误提示不同。宿主错误码 `-1009` 对应这句小爱提示，能定位其错误路径，但不能据此确定是账号资格、区域、ROM、服务端策略或其他原因。可选诊断 Hook 未安装时，该路径仍可能没有事件，缺失项会记录在安装状态中。
4. 若已有 `host_control_call`、`start_rejected` 或会话事件，再沿模块执行链排查，不把宿主提示文字直接当成控屏执行器故障。

这类诊断增强不绕过宿主功能开放条件，也不保证复现其他用户的同一故障。若问题发生在开关打开前、模块尚未注入、进程被杀或记录未送达，仍可能只有环境快照；需要结合复现时间和相应用户的新日志判断。

### 已进入控屏的故障

1. `tree_empty` 时检查 `tree_windows` 和各窗口原因，区分无窗口、过期根节点和筛选失败。`foreground` 为空不等于截图也为空。
2. `input_set_text.accepted=true` 后仍失败时，查 `input_readback` 的 `nodePresent`、`readable`、`matches` 与最终原因，区分节点重建、不可读及内容不匹配。
3. 沿 `frame_stored → frame_read → image_read_complete → image_attached` 查图片传递。`missing_or_consumed` 本身不能区分从未存在与已经消费，需要结合该摘要之前的存取记录。
4. 使用受控外部模型时，将 `observation.imageDigest` 与 `request_image.imageDigest` 对照。工具中附图成功不能单独证明最终请求含有该图。

官方模型的私有网络传输不在受控外部适配器中，日志只能确认到宿主图片附加，不能据此证明官方服务已收到图片。请求日志也不能证明模型理解了图片。图片摘要不能证明画面不是黑屏、旧画面或错误页面；这类情况仍需在授权的复现场景单独核对实际后台截图。现有截图／输入／窗口查询不因日志开启而增加重试或物理屏回退。

## 验证范围

本地测试覆盖开关／诊断上下文、帧与请求图片关联、IPC 边界、轮转／容量／失败恢复、ZIP 白名单及设置页交互。已执行的测试和授权设备结果以 [验证记录](validation.md) 为准：2026-10-18 的采集链路使用固定 mock；后续日志布局专项使用合成元数据，不能当作再次验证真实宿主采集、前后台控屏或真实模型。没有据此证明间歇性故障已修复。

## 设备回归

本节是测试入口说明，不授予设备操作权限。

按仓库设备测试流程准备本地 mock、ADB reverse 和 Debug／AndroidTest APK，安装后先等更新通知消退、收起通知栏，避免悬浮通知拦截坐标测试。设备须解锁且测试期间独占屏幕。`scripts/device_test.py` 支持 `--no-detailed-log`、`--detailed-log`，可与 `--background`、`--dual-tools`、`--hook`、`--responses`、`--profiles` 组合；测试结束恢复原日志选项。默认用例在原生会话中关闭后重新开启日志；双工具用例还在同一宿主进程中等待开关缓存到期后验证持续会话，使用 `MiAIPlusPhoneUseTest` 的 `NATIVE_LOG_OFF/ON`、`HOST_LOG_OFF/ON` 标记划分采集区间。应同时核对业务断言和日志，不能仅凭测试输出了几行日志判断通过。

完成开启日志的前台／后台和宿主用例后，可运行 `python3 scripts/device_test.py --logs` 检查首页日志页、筛选／展开、真实双来源持久记录、URI 导出 ZIP、文件选择／分享面板和清空；此用例会清空本轮保留日志及分享缓存，须只在已授权且无需保留这些记录的测试设备上运行。测试恢复原日志开关。

只读导出回归可运行 `python3 scripts/device_test.py --diagnostics`；它要求此前已有真实宿主采集记录，验证双来源和 ZIP 环境／安装状态，不清空日志或合成事件。测试生成的唯一目录路径会输出，核对后可单独清理。
