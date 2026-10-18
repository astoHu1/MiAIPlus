# 详细日志与日志页验证记录（2026-10-18）

[返回验证摘要](../validation.md)。本页保存发布构建之后的诊断功能验证，按原记录保留，不表示重新执行过历史检查。记录中的 `work/` 路径是验证当时的本地忽略产物，不随源码分发。

## 日志页精简与 LSPosed 布局参考（2026-10-18）

实际打开已授权手机上的 LSPosed 日志页及更多菜单，参考其顶部图标操作、搜索／分类和日志卡片。首页日志入口移除副标题；日志页隐藏底栏，保存／分享放顶部，详细日志开关、自动刷新、手动刷新与清空收进「更多」。移除常驻操作教学、隐私说明和容量上限说明；保留精简统计与记录状态，仅非零丢弃／写入失败时提示。记录卡片突出来源、时间、会话和等宽事件名，点击仍可展开复制 JSON。后端采集、权限、轮转及 ZIP 内容不变。

364 项 JVM／Robolectric 测试全部通过（0 failures/errors/skipped），覆盖工具栏入口、菜单开关、重建后深浅色、来源筛选、详情及取消／确认清空。Debug／Release 构建、Debug AndroidTest 构建、完整 Debug／Release Lint 通过（0 errors，35／26 warnings）；记录 `work/log-ui-redesign-final-checks.log`。后续仅调整设备夹具，AndroidTest 构建与 Debug Lint 再次通过（`work/log-ui-redesign-runner-final-checks.log`）。合成截图更新为 `docs/images/settings-logs.png` 与 `settings-logs-dark.png`，已人工核对。

同一 Xiaomi API 37 手机安装新版 Debug／AndroidTest，使用原本为空的日志目录填入 10 条明确标记的合成双来源元数据，未请求模型或启动控屏。完整日志 UI 流程通过：菜单开关、搜索／筛选、展开详情、URI 导出、系统文件选择器实际保存 ZIP 并读回校验、分享面板、取消／确认清空及分享缓存清理。仅打开分享面板，未选择接收方。最终 `work/log-ui-redesign-device/logs-verified.runner.log`；此轮不声称重新验证真实宿主采集或前后台控屏。初轮夹具对已选中菜单项的无障碍点击识别失败，另一次模糊匹配把「刷新」匹配到「自动刷新」；已改为前缀匹配，并对未暴露 ACTION_CLICK 的选中项注入该节点中心触摸，最终开关持久化断言通过。失败记录保留在忽略目录。

测试恢复日志开关为关闭，合成记录／分享缓存及唯一命名的下载测试包清除，未修改执行配置、显示参数或 ADB reverse。仪器测试退出后的模块无障碍服务已单独重新绑定，保留其他服务与原列表；仅 display 0。


## 首页日志查看与 ZIP 导出（2026-10-18）

首页新增「日志」入口，沿用默认关闭的详细日志开关，支持 App 内查看、事件／会话／元数据搜索、模块／小爱来源筛选、展开复制、可关闭的前台自动刷新、保存／分享 ZIP 及确认清空。模块与宿主元数据通过独立有界队列汇总到模块私有轮转日志（最多 2 MiB）；宿主追加 IPC 沿用 UID 校验。ZIP 只包含元数据 JSONL、白名单环境信息和说明；FileProvider 仅开放专用分享目录并临时授权读取。默认执行逻辑、权限、取消、观察、输入和一次性帧不变。使用与丢失边界见 [详细日志](../phone-use-logging.md)。

最终 364 项 JVM／Robolectric 测试通过（0 failures/errors/skipped），新增覆盖整条记录轮转／进程重建后读取、UTF-8 与单行容量限制、有界队列溢出不阻塞、存储失败计数与恢复、ZIP 布局／隐私／清空顺序、Provider UID／开关边界、禁用后保留旧日志和首页筛选／展开／取消／清空。Debug／Release 构建、Debug AndroidTest 构建、完整 `lintDebug`／`lintRelease` 通过（0 errors，35／26 warnings），最终记录 `work/log-viewer-final-actions-checks.log`；后续仅测试夹具补强另通过 AndroidTest 构建和 Debug Lint（`work/log-viewer-saf-fixture-checks.log`）。合成浅色截图 `docs/images/settings-logs.png` 和实机深色展开页已人工核对。

授权 Xiaomi 25019PNF3C／API 37／小爱 `8.3.30.4420`，实际安装 Debug 与 AndroidTest 并串行验证，本地固定 mock，不请求真实模型：

- 日志关闭前台用例 0 条；开启 141 条；虚拟屏 219 条；两个注册宿主工具 425 条；Chat／Responses 各 36 条。原控屏业务断言、同一会话开关的关闭区间、后台输入／主屏隔离和正常回收均通过；私有文件中确认实际宿主 `image_attached` 持久化。
- `python3 scripts/device_test.py --logs` 完整通过：首页入口、开关持久化、搜索／来源筛选、展开详情、模块与宿主记录、URI ZIP 导出、系统文件选择器实际保存 ZIP 并从下载目录读回校验、分享面板、取消清空／确认清空及分享缓存清理。校验包内有两端记录且不含测试密钥／测试输入；没有选择分享接收方或对外发送。唯一命名的测试下载文件已删除，日志开关恢复。
- 原设置完整回归通过：导航、配置草稿／保存／切换／删除、重建、键盘与保存按钮、API 弹窗背景不变、全局规划器锁定、权限三档、步数边界与直接测试导航；恢复并逐项校验原配置。

