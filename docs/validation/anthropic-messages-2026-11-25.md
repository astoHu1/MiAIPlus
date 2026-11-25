# Anthropic Messages 验证

2026-11-25，Android API 37，小爱 8.3.30.4420，Debug APK。协议配置见 [使用自己的模型](../../README.md#使用自己的模型)。

本地 730 项测试通过，1 项需额外 models.dev 快照的测试跳过。Debug、Release 和 AndroidTest APK 构建成功，Debug 与 Release Lint 通过。已核查协议菜单在浅色和 320 dp 深色／1.5 倍字号下的显示。

## ADB mock

在 Mac 启动 `python3 scripts/mock_model.py`，建立 `adb reverse tcp:18765 tcp:18765`，安装 Debug 和 AndroidTest APK 后运行：

```sh
python3 scripts/device_test.py --hook --anthropic
python3 scripts/device_test.py --anthropic
python3 scripts/device_test.py --hook --responses
python3 scripts/device_test.py --hook
```

- Anthropic 原生 Dyn 和三个直接客户端均通过流式文字及分段工具调用。续调校验原工具调用 ID、中文参数、签名思考、工具结果及 JPEG，记忆、gate、能力判断和 intent 路由通过非流式请求。
- 独立 Anthropic 聊天、Phone Use 与 AutoGLM 的截图操作及取消通过。原生 Chat Completions 和 Responses 回归通过。
- 探针恢复并核对了原配置，测试结束移除 ADB 反向映射。

## 真实服务探针

在 Mac 通过 CC Switch 的 Claude 路由分别发送一次 Haiku 和 Sonnet 流式请求，每次最多 16 输出 token，未附带工具或手机数据。两个上游分别返回 HTTP 403 和 500，未收到真实 SSE。成功的端到端结果来自本机 mock。
