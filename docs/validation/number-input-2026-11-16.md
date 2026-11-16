# 数字对话框输入顺序（2026-11-16）

最大步数的数字编辑框此前只传递字符串，OverlayDialog 内容快照更新时无法保留完整编辑状态，可能使第二个数字插到第一个数字前面。`NumberDialog` 现在在对话框宿主外保留 `TextFieldValue`，同步更新文本、选区与输入法合成区；仅文本变化回传原字符串接口，取消重开从调用方保存值初始化。布局、数字键盘与校验规则保持原样；共用此组件的自动新建对话时长也使用此修复。

本轮按要求只验证此输入问题：

| 检查 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest --tests io.github.miaiplus.NumberDialogInputTest` | 1 项通过；逐段输入 `2`、`0` 得到 `20`，光标位置为 1、2；移动光标插入、选区替换、取消重开恢复 `30` 通过 |
| `-PuiTestBuildType=preview :app:assemblePreview :app:assemblePreviewAndroidTest` | 独立包名的优化主包与配套测试包构建通过 |
| API 37／Xiaomi 25019PNF3C，`-e numberInput true` | PASS；清空最大步数后分别发送 `KEYCODE_2`、等待重组、再发送 `KEYCODE_0`，文本为 `20`，光标依次为 1、2；取消草稿，偏好前后逐项相同 |
| 实机截图 | 已检查 `20` 与末尾光标，输入框、标题及按钮沿用相邻 Miuix 页面样式，无布局改动 |
| `git diff --check` | 通过 |

设备已安装正式签名的 1.0.3（versionCode 18），本地开发签名不能直接覆盖。使用本地忽略的 Gradle init 脚本，仅为验证将 applicationId 改为 `io.github.miaiplus.inputcheck`，测试包为 `.inputcheck.test`，没有修改仓库版本配置。测试后卸载这两个临时包；原正式包的版本、安装更新时间与 MIUI op `10021=default` 核对未变。原正式包未包含本轮修复。

日志、截图与本地构建脚本位于忽略目录 `work/numeric-input-20261116/`。最初将 Debug 单元测试与 Preview 仪器测试放在同一构建配置，因任务不存在未执行测试；改为默认配置执行单个 Debug 用例，再单独构建 Preview，均已通过。未运行完整 JVM 测试、完整 Lint、其他设备用例、宿主 Hook、控屏执行或模型请求；自动新建对话时长未单独实机复验。
