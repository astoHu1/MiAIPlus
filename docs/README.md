# 文档索引

## 使用与配置

- [项目介绍与安装](../README.md)
- [配置界面](settings-ui.md)
- [详细日志、故障定位与 ZIP 导出](phone-use-logging.md)
- [后台虚拟屏、前置条件与限制](background-display.md)
- [应用启动与结果确认](app-launch.md)
- [Phone Use 权限与会话连续性](phone-use-permissions.md)
- [无障碍前置检查](accessibility-guard.md)
- [API 102 升级与热重载](api102-migration.md)

## 开发与发布

- [架构、模型协议与 OpenCode Go 接入](architecture.md)
- [实现与验证记录、未覆盖范围](validation.md)
- [设置页性能与复现方法](settings-performance.md)
- [后端性能静态审计与 Agent 交接（2026-10-19）](audits/backend-performance-2026-10-19/README.md)
- [Phone Use 性能专项、关键路径与验证计划（2026-10-19）](audits/phone-use-performance-2026-10-19/README.md)
- [动态页面控屏实测](phone-use-dynamic-pages.md)
- [模块图标资源与预览](module-icon.md)
- [发布流程与分发边界](releasing.md)
- [v1.0.1-preview 发布说明](releases/v1.0.1-preview.md)

文档保留使用说明、维护依据、验证证据与发布说明。宣传海报、宣传文案和生成脚本不纳入仓库文档；本地素材可放在已忽略的 `docs/promo/`。原始探针输出不随文档分发，实测结论和范围记录在对应行为文档中。截图仅保留文档实际引用的版本，历史截图不代表当前全部设置。

## 维护约定

| 文档层次 | 内容与更新时机 |
| --- | --- |
| 功能说明 | 用户入口、当前行为、配置和限制；行为变更时同步更新，避免堆叠开发流水 |
| 架构与专项维护 | 源码入口、职责、协议和关键约束；具体操作链接到功能说明 |
| 验证摘要 | 当前源码与已发布构建分别列出证据和未覆盖范围，不在多份文档重复测试计数 |
| `validation/` 阶段记录 | 保留日期、构建范围、测试方式、结果和失败限制；新阶段新增记录，旧结论不冒充当前保证 |
| `releases/` 发布说明 | 固定于对应发布包；未发布的后续改动只更新功能说明与验证记录 |

查功能从上面的使用文档进入，查当前证据从 [验证摘要](validation.md) 进入，再按链接查看历史记录。截图说明应标明 fixture 或实机及适用范围；测试命令不构成设备操作授权。文档整理只执行链接与差异检查时，不声称已重新验证功能。
