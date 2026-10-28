# Pluginify 独立包与实机验证（2026-10-28）

用户明确授权更换包名、安装到已连接手机并进行实机测试。本记录是 [第一阶段本地验证](plugin-platform-2026-10-28.md) 的后续，不将本轮设备结果追溯为此前已经通过。

## 安装身份与工作区

- 分支：`Pluginify`；收尾 HEAD 为 `d185de5`。工作区同时保留并行 Phone Use 观察／执行反馈改动，Plugin 改动尚未提交。本轮未执行分支切换、reset、提交或推送。
- 安装包：`io.github.miaiplus.pluginify`，桌面名称「MiAIPlus Pluginify」；主线 `io.github.miaiplus` 保留安装，未覆盖、卸载或迁移主线配置。
- 源码 namespace、Xposed 入口与 helper Java 类名保持 `io.github.miaiplus`。组件使用完整类名，例如 `io.github.miaiplus.pluginify/io.github.miaiplus.MainActivity`。
- Bridge／FileProvider authority、无障碍检查的包名参数、返回标记、宿主话题偏好、通知与广播按 applicationId 隔离；独立包不清理主线旧 root guard。
- 独立包不安装硬编码主线身份的旧 `HookSmokeProbe`，只使用新的 DUMP 权限保护、独立 action 的 `PluginPlatformProbe`。

先前本地验证记录中的 `2abd15c` 是当时的共享工作区 HEAD；不能据此声称当前全部工作区改动已经提交。实际验收包以下面的摘要为准。

## 本地构建与回归

使用本机 JDK 25 和仓库配置的 Android SDK／Gradle 缓存，环境路径仅在 shell 指定，未写入源码。分阶段执行了以下任务：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease :app:assemblePreview \
  :app:assembleDebugAndroidTest --offline --console=plain
