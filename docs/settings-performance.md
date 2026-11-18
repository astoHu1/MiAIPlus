# 配置界面性能

当前布局与实现见 [设置界面](settings-ui.md)。以下采样早于 2026-11-06 UI 重写；重写后未重新采样，功能与安装结果见 [UI 重写验证](validation/settings-ui-rewrite-2026-11-06.md)。

## 采样与复测

性能测试使用不可调试、启用 R8 的 Preview；Debug 对照与 Preview 各自使用同次构建的主包和测试 APK，避免映射不匹配。Preview 使用开发签名，正式包按 [发布流程](releasing.md) 签名。

```sh
./gradlew -PuiTestBuildType=preview :app:assemblePreview :app:assemblePreviewAndroidTest
adb install -r app/build/outputs/apk/preview/app-preview.apk
adb install -r app/build/outputs/apk/androidTest/preview/app-preview-androidTest.apk
adb shell am instrument --user 0 -w -e performance true \
  io.github.miaiplus.test/io.github.miaiplus.SettingsUiRunner
```

Runner 使用临时配置，等待首屏稳定后执行四轮底栏切换、各页 12 次 300 ms 往返手势、12 次草稿文本修改和四轮二级页进退，结束后恢复配置。每项留 500 ms 收尾，读取 `dumpsys gfxinfo` 截止时间统计；CPU 来自 `Process.getElapsedCpuTime()`，100% 为一个核心。文本修改使用无障碍设置文本，不测逐键输入法延迟。采样不启动模型或虚拟屏检测。

## 2026-10-28 底栏动画

底栏改为 220 ms 淡入、24 dp 水平滑入，只绘制新页；连续切换取消旧动画，重复点当前标签不重播。实机底栏 P95 为 19 ms，1/660 帧错过截止时间，仍出现 150 ms 长帧。详细结果见 [底栏动画记录](validation/settings-tab-animation-2026-10-28.md)。

## 2026-10-17 主应用对照

设备为 Xiaomi 25019PNF3C / xuanyuan、API 37，竖屏 1440×3200、560 dpi；保留系统浮层和无障碍服务。原 Debug 与优化 Preview 均保留已有后端改动。

此次优化包括：底栏仅保留一份页面内容，表单按分组懒加载并恢复滚动位置，未保存状态仅在干净／已修改之间变化时通知导航，完整诊断仅返回前台时刷新，运行状态页每 3.5 秒轻量刷新。

| 操作 | P95：Debug → Preview | 错过截止时间 / 总帧：Debug → Preview | CPU：Debug → Preview |
| --- | --- | --- | --- |
| 底栏切换 | 350 → 150 ms | 50/246 → 13/40 | 159.72% → 56.53% |
| 模型页滚动 | 109 → 21 ms | 37/472 → 2/820 | 182.36% → 119.02% |
| 控屏页滚动 | 77 → 10 ms | 14/588 → 1/1146 | 168.77% → 78.96% |
| 修改配置名称 | 150 → 40 ms | 24/58 → 8/48 | 73.36% → 22.77% |
| 二级页进退 | 93 → 31 ms | 41/620 → 1/908 | 180.59% → 106.47% |

首页、虚拟屏各 5.5 秒空闲窗口均无新绘制帧，CPU 为 1.24%、0.33%。虚拟屏内容未超出视口，手势未产生滚动帧，因此没有滚动 P95。首次首页和模型页布局另测约 63、54 ms。

这是同设备短时对比，包含构建模式差异；收益不能全部归于源码修改。模型滚动仍约占一个核心，底栏仍有长帧。原始输出保存在本地忽略目录 `.tools/settings-perf-20261017/`。

当轮 209 项 JVM／Robolectric、双变体完整 Lint、主包与配套测试包构建通过；实机 UI Runner 覆盖配置增删切换、草稿恢复／放弃、键盘与保存按钮、当时的步数边界及页面导航。配置恢复一致，无障碍服务重连后正常。设备未运行模型任务；这些结果不测控屏运行时开销。

## 2026-10-16 历史采样

### 虚拟屏页

同一机型 API 37 / HyperOS 4，横屏 3200×1440，优化 Preview 主应用保留已安装后端。页面空闲 5 秒后执行 12 次 300 ms 往返滑动；CPU 读取 `/proc/PID/stat`，帧统计来自 `dumpsys gfxinfo`。

| 指标 | 第一轮 | 第二轮 |
| --- | ---: | ---: |
| 空闲窗口 / CPU 时间 | 5.07 s / 0.01 s | 5.08 s / 0.01 s |
| 空闲 CPU | 0.20% | 0.20% |
| 滚动窗口 / CPU 时间 | 4.52 s / 4.87 s | 4.34 s / 4.59 s |
| 滚动 CPU | 107.74% | 105.76% |
| 帧耗时 P95 / P99 | 13 / 18 ms | 11 / 17 ms |
| 错过截止时间 / 总帧 | 1 / 990 | 1 / 968 |

未运行前置检查或能力检测；横屏结果与竖屏数据不能直接计算改进比例。原始输出位于本地 `.tools/virtual-ui/`。

### 初轮界面优化

原 Debug 的 JIT 编译线程占 CPU cycles 样本 45.67%。优化启用 R8／资源缩减，移除整页 `AnimatedContent` 尺寸过渡，限制状态轮询，模型列表改为懒加载并去重 ID；保留无障碍语义与设备服务。

独立 `io.github.miaiplus.uiperf` 包使用相同默认配置，在竖屏 1440×3200、560 dpi 下执行 12 次 300 ms 往返滑动：

| 指标 | 原界面 Debug | 优化界面 Preview |
| --- | ---: | ---: |
| 进程 CPU | 173.71% | 27.75% |
| CPU 时间 | 8.06 s | 1.16 s |
| 帧耗时 P95 / P99 | 93 / 117 ms | 7 / 9 ms |
| 错过截止时间 / 总帧 | 52 / 254 | 0 / 794 |
| 冷启动 Activity 耗时 | 1023 ms | 377 ms |

保留原配置和后台实现的主应用另测：Debug 为 CPU 178.64%、P95 81 ms、58/316 帧超时；Preview 为 45.12%、16 ms、1/802 帧。主应用与独立包配置不同，分别比较。原始数据保存在本地 `app/build/outputs/performance/`。

初轮前端分支 89 项 JVM／Robolectric 通过，但完整 Lint 仍有 `DisplayHelper.java` 的 3 个错误，性能 APK 曾跳过对应 lintVital；不能作为完整发布检查。另用保留已安装后台的临时副本完成主应用安装与 UI 回归，该副本 Preview 构建和 Lint 通过。
