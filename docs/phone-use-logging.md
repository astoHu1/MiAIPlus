# 详细日志与故障诊断

在「首页 → 日志 → 更多」切换「详细日志」，与「控屏 → 诊断」同步。默认关闭，保存后无需重启，也可在任务期间切换。模块逐条读取开关，宿主通过 UID 校验的 IPC 查询并最多缓存 1 秒。

日志覆盖文字／语音路由、插件目录、模型入口、宿主错误和控屏执行。采集在有界后台队列运行，只读取诊断状态。

## 配置与收集

无需电脑：

1. 在「首页 → 日志 → 更多」开启「详细日志」，返回小爱复现问题。
2. 回到日志页，可搜索事件、会话摘要或任意元数据，按「模块／小爱」筛选，点击记录展开并复制详情。最新记录在前，显示筛选后最近 500 条；压缩包包含所有保留记录。自动刷新只在日志页可见且 App 在前台时每 2 秒读取，可在右上角「更多」中关闭或手动刷新。
3. 点击右上角保存图标，通过系统文件选择器选择位置；或点击分享图标，生成 ZIP 后由系统分享面板选择接收应用。导出和查看不发起模型请求，也不启动控屏。
4. 复现后关闭开关。关闭只停止新记录，旧日志仍能查看与导出；「更多 → 清空」需确认，会删除 App 内日志和分享缓存，已保存到其他位置的压缩包不受影响。开启时清空后仍会继续记录。

## 存储、导出与隐私

ZIP 包含 `phone-use.jsonl`（按模块收到的顺序、旧记录在前）、`environment.json`（环境与保留日志摘要，当前 `schema=2`）和 `README.txt`，不包含完整配置、设备唯一标识、系统全量 Logcat 或截图。分享只授予所选应用对该 ZIP 的临时读取权限，不公开其他私有文件；最多保留最近 3 个分享包，下次分享清理旧包。

环境快照包括模块／小爱版本、精确适配结果、Android API 与 ROM 的公开 build 版本、机型、语言和显示参数，配置的开关／固定选项／是否已填写端点与凭据，插件和工具数量，无障碍连接、模块进程及桥接状态。框架部分记录服务是否绑定、作用域是否含小爱及可读的已加载模块版本；退出记录只保留系统原因、状态和时间，不包含自由文本描述。各部分独立读取，失败时保留异常类型，避免一个损坏配置阻断整个导出。

`hook.status` 和 `hook.lastReload` 即使在日志关闭时也保存最近的结构化安装／重载报告，包括阶段、版本、可选 Hook 缺失项和受限绑定标识。排查时核对报告时间、安装版本与框架状态；配置和工具目录均为导出时快照。

日志保存在模块私有目录 `files/phone-use-logs/`，4 个文件各最多 512 KiB，总计最多 2 MiB，超限自动轮转。单条最多 8 KiB（UTF-8，含行尾）；超长或包含换行的整条记录丢弃，不截成无效 JSON。模块写盘和宿主 IPC 使用独立、有界的 256 项异步队列，不在控屏执行线程等待写盘或新增的日志 IPC。宿主经现有 UID 校验向模块追加，不能读取／清空／导出私有日志。热重载清除待传送队列及宿主引用。

