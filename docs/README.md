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
- [Eta 外部 Agent](eta-agent.md)

## 开发与发布

- [架构、模型协议与 OpenCode Go 接入](architecture.md)
- [实现与验证记录、未覆盖范围](validation.md)
- [设置页性能与复现方法](settings-performance.md)
- [后端性能静态审计与 Agent 交接（2026-10-19）](audits/backend-performance-2026-10-19/README.md)
- [Phone Use 性能专项、关键路径与验证计划（2026-10-19）](audits/phone-use-performance-2026-10-19/README.md)
- [Phone Use 弱长上下文模型适配、开源对照与后续方案（2026-10-28）](audits/phone-use-context-2026-10-28/README.md)
- [动态页面控屏实测](phone-use-dynamic-pages.md)
- [模块图标资源与预览](module-icon.md)
- [发布流程与分发边界](releasing.md)
- [v1.0.1-preview 发布说明](releases/v1.0.1-preview.md)
- [v1.0.2-preview 发布说明](releases/v1.0.2-preview.md)
- [v1.0.2 发布说明](releases/v1.0.2.md)
- [v1.0.3 发布说明](releases/v1.0.3.md)

## 维护约定

- 功能说明写当前行为和使用方法；架构文档写职责与关键约束，重复内容用链接引用。
- 验证摘要只保留结论、关键缺口和证据链接。日常修复直接更新现有文档，不逐次新增报告。
- `validation/` 与 `audits/` 保存值得复用的实验、调查证据；`releases/` 固定记录对应发布包。
- 删除过时指令和无引用截图；本地素材、原始探针输出放在已忽略目录。
