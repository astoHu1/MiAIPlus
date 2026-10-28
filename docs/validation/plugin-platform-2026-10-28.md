# Plugin 平台第一阶段验证（2026-10-28）

本记录对应 `Pluginify` 独立分支上的未提交 Plugin 平台改动，不是正式发布验收。任务开始时基线为 `d185de5`；共享工作区中的 Phone Use 观察与执行反馈工作随后单独提交为 `2abd15c`，本轮最终检查时 HEAD 为该提交。测试包含当时工作区的完整源码，不能把全部测试数归属于 Plugin 新增功能。

后续用户授权独立包安装与手机测试，结果另见 [独立包实机记录](plugin-platform-device-2026-10-28.md)。下文「未操作设备」等描述只对应本记录的首轮阶段；共享分支 HEAD 后续变化也以该后续记录为准。

本轮保留其他任务的源码、测试、审计与文档改动，没有提交、推送、安装、发布或执行手机操作。分支选择沿用任务开始时已有的 `Pluginify`，没有切换或重置共享工作区。

## 实现范围

- `PluginSettings`、`PluginRuntime` 与 `NativePluginTools` 统一内置前台／后台 Phone Use 和用户配置的 MCP 工具目录、宿主注册、定义注入及清理。
- 两个 Phone Use 工具名保持不变；插件启停也覆盖旧兼容入口，继续使用原有会话、权限、确认、观察、后台故障停止与原对话返回机制。
- 首页增加插件页，可管理内置插件、保存 MCP 连接、显式发现工具、检查目录、启停及停止模块当前 MCP 调用。新增 MCP 默认关闭，保存不连接。
- 首个远程后端固定为 MCP `2025-11-25` Streamable HTTP 的 tools 子集：初始化、分页发现、JSON／有界 SSE、文本与结构化结果；未实现通用 Harness 或任意代码加载。
- 远程任务独立于控屏任务，包含 UID／请求所有权、目录与配置重验、并发及大小预算、超时、取消、迟到结果拒绝和重载忙状态检查。

架构、使用步骤与完整边界见 [Plugin 平台](../plugin-platform.md)，协议研究见 [MCP 调研](../plugin-platform-mcp-research.md)。

## 最终本地检查

2026-10-28 使用本地 Temurin JDK 25、仓库配置的 Android SDK 与 Gradle 缓存执行。`JAVA_HOME`、`ANDROID_HOME` 和 `GRADLE_USER_HOME` 仅在本次 shell 中指定，以下省略机器专属绝对路径：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:lintRelease :app:assembleRelease :app:assembleDebugAndroidTest \
  --offline --console=plain
