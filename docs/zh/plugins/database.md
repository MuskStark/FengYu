---
title: 插件数据库规范
description: 数据库级隔离、H2 进程内 TCP server、Admin 凭据与用户授权 provisioning，以及隔离插件的凭据规则。
lang: zh-CN
---

# 插件数据库规范

插件在清单中声明 `database` 即开启数据库访问。宿主随后向隔离 Worker 环境注入**连接坐标**（`FENGYU_DB_*` —— type/driver/url/username/password —— 以及一个私有数据目录），worker 端由 `PluginDatabaseConfig.fromEnvironment(...)` 读取。这些坐标绝不暴露给 iframe，且 worker 自行建立连接。

核心规则是**由数据库引擎隔离，而非依赖插件自觉**。宿主为每个 `database` 插件 provision 其专属的受限 DB 用户（SQLite 为文件），越界访问由引擎本身拒绝。插件永远拿不到宿主的运行时凭据，也拿不到用于 provisioning 的 admin 凭据。

## 数据库级强制隔离

每个 `database` 插件运行在隔离的命名空间内。隔离机制取决于宿主配置的数据库：

| 数据库 | 隔离机制 | worker 连接串 | provisioning |
| --- | --- | --- | --- |
| **H2（server 模式）** | RBAC —— per-plugin 用户 + schema + `GRANT` | `jdbc:h2:tcp://127.0.0.1:<port>/...;SCHEMA=fengyu_<plugin>` | `CREATE USER` + `CREATE SCHEMA AUTHORIZATION` + `GRANT ALL ON SCHEMA` |
| **MySQL** | RBAC —— per-plugin 用户 + 独立 database + `GRANT` | `jdbc:mysql://host:port/fengyu_<plugin>` | `CREATE USER '...'@'127.0.0.1'` + `CREATE DATABASE` + `GRANT ALL PRIVILEGES` |
| **PostgreSQL** | RBAC —— per-plugin role + schema + `GRANT` | `jdbc:postgresql://host:port/<host-db>?currentSchema=fengyu_<plugin>` | `CREATE ROLE LOGIN` + `CREATE SCHEMA AUTHORIZATION` + `GRANT USAGE, CREATE` |
| **SQLite** | **文件级（文档明确声明的例外）** | `jdbc:sqlite:<宿主分配路径>` | 无 —— 宿主在插件数据目录下分配独立文件 |

**关键不变量：**

- worker 永远拿不到宿主的运行时凭据或 admin 凭据，只持有自己的受限凭据。
- 对 H2、MySQL、PostgreSQL，由数据库引擎本身强制边界 —— 插件无论用什么表名，都无法读写其他插件（或宿主）的表。
- 宿主自身的连接（HikariCP）与 Hibernate 方言不依赖 URL scheme；无论宿主走 `file:` 还是 `tcp://`，`H2Dialect` 都不变。

### 为什么 SQLite 是例外

SQLite 没有 TCP server，也没有 `CREATE USER` / `GRANT` 模型 —— 引擎没有任何可供宿主限制的能力。与其自造一个 DB server（既违背 YAGNI，又重新引入隔离本要杜绝的共享），SQLite 被作为**文档明确声明的技术例外**：隔离粒度为文件级。宿主在插件私有数据目录下分配一个独立的 `.db` 文件，并把该路径交给 worker。插件不能自选数据库路径，必须使用宿主分配的文件。

## H2 进程内 TCP server

宿主的 H2 数据库可运行于两种模式。嵌入式 `jdbc:h2:file:...` 持有独占的 OS 文件锁，任何第二进程（尤其是沙箱内的 worker）都无法附加到同一文件 —— H2 的 `AUTO_SERVER=TRUE` 会被 OS 沙箱阻断，故刻意不使用。为支持 per-plugin RBAC，宿主将 H2 提升为**进程内 TCP server**：

- 宿主启动一个 `org.h2.tools.Server` TCP 实例，绑定到 **`127.0.0.1`** 的 OS 分配动态端口。绑定到 loopback 使用 **`h2.bindAddress` 系统属性** —— H2 2.4.240 没有 `-tcpHost` 标志（传入会抛 `JdbcSQLFeatureNotSupportedException`），故在创建 server 前用 `System.setProperty("h2.bindAddress", "127.0.0.1")` 强制 loopback。刻意省略 `-tcpAllowOthers`。
- 首次启动时宿主自身的 `db.url` 由 `jdbc:h2:file:...` 改写为 `jdbc:h2:tcp://127.0.0.1:<port>/...`。Hibernate 方言不依赖 URL scheme，切换安全。
- **生命周期顺序（关键约束）**：`HeadlessLauncher.main` 在启动数据库探测（`probeAndDecide`）**之前**就启动 TCP server，因为探测在 Spring 启动前就开 JDBC 连接 —— server 模式下该连接走 `tcp://`，需要 server 已在监听。Spring bean 只负责关闭（`@PreDestroy`）。
- 所选端口写入 `<config>/h2-server.properties`（非敏感，仅供诊断）。

