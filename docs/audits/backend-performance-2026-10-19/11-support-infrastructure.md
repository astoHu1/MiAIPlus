# 支持层与构建、开发工具性能审计

日期：2026-10-19。基线：`418267f87e3909fd3b526c51c525bad86b895260`。本报告为静态审计，无本轮性能测量。未修改后端代码，未执行脚本、构建、测试、网络请求或设备操作；本文中的验证是建议，不能写成已通过。

初次审计 `git status --short` 为空；记录本文时仅看到并行文档工作产生的 `?? docs/audits/`，保留全部他人工作。已阅读 `AGENTS.md`、`docs/architecture.md`、`docs/validation.md`，并核对 `docs/settings-performance.md` 中的既有验证边界。

## 覆盖范围

完整阅读：

- `app/src/main/java/io/github/miaiplus/SettingsState.kt`（298 行），仅讨论非 Compose 数据/持久化/IPC 层。
- `app/src/main/java/io/github/miaiplus/VirtualScreenState.kt`（168 行）。
- `scripts/accessibility_guard_device_test.py`、`scripts/background_display_test.py`、`scripts/device_test.py`、`scripts/mock_model.py`、`scripts/package_release.py`、`scripts/reply_actions_mock.py`、`scripts/test_accessibility_guard.py`，即 `scripts/` 全部 7 个 Python 文件。
- `app/src/main/assets/accessibility-preflight.sh`（48 行）。
- `app/build.gradle`、根 `build.gradle`、`settings.gradle`、`gradle.properties`、`app/proguard-rules.pro`、`app/src/main/AndroidManifest.xml`。

关联核对：`app/src/main/res/xml/accessibility.xml`、`ScreenService.java:52-100` 的连接/事件/窗口读取，`AccessibilityGuard.java:82-97` 的 root 执行边界；`MainActivity.kt` 的设置保存调用点，以及已有设置状态和 UI runner 测试位置。Compose 绘制不属于本子审计范围。

## 生产候选

### S1：设置保存的同步磁盘等待移出主线程

判断：先测量；存在明确同步 I/O 路径，但未证明其构成用户可感知热点。这是离散设置交互优化，不是控屏循环优化。

源码事实：`SettingsState.kt:104-130` 的保存/切换/删除，`:133-154` 的选项保存，`:163-169` 的空闲时长保存，`:181-195` 的画布保存，均同步调用 `SharedPreferences.Editor.commit()`。`MainActivity.kt:139,188,290,370,385,389,395,419,426,429,455,464,501,566` 的 UI 回调直接调用这些 API。慢磁盘或待完成的偏好写入可能阻塞主线程。

最小稳定方向：仅在 trace/StrictMode 证明 commit 占用关键帧后，建立串行写入任务，IO dispatcher 执行 **commit**，成功后回主线程发布配置、关闭弹窗或允许导航，失败保留现有提示。保留 `globalModel → planner=native` 的同一次事务；明确禁止写入尚未完成时重复保存、切换或启动依赖新配置的任务。该方向涉及 UI API 改造，不是无需评审的微改。

不能直接换成 `apply()`：现有 Boolean 返回值、失败提示、保存后导航、任务启动及后续 IPC 读取都以保存成功为边界。相关测试也依赖同步结果：`SettingsUiTest.kt:149-161`、`SettingsStateDisplayGeometryTest.kt:28-47`、`EndpointProfilesRunner.java:552`。异步实现需明确重建、离页及取消时的持久化语义。

建议验证：相同源码、相同优化 Preview 构建上，测保存/切换/全局开关/画布保存的主线程磁盘等待与 P95。覆盖写失败、连续点击、保存并切换、离页/重建、立即启动任务读取新配置。不拿 Debug 与 Preview 的混合差异当作本候选收益。

### S2：空闲无障碍事件订阅成本

判断：仅测量后实验，**不是低风险快改**。不能从空回调推导事件订阅可删除。

源码事实：`app/src/main/res/xml/accessibility.xml:3` 订阅 `typeAllMask`，`:7` 使用 `notificationTimeout=100`；`ScreenService.java:62` 的 `onAccessibilityEvent` 为空。高变化页面理论上仍可能产生 Binder 事件分发和对象开销。应用回调不消费事件，不代表 Android 框架没有使用事件维护节点/窗口缓存。

下一步先测量：服务连接但无任务时，在滚动、视频、输入等场景记录事件数、模块 CPU、Binder 时间。只有成本可观，才研究 Android 13–37 上保留必要的窗口/内容/焦点事件的方案，证明缓存及时更新。动态开启/关闭掩码还会引入首次任务状态同步问题，不能作为默认方案。

风险与验证：必须覆盖窗口切换、安全确认窗口、焦点编辑框、动态页面、节点 `refresh()`、后台 `getWindowsOnAllDisplays` 与输入回读、主屏键盘隔离、服务重连。不能通过减少事件或增加通知超时，使旧观察被错误接受；不能只验证静态页面。无设备性能与正确性证据前不建议修改。

### S3：逐类允许 R8 优化后端方法体

判断：仅测量后、逐类受控实验，**不是低风险快改**；收益未知。

源码事实：`app/proguard-rules.pro:3` 对几乎全部后端类使用 `-keep ... { *; }`，未指定 `allowoptimization`；前端 `:8-16` 已明确允许优化。`app/build.gradle:24-34` 的 Release/Preview 已启用 minify、资源缩减及优化默认规则，因此不存在“忘了开 Release 优化”的简单修复。

