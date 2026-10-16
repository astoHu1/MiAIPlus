# 后台虚拟屏实现规格与下一 Agent Session 交接

调查日期：2026-10-15（Asia/Shanghai）。已按更新后的本地源码复核现状；本次复核只读代码、修改交接文档，没有运行构建、联网调查或设备测试。这是开发准备与验收规格，不代表虚拟屏功能已经实现。

## 1. 任务目标与交付边界

在**用户当前这台手机**上，为 MiAIPlus 的控屏 Agent 创建独立的后台显示空间，在其中启动已有应用、获取截图/窗口树、点击/滑动/输入并完成任务；用户仍可在物理主屏操作其他应用。继续复用小爱官方模型、原生工具和已有截图管道，可选外部模型模式也应使用同一执行后端。

本次 Session 只做调查、代码审阅和文档交接，未实现产品代码、未在设备上创建虚拟屏、未改变 LSPosed 作用域、未执行设备操作。下一 Session 的任务是落实本文，而不是重新泛泛研究虚拟屏。

首期是单个后台会话、指定包名启动、可核验的原子动作闭环；允许能力不足时明确拒绝后台任务。不得后台失败后偷偷执行物理主屏。物理屏模式继续作为用户明确选择的独立模式。

虚拟屏不是应用克隆或独立 Android 用户：进程、账号、数据、单实例 Activity、通知、音频、剪贴板及部分系统服务仍可能共享。不能承诺同一个应用在两块屏幕各自独立运行。首期用户与 Agent 同时操作同一包时应拒绝或暂停，跨应用 intent/deep link 也要检测迁移和焦点影响。

## 2. 已审阅的仓库基线

本地 checkout 无 Git 元数据，不能给出提交 SHA。`app/build.gradle` 实际为 versionName **0.2.3**、versionCode 5、minSdk 33、target/compileSdk 35、Java 17；状态文档与源码可能不同步，旧调查中出现的 0.2.1/0.2.2 不应作为当前代码版本。先按代码定位，不把 dist APK 视为最新源码构建。

以下是上一轮从状态文档摘录的历史记录，本次仅复核代码，不能由此确认当前代码仍通过这些测试：小米 15 Ultra / Android 17 / LSPosed 2.2.1；官方默认模型已完成一次计算器 AC 单步闭环。复杂多步成功率仍未通过。`UI-AUTOMATION-STATUS.md` 是更早的 stock 云端 planner 超时记录，不能据此推翻后来本机执行器的验证，也不能将 stock 超时概括为永久不可用。

### 2.1 可以直接复用

| 文件/能力 | 现状 | 下一 Session 的处理 |
|---|---|---|
| `XiaoAiHook.java` | 精确适配小爱 8.2.65.4714 / 508002065；controlApp、描述、PromptBuilder Hook；失败回滚 | 保留版本检查和已有工具路由 |
| `NativePhoneUseTool.java` | 原生 `miaiplus_phone_use` 工具，goal 首次请求、session+action 后续；支持坐标空间 | 工具绑定执行模式与会话，模型不能任意选择系统 display |
| `NativeModelHook.java` | ToolResult/ImageContent 接入；五个 streamCompletion 入口的外部替换；独立记忆摘要请求 ep.j.o/er.a0.getClient 的配置和干净传输替换 | 保留已有消息/SSE/记忆链路；虚拟屏不要求第二个模型或外部 Key |
| `NativeIntentHook.java` / `IntentProtocol.java` | 适配精确路径 `/osbot/api/intent/v2/chat/completions` 的请求与路由响应，复用干净 HTTP；由 globalModel 开关控制 | 保留意图路由层，不与显示执行后端混合 |
| `BridgeProvider.java` | UID 检查、start/poll/cancel、单 worker/active、watchdog、私有截图管道；已有 NativeSession 与其他入口的互斥检查 | 扩展为统一后端资源所有权和会话状态，保留已有互斥与 UID 边界 |
| `FrameStore.java` | 内存最多 4 帧、60 秒 TTL、UID 绑定、一次性读取；已解码 JPEG 读取实际宽高 | 保留尺寸解析并补 display/frame 元数据，不扩大截图暴露范围 |
| `Action.java` / `Coordinates.java` | 白名单动作；NativeSession 已接入三种坐标空间转换与越界拒绝；外部 AgentEngine 当前仍使用归一化动作 | 保留现有转换，并统一两条执行路径的坐标边界 |
| `NativeSession.java` | 小爱官方模型逐步执行；单 active、owner、4 分钟、步数与重复动作保护；执行前重截图比较包名和树，launch/home/back 例外 | 保留已有重观察和非阻塞取消，补 DisplaySession 归属、重规划及资源释放 |
| `AgentEngine.java` / `RunState.java` | 可选 computer/AutoGLM 外部循环；取消与超时 | 使用与 NativeSession 相同的后端和资源所有权 |
| debug fixture / device runner | 截图、中文输入、取消、工具图片、mock model 验证设施 | 扩展双屏隔离 fixture，区分 mock 与真实模型 |

