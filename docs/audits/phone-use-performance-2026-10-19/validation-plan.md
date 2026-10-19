# 测量与回归计划

返回[交接总览](README.md)。这是供后续 Session 选用的计划，**本轮未执行任何性能测试或功能测试**，命令示例不授权设备操作。

## 先校准现有指标

| 指标 | 源码锚点 | 不能误读的范围 |
| --- | --- | --- |
| 物理capture_complete.durationMs | ScreenService:352–359 | synchronized方法进入锁后才起算，漏锁排队；含限频、主队列、树、硬件截图、编码及PhoneUseLog.frame，非纯编码 |
| 后台capture_complete.durationMs | BackgroundDisplay:258–284 | 含RPC、JPEG文件读取、树、Base64及frame日志；不含finally文件删除 |
| helper_reply.durationMs | BackgroundDisplay:179–188 | 起点在日志与requestLock之前，含排队、轮询/IO/JSON与helper工作，非纯helper执行 |
| model_request→model_http_response | ModelClient:44–74 | 到HTTP code，不含全部body读取、JSON/动作解析；非完整推理耗时；官方私有传输不套此指标 |
| RealPageGuardRunner.sample | runner:81–95 | 生产PageGuard后又独立VisualRegion旁路验证，整段时间重复计量 |
| device_test总墙钟 | 多数runner轮询70–200ms、固定sleep400–1800ms | 不能用它分辨20ms RPC或数毫秒JSON优化 |

日志开启时frame两次整图摘要会污染capture指标；优化hash导致指标降低，不代表截图后端加快。详细日志关闭/开启分别建基线，开发期低开销trace与产品日志也要分别评估。

## 采样与阶段

固定提交/补丁、同一优化构建类型、设备/ROM/小爱版本、画布、横竖屏、权限模式、页面、日志、mock或模型版本。Debug用于行为回归，性能双方使用同类优化构建，不比较Debug和Preview得出提速结论。先做A/A识别温度/冷启动噪声，冷建屏单列。

建议热态先5次预热、至少100个有效阶段样本，跨3轮分块交替A/B；按真实成本调整样本预算。报告样本数、失败/取消/重新规划、p50/p95和离散度；少量样本不声称稳定p99。真实模型最后做，分离网络/推理、审计请求、人工思考、本地成本与总耗时。

最小trace字段只用单调时间、脱敏task/step引用、枚举和数值，禁止截图正文、输入正文、API密钥和响应内容。阶段至少包括：

1. tool进入→start、ready等待、worker排队；首次preflight/建屏/应用枚举。
2. capture锁排队、main队列、树、截图请求→callback、copy/scale/JPEG/Base64。
3. PageGuard parse、decode、getPixels/ROI；动作分支与实际输入数。
4. helper client锁、dispatch/read/parse、纯helper执行、window dump、frame age与FRAME_LOCK等待。
5. settle请求/实际时长、新观察、FrameStore验证/序列化、pipe/附图。
6. Provider可续调完成→宿主消费、poll/state/registry调用次数。
7. 请求bytes/images/input tokens、adapter/replay CPU/bytes、TTFB与完整completed。
8. 确认创建→成功展示、答复→恢复；取消→worker退出/helper退出；PSS/Java/native峰值、FD、线程、GC和唤醒。

## 最小回归矩阵

以下测试名称位于 `app/src/test/`，仅列可复用入口，不代表既有测试充分或本轮已运行。