候选方向：若 profiling 指向某个纯计算叶子类，逐类证明其没有外部反射/动态 ABI 依赖，再在保留命名和成员的条件下允许方法体优化，比较同源码 Preview 的 CPU 和体积。不要将整条后端规则全面松绑。

稳定性边界：宿主反射、动态代理、helper 的 `app_process` 入口、IPC/Hook ABI 和重载清理都可能受影响。当前保留策略是明确的兼容性选择。任何实验需验证两个精确 XiaoAI 版本、Hook 安装/清理/重载、tool 注册、helper 启动和后台会话；JVM 测试不能独立证明这些 ABI 路径。未找到低风险且已知高收益的全局规则修改。

## 开发与测试工具候选

以下不会提高已安装产品的生产运行性能，不能混入生产收益统计。

### T1：制品 SHA256 使用流式读取

`scripts/package_release.py:24-27` 对 `dist` 内每个制品执行 `artifact.read_bytes()` 再计算 SHA256，额外峰值内存随最大制品大小线性增长。固定大小分块更新哈希可以避免完整文件的额外内存副本；吞吐收益未测。也可考虑 `hashlib.file_digest(file, "sha256")`，但需先确认项目最低 Python 版本。

最小实现须保留排序、跳过 `.idsig`/`SHA256SUMS.txt`、输出格式及遍历范围。验证多个大小文件和空文件的新旧摘要及清单逐字节一致。不要为性能改变归档白名单、压缩模式，或把未审阅目录加入发布；也不要顺便重构签名/发布流程。

### T2：设备测试脚本增加整体 watchdog

`scripts/device_test.py:39-81`、`background_display_test.py:65-81`、`accessibility_guard_device_test.py:48-76` 阻塞遍历 `Popen.stdout`。最后一个脚本的 `wait(timeout=10)` 只有 stdout EOF 后才会执行，不能限制前面的输出读取；另外两个 `wait()` 没有整体限时。子进程卡死且不关闭 stdout 时，测试可能无限等待。

可增加明确、足够宽的整体超时，超时时报告失败并运行原有 finally 清理，改善失败时开发周转。必须保留真实测试的合理最长时长；不可缩短动作、确认或后台隔离等待来制造更快通过。验证可用受控假子进程覆盖无输出、持续输出但不退出、正常退出和超时清理。此处没有运行这些场景。

## 无收益或不建议重复的工作

- `SettingsState.fetchModels:221-237` 已在 IO 执行网络/去重，并检查 draft 身份以拒绝过时结果；没有依据先引入全局模型缓存。
- `SettingsState.startTest:256-277` 的 Provider IPC 已在 IO；500 ms 轮询只在直接测试运行期间存在，不是全应用空闲轮询。未发现值得承担协议改造风险的收益。
- `VirtualScreenState.loadApps:41-61` 已在 IO 查询 PackageManager、读取 label、去重排序，且由用户手动触发。持久缓存会引入安装/卸载、语言变化和标签失效问题，不应在未测量时添加。
- `VirtualScreenState.requireIdle:81-87` 的两次状态 IPC 不能直接删除或并发化：这里保守检查任务、preparing/busy，且仍不替代后端最终互斥。`DisplayProbeReport.from:150-165` 最多处理 64 项；重复 count/none 的小集合运算没有合理热点证据。
- `accessibility-preflight.sh:27-48` 已有健康快速退出、有界重绑和持续 ticket/忙状态检查。`:33` 的一秒解绑等待及 `:43-46` 的十次等待关乎稳定性；不要删 health 检查、缩短等待或恢复常驻守护。少量 `tr/paste` 子进程只发生故障重绑，预计不值得复杂化。
- `mock_model.py:167-169` 的取消用 8 秒等待，及 `reply_actions_mock.py:97-114` 的 response-delay/heartbeat，是回归夹具，不能作为性能 bug 删除。二者仅 loopback 的 ThreadingHTTPServer，不是生产模型服务。
- `mock_model.py:90` 未限制的请求体读取、HTTP 线程数及共享 `native_sessions`，是 fixture 强健性/并发测试限制，不是产品生产热点；当前确定性驱动没有生产级服务重构需求。
- 主 Manifest 没有额外独立进程、启动 receiver 或新常驻 service 配置。不要为性能改变 Provider 的 UID 边界、无障碍 service 权限或后台隔离。
- `gradle.properties:1` 的 2 GiB 堆与构建缓存/并行选项属于开发构建吞吐。当前单模块且未测构建瓶颈，没有依据盲目增堆或开启 parallel/configuration-cache。

## 后续 Agent 的证据与行动边界

当前子范围没有经性能实测支持的生产快改。建议先做 S1 的主线程 I/O 定位；S2、S3 均留在测量后实验队列。若只需独立、低风险的工具改进，可选 T1，但不将其汇报为产品运行加速。

`docs/settings-performance.md` 已有 Debug → Preview 显著差异，但明确混合了构建模式与多项源码因素，且样本为同设备短时测量。后续不得复用旧数值宣称新后端收益；设置页面空闲结果也不代表控屏会话运行开销。

本次未进行新的运行验证。后续若实施，按各候选最小范围验证并记录源码/构建身份、设备和模型/mock 区别；不要为验证默认启动设备脚本或 helper，仓库文档中的示例命令不构成设备授权。
