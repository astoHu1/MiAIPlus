# 插件底栏入口

2026-10-28，基于 `4078690` 的当前工作树。底栏从「首页／模型 API／控屏」改为「首页／模型 API／插件」，使用与现有导航图标同线宽的拼图图标。插件页成为一级页面，顶部不显示返回按钮；首页保留插件概览快捷入口。

原控屏页通过内置 Phone Use 的「控屏配置」进入，虚拟屏继续位于控屏页下。两页均保持插件标签选中，返回按「虚拟屏 → 控屏 → 插件 → 首页」逐级处理。底栏切页方向、二级页面动画、模型 API 草稿保存确认及配置持久化沿用现有机制。没有调整插件启停、MCP 协议、控屏权限或执行后端。

## 本地检查

使用 JDK 25、项目本地 SDK 和 Gradle 缓存执行：

```sh
./gradlew :app:testDebugUnitTest --tests io.github.miaiplus.SettingsUiTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

- 设置页定向回归 27 项通过。最终全量 JVM／Robolectric 回归 524 项通过，无失败、错误或跳过；该数字来自当前整个工作树，包含此前已有的观察与日志改动。
- Debug／Release 完整 Lint 通过，均无错误；分别保留 37／26 项 Warning，各 1 项 Hint。Debug、Release 和 Debug AndroidTest 构建通过。
- 设置页回归覆盖底栏顺序、插件一级页、控屏与虚拟屏的标签归属、工具栏／系统返回、页面重建、首页快捷入口、深浅色、双向切页与中断动画、模型 API 草稿保存及滚动恢复。
- 现有插件 UI 回归改为从底栏进入，继续检查内置项、MCP 草稿、凭证遮挡、启用确认、协议选择及工具参数查看；首次全量回归发现旧测试仍要求首页标签选中，更新为插件标签后完整重跑通过。
- 更新设备设置 runner 的控屏入口与底栏性能采样路径，并通过 AndroidTest 编译；未执行设备 runner。
- 人工核对本轮合成配置的首页、插件深浅色和控屏二级页渲染；截图见 [设置界面](../settings-ui.md#截图)。
- `git diff --check` 通过。

构建日志位于本机临时文件 `/tmp/miaiplus-plugins-tab-validation.log` 和 `/tmp/miaiplus-plugins-tab-full-validation.log`。

## 验证边界

上述为首轮本地检查，当时未安装或操作手机，未启动模型请求、MCP 网络调用、控屏任务或后台显示探针；没有重做设备性能、宿主 Hook 或真实模型验收。截图来自 Robolectric 合成配置，不含个人设备数据。后续用户授权将导航改动单独提交至 Pluginify，并在已连接设备上执行设置页回归；结果另行追加，不追溯为首轮已经验证。
