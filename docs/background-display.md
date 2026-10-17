# 后台虚拟屏

后台虚拟屏让控屏任务在一块独立屏幕上运行，用户继续使用手机主屏。模型通过独立工具 `miaiplus_background_phone_use` 发起虚拟屏任务，前台工具 `miaiplus_phone_use` 操作主屏。兼容入口的默认屏幕设置仅影响旧入口和直接测试的新任务。

本文记录的是实机验证过的行为与已知限制。设备为 Xiaomi 17（xuanyuan，`CP2A.260605.016`，API 37），开启 KernelSU root 并解锁。其他 ROM 未测。

2026-10-17 当前版本实机新增通过：两个原生模型工具在两种兼容默认设置下的独立路由、错误工具续调拒绝、真实虚拟屏点击/滑动送达、主屏焦点/触摸/输入隔离，以及正常结束后的 helper/display 释放。真实灵动岛同意/拒绝和首次自动转前台也已通过；拒绝验证包括 direct answer 与宿主 STOP gate 的直接调用，未覆盖真实模型混合工具批次。完成岛返回会话也已用两条本地测试对话验证。以上为确定动作设备测试，不代表真实模型规划成功率。下方原有实机表格保留为历史证据，其他 ROM 未测。

## 开关与前置条件

- 模块私有设置中的“兼容入口默认使用虚拟屏”默认关闭，不覆盖两个模型工具各自的屏幕选择。
- 需要 root 授权模块 UID（`io.github.miaiplus`）。小爱进程本身拿不到 `su`，所以 helper 由模块 UID 启动。
- 需要解锁状态。锁屏时不创建屏幕，任务报 `DEVICE_LOCKED` 并停止。
- 不写静默回退：后台链路任何一步失败，任务停止并说明原因，不会改到主屏继续执行。
- 用户指定的应用冲突交接是例外：首次目标就是主屏当前应用时明确转前台；中途需要当前应用则先通过灵动岛授权。同意前不操作主屏，拒绝或 60 秒未答复则暂停并结束本轮。
- 运行中锁屏沿用原实现，不新增锁屏停止门禁；创建后台屏仍要求解锁。`FLAG_SECURE` 的目标 ROM 行为也保持原状，见下方实测限制。

## 组成

| 部分 | 职责 |
| --- | --- |
| `helper/DisplayHelper` | 以 root 身份运行、独占持有虚拟屏的进程；只接受类型化 RPC |
| `BackgroundDisplay` | 模块侧会话；启动 helper、发命令、读回帧，负责释放 |
| `DisplaySession` | 一次运行的屏幕身份：逻辑 id、SurfaceFlinger id、几何、generation |
| `BackgroundActions` | 该屏上的动作执行；不含全局动作、不查默认屏 |

helper 通过 `su -c "CLASSPATH=<apk> app_process ... DisplayHelper"` 启动，类从已安装 APK 加载，不需要额外 dex。它**不是**任意 shell 执行器：每行请求是 `<token> <verb> <args>`，参数被解析成数字、包名和文件路径；唯一的子进程是固定 argv 的 `am`、`screencap`、`dumpsys`。回复是每行一个 JSON 对象。

helper 的 stdin 就是命令通道，所以模块**不能**关闭它；该流在本进程退出时自然关闭，helper 读到 EOF 后销毁屏幕。这正是“模块被杀不会留下孤儿屏幕”的机制。

## 屏幕创建

`createVirtualDisplay` 使用的标志（`0xadcb`）除 AOSP 常量外由运行时反射取值，未在 ROM 上定义的名称才回退到字面值：

`PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | ROTATES_WITH_CONTENT | DESTROY_CONTENT_ON_REMOVAL | TRUSTED | OWN_DISPLAY_GROUP | TOUCH_FEEDBACK_DISABLED | DEVICE_DISPLAY_GROUP`

两个标志是刻意不加的：

- **`ALWAYS_UNLOCKED`**：scrcpy 用它让镜像在锁屏下可用。对本项目来说那等于让助手操作一台用户认为已锁定的手机，所以创建屏幕时若已锁屏会失败；运行中锁屏按用户要求继续任务。
- **`OWN_FOCUS`**：实机加它会让物理屏 `mCurrentFocus` 变 `null`。该症状在去掉它之后依然存在（见下），所以不是它的锅，但后台屏本来也不需要顶焦：输入都带 display id 注入，不靠“当前焦点是谁”。不加它让状态更接近默认。

后台屏不需要 `STEAL_TOP_FOCUS_DISABLED` 作为补偿，原因同上。

## 实机验证结果

固定测试目标 `com.miui.calculator/.cal.CalculatorActivity`：

