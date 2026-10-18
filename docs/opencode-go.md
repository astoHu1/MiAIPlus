# OpenCode Go 请求头兼容

资料查阅日期：2026-10-18。[OpenCode Go 官方文档](https://opencode.ai/docs/go/#where-can-i-use-it) 要求客户端使用自己的 User-Agent，并为每个对话发送稳定的 `x-opencode-session`，供路由和提示词缓存使用。此次实现只增加客户端兼容，不代表已用真实订阅验收。

模块外部模型请求发送 `User-Agent: MiAIPlus/<version>`，不伪装成 OpenCode。独立任务在多次 Chat Completions／Responses 请求中复用任务标识；原生聊天按消息 ID 关联会话，同一历史内重试／续轮保持一致，不同对话中的相同提问不合并。原生记忆／路由辅助请求按各自请求 ID 标识；缺少 ID 时使用单次 UUID。它们暂未与主对话合并成同一标识；全部关联历史被压缩掉或缓存淘汰后也可能产生新标识。

当前官网列出的 OpenAI 兼容 Base URL 为 `https://opencode.ai/inference/go/openai/v1`，对应模型按官网选择 Chat Completions 或 Responses，模型名填写服务端的 Model ID。模型列表位于单独的 `https://opencode.ai/inference/go/v1/models`，与模块通用的 Base URL + `/models` 不同，目前可手动填写模型。Anthropic／Gemini 专属协议不属于模块现有两种协议的适配范围。

以上地址和要求来自当日官方文档，不保证服务端后续维持不变。测试范围与本地验证结果见 [验证记录](validation.md)。