### 2.2 本轮代码复核的补充与更正

- `NativeModelHook.java:31-33,94-123,125-162`：安装原生工具和意图 Hook；替换五个流式模型入口；另适配记忆摘要的非流式路径。不能再概括为“只覆盖流式主模型入口”，也不能扩张成全部独立云服务已经覆盖。
- `NativeIntentHook.java:20-45`：按 URI **path** 精确匹配 intent v2 路径，代码没有同时限定来源 hostname；开启 globalModel 后适配 body、清空 query/headers、设置用户端点/认证并改用 cleanHttp。初始化错误明确失败，不静默回原路由。
- `IntentProtocol.java:15-29,35-49`：v2 请求转换成分类提示，要求 FALLBACK/direct_path=false；普通文本分类响应被转换成原生 FALLBACK 决策。但 message **已有 status 时原样交给原解析器**，不能宣称所有响应一律禁止 direct dispatch。原生解析前的适配异常被忽略，由原解析器处理。
- `NativeModelHook.java:38-81` 已同时处理 TOOL Message 与 ToolResult.Success 图片，并清除 CONTINUE 的直接答案标记及 `cli_internal_direct_answer`，让观察返回原 Agent 循环；虚拟屏只需替换观察来源，保留此运输和续跑行为。
- `NativePhoneUseTool.java:14-18,62` 默认 image_pixels；`XiaoAiHook.java:25` 的策略示例使用 screen_pixels/树坐标。两者都支持，但提示与观察应统一说明图片、树、显示逻辑尺寸，不能笼统称“工具一律物理坐标”。
- `FrameStore.java:17-22` 已从编码后的 JPEG 读取真实尺寸；但 `NativeSession.java:45-47,76-78` 的坐标换算和观察文字仍按最长边1280计算图片尺寸。下一步应统一使用 Observation 中实际 JPEG 尺寸，保留已有能力，不重复造一个尺寸解析器。
- `NativeSession.java:61-65` 已执行前重新截图比较 foreground/tree，页面变化直接停止；一般动作尚没有显示归属、windowId、generation 校验或自动重规划。`ScreenService.java:125-132,177-187` 另有包名/手势尺寸保护。
- `AgentEngine.java:76,90-99,109-123` 外部循环仍使用归一化坐标，仅确认分支额外重截图比较页面。不能把 NativeSession 的全部保护或三坐标空间支持当作两条路径都已经具备。
- `NativeSession.java:90-94` 已避免 cancel 等待 synchronized handle；`RunState.java:20-23` 已断开 HTTP。未来增加显示/helper 清理时必须保留此非阻塞设计。
- 锁屏和密码框拒绝有本地检查；验证码、系统授权的接管主要依赖提示词和模型，安全截图受系统限制；敏感提交确认也依赖模型标注。不能把这些写成已经实现了完整本地页面分类/拦截器。

以下缺口仍由当前代码支持，未因模型/路由更新而消失。

### 2.3 当前没有的能力和代码证据

`ScreenService.java` 当前是默认物理屏执行器：

- `Frame` 只有 image/tree/width/height/foreground，没有 displayId、rotation、windowId、generation。
- `capture()` 明确 `takeScreenshot(Display.DEFAULT_DISPLAY, ...)`。
- `currentRoot()` 查 `getWindows()` 的焦点窗口，再回退 `getRootInActiveWindow()`，没有按 display 过滤。
- `gesture()` 未调用 `GestureDescription.Builder.setDisplayId()`；尺寸来自默认 `WindowManager.getMaximumWindowMetrics()`。
- `execute().launch` 普通 `startActivity()`，没有 `ActivityOptions.setLaunchDisplayId()`。
- back/home 使用 `performGlobalAction()`；type 在全局当前根窗口寻找输入焦点。
- 停止/确认使用默认屏 `TYPE_ACCESSIBILITY_OVERLAY`，会遮挡用户前台。