| 候选 | JVM/fixture起点与需补边界 | 授权设备重点 |
| --- | --- | --- |
| P01配置分支 | Provider/Config/PhoneUseRouting相关测试；统计构造次数；owner、热切换、旧任务拒绝 | 同任务多步、设置切换、取消与锁屏 |
| P02/P03树 | PageGuardTest、SystemDialogPolicyTest、ScreenServiceTreeTest；旧新算法差分；同树password/disabled/多焦点仍拒绝 | 普通/全部授权包，SecurityCenter三类页，旋转、遮挡、双击第二击；计入口和节点 |
| P04完成通知 | Provider即时续调、先完成后注册、丢/迟通知、cancel/error、进程死、245秒截止 | 原生现代/兼容入口、前后台多步；终态→消费分布、IPC次数，不增busy拒绝 |
| P05物理截图 | ScreenServiceCaptureTest、CoordinatesTest、NativeSessionReplanTest；迟到成功、超时/拒绝/解绑/取消资源回收 | 文本/照片/动态页，1080p/1440p、旋转；最长main slice、总观察p95和峰值 |
| P06后台快照 | DisplayHelperFramesAndGesturesTest；多色非方形、row padding、1280边界、无缩放、关闭重建 | 正常reader/明确回退源分层，大画布；锁等待、frame age、native峰值 |
| P07等待分支 | NativeSessionReplanTest、AgentEnginePermissionTest；明确零输入分支/实际pause，shadow不证明时序 | Auto挑战、目标变化、慢动画、确认/交接；保留后观察、失败/重规划不增加 |
| P08视觉资源 | VisualRegionTest、ActionPermissionReviewTest；两个不同ROI、非8倍数、16/17通道边界、阈值±1、首末块变化 | 匿名容器/旁侧动画/父文本变化；旧新拒绝与实际注入完全一致 |
| P09 RPC/帧 | BackgroundDisplayTest、DisplaySessionTest、RunStateCancellationTest；0/1/5/19/21/50ms回复、半行/EOF/错token/close竞争 | 前后台工具隔离、主屏IME/焦点、helper死、取消/重建；不回落物理屏 |
| P10请求/历史 | ModelProtocolTest、ResponsesStreamTest；1/6/20组历史、opaque replay、Chat/Responses | 1/5/10/20步images/bytes/tokens；官方与受控端点证据分开；真实模型质量另验 |
| P11帧传输 | PhoneUseLogTest、TransportBoundaryTest；并发消费、错误UID/过期、畸形/超大JSON、提前关pipe、伪尺寸、Unicode diagnostics | 1/4 pending，长期在途帧；整个JSON的UTF-8传输字节上限（非JPEG大小）、one-shot与坏图拒绝 |
| P12启动列表 | AppLaunchTargetTest、LaunchVerifierTest；包名优先、alias/歧义、首有效入口、截断边界、卸载/禁用 | 多应用数、系统确认、chooser、主屏任务冲突；query/label主线程时间 |
| P13日志/等待 | PhoneUseLogTest、DiagnosticLogStoreTest、InteractionUiTest、ActionConfirmationTest；中途开关、32项、失败重试、先答后等/取消绑定/extend | 日志关开、Provider失败、长历史、锁屏重发、持续状态；不能丢Trace或旧批准复活 |

每个候选先跑受影响JVM测试，再按仓库要求跑适用Lint/构建；文档更新本身无须构建。不要以历史364项或发布验证记录替代新补丁检查。

## 设备脚本使用注意

未来获得当时设备授权并准备对应APK、mock和ADB reverse后，按改动选择**独立**命令。例如：

```sh
python3 scripts/device_test.py --no-detailed-log
python3 scripts/device_test.py --responses --reasoning --no-detailed-log
python3 scripts/device_test.py --background --no-detailed-log
python3 scripts/device_test.py --dual-tools --no-detailed-log
python3 scripts/device_test.py --robust-screen
python3 scripts/device_test.py --phone-use-terminal
python3 scripts/device_test.py --action-confirmation
python3 scripts/device_test.py --foreground-consent
python3 scripts/device_test.py --launch
python3 scripts/device_test.py --real-page-guard
```

DeviceTestRunner.onStart:103–116按多条 `if ... return` 选择runner；不要堆 `--background --dual-tools --robust-screen` 假装全部执行。Responses/reasoning/日志参数按所选runner实际支持核对。`--real-page-guard`需要人工驱动真实页面，非全自动；`--logs`会清日志，不能当无副作用读取。

scripts/mock_model.py:171–194固定tap→中文type→finish，仅能证明确定性短链路，不覆盖长期历史、复杂动画、Auto审计网络和自主规划质量。真实设备fixture通过与真实模型任务成功分别记录。测试结束清理reverse映射，不触碰并行Agent的进程和产物。

## 验收与停止条件

一个候选需同时满足：测量中的目标成本下降、成功率/拒绝边界不退化、取消/终止不变慢、无额外泄漏/常驻资源失控。优化越接近安全或模型上下文，差分与真实场景证据要求越高。

后续记录包含基线/补丁身份、精确命令、设备/构建/模式、样本量、分布、失败和未覆盖项；追加到新阶段验证文档。不得用少动作、提前超时、更少日志、错误操作或多次重新规划换取看似更短的任务耗时。
