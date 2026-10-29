# 补充参考：Android Remote Control MCP

用户提供项目：[danielealbano/android-remote-control-mcp](https://github.com/danielealbano/android-remote-control-mcp)。核查日期：2026-10-28；只读 `git ls-remote HEAD` 固定 commit **`892d1afbc2241176fc456ab4438c30909e84e0c7`**。三个只读 Subagent 分别核对观察格式、项目定位／默认值、节点与缓存安全，再由主 Agent 汇总。未 clone、复制代码、增加依赖、构建、运行上游测试、操作设备或调用模型。

返回[研究总览](README.md)／[开源对照](upstream.md)。下文是源码机制及待测适配，不是对本项目的效果或上游安全漏洞的实测结论。

## 对原研究的补充

这个项目对 **C2 模型观察序列化、C5 按需详情／节点引用** 的参考更直接：它展示了如何在观察／工具接口层减少模型每次要读的内容。它不代替 C3 的历史工作集和任务事实设计。

它是手机端 MCP 执行／观察服务器，外部 client／AI 选择工具；所核查 server／tool 路径没有任务 planner、模型对话历史 reducer 或任务工作记忆管理器。HTTP 的 stateless 表示请求传输模式，分页 snapshot cache 表示屏幕数据缓存，均不等于模型每步自动裁历史。见固定[架构:13–55](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/docs/ARCHITECTURE.md#L13-L55)和[项目说明:169–180](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/docs/PROJECT.md#L169-L180)。

## 可核实的观察设计

以下源码简写根目录为 `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/`。

| 机制 | 固定源码中的行为 | 默认值／限制 |
| --- | --- | --- |
| 聚合观察 | `get_screen_state` 汇总 screen、app／window 和精简节点表 | 省多次元数据查询，不管理历史记忆 |
| 紧凑 TSV | `node_id,class,text,desc,res_id,bounds,flags`；类名去包前缀、状态短标记 | 保留有正文／描述／resource ID 或交互意义的节点；不是仅可点击节点 |
| 结构节点筛选 | 没有信息／交互意义的父节点可省略，孩子继续遍历 | 只影响模型展示；不能直接应用于安全树 |
| 普通节点文字摘要 | text／desc 前 100 字符后加 `...truncated`，TSV 控制字符清理 | resource ID 不截断，合并 WebView 节点正文也是例外；不是统一 token 硬上限 |
| 全文按需读取 | `get_node_details(node_ids[])` 重取当前多窗口树，返回 node ID／完整 text／desc，缺失为 not_found | 不返回任意属性／bounds／子树；也不是读取旧 snapshot 的原文 |
| 分页 | 每页 200 个 kept nodes，携 snapshot／cursor／page／range，找到目标可停止 | WebView 长文、resource ID 和 hierarchy 的祖先闭包仍可使响应大 |
| 屏外／层级开关 | `include_offscreen=true`、`include_hierarchy=true` | 默认不等于最小的“仅屏内扁平列表”；设 false 才减量 |
| 截图按需 | `include_screenshot=false`，请求时 `annotate=true` 默认；screen 工具压至 700 px 框 | 该工具 schema 未开放任意质量／尺寸参数，不直接泛化 README 的可配置声明 |

依据：[CompactTreeFormatter:109–128,209–261](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatter.kt#L109-L261)、[formatter 分页:315–455](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatter.kt#L315-L455)、[ScreenIntrospectionTools:94–196](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt#L94-L196)、[截图:199–263](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt#L199-L263)、[UtilityTools 全文查询:706–819](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityTools.kt#L706-L819)。

**适配判断：**先试独立 model-view formatter、明确截断、定向全文查询，比先增加一个摘要模型更容易检验。选择是否给模型发送图像，与执行器是否采集安全复核所需的新图，是两项独立策略；不能因上游默认无图，就删本项目 PageGuard 必要的新观察。

## 最有价值的隔离：模型视图不改原始执行节点

`ScreenIntrospectionTools:116–125` 先用未合并树填动作缓存，再对模型输出做 WebView merge；交互节点保留原 ID。这个结构支持本研究 C2“完整 Frame 用于执行安全，模型视图另行精简”的方向，不能理解成缓存命中即可跳过新鲜度检查。

[WebViewNodeMerger:45–172](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/WebViewNodeMerger.kt#L45-L172)仅针对带 Chromium `webRole` 的树，把普通 DOM 正文归并到保留的语义／交互节点，部分角色转成 Markdown；native／Compose 不使用同一合并规则。

但[dedupJoin:198–231](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/WebViewNodeMerger.kt#L198-L231)按小写 token 集合判断包含，集合不保留顺序／次数。“token 集合没丢”不证明语义无损，不能直接用于中文否定、商品数量、重复项或相似条目。第一补丁不建议同时移植 WebView merge；先做可解释 formatter，再独立验证合并。

还有一个检索边界：上游不截断合并 WebView 正文，正因为聚合内容仅存在于模型视图，原节点的 `get_node_details` 无法保证取回整段聚合文字。MiAIPlus 若为聚合输出增加硬预算，必须同步设计聚合全文分页／片段检索，不能截掉后声称普通 node details 可以无损恢复。

## 必须保留的源码限定

### 1. cursor 是分页缓存引用，不是实时屏幕有效性保证

[ScreenStateSnapshotCache:24–36](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ScreenStateSnapshotCache.kt#L24-L36)明确 single-slot、**NO change detection**：新 store 替换或 clear 才失效。cursor 路径只读缓存，不重新取屏，后续页也不附新截图。

工具 schema 的“屏幕改变会要求 fresh capture”措辞比这里的实现保证更强，不能照抄成事实。树与截图也非原子捕获，`ScreenIntrospectionTools:199–205` 明示存在时间差／标注可能过时。

MiAIPlus 若实现分页，应绑定 owner／session／display generation／observation，并明确“这一页属于哪次旧观察”。新动作／观察后可拒绝旧 cursor，或仅允许历史只读；它不能给予旧节点继续执行的效力。全局 single-slot 缓存不能直接替代现有任务归属。

### 2. node ID 是结构 hash，不是业务对象或授权身份

[AccessibilityTreeParser:243–262](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParser.kt#L243-L262)按 resource ID、类名、bounds、深度、兄弟序号和 parent ID 生成 32 位 String hash；不含 text／description／任务／观察 epoch。窗口 parent 有区分作用，仍不等于本项目 display generation。hash 不能被描述成无碰撞能力票据。

同位置列表 cell 从 A 换成 B，或“允许”按钮背后授权正文变化，结构 ID 可能仍相同。[NodeTargetResolver:72–174](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/NodeTargetResolver.kt#L72-L174)的 selector 要求唯一匹配，歧义返回最多五候选而不猜，这一点值得借鉴；但不能直接把 ID 命中当旧目标没变。

[ActionExecutorImpl:676–711](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutorImpl.kt#L676-L711)缓存命中会 refresh 并重算结构 ID；[fallback walk:925–960](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutorImpl.kt#L925-L960)按 parsed ID／子序号并行查找，不能仅据注释认定 parse→act 时间窗完全消失。这是静态保证范围，不是本轮复现了误点。

MiAIPlus 的 node reference 若落地，应绑定当前 session／observation／display generation，继续通过 PageGuard、焦点、遮挡、权限正文及注入前复核。截图缩放后像素与原树屏幕 bounds 不同，也必须保留明确坐标空间。

### 3. 省 schema、远程认证和任务安全是不同能力

[ToolPermissionsConfig:13–22](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ToolPermissionsConfig.kt#L13-L22)默认 disabled tools／params 集合为空；按配置少暴露工具是用户能力，不是自动按任务选最小 schema。工具定义的实际计费／缓存由 client／provider 决定，不能笼统保证每轮都省相同 tokens。

MCP 远程认证、调用取消、设备正文前的不可信警告，不能替代 MiAIPlus 的 task-bound consent、UID／来源工具、前后台屏所有权和敏感动作批准。也不因它读取多窗口就允许后台工具读取物理主屏。这里研究观察表示，不新增 MCP 远程服务、文件／Intent 工具或网络暴露面。

### 4. 有界复合滚动需要另立行为实验

[NodeActionTools:407–419,509–605](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/NodeActionTools.kt#L407-L605)的 `scroll_to_node` 在工具内部有界滚动，每次后 fresh read／refind，检查目标歧义和容器变化；不是任意动作脚本批跑。

这种局部 primitive 可减少模型往返，但会改变本项目“一次一个数据动作”、每次尝试计预算、取消和最后观察的语义，不能当 C2 展示格式补丁顺手加入。若以后研究，每个子动作都需计数、检查取消、复核原屏；新弹窗／敏感目标／目标丢失立即返回，不放宽确认与后台故障停止。

## 建议追加的实验

| 实验 | 对照与判定 |
| --- | --- |
| 紧凑模型视图 | 当前原生 12k 字符裁剪 vs 完整行／短类名／flags／明确截断；测输入字节、真实 usage、定位正确率，安全树完全不变 |
| 按需 details／分页 | 全量观察 vs 小工作集＋定向取全文；把额外工具轮次计入任务延迟／费用，缺失详情不得诱发重开 goal |
| 选择性发送图片 | 每步图 vs 可访问树足够时按需图；内部安全采集不变，纯图／canvas／歧义布局确保仍有当前图 |
| WebView 合并 | 与不合并分别测中文顺序、否定、重复数量／列表项和交互目标；不能只比较 token Set |
| 引用新鲜度 | cursor 后页面变化、同位 cell 换内容、同按钮授权正文改变、selector 从唯一变歧义、window／display generation 复用 |
| 预算真实性 | 超长 WebView／resource ID／hierarchy ancestor closure；证明节点数分页不冒充字节／token 硬上限 |

已有上游测试仅阅读，未运行；测试中的 stale snapshot ID 不等于动态屏变检测，fixture 节点数减少不等于 token／长期任务成功率提升。[README:81–105](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/README.md#L81-L105)的 10–50× 表示压缩／10–100ms 动作等比较是上游声明，本轮未重现，不能回填为本项目收益。原本[四层验证方案](validation-plan.md)继续适用。

## 许可证

固定 [LICENSE.md:4–20](https://github.com/danielealbano/android-remote-control-mcp/blob/892d1afbc2241176fc456ab4438c30909e84e0c7/LICENSE.md#L4-L20)写明 **GPL-3.0-or-later**，并有 Google Play services 组合的 additional permission。它不是上轮多数参考项目的 MIT／Apache 许可。本轮只描述机制；后续若复制具体文件／提示或引入组件，应重新核对双方许可、归属和分发 notices，不因额外许可就视为所有代码可按宽松许可复用。
