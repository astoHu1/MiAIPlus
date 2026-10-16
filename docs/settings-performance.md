# 配置界面性能

2026-10-16，前端分支先快进合并远程 main `095156c`。本次生产代码仅修改 `MainActivity.kt`、`SettingsState.kt` 及前端构建配置，不写入 Claude 的 main 工作区。

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