| 项 | 结果 | 证据 |
| --- | --- | --- |
| createOwnDisplay | 通过 | 逻辑 id 与主屏不同，几何 `1080x1920@320`，`type VIRTUAL`，带 `FLAG_TRUSTED` |
| capture | 通过 | 主路径读该屏自身输出（`source=imagereader`），帧内容随该屏变化 |
| launch | 通过 | 目标前台窗口落在该屏 |
| focusIsolation | 通过 | 全程主屏 app 焦点与栈顶任务不变 |
| teardownIsolation | 通过 | 屏幕消失，主屏停留在起始状态 |
| pointer | 通过 | 点击/滑动前后帧不同（`t0`＝`t1` 说明该点无响应，`t2`≠`t3`、滑动后 `t4` 变化），计算器显示从 `4.6e48` 变为 `8` |
| text/key | 通过 | 返回键走 `InputManager` 注入；中文输入经该屏可编辑节点写入并按树回读确认（见下） |
| long_press | 通过 | 独立手势（非慢速 tap）：helper 在 DOWN 与 UP 之间真实持按下，实测 `heldMs=800`，长按前后帧不同 |
| tree | 通过（应用在该屏之后） | 该屏启动应用后，`dumpsys accessibility` 有 `Display[<id>]` 与该应用的 `TYPE_APPLICATION` 窗口；无应用时不合成窗口，如实报空 |

### 端到端后台任务（`scripts/background_display_test.py`）

固定目标，用户主屏由一个会记录每次触摸的 fixture 占用，断言"用户屏幕不动"：

| 检查 | 结果 |
| --- | --- |
| 启动 helper / 建屏 / 首次观察 | 通过；观察来自该后台屏几何（`1080x1920`），前台不是主屏 fixture |
| launch / back / finish | 通过；每步返回 `CONTINUE` 或完成，会话未中断 |
| 主屏 app 焦点与栈顶任务 | 任务前后完全一致 |
| 主屏 fixture 收到触摸 | 无（`lastTouch=none`） |
| 主屏 fixture 输入框被写入 | 无（保持空） |
| 中文输入 | 通过；写入后台屏自己的编辑框，下一次观察的树中出现该文本 |
| 销毁 | 任务结束只剩 display 0，无残留 helper |

## 本轮修正的两个真实缺陷

**1. 后台屏的树一直读成空（前台包名与组件的比较错误）。** helper 把某屏前台报成组件 `包名/Activity`，而树的根节点只报包名。两处比较直接拿整串比 `getPackageName()`，永远不相等，于是任何后台观察都显示"本机未提供后台屏的无障碍树"，`type` 一律被拒。改为先取 `/` 前的包名再比。修好后同一屏同一页面立刻能读到 `EditText`。

**2. 写入被接受却报成失败（回读时机与过期句柄）。** `ACTION_SET_TEXT` 返回 `true` 只代表视图接受了请求，值在下一帧才生效；而回读复用了写之前拿到的窗口对象，视图重建后它可能仍报旧值。实测两者叠加：树里已经出现新文本，`setTextOnDisplay` 仍返回 `false`。改为写完之后在调用线程上、用**每次重新枚举**的窗口对象做有界重试（最多 1.2 秒），并且不把等待放在无障碍服务主线程上。回读仍然读不到就判失败——"接受"依旧不等于"生效"。

`focusIsolation` 的一个细节值得写清楚：另一块屏存在时，主屏的 `mCurrentFocus` 会读成 `null`，从**创建那一刻**就是这样，与是否 launch 无关。但它同时满足：`mFocusedApp` 仍指向用户原应用、`dumpsys input` 里 `FocusedDisplayId: 0` 仍在、且实测向 display 0 注入 HOME 只改变 display 0 的栈顶（后台屏的 calculator 不动）。所以这是**上报形态**，不是主屏失效。判定因此以 app 焦点与栈顶任务为准，窗口焦点只作为证据记录。

## 截图路径

后台屏的画面来自该屏自己的输出，而不是外部命令：

1. 创建屏幕时，`ImageReader` 的 Surface 就是该屏的输出面。一个消费线程持续 `acquireLatestImage` 取帧再释放，**保留最近一帧**（最多每 200ms 复制一次，避免整屏像素高频拷贝）。
2. 截图时直接复制这一帧并按物理屏同一预算缩放到 JPEG（`source=imagereader`）。
3. 消费线程同时解决了一个隐患：`ImageReader` 的 buffer 是有限的，没人取帧时生产者会阻塞，屏幕会停止绘制。旧实现建了 reader 却从不读，屏幕刷几帧后就可能卡住。

保留 `screencap -d <SurfaceFlinger token>` 作为回退（`source=screencap`），原因有两点：刚建好、还没启动任何应用的 own-content 屏不合成任何帧，此时 reader 取不到东西，而 `screencap` 能返回“确实是空屏”；再者两条路径不完全等价，`screencap` 会应用该屏的安全窗口策略，回退能区分“差异来自截图路径”还是“来自屏幕本身”。两条路径都只按该屏自身身份寻址，都不会退回物理屏。

