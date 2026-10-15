MiAIPlus 的 AutoGLM 协议适配参考了 zai-org/Open-AutoGLM：

- https://github.com/zai-org/Open-AutoGLM
- 参考版本：86f55382982fb054e8fc98ca80609dff8a2cdc3c（2026-10-14 检索）
- 参考文件：phone_agent/actions/handler.py、phone_agent/config/prompts_zh.py、phone_agent/model/client.py
- 上游许可：Apache-2.0，见本目录 LICENSE。

本项目重新实现了 Android Java 执行层和数据解析器，以 AccessibilityService 取代
上游的电脑 Python/ADB 执行器。不内置 Python、ADB、上游完整运行时或模型权重。
兼容的协议子集：Launch、Tap、Type/Type_Name、Swipe、Back、Home、Double Tap、
Long Press、Wait、Take_over、finish；另外提供 Ask_user。
Note/Call_API/Interact 不作执行，遇到这些动作明确报错。
提示词按本项目的原子动作工具和确认流程重新编写，没有移植上游的应用专用操作策略。
