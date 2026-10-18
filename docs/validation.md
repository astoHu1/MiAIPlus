# MiAIPlus 1.0.0-preview 实现与验证状态

## OpenCode Go 请求头、全局规划联动与下拉窗口（2026-10-18）

联网查阅 [OpenCode Go 官方文档](https://opencode.ai/docs/go/#where-can-i-use-it)，为独立与原生外部 Chat／Responses 请求补充客户端 User-Agent 和 `x-opencode-session`。独立循环按任务稳定；原生按消息身份关联，重复／续轮与部分历史裁剪复用，不按提问内容合并不同会话。记忆和路由按辅助请求 ID 分组，缺失时使用单次 UUID，尚未与主对话统一；全部关联消息被压缩掉或缓存淘汰后可能产生新标识。接入地址、模型列表路径差异及协议范围见 [接入说明](opencode-go.md)。

全局外部模型开启时强制原生控屏规划，开启设置时同步保存 `planner=native`，旧配置读取也遵守约束；界面显示「小爱当前模型」并禁用选择。关闭全局替换后保持原生规划，可再手动选独立外部规划。API 协议等五类下拉选择关闭背景压暗，保留 Miuix 定位、勾选、动画和外部点击关闭；确认／编辑对话框保持原有行为。

本地 282 项 JVM／Robolectric 测试通过（0 failures/errors/skipped）。新增 8 项覆盖实际本地 HTTP 的 Chat／Responses 请求头、同任务稳定与跨任务隔离、原生消息身份与部分裁剪、空历史、标识安全、旧配置强制规划、设置保存／禁用／重新选择，以及下拉背景像素不变和协议草稿选择。`protocol-popup-no-dim.png` 与 `control-global-model.png` fixture 截图已渲染并人工核对，位于 `app/build/outputs/ui-screenshots/`。Debug／Release 完整 Lint、APK 与 Debug AndroidTest APK 构建均通过；Lint 为 0 errors、35／26 warnings；`git diff --check` 通过。

最新 Debug／AndroidTest 已安装到 Xiaomi 25019PNF3C / xuanyuan、API 37、小爱 8.3.30.4420（508003030）。`device_test.py --hook` 与 `--hook --responses` 均通过，重启小爱后在真实宿主客户端验证 Dyn／直接模型、流式文字／工具、工具结果及图片续轮、记忆、gate／能力和 intent 原生响应解析。模拟服务逐个请求断言唯一有效 UUID 会话头及自有 User-Agent，并检查工具续轮标识不变、不同测试对话标识不同。

`device_test.py --profiles` 实机通过，覆盖 API 协议草稿／保存、非黑色页面区域在弹窗前后像素不变、全局开启时规划模型保存／禁用及实际触摸不展开、关闭后重新选择，以及原有设置页回归。截图已人工核对。Runner 验证原配置完整恢复，无障碍服务列表保持原值；测试模拟服务已停止，reverse 映射已移除。此轮使用本地模拟模型，未调用真实 Go 订阅，不能据此保证服务端账号、模型或长任务兼容；旧小爱 8.2.65 仅核对方法签名，未降级实测。工作区并行 README 改动未纳入本任务提交。

## 测试集精简（2026-10-18）

在 `452e104` 基础上审计单元测试、设备 runner 与主机脚本：JVM／Robolectric 测试方法从 334 合并为 274（减少 60，约 18%），测试类从 50 合并为 47；无障碍主机测试从 9 个方法合为 5 个契约测试，保留全部 10 个输入场景。方法数按 JUnit 报告统计，输入矩阵的每行仍实际执行，不能把方法数减少解释为删除同等数量的行为场景。

同契约输入改用带场景标签的矩阵，配置规范化、会话路由及交互通知测试集中到对应类；两项纯 Bundle 解析从 Compose 页面夹具移到轻量 Robolectric 类。删除的等价重复包括旧请求 token 退役、显示 generation 唯一性、画布 tap 正反例和已由独立拒绝测试覆盖的确认等待。保留原 SDK 配置、三种页面交接的独立 Activity／ViewModel、新会话零执行历史下的重规划取消，以及权限、超时、注入边界、一次性令牌与后台触摸焦点隔离断言。

`LiveProbeRunner` 原有 live／configure／text 路径合入 `DeviceTestRunner`，设备与 UI runner 文件合计 17→16，保留分派优先级和全部设备场景。后台主机脚本删除 instrumentation 启动前的重复任务检查；测量仍由 runner 在测试页与键盘就绪后采集真实基线，持续检查焦点／IME 隔离。

本地 274 项 JVM／Robolectric 测试全部通过（0 failures／errors／skipped）；`:app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` 通过，Debug Lint 为 0 errors、35 warnings。无障碍主机测试 5 项通过，两份修改脚本的 Python 语法及 `git diff --check` 通过。本轮未执行设备测试、真实模型请求、Release 构建或 Release Lint；设备 runner 的合并仅完成编译验证。未修改生产代码，未提交、推送或发布。

## 前台输入法隔离实机复验（2026-10-18）

用户反馈虚拟屏打开应用／点击时前台键盘仍被挤掉，并指定当前连接设备进行复验。比对设备已安装 APK 与本地修复包：原安装包不含 `createIsolatedDisplay` 和 `VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED`，确认设备尚未运行上一轮的修复代码。先安装上一轮仅包含显示焦点／HIDE 修复的 Debug 与 AndroidTest，完整后台回归通过，主屏实际键盘持续监测 2058 次、`physicalImeLoss=null`。因此不能把此次用户反馈直接归因为这些标志在该 ROM 上无效。

另外为后台触摸补充运行平台的 `MotionEvent.FLAG_NO_FOCUS_CHANGE`：所有点击／长按／滑动／取消都携带它，并保留目标 display id，使可信注入跳过 [InputDispatcher 的外部窗口点击焦点通知](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/services/inputflinger/dispatcher/InputDispatcher.cpp)。平台缺字段或值无效时拒绝后台输入。此项是事件层的进一步约束，不把它描述为本次设备已经证明的唯一根因。

包含事件层约束的 Debug／AndroidTest 已在 Xiaomi 25019PNF3C / xuanyuan、API 37、`CP2A.260605.016` 上完成通用 `background` 设备回归。使用设备当前默认输入法，主屏自有 Fixture 编辑框真实打开键盘，覆盖原生会话创建／启动计算器／返回／结束，独立后台编辑框三次真实点击各在目标页计数精确增加一次，中文经产品 `BackgroundActions` 写入并在原节点回读，BACK 退出后台页，销毁及 capability 建屏／启动／输入／销毁均保持主屏键盘。持续 2107 次监测无丢失（`physicalImeLoss=null`），主屏 Fixture 零触摸、零文字泄漏。此次只验证确定动作，不代表模型自主规划能力。

本地 334 项 JVM／Robolectric 测试通过（0 failures/errors/skipped），Debug／Release／AndroidTest 构建及两套完整 Lint 通过（0 errors，35／26 warnings）。新增事件标志拒绝与触摸隔离回归；既有点击／长按／滑动／取消检查同时覆盖标志、source 与 display id。Robolectric 4.17 自带 MotionEvent shadow 缺失 display id 属性，实现最小测试 shadow 补充该属性，flags／source／坐标／时序仍使用原 shadow；真实按屏路由另外经设备测试检查。

完整后台回归后配置内容摘要与无障碍服务列表均保持原值，服务重连后 `connected=true,busy=false`；设备仅剩 display 0，无 helper、无活动 instrumentation，回到桌面，未添加／删除 reverse 映射。未请求真实模型，未提交、推送或发布。按用户要求，测试集仅保留自有测试页的通用输入法回归，已移除第三方应用专项 runner、分发参数及运行说明；上述设备结果来自移除专项前的通用回归。

移除专项后重新执行 `:app:lintDebug :app:assembleDebugAndroidTest` 通过，并检查生成测试 APK 的 DEX 不再包含专项 runner、参数及目标包名。本次移除未重新执行设备测试。

## 模块截图与虚拟屏键盘隔离（2026-10-18）

模块 `MainActivity` 不再设置 `FLAG_SECURE`，设置页及继承宿主策略的弹窗允许用户截图。密钥默认遮挡、离页重新遮挡及私有存储保持。设置 UI runner 改为断言允许截图，截图后不再重新开启安全旗标。

虚拟屏此前未设置独立焦点和禁止抢顶层焦点，仅按 display id 注入输入、设置 `IME_POLICY_LOCAL`。这些条件只能限定输入去向和键盘显示位置，不能阻止后台启动／触摸争用系统顶层焦点及 IME client。当前统一生产建屏与 capability 初始化：要求运行平台真实提供 `OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED` 并回读有效显示标志，在启动任何应用前设置并回读 `IME_POLICY_HIDE=2`。后台文字仍通过原目标屏编辑框 `ACTION_SET_TEXT` 写入和精确回读；重复 IME 验证不重新配置已经就绪的屏幕。隔离校验失败立即释放后台屏，不回退主屏。AOSP Android 13 没有这两个焦点标志，未回移植能力的 ROM 会明确拒绝后台模式，模块前台仍支持 Android 13。

源码依据为 AOSP Android 14 的 [Display 焦点标志说明](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/view/Display.java) 及 [WindowManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/wm/WindowManagerService.java)：`moveDisplayToTopInternal` 拒绝不能抢顶层焦点的屏幕，`hasInputMethodClientFocus` 拒绝非顶层焦点屏绑定 IME client。`LOCAL` 并不隔离系统输入法。

本地 332 项 JVM／Robolectric 测试通过（0 failures/errors/skipped），新增 7 项回归覆盖 HIDE 成功及重复检查无写入、LOCAL／FALLBACK／缺失方法拒绝、缺失请求／有效焦点标志拒绝，以及 app／task 不变但窗口焦点丢失或不可读时不误报隔离通过。Debug／Release／AndroidTest 构建及两套完整 Lint 通过（0 errors，35／26 warnings）。`git diff --check` 通过。

设备回归 `BackgroundRunner` 新增 `PhysicalImeGuard`：主屏先真实打开键盘并稳定 500ms，此后按每帧、16ms 轮询及焦点事件持续监测窗口／编辑框焦点、IME 可见性及 `isActive(input)`，首次丢失锁存。覆盖原生后台会话创建／启动／返回／结束，以及后台测试页真实点击编辑框、产品输入／回读、BACK 退出页面、销毁；随后同一主屏键盘保持打开，验证 capability 真正建屏、HIDE 策略及无残留。helper 能力探针也不再忽略主屏窗口失焦，但其离散采样本身不证明活跃键盘隔离。

本轮未安装设备、未执行上述设备回归或真实模型任务，未验证目标 ROM／不同输入法实际保持键盘的结果；新增 runner 入口仍为 `python3 scripts/background_display_test.py`。历史仅比较 app 焦点／任务的 PASS 不能作为本次键盘修复的实机结论。未提交、推送或发布。

## 虚拟屏自定义画布（2026-10-18）

「控屏 → 虚拟屏 → 自定义分辨率」新增宽、高和密度编辑，默认保持 1080×1920@320，范围与 root helper 一致：宽高 320–4096 像素、密度 80–1000 dpi。「填入本机分辨率」读取默认物理屏当前的完整像素尺寸与密度，只填入编辑框；点击「保存」后持久化。NativeSession、AgentEngine 的新虚拟屏会话和能力检测都使用保存参数，进行中的会话不调整尺寸。后台设备验收显式写入基准画布，结束恢复原配置。

本地 325 项 JVM／Robolectric 测试通过（0 failures/errors/skipped），包括画布默认值、异常配置钳制、合法配置保留、设置持久化、边界拒绝、默认屏参数读取，以及界面编辑／填入／保存。画布弹窗截图已渲染核对。Debug／Release／AndroidTest 构建及两套完整 Lint 通过（0 errors，35／26 warnings）；`git diff --check` 通过。

本轮未安装设备、未运行虚拟屏／模型任务，未验证自定义尺寸下的真实创建、输入／截图隔离或第三方 APP 再次打开后的布局；不将历史固定画布的设备结果视为自定义画布验收，也不承诺修复 APP 已缓存的布局状态。改动未提交、推送或发布。

## 控屏 200 步与统一对话生命周期（2026-10-18）

Phone Use 最大步数设置范围从 1–80 扩至 1–200，默认仍为 30；设置输入、持久化和执行配置共同遵循该上限。首页新增「对话 → 自动新建对话」，按用户选择默认 15 分钟，支持 1–1440 分钟，0 关闭，独立于控屏开关及模型 API。

全屏和系统浮窗保存同一原生话题身份、共用上次用户操作时间；时长内唤醒续用，超时后唤醒新建。触摸、按键、文字提交及最终 ASR 结果更新计时，模型回复和工具结果只刷新身份，不延长时间。历史及通知显式选择优先；控屏忙碌、设置失效或待处理唤醒过期时不回放自动跳转。删除当前话题使保存身份失效，重载释放回调。

移除聊天 Agent 的计数停止、普通 60／复杂 1000 轮开始门槛，以及侧栏第 101 个话题的创建门槛；保留真实轮次、事件、取消、错误、上下文压缩、时间限制及实际强制禁用工具。独立 Phone Use 动作预算仍生效；未修改其他专项 Agent 的限制或模型服务端限制。

本地 318 项 JVM／Robolectric 测试通过（0 failures/errors/skipped），包含设置保存／重建、200 步边界、闲置策略、显式路由、配置／忙碌竞争、Launcher 恢复前不得消费唤醒及真实 Activity 生命周期判断。首页截图已重新渲染核对，显示默认 15 分钟。Debug／Release／AndroidTest 构建及两套完整 Lint 通过，Lint 为 0 errors、34／25 warnings。

同一 Xiaomi 25019PNF3C / xuanyuan、API 37、小爱 8.3.30.4420（508003030）已安装最新 Debug／AndroidTest，`--conversation-continuity` 完整通过：

- 真实宿主计数 gate 在 41／61／205／1001 轮放行，保留实际轮次／session 事件、同步及挂起发送、取消传播。
- 自有本地话题续用、超时后新建不同 topic／chat、显式历史及完成通知覆盖待处理自动唤醒、原生通知选择更新时间，以及从磁盘重建策略与话题身份。
- 真实桌面 Launcher 的超时／未超时唤醒，实际卡片身份与可见导航同步。
- 三次真实 VoiceService 唤醒均经原生界面重置，并断言系统浮窗已附着、可见且宿主不在全屏前台；全屏转浮窗续用、浮窗超时新建、浮窗再次续用及返回全屏均保持预期话题。HOME 切换后等待原生全屏前台标志消失，避免 paused Activity 过渡误选全屏。测试结束严格确认恢复原生主对话路由／选中项／卡片模式，清理自有话题并恢复配置。

同一生产代码的 `--phone-use-terminal`、`--focus` 和 `--conversation-return` 回归通过，覆盖实际错误终态、强制清工具与 session 释放、收尾不抢前台及完成通知返回最初对话。前者采样 106 次主屏焦点／任务，未发生旧任务重开。

上述会话测试使用 1 分钟测试配置并定向推进保存的闲置时间，不实际等待 15 分钟；三次自有 nonce 唤醒的 recorder 预热和 ASR 启动在录音前阻断，音频／模型兜底拦截均未触发，未请求模型或采集音频。验证了真实窗口／路由及原生方法，不代表模型自主长任务成功率；未按实体电源键或语音热词唤醒，未实测多窗口中已恢复页面的焦点切换或宿主进程重启，持久化仅验证状态重建。旧小爱 8.2.65 绑定经源码及本地检查，本轮未降级实测。

测试后回到桌面，instrumentation 已结束（`mFinished=true`），无 DisplayHelper、仅 display 0，无 reverse 映射；原无障碍服务列表保持一致，模块服务重连后健康查询为 `connected=true,busy=false`。改动未提交、推送或发布。

## 控屏步数与模型错误终态（2026-10-18）

针对临近步数上限时反复返回对话、灵动岛重复提示未完成的问题，修复四条收尾路径：

- 结构化工具此前只读取 `AppResult.getText()`，把底层错误也返回为普通 Success。现在保留 `isError()`，返回 `shouldRetry=false`、`PERMANENT`、`terminateAgent=true` 的原生 Failure，并提供明确的 STOP 原因和禁止自动重开指令。停止 gate 同时识别 Failure、所有顶层 PAUSE 和精确兼容 CLI 入口，CONTINUE 页面数据不能注入停止指令。
- 过期续调此前会先分配新控屏任务、收起界面，再因会话不存在失败。现在在任务/UI 分配前验证会话、UID、期限和来源工具，重复旧 session 不改变任务 ID、结束结果或通知。
- 观察中报告剩余动作预算，最后一步仍保留新观察；预算为零时只接受完成或接管，额外动作在坐标解析和执行前停止。去除“达到步数上限，请重新发起任务”等诱导自动重启的文案。页面变化仍在同一会话 CONTINUE，跳过动作消耗预算。
- 宿主轮次耗尽、禁用工具或本轮退出时，按执行身份/requestId/原观察 session/模块 task token 清理对应未完成控屏。旧回合不能结束替代任务或后续纯对话；无可靠请求身份时保留原 watchdog。宿主 UI 启动和 Activity 恢复统一遵守收尾静默，明确唤醒与有效通知返回仍可立即打开。

本地 267 项 JVM/Robolectric 测试通过（0 failures/errors/skipped）；Debug、Release、AndroidTest 构建及两套完整 Lint 通过（0 errors，Debug 31／Release 24 warnings）。新增 Debug 探针、runner 和 `device_test.py --phone-use-terminal`。

Xiaomi 25019PNF3C / xuanyuan、API 37、小爱 8.3.30.4420（508003030）已安装本轮 Debug／AndroidTest。`--phone-use-terminal` 七阶段通过：真实注册工具返回 Failure／不可重试／终止 Agent，实际原生 STOP gate 生效；最后观察保持非终态，超额动作停止；三次旧会话重试及缺参调用不新建任务、不改变结束通知发布时间；预算为零后完成／接管／询问均可收尾；直接调用宿主真实 IterationGuard 与 AgentContext.release 后，原生 session 释放并保留对应原因。111 次主屏焦点/任务采样保持自有页面，无注入触摸或输入。`--focus` 回归通过，覆盖跨步通知、快速任务更换、停止、收尾不抢前台和点击通知返回原全屏对话。

`--robust-screen` 回归通过：动态无关文字继续刷新时稳定目标仍可点击，移动/替换目标拒绝旧动作并在原会话重规划，严格 3 次触摸/点击，50ms 采样跨步通知只在 finish 后结束。`--dual-tools` 在两种兼容默认屏幕设置下通过，跨工具错误返回真实 Failure 后原会话仍可由正确工具继续，后台点击/滑动保持主屏隔离，helper/display 正常退出。

`--action-confirmation` 回归通过：实际注册工具及通知 PendingIntent 覆盖同意、拒绝、停止与真实 60 秒超时；同意仅执行一次自有页面无害点击，后三种均零触摸，并返回终止结果。等待期间 Activity／焦点保持，无旧无障碍确认悬浮窗；收尾后旧按钮失效，runner 恢复原配置。

测试后已回到桌面，无活动 instrumentation、无 DisplayHelper，仅 display 0，无 reverse 映射。无障碍服务列表逐字保持原值，重新连接后健康查询为 `connected=true,busy=false`。本轮改动未提交、推送或发布。

上述实机检查使用确定动作、自有页面和真实宿主方法，不调用真实模型，也不证明模型自主任务成功率；旧版小爱绑定经源码/本地验证，本轮未降级重测旧版。

## 新版全屏思考强度菜单动画修复（2026-10-17）

8.3.30 全屏外部模型菜单此前替换为普通 Android `PopupWindow`，绕过了宿主原生动画。现在保留原生 `FunctionModePopup`／`HyperPopupMenu`，只在工厂同步调用内替换档位、勾选状态及经 UID／配置身份校验的保存回调。保留原生定位、键盘焦点策略、再次点击收起、配置变更处理与 Miuix 动画；热重载释放选择回调并关闭菜单。旧版全屏和普通输入栏路径保持原实现。

本地 235 项 JVM/Robolectric 测试通过（0 failures/errors/skipped）；Debug、Release、AndroidTest 构建及两套完整 Lint 通过（0 errors，Debug 31／Release 24 warnings）；`git diff --check` 通过。

同一 Xiaomi 25019PNF3C / xuanyuan、API 37、小爱 8.3.30.4420（508003030）已安装修复版 Debug／AndroidTest。`--hook --responses --reasoning` 和 `--hook --reasoning` 两套实机回归通过：覆盖官方三档保持、外部模型菜单与保存、全屏重开勾选、键盘焦点策略、线上推理参数，以及原生全屏动画的 forward start／end 和 reverse start／end 四个回调。动画检查等待实际完成，不以 `isShowing` 代替动画完成。模型请求使用本机 mock，未验证真实服务任务质量；动画回调证据不等于逐帧视觉效果评估。本轮 mock 已停止、18765 reverse 已删除，无活动 instrumentation，无障碍服务列表保持原值。

## 超级小爱 8.3.30 适配（2026-10-17）

ADB 测试机已升级为超级小爱 **8.3.30.4420（508003030）**。新增精确 APK 兼容档案，保留 **8.2.65.4714（508002065）** 的原绑定；versionName 和 versionCode 必须同时匹配，其他版本仍拒绝启用。适配模型配置新增字段、混淆类／方法、能力分类云端提示、本机 DI 工具注册表、三套推理菜单、全屏／浮窗收起入口与新版主聊天页导航。外部能力分类补充本地 JSON schema；不扩大分类器的工具执行权限。

本地验证：235 项 JVM/Robolectric 测试通过（0 failures/errors/skipped），包含新增精确版本门禁／档案切换和新版能力分类请求回归。Debug、Release、AndroidTest 构建和两套完整 Lint 通过（0 errors，Debug 31 / Release 24 warnings）；Python 语法与 `git diff --check` 通过。

同一 Xiaomi 25019PNF3C / xuanyuan、API 37 上安装适配 Debug/AndroidTest，实机已通过：

- Chat Completions 与 Responses 两套原生 Hook：Dyn／直接客户端、SSE 文字／工具调用与严格终态、工具结果和图片回传、记忆、gate、能力分类及 intent 路由的实际请求和原生解析。使用本机 mock，校验独立认证、私有字段清理及推理参数。
- 原生输入栏、全屏和浮窗三套推理菜单：关闭外部模式保留 AUTO／QUICK／EXPERT；开启后验证档位数量、标签、保存、重开选中状态和线上参数，不改小爱原模式。
- `--robust-screen`：无关文字每 100ms 刷新仍可点击，目标移动／替换后零旧触摸并在原会话重新规划；严格 3 次触摸／点击，50ms 采样跨步通知终态。
- `--dual-tools`：两种兼容默认屏幕设置下，两工具分别注册／导出并固定选屏，错误工具续调拒绝；后台实际点击／滑动且主屏不变，helper／display 正常退出。
- `--conversation-return`：两条自有本地对话，跨上下文续调／结束，切换对话再点击完成通知仍返回首次对话，页面 chatId 与实际 topicId 同时匹配。
- `--focus`：全屏收起、跨步通知、快速任务交接、停止、结束不抢前台和完成通知返回。
- `--foreground-consent` 与 `--action-confirmation`：真实通知同意／拒绝／停止及真实 60 秒超时，首次／中途应用冲突交接、拒绝后的 direct answer／原生 STOP gate；等待期间主屏焦点保持。确认仅批准一次固定无害点击，拒绝／停止／超时均零触摸。
- `--reply-actions`／`--reply-actions-chat`：自有 UUID 对话实际提交，固定原生文字流、STOP 且无 Error，复制／重试按钮经无障碍确认可见、启用、可点击。Responses 采用 35 秒完成、每 5 秒心跳场景；Chat 作对照。原生正文匹配与按钮检查通过，未对正文做截图视觉核对；新版旧卡片内部诊断计数不适用，不影响上述必需断言。
- API 102 热重载返回 SUCCEEDED，小爱 PID 保持不变；随后 `--dual-tools --no-restart` 在两种兼容默认设置下完整通过，包括 DI 注册表工具／schema、真实前后台动作、跨工具续调拒绝和正常清理。

`--focus --popup` 尚未通过：服务动作唤醒后没有 resumed 宿主页面，`FloatManager.getRootLayout()` 为 null，未满足浮窗已显示的前置条件。加入关闭麦克风／电源键来源的试验同样未建立浮窗，未据此放宽探针或宣称实体电源键浮窗焦点链已验证；三套模式菜单的实际 popup 测试通过不等于此项焦点验证。

最终 Debug 已安装。测试后停止本轮两个本机 mock，删除 18765／18766 reverse 映射；无活动 instrumentation、无 DisplayHelper，仅 display 0。无障碍服务列表逐字保持原值，重新连接后健康查询为 `connected=true,busy=false`；各 runner 恢复原配置并清理自己的对话／页面。

以上协议为 mock，控屏为确定动作与自有页面验证，未调用真实外部模型或验证新版官方模型自主规划成功率。旧版绑定保留并经本地回归，本轮设备未降级重测旧小爱。研究 APK／反编译材料只保留在忽略目录 `work/`，不进入发行物。未提交、推送或发布。

## 动态页面局部稳定区域（2026-10-17）

针对广告轮播等局部变化造成的重复拒绝，`PageGuard` / `VisualRegion` 新增局部画面锚定。纯图片目标比较点击点及周边画面；覆盖控件的父容器汇总文字改变时，比较整个控件及周边画面。区域外刷新可以继续，不放宽目标语义、位置、身份、启用、焦点、覆盖节点顺序、前台包或显示实例检查。没有新增自动重试、移动目标追踪或模型可绕过的稳定性开关。

新增 13 项原生图像解码的 Robolectric 测试，覆盖无关广告变化、点击点／周边／控件角落／微小图标变化、无文字广告容器与空标签、新遮挡与节点替换、坏图和尺寸变化、JPEG 舍入误差、边缘坐标及三种点按动作；另新增 5 项树读取回归，覆盖节点预算、循环引用、不同遮挡节点与文本／密码处理。最终 232 项 JVM/Robolectric 测试通过（0 failures/errors/skipped）；Debug、Release、AndroidTest 构建及两套完整 Lint 通过（0 errors，Debug 31 / Release 24 warnings）；`git diff --check` 与设备脚本语法检查通过。

随后经用户授权，在 Xiaomi 25019PNF3C / xuanyuan、API 37 上实测微博、淘宝、京东、抖音、小红书、应用商店。微博广告过渡暴露祖先循环引用耗尽 250 节点预算的问题，现按来源节点身份去重并改为广度优先读取。最终包微博底部导航、热点标签及稳定区域分别 20/20 放行，广告真正变化的 16 帧全部拒绝；等待 8 秒后实际点击星品、返回首页成功。其他应用的动态页面与稳定入口、真实导航及限制详见 [动态页面实测](phone-use-dynamic-pages.md)。未请求模型，不是自主业务任务成功率验证。

两帧稳定不保证注入时画面仍稳定，纯图片的注入前最终检查仍复用最近截图、只刷新节点，不能捕获其后的纯像素变化；真正的窗口／目标变化与过渡帧仍可触发重新规划。下文较早“纯图片必须整张截图一致”的记录属于此前规则。

## 非全屏浮窗思考强度菜单修复（2026-10-17）

用户截图中的“＋ → 回答模式”来自独立的 `FloatStateMorePopup`，此前输入栏／全屏功能栏的 Hook 没有覆盖这条路径。现补充该精确版本入口：全局外部模型开启时，将子菜单替换为当前模型支持的思考档位，同步 Miuix 二级菜单选中状态；选择经现有 `set_reasoning` IPC 按 endpoint/model/api 保存并校验过期配置，不调用原生模式切换。官方模式保留原菜单，其他输入／附件菜单继续使用原处理，重载时清理浮窗与菜单快照。

新增 JVM/Robolectric 回归覆盖 GPT‑6.1 Sol／Qwen／未知模型菜单、选中状态与重新打开、经真实 BridgeProvider 保存、切换模型／关闭外接后拒绝旧菜单写入，以及官方模式菜单保持。

本地联合验证：214 项 JVM/Robolectric 测试通过（新增 5 项，0 failures/errors/skipped）；Debug、Release、AndroidTest 构建及 Debug／Release 完整 Lint 通过（0 errors，31／24 warnings）。随后收紧保存范围断言，新增 5 项再次通过。`git diff --check` 通过。

用户连接 ADB 后，已在 Xiaomi 25019PNF3C / xuanyuan、API 37、小爱 8.2.65.4714（508002065）安装修复版 Debug 与 AndroidTest。通过原生 VoiceService 唤醒非全屏“我在听”浮窗，实际点击右侧星形按钮 → 回答模式：当前 `deepseek-flash` 显示模型默认／关闭思考／low／high／max；high 切到 low 后，重新打开仍选中 low，模块私有配置也确认为 low。关闭外部模型后重新打开，恢复自动／快速／专家原生菜单；最终恢复外部开关与原 high 档，重新打开核对选中 high。菜单截图保存在 `work/reasoning-popup-fix/menu-verified.png`。无障碍服务列表保持一致，无新增 reverse，小爱进程存活。

此项实测验证原生浮窗菜单与 IPC 保存；唤醒使用系统服务动作，未按实体电源键，也未发送模型推理请求，不代表服务端参数／回复验证。未提交、推送或发布。

## 敏感操作灵动岛确认（2026-10-17）

前台控屏的敏感操作确认已从旧 Android 无障碍悬浮窗迁移到小爱灵动岛：显示操作说明，提供“确认本次操作／停止任务”，等待时不打开小爱页面。确认绑定当前 UID、控屏任务和一次性 token，仅批准当前动作；拒绝、取消或 60 秒超时停止任务。批准要求解锁，之后保留页面／目标复核；空白确认说明回退到已有操作意图，完整非空说明不截断。后台敏感动作继续 PAUSE／手动接管。设置页独立模型测试没有宿主灵动岛观察器，遇到确认会立即 PAUSE 并提示从小爱对话发起。

本地联合验证：206 项 JVM/Robolectric 测试通过（0 failures/errors/skipped），覆盖确认、拒绝、取消、模拟单调时钟推进后的超时、过期／重放 token、任务与 UID 隔离、完整说明及空白说明回退；Debug、Release、AndroidTest 构建及两套完整 Lint 通过（0 errors，Debug 31 / Release 24 warnings）。`git diff --check` 与设备脚本 Python 编译检查通过。

首次安装时 ADB 断开；用户重新连接后，已在 Xiaomi 25019PNF3C / xuanyuan、API 37、小爱 8.2.65.4714（508002065）安装最新 Debug 与 AndroidTest。`--action-confirmation` 实机专项通过：经实际注册的前台工具及通知 PendingIntent 验证同意、拒绝、停止与真实 60 秒超时。同意后自有测试按钮恰好一次触摸／点击，其他三种情况均为零；等待期间主屏 Activity／焦点不变，无旧无障碍确认悬浮窗，结束后会话与确认卡清理，旧按钮失效。截图 `work/action-confirmation-island.png` 已人工核对灵动岛摘要正常弹出；完整说明与展开岛按钮映射由通知载荷断言覆盖，未人工点击展开岛按钮或实测重新锁屏竞态。测试使用固定自有页面，不请求模型，配置由 runner 恢复。

同包 `--foreground-consent` 实机回归通过，覆盖首次当前应用自动切前台、中途同意／拒绝、重新观察、拒绝后的 direct answer／原生 STOP gate，以及等待期间主屏焦点和输入保持。测试后无活动 instrumentation、无 DisplayHelper，仅 display 0，无新增 reverse；无障碍服务已重新连接，健康查询 `connected=true,busy=false`，原服务列表逐字保持一致。未提交、推送或发布。

## Responses 回复操作按钮修复（2026-10-17）

已复现一条导致“正文已显示但复制／重试按钮缺失”的 Responses 路径：首段文字后，上游每 5 秒发送推理进度或注释心跳，35 秒后发送 completed；旧转换器吞掉这些活动，触发宿主 30 秒逐行 SSE 空闲超时。旧包实机无正常 Done、出现原生 Error，界面停留在停止按钮。短回复的 mock Responses 原本正常，不能用它排除该故障。

`ResponsesStream` 现将已收到的注释、空帧与未显示事件转成固定无内容心跳，保留正常收尾。空文字 delta 也不再阻止 completed 中的最终正文。failed、incomplete、拒绝、提前 EOF 仍失败，不提前执行工具。没有强制显示按钮，也没有延长真正静默连接的超时。

联合验证：191 项 JVM/Robolectric 测试通过（0 failures/errors/skipped，新增 5 项流式保活与空 delta 回归）；Debug、Release、AndroidTest 构建与完整两套 Lint 通过（0 errors，Debug 33 / Release 26 warnings）。

Xiaomi 25019PNF3C / xuanyuan、API 37、小爱 8.2.65.4714（508002065）已安装修复 Debug。相同 35 秒延迟场景修复后通过，并用下述可复现脚本再次验证：固定回复经原生文字流精确匹配，随后 STOP、无 Error，界面复制／重试按钮可见、启用且可点击；Chat Completions mock 对照同样通过。另以保存的真实服务地址／密钥／模型、临时强制 Responses，在自有新对话发送一条固定短请求，同样通过；此项不代表真实服务的长推理或任意任务质量。

专项探针只操作自有 UUID 对话，结束时取消对应请求、恢复原入口并删除自有记录，完整恢复原配置；不输出正文、密钥或服务地址。正文的无障碍树不可读，因此自动检查明确区分原生正文匹配与实际按钮可见性；旧包及短回复画面另经截图核对。

最终包另通过 Responses 与 Chat 两套原生 Hook 回归，覆盖文字／工具流、截图与工具结果回传、记忆和 gate／路由；流式用例补充严格断言恰好一次终态 Done、原因为 STOP／TOOL_CALLS 且无 Error。测试后停止自有 mock、删除本轮 reverse 映射，无 DisplayHelper；无障碍服务列表保持一致，健康查询 `connected=true,busy=false`。未提交、推送或发布。

可复现 mock：启动 `python3 scripts/reply_actions_mock.py --response-delay 35`，添加 `adb reverse tcp:18766 tcp:18766`，安装 Debug/AndroidTest 后运行 `python3 scripts/device_test.py --reply-actions`。该场景交替发送注释与推理心跳，日志只含事件类型和相对时间。Chat 对照使用 `--reply-actions-chat`；真实服务短回复使用 `--reply-actions-current-config`（保留地址、密钥、模型，临时强制 Responses，结束恢复）。测试完成删除 reverse 映射并停止自有 mock。

## 前台控屏动态页面与通知终态修复（2026-10-17）

修复两项用户反馈：局部页面刷新不再按整棵无障碍树变化终止任务，通知也不再把失败显示为“手机操作已结束”。点击按目标区域的控件语义、资源 ID、位置、覆盖节点与窗口进行验证；输入限定原聚焦编辑框，前台注入前再检查一次。目标移动、替换或失焦时不执行旧动作，在同一会话返回新截图/树与 `CONTINUE`，要求模型重新规划。跳过动作仍受步数预算约束。双击首击后发生变化明确报告部分执行并停止，不自动重试。

灵动岛跨工具步与规划等待保持进行中，只在同一任务明确终态且执行已退出后收尾；明确 `finish` 显示完成，异常/取消显示“手机操作已停止／任务未完成”，PAUSE 显示接管。

最终联合构建：186 项 JVM/Robolectric 测试通过，0 failures/errors/skipped；Debug、Release、AndroidTest 构建及两套完整 Lint 通过（0 errors，Debug 33 / Release 26 warnings）。包含 18 项 PageGuard、6 项 NativeSession 重新规划、4 项通知生命周期测试。

Xiaomi 25019PNF3C / xuanyuan、API 37、小爱 8.2.65.4714（508002065）已安装最终 Debug 并通过 `device_test.py --robust-screen`：真实注册前台工具在自有页面每 100ms 更新无关文字时成功点击；移动/替换目标后的旧动作零触摸注入，原会话重新规划后成功点击；总计严格验证 3 次真实触摸按下、3 次按钮点击。每 50ms 采样通知，覆盖所有续步与三个等待步骤，只有显式 finish 后出现完成通知。runner 恢复原配置并清理自有页面。

最终包回归通过 `--dual-tools`（全局默认 true/false 下两工具分别选屏、跨工具续调拒绝、后台实际点击/滑动与主屏隔离、helper/display 正常退出）、`--focus` 与 `--focus --popup`（跨步通知、快速任务交接、停止按钮、收尾不抢前台、完成通知返回全屏/浮窗原生对话入口），以及 `--conversation-return`（两条自有本地对话、真实工具上下文、跨上下文续步/完成后点击通知仍精确返回首次对话、每任务独立 PendingIntent）。

测试后无活动任务、无 DisplayHelper/活动 instrumentation、仅内置显示屏、无 reverse 映射；恢复无障碍 Binder 连接，健康查询为 `connected=true,busy=false`，原无障碍服务列表保持一致。未提交、推送或发布。

以上为确定动作、真实工具与设备执行验证，未调用模型，不代表真实模型自主规划成功率。纯图片目标仍保守要求截图一致，注入前的节点检查不能证明截图之后所有纯像素变化均被捕获。

## 双控屏工具与会话返回（2026-10-17）

按用户顺序先将模型工具拆分：`miaiplus_phone_use` 固定从主屏开始，`miaiplus_background_phone_use` 固定从虚拟屏开始；两者同时注册和导出，不改全局偏好。续步绑定来源工具，调用错误工具不结束原会话；后台经允许切到前台后仍沿用原工具。旧入口与直接测试保留默认屏幕设置，界面文案明确其范围。补充外层 IPC 取消/watchdog 到当前原生步骤的绑定，取消前置竞态与旧步骤取消不会影响后续任务。

当前联合验证：162 项 JVM/Robolectric 测试通过；Debug、Release、AndroidTest 构建与两套完整 Lint 通过（0 errors，Debug 29 / Release 26 warnings）。双工具实机 runner 已通过：真实工具定义和代理、两种全局默认与中途切换、错误工具续调、后台实际点击/滑动送达、主屏隔离和正常 helper/display 退出。灵动岛授权专项通过：首次应用自动切前台、中途真实通知同意/拒绝按钮、等待时主屏保持、拒绝 direct answer 和宿主 STOP gate；gate 为真实宿主方法直接调用，未运行真实模型混合工具批次。

完成岛按首次工具 `ExecutionContext` 的 agent/chat 定位原生页面，任务启动前只读查询；旧入口读取当前全屏/浮窗话题。续调不更新目标，每个任务 PendingIntent 独立。实机在两条本地自有对话中验证：跨上下文续调/结束、先切换另一对话再点完成通知，页面 chatId 与实际显示 topicId 均返回原对话；不同任务各自正确返回。测试记录已精确清理。全屏与语音浮窗入口的焦点通知、快速任务切换、停止按钮和完成后点击返回回归均通过；修复无话题主对话浮窗经启动路由被静默拦截的问题，改为直接进入原生主对话页。

本次设备：Xiaomi 25019PNF3C / xuanyuan，API 37，小爱 8.2.65.4714（508002065）。最终 Debug 包已安装，测试后无活动任务、无 helper、仅 display 0、无新增 reverse 映射；无障碍服务已重新连接，测试配置已由 runner 恢复。未提交、推送或发布。

## 虚拟屏审查修复（2026-10-17）

按用户逐项决定修改问题3、4、6–17；问题1的目标 ROM `FLAG_SECURE` 行为、问题2的运行中锁屏策略及暂不确定事项不变。问题5接受页面不保留，不新增任务恢复；关屏结束虚拟屏 Activity/task，模块不主动 force-stop 或杀目标应用进程，系统后台缓存回收仍由 Android 决定。

主屏冲突以真实 TaskInfo 组件与任务 ID 判断，查询失败停止。首次目标就是主屏当前应用时转前台并重新观察；中途通过小爱灵动岛征求同意，不打开小爱页面，拒绝/超时暂停并将原生工具结果标为本轮终态。请求绑定 UID、任务与一次性 token；同意后再次验证当前应用，作废旧截图/坐标。完整能力探针与准备、执行、跨步等待、收尾互斥，探针期间拒绝新任务。

修正取消后的异步资源释放、deadline/watchdog 同步续期、实例缓存隔离、最终帧节流、原焦点节点精确输入回读、输入失败传播、手势时间、LOCAL 输入法回读验证、主动唤醒解除静默与探针空基线。新增 JVM/Robolectric 回归覆盖这些边界。首轮修复阶段仅做本地验证；后续已安装 Debug/AndroidTest 并完成本节上方的双工具、真实灵动岛授权及会话返回实机专项。实际手势隔离与强制清理前 helper 正常退出已有新增设备证据；信息流滚动位移仍未重新验证，旧 ScrollRunner 只证明会话持续。本轮专项采用确定动作与本地测试页面，未验证真实模型自主规划成功率。

本地联合验证：146 项 JVM/Robolectric 测试全部通过（较本轮开始新增 43 项）；`lintDebug`、`lintRelease`、`assembleDebug`、`assembleRelease`、`assembleDebugAndroidTest` 通过，未跳过完整 Lint；`git diff --check` 通过。后台 helper 在独立 root `app_process` 中查询隐藏系统 API，新增局部 Lint 说明；其他 ROM 的该接口兼容性仍需实测。没有提交、推送或发布构建产物。

## Miuix 与最新虚拟屏集成（2026-10-17）

合入 `ui/miuix-settings` 的三页导航和虚拟屏设置，并与 main 工作区最新后端源码联合验证，包括页面变化检查、会话续期、截图淘汰、中文输入回读及宿主防抢焦点修复。后端原有未提交改动保持未提交，前端合并不代替后端提交或发布。

直接测试按已保存执行模式处理界面：主屏控屏先隐藏安全设置窗口，虚拟屏控屏保留设置页，纯对话也保留界面；虚拟屏检查进行中拒绝从设置页启动直接测试。虚拟屏开关注明只影响新任务。按需前置检查、检测确认、逐项三态结果及默认关闭行为保留。

本轮完整集成源码的 103 项 JVM/Robolectric 测试、`lintDebug`、`lintRelease`、`assembleDebug`、`assembleRelease`、`assembleDebugAndroidTest` 全部通过；未跳过 Lint。新增三项 IPC fixture 回归覆盖虚拟屏/主屏/纯对话的界面交接，未请求模型。本轮未安装 APK、操作设备或重跑真实模型任务；历史实机记录见下文。

## 虚拟屏前端（2026-10-16）

独立前端分支新增「控屏 → 虚拟屏」二级页，接入既有 `background_display_status` 与 `background_display_probe`。包含执行模式、root/解锁前置检查、测试应用搜索与选择、检测确认与逐项结果详情。操作前检查控屏任务；不自动启动 helper 或重复探测；不修改后台源码。96 项本地 JVM/Robolectric 测试通过，原有后台三处 Lint 错误仍保留。先推送 PR，再在 Xiaomi 25019PNF3C / API 37 横屏实测：开关恢复、前置检查、搜索/选择、确认/取消、详情、重建与返回通过；两轮能力检测均为 7 项通过、无障碍树未验证。定向 BACK 不代表中文输入通过。测试包保留手机既有后端及 record 支持类，214 个类逐一比对一致；没有覆盖为分支里的旧后端。原配置仅注入报告时间变化，无障碍状态保持，无残留模块虚拟屏/helper，未重启小爱或请求模型。详见 [界面验证](settings-ui.md)。

## 配置界面导航（2026-10-16）

本轮只在 `ui/miuix-settings` 的独立工作区调整前端。底栏按首页、模型 API、控屏排列；首页改为 KernelSU 式状态总览，历史注入报告标注时间。二级页进入/返回使用 260 ms 绘制层过渡，结束移除旧页面，不恢复整页尺寸动画。草稿切页确认、滚动位置、深浅色与必要数据发送确认保留，文案精简。

Debug、AndroidTest 与优化 Preview 构建通过（Preview 显式跳过基线阻塞的 lintVital）；92 项 JVM / Robolectric 测试通过，包括底栏顺序、草稿保存/放弃/取消、二级页双向过渡、报告状态与按需后台屏查询。截图见 [配置界面](settings-ui.md)。本轮没有同步 main 工作区、安装手机、重启小爱或请求模型；旧实机性能数据属于此前版本。完整 Lint 仍有原有后台 `DisplayHelper.java` 的三处阻塞。

## 配置界面性能（2026-10-16）

前端分支已同步远程 main `095156c`。新增优化的 Preview 构建，移除整页尺寸过渡，限制状态轮询范围，模型列表按可见项组合。Xiaomi 25019PNF3C / Android API 37 的独立测试包 12 次滚动对比：原 Debug 进程 CPU 173.71%、帧耗时 P95 93 ms；优化 Preview 为 27.75%、7 ms，0/794 帧错过截止时间。89 项测试通过。该对比包含构建模式变化，不代表单独去掉动画的收益。后台源码未修改；完整基线 Lint 仍有 3 处原有后台错误。详见 [性能与验证记录](settings-performance.md)。

以下为此前实现与验证记录。

## 配置界面分支（2026-10-16）

`ui/miuix-settings` 将模块设置改为 Miuix 0.9.4 Compose 界面，含配置首页、模型服务、控屏设置、运行状态和直接测试。仅接入原有配置与服务接口；不包含 main 工作区未提交的后台屏修改。交互与集成说明见 [配置界面](settings-ui.md)。

Debug、Release、AndroidTest 构建与 88 项 JUnit（含 5 项 Robolectric Compose 测试）通过，并生成深浅色及各设置页面截图。兼容检查将后台屏检查入口从旧 Java 页面迁至运行状态页，仅点击时查询；修正设备 runner 的旧截图构造调用。与 main 工作区后台屏新代码合并的隔离副本也通过上述构建、测试及两套 Lint（0 errors），该后台代码未包含在 UI 分支中。本次没有安装实体设备或验证 XiaoAI、root 与模型服务。UI 基线的 Lint 仍被未修改的 `helper/DisplayHelper.java` 阻塞：第 144、238 行 `WrongConstant`，第 229 行 `BlockedPrivateApi`；新增界面没有 Lint 错误。以下正文保留原分支的历史验证记录。

本轮复核修复（2026-10-16）：新任务在旧任务终态轮询前开始时替换观察器并保留返回入口，避免灵动岛和停止按钮消失；区分安全中心普通页面与授权窗口，普通页面可启动其他应用，其他授权仍暂停。68 项 JUnit、debug/release/AndroidTest 构建和两套 Lint 通过；本次设备专项与历史验证分列如下。

日期：2026-10-16（Asia/Shanghai）。预发布测试版；下列“通路通过”不代表真实模型任务成功率保证。

## 本次实现

- 2026-10-17 后台控屏三项会话级缺陷修复（真实任务反馈“滑不动、会话反复过期”的根因，均实机复现后修复）：
  - **页面自变即销毁会话**。`NativeSession` 在每次调用前重观察，任何树差异都直接终止会话并要求重来。信息流、加载中的列表和自动刷新页面本来就会自己变化，于是每一次滚动都被判成“页面已改变”。新增 `PageGuard`：只有以控件为锚点的动作（`tap`/`double_tap`/`long_press`/`type`）要求页面未变，其余动作只要求前台应用未变。点击仍不会落到别的页面上。
  - **预算按任务而非按步**。`RunState.deadline` 从首次调用起算 240 秒，长任务中途必然到期，之后每次调用都被判成“会话已过期”。改为按步预留、每次被接受的调用续期（`RunState.extend()`）；`NativeSession` 的看门狗也改为“持续无进展才停止”，而不是任务总时长。终于只停真正卡死的会话。
  - **未读截图挤爆**。`FrameStore` 只允许 4 个未读观察，超出即抛“尚有未读取的屏幕观察，请重启当前任务”，同样表现为会话中途失效。改为超出时淘汰最旧的一条，任务继续。
  - 另补：后台屏若被系统确认窗口（`com.miui.securitycenter` 等）占据，`BackgroundActions` 明确返回 PAUSE，不再对着权限框滑动——那是“列表不动”的另一种真实原因。
  - 实机证据：`ScrollRunner`（新增，`-e scroll true`）在酷安真实信息流上连续滚动 12 步，每步之间故意等 1.8 秒让页面自行加载；断言单一会话贯穿全程、前台始终为酷安、结束前用户主屏栈顶任务未变。通过。
- 2026-10-17 宿主界面不再被反复拉到前台（问题 1）：
  - 原来只在 `onActivityResumed` 里 `moveTaskToBack`，而该回调发生在宿主 Activity **已经恢复并绘制之后**，所以每次工具调用用户都会看到小爱闪一下。改为在宿主自己的“把界面带到前面”入口处拦截：`mainui.c4.Q(Intent)`（`UiManager.start*` 全部汇入的真实 `startImpl`；dex 里的 `G` 只是 4 条指令的 synthetic 桥）、`TabHostMainActivity$a.g(...)`、`LaunchHomeRouterActivity.N()` 与 `L(String)`。挂不到的入口记入 `missingGuards` 并写进 `hook_report`，不静默忽略。
  - 任务结束后不再自动跳回小爱：只发焦点岛卡片（“已完成，点击返回小爱查看结果”），点它才回去。`hostQuietUntil` 覆盖宿主渲染最终结果的那几秒，避免收尾时又跳一次。
  - 实机证据：`device_test.py --focus` 通过，新断言为“结束后宿主未回到前台”“返回动作只由完成卡片提供且点击后可回到会话”。
- 2026-10-17 后台虚拟屏端到端实机验收（新增 `scripts/background_display_test.py` + `BackgroundRunner`）：用户主屏由一个会记录每次触摸的 fixture 占用，Agent 在虚拟屏上完成 launch→back→finish 并写入中文，逐项断言主屏未被影响：主屏 app 焦点与栈顶任务前后一致、fixture 未收到任何合成触摸（`lastTouch=none`）、主屏输入框保持为空、任务结束只剩 display 0 且无残留 helper。中文输入在独立会话中经该后台屏的可编辑节点写入，并由下一次观察的树回读确认（`text=后台输入隔离标记`）。全程真实设备（xuanyuan，`CP2A.260605.016`，API 37，helper uid 0）。
- 2026-10-17 后台虚拟屏修复：**树恒为空**。helper 把某屏前台报成组件 `包名/Activity`，树的根节点只报包名，`treeOnDisplay`/`setTextOnDisplay`/回读三处直接整串比较 `getPackageName()`，永不相等 —— 表现为任何后台观察都写“本机未提供后台屏的无障碍树”、`type` 一律被拒。改为先取 `/` 前包名再比（`matchesPackage`）。修好后同一屏同一页面立即能读到 `EditText`。
- 2026-10-17 后台虚拟屏修复：**写入被接受却报失败**。`ACTION_SET_TEXT` 返回 `true` 只代表视图接受请求，值在下一帧才生效；而回读复用了写之前取得的窗口对象，视图重建后可能仍报旧值。两者叠加时树里已出现新文本、`setTextOnDisplay` 仍返回 `false`。改为写完后在调用线程、用每次重新枚举的窗口对象做有界重试（≤1.2s），不把等待放在无障碍服务主线程上；仍读不到则判失败，“接受”不等于“生效”。
- 2026-10-17 能力报告更新：`tree` 由 `untested` 改为实测 `supported`（应用在该屏后 `dumpsys accessibility` 有对应 `Display[<id>]` 与 `TYPE_APPLICATION` 窗口；无应用时不合成窗口，报空树而非不支持）。`text/key` 保留“按键不改变帧则不声称送达”的判定，同时补充节点写入与树回读证据。8/8 已测项通过。
- 2026-10-17 前端同步：`MiAIPlus-miuix-settings` 与主模块共用 `applicationId io.github.miaiplus`，安装后会**替换**主模块 APK，其内置后端副本停在 `4be55fc`，不含后台虚拟屏本轮全部改动。已同步 `BackgroundActions`/`BackgroundDisplay`/`BridgeProvider`/`ScreenService`/`helper/DisplayHelper` 五个文件；前端 96 项 JUnit、`assembleDebug`、`lintDebug` 通过。

- 2026-10-16 后台虚拟屏（新增，默认关闭）：root helper + 自有内容 VirtualDisplay，控屏任务在独立屏幕运行，用户继续使用主屏。Xiaomi 17（xuanyuan，`CP2A.260605.016`，API 37）实测 7/7 已测项通过：建屏、截图、启动、指针、按键、焦点隔离、销毁隔离（`tree` 归无障碍服务，如实报未测）。三项证据要点：截图走该屏自身输出，查不到帧时失败而非退回物理屏；已在主屏可见的应用拒绝启动（否则 `singleTask` 会把用户的任务搬走）；另一块屏存在时主屏窗口焦点读数为 `null` 属上报形态，判定改以 app 焦点与栈顶任务为准。详见 [后台虚拟屏](background-display.md)。
- 2026-10-16 后台虚拟屏修复（本轮实机发现）：修复 helper stdin 被提前关闭导致所有命令 `IOException`（命令通道就是该流）；修复 `dumpsys` 多行 dump 被合并成一行后 `surfaceFlingerId` 误取物理屏 token（会静默截用户主屏）、`focusPackage`/`tasks` 归属错屏；修复错误回复把 code 当键导致模块读不到、所有失败塌成一条通用错误；修复 `create`/`swipe` 多参数被截断。探测输出改为 `supported`/`unsupported`/`untested` 三态并附证据，未测不等于支持。

- 2026-10-16 API 多配置：保存命名的 Endpoint / Key / 模型 / API 协议，支持另存新增、重命名、删除和下拉快捷切换；旧单套配置保留为默认配置。未保存修改切换前确认，重名拒绝，至少保留一套；全局开关、控屏规划和步数独立保存。异步模型列表丢弃已切换服务的结果，页面重建保留编辑内容。集合和运行时字段一并提交，Config 读取单次快照防止地址/密钥混配。
- 2026-10-16 命名：界面使用 phone use，独立工具为 `phone_use`，原生工具为 `miaiplus_phone_use` / `NativePhoneUseTool`；旧 `computer` 协议设置兼容读取。通知渠道 ID 保留以沿用已有系统设置。原生新请求刷新 Endpoint，记忆 HTTP 构造沿用本次配置快照。

- 2026-10-16 复核：任务快速衔接时切换 `screen_task`、观察器和停止令牌；旧回调失效，沿用原始隐藏入口，不在主线程等待旧 IPC 退出。安全中心可见权限标题区分启动确认、其他授权和普通页面，避免仅凭包名误拦截；点过启动允许后出现其他授权仍暂停。
- 2026-10-16 测试残留清理：收起小爱时会露出后台上一任务，先前残留的 launch fixture 因此短暂显示。已移除设备旧测试任务；测试 launcher 不保留历史/最近任务，主机脚本结束时通过 shell 专用广播关闭仍在前台的 fixture，Runner 结束移除后台模块测试任务。日常 launch 仍直接使用所请求包名的显式 launcher，不经过 fixture。

- 0.3.3：修正无障碍 onInterrupt 仅代表反馈中断、并非连接丢失；只有实际连接失效才取消任务，旧实例销毁不取消新实例的工作。
- 0.3.3：统一控屏 launch，提供本机应用列表、只解析合法 launcher、消除同包多个入口的名称歧义；目标已在前台时不重复启动。匹配小米安全中心启动确认后自动允许本次，再等待目标稳定前台；未打开、其他授权或超时明确 PAUSE，不把 startActivity 返回当成功。详见 [启动行为](app-launch.md)。

- 0.3.2：原生和独立循环均支持 action.intent；岛右侧每 700 毫秒检查变化，等待模型时保留最近意图，缺省按动作回退。普通意图不触发敏感确认，不显示原始推理或输入字段；安全构造 JSON、限制文本长度、更新不反复弹卡片。intent 不参与重复执行指纹。
- 0.3.2：“root 控屏前检查无障碍”只检查新任务；健康时直接执行，失效才执行有界 root helper，确认 Binder 重连后开始。后续 CONTINUE 不再 root 检查，执行中断线只停止任务。迁移时清理旧 daemon 与 service.d，旧恢复接口不再取消任务。详见 [任务前检查](accessibility-guard.md)。

- 0.3.1：移除常驻无障碍停止悬浮键；展开焦点卡片通过 `param_v2.actions` 引用 `miui.focus.actions` 中的同 UID 不可变广播 PendingIntent。标准通知保留同一停止动作，敏感操作确认窗保留。
- 0.3.1：Provider 用 `screen_task` 标识整个任务，连续工具返回和规划等待保持同一生命周期；仅在会话已终止、执行线程退出后确认结束。绑定任务 ID 防止旧观察线程恢复新任务；控屏期间阻止宿主界面更新将小爱重新拉到前台。无坐标的 wait 允许页面更新后重新观察。
- 0.3.1 历史实现：曾使用常驻 root daemon；0.3.2 已按任务前检查替代。

- 迁移到 libxposed API/service 102：使用现代 `XposedModule` 生命周期、API 102 元数据和 `XposedService` 热重载；清理旧 Hook、原生客户端、ToolRegistry 代理、诊断广播和 Activity 回调后再重新安装 Hook。控屏或异步模型工作进行中时拒绝热重载。
- 控屏开始时在小爱 UID 发布 HyperOS 原生焦点通知岛“正在操作手机”，保存原始 Activity/任务入口并在完成后恢复全屏页面；快捷键系统浮窗隐藏并放开输入，完成时自动展开通知岛，点击打开小爱查看结果，PAUSE 提示接管，锁屏不强行拉起界面。

- 补齐普通文字聊天的自定义 API 路由：原 AUTO/QUICK 会经 AIVS Nlp.Request 绕开 OSBot。仅在全局外部模式开启时，聊天页发送选择原生 MiClaw 分支，保留支持校验、历史、卡片与现有工具链；默认模式执行原判断，不改原模式或存储。配置不可读时停止提交并提示。

- 修复外部模型请求取消/断连导致的小爱闪退：Xposed 将 IOException 包装为 InvocationTargetError，原拦截器让该 Error 逃出 OkHttp 异步线程。新增 TransportBoundary，展开反射/代理包装并保留原 IOException；适配错误转换为不含请求内容的 IOException，虚拟机致命错误不吞掉。Responses 转换失败关闭原 body。
- 聊天页原“自动 / 快速 / 专家”入口在全局外部模型开启时替换为模型对应的思考档位；普通输入栏和全屏功能栏分别适配。默认官方模式保留原生菜单、枚举、回调及存储。
- 新增 `ReasoningPolicy`：已确认 OpenAI 模型按各自有效 effort 集合显示（GPT‑6.1 Sol 无 none/minimal）；DeepSeek Flash 使用独立开关/effort，Qwen 已确认混合模型使用开关。未识别模型/协议组合仅显示模型默认；不推断任意网关扩展。
- 选择经 UID 校验 IPC 按 endpoint/model/api 单独保存，拒绝过期菜单与非法档位。新请求读取当时选择，已在途请求参数不变；外部默认不继承原生 enableThinking 布尔值。原生与独立模型请求均注入正确线上字段，Responses 回放缓存按服务/模型共享于不同档位。

- 新增独立 API 协议选择（auto/responses/chat）。自动将 `gpt-6` 前缀模型转至 Responses；可手动选择以支持服务别名。
- 全局主模型、记忆、gate/能力判断/路由、独立文字/控屏循环均支持 Responses；实际发送 input/input_image/扁平函数工具/call_id，接收 output 与 Responses SSE。原生前端仍使用原消息和 Agent 事件解析。
- Responses 不发送固定 temperature/top_p，使用 store:false；独立历史保留原始 output，原生路径使用有界内存缓存回放加密 reasoning/phase。工具调用仅在完整 completed 输出验证后释放，异常/不完整响应停止。独立输出预算 16384；GPT-6 原生小额预算至少 8192（含推理 token）。

- 原生结构化 `miaiplus_computer` 函数接入原 ToolRegistry；显式支持截图像素、屏幕像素和归一化坐标，旧 controlApp 保留。
- 默认“小爱当前模型”逐步规划：`controlApp` 返回会话号、截图、无障碍树与 CONTINUE；原主模型继续提交单步动作。不要求填写外部地址、Key 或模型。
- 可选全局外部主模型：在精确支持版本的 DynLlmClient / 直接 Taiyi、OpenAI、Responses、Anthropic、记忆摘要非流式请求以及 intent 路由/gate 入口替换模型配置，保留原消息、提示词、工具、原生 SSE 与工具执行。仅覆盖 OSBot 推理入口，不能宣称所有独立云服务都已替换。
- 外部推理使用独立 Ktor/OkHttp 网络实例，不复用小米账号、ServiceToken、设备签名和日志拦截器；关闭重定向。
- CONTINUE 观察清除原直接答案标记，图片在 ToolResult 阶段附加，避免后续消息复制丢失；JPEG 元数据使用编码后尺寸。
- 一次性截图管道绑定调用 UID，60 秒失效；原生 TOOL 消息构造时附加 ImageContent。图片加入小爱后，历史保存行为由原应用控制。
- 保留独立外部 computer / Open-AutoGLM 协议循环与第三方文字对话；默认并不运行第二个模型。
- 本机截图、无障碍树、点击/双击/长按/滑动、中文输入、启动应用、返回/主页、停止、步数/超时/重复动作限制。
- 精确适配小爱 8.2.65.4714 / 508002065；Hook 安装失败回滚；不改小爱 APK 或其磁盘配置。
- 默认服务地址、API Key、模型名均为空，全局外部开关默认关闭。源码包采用发布文件白名单，不打包逆向资料、原 APK、实机日志、工具链和签名密钥。

## 已通过的验证

- 2026-10-16 后台虚拟屏实机：83 项 JUnit 通过；debug/release/AndroidTest 构建通过，两套 Lint 均 0 errors。固定目标 `com.miui.calculator/.cal.CalculatorActivity` 的 capability 探针：`createOwnDisplay`、`capture`、`launch`、`pointer`、`text/key`、`focusIsolation`、`teardownIsolation` 七项 supported；`tree` 如实报 untested 并说明应到无障碍服务侧测。build `CP2A.260605.016`，helper uid 0。机器可读结果见 [能力报告](virtual-display-capability-report.json)。
- 2026-10-16 后台虚拟屏隔离证据：① 向 display 0 注入 HOME，只有 display 0 的栈顶变为桌面，虚拟屏上的 calculator 任务不动 —— 输入按屏定向；② 目标应用已在主屏可见时启动被拒（`TASK_IN_USE`），前后对比主屏前台应用未变 —— 未抢用户窗口；③ 探针全程主屏 app 焦点与栈顶任务与基线一致，销毁后仍一致。
- 2026-10-16 后台虚拟屏截图路径改为读该屏自身输出：helper 建屏时创建的 `ImageReader` 现在由消费线程持续取帧（旧实现从不读，buffer 队列满后生产者阻塞、屏幕会停止绘制），截图直接复制该帧并缩放（`source=imagereader`），不再依赖 `screencap -d <SurfaceFlinger token>` 的存在。own-content 屏未启动任何应用时不合成帧，此时回退 `screencap`（`source=screencap`）。实机：空屏 `screencap`、启动后 `imagereader`（frames 10→97 持续增长）。
- 2026-10-16 后台虚拟屏输入路径改为进程内注入：触摸与按键都经 `InputManager.injectInputEvent` 反射调用，事件的 display id 与 source 在事件上设置（`SOURCE_TOUCHSCREEN` / `SOURCE_KEYBOARD`），反射句柄缓存一次而非每事件重解析；`key` 报 `source=inputmanager`、`delivered=true`。旧路径每次按键都 `input -d` 起一个进程。
- 2026-10-16 后台虚拟屏送达证据：点击/滑动以同一屏前后帧比对判定（`t0`＝`t1` 为该点无响应，`t2`≠`t3`、滑动后 `t4` 变化），计算器显示由 `4.6e48` 变为 `8`；空屏发键 `delivered=false` 属该屏无焦点窗口，launch 后为 `true`。
- 2026-10-16 `FLAG_SECURE` 实测（debug-only `SecureFixtureActivity`，窗口属性确认为 `SECURE`）：reader 直读该屏输出、`screencap -d` 读同一屏、以及 shell/root 身份 `screencap` 读主屏，**四者都能看到 canary 文本**。结论是该 ROM 对 `screencap` 本身就不拦 `FLAG_SECURE`，reader 路径不是相对旧行为的回退；安全边界由 ROM 决定，换 ROM 必须重测。
- 2026-10-16 后台屏文本写入增加回读校验：`ACTION_SET_TEXT` 返回 `true` 只说明请求被接受，应用可以接受后不生效或被监听器改写，因此现在从窗口重新解析可编辑节点并比对其文本，读不到即判失败，不再把“已接受”当作“已写入”。
- 2026-10-16 后台虚拟屏 IPC：`content call` 实测 `background_display_status` 返回 `root=true`、`uid=0`、`deviceLocked=false` 且不创建屏幕；`background_display_probe` 返回完整 items 与 ROM 全部 `VIRTUAL_DISPLAY_FLAG_*` 实际取值。两方法均限模块 UID / root。
- 2026-10-16 后台虚拟屏释放：holding 状态下杀掉 helper 进程，helper 消失且其屏幕（display 63）一并销毁，设备只剩 display 0，无孤儿屏也无孤儿进程；`ime` 策略实测返回 `LOCAL`（建屏后与 launch 后各测一次）；测试结束后设备无残留 helper、无残留截图缓存。
- 2026-10-16 后台虚拟屏请求标志核对：实际下发 `0xadcb`，含 `TRUSTED`、`OWN_CONTENT_ONLY`、`OWN_DISPLAY_GROUP` 等；**不含** `ALWAYS_UNLOCKED`（不借锁屏工作）与 `OWN_FOCUS`（不需要顶焦，输入均带 display id 注入）。

- 2026-10-16 多配置最终包：77 项 JUnit 通过（新增 9 项配置迁移、切换、重名、删除、空配置、校验和并发快照测试）；debug/release/AndroidTest 构建通过，Lint 均 0 errors（debug 16 / release 13 warnings）。最终 debug 包已安装；设置页专项及原生 Responses Hook 重新验证通过，原用户配置已恢复。
- 2026-10-16 多 Endpoint 专项：设置页实机验证旧配置及密钥保留、保存修改、下拉切换和 Config 的地址/密钥/模型/API 整套恢复通过，测试结束恢复原配置，无真实模型请求。Chat / Responses 模拟模型控屏循环及原生 Responses Hook（模型、记忆、gate、能力和路由请求）回归通过；Chat 首次因测试输入框未获得焦点失败，重跑通过。
- 2026-10-16 复核：68 项 JUnit 全通过（新增安全中心普通页面启动、其他授权阻止启动、启动确认后出现其他授权的边界）；debug/release/AndroidTest 构建通过，Lint 均 0 errors（debug 16 / release 13 warnings）。
- 2026-10-16 测试清理验证：debug/AndroidTest 构建与 Debug Lint 通过；启动 Hook 探针（自动确认计数 1）及全屏焦点/停止/任务切换探针通过，分别结束后检查 Activity 任务栈，均无 LaunchFixtureActivity 或 FixtureActivity 残留。修复包已安装到测试机。
- 2026-10-16 实机：全屏与快捷键浮窗入口通过确定性快速任务衔接探针（暂缓旧观察器的终态读取，再结束旧任务并开始新任务），验证观察器替换、原返回入口保留、实时意图和展开停止；全屏恢复同一 Activity，浮窗保持隐藏并通过完成岛返回原生会话入口。从安全中心普通页面实际 Hook 启动独立测试 APK 通过，自动启动确认计数为 1，目标前台与重复启动保留实例通过。探针直接调用工具后消费一次性截图管道，避免多次观察耗尽未读帧额度。

- 0.3.3：65 项 JUnit 全通过；debug/release/AndroidTest 构建通过，Lint 均 0 errors（debug 16 / release 13 warnings）。新增覆盖应用名称歧义、同包多入口、启动超时、短暂前台、取消、同目标已打开、匹配/不匹配启动确认、只点击一次及点击后仍需前台验证。
- 0.3.3：小米 15 Ultra 实机复现安全中心 ConfirmStartActivity。真实 Hook 探针验证自动“本次允许”计数为 1，随后另一个测试 APK 稳定前台、返回新观察并继续同一会话；按应用名再次打开保留原实例。最终 0.3.3 安装包重装独立测试 APK 后再次验证通过，自动确认计数为 1。未修改系统授权数据库或使用 root 启动。
- 0.3.3：最终包快捷键浮窗、实时意图、连续 CONTINUE 不提前恢复、完成岛和展开停止按钮回归通过。
- 0.3.3：实机模拟模型回归通过 computer / AutoGLM / native 截图、点击、中文输入、观察完成、取消及一次性图片管道，并主动触发 onInterrupt 验证会话继续；它验证执行链，不等于真实模型总会选择正确工具和包名。
- 0.3.2：47 项 JUnit 全通过；9 项实际任务前 helper 脚本主机测试通过；debug/release/AndroidTest 构建通过，Lint 均 0 errors（debug 16 / release 13 warnings）。
- 0.3.2：小米 15 Ultra 实机全屏和快捷键浮窗专项通过。两次 wait/CONTINUE 分别携带不同 intent，断言焦点岛右侧及时变化、onlyAlertOnce 和关闭重复浮动；中间不恢复入口，finish 后恢复同一 Activity / 完成岛；实际发送展开岛绑定的停止 PendingIntent 后任务结束。调试探针改为异步工作但不占用广播队列，避免阻塞它自己测试的停止广播。
- 0.3.2：ADB-root 实机任务前检查通过。Runner 保持系统组件启用但模拟连接不可用，健康路径不调用 root；准备阶段 helper 重绑后 Binder 恢复、准备任务未取消、其他服务保留，执行阶段再次调用 helper 被拒绝且设置不变。测试后恢复原开关。此项模拟“假开启”，不声称复现所有 ROM 掉线原因。
- 0.3.0：43 项 JUnit、debug/release/AndroidTest 构建及两套 Lint 通过。实机 API 102 Hook/Responses 回归通过；专项实机截图确认“正在操作手机”及完成通知岛自动展开；全屏恢复同一个 Activity，快捷键通过原生 VoiceService 唤起真实系统浮窗，检查隐藏/触摸放行及完成通知点击入口。安装新 APK 后小爱 PID 不变，API 102 服务重载回调 SUCCEEDED，重载后继续验证 Hook。其他 ROM 仍需单独回归。
- 0.2.7 普通聊天页端到端模拟服务验证：从全新聊天输入短提示，页面显示服务端唯一标记 `UI_ROUTE_OK_027`；服务端校验 `/v1/responses`、指定模型、Bearer 认证及无小米账号/设备认证头。该测试证明实际页面发送→自定义协议→原生流解析→页面回复，不是真实模型能力证明；测试后恢复原 API 配置。
- 修复前按用户原文输入糖果题，普通聊天回答 36，日志只有官方 Nlp.Request，没有自定义模型调用；另一次短提示回复 OK 也走旧链路。这些答案不构成自定义模型身份或能力证据。
- 0.2.7 普通聊天页使用实际 API 发“只回复 OK”：已出现 OSBot 主模型调用及自定义端点请求，未再走旧 Nlp.Request 回答；服务等待响应头超时，进程存活，停止后续重试。尚无真实 Sol 成功回复，不能验证网关背后的模型身份。

- 0.2.6：35 项 JUnit 全通过；debug/release/AndroidTest 构建通过；debug/release Lint 均 0 errors（11/8 原有 warnings）。新增测试覆盖取消、嵌套反射/代理包装、错误信息脱敏及致命错误保留。
- 0.2.6 实机异常回归：在真实小爱异步 OkHttp 客户端中分别以 Chat/Responses 注入连接重置、主动取消和异常 JSON，共六项均收到 IOException 失败回调，小爱进程存活。loopback 测试不读取或修改实际 API 配置，不调用付费模型。
- 0.2.6 经用户授权，以已配置的 `gpt-6.1-sol` / Responses / low 做短提示词实测：原生客户端请求已不再崩溃，但服务返回 HTTP 400，明确表示当前账号仅支持 Chat Completions、Sol 工具调用要求 Responses。无工具的最小 Responses 请求在 `/responses` 与 `/v1/responses` 均返回 HTTP 502 / Upstream access forbidden。因此尚未收到真实模型成功回复，服务账号/上游兼容仍待解决；未改动用户 API 配置。手机锁屏期间未进行聊天页面输入验证。
- 0.2.5：31 项 JUnit 全通过；debug/release/AndroidTest 构建通过，debug/release Lint 均 0 errors（11/8 原有 warnings）。新增测试覆盖各模型有效档位、Sol 6.1 与 6.0 的 none 差异、未知别名/思考专用模型、Chat/Responses/Qwen/DeepSeek 请求参数、默认参数清理与按模型隔离持久化。
- 0.2.5 实机原生菜单检查：真实普通输入栏与可见全屏功能栏分别打开原菜单；官方模式仍为 AUTO/QUICK/EXPERT，原标签不变。外部菜单选择、重新打开恢复选择与服务端参数断言通过，选择未改变原生模式；非法/过期写入拒绝。
- 0.2.5 原生请求回归：`--hook --responses --reasoning`（GPT‑6.1 Sol 模拟模型）和 `--hook --reasoning`（Qwen 模拟模型）均通过；流式文字/工具、工具结果/图片第二轮、记忆摘要、gate/能力判断/intent 请求均由模拟服务检查所选思考参数，Responses 同时检查加密 reasoning 回放。
- 0.2.5 独立循环回归：Responses 与 Chat 均通过原生空配置会话、一次性截图管道、纯文字、computer/AutoGLM 实际截图→点击→中文输入→观察确认→结束，以及取消；各项结束恢复模块原配置。

- 0.2.4：23 项 JUnit 全通过；debug/release/AndroidTest 构建通过；debug/release Lint 均 0 errors（11/8 warnings，与原版本相同）。新增测试覆盖 API 选择、图文消息/工具/结果转换、reasoning/phase 回放、结构化输出、token usage、文字流式输出，以及 incomplete/failed/拒绝/提前 EOF 拒绝执行。
- 0.2.4 原生 Hook 实机模拟服务验证：Responses 与 Chat Completions 两种协议均通过 Dyn/直接 OpenAI、Responses、Anthropic 入口的流式文字和工具调用；第二轮实际原生消息携带工具结果和截图回传，Responses 同时校验 encrypted reasoning。记忆摘要、gate、能力判断、intent 路由均经各自原生解析器通过；模拟服务按测试模型名严格绑定 API 路径，错误使用旧端点会失败。Responses token usage 也经原生流解析通过。

- 0.2.4 独立循环实机模拟服务验证：`--responses` 和 Chat 两套测试均通过纯文字无截图、computer/AutoGLM 截图→点击→中文输入→新观察确认→结束，以及取消；默认 native 空配置会话及一次性截图管道回归通过。测试配置在结束后恢复。
- 0.2.3 历史验证：Gradle debug / release 构建、14 项 JUnit、debug / release Lint（均 0 errors；debug 11 warnings、release 8 warnings，主要为静态服务引用、界面文案和导出 Provider；Provider 有运行时 UID 检查）。
- 小米 15 Ultra / Android 17 / LSPosed 2.2.1 实机：真实 controlApp Hook、原 getCurrentPackage 工具、锁屏拒绝操作、原生 TOOL 消息图片附加。
- 空外部地址/Key/模型配置时的本机逐步会话：测试 Runner 模拟主模型提交动作，完成截图→实际点击→中文输入→新观察确认→结束；截图管道二次读取拒绝。
- 独立 computer / AutoGLM 两种协议：模拟模型驱动真实截图、手势、中文输入、新观察与结束；纯文字对话不带截图；取消测试通过。0.2.3 最终设备测试在重新连接无障碍服务后通过。
- 全局替换真实 Hook：分发器的原 Taiyi 配置及直接 OpenAI、Responses、Anthropic 客户端被换为测试模型/地址/Key，模拟服务验证原 systemPrompt、工具 schema、stream 参数、精确 Authorization；原生解析得到 SSE 文字和 tool-call 事件；记忆摘要非流式请求经干净客户端完成并返回可解析的原生响应。
- **官方默认模型无额外 API 的实机自然语言闭环通过：** 外部地址/Key/模型为空，全局外部关闭；由小爱真实模型调用 `miaiplus_computer`，启动计算器→用 JPEG 像素坐标点击 AC→新观察确认 0→调用 finish→给出完成回复。执行过程有真实 scoped Hook 调用记录与屏幕核对，模型步骤未由 Runner 代答。
- 原功能代表性回归：常规对话返回 123+456=579；主 Agent 实际调用 web_search 搜索 Open-AutoGLM 并返回项目链接；按原 settings CLI 打开系统蓝牙设置页，未改动开关。分享输入入口有时仅回复说明，主 Agent 输入才能验证工具执行。
- 独立 gate、能力判断和 intent 路由：在小爱进程调用真实 go.c.chat、router.a.b、router.t.b，通过临时模拟服务验证模型、提示词、精确认证及无小米头/计费参数，结果经原生解析器成功返回。intent-v2 转成标准消息，并把 content JSON 转回原响应结构；不确定路由使用 FALLBACK，实际工具执行留给原主 Agent。
- 坐标换算测试覆盖 1440×3200 原屏与 576×1280 JPEG 同一点、滑动端点、旧归一化协议、不修改原参数以及越界拒绝。
- 模拟服务拒绝小米账号/设备相关认证头；干净网络实例通过此检查。测试不记录截图、请求正文或认证值。
- 实机曾发现完整应用列表查询触发小米权限弹窗；已改为 launcher intent 查询，补齐聚焦窗口与无障碍事件处理。

## 尚未验证，发布时必须注明

- 0.3.3 自动启动确认目前适配实测中文小米安全中心模板；其他 ROM/语言/模板，以及真实模型是否遵循统一 launch 指引仍需验证。未知确认不会盲点，启动未确认成功就暂停。
- 应用 UID 的 su 先前被 KernelSU 拒绝（Permission denied），本轮 ADB-root 测试不能等同于应用 root 授权与旧 daemon 清理链已完成实机验证。0.3.2 不再提供 service.d 或长时间巡检，因此不适用旧版的开机/待机验证。
- 0.3.1 的展开动作停止验证此前受锁屏限制；0.3.2 已在解锁设备上完成全屏和浮窗实测。其他 ROM、真实模型是否持续生成准确意图仍需单独验证。

- 0.2.5 的 Responses 与思考档位验证使用本地模拟服务；0.2.6 的实际 `gpt-6.1-sol` 请求被服务端账号/上游拒绝，尚无成功回复。Qwen/DeepSeek 真实服务及长推理耗时仍未验证。通路回归不是模型任务质量证明。

- **复杂多步任务准确率仍未通过。** 官方模型在多步计算器任务中出现选点错误，不能宣称任意应用都可可靠自动完成。
- 所有官方模型/入口的图片支持。当前实际默认模型已利用截图完成清除任务；其他官方模型及独立传输链不能据此视为全部兼容。
- 手机设置、搜索和米家等全部功能的完整回归。已完成常规文字对话、实际 web_search 搜索及打开系统蓝牙设置页的代表性实测；这不代表所有原生功能均已回归。
- 当前版本新增的路由/gate 在锁屏状态下通过真实请求和解析验证；控屏动作回归及官方模型自然语言任务曾在 0.2.1/0.2.2 通过，0.2.3 最终设备测试也已通过；复杂任务准确率仍未证明。不能将这些通路证据视为任意应用任务的成功保证。
- 原语音云端 NLP/实时会话及其他 SDK 的独立推理未证明全部替换；已覆盖的 OSBot 接口不等于 APK 全部模型流量。
- 用户真实服务的模型名称、密钥、视觉/工具能力和连续任务质量。未使用用户服务作为默认值，模拟服务通过不代表任意供应商兼容。
- 全应用覆盖、动态页面稳定性、Agent 原生回合预算与长期任务成功率。无额外应用白名单不等于所有页面都能自动化。

## 运行限制

不绕过锁屏、安全窗口、密码输入或验证码。当前 launch 对应的小米启动确认自动允许本次；其他系统授权仍接管。敏感操作确认依赖模型标注。原模型是否选工具、是否正确理解观察与预算限制，都会影响实际效果。

APK 内置的是参考 Open-AutoGLM 的本机执行器和动作协议，没有 AutoGLM 模型权重。默认官方模式仍按小爱原模型网络路径推理，不等于完全离线。

正式分发需要发布者自己的长期签名；preview 使用开发签名，unsigned APK 需要签名才能安装。不要把预发布通路测试表述为正式功能保证。
