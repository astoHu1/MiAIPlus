# 通用故障诊断收尾（2026-11-10）

[返回验证摘要](../validation.md)。基于 `e3093ba`，审查并完善已有详细日志工作区改动；本页区分本地检查、固定 mock 实机回归与尚未复现的用户问题。验证产物位于忽略目录 `work/diagnostics-20261110/`，不随源码提交。

## 改动与审查发现

旧日志主要覆盖控屏开始后的执行链；入口沿用小爱、工具目录不可读、Hook 安装失败或小爱在工具执行前拒绝时，可能没有可用的控屏事件。本轮增加文字／最终 ASR 路由原因、模型入口工具计数、宿主错误与支持检查、任务拒绝／结束／取消来源；不改变路由规则、宿主支持条件、权限或安全门禁。

结构化安装／重载历史状态即使详细日志关闭也保留；ZIP 的 schema 2 环境增加宿主精确版本适配、框架／加载状态、ROM 公开 build 信息、配置布尔值／固定选项、工具数量、桥接、无障碍及进程退出原因。未知字段和自由文本被过滤，不导出密钥、端点、用户正文或系统退出描述。

审查中修正了日志关闭时仍反射读宿主对象、异步事件二次排队、调用时间／trace 丢失、缓存更新中的关态竞态、旧 generation 回调及活动观察器未参与热重载忙检查的问题。宿主观察读取和新增日志 IPC 在有界队列中执行，回调异常不能影响原结果；排队工作可丢弃，已执行观察须完成后才能退休该 generation。

发现并修正事件参数覆盖保留字段：截图采集方式／helper 来源及取消原因曾覆盖 `source=module|host`，使筛选和来源统计失真。改用 `captureSource`／`helperSource`／`cancelSource`，保护基础身份字段；历史未知来源单列，不猜归属。`writer` 是每代日志实例的随机号，避免同 PID、同版本热重载后的 `seq` 重新计数混淆。

新增离线 `scripts/diagnose_log.py`，读取 ZIP／JSONL，不落地解压或联网；限制容量，汇总固定元数据及故障链，按 `messageRef` 给出受信任本地源码候选，不将缺失记录、旧报告或单个错误码解释为确定根因。

## 本地检查

JDK 25，仓库指定 Gradle／AGP／Kotlin 与 SDK 37：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:lintRelease :app:assembleRelease :app:assembleDebugAndroidTest :app:assemblePreview
python3 -m unittest discover -s scripts -p 'test_*.py'
git diff --check
```

最终 602 项 JVM／Robolectric 测试通过，0 failures/errors/skipped；Python 21 项通过，其中离线诊断 14 项。完整 Debug Lint 为 0 errors／38 warnings／1 hint，Release 为 0 errors／27 warnings／1 hint；Debug、Release、Preview 与 Debug AndroidTest 构建通过。最终构建记录 `reload-checks.log`，Python 为 `python-final.log`。未跳过完整 Lint；Release 的 vital 任务被 Gradle 跳过不代替已经执行的 `lintRelease`。

新增行为覆盖异常解包／调用点／正文隔离、环境和安装状态白名单、损坏配置、未知来源、保留字段、首次取消原因、路由快照与过期、关态不读宿主、非阻塞 IPC、异步顺序与 trace、generation 清理及诊断观察期间拒绝热重载。

收尾测试曾发现两项异步测试失败：一个是 stamp-first 缓存刷新期间主线程把后续回调误判为关闭，删除该提前返回；另一个错误地要求调用前取得的墙钟与实际入队时间毫秒级相同，改为断言入队窗口及同一回调内一致。修复后上述最终全套通过；失败日志保留于 `final-build.log`。

## 已授权实机，固定 mock

设备 Xiaomi 25019PNF3C／API 37，公开系统构建 `OS4.0.0.18.XOACNXM`，小爱 `8.3.30.4420 (508003030)`。安装 Debug／AndroidTest，串行运行本地 loopback mock 和 ADB reverse，没有调用真实模型。

- `--hook --detailed-log`：实际 controlApp Hook、图片管道、续调、Dyn／直接客户端／记忆替换、SSE、能力与 intent 路由通过。
- 最终代码 `--hook --responses --reasoning --detailed-log`：上述 Responses 路径、官方模式菜单保留和外部推理档位通过，见 `final-hook.log`。
- 最终代码 `--detailed-log`：无需模型配置的原生会话、一次性截图、Phone Use／AutoGLM 截图→点击→中文输入→观察确认→完成及取消通过，见 `final-foreground.log`。
- 最终代码 `--background --detailed-log`：后台应用启动、树、中文输入回读通过，主屏无点击或文字泄漏；2172 次物理输入法采样无丢失，任务结束只剩 display 0，见 `final-background.log`。
- 最终代码 `--dual-tools --detailed-log`：两种旧默认屏幕配置下，实际注册工具、前后台启动、跨工具续调拒绝且原会话保留、后台点击／滑动隔离、helper／display 正常回收通过，见 `final-dual-tools.log`。
- `--no-detailed-log` 的业务用例通过；核对新增记录，关闭期间无动作／会话／模型记录。原开关是开启，Instrumentation 初始化在覆盖开关之前可写启动事件，恢复后也可继续写，不能以整轮文件哈希不变为验收条件。首次用哈希比对的辅助断言因此失败，改用事件与开关窗口核对后通过，见 `final-disabled-verified.log` 与 `disabled-event-summary.json`。
- `--diagnostics` 读取现存真实记录，不清空或合成日志；导出 ZIP 并读回 6116 条有效 JSONL，模块 3845、小爱 1862、历史未知来源 409，含本轮身份字段的双来源及实际模型入口。schema 2、宿主精确适配、`ready`、可选观察器缺失项、来源统计及未泄露测试密钥的断言通过。ZIP 拉到电脑后由离线脚本完整解析，0 损坏行；导出时无障碍连接为 false，环境如实保留该状态，未假报健康。见 `diagnostics-export.log`、`offline-analysis.txt`。

宿主结构化安装状态为 `ready`、73 个 Hook、0 个缺少的 UI guard，三个可选诊断观察器均安装成功（`optional` 为空）。这证明绑定可安装，不代表已经复现线上拒绝。

结束后覆盖安装最终优化 Preview，按 APK SHA-256 核对设备安装内容与本地最终产物一致；正常启动小爱后上报 `ready`、74 个 Hook、0 个缺少的 UI guard，三个可选观察器均在。业务偏好逐项比较不变，只有安装诊断状态与时间更新；原无障碍列表精确恢复，服务健康连接、任务空闲、仅 display 0。测试 ZIP 的唯一设备目录、本轮 ADB reverse 和自有 mock 进程已清理，原有日志保留。

## 提示来源与边界

只读核对本地对应 APK：8.3.30 的宿主错误入口把 `-1009` 映射到资源 `error_hint_access_denied`，其文字为“当前功能暂未对你开放。”；Agent 普通错误可经映射器转入此入口。`ensureSupported(Context)` 的本地拒绝使用另一条“当前设备暂不支持该功能”。只保留实现判断，没有提交反编译内容。

本轮未在那位 HyperOS 3 用户设备上复现，不能证明他实际收到 `-1009`，也不能认定账号、地区、ROM 或服务端策略是根因。真实宿主错误码采集仍需故障当时的新日志；已安装观察器不等于已触发它。旧版宿主仅核对既有绑定索引，未降级实测。route 是近期时间上下文，不能证明异步错误的请求归属。

未验证真实模型任务质量、声学唤醒／识别、所有 ROM 或长期日志丢失率；没有绕过功能开放限制，也没有创建正式签名发布版本。