```

上面是本轮已执行任务的集合，不冒充单次命令输出：修复与定向检查后，完整 JVM 套件和产物／Lint 分轮验证，日志保留在忽略目录 `work/pluginify-device-20261028/`。

| 项目 | 结果 |
| --- | --- |
| 完整 JVM／Robolectric | 70 个 suite，506 项；0 failures／errors／skipped |
| 新增隔离回归 | 原 499 项基础上新增 7 项，覆盖安装身份、manifest authority、组件 namespace、root guard 与通知身份 |
| 假 Android shell 回归 | `python3 scripts/test_accessibility_guard.py`，7 项通过；不操作手机 |
| Debug Lint | 0 errors，37 warnings、1 hint |
| Release Lint | 0 errors，26 warnings、1 hint |
| APK | Debug／Release unsigned／优化且开发签名的 Preview／Debug AndroidTest 均构建成功 |
| 其他 | Python 脚本语法、shell 语法与 `git diff --check` 通过 |

本轮先修正了新 debug 探针使用的 Android 不提供的 `JSONObject.similar` 与 API 34 `Stream.toList`，再编译。一次 506 项完整运行中，虚拟屏忙状态 UI 测试的异步提示尚未出现即断言，导致 1 项失败；增加基于实际提示的 5 秒有界等待，保留禁止发起 probe 的断言，定向及完整 506 项随后通过。没有用跳过测试或 Lint 获取通过结果。

## 实机方法与结果

设备为 Xiaomi 15 Ultra／Android API 37，当前小爱 `8.3.30.4420`（`508003030`），Android owner 用户 0。没有调用付费模型或真实第三方 MCP；MCP fixture 仅在电脑 loopback 监听，经本轮独占的 ADB reverse 连接，只提供合成 echo、数字加法和有界延迟返回。

测试入口：`scripts/plugin_platform_device_test.py` → `PluginPlatformRunner`；宿主阶段通过 `${applicationId}.PLUGIN_PROBE` 触发注入小爱进程中的 debug 探针。脚本拒绝主线包和非 owner 用户，fixture 偏好在 finally 恢复，mock／reverse 只清理本轮创建的资源。

```sh
python3 scripts/plugin_platform_device_test.py --package io.github.miaiplus.pluginify
python3 scripts/plugin_platform_device_test.py --package io.github.miaiplus.pluginify --host
python3 scripts/plugin_platform_device_test.py --package io.github.miaiplus.pluginify --host --phone foreground
python3 scripts/plugin_platform_device_test.py --package io.github.miaiplus.pluginify --host --phone background
```

多设备时另加 `--serial` 指向授权设备；本文不保存设备连接地址或私有配置。

| 验证阶段 | 实际结果 | 证据边界 |
| --- | --- | --- |
| 并装／Provider | 新旧包同时安装，独立 Bridge／FileProvider；shell 直接获取 plugin catalog 被 `SecurityException` 拒绝 | 未放宽生产 IPC UID 校验 |
| Android MCP | 真实 Android HTTP 握手、分页发现、结构化加法、Provider echo 通过 | 自有合成服务，不代表任意生产服务兼容 |
| 生命周期 | 启停、过期 revision 拒绝、Phone Use 设置不影响 MCP、取消及旧任务不影响新调用通过；MCP 未创建／改变 screen task | 取消只能停止本地等待，不能撤销远端副作用 |
| 实际宿主 | 当前小爱真实 `ToolRegistry` 中前台／后台／MCP 三类代理和唯一 definition、schema、说明及当前模块归属一致；通过原生 MCP proxy 执行 echo 成功 | 未让模型自主选择工具，不是主模型任务质量测试 |
| 前台 Phone Use | 自有 fixture 上获取附图、同工具／同会话 wait 续调、finish 及收尾通过 | 仅观察与等待，不是点击／输入或复杂应用任务验收 |
| 后台 Phone Use | 临时授予新包 root 后，空虚拟屏附图、同工具／同会话 wait、finish 通过；未 launch 应用、未回退主屏 | 不证明第三方应用、输入隔离、交接或长任务全部通过 |
| 设置 UI | 手机实际打开首页、插件页和 MCP 编辑窗口；最终优化 Preview 能正常启动 | 本轮手机为浅色，深色仍沿用本地渲染证据 |

### 失败与恢复

1. 首次前台探针明确返回无障碍未连接，没有注入动作。脚本原先在 instrumentation 启动前设置服务，target 进程重启后未重新绑定；改为 runner 发出带 nonce 的连接握手后，仅重绑本包服务并等待新健康实例，重建 AndroidTest 后前台通过。其他服务始终保留。
2. 首次后台探针在 root 未授权时以 I/O 错误停止，未切回主屏、未冒充成功。通过 KernelSU 正常管理界面确认新包「超级用户」关闭，临时开启后重跑，后台观察／续调／结束通过；结束后已在同一管理界面关闭。未修改 SELinux、系统目录权限或通过替代执行器绕过 root 检查。
3. LSPosed 通过正常管理界面临时停用主线并启用 Pluginify 的推荐小爱作用域，重启小爱进行测试；没有写框架数据库，也没有同时启用两套相同 Hook。

## 最终安装与清理

最终保留安装的是优化后的 Preview，不是调试探针版本。设备安装 APK 与本地 `app/build/outputs/apk/preview/app-preview.apk` 的 SHA-256 相同：

```text
5bb191d4e586d5860e0dd736b86b80034456de3239b2f842741e83445eadba9d
```

用于本轮设备探针的 Debug APK SHA-256 为 `a8ef4157234ac6cc47ec59b2272845485ef6beaca9e732ac7f5175626e366378`；最终 Debug AndroidTest 为 `b8d633f4875bde1cd758a65f8b4f7ed46078e8c191334689bf10a425515f4b9c`。宿主与控屏探针在 Debug 上执行；优化 Preview 的构建、安装、摘要和界面启动验证不能写成重复执行了 Debug 探针。

收尾恢复主线模块启用和原小爱作用域，Pluginify 模块停用，重启小爱后主线重新报告 ready。新包临时 root 已撤销；无障碍服务列表、ADB reverse 与开始时逐字相同。主线安装路径不变，逐项比较其偏好，仅 `hook_at` 运行时间戳改变。插件测试参数、认证与目录均已恢复，未保留测试 MCP 连接。

已移除本轮新装的 `io.github.miaiplus.pluginify.test`，保留原来的主线测试包与 fixture。自有 mock 已退出，无活动 instrumentation、DisplayHelper 或本模块虚拟显示；两个包均无活动／准备中的控屏任务，主线无障碍仍健康。Pluginify 的历史 ready 报告只表示最后一次加载时间，不代表收尾仍启用。

之后若要手动试用新包，应在 LSPosed 停用主线、启用 Pluginify 后重启小爱，并按需要单独启用新包无障碍／root；不要同时启用两套模块。

## 未覆盖范围

未复测小爱 8.2.65、Android 13 或其他 ROM；未使用真实模型或第三方生产 MCP；未重做复杂前后台应用任务、实际点击输入、原生停止按钮取消 MCP、OAuth、新协议或任意 Harness 验收。MCP 代理的 `isStoppable=false` 与本地停止方式等边界仍见 [平台说明](../plugin-platform.md)。本轮没有签名发布、分发、提交或推送。
