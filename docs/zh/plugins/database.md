---
title: 插件数据库规范
description: 隔离插件的数据库环境、表结构归属与凭据规则。
lang: zh-CN
---

# 插件数据库规范

插件可以使用宿主选择的 H2、SQLite、MySQL 或 PostgreSQL，而不依赖宿主持久化代码。宿主只在清单声明 `database` 时，向隔离 Worker 注入数据库类型、驱动、JDBC URL、用户名、密码和稳定私有数据目录；`PluginDatabaseConfig.fromEnvironment(...)` 负责读取。这些值绝不暴露给 iframe。

## 独立表结构

每个插件独立拥有并迁移自己的表，不得依赖宿主 JPA、宿主仓库或其他插件表。命名规则为：

```text
FengTu_PL_<插件>_<表>
```

邮件中心只创建 `FengTu_PL_Email_*` 表。四种数据库均使用可重复执行的版本迁移；H2 与 SQLite 是本地必测项，配置 CI URL 后运行 MySQL 与 PostgreSQL 契约。

## 凭据

宿主用机器绑定 AES-GCM 保护数据源密码。插件秘密仍由插件负责：邮件中心把 AES 密钥放在稳定私有目录，持久化前加密 SMTP/IMAP 密码。密码在 RPC 中只写不读，错误会脱敏，数据库配置不会进入 iframe。

- 声明 `database` 并使用官方 Worker SDK。
- 迁移必须按方言、版本化且幂等。
- 所有表使用 `FengTu_PL_<插件>_` 前缀。
- 加密插件凭据且绝不通过 RPC 返回。

另见[插件清单](/zh/plugins/manifest)、[Worker](/zh/plugins/worker)和[邮件中心](/zh/plugins/email-center)。
