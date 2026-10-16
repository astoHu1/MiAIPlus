# MiAIPlus

为超级小爱增加本机控屏工具及可选的 OpenAI 兼容主模型替换。**全新安装不预填任何服务地址、API Key 或模型名。**

当前精确适配超级小爱 **8.2.65.4714 / versionCode 508002065**，Android 13+，支持 libxposed API 102 的 LSPosed。
其他小爱版本跳过 Hook。`0.3.3` 是预发布测试版，包含稳定应用启动与小米启动确认自动处理、焦点岛实时意图、展开岛停止控制、任务级页面恢复、可选 root 控屏前无障碍检查和 API 102 热重载。

## 三种使用方式

| 使用方式 | 小爱主对话与工具规划 | 控屏规划 | 是否需要额外 API |
|---|---|---|---|
| 默认 | 官方原模型 | 同一个官方模型逐步调用本机控屏工具 | 不需要 |
| 全局外部模型 | 用户配置的模型 | 同一个外部主模型逐步调用本机控屏工具 | 需要 |
| 独立外部控屏循环 | 官方模型或用户选择的全局模型 | 模块单独调用外部视觉模型运行 phone use / AutoGLM 循环 | 需要 |

默认模式优先使用原生结构化函数 `miaiplus_phone_use`：首次传 `goal`，后续传 `session` 和 `action`。默认坐标为截图 JPEG 像素；可用 `coordinate_space` 明确指定截图像素、屏幕像素或归一化坐标。兼容入口 `voiceassist__controlApp` 首次接收完整任务，工具返回截图、无障碍树、会话号与 `CONTINUE`；小爱当前模型继续调用同一工具，每次一个原子动作，直至观察确认完成。**不再要求第二个视觉 API。**
截图经一次性私有管道送回小爱，转换为原生 `Message.images`，不把整张图塞进 Binder Bundle。模型或官方传输链不支持图片时，仍有无障碍树，但对纯图片页面的能力会受限。

“全局外部模型”接入小爱的 OSBot 统一模型入口和直接 Taiyi / OpenAI / Responses / Anthropic 客户端，以及记忆摘要、记忆 gate、能力判断和语义路由请求，保留原消息历史、提示词、流式响应、工具定义与工具执行链。自定义 API 开启时，聊天页普通文字发送也转入原生 MiClaw/OSBot 链路，避免 AUTO/QUICK 模式绕回旧云端；不改写小爱原模式。主对话、子 Agent 和经这些入口的辅助推理使用所选模型。它不把语音识别、语音合成、搜索引擎或米家服务改造成第三方服务，也不宣称覆盖 APK 内所有独立云服务。

## 安装与配置

1. 安装 APK，在 LSPosed 启用 **MiAIPlus**，作用域只选超级小爱 `com.miui.voiceassist`。
2. 打开 MiAIPlus，开启“启用本机控屏工具”，规划模型保持“小爱当前模型”。**可以留空全部 API 字段并保存。**
3. 在系统无障碍设置启用 MiAIPlus。小米系统如需自动打开其他应用，在应用信息 → 其他权限中允许“后台弹出界面”。
4. 重启小爱，模块状态应包含 `ready`、`native-planner`、`global-model`。这是 Hook 安装状态，不等于当前开启了全局外部模型。
5. 在小爱中提出明确的应用内任务。普通聊天、搜索、手机设置和已有专用工具继续使用原链路。

控屏时焦点通知右侧随步骤显示模型的简短操作意图；等待下一步时保留最近意图。模型未提供意图时显示动作说明，更新不反复弹卡片。展开灵动岛，点击“停止操作”。敏感操作仍单独弹出确认。多步工具调用与等待模型规划期间保持目标页面，整个控屏会话完成、取消、超时或接管后才按入口恢复小爱或显示完成岛。

控屏打开应用会自动处理与当前目标匹配的小米“本次允许”启动确认，确认目标确实进入前台后才继续；应用未打开时明确暂停，不虚报成功、不循环拉起。不同应用同名时使用包名。此功能无需 root，其他权限仍需接管。详见 [应用启动](docs/app-launch.md)。

可选开启设置页“root 控屏前检查无障碍”，并在 KernelSU / Magisk / APatch 授予 **MiAIPlus 本身** root 权限。每个新控屏任务开始前检查真实 Binder 连接，失效时才通过 root 重绑 MiAIPlus，处理小米系统“界面显示已开、实际已断”的情况并保留其他服务。没有常驻巡检、开机任务或中途修复；同一任务的后续工具调用不再检查。默认关闭。ADB 能执行 root 不代表应用已有授权。详见 [任务前检查](docs/accessibility-guard.md)。

