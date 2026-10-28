# 配置界面性能

## 底栏过渡补充（2026-10-28）

底栏切页改为 220 ms 淡入、24 dp 水平滑入，按首页、模型 API、控屏的排列决定方向。旧页仍立即释放，只有新页参与绘制层过渡，不恢复两整页交叠、尺寸动画或 lookahead。动画状态按目的页创建，连续切换取消旧页动画，点击当前标签不重播；现有二级页过渡、表单懒加载与滚动恢复保持。新实现的验证结果见 [底栏动画记录](validation/settings-tab-animation-2026-10-28.md)，下方历史性能数据不能代表本次结果。

## 历史实机优化（2026-10-17）

用户反馈所有页面卡顿后，在已连接的 Xiaomi 25019PNF3C / xuanyuan、API 37 上复测。手机原安装包带 `DEBUGGABLE`，先用原包采样，再安装当前完整源码构建的优化 Preview；保留工作区已有后端改动。屏幕为竖屏 1440 × 3200、560 dpi，系统浮层与原无障碍启用列表保留，UiAutomation 使用 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`。

本轮前端修改：

- 底栏跨标签切换立即替换内容，不再同时绘制两整页；二级页保留 240 ms 绘制层过渡。
- 模型配置表单按可见分组懒加载，滚动位置在切页与重建后保留；模型选择列表继续懒加载。
- 未保存标记使用带结构相等策略的派生状态。已修改草稿继续输入时，不再逐键通知导航和保存栏。
- 完整诊断仅返回前台时刷新；运行状态页进入时立即做轻量刷新，此后每 3.5 秒刷新。普通切页不重复读取诊断。

`SettingsUiRunner -e performance true` 使用临时配置，先等待首屏稳定，再执行四轮底栏切换、各页 12 次 300 ms 往返手势、12 次草稿文本修改及四轮二级页进退。每项结束留 500 ms 收尾，进程 CPU 来自 `Process.getElapsedCpuTime()`，100% 表示一个核心；每项重置并读取 `dumpsys gfxinfo` 的现代截止时间统计。输入项通过无障碍设置文本，不等于逐键输入法延迟测试。

| 操作 | P95：原 Debug → 最终 Preview | 错过截止时间 / 总帧：Debug → Preview | CPU：Debug → Preview |
| --- | --- | --- | --- |
| 底栏切换 | 350 → 150 ms | 50/246 → 13/40 | 159.72% → 56.53% |
| 模型页滚动 | 109 → 21 ms | 37/472 → 2/820 | 182.36% → 119.02% |
| 控屏页滚动 | 77 → 10 ms | 14/588 → 1/1146 | 168.77% → 78.96% |
| 修改配置名称 | 150 → 40 ms | 24/58 → 8/48 | 73.36% → 22.77% |
| 二级页进退 | 93 → 31 ms | 41/620 → 1/908 | 180.59% → 106.47% |

最终 Preview 的首页、虚拟屏各 5.5 秒空闲窗口均无新绘制帧，CPU 分别为 1.24%、0.33%。虚拟屏在本次竖屏、无检测结果的状态下内容未超出视口，手势没有产生滚动帧，因此不报告它的滚动 P95；零帧时 gfxinfo 的 4950 ms 占位值不构成耗时证据。

这是同设备短时对比，包含构建模式变化，不能将全部收益归因于源码修改，也不能宣称已消除卡顿。最终包底栏切换仍有 150 ms 长帧，模型页滚动仍约占一个核心。中间 Preview 采样与最终懒加载版本有波动，未证明懒加载单独带来的性能增幅。另一次短时界面线程跟踪中，首屏和模型页首次布局所在帧仍分别约 63、54 ms，说明首次布局开销仍需继续优化。

当前源码的 209 项 JVM/Robolectric 测试通过，包含过渡中跨标签切换释放旧页、派生未保存标记，以及懒加载表单切页/重建后恢复滚动位置。Debug/Release 完整 Lint（0 errors）、Debug/Release/Preview APK 和 Debug/Preview AndroidTest 构建通过。最终 Preview 实机设置回归通过配置保存/新增/切换/删除、草稿重建/放弃、真实键盘与保存按钮、0/81 拒绝与 1/80 保存、前置检查结果及直接测试页导航；测试器在操作前等待首屏就绪。原配置由 runner 恢复并逐项比较一致。

性能采样不启动虚拟屏检查、helper 或模型。功能回归仅手动运行前置检查，不运行能力探针或模型任务；未重启小爱。采样没有逐项记录本模块 Binder 连接健康；最终检查发现本模块服务需重连，已仅重连其自身并验证绑定成功、无崩溃标记，原启用列表保持一致。这些空闲设置页数据不代表控屏会话运行时开销。手机保留最终不可调试 Preview。原始日志、跟踪与构建输出留在本地忽略目录 `.tools/settings-perf-20261017/`。

复测时，Debug 与 Preview 必须使用各自配套的测试 APK，避免 R8 映射不匹配：

```sh
# Debug 对照使用同一源码的主包与设置专项 runner。
./gradlew -PsettingsUiTests=true :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument --user 0 -w -e performance true \
  io.github.miaiplus.test/io.github.miaiplus.SettingsUiRunner