server 运行后，`database` 插件获得一个 H2 用户和一个 `fengyu_<plugin>` schema，通过 `tcp://127.0.0.1:<port>/...;SCHEMA=fengyu_<plugin>` 连接 —— 真正的 DB 级 RBAC 隔离。若宿主仍处于 file 模式 H2（尚未提升），`database` 插件按 SQLite 的方式处理：宿主在插件数据目录下分配独立文件。

## Admin 凭据与 provisioning

RBAC provisioning 需要一组 **admin 凭据** —— 一个具备 `CREATE USER` / `CREATE SCHEMA` / `GRANT` 权限的 DB 账户，独立于宿主的常规运行时账户。它是可选的，由 setup 向导采集：H2 / MySQL / PostgreSQL 时显示，SQLite 时隐藏。以 AES-GCM 加密存入 `datasource.properties` 的 `db.admin.username` / `db.admin.password`，**仅用于 provisioning DDL** —— 永不注入 worker。

per-plugin worker 凭据（per-plugin 用户名加随机 URL-safe-base64 密码）在首次授权时生成，以 AES-GCM 加密存入 `plugin-db.properties`。它绝不是宿主或 admin 的凭据，也绝不暴露给 iframe。

### Provisioning 流程

provisioning 是**用户授权、绝不隐式触发**的：

1. 声明 `database` 的插件在 设置 → "数据库隔离" 中显示 **"授权数据库"** 操作。
2. 用户点击，确认对话框（说明将为该插件创建独立 DB 用户/schema），宿主发起 `POST /api/plugin-db/provision/{pluginId}`。
3. provisioner 读取 admin 凭据（仅此刻读取），检查是否已有记录（幂等 —— 已存在则直接返回已存凭据，不重复执行 DDL），随后按引擎执行带 `IF NOT EXISTS` 的 DDL，存储新的 per-plugin 凭据，返回用于注入的连接坐标。
4. 下次启动 worker 时，环境服务注入该插件受限的 `FENGYU_DB_*` 坐标。

**尚未授权**的 `database` 插件**完全不会**收到任何数据库环境 —— UI 引导用户授权。宿主的全局 DB 凭据永不进入 worker。

若 admin 凭据权限不足，provisioning 以明确错误失败，引导用户回到 setup 向导。**绝不静默降级**为共享宿主凭据。

### 卸载时 deprovision

卸载插件会通过 admin 凭据 `DROP USER` / `DROP SCHEMA` / `DROP DATABASE`，随后删除存储记录。deprovisioning **非阻塞**：DDL 失败会被捕获并记录日志（留待日后重试），但存储记录始终会被删除，卸载本身必然成功。SQLite 无 deprovisioning 步骤 —— 插件数据目录下的 `.db` 文件随常规卸载清理。

## 表前缀约定（命名整洁）

早期版本把 `FengTu_PL_<Plugin>_<Table>` 前缀当作隔离机制。引擎级 RBAC 就位后，该前缀降级为**命名整洁，而非安全边界**：

```text
FengTu_PL_<插件>_<表>
```

每个插件仍独立拥有并迁移自己的 schema，不得依赖宿主 JPA 或其他插件表，并应保留前缀以便归属识别。但插件再也无法靠换前缀逃出自己的命名空间 —— 边界由引擎级 GRANT 定义。请保持迁移按方言、版本化且幂等，以在四种数据库上均可运行。

## 机密

宿主用机器绑定 AES-GCM 保护数据源密码、admin 凭据以及每个 per-plugin worker 凭据。插件自有的秘密仍由插件负责 —— 例如邮件中心把 AES 密钥放在稳定私有目录，持久化前加密 SMTP/IMAP 密码。密码在 RPC 中只写不读，错误会脱敏，数据库配置不会进入 iframe。

## 检查清单

- 声明 `database` 并使用官方 Worker SDK。
- 把引擎级隔离视为权威；保留 `FengTu_PL_<插件>_` 前缀用于命名整洁。
- 在依赖数据库功能之前，先到 设置 → "数据库隔离" 授权插件的 DB 访问。
- 迁移必须按方言、版本化且幂等（H2 与 SQLite 是本地必测项；配置后运行 MySQL 与 PostgreSQL）。
- 加密插件自有凭据，且绝不通过 RPC 返回。

另见[插件清单](/zh/plugins/manifest)、[Worker](/zh/plugins/worker)和[邮件中心](/zh/plugins/email-center)。
