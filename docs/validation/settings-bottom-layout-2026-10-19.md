# 模型 API 列表底部布局修复

2026-10-19，针对基于 `992a880` 的工作树设置 UI 改动。模型 API 页原先在懒列表外固定保留 24 dp 底部间距，列表卡片会在底栏上方提前截断，留下不随滚动变化的空白条。现将该间距移至 `LazyColumn.contentPadding`，滚动视口延伸到底栏或保存操作栏上沿，末项仍保留滚动内边距。

## 本地验证

- 新增 `modelListReachesTheBottomBarAndKeepsItsLastSettingAccessible`：修复前底部边界断言失败；修复后通过。覆盖浅色、深色、未保存时的保存操作栏和滚动到末项后的可见性。
- 25 项 `SettingsUiTest` 通过；检查了 `model-bottom-bar-light.png`、`model-bottom-bar-dark.png`、`model-bottom-bar-dirty.png` 的 Robolectric 渲染。图片输出在 `app/build/outputs/ui-screenshots/`，使用合成配置。
- 完整 376 项 JVM／Robolectric 测试通过，无失败或跳过；Debug／Release 完整 Lint、Debug／Release APK 和 Debug AndroidTest APK 构建通过。命令为 `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:lintRelease :app:assembleRelease :app:assembleDebugAndroidTest`。
- `git diff --check` 通过。本地执行日志位于忽略目录 `.tools/model-bottom-layout-*-20261019.log`。

## 范围

本轮没有连接或操作测试手机，没有安装 APK，也没有请求模型。Robolectric 渲染不证明实际 ROM、手势／三键导航、软键盘或多窗口下的表现；原键盘与系统栏处理未改动。构建输出不是新的正式发布包。