### FLAG_SECURE（实测结论）

新路径读的是该屏的输出面，是否绕过 `screencap` 的安全窗口策略必须实测，不能假定相同。用带 `FLAG_SECURE` 的 debug fixture（`SecureFixtureActivity`）在目标机上测：

| 路径 | 结果 |
| --- | --- |
| reader 直读该屏输出 | 显示 canary 文本 |
| `screencap -d`（root）读同一屏 | 显示 canary 文本 |
| `screencap` 读主屏（shell 身份） | 显示 canary 文本 |
| `screencap` 读主屏（root 身份） | 显示 canary 文本 |

四者一致，说明**该 ROM 对 `screencap` 本身就不拦 `FLAG_SECURE`**（窗口属性里 `fl=... SECURE ...` 已确认置位）。因此 reader 路径不是相对旧行为的回退；安全边界的实际情况由 ROM 决定，不由本项目引入。换 ROM 必须重测这一项，不能沿用本结论。

## 主屏应用冲突与前台交接

多数应用是 `singleTask`。如果目标应用已经在主屏可见，`am start --display <虚拟屏>` 不会新建实例，而是**把用户正在看的那个任务搬过去**——用户被丢回桌面，命令却报 `Status: ok`。这正好是本功能最不能出的错。

加 `MULTIPLE_TASK` 无效：实机实测任务 `#191` 仍从 display 0 移到虚拟屏，因为复用由 Activity 自身的 launchMode 决定，不由启动标志决定。

启动前通过系统 TaskInfo 的真实 Activity/component 与 task identity 判断归属，不再把 taskAffinity 当包名。任务查询失败时停止，不解释为没有冲突。其他屏上可见任务仍被底层 `TASK_IN_USE` 拒绝；启动后继续验证目标落点。

两条控屏入口共用交接规则：尚未在后台应用执行操作、首次 launch 的目标就是主屏当前应用时，关闭后台屏，改为前台控屏；wait 不算已经使用后台应用。中途遇到主屏当前应用时，灵动岛显示“小爱请求使用当前应用”，提供“同意并前台控屏”和“拒绝并暂停”。卡片没有打开小爱的点击入口。请求绑定调用 UID、当前任务和一次性 token，等待最多 60 秒；拒绝、超时或等待期间主屏应用变化都结束本轮，不执行原动作。

同意后再次核对主屏应用，再关闭后台屏、作废旧截图/坐标、返回新的主屏观察，由模型重新规划。不会拿后台坐标直接操作主屏。没有可用的小爱岛交互时请求无法获准，超时后暂停。

按用户对问题5的取舍，不恢复虚拟屏使用前的 Activity/页面。关屏保持 `DESTROY_CONTENT_ON_REMOVAL`，会结束该屏的 Activity/task；模块不对目标应用执行 force-stop、killBackgroundProcesses 或进程 kill。应用进程的缓存与回收由 Android 决定，不能承诺系统永不回收。

其他限制：

- 一个模块同时只有一个后台会话，与既有单任务约束一致。

## 为什么有的项是未测

探测按“跑过才算数”的原则输出 `supported` / `unsupported` / `untested`，未测不等于支持。

- `pointer`、`text/key`：注入被接受不等于送达。判别用同一屏的前后帧比对：帧变了才算送达。若帧没变则报 `untested`——点可能落在无响应区域，既证明不了送达也证明不了没送达，两种结论都不该下。实测中带数字键盘的页面上点击确实改变了帧（`f1.jpg` ≠ `f2.jpg`，且连续两次相同），证明送达；换成无响应区域时即为 `untested`。
- `tree`：窗口树由模块无障碍服务的 `getWindowsOnAllDisplays()` 提供，helper 进程里没有这条链路，所以它明确报告未测并说明该去哪儿测，而不是从截图反推。

## 错误码

模块侧按 code 分派，不匹配文案（早期实现把 code 当键、文案当值，模块读不到，于是所有失败都塌成一条通用错误——已修）：

| code | 含义 |
| --- | --- |
| `PERMISSION_DENIED` | 未取得 root 授权 |
| `DEVICE_LOCKED` | 设备锁屏，先解锁 |
| `NO_DISPLAY` / `DISPLAY_UNAVAILABLE` | 后台屏已失效，任务停止 |
| `TASK_IN_USE` | 目标应用已在用户屏幕上，拒绝启动 |
| `TASK_STOLEN` | 启动未落在后台屏，任务停止 |
| `CAPTURE_FAILED` | 后台截图失败，任务停止 |
| `BACKEND_DIED` | helper 进程退出或无响应 |

## 能力探测

开发用探针，只读设备事实与窗口归属，不含页面内容或凭据：

