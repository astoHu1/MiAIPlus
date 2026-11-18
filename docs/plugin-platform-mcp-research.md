# MCP 协议与客户端选型

调研基线：2026-10-28。插件的使用方式见 [Plugin 平台](plugin-platform.md)，执行架构见 [架构](architecture.md#统一-plugin-层)。

## 采用的协议

`McpClient` 支持 tools-only Streamable HTTP，默认 `2025-11-25`，可显式选择 `2025-06-18`。握手返回版本必须与配置一致；切换版本清空旧工具目录，不自动降级或重发工具调用。

调研时官方规范已更新为 `2026-07-28`，需要单独适配：

| 项目 | 已实现的 2025-11-25 | 2026-07-28 |
| --- | --- | --- |
| 初始化／发现 | `initialize` → `notifications/initialized`，握手声明能力 | 无初始化握手；使用 `server/discover` |
| HTTP | POST，可选 GET SSE | POST，移除独立 GET stream |
| 会话 | 可选 `MCP-Session-Id`，可 DELETE 结束 | 无协议级 session |
| 请求信息 | 版本头及可选 session header | 版本头与 `_meta` 一致，增加 `Mcp-Method`／`Mcp-Name` 等头 |
| 服务器索取输入 | SSE 中的 JSON-RPC request | 结果内嵌 MRTR input requests |
| SSE 断开 | 不等于远端取消 | 该请求的取消信号 |

参考：[版本说明](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning)、[Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)、[服务发现](https://modelcontextprotocol.io/specification/2026-07-28/server/discover)。

## 客户端实现

客户端使用 Android `HttpURLConnection` 和 `org.json`，独立于宿主 HTTP 客户端和模型凭证。每个 MCP 服务提供多个工具，保留远端名称及原始 `inputSchema`，宿主名称按插件 ID 隔离。工具说明、schema 和结果按外部输入处理。

支持范围：

- 单一、显式配置的最终 endpoint，保留路径和结尾斜线；显式 Bearer 或无认证。
- `initialize`／`notifications/initialized`、空客户端能力及服务端 `tools` 能力校验。
- `tools/list` 不透明 cursor 分页，拒绝重复名称、cursor 和超限目录。
- `tools/call` 原始名称与对象参数，一次调用最多发送一次工具请求。
- JSON 或请求关联的 SSE：注释、空 priming event、多行 `data` 及 CR／LF／CRLF。
- JSON-RPC envelope、精确 response ID、可选 session header 校验。
- text／JSON `structuredContent`，`isError=true` 按执行失败处理。

| 项目 | 限制 |
| --- | --- |
| 单插件目录 | 64 个工具、64 页 |
| 请求／响应 | 128 KiB／256 KiB；JSON 另限嵌套深度 |
| 完整操作 | 首次握手起 60 秒，进度通知不续期 |
| 连接／单次读取 | 最多 15 秒，受剩余总预算约束 |
| 取消／超时 | 关闭当前连接，不发新请求 |
| 重定向 | 拒绝全部 3xx |
| 错误显示 | 固定脱敏消息，不返回 HTTP body、凭证、URL 或原始异常 cause |

公网使用 HTTPS；HTTP 仅允许本机或数字内网地址，凭证和参数以明文传输。客户端只连接所配置 endpoint，不解引用工具返回的图片、资源或认证 URL。

不支持 `2026-07-28`、自动版本探测、旧版 HTTP+SSE 双端点、stdio、OAuth、Resources、Prompts、Sampling、Roots、Elicitation、Tasks 或 MCP Apps。图片、音频、资源结果及服务器主动 JSON-RPC request 会失败。未实现独立 GET 通知流、自动目录刷新、SSE 恢复或 `Last-Event-ID`；POST 流中断不重放调用。关闭只断开本地连接，不发送 DELETE session，由服务端过期其状态。远端已发生的副作用无法撤回，超时或断线后不自动重试。

发请求前若发现全局 `CookieHandler` 则停止。使用显式 Bearer、固定长度 body、禁交互认证和禁重定向；Android 缺少公开的 `Authenticator.getDefault()`，因此无法完整隔离进程全局认证状态。发现目录拒绝回显已知 Bearer 原文及其 JSON 转义值，但无法识别服务自行变换的秘密。

协议依据：[2025-11-25 生命周期](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)、[传输](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)、[安全实践](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices)；[2025-06-18 生命周期](https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle)、[传输](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)、[Tools](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)。

## SDK 选型

调研时官方 [Java SDK](https://github.com/modelcontextprotocol/java-sdk) 使用 Java 17+、JDK HttpClient、Reactor 和 Jackson；Android 的 Java 17 源码级别不提供完整桌面 JDK 类库。[Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk) 使用 Ktor，需另选客户端 engine。两者尚未完成本模块的 Android target、体积、R8、取消和双宿主 ClassLoader 验证。

目前保留有界客户端；扩展 OAuth、MRTR、资源或通知前重新评估官方 SDK。

## 验证入口

- `McpClientTest`：假连接验证握手、版本、分页、JSON／SSE、认证、大小限制、脱敏、取消和单次发送。
- `McpClientWireTest`：桌面 JVM loopback fixture 验证真实 HTTP、UTF-8、headers、分页和失败后不重发。
- [独立包实机验证](validation/plugin-platform-device-2026-10-28.md)：Android fixture HTTP、小爱实际注册及代理。
- [Termux 实机验证](validation/termux-mcp-2026-10-28.md)：真实 `termuxgpt/termux-mcp` 0.15.0（commit `48e3e58b3c8c5d66b97e79c0fa6473d126508f46`，AGPL-3.0）的 `2025-06-18` 兼容、Termux UID 命令、文件管道及失败语义。该阶段独立 HOME 用于隔离状态，shell 权限仍属于 Termux UID。

测试结果与未覆盖范围见 [验证摘要](validation.md)；内置受管 Termux 的后续实现见 [执行契约](termux-tools.md)。
