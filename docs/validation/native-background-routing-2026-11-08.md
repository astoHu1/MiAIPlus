# 官方模型后台控屏入口修复（2026-11-08）

本轮调查“虚拟屏自检通过，外接模型能后台控屏，小爱自身模型不能”的反馈。测试设备为 Xiaomi／Android API 37、小爱 `8.3.30.4420`；使用本地 Debug 与 AndroidTest 构建。修复起点为 `d304460`，本页记录该提交之后的源码与本地测试包，不能当作既有发布包的能力证明。

## 根因与修正

虚拟屏自检和历史双工具探针直接调用 helper、Provider 或注册表代理，没有让小米官方模型从真实聊天入口选择工具。它们可以证明显示后端工作，无法证明普通小爱请求已进入工具循环。

修复前，在关闭全局模型替换、使用原生规划的隔离话题中，经真实 `onSendClick` 提交后台计算任务，观察到 `onSendOsbotRoute=false`，35 秒内未进入任何已适配的 OSBot 模型客户端，也没有控屏工具调用。宿主普通／快速文字入口可走 AIVS `Nlp.Request`；原实现只在全局外接模型开启时选择原生 MiClaw 工具入口。这解释了同一显示后端在两种模型设置下的差异。

文字修复仅在后台插件启用且本次请求明确要求后台／虚拟屏应用操作时，选择宿主已有的 MiClaw 入口。作用域绑定同步提交、callback 实例和线程，提交结束立即恢复；不持久修改小爱模式、模型、端点或模块默认执行屏幕。普通聊天、概念解释、后台音乐及明确否定后台操作的请求保留原入口。配置服务不可用时停止提交，不把无法读取目录解释为插件关闭。

语音有独立的最终识别入口，不能用文字测试代替。新增 Hook 只接管仍属于当前 `main` 的 `VOICE` 请求、精确匹配事件 requestId 的最终识别结果，要求实际 SpeechEngine 同时 `isMainEngine` 和 `isRequesting`；最终识别停止录音不等于请求结束。真实 ASR 的旧 requestInfo map 可为空，缺少记录不能拒绝，但已有 `FINISH` 仍拒绝。沿用相同后台意图与插件检查后交给宿主 `sendToMiClaw`。重复 Final、已取消／结束请求、其他引擎及 MiClaw 自身无 requestId 的回调不能重开任务。排队取消在原任务 handler 上再次核对身份，防止旧请求取消后续录音；宿主清空 handler 队列时也会有界释放重载占用。

同时补全旧 CLI 入口的提示协议：首次用 `MIAIPLUS_START` 显式携带 `origin_tool`；续调保留返回的会话、同一来源工具和坐标空间。执行端继续拒绝跨工具续调和屏幕覆盖；这部分不是本轮真实模型已选择 CLI 的证明。

## 构建与本地回归