队列满、进程退出或存储失败会丢失记录。页面仅在非零时显示模块丢弃／写入失败计数，环境文件始终保留计数；宿主后续记录携带 `hostDropped`／`hostDeliveryErrors`。计数在进程重启或清空后归零，导出不等待宿主待送队列。

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
| `host_error` / `host_agent_error` / `host_agent_support` | 小爱显示错误前的受限错误码、上游码、可用的 HTTP 状态，以及宿主自身的 Agent 支持检查结果 |
| `host_hooks` / `module_gate` / `bridge_recovered` / `hot_reload_result` | Hook 安装就绪、模块开关或桥接不可达、桥接恢复及重载结果；安装／重载的最近结构化报告另存于环境快照 |
| `plugin_catalog` / `prompt_policy` / `model_call` / `tool_call_rejected` | 工具目录是否可读、提示策略是否注入、模型走官方或外部入口、请求工具数量和两个内置控屏工具是否存在，以及调用被拒绝的阶段 |
| `start_rejected` / `continuation_rejected` / `host_control_call` / `bridge_task_missing` | 区分任务尚未启动、续调校验失败、宿主桥接调用失败及内存任务丢失 |
| `run_start` / `session_start` / `step_start` / `step_continue` / `session_close` | 关联入口、原生或外部规划、实际前后台模式、步数、重新规划和等待审计状态 |
| `run_end` / `run_cancel` / `host_turn_stop` / `prompt_wait` / `prompt_result` | 结束阶段、首次记录的取消来源、宿主回合终止及确认等待／结果；不记录确认内容或 token |
| `observation` / `capture_complete` | displayId、显示实例摘要、前台组件、屏幕与 JPEG 尺寸、图片摘要、树字符数、采集来源、helper 帧计数和耗时 |
| `tree_windows` / `window` / `tree_selected` / `tree_empty` | 区分服务缺失、无窗口、根节点缺失／刷新失败、包名不匹配，以及 focused 或包名兜底选择 |
| `action_proposed` / `action_begin` / `action_complete` / `action_coordinates` | 动作类型、归一化坐标、输入长度、确认标记；`action_complete` 仅表示执行器返回，不证明业务目标达成 |
| `launch_state` / `launch_submitted` / `launch_confirmation` / `launch_result` / `launch_transport` | 启动期间变化的前台分类、请求提交、当次确认是否点击、目标是否稳定前台及耗时；`launch_transport` 的 `backend`／`phase` 区分一次性 root helper 与显式 Activity 路径及传输阶段，不输出命令正文 |
| `input_candidate` / `input_set_text` / `input_readback` / `input_failed` | 定位输入焦点、写入是否被接受、原节点是否存在、回读是否可读／匹配、超时或异常；前台只校验写入接受，不做回读 |
| `helper_request` / `helper_reply` / `helper_error` / `screen_handoff` | helper 命令类型、耗时、错误码，以及原有规则允许的屏幕切换；不输出 RPC 参数或整份响应 |
| `frame_stored` / `frame_read` / `frame_read_failed` / `frame_expired` / `frame_evicted` | 帧摘要编号、有效期、读取年龄、排队淘汰、过期、UID 不匹配、缺失或已消费 |
| `image_read_begin` / `image_read_complete` / `image_attached` / `image_attach_failed` | 宿主读取截图及向 ToolResult.Success 或 TOOL Message 附图的路径与数量 |
| `request_image` / `model_request` / `model_http_response` | 模块外部模型及受控原生外部适配器序列化后请求中的图片摘要与数量；可将发送图片关联回观察 |
| `failure` | 失败阶段、解包后的异常类型、有限 cause 类型链、模块自身调用点 `site`／`causeSite`、受限反射绑定和 `messageRef`；不输出异常消息或完整堆栈 |

同一任务使用 `session` 摘要和 `step` 关联；每次真正采集观察生成新的 `observation` 编号，并通过帧管道传给宿主附图日志。主线程无障碍读取沿用调用线程的诊断上下文。记录另含墙上时钟 `time`、单调时间 `elapsedMs`、`pid`、`source=module|host`、日志实例发出记录的递增 `seq`、热重载时变化的随机 `writer` 和写记录的模块版本码 `ver`。`seq` 不是跨进程全局顺序，也不保证异步事件的发生顺序；应结合时间、PID、writer 和观察编号判断。页面的“最新”按模块收取顺序排列，宿主异步传送可能晚于模块后续事件。

宿主 `route` 是本进程最近路由编号，`sinceRouteMs` 为上下文年龄，两分钟后失效，只用于时间关联。`frameRef` 是一次性帧 token 的 SHA-256 前 8 字节摘要，`imageDigest` 是图片 Base64 的同类摘要；最近 32 个图片摘要只在内存用于关联外部请求。

`site` 保留模块类／方法，Debug 另带行号。`messageRef` 为模块异常消息首个中英文冒号前部分的摘要，用于查找源码候选，可能匹配多处；外部异常不生成该摘要。

`source` 只表示模块／小爱进程；采集方式使用 `captureSource`／`helperSource`，取消来源使用 `cancelSource`。旧日志中被采集方式覆盖的来源保留为未知，导出摘要单列 `unknownSourceRecords`，不猜测其进程归属。

不记录用户目标、页面正文／完整树、输入内容、操作说明、确认／审计 token、图片数据、服务地址、模型名、API Key、HTTP 头或完整请求。会记录目标应用包名／组件、窗口状态、动作类型、坐标及长度等诊断元数据。

### 请求负担计量

受控外部传输的 `model_request` 增加纯长度／数量字段：`requestBytes` 为实际发送 JSON 的 UTF-8 字节；`tools`／`toolsBytes` 为工具数和工具 schema JSON 字节；`textChars` 及 `systemTextChars`／`userTextChars`／`assistantTextChars`／`toolTextChars`／`otherTextChars` 按协议角色统计文本。`functionCalls`／`functionResults`、`argumentChars` 和 `opaqueChars` 分别统计调用／结果、参数字符串和 encrypted reasoning 长度，不输出其内容。`requestRef` 关联同一次请求的汇总与 `request_image`，不作为任务授权或帧身份。

`*Chars` 的单位是 UTF-16 code units，`imageBase64Chars` 是 Base64 字符数，均不是 token。`associatedImages` 表示命中最近 32 个图片摘要；Responses 的 `function_call_output.output` 参与观察文本统计。关闭详细日志时不增加请求序列化或 UTF-8 副本。

日志不提供服务端 usage、缓存／推理 token 或模型耗时，缺失值按未报告处理。官方私有传输仅覆盖宿主附图边界。

## 判断故障

先检查环境和请求进入了哪个阶段，再对比同一会话中最后正常与首次异常的观察。可以在仓库中离线读取导出 ZIP 或 JSONL：