`AndroidManifest.xml` 仅声明 INTERNET 和无障碍服务等基础组件；Xposed scope 只有 `com.miui.voiceassist`。没有 VirtualDisplay、Surface/ImageReader、root/Shizuku helper、system_server Hook 或特权服务实现。**LSPosed 在小爱进程注入代码，不会自动把模块 UID 变成 system/shell，也不会自动获得 INJECT_EVENTS 等平台权限。**

## 3. 技术方案对比与选择

| 方案 | 与本目标的关系 | 本项目取舍 |
|---|---|---|
| 普通 VirtualDisplay + Accessibility | 可创建自有内容屏；API 30+ 可定向截图和触摸；任意第三方 Activity、焦点、IME 受系统限制 | 作为能力探测中的低权限路径，不能先承诺任意 App |
| **手机本地特权 helper + VirtualDisplay** | 在 root/shell 身份创建可承载应用的显示、定向启动与输入；可继续复用模块无障碍树 | **推荐首期产品架构**，先在目标 ROM 验证；root 授权由用户配置 |
| scrcpy `--new-display` | 已有可读源码和开发机 PoC；虚拟显示、定向控制、IME 策略有参考价值 | 参考设计及开发验证工具，不把桌面 scrcpy 进程变成产品必要依赖 |
| Shizuku helper | shell 身份的替代授权通道，仍受 OEM/系统权限约束 | 后续可选适配器，首期不同时维护全部通道 |
| system_server LSPosed Hook | 能触及 DMS/ATMS/WMS，但改动系统核心、兼容和启动风险高 | 只有 helper 在目标 ROM 的明确限制无法解决时，再做窄范围版本化适配 |
| MediaProjection 镜像 | 授权捕获，Android 14+ 有 token/FGS 生命周期要求 | 不作为隔离运行任意 App 的依据；本地 own-content 显示不必先引入投影授权 |
| AVD / redroid / 云手机 | 独立系统，容易隔离；不是本机已登录应用和数据 | 可作对照或后续远程 worker，不能替代本次目标 |
| AutoGLM / UI-TARS / Mobile-Agent | 模型、规划和执行编排框架；执行环境另行提供 | 本仓库已有规划闭环，重点补显示与执行基础设施 |

### 3.1 推荐决策顺序

1. 先实现开发用双屏 capability probe；低权限 DisplayManager + 无障碍链可先验证自有 fixture。
2. 用 scrcpy 新显示或自建 shell/root helper 验证目标 ROM 的第三方 App、焦点、IME 和销毁行为。
3. 首期落地一个最小本地 helper（建议 root 启动的 `app_process`/等价 Java 进程；选定后写清启动、UID、SELinux 及 Android 版本），模块通过受控 IPC 使用。shell/root 是否拥有实际 Binder 权限以测试为准，不假设 UID 0 可以无条件通过所有策略。
4. 若公开无障碍定向手势通过并发实测，可使用它作为目标屏触摸适配器；需要定向按键/更稳定注入时由 helper 实现。不能因存在 setDisplayId 就宣称焦点隔离通过。
5. helper 无法达标时记录具体拒绝栈/系统检查，再决定是否需要 system_server 适配；禁止广泛关闭系统权限校验。

### 3.2 已交叉核对的 Android 细节

