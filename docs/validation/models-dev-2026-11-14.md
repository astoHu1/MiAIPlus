# models.dev 推理能力缓存验证（2026-11-14）

本轮基于 main `afa04be253a7d047bdc81dbdb5c7336a816300eb` 加工作区改动，验证远端目录、私有缓存、推理策略和小爱宿主 IPC；没有创建正式发布版本。功能与协议边界见 [缓存说明](../models-dev.md)。没有新增设置页、开关、标签或布局，原有推理菜单改为读取配置中的能力快照。

## 本地检查

使用 Gradle 9.6.0、AGP 9.4.1、Kotlin/Compose 2.4.20、JDK 25、SDK platform 37 / build-tools 37.0.0；本机工具链路径通过环境变量提供，不写入仓库。

```sh
# MIAIPLUS_MODELS_DEV_FIXTURE 指向本轮下载、位于忽略目录中的完整公开 api.json。
MIAIPLUS_MODELS_DEV_FIXTURE="$PWD/work/models-dev-20261114/api.json" ./gradlew \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:lintRelease :app:assembleRelease :app:assembleDebugAndroidTest
python3 -m unittest discover -s scripts -p 'test_*.py'
```

- 673 项 JVM／Robolectric 测试通过，0 失败、0 错误、0 跳过；包含实际完整目录解析。新增缓存 9 项、目录 13 项、IPC 2 项，以及原策略新增 4 项。
- 缓存测试覆盖首次获取、有效期、304、失败退避、旧快照保留、磁盘重载、损坏数据、超限和并发排队等；目录与策略测试覆盖精确身份、provider 覆盖、冲突、协议边界及未知能力回退。
- Debug 完整 Lint 0 错误、43 警告；Release 完整 Lint 0 错误、32 警告；上述构建通过。
- 33 项 Python 测试通过。
- 设备探针调整后再次执行 `:app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assemblePreview`，全部通过；生产实现未在此后修改。
- `git diff --check` 通过。

首次构建因 Android `JSONObject` 没有 `keySet()` 未通过，改为 `keys()` 迭代后完成上述完整检查。最初把目录 Runner 声明为独立 instrumentation，但最终 manifest 由 AGP 使用既有 Runner，首次独立入口调用失败；已改为 `DeviceTestRunner` 的 `models_dev` 分支并重新构建、验收，不把失败调用计为通过。

## 真实设备目录与缓存

Xiaomi 25019PNF3C／API 37／小爱 8.3.30.4420，授权 ADB 设备上执行：

```sh
adb shell am instrument -w -e models_dev true \
  io.github.miaiplus.test/io.github.miaiplus.DeviceTestRunner
```

Debug 首次运行实际匿名下载 models.dev 公开目录，HTTP 200，解析 236 个 provider、9,135 个模型。`MiAIPlusModelsDev` 日志依次可见 `cache_missing`、`refresh_started` 和 `refreshed detail=200`。私有 `catalog.bin` 已写入；测试另建缓存 Engine 从磁盘加载同一快照，检查时间与规模一致，未再次调用传输层。

该 Runner 还使用独立合成新模型检查 `Config`、Provider 合法选择保存与过期／非法选择拒绝、Bundle parcel 往返、策略身份，以及 Chat／Responses 最终请求字段。测试结束恢复原目录与偏好并核对一致，没有在此项发起模型请求。

随后安装本轮优化 Preview，在新进程重复该命令通过，日志确认 `cache_loaded detail=disk`。两次公开目录检查时间均为 `1794663602539`，证明 Preview 复用了有效持久快照。

## 实际小爱宿主与 mock 请求

```sh
python3 scripts/device_test.py --hook --remote-reasoning
python3 scripts/device_test.py --hook --remote-reasoning --responses
```

两种协议均通过。测试将合成目录中的 `future-effort-fixture` 配置为当前模型，档位为 default／low／high／max；该 ID 没有内置能力规则。实际小爱宿主断言本进程没有完整目录，验证模块解析后的身份绑定策略经 IPC 生效。

- 原有输入栏、全屏和浮窗推理入口取得远端档位，选择 high 后可持久化；原生 AUTO／QUICK／EXPERT 保持原有枚举语义。
- mock 服务核对 Chat 的 `reasoning_effort=high`、Responses 的 `reasoning.effort=high`，并确认策略要求删除的采样参数没有进入请求。
- 实际模型／记忆覆盖、门禁／能力／意图分类请求、图片、续调与 SSE 文本／工具解析检查通过。
- 每次探针结束恢复原用户配置并核对。

这些结果是“真实手机与实际宿主 + 合成目录／mock 模型响应”的证据，没有发起真实生产模型请求，也不证明所有上游 provider 接受其声明的参数。

## 收尾与产物身份

手机保留本轮优化 Preview，恢复原安装的 AndroidTest APK；重启小爱使其加载当前模块。最终偏好与测试前逐项比较，变化仅 `hook_at`、`hook_status`、`hook_status_at` 运行状态；用户配置一致。原无障碍服务列表及启用状态恢复核对，移除本轮 18765 reverse 映射、停止本轮 mock 服务，最终设备锁屏且屏幕关闭。

本地日志与私有备份位于忽略的 `work/models-dev-20261114/`，不进入提交或分发。APK SHA-256：

| 产物 | SHA-256 |
| --- | --- |
| Debug | `c13b7a6a495c21fbe9aa1edc40a4822a1b2378e224346511fcdfbcea6a312671` |
| Preview | `901a6b2e38b347c256b1003d938a9c8cdda719f17deecf0c9f686ff740b83cec` |
| Debug AndroidTest | `4b8961bb1874bfa6c6116fcb74e4c8ce55da5a47ed31caab71a607ed222ca9e3` |

## 限制

目录获取与磁盘读取均异步，冷启动发布快照前有短暂使用内置规则的窗口。24 小时刷新检查发生于模块启动与配置读取，没有独立周期定时器；失败的 1 小时退避只在当前进程保存。新请求字段、budget-only 及尚未实现的协议仍需 APK 适配。客户端按能力快照缓存，历史组合随既有重载生命周期清理。此次未测试长期后台存活、所有供应商生产接口、旧小爱版本或其他 ROM。
