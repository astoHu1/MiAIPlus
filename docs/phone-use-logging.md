# Phone Use 与虚拟屏详细日志

在设置的「控屏 → 诊断 → Phone Use 详细日志」开启或关闭。默认关闭，保存后无需重启小爱；模块进程每条记录读取当前开关，宿主 Hook 通过受现有 UID 校验保护的只读 IPC 查询，最多缓存 1 秒。可在任务期间切换，不取消或重开任务。开关不改变执行模式、权限、窗口选择、重试次数、输入回读期限、帧有效期或动作结果。

## 收集

在电脑连接已授权 ADB 设备后，开启开关，先开始收集，再复现一次任务：

```sh
adb logcat -v threadtime 'MiAIPlusPhoneUse:I' '*:S' > phone-use.log
```

复现结束后用 Ctrl-C 停止收集，并关闭开关。不要按单个 PID 过滤：模块与小爱宿主会分别输出记录。日志使用 `MiAIPlusPhoneUse` 标签和 JSON 行，写入系统 Logcat，不新增持久日志文件或截图缓存；关闭只停止新记录，不清除已经收集的文件或 Logcat 中的旧记录。

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

同一任务使用 `session` 摘要和 `step` 关联；每次真正采集观察生成新的 `observation` 编号，并通过帧管道传给宿主附图日志。主线程无障碍读取沿用调用线程的诊断上下文。`frameRef` 是一次性 token 的 SHA-256 前 8 字节摘要，不是可用于读取截图的 token。`imageDigest` 同样是图片 Base64 的 SHA-256 前 8 字节摘要。最近 32 个图片摘要只在内存用于关联外部请求，不保留图片副本。

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
