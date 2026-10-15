# MiAIPlus

为超级小爱增加本机控屏工具及可选的 OpenAI 兼容主模型替换。**全新安装不预填任何服务地址、API Key 或模型名。**

当前精确适配超级小爱 **8.2.65.4714 / versionCode 508002065**，Android 13+，LSPosed Legacy API 82+。
其他小爱版本跳过 Hook。`0.2.1` 是预发布测试版。

## 三种使用方式

| 使用方式 | 小爱主对话与工具规划 | 控屏规划 | 是否需要额外 API |
|---|---|---|---|
| 默认 | 官方原模型 | 同一个官方模型逐步调用本机控屏工具 | 不需要 |
| 全局外部模型 | 用户配置的模型 | 同一个外部主模型逐步调用本机控屏工具 | 需要 |
| 独立外部控屏循环 | 官方模型或用户选择的全局模型 | 模块单独调用外部视觉模型运行 computer / AutoGLM 循环 | 需要 |

默认模式优先使用原生结构化函数 `miaiplus_computer`：首次传 `goal`，后续传 `session` 和 `action`。默认坐标为截图 JPEG 像素；可用 `coordinate_space` 明确指定截图像素、屏幕像素或归一化坐标。兼容入口 `voiceassist__controlApp` 首次接收完整任务，工具返回截图、无障碍树、会话号与 `CONTINUE`；小爱当前模型继续调用同一工具，每次一个原子动作，直至观察确认完成。**不再要求第二个视觉 API。**
截图经一次性私有管道送回小爱，转换为原生 `Message.images`，不把整张图塞进 Binder Bundle。模型或官方传输链不支持图片时，仍有无障碍树，但对纯图片页面的能力会受限。

“全局外部模型”接入小爱的 OSBot 统一模型入口和直接 Taiyi / OpenAI / Responses / Anthropic 客户端，以及记忆摘要、记忆 gate、能力判断和语义路由请求，保留原消息历史、提示词、流式响应、工具定义与工具执行链。主对话、子 Agent 和经这些入口的辅助推理使用所选模型。它不把语音识别、语音合成、搜索引擎或米家服务改造成第三方服务，也不宣称覆盖 APK 内所有独立云服务。

## 安装与配置

1. 安装 APK，在 LSPosed 启用 **MiAIPlus**，作用域只选超级小爱 `com.miui.voiceassist`。
2. 打开 MiAIPlus，开启“启用本机控屏工具”，规划模型保持“小爱当前模型”。**可以留空全部 API 字段并保存。**
3. 在系统无障碍设置启用 MiAIPlus。小米系统如需自动打开其他应用，在应用信息 → 其他权限中允许“后台弹出界面”。
4. 重启小爱，模块状态应包含 `ready`、`native-planner`、`global-model`。这是 Hook 安装状态，不等于当前开启了全局外部模型。
5. 在小爱中提出明确的应用内任务。普通聊天、搜索、手机设置和已有专用工具继续使用原链路。

可选外部服务：填写实际 API Base URL（含 `/v1`）、Key 与模型名；可从服务获取模型列表。
要替换小爱主模型，开启“小爱全局使用外部模型”，保存并重启小爱。要使用独立的控屏模型循环，另选“独立外部视觉模型循环”。两者是独立选项。
`computer` 协议要求模型支持图片与 function calling；`autoglm` 使用 Open-AutoGLM `do(...)` / `finish(...)` 协议。切换主模型时仍应选支持小爱工具调用的模型。

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

需要 JDK 17+、Android SDK platform 35 / build-tools 35.0.0。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:lintRelease
python3 scripts/package_release.py
```

`dist/` 提供未签名 release APK、开发签名预览 APK、源码压缩包及 SHA256 校验。release 变体不含测试页面和调试广播接收器。开发签名仅便于测试；正式分发请使用自己的长期私有签名，升级需保持同一签名。
源码包按文件白名单生成，排除原始 APK、反编译文件、实机日志、个人配置、工具链缓存和签名密钥。
详见 [发布说明](docs/releasing.md)。

## 测试

```sh
python3 scripts/mock_model.py
adb reverse tcp:18765 tcp:18765
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 scripts/device_test.py
# 需要在 LSPosed 启用模块；只在已授权测试设备运行进程内诊断
python3 scripts/device_test.py --hook
adb reverse --remove tcp:18765
```

设备测试临时修改模块自己的配置，结束时恢复；重连模块无障碍服务，保留其他服务。测试使用独立测试页面，不操作个人应用内容。`--hook` 会重启小爱，并在 debug 模块内进行真实 Hook/原生模型客户端诊断。
真实凭据和官方模型的完整自然语言任务验证另见 [验证状态](IMPLEMENTATION-STATUS.md)。
开源参考及许可见 [架构](docs/architecture.md) 与 `third_party/open-autoglm/`。
