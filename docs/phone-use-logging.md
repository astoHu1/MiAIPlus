# Phone Use 与虚拟屏详细日志

从「首页 → 日志 → 更多」进入，开启或关闭「详细日志」；原「控屏 → 诊断」开关仍同步可用。默认关闭，保存后无需重启小爱；模块进程每条记录读取当前开关，宿主 Hook 通过受现有 UID 校验保护的 IPC 查询，最多缓存 1 秒。可在任务期间切换，不取消或重开任务。开关不改变执行模式、权限、窗口选择、重试次数、输入回读期限、帧有效期或动作结果。

## 收集

无需电脑：

1. 在「首页 → 日志 → 更多」开启「详细日志」，返回小爱复现问题。
2. 回到日志页，可搜索事件、会话摘要或任意元数据，按「模块／小爱」筛选，点击记录展开并复制详情。最新记录在前，显示筛选后最近 500 条；压缩包包含所有保留记录。自动刷新只在日志页可见且 App 在前台时每 2 秒读取，可在右上角「更多」中关闭或手动刷新。
3. 点击右上角保存图标，通过系统文件选择器选择位置；或点击分享图标，生成 ZIP 后由系统分享面板选择接收应用。导出和查看不发起模型请求，也不启动控屏。
4. 复现后关闭开关。关闭只停止新记录，旧日志仍能查看与导出；「更多 → 清空」需确认，会删除 App 内日志和分享缓存，已保存到其他位置的压缩包不受影响。开启时清空后仍会继续记录。

ZIP 包含 `phone-use.jsonl`（按模块收到的顺序、旧记录在前）、`environment.json`（版本、Android API、厂商／机型、导出时间、开关与日志统计）和 `README.txt`，不包含完整配置、设备唯一标识、系统全量 Logcat 或截图。分享只授予所选应用对该 ZIP 的临时读取权限，不公开其他私有文件；最多保留最近 3 个分享包，下次分享清理旧包。

日志保存在模块私有目录，4 个文件各最多 512 KiB，总计最多 2 MiB，超限自动轮转。单条最多 8 KiB。模块写盘和宿主 IPC 使用独立、有界的 256 项异步队列，不在控屏执行线程等待写盘或新增的日志 IPC。宿主经现有 UID 校验向模块追加，不能读取／清空／导出私有日志。热重载清除待传送队列及宿主引用。

队列满、进程退出或存储不可用可能丢失记录；模块丢弃／写入失败计数仅在非零时显示于页面，环境文件始终保留计数，只统计本进程启动或上次清空以来。后续送达的宿主记录带 `hostDropped`／`hostDeliveryErrors` 累计计数，进程重启归零；宿主不能送达时也无法保证留下最后的失败计数。导出取得模块已接收记录的快照，不等待尚未送达的宿主队列。空日志或零计数不能单独证明完整链路成功。

开发者仍可通过已授权 ADB 额外收集 Logcat：

```sh
adb logcat -v threadtime 'MiAIPlusPhoneUse:I' '*:S' > phone-use.log
```

复现结束后用 Ctrl-C 停止收集，并关闭开关。不要按单个 PID 过滤：模块与小爱宿主会分别输出记录。日志使用 `MiAIPlusPhoneUse` 标签和 JSON 行，仍写入系统 Logcat；清空 App 日志不会清除系统 Logcat 或已在电脑保存的文件。

## 记录内容

| 事件 | 用途 |
| --- | --- |
| `run_start` / `session_start` / `step_start` / `step_continue` / `session_close` | 关联入口、原生或外部规划、实际前后台模式、步数、重新规划和等待审计状态 |
| `observation` / `capture_complete` | displayId、显示实例摘要、前台组件、屏幕与 JPEG 尺寸、图片摘要、树字符数、采集来源、helper 帧计数和耗时 |
| `tree_windows` / `window` / `tree_selected` / `tree_empty` | 区分服务缺失、无窗口、根节点缺失／刷新失败、包名不匹配，以及 focused 或包名兜底选择；保留现有选择顺序 |
| `action_proposed` / `action_begin` / `action_complete` / `action_coordinates` | 动作类型、归一化坐标、输入长度、确认标记；`action_complete` 仅表示执行器返回，不证明业务目标达成 |
| `input_candidate` / `input_set_text` / `input_readback` / `input_failed` | 定位输入焦点、写入是否被接受、原节点是否存在、回读是否可读／匹配、超时或异常；前台保留原先仅校验写入接受的行为，不额外添加回读 |
| `helper_request` / `helper_reply` / `helper_error` / `screen_handoff` | helper 命令类型、耗时、错误码，以及原有规则允许的屏幕切换；不输出 RPC 参数或整份响应 |
| `frame_stored` / `frame_read` / `frame_read_failed` / `frame_expired` / `frame_evicted` | 帧摘要编号、有效期、读取年龄、排队淘汰、过期、UID 不匹配、缺失或已消费 |
| `image_read_begin` / `image_read_complete` / `image_attached` / `image_attach_failed` | 宿主读取截图及向 ToolResult.Success 或 TOOL Message 附图的路径与数量 |
| `request_image` / `model_request` / `model_http_response` | 模块外部模型及受控原生外部适配器序列化后请求中的图片摘要与数量；可将发送图片关联回观察 |
| `failure` | 失败阶段和异常类型；不输出异常消息／堆栈，以免带出正文或接口响应 |