# Preview 使用优化后、同一构建映射的主包与测试包。
./gradlew -PuiTestBuildType=preview :app:assemblePreview :app:assemblePreviewAndroidTest
adb install -r app/build/outputs/apk/preview/app-preview.apk
adb install -r app/build/outputs/apk/androidTest/preview/app-preview-androidTest.apk
adb shell am instrument --user 0 -w -e performance true \
  io.github.miaiplus.test/io.github.miaiplus.SettingsUiRunner
```

以下保留此前的历史性能记录。

2026-10-16，前端分支先快进合并远程 main `095156c`。本次生产代码仅修改 `MainActivity.kt`、`SettingsState.kt` 及前端构建配置，不写入 Claude 的 main 工作区。

## 本次导航调整

底栏拆为首页、模型 API 和控屏，二级页恢复进入与返回动画。动画只读绘制层的透明度和位移，260 ms 后移除旧页面；不使用 `AnimatedContent`、lookahead 或动画尺寸测量。底栏切换无过渡，模型列表继续懒加载，只有诊断页保留定时刷新。本节描述导航重排；虚拟屏新增后的实机采样单列如下，后续历史对比表来自导航重排之前。

## 虚拟屏页面采样（2026-10-16）

PR 推送后，在 Xiaomi 25019PNF3C / API 37 / HyperOS 4 的现有主应用上测试优化 Preview 前端，保留已安装后端。手机横屏 3200 × 1440，系统性能浮层保持开启，UiAutomation 保留原无障碍服务状态。仅进入虚拟屏页，不运行前置检查或能力检测；5 秒空闲后执行 12 次 300 ms 上下滑动。通过 root 读取应用 `/proc/PID/stat`，100% 表示占满一个核心；帧数据为 `dumpsys gfxinfo` 的现代截止时间统计。

| 指标 | 第一轮 | 第二轮 |
| --- | ---: | ---: |
| 空闲窗口 / CPU 时间 | 5.07 s / 0.01 s | 5.08 s / 0.01 s |
| 空闲 CPU | 0.20% | 0.20% |
| 滚动窗口 / CPU 时间 | 4.52 s / 4.87 s | 4.34 s / 4.59 s |
| 滚动 CPU | 107.74% | 105.76% |
| 帧耗时 P95 / P99 | 13 / 18 ms | 11 / 17 ms |
| 错过截止时间 / 总帧 | 1 / 990 | 1 / 968 |

空闲页面没有启动 helper 或 IPC 检测。滚动时仍约占一个 CPU 核心，不能宣称 CPU 开销已消除。此为短时横屏采样，未重做旧版同条件对照，不能与下方历史竖屏数据直接计算改进比例。原始采样仅留在本地忽略目录 `.tools/virtual-ui/`。

以下为本次导航调整之前的性能优化与实机验证记录。

## 原因与修改

实机采样中，原 Debug APK 的 JIT 编译线程占 CPU cycles 样本的 45.67%，主线程解释执行也占较大比例。先前交付的可调试构建不适合作为日常使用的 Compose 性能版本。

- Release 启用 R8 优化和资源缩减；新增采用开发签名、不可调试的 `preview` 构建，用于本地安装。应用后端类、成员及 libxposed 接口使用保留规则，保持反射与入口名称。
- 移除包住整页表单的 `AnimatedContent`。保留 Miuix 原有标题、控件和弹窗动画，页面切换只保留一份内容树，避免整页尺寸过渡与折叠标题同时反复测量。
- 进入前台时刷新状态；仅在运行状态页每 2 秒更新诊断，其余页面没有定时刷新。离开前台后停止刷新。
- 模型选择弹窗使用 `LazyColumn`，只组合可见项；前端去重服务返回的模型 ID，防止重复列表键。

没有关闭无障碍语义、设备无障碍服务或生产窗口的 `FLAG_SECURE` 来取得性能结果。

## 实机对比

设备：Xiaomi 25019PNF3C / xuanyuan，Android API 37，1440 × 3200、560 dpi。独立的 `io.github.miaiplus.uiperf` 测试包使用同一份默认配置，连续执行 12 次 300 ms 上下往返滑动。进程 CPU 通过 `/proc/PID/stat` 的 utime + stime 计算，100% 表示占满一个核心；帧数据来自 `dumpsys gfxinfo`，CPU 计时窗口未同时运行采样器。

| 指标 | 原界面 Debug | 优化界面 Preview |
| --- | ---: | ---: |
| 进程 CPU | 173.71% | 27.75% |
| CPU 时间 | 8.06 s | 1.16 s |
| 帧耗时 P95 | 93 ms | 7 ms |
| 帧耗时 P99 | 117 ms | 9 ms |
| 掉帧 / 总帧 | 52 / 254 | 0 / 794 |
| 冷启动 Activity 耗时 | 1023 ms | 377 ms |

优化 Preview 首页空闲 5 秒，进程 CPU ticks 未增长。Debug 保留调试运行时；不能把此表解释为同一个 Debug 构建仅移除动画就能取得全部收益。上述为本机短时对比，原始数据在本地 `app/build/outputs/performance/`，未作为发布文件提交。

在保留原配置和原后台实现的手机主应用中也完成安装与回归。无障碍服务保持连接的 12 次滚动结果为 CPU 45.12%、P95 16 ms、1/802 帧错过截止时间；此前同一主应用 Debug 采样为 CPU 178.64%、P95 81 ms、58/316 帧错过截止时间。主应用与独立测试包的配置不同，因此分别记录。

## 构建与回归

日常设备试用：

```sh
./gradlew :app:assemblePreview
adb install -r app/build/outputs/apk/preview/app-preview.apk
```

Preview 使用本机 Android 开发签名，不是正式发布签名。正式发布继续按 [发布说明](releasing.md) 构建并签名 Release。

优化构建的界面回归：

```sh
./gradlew -PuiTestBuildType=preview :app:assemblePreview :app:assemblePreviewAndroidTest
adb install -r app/build/outputs/apk/preview/app-preview.apk
adb install -r app/build/outputs/apk/androidTest/preview/app-preview-androidTest.apk
adb shell am instrument --user 0 -w \
  io.github.miaiplus.test/io.github.miaiplus.SettingsUiRunner
