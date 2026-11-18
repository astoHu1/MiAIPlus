# 后台虚拟屏

后台工具 `miaiplus_background_phone_use` 在独立屏幕运行任务，前台工具 `miaiplus_phone_use` 操作主屏。「兼容入口默认使用虚拟屏」默认关闭，只影响未指定来源的旧入口和直接测试。

## 配置与前置条件

- 模块 UID（`io.github.miaiplus`）需要 root 授权；helper 由模块启动。
- 创建屏幕时设备必须解锁，否则返回 `DEVICE_LOCKED`。运行中锁屏沿用现有行为。
- 平台必须提供并回读确认 `OWN_FOCUS`、`STEAL_TOP_FOCUS_DISABLED` 与 `MotionEvent.FLAG_NO_FOCUS_CHANGE`；缺少时拒绝建屏。未回移植焦点能力的 Android 13 仅支持前台控屏。
- 画布默认 `1080x1920@320`，宽高范围 320–4096 像素、密度 80–1000 dpi。设置页可填入物理屏参数，保存后用于新会话和探测；自定义画布尚未实机验收。
- 同一模块只运行一个后台会话。后台故障停止任务，只有下文的应用冲突交接允许切换主屏。

间歇性空树、输入或截图问题，可在「首页 → 日志 → 更多」或「控屏 → 诊断」开启 [详细日志](phone-use-logging.md)，按任务和观察编号定位。

## 模型入口