```sh
python3 scripts/diagnose_log.py /path/to/diagnostic.zip
python3 scripts/diagnose_log.py /path/to/phone-use.jsonl
```

脚本离线限量读取 ZIP／JSONL，只展示固定元数据，汇总环境、来源、版本、时间范围、丢失计数和近期事件。`messageRef` 的源码候选须与导出构建版本核对，缺失字段表示未报告。

### 小爱回复“当前功能暂未对你开放”

例如 HyperOS 用户请求在屏幕上的普通学习练习中提供帮助，但小爱立即回复这句话，尚未出现 `session_start`：

1. 先看 `environment.json` 中 `host.profile`、`hook.status`、报告时间与版本、`framework`、`config.enabled` 和 `bridge`，排除尚未适配版本、安装回滚、旧进程和桥接不可达等线索。不能只凭报告缺失断言模块未加载。
2. 看 `chat_route`／`voice_route`：规则是否识别到控屏请求、工具当时是否可用、是否实际转交 Agent；再看 `plugin_catalog`、`prompt_policy`、`model_call`，区分未路由、没有工具和已经到达模型入口。
3. 查 `host_agent_support.supported=false`、`host_error` 与 `host_agent_error`。本地支持检查拒绝使用的是“当前设备暂不支持该功能”，与下面的服务错误提示不同。宿主错误码 `-1009` 对应这句小爱提示，能定位其错误路径，但不能据此确定是账号资格、区域、ROM、服务端策略或其他原因。可选诊断 Hook 未安装时，该路径仍可能没有事件，缺失项会记录在安装状态中。
4. 若已有 `host_control_call`、`start_rejected` 或会话事件，再沿模块执行链排查，不把宿主提示文字直接当成控屏执行器故障。

日志若在故障后才开启，或模块未注入、进程退出、记录未送达，可能只剩环境快照；结合复现时间判断。

### 已进入控屏的故障

1. 打开应用卡住时，对照 `launch_transport`、`launch_submitted` 和 `launch_state`，区分 root 路径失败、请求提交、系统确认和前台等待。`launch_confirmation.clicked=true` 表示点击匹配的“本次允许”；`launch_result.opened=true` 才表示目标稳定前台 500 ms。`opened=false` 也可能是返回系统窗口观察。启动回退、期限和确认规则见 [应用启动](app-launch.md)。
2. `tree_empty` 时检查 `tree_windows` 和各窗口原因，区分无窗口、过期根节点和筛选失败。`foreground` 为空不等于截图也为空。
3. `input_set_text.accepted=true` 后仍失败时，查 `input_readback` 的 `nodePresent`、`readable`、`matches` 与最终原因，区分节点重建、不可读及内容不匹配。
4. 沿 `frame_stored → frame_read → image_read_complete → image_attached` 查图片传递。`missing_or_consumed` 本身不能区分从未存在与已经消费，需要结合该摘要之前的存取记录。
5. 使用受控外部模型时，将 `observation.imageDigest` 与 `request_image.imageDigest` 对照。工具中附图成功不能单独证明最终请求含有该图。

图片摘要只用于关联，不能判断黑屏、旧帧或错误页面；需在复现场景核对实际截图。宿主附图或外部请求含图，也不代表服务端接收或模型理解。

## 验证范围

本地测试覆盖开关、上下文、图片关联、IPC、轮转、失败恢复、ZIP 白名单和设置交互。执行结果见 [验证摘要](validation.md)；2026-10-18 采集链路使用固定 mock，后续布局检查使用合成元数据。

## 设备回归

设备回归使用已授权的测试设备。

按仓库设备测试流程准备本地 mock、ADB reverse 和 Debug／AndroidTest APK，安装后先等更新通知消退、收起通知栏，避免悬浮通知拦截坐标测试。设备须解锁且测试期间独占屏幕。`scripts/device_test.py` 支持 `--no-detailed-log`、`--detailed-log`，可与 `--background`、`--dual-tools`、`--hook`、`--responses`、`--profiles` 组合；测试结束恢复原日志选项。默认用例在原生会话中关闭后重新开启日志；双工具用例还在同一宿主进程中等待开关缓存到期后验证持续会话，使用 `MiAIPlusPhoneUseTest` 的 `NATIVE_LOG_OFF/ON`、`HOST_LOG_OFF/ON` 标记划分采集区间。应同时核对业务断言和日志，不能仅凭测试输出了几行日志判断通过。

完成开启日志的前台／后台和宿主用例后，可运行 `python3 scripts/device_test.py --logs` 检查首页日志页、筛选／展开、真实双来源持久记录、URI 导出 ZIP、文件选择／分享面板和清空；此用例会清空本轮保留日志及分享缓存，须只在已授权且无需保留这些记录的测试设备上运行。测试恢复原日志开关。

只读导出回归可运行 `python3 scripts/device_test.py --diagnostics`；它要求此前已有真实宿主采集记录，验证双来源和 ZIP 环境／安装状态，不清空日志或合成事件。测试生成的唯一目录路径会输出，核对后可单独清理。