```

UI Runner 使用临时配置，验证实际 Compose 节点、配置增删切换、草稿保留、键盘避让、步数边界与页面返回，结束恢复原设置。不请求模型。测试用 ViewModel API 保留规则防止仅在独立测试 APK 中使用的方法被移除，主线程断言也会返回测试线程处理。

前端分支的 89 项 JVM / Robolectric 测试通过，包括 1000 项模型列表只组合可见项与选择后保留为草稿的检查。原有 `DisplayHelper.java` 仍有 3 处 Lint 错误（2 × WrongConstant、1 × BlockedPrivateApi），本次未修改或屏蔽。前端分支的性能 APK 仅在本地跳过对应的 lintVital 任务完成测试；完整 Lint 结果仍保留这些错误。设备主应用的安装验证使用已安装后台代码快照加本次前端修改的临时副本，避免回退后台实现；该副本 Preview 构建与 Lint 通过，未把后台快照带入前端分支。

最终优化版主应用通过实机 UI Runner 的配置增删切换、草稿重建/放弃、真实键盘与保存按钮、0/81 拒绝和 1/80 步保存、后台屏检查结果与直接测试页导航。原配置恢复后与安装前备份比较，仅 `hook_at` 诊断报告时间变化。无障碍连接已恢复；未重启小爱或发送模型请求。
