# 现有 Termux MCP 实机接入（2026-10-28）

用户要求撤回原生 MCP 优先的设计调整，继续使用模块自己的 MCP 实现，并用已有开源服务验证 Termux shell。本轮精确移除了上一轮新增的原生迁移建议，没有回退统一 Plugin 平台或其他共享工作区改动。改动保留在 `Pluginify`，未提交或推送。

## 上游与运行方式

- 使用现有 [termuxgpt/termux-mcp](https://github.com/termuxgpt/termux-mcp)，固定 commit `48e3e58b3c8c5d66b97e79c0fa6473d126508f46`，版本 `0.15.0`，AGPL-3.0；没有重写其 MCP 服务或 shell 工具。
- 上游源码包 SHA-256：`aca762de76a4cbe7d929e0f47512eb7484eca62bf1645375aae68a7665ffc5ec`；保留原 LICENSE，摘要 `0d96a4ff68ad6d4b6f1f30f713b18d5184912ba8dd389f86aa7710db079abcb0`。下载代码只在忽略目录 `work/`，未加入 APK、依赖或发布源包。
- 手机为 Xiaomi 15 Ultra／API 37；已有 Termux `0.118.3`、Python `3.13`。服务由 Termux 终端启动，实测进程 UID `10351`，不是 root shell。没有升级 Termux、执行上游安装脚本或安装 Python 依赖。
- 使用本轮独占的私有 HOME／配置目录，避免上游 `termuxGPT` 状态、输出缓存和错误记录进入用户已有 HOME；不读取用户 shell 历史、凭证或文档。
- 仅监听手机 `127.0.0.1:18871/mcp`，启用随机 Bearer token，只开放上游 `run`、`run_digest`、`out_read` 三个工具，全局 shell 超时设为 10 秒。

上游普通 HTTP 启动入口会无条件清理所选端口，可能杀掉已有监听进程。本轮没有调用该入口；用一个小启动器直接实例化未修改的上游 `MCPHTTPServer`／`MCPRequestHandler`，保留鉴权、协议、工具发现和执行实现，端口冲突只会 bind 失败。没有编写替代 MCP server，也没有修改上游协议版本字符串。

启动器调用的上游入口为：

```python
from termux_mcp import mcp_config as cfg
from termux_mcp.mcp_server import MCPHTTPServer, _check_startup_guards
from termux_mcp.mcp_transport_http import MCPRequestHandler

host = cfg.native_host()
_check_startup_guards(host)
MCPRequestHandler.endpoint = cfg.native_path()
MCPHTTPServer((host, cfg.native_port()), MCPRequestHandler).serve_forever()
```

鉴权使用全局 `TERMUX_MCP_AUTH=on` 与 `TERMUX_MCP_AUTH_TOKEN`，没有只设置 native token：上游 capability token 的回落认证也需要全局鉴权生效。实际验证无 token 和伪造 `cap_` token 均收到 HTTP 401。工具过滤不是 shell 沙箱，启用后可执行 Termux UID 自身能够执行的命令；没有赋予它模块的无障碍或后台 root helper 权限。

## 必要的客户端兼容

这个真实上游只支持 MCP `2025-06-18`。之前客户端只接受 `2025-11-25`，原样无法完成握手。本轮核对官方旧版 lifecycle／transports／tools 子集后，新增显式版本选择：

- 默认仍为 `2025-11-25`，用户可另选 `2025-06-18`。
- 初始化响应必须严格等于所选版本；即使响应是另一个已支持版本也拒绝，不自动降级或重发工具调用。
- 后续版本头使用所选值；修改版本属于连接身份变更，清除旧发现目录并使旧 revision 失效。
- 编辑窗口提供受限下拉，保存、重开、丢弃草稿及重建均有回归；不支持任意版本字符串、stdio 或旧双端点 SSE。

## 实际结果

没有 mock server。模块直接连接手机上的真实上游服务；临时 ADB forward 只用于电脑对同一服务做原始协议交叉检查，用后恢复。模块和小爱代理阶段均直接访问手机 loopback，没有 ADB reverse。

| 检查 | 实际结果 | 边界 |
| --- | --- | --- |
| 原始 HTTP | 初始化协商 `2025-06-18`，发现且只发现三个指定工具；无 token／伪造 capability token 均 401 | 真实上游协议，不是模块 fake connection |
| 身份 | 模块 Provider 执行 `run_digest(cmd)`，精确验证 Termux UID、隔离 HOME、PREFIX 和 shell 路径 | 非 root，HOME 是本轮隔离目录 |
| 文件与 shell | 创建自有目录和含中文两行文件；`grep`、`wc`、`sha256sum`、读取回传均通过 | 只访问自有 fixture，不访问用户文件 |
| 非零退出 | 上游原始响应实际含 stdout／stderr 标记、数字 `exit=7`、`ok=false` 和 `isError=true` | 上游把 stdout／stderr 合并，不是两个独立 stream |
| 模块失败语义 | 同一非零命令经 Android 客户端与 Provider 返回失败，没有包装成成功 | 当前模块会脱敏隐藏 `isError` 的正文，不向小爱透传该 stderr／数字退出码 |
| 小爱实际代理 | 小爱 `8.3.30.4420` 真实 registry 中验证唯一 definition、schema、说明、当前模块 proxy 归属；代理实际调用 Termux 命令并验证 UID／HOME／shell | 确定性 debug 探针，没有调用模型自主选工具 |
| 生命周期 | 自有 shell fixture 清理成功；remote busy 与 screen task 未污染；临时插件 key、host report 和 `hook_at` 逐字恢复 | 不证明本地取消可以回滚已经执行的 shell 命令 |

设备日志分别为忽略目录内 `device-module.log`、`device-host.log` 和 `upstream-wire.json`。服务身份及源码摘要另外保存；不把 token 或设备 ADB 连接地址写入提交文档。

## 复现入口

新增 `TermuxMcpRunner` 和 `scripts/termux_mcp_device_test.py`。脚本要求服务已单独启动，不安装上游、不启动 mock、不改变 LSPosed 作用域；拒绝主线安装包和非 Android owner 用户。示例：

```sh
python3 scripts/termux_mcp_device_test.py \
  --package io.github.miaiplus.pluginify \
  --endpoint http://127.0.0.1:18871/mcp \
  --token-file /path/to/private-token-file \
  --protocol 2025-06-18 --tool run_digest --command-field cmd \
  --uid ACTUAL_TERMUX_UID --termux-home /path/to/allowed-isolated-home
```

多设备另加 `--serial`。`--termux-home` 仅接受 Termux 标准 HOME 或 runner 定义的严格 nonce 隔离路径。`--host` 会重启小爱，并要求 Pluginify Debug 已是唯一启用的小爱模块；保持 DUMP 权限校验，只接受固定测试命令，不允许通过广播传入任意 shell 命令。

## 本地检查

70 个 JVM／Robolectric suite 共 **513 项**，0 failures／errors／skipped。新增 5 项后端协议回归和 2 项设置状态／Compose UI 回归；Debug Lint 为 0 errors、37 warnings、1 hint，Release 为 0 errors、26 warnings、1 hint。

Debug、Release unsigned、优化 Preview、Debug AndroidTest 均构建成功。完整任务集合：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease :app:assemblePreview \
  :app:assembleDebugAndroidTest --offline --console=plain
```

完整构建后对 debug 探针及测试恢复细节做了定向补建，没有将一次构建输出冒充所有后续源码都已构建。Python 语法及差异检查另行执行；文档改动不重复构建。

## 范围与收尾

实测验证真实第三方服务、真实 Termux shell 和小爱进程中的实际代理调用。未执行真实模型自主任务、长驻终端会话、安装软件包、root 命令、手机隐私 API 或用户文件操作；不能据此声称全部上游工具、会话型 shell 或所有 MCP 服务器兼容。

收尾已完成：主线模块恢复启用，保留原小爱作用域；独立包 `io.github.miaiplus.pluginify` 的优化 Preview 已安装，但模块停用，避免双模块同时注入。小爱重启后，主线无障碍连接正常且不忙；没有遗留 instrumentation 或 DisplayHelper，手机返回桌面。

最终 Preview APK 的 SHA-256 为 `224e873a2484990786b878cf775c5f08e070037d8d5f5c94a246bae9bc7f5ff2`，已核对手机安装包字节一致。另在 Preview 上实际打开插件页及新增 MCP 窗口，确认下拉中有两个协议版本，随后丢弃草稿，没有保存临时连接。前述模块与小爱代理探针使用 Debug／AndroidTest；未把这些结果描述为在优化 Preview 上重跑。

核验自有服务进程的 PID、UID 与命令行后发送 SIGTERM，确认进程已退出；删除本轮独占设备目录及暂存目录，原先不存在的 Termux `.cache` 父目录也恢复为不存在。主机端生成的 token／配置／传输包已删除，保留元数据不含 token。临时测试 APK 已卸载，原 ADB forward 列表已恢复；未设置 ADB reverse、升级 Termux 软件包或修改 root 授权。临时插件配置与宿主报告（含 `hook_at` 及原先缺失 key 的状态）均有精确恢复断言。