- `GestureDescription.Builder.setDisplayId(int)` **是 API 30+ 的公开能力**；“无障碍手势不能指定屏幕”是错误表述。本项目 minSdk 33，不需要为 API<30 降级设计。
- `AccessibilityService.takeScreenshot(displayId, ...)`、`getWindowsOnAllDisplays()` 可用于目标显示观察。窗口必须在目标 display 内筛选，不能回退读用户主屏。
- `ActivityOptions.setLaunchDisplayId()` 只是指定目的地，不绕过多显示权限、私有/不受信任显示、后台启动和 Activity/task 复用规则。启动成功要检查最终 task/window 的真实 display。
- `OWN_CONTENT_ONLY` 避免无内容时镜像主屏；`Presentation` 只适合自有 UI，不能代替任意应用托管。
- `TRUSTED` / `OWN_DISPLAY_GROUP` 等受平台权限限制；AOSP 新版有 `OWN_FOCUS` 与 `STEAL_TOP_FOCUS_DISABLED`。后者是“禁止窃取顶部焦点”，不是允许抢焦点。这些隐藏标志的存在、权限和效果必须按目标 API/ROM 探测。
- `InputManager.injectInputEvent` 是隐藏/特权路径；INJECT_EVENTS 不能通过普通 manifest 声明获取。事件必须绑定 display；键盘和触摸分别验证。
- MediaProjection 创建的采集输出不等于独立应用任务空间；不要引入并不存在的通用 `CREATE_VIRTUAL_DISPLAY` 权限。
- ImageReader/Surface 是新屏的输出消费者；不是把物理主屏投射到虚拟屏。FLAG_SECURE、锁屏限制继续保留。

### 3.3 scrcpy 源码给出的关键工程线索

上一轮调查直接读取了官方 `doc/virtual-display.md` 和 `NewDisplayCapture.java`，不能只参考普通镜像模式：

- `--new-display=宽x高/DPI`、`--start-app=包名`；退出时销毁虚拟屏。
- **默认虚拟屏 IME 可能显示在主屏**，需测试 `--display-ime-policy=local`，否则“后台输入”仍干扰前台。
- 默认销毁显示内内容；`--no-vd-destroy-content` 会将应用移至主屏，本项目应避免该行为。
- 源码在不同 API 使用 trusted/display group/own focus 等标志；这些是能力适配参考，不应把 master 的位常量原样复制到所有 ROM。
- 上游示例含 ALWAYS_UNLOCKED 等标志，本项目不能借此绕过锁屏；锁屏时暂停并停止后续输入。
- 不需要复刻实时 H.264/音频/桌面播放器；首期按动作采 JPEG 即可。若复制代码须锁定 commit、保留 Apache-2.0 归属并更新 NOTICE。

## 4. 目标架构与模块契约

```text
小爱原生工具 / 可选外部循环
                 │
       NativeSession / AgentEngine
                 │
         DisplaySession（单一所有者）
                 │
        ScreenBackend（统一契约）
           ├── PhysicalAccessibilityBackend（原功能）
           └── VirtualDisplayBackend
                  ├── PrivilegedDisplayBridge（创建/启动/按键/焦点策略）
                  ├── Surface/ImageReader（本屏画面）
                  └── Accessibility observer/gesture（本屏树/输入）
```

建议名称可随代码风格调整，但职责不能合并成零散全局 displayId。首期沿用现有单任务与跨入口互斥，再把它扩展为 DisplaySession 的统一所有权，避免两个入口争用显示或确认 UI；不是从零增加互斥。

### 4.1 DisplaySession 与后端

```java
// 以下为设计接口，不是已存在代码。
interface ScreenBackend extends AutoCloseable {
    CapabilityReport probe();
    DisplaySession open(SessionOptions options, RunState run);
    Observation observe(DisplaySession session, RunState run);
    ActionResult execute(DisplaySession session, BoundAction action, RunState run);
    void close(); // 幂等，只清理本后端拥有的资源
}
```

`DisplaySession` 至少含 runId、ownerUid、backend、displayId、generation、逻辑宽高、densityDpi、rotation、创建时间、资源句柄、ownedTaskIds 和能力状态。displayId 可被系统复用，必须带 generation/内部随机 session identity，拒绝旧 display 的请求。

Helper 只接受 create/observe metadata/launch/inject/key/resize/close 等类型化 RPC，不能成为任意 shell 执行器。IPC 绑定模块 UID、session nonce 与 helper 实例；Binder 校验 calling UID，若采用本地 socket 必须校验 peer 凭据并保证认证密钥仅授权端可读，禁止公共端口或可导出的任意命令入口。设置 Binder death/客户端断开清理、独立 watchdog 和资源上限，不能只依赖 Activity.onDestroy。

### 4.2 capability probe：可用需要全部通过

每项输出 supported/unsupported/untested、原因、实际执行身份、API/ROM/Build fingerprint、实测时间；未测不等于支持。

