# MCP 接入调研与本轮协议边界

资料查阅日期：2026-10-28。本文区分官方协议现状、源码采用的子集以及仍未验证的兼容性；不是任意 MCP 服务或任意 Harness 的兼容承诺。

## 可行性结论

可以把前台控屏、后台控屏和外部 MCP Tools 放在统一的 Plugin 目录、工具定义、宿主注入与执行路由之下。MCP 是一种插件提供方协议，不应成为所有内置工具的父类。Phone Use 仍由模块现有执行器、会话与权限机制承担；外部工具通过模块私有配置的远端 MCP 地址执行，不取得无障碍、root helper 或宿主认证身份。

对用户而言，一个 MCP 服务可以提供多个工具；每个工具独立保留原始 `inputSchema` 和远端名称，宿主侧使用按插件 ID 隔离的稳定工具名。服务器返回的工具说明、schema 和结果均属于外部输入，不代表模块认可其安全性或授予它 Android 权限。

## 现行规范发生了变化

截至查阅日，官方 `latest` 指向 **2026-07-28**，其 Streamable HTTP 与先前版本不能混为一谈：

| 项目 | 本轮使用的 2025-11-25 | 现行 2026-07-28 |
| --- | --- | --- |
| 初始化 | `initialize` → `notifications/initialized` | 无初始化握手；每请求携带版本、身份与能力 metadata |
| 服务发现 | 握手中的能力声明 | `server/discover` |
| HTTP | POST，允许可选 GET SSE | POST；移除独立 GET stream |
| 会话 | 可选 `MCP-Session-Id`，可 DELETE 结束 | 无协议级 session |
| 请求头 | 握手之后 `MCP-Protocol-Version`；存在会话时带 session header | 每请求版本头与 `_meta` 一致，还要求 `Mcp-Method` / 适用的 `Mcp-Name` 与参数头映射 |
| 服务器向客户端索取输入 | SSE 中的服务器 JSON-RPC request | 结果内嵌的 MRTR input requests |
| SSE 断开 | 不是远端取消的充分证据；可选恢复 | 请求 SSE 断开是该请求的取消信号 |

官方现行文档及兼容说明：