官方模型的显式后台操作请求在原生规划和后台插件可用时进入 MiClaw 工具链；功能解释、能力询问、否定后台和普通聊天沿用原路由。配置或插件目录读取失败时停止提交。文字及最终语音识别的请求归属校验见 [架构说明](architecture.md#聊天页文字与最终语音识别路由)。

仅有 CLI 时，首次调用 `voiceassist__controlApp` 的 query 为：

```text
MIAIPLUS_START:{"goal":"完整任务","origin_tool":"miaiplus_background_phone_use"}
```

收到 `CONTINUE` 后使用返回会话号、同一来源工具和本步动作续调：

```text
MIAIPLUS:{"session":"返回会话号","origin_tool":"miaiplus_background_phone_use","coordinate_space":"image_pixels","action":{"action":"wait","duration_ms":1000}}
```

前台来源为 `miaiplus_phone_use`。续调不重交 goal、不省略或更换来源；明确交接到前台后也保留首次来源，直到 `finish`／`take_over`／`ask_user` 或停止。

## 实现

| 组件 | 职责 |
| --- | --- |
| `helper/DisplayHelper` | root 进程，持有虚拟屏并处理类型化 RPC |
| `BackgroundDisplay` | 启动 helper、发送命令、读取帧和释放会话 |
| `DisplaySession` | 逻辑 id、SurfaceFlinger id、几何与 generation |
| `BackgroundActions` | 向指定屏幕执行动作 |

helper 从已安装 APK 经 `app_process` 启动。请求为 `<token> <verb> <args>`，参数解析为数字、包名或路径；子进程仅使用固定 argv 的 `am`、`screencap`、`dumpsys`。stdin 是命令通道，模块退出造成 EOF 后 helper 销毁屏幕。

建屏标志为：

`PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | ROTATES_WITH_CONTENT | DESTROY_CONTENT_ON_REMOVAL | TRUSTED | OWN_DISPLAY_GROUP | TOUCH_FEEDBACK_DISABLED | DEVICE_DISPLAY_GROUP | OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED`

普通标志可回退到 AOSP 常量；焦点隔离标志必须由平台提供。`OWN_FOCUS` 维持独立窗口焦点，`STEAL_TOP_FOCUS_DISABLED` 阻止抢主屏顶层焦点；触摸事件另带 `FLAG_NO_FOCUS_CHANGE` 并绑定 display id。后台屏设置并回读 `IME_POLICY_HIDE=2`，不创建软键盘，避免争用主屏 IME client。初始化失败会释放屏幕。未设置 `ALWAYS_UNLOCKED`。

### 观察与输入

- `ImageReader` 持续消费该屏输出，最多每 200 ms 复制一帧；节流期间保留最后一帧，观察时刷新。截图按物理屏相同预算压缩为 JPEG，标记 `source=imagereader`。
- 无输出帧等情况回退到 `screencap -d <SurfaceFlinger token>`，标记 `source=screencap`。两条路径均绑定后台屏身份。
- 无障碍树由模块服务的 `getWindowsOnAllDisplays()` 读取该屏窗口，按前台组件的包名匹配；缺失时如实返回空树，供模型纯视觉处理。
- 文字只写该屏已聚焦、可见、非密码的 editable 节点；写后重新枚举窗口，有界重试最多 1.2 秒，精确回读完整值。手势和按键拒绝会向上传播。
- `NativeSession` 与 `AgentEngine` 共用观察、动作和确认规则。后台人工确认使用小爱灵动岛；批准后留在原屏，拒绝、停止或超时结束任务。详见 [权限与连续性](phone-use-permissions.md)。
- 控件动作由 `PageGuard` 检查目标变化，滚动和导航检查前台身份。会话按有效步骤续期，持续无进展才超时；未读截图超过 4 个时淘汰最旧项。
- 后台系统选择器和授权窗口返回同会话观察，按确认策略继续；审批后重新核对窗口。详见 [应用启动](app-launch.md)。

### 安全窗口的 ROM 限制

历史 Xiaomi 17（xuanyuan，`CP2A.260605.016`，API 37，KernelSU）上，带 `FLAG_SECURE` 的自有 fixture 在 ImageReader、后台 root screencap、主屏 shell/root screencap 四条路径均能读到 canary。该 ROM 未拦截这些截图路径；其他 ROM 需单独验证。

## 主屏应用冲突与交接

`singleTask` 应用可能把主屏已有任务搬到虚拟屏，`MULTIPLE_TASK` 也不能阻止。启动前按系统 TaskInfo 的 Activity/component 和 task identity 检查归属；查询失败停止，其他屏可见任务返回 `TASK_IN_USE`，启动后继续核对落点。

首次 launch 就是主屏当前应用、且尚未操作后台应用时，关闭后台屏并转前台；wait 不算后台操作。中途遇到主屏当前应用，则在灵动岛显示“小爱请求使用当前应用”，等待“同意并前台控屏”或“拒绝并暂停”。请求绑定 UID、任务和一次性 token，最多等待 60 秒。拒绝、超时或等待期间主屏应用变化均结束本轮。

同意后复核主屏应用，关闭后台屏、作废旧观察和坐标，返回主屏新观察供模型重新规划。灵动岛不可用时无法获准。

关屏使用 `DESTROY_CONTENT_ON_REMOVAL`，结束该屏 Activity/task，不恢复使用前页面，也不 force-stop 或 kill 应用进程；进程回收交给 Android。

## 探测与错误

```sh
# 模块/root 专用 IPC：轻量状态查询，不建屏
content call --uri content://io.github.miaiplus.bridge --method background_display_status
# 能力探测会建屏、启动指定目标并销毁；省略 --arg 时 launch 为 untested
content call --uri content://io.github.miaiplus.bridge --method background_display_probe \
    --arg com.miui.calculator/.cal.CalculatorActivity
```

完整探测与控屏从准备到收尾全程互斥，启动目标也检查跨屏冲突。轻量查询使用独立截图目录。helper 探测没有模块无障碍服务链路，tree 标为未测；输入须由同屏前后帧变化确认，未变化标为 `untested`。自检覆盖显示后端，模型路由与任务质量另行验证。

| code | 含义 |
| --- | --- |
| `PERMISSION_DENIED` | 模块未取得 root |
| `DEVICE_LOCKED` | 创建时设备锁屏 |
| `NO_DISPLAY` / `DISPLAY_UNAVAILABLE` | 后台屏失效 |
| `TASK_IN_USE` | 目标在其他屏可见 |
| `TASK_STOLEN` | 启动落点不符 |
| `CAPTURE_FAILED` | 截图失败 |
| `FOCUS_ISOLATION_FAILED` | 焦点隔离能力不可用或未落实 |
| `IME_ISOLATION_FAILED` | HIDE 输入法策略设置或回读失败 |
| `BACKEND_DIED` | helper 退出或无响应 |

## 实机验证结果

历史确定性实机覆盖建屏、截图、launch、点击／长按／滑动／中文输入、主屏触摸与输入隔离、正常释放、双工具路由及灵动岛交接；酷安信息流单会话连续滚动 12 步通过。早期仅比较 app 焦点与栈顶任务的记录没有覆盖主屏键盘，键盘隔离须看后续专项结果。

2026-11-08 官方 `mimo-omni` 从文字、合成最终 ASR 和热重载后的 ASR 入口完成后台计算器 `137×29=3973`，逐步图片、主屏隔离与回收通过；未测声学唤醒、最终聊天正文或复杂任务成功率。见 [入口验证](validation/native-background-routing-2026-11-08.md)、[发布基线](validation/preview-1.0.1.md) 和 [验证摘要](validation.md)。