| 探测项 | 必须证明 |
|---|---|
| createOwnDisplay | 新屏非 default、非主屏镜像，有稳定逻辑尺寸和输出 |
| capture | JPEG 来自该屏，安全窗口仍拒绝 |
| launch | 自有 fixture + 第三方测试 App 确实位于该屏 |
| tree | 树/焦点节点属于该 display/window/package；无法读取时明确报告 |
| pointer | 单击、滑动、长按及并发用户触摸，不取消/注入物理主屏手势 |
| text/key | 中文输入、返回及焦点不影响物理屏的编辑框/IME |
| focusIsolation | 启动/切换/输入过程中物理屏 resume/focus/键盘和用户交互不被抢走 |
| teardownIsolation | 关闭/崩溃不会将后台任务自动迁移至主屏 |

create/capture/pointer 的成功不足以启用“后台可用”。树不能读取但视觉链可用时可定义 `vision_only` 限制模式；首期 type 必须有可靠可编辑节点，不应为了通过探测偷偷借默认焦点输入。

### 4.3 观察、动作与返回协议

```json
{
  "frame": {
    "run_id": "r", "seq": 12, "captured_elapsed_ms": 100000,
    "display": {"id": 4, "generation": "g", "width": 1080, "height": 1920, "density_dpi": 320, "rotation": 0},
    "image": {"width": 720, "height": 1280, "token": "private-one-use-token"},
    "window": {"id": 8, "task_id": 21, "package": "example.app"},
    "tree_status": "available", "ax_tree_redacted": "..."
  },
  "action": {
    "action_id": "a", "source_frame_seq": 12,
    "coordinate_space": "image_pixels",
    "action": "tap", "element": [360, 640]
  },
  "result": {
    "action_id": "a", "status": "completed",
    "error_code": null, "post_frame_seq": 13
  }
}
```

以上是内部 envelope 示例；保留现有 action 字段，不必破坏旧工具兼容。display 由 session 服务端决定；即使动作带 display_id，也只能匹配本 session，不能由模型枚举/切换其他显示。

- 截图像素尺寸与 display 逻辑尺寸分开保存，letterbox/insets/crop/rotation 统一通过变换处理；树边界的坐标空间要明确。
- frame 必须属于 run、UID、display generation 和最新 seq。60 秒图片 token TTL 不是“60 秒内坐标都有效”。官方模型思考可能超过 2 秒，不采用未经测量的 2 秒硬门槛；执行前重新观察并校验相关窗口、尺寸、旋转和页面，超时与变化时重规划。
- 截图与树不是原子采样：采集前后核对窗口/旋转元数据，变化则丢弃并有限重试，不能把不同页面组合送模型。
- action_id 去重，重复请求返回已知结果；已发送但结果未知记 UNKNOWN，先观察，不自动重放 type/发送/提交。
- `completed` 只说明输入执行完成；任务完成需要新观察与目标证据。任意任务没有通用语义验证器，保留“模型报告完成”表述，fixture 可用确定性断言。

### 4.4 各动作的虚拟屏语义

| 动作 | 后台实现约束 |
|---|---|
| tap/swipe/long_press/double_tap | 在目标 display 执行；前后检查 generation/window；使用公开 setDisplayId 或通过探测的 helper |
| type | 仅目标屏焦点 editable 节点 ACTION_SET_TEXT，拒绝 password；禁止用全局剪贴板或当前主屏输入焦点兜底 |
| launch | 指定目标 display；启动后验证实际 task/window；singleTask/已有任务迁移必须检测；不支持则暂停 |
| back | helper 定向 KeyEvent 或经探测的等价后端；不能复用全局 performGlobalAction |
| home | 首期定义为“虚拟会话应用选择页/空闲状态”；无目标屏 launcher 则 UNSUPPORTED_NAVIGATION，不能跳主屏 Home |
| wait | 可取消短等待，随后重新观察 |
| finish | 保存最后观察与完成理由，释放显示；不能仅因 finish 文本宣称确定成功 |
| ask_user/take_over | 暂停并从任务通知进入预览/说明；首期可停止后重建会话，不承诺无损任务迁移 |