本轮最终检查执行：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:assemblePreview
```

最终 `final-build2.log` 完整检查为 556 项 JVM／Robolectric，失败、错误和跳过均为 0；Debug／Release 完整 Lint 均 0 错误，各列出 38／27 条警告和 1 条提示。Debug、Release、Debug AndroidTest 和优化 Preview 均构建成功。日志、配置备份、原始设备资料和截图只留在本地忽略目录，不加入分发。

新增回归覆盖后台意图分类、说明／否定请求、同步作用域与跨线程隔离，以及语音请求归属、重复 Final、取消／结束状态、替换任务、排队新录音和重载占用释放。CLI 提示示例还验证了默认屏幕设置反转时的显式路由与错误来源拒绝。

## 实机验收方法

授权设备解锁、root 与无障碍健康连接后，可分别运行：

```sh
python3 scripts/device_test.py --native-background-model
python3 scripts/device_test.py --native-background-voice
```

两项均保存原配置，关闭全局外接模型、选择 `planner=native`，创建独立本地话题。固定任务为“在后台独立虚拟屏先清空计算器，再实际输入 137×29，读取结果并 finish”；工具由真实官方模型选择，不使用 mock、固定代理调用或代答。记录实际客户端、原生 model/provider/视觉能力、真实工具结果、会话与图片数、实际动作及执行状态。

文字项提交真实 `onSendClick`。语音项启动真实 main ASR 请求，核对生产请求归属后向真实 `onResults` 提供固定的合成最终识别文本，并要求恰好一次 `sendToMiClaw`；它验证语音识别结果的提交路径，不验证声学识别准确率、实体按键或唤醒词。

验收要求单一后台会话、每次观察附图、至少 4 次 `tap:executed`，以及 finish 之前的最后一张计算器观察显示正确公式和 3973，随后真实工具明确 finish。每次新观察都会使旧结果证明失效。主屏由自有 fixture 占用，持续检查主屏栈顶／焦点、零触摸和空输入。任务结束核对 helper／display 释放，并恢复配置、验证原话题路由，删除隔离话题。

本机计算器后台可能没有可用无障碍树，因此结果以实际图片为准。Runner 仅在本轮 task／session、观察步数和图片摘要均匹配、执行器空闲、实际后台前台包为计算器时，复制执行器已经生成的 JPEG 到专用私有缓存。主机读取该固定缓存，由 `verify_calculator_frame.py` 调用 Tesseract，仅读取图高 24%–40% 内的当前计算区域，要求整行公式 `137×29` 与整行结果 `3973`／`3,973` 同时成立，再用同 nonce、步数和图片摘要回执；历史栏和数字子串不能通过，旧帧回执不能解锁后续观察。两张实际后台图片已作正负核验：当前公式与结果齐全的帧通过；历史栏有 3973、当前输入为 1 的帧拒绝。图像保留在本地忽略目录供人工核对，设备专用缓存随测试清理。最终聊天回复正文未验证；探针在控屏完成后清理 owned turn，可能提前取消自然语言收尾，不能将诊断聊天截图视为最终回复成功。

为区分模型看图判断错误与传输漏图，调试探针还只读观察 owned 会话中 `dn.i.buildOpenAIRequest$llm_client` 最终返回的 JSON，记录附图总数、最后附图摘要及其与本次工具观察的匹配计数；不修改请求或 Flow，不输出正文、URL、Base64 或完整 JSON。清理阶段等待真实 host completion guard 结束后才尝试打开隔离话题截图；截图诊断可以跳过，但原话题路由恢复与隔离话题删除失败仍使验收失败。

## 本轮确定性设备回归

`python3 scripts/device_test.py --dual-tools` 的最终日志 `dual-tools-final2.log` 通过：两种旧 `backgroundDisplay` 默认值下，前台／后台工具均已实际导出和注册，分别启动对应屏幕；错误来源工具的续调被拒绝，但原会话没有丢失，仍能继续。后台 fixture 的 tap／swipe 未触及物理 fixture，正常 finish 后 helper／display 清理，默认配置未变。这是注册表与确定性动作回归，不代替官方模型选择工具的验收。

`python3 scripts/device_test.py --background` 的最终日志 `background-isolation-final4.log` 通过：主屏始终为自有 `FixtureActivity`，无触摸或文本输入；后台 launch／back／finish 成功，启动后树可用。输入项记录 `typingTree=present`、`editorTaps=3Delivered`、`typeVerified=inBackgroundTree`；物理输入法共 2188 次采样，`physicalImeLoss=null`。结束后显示列表为 `[0]`，无残留虚拟显示。该项采用确定性测试目标，不使用真实官方模型作规划。

早期三次 `background_display_test.py` 失败来自脚本未补入现有 Runner 要求的 `LAUNCH_FIXTURE` 握手，不能归为后台实现失败；已补脚本，最终改用上述 `device_test.py --background` 获得明确通过。`background-isolation-final3.log` 无有效输出且异常退出，不计通过，也不以最终通过覆盖这些尝试。

最终 `hot-reload-final.log` 记录 LSPosed API 102、目标 PID 28718、`state=UP_TO_DATE` 和 `reload=SUCCEEDED`。随后以 `--no-restart` 执行 `official-final-voice-reload.log`，在同一宿主完成下表的完整语音结果入口复验。

## 官方模型最终结果

三项均为 `globalModel=false`、原生 `mimo-omni`／provider `openai`，实际使用 `miaiplus_background_phone_use`。每项均为单一后台会话，最后图片由 OCR 及人工核对为 `137×29=3973`，真实调用 finish，主屏零触摸／空输入；helper／display 正常释放，原路由、隔离话题和配置恢复检查通过。

| 最终日志 | 工具调用 | 观察／附图 | 已执行点击 | 最新请求图片摘要匹配／不匹配 | 主屏隔离采样 |
| --- | --- | --- | --- | --- | --- |
| `official-final-typed3.log` | 11 | 10／10 | 8 | 10／0 | 167 |
| `official-final-voice2.log` | 15 | 14／14 | 12 | 14／0 | 317 |
| `official-final-voice-reload.log` | 12 | 11／11 | 9 | 11／0 | 199 |

两次语音项均确认真实生产请求归属、`sendToMiClaw=1` 与重复 Final 被抑制。它们验证真实 ASR 请求承接固定合成最终识别文本后的完整控屏路径；不验证声学识别、实体按键或唤醒词。最终 JSON 图片摘要匹配证明宿主序列化带上最新观察，不能单凭此证明服务端收到或模型正确理解所有画面。

## 分阶段结果与探针限制

| 阶段 | 已观察结果 | 结论边界 |
| --- | --- | --- |
| 修复前真实文字提交 | 官方模式 `route=false`，OSBot 客户端和控屏工具均 0 次 | 复现入口绕过工具循环；不说明显示后端失败 |
| 早期修复后尝试 | 官方 `mimo-omni`／OpenAI 客户端实际进入后台工具；一次尝试记录 14 次调用、14 帧、单会话、主屏隔离，但 240 秒未获得有效完成证明 | 只计部分链路，不计最终通过 |
| 探针修订 | gate 单点观察漏记部分真实调用；曾加入的 Flow 包装无法证明透明；已恢复带执行身份的工具 afterHook 与 gate 去重，删除全部 Flow 包装 | 探针实现问题不归为生产显示故障 |
| 图片核验修订 | `run-as` 被设备目录策略拒绝；Swift 冷编译及预编译 Vision 运行均曾超时；整图找结果可能误中历史 | 改为校验固定路径后 root 读取私有帧，以 Tesseract TSV 限定当前公式／结果区域；早期截图及中断均不计最新通过 |
| 末帧与清理修订 | 一次文字运行中间得到正确公式和结果，后续模型重复按键改变了算式；旧探针保留历史通过状态，且截图导航被结束后的正常 quiet guard 压制 | 清空每步旧证明并按最后观察的步数、图片摘要绑定回执；等 guard 自然结束后再诊断截图，严格验证原路由恢复；该次运行不计通过 |
| 语音首次及诊断失败 | 最初未到生产 ownership 边界；诊断确认 main／VOICE／事件匹配而旧 requestInfo map 为空，等待超时后引擎已不活跃；结合宿主生命周期核对发现旧 map 门槛错误拒绝 | 改用实际 `isMainEngine && isRequesting` 加精确请求归属，空 map 可接受，已知 `FINISH` 仍拒绝；失败尝试不计通过 |
| 最终文字、语音及热重载后语音 | 三项严格验收均通过，数据见上表 | 只证明本轮固定计算器任务与该设备／模型，不外推任意任务质量、声学识别或回复正文 |

官方模式不等于某一个客户端类。最终文字样本已观测到 `globalModel=false`、原生 `mimo-omni`、provider 为 `openai`、`getSupportsVision=true`，客户端为 `dn.c`／`dn.i`；`dn.i` 本身不能被解释为使用了用户外接模型。端侧 Taiyi 的序列化不读取附图是另一条已核对的实现限制，本轮没有修改它，也不能推广为全部官方云模型都丢图。

## 安装与设备收尾

已安装最终 build2 的优化 Preview（versionCode 17／versionName `1.0.2`，无 `DEBUGGABLE` 标志），重启小爱后冷启动状态和 Hook 报告为 ready。最终配置与原始配置逐项相同，仅允许 `hook_report`／`hook_at` 诊断字段变化；保留用户原有 `globalModel=true` 等业务设置，未把官方模型测试配置留在手机上。模块 ScreenService 健康连接保留，以便继续使用功能。

收尾检查确认后台任务 `active=false`、仅有 Display 0、无 DisplayHelper，ADB reverse 列表为空。随后实际执行 `input keyevent 223` 锁屏并回读：`isKeyguardShowing=true`、`mWakefulness=Dozing`、display `state=OFF`／`committedState=DOZE_SUSPEND`。手机已锁屏，未保留点亮的测试画面。
