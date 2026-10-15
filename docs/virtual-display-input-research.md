# 虚拟屏输入与截图调查补充

日期：2026-10-15。权威交接规格见 [virtual-display-implementation-spec.md](virtual-display-implementation-spec.md)，下一 Session 入口见 [NEXT-SESSION-VIRTUAL-DISPLAY.md](NEXT-SESSION-VIRTUAL-DISPLAY.md)。本文整合并纠正并行调查中的冲突，不代表设备实测结果。

## API 与权限核对

| 路径 | 能否指定 display | 权限/限制 | 建议用途 |
|---|---|---|---|
| Accessibility `dispatchGesture` | **API30+ Builder.setDisplayId 可以** | 用户启用无障碍；跨屏用户手势取消、焦点与 OEM 行为仍要测 | 首期候选触摸适配器 |
| Accessibility `takeScreenshot` | API30+ 可以 | 截图能力、可访问显示、FLAG_SECURE/OEM 限制 | 按需目标屏截图 |
| `getWindowsOnAllDisplays` | API30+ 按显示枚举 | 目标窗口不一定有树；不能默认屏 fallback | 树、焦点节点与文字输入 |
| 节点 `ACTION_SET_TEXT` | 节点所属窗口决定目标 | 必须验证 window/display 与 editable/password | 中文输入，不借全局剪贴板 |
| `performGlobalAction(back/home)` | 无可靠目标 display 参数 | 可能影响当前物理屏 | 不用于后台导航 |
| helper `InputManager.injectInputEvent` | 设置事件 display | 隐藏 API、INJECT_EVENTS、调用身份和 SELinux；不能普通 APK 反射即获得权限 | 定向按键与特权触摸 |
| `adb shell input` | 目标版本可有 `-d` 等选项 | 语法先看设备 help，普通 app 执行 shell 命令不会自动变 shell UID | 开发验证，非产品外部依赖 |
| MediaProjection | 输出采集 surface | 授权/FGS/token；不负责独立承载 App 或输入 | 仅实际需要投影时选择 |
| own-content VirtualDisplay + ImageReader | 独立输出 | 第三方 App 启动、trusted/focus 权限另行解决 | 推荐本地虚拟显示后端 |

本项目 minSdk33，API<30 无定向 gesture 不构成当前兼容性问题。关键不是手势 API 存不存在，而是创建、启动、截图、树、输入、焦点、IME 和销毁全部来自同一显示且用户前台不受影响。

## 推荐执行边界

- `ScreenBackend` 的 `DisplaySession` 固定 displayId、generation 和 owner，模型只提交本会话动作；不得任意传系统显示 ID 获得控制权。
- 物理屏继续走现有无障碍后端；虚拟屏先探测公开定向 gesture，缺少键盘/焦点能力时使用受控本地 root/shell helper。
- 输入失败返回 `UNSUPPORTED_DISPLAY_INPUT` 或准确原因；绝不静默退到默认屏。
- 截图与树绑定窗口和旋转，采集前后校验；持有 ImageReader/HardwareBuffer 时必须及时释放。
- 当前三坐标空间转换已接入 NativeSession，外部 AgentEngine 仍使用归一化动作；FrameStore 已读取真实 JPEG 尺寸。目标后端统一保留 image_pixels/screen_pixels/normalized 三种输入，通过 frame 的真实 JPEG 与逻辑尺寸做唯一转换，不假设 JPEG 等于 display 分辨率。
- 60秒截图 token TTL 与坐标有效期是两回事；执行前再观察，不用未测量的固定2秒过期策略阻断真实模型推理。
- 取消阻止后续动作；已经提交的事件结果可能 UNKNOWN，恢复先观察而非重放。
- 显示生命周期需要 helper 失联 watchdog，清理时防止后台 task 被迁回物理屏；不 force-stop 用户原有应用进程。

## 必须避免的误判

1. LSPosed 只注入小爱不等于拿到 system_server 权限；manifest 声明 INJECT_EVENTS 不会授予 signature 权限。
2. MediaProjection 不等于后台应用隔离；Presentation 不等于任意 App 容器。
3. `OWN_FOCUS`/trusted 等标志需要平台适配，存在不等于目标 ROM 可用。
4. 默认 IME/全局返回/启动已有 singleTask Activity/销毁迁移都可能干扰主屏，即使触摸明确发到虚拟屏也不能忽略。
5. ADB `screencap -d` 的显示标识不一定是逻辑 displayId，需按版本核对；不要照抄编号。
6. 不要绕过锁屏、FLAG_SECURE、密码或系统授权以证明能力。

## 可核对的一手来源

- [GestureDescription.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/accessibilityservice/GestureDescription.java)
- [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [InputManager.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/input/InputManager.java)
- [scrcpy virtual display](https://github.com/Genymobile/scrcpy/blob/master/doc/virtual-display.md)
- [scrcpy NewDisplayCapture.java](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java)