首期避免软件键盘：中文通过节点赋值。若 App 强制 IME，必须隔离 IME 到目标屏且验证默认屏不会弹键盘；无法保证就暂停。应用自行发起的权限框、外部活动、分享页、浏览器跳转都要重新检查显示归属。

## 5. 生命周期、UI 与错误行为

建议状态：NEW → PREPARING → OBSERVING → PLANNING → EXECUTING → VERIFYING → 下一轮；旁路 AWAIT_CONFIRM/PAUSED/FAILED/CANCELLED/TIMED_OUT/SUCCEEDED。

- 保持现有 4 分钟预算和 maxSteps，首期不顺带扩大长任务范围。等待确认占用预算的行为要向用户说明。
- 全过程串行，取消后不再规划/提交新输入；已提交动作未必能立即撤回，记录 unknown 而不是假称全部回滚。
- finally、取消、超时、无障碍断开、display removed、helper 死亡、模块进程死亡均有清理策略。helper 自带失联 watchdog；close 重入安全。
- 释放 Surface/ImageReader/每个 Image/HardwareBuffer/VirtualDisplay/回调/线程/socket；先处理 owned tasks 的销毁策略，再释放显示，避免内容迁往物理屏。不要按包 force-stop 用户已有进程，也不清理用户原有任务。
- 保留已有本地锁屏/密码拒绝、系统安全截图限制和验证码/授权接管提示；后台后端不得绕过这些边界。当前没有完整的本地验证码/授权页面分类器，不能把提示词当作强制拦截实现。
- 网络错误有限重试；STALE_FRAME 重新观察；DISPLAY_LOST、WRONG_DISPLAY、FOCUS_ISOLATION_FAILED、IME_ON_DEFAULT、PERMISSION_DENIED、SECURE_CONTENT、BACKEND_DIED 明确报告；任何路径不隐式转主屏。
- 任务期间使用低打扰的持续通知提供“停止/查看/待确认”，不自动弹 Activity、不创建默认屏悬浮按钮。用户主动打开预览时可见虚拟屏画面，但预览触控需明确进入接管模式，与 Agent 互斥。
- 发送/支付/删除等继续保留确认；当前敏感识别依赖模型标注是已知弱点，本期至少加入所有后台提交的策略门禁/包级限制及单动作确认，不宣称已有通用敏感行为识别。
- Android 13+ 通知权限及 target 35 前台服务类型需据实际 helper/服务实现确认；不为了常驻盲目声明 mediaProjection。若确实采用投影才加入对应 FGS 权限、类型与每会话授权处理。

## 6. 文件级实施清单

| 文件/新增组件 | 要做的事 |
|---|---|
| 新增 ScreenBackend / DisplaySession / Observation | 将屏幕归属、元数据、能力和生命周期显式化 |
| 新增 VirtualDisplayBackend / PrivilegedDisplayBridge | 一个本地可运行 helper 通路；own-content 显示、输出消费、任务/焦点/IME 策略、受控 IPC |
| `ScreenService.java` | 默认屏实现抽为后端；多屏树按 getWindowsOnAllDisplays 筛选；指定 display 截图/手势；移除后台路径的全局导航/默认窗口 fallback |
| `NativeSession.java` | State 保存 DisplaySession；初次创建、每步验证、结束/异常/timeout/cancel 统一释放；现有 timer 只 cancel/hideControls 不够 |
| `AgentEngine.java` | 不再直接持有 ScreenService，使用同一 ScreenBackend；与 native 会话共享单 run 协调器 |
| `RunState.java` / `NativeSession.cancel()` | 保留已有取消标志、HTTP disconnect 和非阻塞取消；补资源清理钩子及结构化错误，不在主线程等待 helper |
| `NativePhoneUseTool.java` / `XiaoAiHook.java` | 工具提示说明后台会话、不可操作主屏；模式服务端固定；保留精确版本与 Hook rollback；原生工具当前 isStoppable=false/onCancel 不取消 run，后台需接通取消链 |
| `NativeModelHook.java` / `NativeIntentHook.java` / `IntentProtocol.java` | 保留已有流式、意图路由、记忆摘要及图片链路；新增后台能力不能退回较旧 Hook 覆盖范围 |
| `BridgeProvider.java` | capability/session 状态查询、UID 鉴权、取消传播；防止外部请求任意 display/control |
| `FrameStore.java` / `Coordinates.java` | 保留已有真实 JPEG 尺寸解析、一次性 token 和原生三坐标空间转换；补 display generation/seq，并统一原生与外部循环 |
| `Config.java` / `MainActivity.java` | 与 planner/model 独立的 physical/virtual 设置、root/backend 状态、失败原因、主动预览入口；配置缺省保持旧行为 |
| manifest / service / scope | 仅按实际后端加入声明；首期不默认添加 system_server scope；如需要，明确恢复开关和版本适配 |
| fixture / runners / scripts | 双屏输入日志、焦点/IME/任务归属、进程死亡/清理与模型闭环检查 |
| README / architecture / status / releasing | 功能开关、真实权限需求、支持设备、未验证限制；新增 helper 源码进入发布白名单 |