可选外部服务：填写实际 API Base URL（含 `/v1`）、Key 与模型名；可从服务获取模型列表。
设置页“API 配置 · 快捷切换”可保存多套命名配置，每套包含 Base URL、Key、模型和 API 协议。原单套配置保留为“默认配置”；编辑后用“保存当前 API 配置”更新，用“另存为新 API 配置”新增。下拉选择立即切换，未保存修改可选择保存或放弃；支持重命名和删除，至少保留一套。启用开关、全局外部模型、控屏规划和最大步数仍为全局设置。“保存配置”同时保存当前 API 配置和全局设置。
Endpoint 切换用于后续新请求，正在发送的请求保持原参数；独立外部循环在任务开始时读取配置，需下一次任务才使用新配置。原生聊天提交、模型调用、记忆和路由请求开始时刷新配置，避免短暂沿用旧端点。获取模型列表期间切换配置会丢弃旧服务的列表结果。
模型 API 协议可选“自动 / Responses / Chat Completions”，与 phone use / autoglm 动作协议独立。自动模式下 `gpt-6` 开头的模型使用 `/responses`，其他名称保持 `/chat/completions`；自定义别名或仅提供 Responses 的网关请显式选择 Responses。`gpt-6.1-sol` 工具调用要求 Responses，纯文本可用 Chat Completions，见 [官方模型说明](https://developers.openai.com/api/docs/models/gpt-6.1-sol)。地址仍填写 Base URL（例如服务提供的 `/v1`），无需手动附加 `/responses`。
Responses 同时用于全局主模型、记忆摘要、gate/能力判断/路由，以及独立文字和控屏循环；转换图片、工具定义、call_id 与结果，并回传可用的加密推理上下文。Responses 请求使用 `store:false`，不发送固定 temperature/top_p；独立请求输出预算为 16384 tokens，GPT-6 原生请求的小额文本预算至少调整为 8192 tokens，以容纳推理。该预算包含推理 token，仍可能耗尽；响应 incomplete/failed、拒绝或流中断时不执行未完成工具调用。
要替换小爱主模型，开启“小爱全局使用外部模型”，保存并重启小爱。要使用独立的控屏模型循环，另选“独立外部视觉模型循环”。两者是独立选项。
自定义 API 启用后，小爱聊天页原来的“自动 / 快速 / 专家”入口切换为所选模型的推理档位（普通输入栏和全屏功能栏均适配）。关闭全局外部模型后仍使用小爱原菜单；自定义档位按服务地址、模型名和 API 协议单独保存，不改写小爱原模式。选择立即用于下一次新请求；已在执行的请求保持开始时的参数。“模型默认”不发送显式思考参数。

| 自定义模型 | 可选档位（均含“模型默认”） |
| --- | --- |
| GPT‑6.1 Sol、GPT‑6 Astra | low / medium / high / xhigh / max |
| GPT‑6 Sol / Luna、GPT‑5.6 Sol | none / low / medium / high / xhigh / max |
| GPT‑5.5 / 5.4 / 5.2 | none / low / medium / high / xhigh |
| GPT‑5.3 Codex | low / medium / high / xhigh |
| GPT‑5.1 | none / low / medium / high |
| GPT‑5 | minimal / low / medium / high |
| DeepSeek Flash (`deepseek-flash`) | 关闭 / low / high / max |
| 已识别的 Qwen 混合思考模型（Chat Completions） | 关闭思考 / 开启思考 |
| 未识别别名、其他模型或未经确认的协议组合 | 模型默认 |

OpenAI 在 Responses 中使用 `reasoning.effort`，Chat 中使用 `reasoning_effort`；GPT‑6.1 Sol 不提供不受支持的 none/minimal。DeepSeek Chat 使用 `thinking.type` 与 `reasoning_effort`；Qwen Chat 使用 `enable_thinking`，不把 SDK 的 `extra_body` 包装发到服务端。Qwen 仅识别已确认的混合模型名称，思考专用模型不显示关闭开关；网关的不同扩展字段不能仅凭兼容端点推断。当前不直接转换原生 Anthropic/Gemini 协议，也不为它们虚构通用强度。

档位依据 [OpenAI GPT‑6.1 Sol](https://developers.openai.com/api/docs/models/gpt-6.1-sol)、[OpenAI reasoning guide](https://developers.openai.com/api/docs/guides/reasoning)、[DeepSeek 思考模式](https://api-docs.deepseek.com/guides/thinking_mode) 和 [Qwen 深度思考](https://help.aliyun.com/zh/model-studio/deep-thinking)；具体识别名单见 `ReasoningPolicy.java`。模型升级或网关别名可能需要更新名单。

`phone use` 协议要求模型支持图片与 function calling；`autoglm` 使用 Open-AutoGLM `do(...)` / `finish(...)` 协议。切换主模型时仍应选支持小爱工具调用的模型。
原 `computer` 动作协议现显示为 **phone use**，已有协议设置兼容读取；独立循环工具名为 `phone_use`，原生工具为 `miaiplus_phone_use`。更新工具名后请热重载模块或重启小爱。

设置页直接测试适用于独立外部循环和第三方纯对话。默认控屏模式需在小爱里提交任务，因为规划模型就在小爱内部。明确的“用第三方模型回答……”可走独立文字请求，需填写外部配置，不读取屏幕。

## 数据与边界

- 默认模式的屏幕观察交给官方小爱当前模型，按其原生传输路径处理；并不意味着模型完全离线。全局外部模式会把原生聊天历史、系统提示、工具上下文及模型可接收的屏幕观察发送到所选服务。
- 密钥保存在模块 UID 的私有配置中；全局替换开启时，经校验的 IPC 临时交给小爱进程中的原生客户端。不会写入小爱配置文件。原应用自己的日志/缓存行为仍由原应用决定。
- 模块设置页禁截屏与备份；执行器不将屏幕图像写盘。原生模式的图片加入小爱消息后，可能受小爱自己的会话保存策略管理。
- 公网只允许 HTTPS；字面 LAN/loopback 地址允许 HTTP。模块自有 HTTP 客户端及全局模式的独立 Ktor 客户端均不跟随重定向；后者不复用小米账号、设备签名或服务令牌拦截器。
- 本机执行点击、长按、双击、滑动、中文文本替换、返回、主页、应用启动，没有额外应用白名单。密码框、锁屏、验证码、安全窗口和不可编辑控件需要接管。
- 每次只执行一个动作；停止键、四分钟截止、步数限制、重复动作检测、前台与尺寸检查保留。提交确认依赖模型正确标注，不能保证任意模型永不误操作。
- 官方模型是否正确理解/持续选择工具、是否支持图片、原 Agent 的回合与上下文预算，仍影响默认模式任务成功率。不得把工具通路测试当成所有应用任务的保证。
- AutoGLM 是采用的开源动作协议/循环参考；APK 不包含 AutoGLM 权重。默认由官方小爱规划，选择外部模式才需要另外的推理服务。

撤销：停止任务，关闭全局外部模型及控屏开关，保存；在 LSPosed 停用模块并重启小爱。无需还原小爱模型配置文件。

## 构建与发布

需要 JDK 17+、Android SDK platform 37 / build-tools 37.0.0。项目使用 Gradle 9.6.0、AGP 9.4.1，targetSdk 仍为 35。LSPosed 运行时需要支持 libxposed API 102；首次从旧入口迁移后要重启一次小爱，后续可在设置页请求热重载。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:lintRelease
python3 scripts/package_release.py
```

`dist/` 提供未签名 release APK、开发签名预览 APK、源码压缩包及 SHA256 校验。release 变体不含测试页面和调试广播接收器。开发签名仅便于测试；正式分发请使用自己的长期私有签名，升级需保持同一签名。
源码包按文件白名单生成，排除原始 APK、反编译文件、实机日志、个人配置、工具链缓存和签名密钥。
详见 [发布说明](docs/releasing.md) 和 [API 102 / 控屏交互说明](docs/api102-migration.md)。

## 测试

```sh
python3 scripts/mock_model.py
adb reverse tcp:18765 tcp:18765
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 scripts/device_test.py
# 仅验证多 API 配置迁移、保存和快捷切换；无需模拟服务，结束恢复原配置
python3 scripts/device_test.py --profiles
# 需要在 LSPosed 启用模块；只在已授权测试设备运行进程内诊断
python3 scripts/device_test.py --hook
# 实际应用启动、自动启动确认与前台验证（使用独立测试 APK）
python3 scripts/device_test.py --hook --launch
# Responses 协议的独立循环和原生 Hook 回归
python3 scripts/device_test.py --responses
python3 scripts/device_test.py --hook --responses
python3 scripts/device_test.py --hook --responses --reasoning
python3 scripts/device_test.py --hook --reasoning
adb reverse --remove tcp:18765
```

设备测试临时修改模块自己的配置，结束时恢复；重连模块无障碍服务，保留其他服务。测试使用独立测试页面，不操作个人应用内容。`--hook` 会重启小爱，并在 debug 模块内进行真实 Hook/原生模型客户端诊断。
真实凭据和官方模型的完整自然语言任务验证另见 [验证状态](IMPLEMENTATION-STATUS.md)。
不修改 API 配置的网络异常回归（debug 模块，已启用 LSPosed 并打开小爱）：

```sh
adb shell am broadcast -a io.github.miaiplus.DEBUG_PROBE -p com.miui.voiceassist --es frame MIAIPLUS_TRANSPORT_FAILURE
```

结果写入模块状态报告；使用进程内 loopback 服务，不调用付费模型，检查 Chat/Responses 的断连、取消和异常 JSON 是否进入原生 IOException 回调。
开源参考及许可见 [架构](docs/architecture.md) 与 `third_party/open-autoglm/`。
