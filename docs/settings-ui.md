# 模块配置界面

此界面在独立的 `ui/miuix-settings` 分支开发。已同步远程 main `095156c`，不包含 main 工作区尚未提交的后台屏实现修改。

## 页面与保存方式

| 页面 | 内容 | 保存方式 |
| --- | --- | --- |
| MiAIPlus | 模块开关、控屏规划、外部模型对话、设置入口 | 开关与选项即时写入原有配置 |
| 模型服务 | 配置切换、连接信息、模型列表、API 协议 | 显式保存；离开或切换时处理未保存修改 |
| 控屏设置 | 动作协议、最大步数、后台屏开关、权限 | 即时保存；步数验证 1–80 |
| 运行状态 | 小爱报告、框架与热重载、无障碍连接、后台屏检查 | 显示服务返回值；后台屏仅按需查询 |
| 直接测试 | 外部模型控屏与纯对话 | 使用已保存配置；不替代小爱主模型规划 |

使用 Miuix 0.9.4 的 `MiuixTheme`、`Scaffold`、可折叠大标题、`Card`、`SwitchPreference`、`ArrowPreference`、`OverlayDropdownPreference`、`TextField`、`TextButton` 与 `OverlayDialog`。颜色与文字样式沿用组件默认值；跟随系统深浅色，内容最大宽度为 680 dp，支持滚动、键盘避让与系统返回。

配置草稿放在 ViewModel 中以保留旋转时的输入，不把密钥写入 SavedState。密钥默认遮挡，切换配置后恢复遮挡；继续使用 `FLAG_SECURE` 与应用私有配置存储。服务地址、密钥和模型默认仍为空。

## 集成边界

仅新增界面状态与交互，沿用 `Config`、`EndpointProfiles`、`AccessibilityGuard`、`HotReload` 和 `BridgeProvider`。未修改 Hook、模型协议、任务执行器或后台屏实现。开启外部模型前验证已保存配置；全局对话替换保留数据发送说明。后台屏开关只是现有配置入口，不表示新实现或设备兼容性已验证。

与 main 工作区的后台屏新代码在隔离副本中合并检查，唯一的源码冲突是旧 `MainActivity.java` 的修改与新 Kotlin 页面替换。其“检查后台屏可用性”已迁到运行状态页，调用 `background_display_status`，仅点击时在工作线程查询；定时刷新不启动 root 进程，也不创建屏幕。结果区分未授权、锁屏、检查失败与前置条件满足，不把 root 授权当成完整能力验证。该接口的后台实现仍由原工作区单独提交。

AGP 9 内置 Kotlin 使用 2.4.20，与 Compose 编译插件保持一致，以读取 Miuix 0.9.4 的 Kotlin 元数据。API 102 依赖仍为 `compileOnly`。

## 本地检查

使用仓库规定的 Gradle 命令。`SettingsUiTest` 在 Robolectric Android 35 上验证默认设置、页面导航、步数边界、未保存配置、未连接状态及后台屏检查的按需调用与结果映射。后台检查使用 IPC fixture，不启动真实 root。渲染截图输出到 `app/build/outputs/ui-screenshots/`，不包含设备或个人服务数据。

本次 UI 分支的构建与 89 项测试通过。Lint 的 3 个阻塞均来自基线中未修改的 `helper/DisplayHelper.java`：144、238 行 `WrongConstant`，229 行 `BlockedPrivateApi`。没有为这些问题添加屏蔽规则，也没有修改后台屏代码。

与后台屏新代码合并的隔离副本通过 Debug、Release、AndroidTest 构建、88 项测试及两套 Lint（0 errors）。设备测试 runner 的旧 Frame 构造已改用现有 `Frame.physical` 工厂，避免截图元数据扩展后编译失败。

配置界面已完成实机性能对比；日常试用使用优化的 Preview 构建。详见 [性能记录](settings-performance.md)。本次未重启小爱或请求真实模型。

状态仅在进入前台时刷新，运行状态页另每 2 秒更新；模型选择列表按可见项组合。日常实机使用 `:app:assemblePreview`，Debug 用于调试与原设备测试脚本。