## 7. 分阶段执行与通过条件

### P0：设备事实与能力探针（先于模型接入）

读取 AGENTS.md 和本文，记录设备 build fingerprint/API、小爱版本、root/LSPosed、可用 shell 工具及多显示能力。不得把状态文档 Android 17 当成所有 API35 源码行为都相同。probe 生成机器可读 JSON 和简短报告，禁止记录个人页面或凭据。

可参考开发机命令（仅下一 Session 经设备授权后执行）：

```sh
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.fingerprint
adb shell dumpsys display
adb shell dumpsys activity activities
adb shell dumpsys window displays
adb shell input --help
scrcpy --version
scrcpy --help
```

scrcpy PoC 示例（必须先核对已安装版本）：

```sh
scrcpy --new-display=1080x1920/320 --start-app=io.github.miaiplus --display-ime-policy=local --no-audio
```

不要用 `--no-vd-destroy-content`。App/启动页应使用无个人数据 fixture；开发期 GUI 窗口只是观察后台屏的工具，不是最终产品依赖。命令支持情况以设备 help 与实际返回为准，不能用逻辑 displayId 直接假定匹配 `screencap -d` 所需的物理显示标识。

**P0 交付：** capability JSON、实际 flags/identity、截图和窗口一致性证据、焦点/IME/销毁测试；失败时指出系统检查与下一条本地路径，不把远程云手机当本机成功。

### P1：本地后端确定性闭环

实现 helper + DisplaySession + fixture，不接模型。后台 fixture 完成点击计数、滑动、中文输入、返回、显式启动；同时主屏另一个 fixture 接收用户触摸/文字并记录 onResume/onPause/onWindowFocusChanged 与输入事件。每个动作前后记录目标 display/window，主屏不收到 Agent 合成事件、焦点和输入目标不变。主屏动画/用户触摸会改变截图，**不能用主屏画面 hash 不变作为唯一验收**。

覆盖取消、helper kill、模块 kill、无障碍断开、display removal、旋转、超时、旧 generation/seq、重复 action_id、无特权失败；资源和 task 不迁回主屏。

### P2：接入默认官方模型与可选外部循环

将同一后端接入 NativeSession 和 AgentEngine。先 mock 单步闭环，再真实小爱模型完成后台计算器/无个人数据测试应用任务；截图中可见目标仅位于虚拟屏，观察结果带 display metadata。保持空外部配置可用，并回归旧物理屏模式、普通聊天及原工具。

mock 成功只能证明执行通路，真实模型完成一个任务不能宣称任意应用成功。

### P3：兼容性与发布验收

首要实机为状态文档记录的小米目标机；Android 13/API33、14/API34、15/API35 与目标 Android17 分开记录，AOSP/Pixel 或模拟器作对照。API/ROM 未测写 untested，不承诺 Android13+ 全兼容。

关键负例：目标 App 已在主屏使用、singleTask/不支持多显示 App、IME 强制显示主屏、权限弹窗/外部 intent、FLAG_SECURE、锁屏、多窗口/画中画、前台游戏/视频/中文输入、用户触摸取消后台手势、显示销毁迁移、服务后台被杀。无法达标的包要明确限制，不全局 Hook 系统绕过。

