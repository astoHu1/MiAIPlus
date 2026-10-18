# 酷安宣传图 · 2026-10-17

五张 1440 × 1920（3:4）PNG，按文件名顺序发布。`MiAIPlus-coolapk-5-posters.zip` 只包含五张原图；`contact-sheet.jpg` 用于预览。

内容依据当前仓库的 `README.md`、`docs/architecture.md`、`docs/validation.md` 以及 `HostCompatibility`、`NativePhoneUseTool`、`ReasoningPolicy` 和交互确认源码核对。适配版本为小爱 8.2.65.4714 和 8.3.30.4420，模块版本为 1.0.0-preview。

## 设计

暖白、深墨绿、橙色与少量浅绿；使用本机 Hiragino Sans GB 与 Avenir Next 排版。背景屏与灵动岛均为明确标注的功能示意，不是实机截图。没有使用文生图、虚构模型回复或编造任务成功记录。仓库已有界面图包含旧版测试状态，未用于本组宣传图。

宣传图表述实现功能，不把 mock 协议回归、确定动作实测写成真实模型自主任务成功率。保留预发布、设备兼容性、root、精确版本、敏感操作以及观察发送等使用条件。图片不构成新一轮设备验证。

## 复用与修改

`render.py` 为完整排版源文件。Python 需安装 Pillow，字体路径采用 macOS 系统字体。运行 `python3 render.py` 可重新导出五张原图、预览和 ZIP，不修改应用源码。不随图打包字体文件。

## 配文建议

给小爱加了前台控屏和后台虚拟屏，也能接自己的模型。默认用小爱当前模型，不用额外配 API。新版已适配小爱 8.3.30，想试的先看最后一张安装条件。

项目：https://github.com/astoHu1/MiAIPlus
