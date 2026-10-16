# 模块配置界面

在独立工作区的 `ui/miuix-settings` 分支开发。本次导航调整只修改前端及其测试、文档，没有操作 main 工作区或手机。

## 页面

底栏固定按「首页 → 模型 API → 控屏」排列，使用 Miuix 原生 `NavigationBar` / `NavigationBarItem`。

| 页面 | 内容 | 保存方式 |
| --- | --- | --- |
| 首页 | KernelSU 式状态卡、模块版本、无障碍状态、当前控屏配置 | 只读总览 |
| 模型 API | 配置切换、地址与密钥、模型列表、API 协议、外部对话开关 | API 配置显式保存；切页时可保存、放弃或取消 |
| 控屏 | 启用开关、规划模型、动作协议、最大步数、后台屏、权限 | 即时保存；步数限制 1–80 |
| 运行状态 | 注入报告、框架与热重载、权限、后台屏检查 | 首页进入；后台屏仅点击时查询 |
| 直接测试 | 外部模型控屏与纯对话 | 运行状态页进入；只用已保存配置 |

首页的注入结果来自持久化报告，注明「上次」并显示报告时间，不将历史报告或控屏开关显示为实时连接。无报告时提示启用模块并重启小爱。

二级页进入和返回有 260 ms 的短距离滑动与淡入淡出，仅改变绘制层的位移和透明度，不使用整页尺寸过渡或 lookahead。结束即释放旧页面；退出页面不接收点击或无障碍焦点。底栏切换直接完成，各页保留滚动位置，系统返回逐级退回。首页和表单无定时刷新；诊断页前台每 2 秒更新。

控件、圆角、间距、字号和颜色沿用 Miuix 0.9.4，跟随系统深浅色，内容最大宽度 680 dp。删去重复说明，保留保存边界、数据发送和执行条件等必要提示。

## 配置与兼容

草稿在 ViewModel 中保留，不把密钥写入 SavedState。密钥默认遮挡，离开页面或切换配置后重新遮挡；继续使用 `FLAG_SECURE` 与应用私有存储。服务地址、密钥、模型默认仍为空。未保存时，外部对话开关暂不可更改，避免将草稿与已保存服务混用。

沿用 `Config`、`EndpointProfiles`、`AccessibilityGuard`、`HotReload` 和 `BridgeProvider`，不修改 Hook、模型协议、执行器或后台屏实现。开启外部模型前验证已保存配置；保留对话数据发送确认。后台屏检查只查询 root 和锁屏前置条件，不创建屏幕，也不代表完整兼容性验证。

## 验证

`SettingsUiTest` 在 Robolectric Android 35 上检查底栏顺序、草稿保存/放弃/取消、二级页进退过渡与父页面、历史注入状态、步数边界、模型列表懒加载以及后台屏按需查询。截图位于 `app/build/outputs/ui-screenshots/`，只使用默认值和测试数据。

本轮 92 项 JVM / Robolectric 测试通过，Debug、AndroidTest 与优化 Preview APK 构建通过。Preview 本地构建显式跳过存在基线错误的 `lintVitalPreview`；完整 Lint 单独执行并保留失败结果。仅进行本地构建与测试，不安装手机、不重启小爱、不发起模型请求。此前设备性能结果见 [性能记录](settings-performance.md)，不代表本次重排后的实机测量。

完整 Lint 仍被基线 `helper/DisplayHelper.java` 的三处错误阻塞：144、238 行 `WrongConstant`，229 行 `BlockedPrivateApi`。本次不修改后台文件或添加屏蔽规则。

## 截图

| 首页 | 模型 API | 控屏 |
| --- | --- | --- |
| ![首页](images/settings-light.png) | ![模型 API](images/settings-model.png) | ![控屏](images/settings-control.png) |

![深色首页](images/settings-dark.png)