- [2026-07-28 版本与兼容](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning)
- [2026-07-28 Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)
- [2026-07-28 服务发现](https://modelcontextprotocol.io/specification/2026-07-28/server/discover)

首轮客户端显式固定 **2025-11-25**；后续为接入现成 Termux 服务，增加 **2025-06-18** 的显式兼容选项，默认仍为 `2025-11-25`。两者均严格校验握手响应与所选版本一致，不把旧版握手称为最新协议，不在错误时猜测服务版本，也不通过隐式回退重新发送 `tools/call`。2026-07-28 应作为单独版本适配开发并验证，不能仅更换版本字符串。

### 2025-06-18 显式兼容与真实 Termux 服务

对官方 `2025-06-18` lifecycle、transports 和 tools 规范核查后，确认本模块采用的 `initialize`／`notifications/initialized`、POST JSON／SSE、202 通知响应、`MCP-Protocol-Version`、文本／`structuredContent` 与 `isError` 子集可复用。插件编辑器提供两个固定选项，不接受任意版本字符串；配置版本改变时清空旧发现目录并更新 revision。即使服务器返回另一个已支持版本，也不能在该连接继续执行。

真实服务采用 `termuxgpt/termux-mcp`，固定 commit `48e3e58b3c8c5d66b97e79c0fa6473d126508f46`，包版本 `0.15.0`，AGPL-3.0。它的协议声明仅为 `2025-06-18`；本轮没有修改上游版本字符串，也没有把自有 fixture 改名为真实服务。直接使用已有 Termux Python 加载上游源码，没有运行上游安装脚本或升级 Python／pip；服务只监听 loopback，显式启用全局 Bearer 认证，并用独立 HOME／配置目录容纳上游状态。上游源码未打包进模块。

设备上的真实 `run_digest(cmd)` 已通过模块 Provider 和小爱当前模块工具代理调用；合成输入仅是测试命令与文件，服务与 shell 执行器均为真实上游。Provider 覆盖 Termux UID／环境、中文两行文件、grep／wc／SHA-256／读取和 MCP 失败语义；宿主代理单独覆盖固定身份命令与当前注册归属。上游原始 MCP 响应另行确认 stdout／stderr、数字 exit `7` 和 `isError=true`，无 token 与伪造 capability token 均被 401 拒绝。模块本身对 `isError` 只返回脱敏失败，不暴露该错误正文，因此这不是模块完整透传 stderr／退出码的证据。

独立 HOME 只用于避免写入用户现有状态，并非 shell 沙箱；服务仍具有 Termux UID 的命令执行能力。没有验证模型自主选择工具、持久 shell 会话或复杂长任务。启动适配、命令、收尾状态及证据边界见 [Termux 实机记录](validation/termux-mcp-2026-10-28.md)。

## 本轮源码支持的子集

`McpClient` 不依赖宿主私有 HTTP 客户端，也不复用模型 API Key、账户头或设备签名。它使用 Android 自带的 `HttpURLConnection` 和 `org.json`。

发起请求前通过 Android 公共 API 检查本进程是否注册了全局 `CookieHandler`；若存在则停止，不继承或修改该处理器。Android 没有公开 `Authenticator.getDefault()`，本版本不调用桌面 JDK 专属 getter，也不反射私有字段或修改全局认证状态。请求使用显式 Bearer、固定长度 body、`allowUserInteraction(false)` 与禁重定向；这些措施不构成对平台全局 `Authenticator` 的完整隔离。当前模块进程源码没有注册该处理器，但如果后续同进程代码注册了它，潜在的隐式认证影响需重新评估，不能宣称完全不存在。此实现不是独立进程沙箱。发现工具时，也拒绝已知 Bearer 原文或其 JSON 转义值进入工具名称、说明和 schema；它不能识别服务自行变换、编码或间接泄露的任意秘密。

支持：

- 单一、显式配置的 Streamable HTTP 最终 endpoint；保留路径及其结尾斜线。
- 显式选择 `2025-11-25` 或 `2025-06-18` 的 `initialize` / `notifications/initialized`；客户端能力声明为空。
- 校验服务返回的协议版本和 `tools` 能力。响应与所选版本不一致则停止，即使该响应版本也在支持列表内。
- `tools/list` 与不透明 cursor 分页；拒绝重复名称、重复 cursor 或超限目录。
- `tools/call`，保持原始工具名称和对象参数。一次调用最多发送一次工具请求。
- `application/json` 或请求关联的 `text/event-stream` 响应；支持注释、空 priming event、多行 `data` 与 CR/LF/CRLF。
- 完整 JSON-RPC envelope、精确 response ID、可选 session header 的检查。
- 普通 text 与 JSON `structuredContent`；`isError=true` 是执行失败，不包装成成功。
- 用户显式配置的 Bearer token；无 token 服务也可使用。凭证仅用于所配置 endpoint。

默认限制：

| 项目 | 上限或行为 |
| --- | --- |
| 单插件发现工具 | 64 个 |
| 发现分页 | 64 页，重复 cursor 立即停止 |
| 每个响应 | 256 KiB；解析前另限 JSON 嵌套深度 |
| 每个请求 | 128 KiB |
| 一个客户端实例的完整操作 | 从首次握手开始 60 秒总预算；进度通知不重置 |
| 连接 / 单次读取 | 最多 15 秒，且不超过开始请求时剩余总预算 |
| 取消与总预算 | 关闭当前连接；超时 watchdog 也关闭当前连接，不发新网络请求 |
| 重定向 | 所有 3xx 均拒绝，包括同域跳转 |
| 错误显示 | 固定、脱敏消息；不传回 HTTP body、凭证、URL 或原始异常 cause |

公网使用 HTTPS。HTTP 仅用于配置校验允许的本机或数字内网地址；该通道是明文的，Bearer 与工具参数也可能被网络中的其他方读取。客户端仅连接用户在设置中指定的最终 endpoint，不跟随服务结果、图片、资源链接或认证发现 URL 发起其他请求。

不支持或不声明：

- 2026-07-28 modern era、自动版本探测或旧版 HTTP+SSE 双端点 transport。
- 本机 `stdio`、在模块内直接执行任意 shell 命令、下载运行 JAR/DEX/APK、外部插件任意代码加载。用户配置的 MCP 服务可以提供 shell 工具，其执行发生在该服务自身权限下。
- OAuth 发现、动态注册、授权浏览器流程、token refresh 或模型凭证透传。
- MCP Resources、Prompts、Sampling、Roots、Elicitation、Tasks、MCP Apps 或任意 Harness 的完整运行环境。
- 图片、音频或资源内容解引用；这些结果会明确失败，不静默获取或伪装成文本成功。
- 服务器主动的 JSON-RPC request；遇到这类请求停止，不执行其中的采样、文件读取或交互指令。
- 独立 GET 通知流、服务推送的自动目录刷新、SSE 恢复与 `Last-Event-ID`。POST 流在最终结果前断开时失败，不重放有副作用的工具调用。
- 关闭时自动 DELETE session：为保证取消/关闭不再启动网络操作，本版本仅断开本地连接，让服务自行过期其 session。不能保证服务器立即释放状态。

尤其是，2025-11-25 规定连接断开不等于取消。关闭本地连接只能停止本模块等待，不能承诺远端撤销已经发生的发送、写入或其他副作用。超时、断线与格式错误后的提示必须保留这种不确定性，不能自动重试。

本轮实现依据：

- [2025-11-25 生命周期](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)
- [2025-11-25 Transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- [2025-11-25 Tools 与错误语义](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)
- [2025-11-25 安全最佳实践](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices)
- [2025-06-18 生命周期](https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle)
- [2025-06-18 Transport](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)
- [2025-06-18 Tools 与结果语义](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)

## Android SDK 选型

官方 [Java SDK](https://github.com/modelcontextprotocol/java-sdk) 当前 README 指明 Java 17+、默认 JDK HttpClient、Reactor 与 Jackson。Android 的 Java 源码级别 17 不等于完整提供桌面 JDK 类库，不能直接据此宣布该 SDK 在本模块上可用。

官方 [Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk) 当前 README 提供 JVM / Native / JS / Wasm，客户端使用 Ktor，并要求应用自己选择 client engine。它可以作为后续评估方向，但本轮没有完成 Android target、依赖大小、R8、协程取消与双宿主 ClassLoader 的兼容验证，也没有引入 SDK 依赖。

采用有界的小客户端是本轮范围控制，不代表自实现已具备完整 MCP SDK 的符合性。后续如果扩大到 OAuth、modern MRTR、资源与通知，优先重新评估官方 SDK 及其 Android 适配，避免把零散的协议实现无限扩展。

## 协议单测与验收范围

`McpClientTest` 使用可注入的假 `HttpURLConnection`，不启动 mock 服务或访问真实网络，覆盖：

- 握手顺序、空客户端能力、精确 endpoint、session / version / Bearer headers 和关闭无新增网络。
- 多页发现、cursor 顺序、重复 cursor、重复名称、64 工具数量边界。
- 文本与 structuredContent；执行错误、协议错误、错 ID、协商版本不匹配和缺失能力。
- SSE 注释、priming、多行 data、各换行形式、缺少最终结果、服务器反向请求。
- 响应与请求大小、嵌套和尾随 JSON 限制、错误脱敏、重定向、认证失败、过期 session、会话身份突变。
- 取消前/响应期间的停止、总预算到期、不自动重发工具、非文本内容不解引用。
- 缺失服务身份、非对象 schema、发现定义回显已知 Bearer、拒绝继承或修改进程全局 Cookie 处理器。没有把桌面 JDK 的 Authenticator getter 测试作为 Android 隔离证据。
- 显式 `2025-06-18` 握手与版本头、两版互相返回时拒绝执行、旧版配置持久化、默认保持及协议切换后清目录／拒绝过期 revision。

`McpClientWireTest` 另外定义桌面 JVM loopback 集成测试：在 `127.0.0.1` 随机可用端口临时运行 fixture socket，通过真实 `HttpURLConnection` 验证握手、202 空通知、分页、认证/session/version headers、原样 endpoint 路径、UTF-8 参数与 JSON/SSE 工具结果；错误发生后核对副作用仅一次且不重发。fixture 在线程和 socket 上设有时限，按测试关闭，不占用现有模型 mock 服务端口，也不连接手机或外网。

这些测试是协议与边界的确定性验证，不证明真实服务、真实模型或已注入小爱的运行效果。具体本轮已运行的构建、Lint、测试结果及未覆盖项由主验证记录汇总；不能以单测源码存在代替通过证据。

2026-10-28 后续已用受控无副作用 fixture 完成 Android HTTP 握手、分页发现、调用、配置启停／过期 revision 拒绝、本地取消，以及小爱 8.3.30.4420 的实际工具注册与原生代理 echo；详见 [独立包实机验证](validation/plugin-platform-device-2026-10-28.md)。这补充了上面的本地协议测试，未覆盖真实模型结果消费、生产服务、全部热重载路径或其他宿主版本。支付、账号写入和生产 MCP 服务不应被用作第一次兼容性验收。

随后固定版本的真实 Termux 服务验证独立记录于 [Termux 实机验收](validation/termux-mcp-2026-10-28.md)。它补充真实服务与手机 shell 证据，不追溯修改前述 fixture 阶段的验证范围。
