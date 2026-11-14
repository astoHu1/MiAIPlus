# 系统提示词与人设：实现与验证记录（2026-11-14）

用户可编辑小爱的系统提示词并管理多套人设。本轮新增设置页、模块私有配置、宿主 IPC 与三处注入点；本文记录实际跑过的检查、设备证据和已知边界。

## 行为

- **生效方式**：`off`（默认）/ `append`（在原宿主提示词后追加）/ `replace`（用用户文本替换宿主提示词）。
- **人设方案**：多套人设，名称唯一且不超过 20 字，正文不超过 4000 字；至少保留一套。保存或切换时，当前人设正文写入扁平快照 `customPromptText`，宿主只读扁平键。
- **注入点**：`as.a` 的 `build` / `buildSessionHead` / `buildTurnInject`。扩展能力策略始终由**原始宿主文本**计算后追加，因此替换模式不会丢掉工具声明。追加模式对已包含的文本不重复追加。
- **文字对话走智能体**（默认关）：官方模型下的普通文字提交只有开启后才会强制走智能体通道，否则提示词无处可注入。C()/D() 门禁未改动。
- **语音对话也应用**（默认关）：普通语音在拿到原生 chat sender 时才转交，拿不到时保持原生行为并记 `sender_unavailable`，绝不取消用户的语音轮次。
- **独立对话也应用**（默认关）：只作用于 `AgentEngine` 的纯对话模式，前置而非替换其固定系统提示。
- 不注入记忆摘要、意图分类、`AgentEngine.audit()` 与控屏循环。

## 隐私边界

提示词正文与人设名称只存在于模块私有偏好和宿主 IPC 中。诊断快照只记录 `customPromptMode` 枚举、`customPromptChars` 字符数及三个开关布尔值；人设列表与正文不进入 `DiagnosticEnvironment`、`PhoneUseLog`、异常消息或 Toast。`prompt_settings` 是与 `conversation_settings` 分开的 IPC 方法，后者的键集仍由测试逐字断言。

## 本轮实际执行的检查

| 检查 | 结果 |
| --- | --- |
| `./gradlew :app:testDebugUnitTest` | 通过，638 项（含新增 `PersonaProfilesTest`、`PromptInjectionTest`、`BridgeProviderPromptSettingsTest`、`PromptSettingsUiTest`） |
| `./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac` 及测试源编译 | 通过 |
| `./gradlew :app:lintDebug :app:assembleDebug` | 通过 |
| `-PsettingsUiTests=true :app:assembleDebug :app:assembleDebugAndroidTest` | 通过 |
| 设备 `-e prompt true`（Xiaomi 25019PNF3C，API 37，小爱 8.3.30.4420） | **PASS**：默认态、模式菜单、人设新建/切换/删除、扁平快照、`prompt_settings` 回读、三个开关、脏草稿离开确认、仅元数据诊断；五张合成截图；未发起模型请求 |
| 设备 `-e menus true` 回归 | **PASS**，未回归既有菜单用例 |

设备用例结束时恢复原配置并校验一致。

## 尚未验证

- 没有用小爱真实模型或 mock 模型驱动一次**带自定义人设**的完整对话，因此「提示词确实到达模型」只有 `prompt_settings` 回读与 `prompt_policy` 事件代码路径作依据，没有端到端回复证据。
- 语音转交路径未实机触发（需真实录音与悬浮窗），只由单元测试固定决策表。
- 未在旧版小爱 8.2.65.4714 上复验。
- 深色模式与 1.5 倍字号下的人员页仅由 Robolectric 渲染覆盖，未逐张实机核对。

## 设备测试环境陷阱（务必先读）

本轮花了大量时间排查「测试跑不起来」，根因**不在被测代码**。以后在同一台 MIUI／HyperOS 设备上跑 `SettingsUiRunner` 前先确认以下两条。

### 1. 后台弹出界面权限

`SettingsUiRunner` 用 `Instrumentation.startActivitySync` 拉起 `MainActivity`。MIUI 把来自 instrumentation 进程的启动判为后台启动并**静默丢弃**：

```
E ActivityTaskManager: Abort background activity starts from 10446
```

此时 `startActivitySync` 永不返回，instrumentation 既不失败也不输出，`am instrument` 只能被外部超时杀掉。表现是「构建成功、安装成功、`am instrument` 挂住、无任何日志、无截图」。

**修复**（授予 MIUI 的后台弹出界面 op）：

```sh
adb shell cmd appops set io.github.miaiplus 10021 allow
adb shell cmd appops get io.github.miaiplus 10021   # 期望 MIUIOP(10021): allow
```

注意这**不是** AOSP 的 `SYSTEM_ALERT_WINDOW`（`cmd appops set ... SYSTEM_ALERT_WINDOW allow` 会显示 `default` 且无效），也不是 `RUN_IN_BACKGROUND`，更不能靠 `settings put global background_activity_starts 1` 解决。`10021` 是 MIUI 私有 op，`cmd appops` 只认数字。

### 2. 测试包必须用 SettingsUiRunner 构建

默认 `testBuildType` 只把 `src/uiTest/java` 当源目录，清单里仍是标准 `AndroidTestRunner`，因此 `pm list instrumentation` 里找不到 `SettingsUiRunner`（报 `INSTRUMENTATION_FAILED`）。必须：

```sh
./gradlew -PsettingsUiTests=true :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm list instrumentation | grep miaiplus   # 应出现 SettingsUiRunner
```

`-PuiTestBuildType=preview` 同样会切换 runner，但那用的是优化包。

### 3. 其他排查陷阱

- **`uiautomator dump` 报 `could not get idle state`**：与本功能无关，是小米系统 UI 的持续动效导致无障碍等不到空闲。不要据此判断界面卡死；改用 `adb exec-out screencap -p` 截图核对。
- **残留 instrumentation**：被 kill 的运行可能留下 `mFinished=false` 的活动状态，之后的运行会直接挂住。先 `adb shell am force-stop io.github.miaiplus` 再跑。
- **不要用 `adb shell input tap` 手点验证界面流程**：坐标随滚动、输入法和深色模式漂移，容易得出错误结论（本轮曾因此误判「保存没有写盘」）。界面流程一律走 `SettingsUiRunner`；只有核对单帧外观时才截图。
- **`ACTION_SET_TEXT` 不产生输入法事务**，`hideKeyboard()` 找不到要隐藏的键盘，标签栏可能仍在编辑器下方。编辑后先用 `clearEditorFocus()`，再用 BACK 触发离开守卫，比点标签栏稳定。

## 复现命令

```sh
./gradlew -PsettingsUiTests=true :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell cmd appops set io.github.miaiplus 10021 allow
adb shell am force-stop io.github.miaiplus
adb shell am instrument --user 0 -w -e prompt true \
  io.github.miaiplus.test/io.github.miaiplus.SettingsUiRunner
```

截图落在应用私有目录 `cache/settings-ui-test/`，取回：

```sh
adb shell "run-as io.github.miaiplus ls cache/settings-ui-test/"
```