按 AGENTS.md 跑对应构建和检查：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:assembleRelease :app:lintRelease
```

设备测试扩展 `FixtureActivity`、`DeviceTestRunner` 与 `scripts/device_test.py`。现有脚本会切无障碍、主屏启动 fixture，`--hook` 会重启小爱，不能在“用户继续操作前台”的测量期间直接运行；后台测试必须分离初始化、用户前台测量和清理阶段。不要把目前不存在的 `--display-probe` 参数写成已可运行命令。

## 8. 下一 Session 必须交付的结果

1. 可构建的本地虚拟屏后端、受控 helper 与默认关闭的功能开关；不存在静默主屏回退。
2. 默认官方模型和可选外部循环共用后端；前者不要求外部 API 配置。
3. capability JSON、确定性双屏 fixture 证据、真实模型测试记录、失败矩阵及资源清理证据。
4. 更新的 architecture/status/README 和新增依赖许可；明确实际验证的设备与 App。
5. 若设备能力阻塞：仍交付可审阅的 probe、代码、测试、准确错误与已试路径；不得把“能截图/创建屏”当成目标完成。

首期暂不要求：多 Agent 并行屏、长期任务恢复、应用克隆、跨用户账号、全 ROM system_server Hook、高帧率视频流和云手机服务。这些不能挤占“同一台手机前台不受影响”的最小闭环。

## 9. 信息来源与证据等级

### 上一轮调查核对的上游资料（2026-10-15；本轮未重新联网）

- AOSP [GestureDescription.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/accessibilityservice/GestureDescription.java)：Builder.setDisplayId 确实存在。
- AOSP [DisplayManager.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/hardware/display/DisplayManager.java)：own content/trusted/own focus/steal top focus disabled 及注释。
- AOSP [ActivityOptions.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/app/ActivityOptions.java)：launch display 及 secondary display 约束。
- scrcpy [virtual-display.md](https://github.com/Genymobile/scrcpy/blob/master/doc/virtual-display.md)：new-display、start-app、销毁、IME local。
- scrcpy [NewDisplayCapture.java](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java)：按 API 适配 flags、创建/释放、IME policy、坐标变换。

以上 master 是调研时可变分支，不是目标 ROM 的源码；实施时锁定上游 commit 和目标版本，必要时查看该设备 framework。源码存在不代表该 ROM 支持/授权。

### 实施时参考的官方入口

- [VirtualDisplay](https://developer.android.com/reference/android/hardware/display/VirtualDisplay)、[DisplayManager](https://developer.android.com/reference/android/hardware/display/DisplayManager)
- [GestureDescription.Builder](https://developer.android.com/reference/android/accessibilityservice/GestureDescription.Builder)、[AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [ActivityOptions](https://developer.android.com/reference/android/app/ActivityOptions)、[MediaProjection 指南](https://developer.android.com/media/grow/media-projection)
- [DisplayManagerService AOSP](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/display/DisplayManagerService.java)
- [InputManager AOSP](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/input/InputManager.java)
- [VirtualDeviceManager](https://developer.android.com/reference/android/companion/virtual/VirtualDeviceManager)：受限的 companion/virtual device 路径，不能默认认为普通手机模块可直接授权。

### 产品类比与尚不能证实的部分

用户用豆包 AI 手机描述目标体验；本次没有取得足以确认其内部虚拟显示架构、权限和实现代码的官方技术材料。**不能写成“豆包内部就是上述 flags/helper/system_server Hook”。** 这里采用的是可查 Android/scrcpy 能力推导的本项目工程方案，不是对竞品的逆向结论。

相关开源项目：[scrcpy](https://github.com/Genymobile/scrcpy)、[Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM)、[UI-TARS Desktop](https://github.com/bytedance/UI-TARS-desktop)、[Mobile-Agent](https://github.com/X-PLUG/MobileAgent)、[redroid](https://github.com/remote-android/redroid-doc)、[Android Emulator CLI](https://developer.android.com/studio/run/emulator-commandline)。这些项目分别覆盖显示控制、规划或独立环境，不能混为“都已实现本机后台不扰前台”。

上一轮恢复 8 个并行子任务：仓库、Android display、输入截图、无障碍、运行时、竞品、scrcpy、验收。其中部分任务在返回中间摘要后遇到上游 502；无障碍独立任务未返回可用最终报告，其内容由主 Agent 审查现有 ScreenService 和相关源码补足。最终规格已纠正 API 不存在、自动回退主屏、MediaProjection 等价隔离环境等冲突建议；未将未经实机验证的建议写成已实现能力。
