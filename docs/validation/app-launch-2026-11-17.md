# 前台应用启动恢复（2026-11-17）

## 故障与定位

用户导出的 2026-11-16 日志中，一次启动在桌面等待约 10 秒后暂停；另一次直到约 9.97 秒才出现安全中心，随后启动超时。旧日志不包含页面正文，不能据此确定具体弹窗模板或是否点过允许。

在 Xiaomi API 37 / HyperOS OS4.0.4.0.XOACNXM 上，用独立开发签名包直接调用生产 `ScreenService.execute(launch)`，复现桌面停留后超时。系统日志显示小米拒绝 `CHECK_ALLOW_START_ACTIVITY` / `ConfirmStartActivity`，框架同时报告 `BAL_ALLOW_PERMISSION` 与启动中止。替换为公开 `LauncherApps.startMainActivity()` 再验，仍被同一确认入口拦截，因此未采用该路径。

正式包的 MIUI AppOp 10021 为 default 且有拒绝记录。隔离包是新 UID，不能把两包全部权限状态视为相同；上述实测用于验证当前启动故障和修复路径，不证明每个生产任务都经历相同分支。

## 修复范围

- 前台已解析的 MAIN/LAUNCHER 目标优先使用独立一次性 root 启动；保持同一用户和物理屏，后台显示实现不变。
- 请求开始发送前失败才允许原显式启动路径；请求开始发送后不重试。取消、锁屏、短期限和连接关闭约束迟到执行。
- 启动确认兼容“启动应用”和“打开应用”；匹配当前发起方、目标及唯一“本次允许”后仅点击一次。
- 晚到确认有一次 1.5 秒过渡宽限；新出现的未知安全中心窗口返回观察，目标仍须稳定前台 500 毫秒才算打开。
- 新增启动阶段元数据，不记录页面正文或命令内容，不修改授权数据库、AppOps 或全局策略。

## 验证与安装边界

22 项相关 JVM 测试通过：`LaunchVerifierTest` 13 项、`LaunchPromptTest` 4 项、`SystemDialogPolicyTest` 5 项。最终源码的 `:app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` 通过，Debug Lint 为零错误；独立 applicationId 的设备测试包亦构建通过。未运行完整 JVM 集合、Release／Preview 检查或无关设备回归。

用户授权 root 后，第一次回归在等待桌面阶段被安全锁屏拦截，未执行启动动作。用户随后亲自解锁，再运行隔离包的生产 `ScreenService.execute(launch)`，日志显示 `su_start_failed`，回退标准路径后仍停留桌面并暂停。保留同一次安装追加 `root_probe_only` 诊断：目标进程内的 `su -c id` 在 stderr 管道和 `/dev/null` 重定向两种方式下都抛出 `IOException(errno=13)`，说明独立测试 UID 执行被拒，排除了仅重定向引起的失败；不能把正式包的授权视为隔离包授权。

正式包的 `background_display_status` 只读探针返回 `root=true, uid=0, deviceLocked=false`，没有创建显示屏。随后由已授权的 ADB root shell 从测试 APK 运行本轮 `ForegroundLaunchHelper`：两次均完成 READY／请求／`submitted` 协议，测试 fixture 在物理屏成为唯一 `topResumedActivity` 并稳定至少 600 毫秒，第二次启动保持相同 ActivityRecord，没有手动点击安全中心。**root 助手组件的启动路径实机通过；这不覆盖从模块 UID 启动新助手的完整调用链，也不等于页面实例／触控计数断言通过。** 独立包完整回归仍未通过，不能宣称完整 Phone Use 验收成功。

新增的仅诊断 instrumentation 参数 `-e root_probe_only true` 报告包名、实际 UID、两种 `su` 启动方式的受控身份结果或异常类链／errno；每次等待 `su` 退出至多 3 秒，随后收尾读取线程与清理，不改配置或服务。该诊断分支已重新构建并在设备执行。启动回归的总耗时断言同时计入 root 握手／回执与晚到确认预算，最终测试 APK 编译通过。

测试使用独立 applicationId 和配套 fixture；原正式签名安装不能由本地开发签名覆盖，未卸载、清除或覆盖原应用。最终回归结束后已移除两个独立包，正式包安装元数据与基线一致，原有无障碍服务仍启用且列表无临时测试服务；期间列表额外出现一个原有服务的等价全名条目，保留当前状态，没有强行覆盖。修复尚未部署到原正式签名安装。未调用真实模型，未进行后台控屏或完整端到端模型验收。
