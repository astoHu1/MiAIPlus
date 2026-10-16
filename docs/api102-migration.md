# API 102 与控屏交互

MiAIPlus 0.3.0 使用 [libxposed/api](https://github.com/libxposed/api) 102.0.0 和 [libxposed/service](https://github.com/libxposed/service) 102.0.0。发布包通过 `META-INF/xposed/module.prop` 声明 `minApiVersion=102`、`targetApiVersion=102`、`autoHotReload=true`，入口是 `io.github.miaiplus.XiaoAiHook`，作用域只有 `com.miui.voiceassist`。旧的 `xposed_init` 和 `de.robv.android.xposed` 元数据不再使用。

## 首次升级

安装 0.3.0 后，在 LSPosed 中确认超级小爱仍在模块作用域，首次从旧入口迁移需要重启一次超级小爱。之后安装同签名的新 APK，`autoHotReload=true` 允许框架自动加载新代；设置页的“安装更新后热重载模块”也可查找 API 102 的运行目标并请求 `XposedService.hotReloadModule`。框架返回目标已是当前版本时不会重复操作。

热重载前模块会撤销旧 Hook、移除小爱 ToolRegistry 中的代理、关闭自有网络客户端、清理推理 UI、广播接收器和 Activity 生命周期回调。正在控屏、模型流、诊断广播或其他外部工作时会拒绝热重载；请求失败应等待任务结束后重试，若框架报告不支持则重启小爱。热重载回调不会自动重放旧的 package-ready 回调，模块会显式重新安装精确版本 Hook。

## HyperOS 控屏状态

控屏开始前，模块在小爱进程的 UID 中发布带 `miui.focus.param` / `miui.focus.pics` 的原生焦点通知，标题为“正在操作手机”。展开卡片的 `param_v2.actions` 引用 `miui.focus.actions` 中的不可变广播 PendingIntent，显示“停止操作”；标准通知保留同一动作。不再显示常驻悬浮停止键，敏感操作确认窗保留。焦点岛右侧以 action.intent 显示当前简短操作意图，每 700 毫秒检查变化；等待下一步保留最近意图，缺省时按动作提供说明。更新设置 onlyAlertOnce 并关闭 enableFloat/islandFirstFloat，不反复弹出。文案经 JSONObject 写入，不拼接模型文本到 JSON；不显示原始推理或输入字段。通知权限或渠道被关闭时，任务会在真正触摸前停止并给出原因。

`screen_task` 标识整个控屏任务，延续到所有 CONTINUE 之后。`screen_state` 仅在执行线程已退出且 native 会话已关闭时确认 `completed`；单次工具返回不构成完成。观察线程绑定该任务 ID，旧观察结果不能恢复新任务的页面。控屏期间抑制宿主 `UiManager.start` 重新显示页面；Activity 因工具结果恢复时移回后台，直到任务终止才解除。

前一任务结束但终态尚未轮询到时，新任务会立即替换观察器、任务 ID 和停止令牌，沿用仍隐藏的原始返回入口。已排队的旧任务回调因 ID 不匹配失效；停止旧观察器不在主线程等待 IPC 退出，避免阻塞新任务的界面握手。

模块在主线程保存开始控屏时正在恢复的 Activity、任务栈和入口 Intent。全屏页面通过任务栈移到后台；快捷键在该版本实际使用系统悬浮窗口，没有前台 FloatActivity，模块按原生窗口类型识别它，隐藏窗口并移除触摸/焦点能力，让被操作应用接收输入。不会调用会停止 Agent 或清空当前任务的原生 pushBackgroundOperate。任务完成后：

- 全屏小爱页面移回开始控屏的同一任务；
- 快捷键/浮窗入口自动展开“手机操作已结束”通知岛（30 秒），点击打开小爱查看结果；不会在完成瞬间抢占整个屏幕；
- `PAUSE` 结果停留在通知岛并提示用户接管；
- 锁屏时不强行拉起页面，控屏本身也继续拒绝锁屏输入。

这些行为依赖目标版本小爱 8.2.65.4714（versionCode 508002065）和 HyperOS 的原生焦点模板。其他小爱版本会被精确版本门禁拒绝。

## 构建和验证

使用 JDK 17+、Android SDK platform 37、build-tools 37.0.0、Gradle 9.6.0 和 AGP 9.4.1；targetSdk 仍为 35。API 与 service 依赖分别为 `compileOnly` 和 `implementation`；API 运行时由 LSPosed 提供，service/interface 随模块 APK 打包。依赖许可与版本化来源记录在 `third_party/libxposed/`。

已验证：API 102 源码接口适配、43 项 JUnit、debug/release Lint、debug/release/AndroidTest 构建，以及 Xiaomi 15 Ultra / Android 17 / LSPosed 2.2.1 上的真实 Hook、Chat/Responses 传输和控屏探针。专项探针检查操作提示、同一个全屏 Activity 恢复、快捷键系统浮窗被隐藏且不拦截触摸、完成通知岛及其点击入口。已保存该设备实际通知岛截图核对展开样式；其他 ROM 的最终样式仍由其 SystemUI 决定。实机安装更新前后小爱 PID 不变，API 102 服务重载返回 SUCCEEDED；重载后继续验证 Hook。完整自然语言任务成功率不由这些通路测试保证。

## 上游依据

迁移按 Maven Central 的 **102.0.0 已发布源码**逐项核对，避免把开发分支 API 当作发布接口：

- [API 102.0.0 sources](https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0-sources.jar)：`XposedModuleInterface` 的 `onPackageReady`、`onHotReloading`、`onHotReloaded`，以及 `XposedInterface` 的 HookBuilder / Chain / Invoker。
- [service 102.0.0 sources](https://repo.maven.apache.org/maven2/io/github/libxposed/service/102.0.0/service-102.0.0-sources.jar)：`XposedServiceHelper`、`getRunningTargets`、`hotReloadModule` 与 `HotReloadResult`。
- 上游要求热重载前移除模块拥有的线程/回调/宿主引用；新代不会重放初次 package 回调，必须显式安装 Hook；saved state 不能保存旧模块类加载器对象。实现据此拒绝忙碌重载，并使用空 saved state。

可复现专项（已解锁、获授权且启用模块的测试机）：

```sh
python3 scripts/device_test.py --hook --focus
python3 scripts/device_test.py --hook --focus --popup
adb shell am instrument --user 0 -w -e reload true io.github.miaiplus.test/io.github.miaiplus.DeviceTestRunner
python3 scripts/device_test.py --hook --focus --no-restart
```

前两项会重启小爱并临时使用观察探针，结束恢复配置。`--no-restart` 保留现有小爱进程，用于验证热重载后同一 PID 中的新 Hook。快捷键测试通过 VoiceService 的原生唤醒动作打开实际系统悬浮窗口，而不是直接启动会自动退出的 FloatActivity。重载探针只检查框架及请求重载，不读取模型配置。