初轮设置夹具直接假定模型字段可见，日志夹具把搜索框同名文字当成日志行；已改为滚动寻找可见元素、排除可编辑节点并收起键盘。真机另发现自动刷新期间临时禁用导出按钮，已改为操作排队等待读取，最终日志流程通过。失败尝试保留在忽略目录，最终日志页结果 `work/log-viewer-device-20261018/logs-saf-final.runner.log`，设置结果 `settings-final.runner.log`，控屏及汇总见同目录 `summary.json`。最终日志默认关闭，用户配置恢复，ADB reverse 无残留，helper 为 0、仅 display 0、无障碍已连接且任务空闲。

本次未请求真实模型，未复现原先间歇性故障，不将诊断功能视为故障修复；未安装正式签名 Release 或创建发布版本。

## Phone Use 与虚拟屏可选详细日志（2026-10-18）

「控屏 → 诊断 → Phone Use 详细日志」默认关闭，保存后无需重启，可在任务间或任务期间开关；模块即时读取，宿主只读 IPC 的开关缓存最长 1 秒。记录元数据到 `MiAIPlusPhoneUse` Logcat 标签，覆盖前后台会话／观察、窗口选择与空树原因、动作、后台输入精确回读、helper RPC、帧有效期／消费和宿主附图；受控外部请求可按图片摘要核对实际序列化内容。会话／步骤／观察关联信息随内部帧管道传递，不进入用户正文。不记录页面正文、输入内容、图片、密钥、服务地址、完整请求或异常消息。执行模式、权限、重试、回读期限和一次性帧约束不变。官方私有传输只验证到宿主附图边界，不能据此证明服务端收图或模型理解。使用方法见 [详细日志](../phone-use-logging.md)。

本地 356 项 JVM／Robolectric 测试全部通过（0 failures/errors/skipped）；新增 11 项覆盖默认关闭与运行中切换、宿主开关刷新及 IPC 不可用、跨线程诊断上下文、正文／异常脱敏、日志后端异常时仍正常交付帧、日志开关两态下截图内容／单次读取不变、过期及 UID 拒绝、Chat／Responses 图片关联、窗口树和拒绝输入行为不变，以及设置持久化。设置页截图 `app/build/outputs/ui-screenshots/control-phone-use-log.png` 已渲染并人工核对。Debug／Release 构建、Debug AndroidTest 构建、`lintDebug`、`lintRelease` 全部通过，未跳过 Lint；`git diff --check` 通过。最终生产代码检查记录在忽略目录 `work/phone-use-log-host-context-final.log`；AndroidTest 夹具后续改动另通过 `assembleDebugAndroidTest` 与 `lintDebug`。

授权 ADB 实机：Xiaomi 25019PNF3C／xuanyuan、Android API 37、已解锁 root、小爱 `8.3.30.4420 (508003030)`，安装 Debug／AndroidTest APK 并串行测试，本地固定 mock，不请求真实模型。当前通过：

- 关闭日志的 Phone Use／AutoGLM／原生会话：截图→点击→中文输入→新观察确认→结束，以及取消和帧单次消费；收集区间详细日志为 0 条。
- 开启同一用例：141 条元数据记录，模块原生会话中关闭后重新开启日志，关闭区间 0 条；帧诊断字段随实时开关变化，输入／目标／测试密钥未出现在详细日志中。
- 实际虚拟屏：219 条记录；后台输入中文在后台树中确认，3 次编辑框点击送达；主屏无点击／文字泄漏，持续 2177 次键盘检查未丢失焦点、连接或可见状态。日志捕捉空后台屏的 `tree_empty/no_windows`，应用启动后树可读，并记录 `input_set_text` 与精确回读成功。
- 两个已注册宿主控屏工具：426 条记录；两种旧版默认屏幕设置下均通过，跨工具续调拒绝不丢失原会话，后台点击／滑动隔离和正常释放；两次同一宿主会话关闭日志，缓存到期后关闭区间 0 条，再开启恢复宿主附图记录。
- 原生外部 Chat／Responses 各 36 条记录；实际 Hook 与模型／记忆／路由模拟请求通过。`ToolResult.Success` 与 TOOL Message 附图，以及序列化请求图片摘要／数量可关联回观察。
- 实际 Compose 设置页完整回归通过：默认关闭、点击开启、持久化、Activity 重建、再次关闭，不启动模型任务；同时通过原配置管理、规划器锁定、权限预设、步数边界和导航检查。截图 `work/phone-use-log-device-20261018/settings-final/control-phone-use-log-device.png` 已核对。旧禁用规划器检查已适配 Compose 合并后的整行语义，生产 UI 无改动。

初轮失败保留在忽略目录：安装后的模块更新通知使坐标点击打开 LSPosed；等待通知消退并收起通知栏后原基线通过。旧双工具夹具假定固定 1080×1920 却未设置尺寸，已改为显式测试尺寸，未修改生产路由。实测还发现 `Application.attach` 时 `getApplicationContext()` 可为空，导致宿主日志被关闭；已改为直接保存传入 Application 的弱引用，并以同一启动时序的单测和宿主实测验证。最终控屏日志与断言在 `work/phone-use-log-device-20261018/final/`，设置页完整复测在 `work/phone-use-log-device-20261018/settings-final/`。每项用例恢复设置并校验；整轮结束仅宿主启动状态时间戳 `hook_at` 更新，用户配置值不变。原无障碍服务列表和 ADB reverse 恢复，helper 进程为 0、显示列表仅 display 0，无障碍已连接且任务空闲。

未调用真实模型，未复现拼多多／抖音的间歇性故障，也不将新增诊断视为修复该故障。未安装正式签名 Release 或发布版本；[发布基线记录](preview-1.0.1.md) 对应此前版本。
