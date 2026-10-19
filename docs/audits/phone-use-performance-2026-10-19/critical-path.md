# 关键路径、等待和静态计数

返回[交接总览](README.md)。本文件的源码路径均相对 `app/src/main/java/io/github/miaiplus/`，行号对应总览提交。所有成本未做设备测量。

## 原生规划单步顺序

`NativePhoneUseTool.execute` → `PhoneUseRouting.encode` → 宿主 `controlApp` → `XiaoAiHook` → Provider `start` → worker `AgentEngine.run` → `NativeSession.handle` → 观察/检查/动作/新观察 → `FrameStore.put` → worker 完成 → 宿主 poll 消费 → `ToolResult.Success` 或旧 TOOL Message 附图 → 下次模型请求。

| 阶段 | 源码锚点 | 必须分开计量的成本 |
| --- | --- | --- |
| 工具授权/路由/任务建立 | NativePhoneUseTool:97–105；PhoneUseRouting:24,42；BridgeProvider:176–226 | flags、JSON、start IPC、worker 队列、screen_ready；6秒是握手上限，不是固定等待 |
| 首次准备 | NativeSession:70–94；BridgeProvider:218–224 | 可选健康检查、后台建屏、固定800ms、可启动应用枚举；冷态单列 |
| 续步权限与目标检查 | NativeSession:129–203 | Auto 审计、人工等待、推理后的新观察、目标变化拒绝；不同安全时点不能合并 |
| 执行与稳定等待 | NativeSession:205–230 | 注入前现场复核、动作真实时长、固定800ms、末尾新观察 |
| 结果构造和帧能力 | NativeSession:232–252；FrameStore:40–47 | 真实JPEG bounds、JSON/UTF-8、树/提示文本和一次性token |
| 回宿主 | BridgeProvider:227–240,249–256；XiaoAiHook:135–147 | 模块完成→下一poll的相位等待、poll IPC、结果反射构造 |
| 附图/请求 | NativeModelHook:54–103,270–288；NativeResponsesAdapter:58–74 | pipe读取、JSON、ImageContent；外部主模型另有整请求转换；官方私有传输证据有限 |

`NativePhoneUseTool:104` 与 `NativeModelHook:223–225` 都可能调用 `NativeTurnLifecycle.observe`；后者 `:30` 同步 `screen_state`。有识别到 Turn 时可能重复 IPC，先计数，不能未经覆盖所有宿主/CLI 路径直接删前者。

完整观察指获取新截图和对应树，不含注入前仅重新读树：

| NativeSession 分支 | 前观察 | 后观察 | 固定800ms |
| --- | ---: | ---: | --- |
| 首次start | 0 | 1 | 有 |
| 普通tap/type/long_press/double_tap/swipe | 1 | 1 | 有 |
| launch/back/home/wait | 0 | 1 | 有 |
| 首次Auto审计挑战 | 0 | 1 | 有 |
| 消费相同挑战或审计目标过期 | 1 | 1 | 有 |
| 普通前观察判目标变化/注入前拒绝 | 通常1 | 1 | 有 |
| finish/take_over/ask_user | 0 | 0 | 无 |

依据NativeSession:132–174,195–230。swipe虽不做元素锚定，仍在:199–202新观察并校验屏幕/授权；不能按needsFreshFrame推导截图次数。人工确认后:171清空reviewedObservation，必须获得确认之后的观察。

## 400、700、800ms 不能直接相加