同一任务使用 `session` 摘要和 `step` 关联；每次真正采集观察生成新的 `observation` 编号，并通过帧管道传给宿主附图日志。主线程无障碍读取沿用调用线程的诊断上下文。记录另含墙上时钟 `time`、单调时间 `elapsedMs`、`pid` 和 `source=module|host`，宿主异步传送可能晚于模块后续事件，可结合时间和观察编号判断顺序。`frameRef` 是一次性 token 的 SHA-256 前 8 字节摘要，不是可用于读取截图的 token。`imageDigest` 同样是图片 Base64 的 SHA-256 前 8 字节摘要。最近 32 个图片摘要只在内存用于关联外部请求，不保留图片副本。

不记录用户目标、页面正文／完整树、输入内容、操作说明、确认／审计 token、图片数据、服务地址、模型名、API Key、HTTP 头或完整请求。会记录目标应用包名／组件、窗口状态、动作类型、坐标及长度等诊断元数据。

## 判断故障

先对比同一会话中最后正常与首次异常的观察：

1. `tree_empty` 时检查 `tree_windows` 和各窗口原因，区分无窗口、过期根节点和筛选失败。`foreground` 为空不等于截图也为空。
2. `input_set_text.accepted=true` 后仍失败时，查 `input_readback` 的 `nodePresent`、`readable`、`matches` 与最终原因，区分节点重建、不可读及内容不匹配。
3. 沿 `frame_stored → frame_read → image_read_complete → image_attached` 查图片传递。`missing_or_consumed` 本身不能区分从未存在与已经消费，需要结合该摘要之前的存取记录。
4. 使用受控外部模型时，将 `observation.imageDigest` 与 `request_image.imageDigest` 对照。工具中附图成功不能单独证明最终请求含有该图。

官方模型的私有网络传输不在受控外部适配器中，日志只能确认到宿主图片附加，不能据此证明官方服务已收到图片。请求日志也不能证明模型理解了图片。图片摘要不能证明画面不是黑屏、旧画面或错误页面；这类情况仍需在授权的复现场景单独核对实际后台截图。现有截图／输入／窗口查询不因日志开启而增加重试或物理屏回退。

## 设备回归

按仓库设备测试流程准备本地 mock、ADB reverse 和 Debug／AndroidTest APK，安装后先等更新通知消退、收起通知栏，避免悬浮通知拦截坐标测试。设备须解锁且测试期间独占屏幕。`scripts/device_test.py` 支持 `--no-detailed-log`、`--detailed-log`，可与 `--background`、`--dual-tools`、`--hook`、`--responses`、`--profiles` 组合；测试结束恢复原日志选项。默认用例在原生会话中关闭后重新开启日志；双工具用例还在同一宿主进程中等待开关缓存到期后验证持续会话，使用 `MiAIPlusPhoneUseTest` 的 `NATIVE_LOG_OFF/ON`、`HOST_LOG_OFF/ON` 标记划分采集区间。应同时核对业务断言和日志，不能仅凭测试输出了几行日志判断通过。

完成开启日志的前台／后台和宿主用例后，可运行 `python3 scripts/device_test.py --logs` 检查首页日志页、筛选／展开、真实双来源持久记录、URI 导出 ZIP、文件选择／分享面板和清空；此用例会清空本轮保留日志及分享缓存，须只在已授权且无需保留这些记录的测试设备上运行。测试恢复原日志开关。