```

最终结果为 `BUILD SUCCESSFUL in 1m 18s`，140 个 actionable tasks。

| 检查 | 最终结果 | 证据 |
| --- | --- | --- |
| JVM／Robolectric | 68 个 suite、499 项测试；失败、错误、跳过均为 0 | `app/build/test-results/testDebugUnitTest/TEST-*.xml` |
| 本轮 Plugin 相关测试 | 9 个 suite、104 项，包含于 499 项总数内 | 下表 |
| Debug Lint | 0 errors、36 warnings、1 hint | `app/build/reports/lint-results-debug.xml` |
| Release Lint | 0 errors、26 warnings、1 hint | `app/build/reports/lint-results-release.xml` |
| Debug APK | 构建成功 | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | 构建成功，unsigned；不是签名分发包 | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Debug AndroidTest APK | 构建任务成功；未据此运行设备 instrumentation | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |

未以省略 Lint 的构建冒充完整检查；Lint 仍有警告，不把零错误表述为零问题。未运行 CI、Preview 构建、签名或 release 打包脚本。本地存在的其他 Preview 产物不作为本轮 Plugin 验证依据。

### Plugin 测试分布

| Suite | 项数 | 证据类型与主要范围 |
| --- | ---: | --- |
| `PluginSettingsTest` | 19 | 私有配置、默认值、原子变更、目录预算、身份与 manifest 失效 |
| `PluginRuntimeTest` | 19 | 假客户端下的发现、调用、所有权、配置重验、取消、迟到结果与 worker 清理 |
| `BridgeProviderPluginTest` | 7 | Robolectric Provider／IPC 入口与 UID、远程任务隔离 |
| `PhoneUsePluginGateTest` | 5 | 内置插件门禁、兼容入口与续调的初始后端归属 |
| `McpClientTest` | 27 | 注入的假 `HttpURLConnection`：握手、分页、响应、协议错误、边界与取消 |
| `McpClientWireTest` | 3 | 桌面 JVM 的真实 loopback socket／`HttpURLConnection`，临时端口；JSON／SSE、请求头、UTF-8 与副作用调用不重放 |
| `NativePluginToolsTest` | 16 | 自编最小宿主契约 fixture：注册表归属、同名冲突、definition 清理、代理调用与重载 |
| `PluginSettingsStateTest` | 6 | 设置页状态、草稿、显式发现、启停及失败保留 |
| `PluginSettingsUiTest` | 2 | Robolectric Compose 插件入口与列表／编辑界面 |

宿主 fixture 是测试自行编写的最小契约，不是反编译宿主代码，也不证明真实小爱 ABI 或 Hook 已通过。loopback 测试没有访问第三方服务器或手机，只证明桌面传输行为；其结果不能代替 Android 网络栈和生产 MCP 服务验收。

### UI 检查

已查看本地生成的以下图片，检查插件页浅色、深色及 MCP 编辑布局：

- `app/build/outputs/ui-screenshots/settings-plugins-light.png`
- `app/build/outputs/ui-screenshots/settings-plugins-dark.png`
- `app/build/outputs/ui-screenshots/settings-plugins-mcp.png`

这是 Robolectric 渲染检查，不是设备截图、真实键盘／系统栏或真实宿主界面验收。

## 过程中发现并修正的问题

1. 早期 66 项定向测试有 3 项失败：错误 Bundle 缺少 `status=error`、删除配置后迟到结果／状态处理、UI fixture 使用不允许的公网明文 HTTP 地址。分别修复错误状态、配置失效判断，并将 fixture 改为 HTTPS；最终完整检查通过。
2. 首页新增插件入口使原动画测试的目标移出可见范围。测试冻结 Compose 时钟后执行 `performScrollTo()`，触发 Robolectric idle／ShadowTrace 循环并耗尽内存。仅中断本轮自己的前台 Gradle 会话，将两个测试的滚动移至冻结时钟前；定向动画回归及最终完整套件通过。没有停止其他任务的进程。
3. 一轮完整运行中 499 项测试已通过，但 Lint 发现 `Stream.toList()` 要求 API 34。改为普通 `ArrayList` 循环，最终 Debug／Release Lint 通过，保留 Android 13 支持。
4. 静态复核移除了 Android 未公开的桌面 JDK `Authenticator.getDefault()` 调用。保留公共 `CookieHandler` 检查，并在协议文档明确记录全局认证处理器的隔离限制，没有将其描述为独立进程沙箱。

这些是本轮失败尝试及修复记录；最终通过结果不能抹去早期失败，也不能扩展为尚未执行的设备结论。

## 尚未验证与发布限制

- 未在两版受支持小爱上安装或执行本轮 Hook；未运行真实模型、真实第三方 MCP 服务或手机端到端任务。历史 Phone Use 设备证据不能自动转作插件化后的证据。
- MCP 仅为显式固定的 `2025-11-25` tools 子集；不支持 stdio、OAuth、Resources／Prompts／Sampling／Elicitation／Tasks／Apps、多媒体资源解引用、自动目录推送或任意本地代码插件。不同 Harness 的能力仍需分别适配。
- 原生 `Tool.onCancel` 尚无可靠的具体调用归属，MCP 代理为 `isStoppable=false`。小爱原生停止按钮不保证取消 MCP；当前模块本地中止入口是插件页「停止 MCP 调用」，另有线程中断、超时和自身配置失效。断开不能撤销远端已发生的副作用。
- 对来源未知的宿主缓存 definition 副本不按名称删除，避免误删其他注册表对象；旧定义可能暂时显示，旧代理执行时仍重验并拒绝失效调用。
- Phone Use 的本地确认不等于对远程工具的统一业务副作用审计；启用 MCP 意味着允许模型选择该服务已发现的工具，服务权限及账户范围仍由其认证配置约束。
- 发布前仍需真实宿主／服务验收，按 [发布流程](../releasing.md) 审核具体源码与产物。本轮没有创建正式分发包。
