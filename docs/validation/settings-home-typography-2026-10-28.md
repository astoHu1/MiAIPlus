# 首页概览卡字号与对齐

2026-10-28，基于 `dc9107a` 的工作树 UI 修改。首页「版本／无障碍」原来采用 11 sp 小标签和 14 sp 数值，与下方设置项的主次关系不同。两卡现复用 Miuix `BasicComponent` 的 17 sp 中等字重标题、14 sp 次要色说明和 16 dp 内边距，并根据本行内容等高。没有修改配置、Hook 或控屏执行逻辑。

## 本地检查

- 最终源码执行 `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:lintRelease :app:assembleRelease :app:assembleDebugAndroidTest :app:assemblePreview` 成功。
- 376 项 JVM／Robolectric 测试通过，无失败或跳过。Debug／Release 完整 Lint 均无错误，分别有 36／26 项警告。
- 检查现有设置 UI 测试输出的深浅色首页渲染，更新 `docs/images/settings-light.png` 和 `docs/images/settings-dark.png`，使用合成配置。
- `git diff --check` 通过；构建日志保留在本地忽略目录 `.tools/home-typography-20261028/checks-final.log`。

## 实机安装、恢复与边界

设备为 Xiaomi 25019PNF3C、API 37，1440×3200、560 dpi、字体缩放 1.0。经用户明确授权，在允许不同签名覆盖安装的设备上用 `adb install -r` 安装当前优化 Preview，返回成功。本次收尾直接覆盖安装，不再采用独立 UI 包方案。

恢复阶段发现安装状态中的原配置缺失、模块无障碍服务断开。本地安装前备份完整；恢复原配置、原日志和原应用文件共 49 项后，`config.xml` 的 SHA-256 与安装前完全一致。仅重新启用模块自己的无障碍服务，最终启用服务集合与安装前一致，`accessibility_health` 返回 `connected=true`、`busy=false`、`preparing=false`，重新进入首页显示「已连接」。最终实机截图确认两卡的字号、主次颜色与等高布局。恢复完成后删除本次敏感数据备份、旧 APK 和中间截图，只保留构建日志、最终 UI 截图和无敏感信息的检查摘要。

实机仅检查当前浅色首页；深色由 Robolectric 覆盖。没有改变系统字体、主题或密度，没有触发模型请求、虚拟屏探测或重启小爱；未重做 Hook、root 授权、控屏链路或放大字体的设备验收。首页绿色状态卡仍是原有历史注入报告，不是本轮 Hook 验证。测试包不是新的正式发布版本。
