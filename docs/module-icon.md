# 模块图标

蓝紫色底上的前后两块屏幕与点击指针，表达 MiAIPlus 的屏幕操作能力。前方白色屏幕表示前台控屏，后方半透明屏幕表示独立的后台虚拟屏。图形使用原生矢量资源，没有文字、位图依赖或额外运行时代码。

![圆角、圆形与主题单色图标预览](images/module-icon.png)

`AndroidManifest.xml` 的 `android:icon` 与 `android:roundIcon` 均指向 `@mipmap/ic_launcher`，供桌面、系统应用信息和模块管理器读取。自适应图标由系统裁切；Android 13+ 的 `monochrome` 使用同一前景，主题颜色由启动器决定。前景位于自适应图标中央安全区域。项目最低版本为 Android 13，因此资源放在 `mipmap-anydpi-v33`，无需旧版位图回退。

资源位置：

- `app/src/main/res/drawable/ic_launcher_foreground.xml`：前后屏幕与点击指针。
- `app/src/main/res/values/icon.xml`：背景色。
- `app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml`：自适应和单色图层。

## 更新（2026-10-18）

预览直接取自 Android 矢量路径、透明度和背景色，以 72/108 的静态可见区域模拟圆角、圆形与单色主题。预览不代表特定启动器或模块管理器的实际显示效果。按用户要求，本次仅替换图标和对应说明、预览；没有编译、运行测试、安装或操作设备。上一版图标的编译结果不作为新版的验证结论。