```sh
# 模块/root 专用 IPC
content call --uri content://io.github.miaiplus.bridge --method background_display_status
# 要测 launch 时把组件放进 --arg；不放则 launch 一项如实报 untested
content call --uri content://io.github.miaiplus.bridge --method background_display_probe \
    --arg com.miui.calculator/.cal.CalculatorActivity
```

`background_display_status` 轻量（只起 helper 问身份，不建屏），可以放在定时刷新里；`background_display_probe` 会真正建一块屏再销毁，因此单独一个方法，不要让每秒刷新去跑。

完整探针与控屏任务互斥：包含准备阶段、原生两次工具调用间隔和收尾阶段；探针运行中拒绝新任务。探针启动应用也拒绝其他任意屏的可见任务冲突。轻量状态查询使用自己的截图目录，不删除活动任务的帧。

两份方法都限制为模块 UID 或 root。探针输出机器可读 JSON；一次实机结果见 [能力报告](virtual-display-capability-report.json)。

## 长任务会话（滚动采集）

真实信息流上的连续滚动曾表现为“滑不动、会话反复过期”。显示后端和输入都是好的：实测该屏连续滚动 10 次全部被接受，帧持续增长，酷安任务全程 `visible=true`。问题在三处会话管理：

| 缺陷 | 现象 | 修正 |
| --- | --- | --- |
| 每次调用前重观察，任何树差异即终止会话 | 信息流自行加载时，每步都被判“页面已改变” | `PageGuard`：只有控件锚定动作（tap/double_tap/long_press/type）要求页面未变，滚动与导航只要求前台应用未变 |
| 240 秒预算从任务开始计 | 长任务中途到期，此后每次调用都报“会话已过期” | 预算改为按步预留，每次被接受的调用续期；看门狗改为“持续无进展才停止” |
| 未读截图上限 4 个，超出即抛错 | 同样表现为会话中途失效 | 超出时淘汰最旧一条，任务不中断 |

后台屏若被系统确认窗口占据（如安全中心 `ConfirmStartActivity`），`BackgroundActions` 返回 PAUSE 而不是对着权限框滑动。

实机验收：`ScrollRunner`（`am instrument -e scroll true`）在酷安真实信息流上连续滚动 12 步，每步间等待 1.8 秒让列表自行加载，断言单一会话贯穿全程、前台始终为目标应用、结束后用户主屏栈顶任务未变。通过。这条用例需要真实第三方应用已信任过启动确认（首次启动会在后台屏弹确认）。

## 与主屏模式的关系

`NativeSession` 与 `AgentEngine` 共用同一后端与同一套规则：观察、动作、确认都按当前模式分派。后台模式下确认弹窗不显示在物理屏上（那会盖住用户），而是返回 PAUSE 等用户重新发起；后台模式也不支持小米启动确认弹窗，需要授权时 `take_over`。

无障碍树在后台屏拿不到时按空树上报，prompt 会明确说明“本机未提供后台屏的无障碍树”，让模型按纯视觉处理，而不是借用主屏的树。

## 本轮正确性修复（2026-10-17）

- 原生会话停止立即取消，随后异步释放本会话的后台屏；旧会话的清理不能关闭替换后的新会话。每次接受步骤同步续期 deadline 和 watchdog，旧计时回调不能误停新预算。
- 帧节流保留最新待复制图像，空闲时仍完成最终帧复制，观察时刷新待复制图像；不再永久丢失 200ms 内最后一次重绘。
- 输入只写目标屏上已聚焦、可见、非密码的 editable 节点；回读原窗口与原节点，完整值精确匹配。没有焦点、节点消失、值不可读或替换未生效均不报成功。
- tap、long_press、swipe、key 的拒绝/缺失确认向上传播。手势共享 downTime，eventTime 使用实际 uptime，按目标时间调度；输入法策略必须设置并回读确认为 LOCAL 后才能就绪。
- 主动语音/快捷键/蓝牙唤醒及桌面启动可解除收尾静默；没有新唤醒时继续抑制旧结果拉起小爱。探针缺少稳定主屏基线时先判空，相关隔离项记为 untested。

本轮未修改审查中的问题1、2和暂不确定事项；问题5接受页面不保留，未增加任务恢复。审查另列的三项设备验收漏检仍需专项复验：滚动位移、真实触摸隔离、强制清理前的 helper 正常退出；历史 PASS 不代表这些负例已覆盖。

本轮本地验证：146 项 JVM/Robolectric 测试、Debug/Release 完整 Lint、Debug/Release APK 与 AndroidTest APK 构建均通过。原生混合工具批次的拒绝处理使用当前精确支持版本已核实的每工具停止 gate；只接受本次控屏工具来源与专用结果，不对页面文字或其他工具回显文本设置终态。宿主 DEX 核查及本地回归不能代替更新 APK 后的设备闭环。
