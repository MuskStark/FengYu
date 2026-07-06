# ZhiFlow Plugin Development

_plugins 目录下所有文档的统一入口。_

## Getting Started

1. [Prerequisites & Setup](./getting-started.md) — 环境要求、Maven 配置
2. [Project Scaffold](./scaffold.md) — 项目结构与文件模板
3. [SPI Registration](./spi.md) — Java ServiceLoader 接入机制

## Core Topics

4. [Plugin Entry Point](./entry-point.md) — `SwissKitJPlugin` 接口实现
5. [UI Development](./ui.md) — JavaFX UI 编写、i18n 绑定
6. [Internationalization](./i18n.md) — 多语言支持
7. [Database Layer](./database.md) — H2 + MyBatis 集成

## Advanced Topics

8. [Excel I/O](./excel.md) — FesodSheet 读取/写入
9. [Background Tasks](./background-tasks.md) — JavaFX Task 异步处理
10. [Build & Deploy](./build-deploy.md) — 生产打包、热部署
11. [Common Pitfalls](./pitfalls.md) — 常见错误与解决方案

---

## 快速导航

| 场景 | 推荐阅读 |
|------|----------|
| 我的插件需要数据库 | → [Database](./database.md) |
| 我的插件需要读写 Excel | → [Excel](./excel.md) + [Background Tasks](./background-tasks.md) |
| 我的插件需要文件上传 | → [Background Tasks](./background-tasks.md) |
| 独立窗口样式不对 | → [UI - Themes](./ui.md#themes) |
| i18n 不生效 | → [i18n](./i18n.md) + [Pitfalls #9](./pitfalls.md) |
| 部署后插件加载失败 | → [SPI](./spi.md) + [Pitfalls #1-3](./pitfalls.md) |