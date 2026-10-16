# 模块配置界面

此界面在独立的 `ui/miuix-settings` 分支开发。基于提交 `4be55fc`，不包含 main 工作区尚未提交的后台屏实现修改。

## 页面与保存方式

| 页面 | 内容 | 保存方式 |
| --- | --- | --- |
| MiAIPlus | 模块开关、控屏规划、外部模型对话、设置入口 | 开关与选项即时写入原有配置 |
| 模型服务 | 配置切换、连接信息、模型列表、API 协议 | 显式保存；离开或切换时处理未保存修改 |
| 控屏设置 | 动作协议、最大步数、后台屏开关、权限 | 即时保存；步数验证 1–80 |
| 运行状态 | 小爱报告、框架与热重载、无障碍连接 | 显示现有服务返回值 |
| 直接测试 | 外部模型控屏与纯对话 | 使用已保存配置；不替代小爱主模型规划 |

使用 Miuix 0.9.4 的 `MiuixTheme`、`Scaffold`、可折叠大标题、`Card`、`SwitchPreference`、`ArrowPreference`、`OverlayDropdownPreference`、`TextField`、`TextButton` 与 `OverlayDialog`。颜色与文字样式沿用组件默认值；跟随系统深浅色，内容最大宽度为 680 dp，支持滚动、键盘避让与系统返回。

配置草稿放在 ViewModel 中以保留旋转时的输入，不把密钥写入 SavedState。密钥默认遮挡，切换配置后恢复遮挡；继续使用 `FLAG_SECURE` 与应用私有配置存储。服务地址、密钥和模型默认仍为空。

## 集成边界

仅新增界面状态与交互，沿用 `Config`、`EndpointProfiles`、`AccessibilityGuard`、`HotReload` 和 `BridgeProvider`。未修改 Hook、模型协议、任务执行器或后台屏实现。开启外部模型前验证已保存配置；全局对话替换保留数据发送说明。后台屏开关只是现有配置入口，不表示新实现或设备兼容性已验证。

AGP 9 内置 Kotlin 使用 2.4.20，与 Compose 编译插件保持一致，以读取 Miuix 0.9.4 的 Kotlin 元数据。API 102 依赖仍为 `compileOnly`。

## 本地检查

使用仓库规定的 Gradle 命令。`SettingsUiTest` 在 Robolectric Android 35 上验证默认设置、页面导航、步数边界、未保存配置与未连接状态。渲染截图输出到 `app/build/outputs/ui-screenshots/`，不包含设备或个人服务数据。

本次 Debug 构建与 86 项测试通过。Lint 的 3 个阻塞均来自基线中未修改的 `helper/DisplayHelper.java`：144、238 行 `WrongConstant`，229 行 `BlockedPrivateApi`。没有为这些问题添加屏蔽规则，也没有修改后台屏代码。

这些检查不代表真实 XiaoAI、root、LSPosed 或模型服务验证。未向实体设备安装 APK，也未重启小爱。
