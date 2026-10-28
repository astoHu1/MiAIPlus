# 设置页精简与主线整合

日期：2026-10-29。集成基线为 main `8e9ff66` 与 Pluginify `4293f3e`，在独立工作树中合并、构建和测试。原 Pluginify 工作树的并行 Phone Use 编辑未加入本次提交。

## 界面

- 插件页分成「内置工具」与「MCP 插件」。前台／后台控屏共用「控屏配置」，MCP 区直接提供添加、编辑、获取工具和删除。
- 删除重复页头、长段说明和页尾提示；工具技术名称与 schema 放入「参数详情」。启用外部服务时保留一句数据发送提示。
- 首页、模型 API、控屏与虚拟屏说明缩短。权限选择显示「手动确认／智能判断／自动执行」，存储值及执行规则仍为 manual／auto／yolo。
- 主线沿用 `io.github.miaiplus` 和「MiAIPlus」，覆盖安装保留原配置。Pluginify 分支继续保留独立包身份。

## 本地检查

以下命令全部通过：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease :app:assemblePreview \
  :app:assembleDebugAndroidTest
./gradlew -PuiTestBuildType=preview :app:assemblePreview :app:assemblePreviewAndroidTest
python3 scripts/test_accessibility_guard.py
```

524 项 JVM／Robolectric 测试及 7 项 guard 脚本测试通过。Debug／Release 完整 Lint 零错误。浅色、深色、MCP 草稿、凭证遮挡、协议选择和参数展开由 Compose 测试及渲染核对。插件与控屏截图见 [界面文档](../settings-ui.md)。

## 实机

使用已连接的 Xiaomi API 37 设备，安装实际合并源码的优化 Preview 主线包及匹配的 Preview AndroidTest。安装前核对主包身份和升级签名。

1. `SettingsUiRunner -e plugins true`：底栏插件入口、控屏／虚拟屏选中态、添加 MCP、草稿重建与取消、工具栏／系统返回、当前标签返回根页、模型未保存确认全部通过。
2. 完整 `SettingsUiRunner`：模型配置保存／另存／切换／删除、键盘下保存按钮、重建与放弃草稿、API 协议、无背景变暗的下拉、全局模型规划锁定、三种操作确认的持久化与新 ViewModel、详细日志开关、步数边界、虚拟屏前置检查结果及直接测试入口全部通过。
3. 两组 runner 均返回 PASS，并逐项恢复原偏好后确认相等。未发起模型请求或虚拟屏能力探测。此轮是设置回归，不重新证明宿主 Hook、MCP 网络或真实模型控屏质量。

测试后恢复原先已安装的 instrumentation APK，并以 SHA256 核对。手机保留本次优化主包；其已安装 APK 与本地 Preview 一致。无障碍服务列表及 ADB reverse 映射均与测试前相等，未更改 LSPosed 作用域或重启小爱。原始截图、APK 备份及运行日志只保留在忽略的 work 目录。

主包 SHA256：`68d831616f23abb59aa9ad136cd2fc4789e038ba2d8ebfe33d03316fa0a1735b`。

## Phone Use 保留核对

main `8e9ff66` 的 15 个文件全部保留。AgentEngine、ModelClient、NativeResponsesAdapter、PhoneUseLog、ModelObservation 及观察／重规划测试与 main 一致；NativeSession 仅叠加插件原始屏幕归属字段与续调查询，保留模型观察投影、执行状态和计量逻辑。PhoneUseLogTest 仅将 Provider authority 改为跟随安装身份；架构和验证文档同时保留两边内容。合并后全套 524 项测试包含原主线优化的回归。
