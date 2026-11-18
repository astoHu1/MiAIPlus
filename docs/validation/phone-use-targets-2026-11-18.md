# Phone Use target 引用与动态页面验证（2026-11-18）

## 范围

本轮验证当前工作区中动态页面 target 引用、坐标输入校验、目标过期拒绝和同会话重新规划。设备为 Xiaomi 25019PNF3C（xuanyuan）、API 37；使用 Debug 主 APK 与 AndroidTest APK，fixture 只包含本测试自有页面和按钮，不调用真实模型。

## 本地检查

通过：

- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:lintRelease :app:assembleRelease :app:assembleDebugAndroidTest`
- 使用已有 `work/models-dev-20261114/api.json` 设置 `MIAIPLUS_MODELS_DEV_FIXTURE`，以 `:app:testDebugUnitTest --rerun-tasks` 重新执行完整套件：756 项通过，零失败、零跳过。
- `python3 scripts/test_accessibility_guard.py`：7 项通过。
- `git diff --check`

本轮构建包含 `NodeTargets`、坐标校验、NativeSession 重规划、日志页语义点击及对应回归测试。双变体完整 Lint 零错误，Debug 48 条警告、Release 37 条警告；构建保留 SDK 路径及弃用提示。日志页明暗主题、展开状态截图已核对，文档本地链接检查通过。本次提交前复核沿用下述已保存的实机 fixture 结果，未重复安装或操作设备。

## 设备实测

Debug 与 AndroidTest APK 安装成功。首次运行暴露测试 fixture 通过 instrumentation 进程直接 `startActivity()` 时被 Android 设备拒绝，日志为 `Abort background activity starts`；这不是业务控屏失败。测试 runner 改为通过已授权 root shell 启动自有 fixture 后重新安装并复测。

`python3 scripts/device_test.py --robust-screen` 通过，报告确认：

- 无关文字每 100ms 刷新期间稳定目标仍可点击；
- 目标移动和替换后旧 target／坐标动作不产生触摸，并在同一会话取得新观察后重规划；
- 严格 3 次物理 touch-down、3 次按钮点击，零旧坐标注入；
- 50ms 续步与等待阶段通知采样持续，只有显式 `finish` 后报告完成；
- fixture、偏好和测试服务清理完成，设备恢复到测试前 Settings 子页面，无障碍服务列表保持原有条目。

## 限制

这证明当前 Debug 工作区在该 Xiaomi API 37 设备上的确定性宿主探针通过，不等于所有第三方动态页面、任意 ROM 或真实模型自主任务均通过。正式签名包未被本地 Debug 签名覆盖，未调用生产模型。