| 等待 | 实际作用 | 优化边界 |
| --- | --- | --- |
| 400ms | XiaoAiHook:138–147，poll 未完成后 sleep；直接阻塞工具结果回宿主 | 完成后可能再等一个标称周期，另加IPC/调度；不是每步固定400ms。周期缩小会增加IPC和Config成本 |
| 700ms | InteractionUi:350–386 独立 `scheduleWithFixedDelay` 状态观察器 | 普通动作不串行等它；确认从创建到展示则受它影响。通知已按成功发布状态去重，不是每700ms重建 |
| 800ms | NativeSession:93 首步；:224 所有非终止续步；AgentEngine:181 循环 | 直接在执行路径。真实动作/动画稳定、首次收起、交接与明确无输入分支分别判断 |
| 100ms | ActionConfirmation:50–56、ForegroundConsent:62–66 答复轮询；RunState:73–77 pause切片 | 可事件唤醒以减少答复延迟/空唤醒；不代表可以缩短页面稳定等待 |
| 20ms | BackgroundDisplay:201–216 helper ready轮询 | 每RPC有轮询相位成本；不是helper固定执行成本 |
| 350ms | ScreenService:28,365–413 物理截图限频 | 800ms缩短后可能显露限频；不能直接删除同步/限频 |

理想均匀相位下，400ms轮询残余均值约200ms，5次20ms轮询约50ms；**这些只是模型假设，非基线或收益预测**。调度、IPC和负载会改变分布。700ms固定延迟调度与主线程排队也意味着实际展示延迟可能更长。

## 结果更快发布之前要先处理完成顺序

BridgeProvider:228 先写 volatile `status="done"`，:237–240 才在 JOBS 锁内释放 `active` 并刷新任务；`start:188–190` 同锁检查 active。静态推导存在 poll 已见 done、下一步已开始而上一步 active 尚未释放的窄窗口；本轮未复现。更快 poll/完成通知可能增加触及概率。

P04 设计应在任务清理/active释放后再发布可续调完成信号，连同 error/cancel 分支一起定义；覆盖即时续调、旧任务取消与替换、模块死亡和245秒宿主截止。不能只把 sleep 改短，也不能持 JOBS 锁阻塞等待 worker（会阻止完成）。

## 后台普通续步计数

下表限定 `NativeSession`，普通无审批、无交接、无初始化、无异常、执行成功的一步，含末尾结果观察。每次 helper capture 最后还会 query focus（DisplayHelper:1074→1462）。AgentEngine 规则不同，不能套表。

| 动作 | helper RPC | `dumpsys window displays` | 树读取入口 |
| --- | ---: | ---: | ---: |
| tap / swipe | 5 | 3 | 3 |
| type | 5 | 4 | 至少3，另有写入定位/回读 |
| double_tap | 8 | 4 | 4 |
| back | 4 | 2 | 2 |
| home | 5 | 3 | 2 |
| wait | 2 | 1 | 1 |

依据：NativeSession:195–215,224–230；BackgroundActions:69–109,126–137；BackgroundDisplay:245–250,258–290。tap的典型路径为执行前capture、display、focus、tap、结果capture；两次capture各带树读取，输入前另读树。合并同点display/focus最多省1 RPC，**display不启动dumpsys，不能声称同时省1 dump**。

树读取入口可能返回空或聚合多个window，不代表固定完整节点遍历；helper RPC不含Provider start/ready/poll/state或token读取。

## 帧 payload 的真实路径

- 原生物理：JPEG→Base64（ScreenService:427）→FrameStore Base64解码查真实bounds→image JSON→UTF-8 bytes（FrameStore:40–46）→pipe（BridgeProvider:353–359）→BAOS/UTF-8 String/JSONObject（NativeModelHook:272–280）→Base64 String交ImageContent（:287–288）。宿主这里没有第二次Base64解码。
- 原生后台：helper JPEG文件→byte[]→Base64（BackgroundDisplay:266–271），之后走同一FrameStore链路。
- `ToolResult.Success` 和旧 TOOL Message 是择一路径；前者消费token并替换marker后，后者不会再读同帧，不能计作两次传输。
- 独立外部 AgentEngine 绕过FrameStore/pipe，已只保留最新图和六组历史（:85–98）。原生外部主模型先走FrameStore，再经过NativeResponsesAdapter整请求转换。原生官方历史由宿主管理，本模块附图不证明它会反复上传旧图。
