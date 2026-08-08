# Toolchain SDK 审计整改：DB 连接强制隔离 + 日志落盘

**Date:** 2026-08-08
**Branch:** 4.0.0
**Status:** Design approved; pending implementation plan

---

## 1. 背景

对官方 toolchain SDK（`toolchain/sdk-java` 1.2.0）及宿主侧 plugin runtime 做了一次专项审计，重点核查**日志传递**与**数据库连接透传**两条通道。审计结论：两条通道设计正确、隔离干净，未发现违反 4.0.0 进程隔离模型的传递，但发现 4 处需要修正的设计取舍 / 瑕疵：

| # | 审计发现 | 现状 | 严重度 |
|---|---|---|---|
| 1 | 远程 DB（MySQL/PG）把宿主**全局同一组** URL/user/password 原样透传给所有 `database` 权限插件 | `PluginRuntimeEnvironmentService.resolveWorkerDbUrl` 远程分支原样返回 `config.url()`；隔离仅靠 `FengTu_PL_<Plugin>_*` 表前缀**约定**，插件可读写宿主全库 | 设计取舍 |
| 2 | 插件日志仅存内存环形缓冲（`PluginLogStore`，500 条/插件），宿主重启即丢失 | 无 per-plugin 持久化日志文件 | 设计取舍 |
| 3 | `PluginLogging.severity(Level)` switch 仅覆盖 TRACE..ERROR，非穷尽 | 未来 SLF4J `Level` 扩展时静默走错路径 | 低危瑕疵 |
| 4 | `docs/en/plugins/manifest.md:87` 把 `database` 权限描述为注入 *"a datasource connection"*，措辞偏松 | 与实际"注入连接坐标"语义不符 | 文档瑕疵 |

经 brainstorming 与用户确认，**4 项均需修正**，其中 #1、#2 升级为架构级改造。

## 2. 目标

1. **DB 级强制隔离**：H2 / MySQL / PostgreSQL 三类数据库下，每个 `database` 权限插件获得独立的 DB 用户 + 独立 schema（或 database），由 DB 引擎层面 `GRANT` 限制其只能访问自己的命名空间——不依赖插件自觉遵守表前缀。SQLite 因引擎无 RBAC，作为**文档明确声明的技术例外**保留文件级隔离。
2. **Per-plugin 日志落盘**：每个插件有独立持久化日志文件 `<LOG_DIR>/plugin-<id>.log`，重启后保留，与宿主 `fengyu.log` 物理分离。
3. 补全 `PluginLogging.severity` switch 的 `default` 分支，遵循现有"非法值抛异常而非静默放宽输出"的防御风格。
4. 修正文档措辞，并把 DB 隔离文档与新架构对齐（含 `docs/zh` 镜像）。

## 3. 非目标

- 不改变 worker 的进程隔离模型（仍 out-of-process、JSON-RPC over stdio、独立 classpath）。
- 不共享宿主 Spring/JPA context、不传递活 `Connection`/`DataSource`/`EntityManager` 句柄（stdio 不可能，也未尝试）。
- 不对 SQLite 做 RBAC（引擎不支持；强行做等于自造 DB server，违背 YAGNI）。
- 不改变 SDK 的 stderr `@fengyu-log:` 日志协议、不改变 worker 捕获 / 脱敏 / 解析链路。
- 不实现 admin 凭据轮换 UI（首版只做采集 + 加密存储 + provisioning 使用）。
- 不实现远程 DB 跨 server 迁移工具。
- 不删除现有内存 `PluginLogStore`（保留为 SSE 实时源，磁盘是持久层）。

---

## 4. 总体架构

```
┌─────────────── HOST (Spring Boot JVM) ───────────────┐
│  [H2 only] org.h2.tools.Server (in-process TCP)       │
│            bind 127.0.0.1:<dynamic-port>              │
│            启动早于 probeAndDecide / DataSource bean    │
│                                                       │
│  PluginDbProvisioner (new)                            │
│    · admin 凭据 → CREATE USER / SCHEMA / GRANT         │
│    · per-plugin 凭据机密存储 → 注入 worker env         │
│                                                       │
│  Host DataSource (HikariCP, via tcp:// for H2)        │
└───────────────────────────────────────────────────────┘
        │ jdbc:h2:tcp://127.0.0.1:<port>/...    (H2)
        │ jdbc:mysql://host:port/<plugin-db>    (MySQL)
        │ jdbc:postgresql://host:port/...       (PostgreSQL)
        │ jdbc:sqlite:<host-allocated-path>     (SQLite, 例外)
        ▼
   ┌──── DB SERVER ────┐
   │  H2/PG: schema fengyu_<pluginA> ← GRANT ONLY pluginA_user
   │  H2/PG: schema fengyu_<pluginB> ← GRANT ONLY pluginB_user
   │  MySQL: db fengyu_<pluginA>     ← GRANT ONLY pluginA_user@127.0.0.1
   │  (host namespace)               ← GRANT host user only
   └───────────────────┘
```

### 4.1 隔离矩阵（核心契约）

| DB 类型 | 隔离机制 | worker 连接串 | 凭据 | provisioning |
|---|---|---|---|---|
| **H2** | DB 级（RBAC） | `jdbc:h2:tcp://127.0.0.1:<port>/...` | per-plugin user + 随机密码 | ✅ CREATE USER + SCHEMA + GRANT |
| **MySQL** | DB 级（RBAC） | `jdbc:mysql://host:port/fengyu_<plugin>` | per-plugin user + 随机密码 | ✅ CREATE USER + DATABASE + GRANT |
| **PostgreSQL** | DB 级（RBAC，schema 粒度） | `jdbc:postgresql://host:port/<host-db>?currentSchema=fengyu_<plugin>` | per-plugin role + 随机密码 | ✅ CREATE ROLE + SCHEMA + GRANT |
| **SQLite** | **文件级（例外）** | `jdbc:sqlite:<host-allocated-path>` | 空（SQLite 无 auth） | ❌ 宿主分配独立文件 |

**关键不变量**：
- worker 永远拿不到宿主运行时凭据或 admin 凭据，只拿到自己的受限凭据。
- DB 引擎层面拒绝越界访问，不依赖插件自觉。
- SQLite 是技术例外：引擎无 RBAC，文档明确声明；隔离靠宿主在插件数据目录下分配的独立文件。

---

## 5. 数据库隔离详细设计（#1）

### 5.1 H2 in-process TCP server（解决文件锁）

现状 H2 是 `jdbc:h2:file:{path}`，宿主持独占文件锁，worker 无法附加。`AUTO_SERVER=TRUE` 已被本仓库证实不可用（`PluginRuntimeEnvironmentService.java:83-88` 注释 + 测试 `embeddedH2WorkerGetsItsOwnDbFileWithoutAutoServer` 断言必须不含 `AUTO_SERVER`，OS sandbox 阻断）。

**改造**：H2 切换为**显式 in-process TCP server 模式**。

- H2 版本 2.4.240（`pom.xml:51`），`org.h2.tools.Server` 已在宿主 classpath（`FengYu/pom.xml:77-80` compile scope）。
- 新增 `@Configuration` bean `H2TcpServerConfig`（`@ConditionalOnProperty(fengyu.mode=app)` + 条件 `DbType==H2`），`@PostConstruct` 启动 `org.h2.tools.Server.createTcpServer("-tcp","-tcpHost","127.0.0.1","-tcpPort",<free-port>,"-tcpAllowOthers","false","-ifNotExists")`，`@PreDestroy` 停止。
- **端口**：用 `ServerSocket` 先在 `127.0.0.1:0` 占位读 `getLocalPort()` 后关闭，交给 `createTcpServer`（镜像 `PluginDevServer.java:198-209` 既有 idiom）。端口写入 `<config>/h2-server.properties`（明文，非敏感）供重启稳定 + 诊断。
- **生命周期顺序**（关键约束）：`HeadlessLauncher.probeAndDecide`（`HeadlessLauncher.java:90-114`）在 Spring 启动**之前**就用 JDBC 探测连接。因此 TCP server 必须在 `main()` 里 `probeAndDecide` **之前**启动，否则探测走 tcp:// 会连不上。即：server-start 从 Spring `@PostConstruct` 前移到 `HeadlessLauncher.main`，Spring bean 仅负责 stop。
- **host 自己的连接**也改走 tcp://（统一路径），`DbType.H2.urlTemplate` 由 `jdbc:h2:file:{path}` 改为 `jdbc:h2:tcp://127.0.0.1:{port}/{path}`，或由 `H2TcpServerConfig` 在 setup 保存后改写 `datasource.properties` 的 `db.url`。`DataSourceAutoConfig`（HikariCP，`DataSourceAutoConfig.java:36-45`）与 Hibernate dialect 不依赖 URL scheme（`H2Dialect` 不变），切换安全。
- **Sandbox 放行**：worker 沙箱在 `database` 权限下设 `allowNetwork=true`（`PluginProcessManager.java:189-191`），沙箱后端（bwrap `--unshare-net` / sandbox-exec `(deny network*)`）均为二值开关、无 host 过滤（`ProcessSandbox.java:171,179-180`），故 `127.0.0.1:<h2-port>` 可达。

### 5.2 Setup 向导新增独立 admin 凭据

现状向导（`SetupController`）只采集**一组**连接凭据（`WizardParams`），存 `datasource.properties` 的 `db.username` / `db.password`。

**改造**：远程 MySQL / PostgreSQL 与 H2 server 模式下，向导额外采集一组 **admin 凭据**（专门用于 provisioning 的 DDL：CREATE USER/SCHEMA/GRANT）。

- `WizardParams` 新增 `adminUsername` / `adminPassword` 字段（嵌入类型不显示，已有文件级隔离足够）。
- `DataSourceConfig` record 新增 `adminUsername` / `adminPassword` 字段（`null` 表示未配置；嵌入式 SQLite 不需要）。
- `datasource.properties` 新增 `db.admin.username` / `db.admin.password`（`CryptoUtil.encrypt` 机密存储，复用 `DataSourceConfigService.java:87,108` 既有机制 + `machineIdFile()`）。
- 向导 UI：选 MySQL / PostgreSQL / H2 时显示 admin 字段（可选，留空则 provisioning 不可用并明确报错）；选 SQLite 时隐藏。
- 向导在 `POST /api/setup/initialize`（`SetupController.java:125-154`）连测时，**额外**用 admin 凭据尝试 `CREATE USER`/`DROP` 一个临时占位用户以验证 admin 权限是否充足（幂等，验证后清理）。

### 5.3 PluginDbProvisioner（核心新组件）

新增 `fan.summer.fengyu.plugin.runtime.PluginDbProvisioner`，职责：用 admin 凭据为指定插件创建/复用受限 DB 凭据 + 命名空间。

**provisioning 流程**：
```
UI 授权确认 ──► POST /api/plugin-db/provision/{pluginId}   (新 controller endpoint)
   │
   ▼
PluginDbProvisioner.provision(pluginId, manifest):
   1. 读 admin 凭据（仅此刻读 datasource.properties）
   2. 幂等检查：读 <config>/plugin-db.properties 的该 plugin 记录
      └─ 已存在 → 直接返回已存凭据（不重复 CREATE）
   3. 生成 plugin 用户名 fengyu_plugin_<safe(id)> + 随机强密码（SecureRandom，32 字节 base64）
   4. 按 DB 类型用 admin 凭据执行 DDL：
      · H2:          CREATE USER IF NOT EXISTS / CREATE SCHEMA IF NOT EXISTS fengyu_<id>
                     / GRANT ALL ON SCHEMA fengyu_<id> TO fengyu_plugin_<id>
      · MySQL:       CREATE USER IF NOT EXISTS '...'@'127.0.0.1' IDENTIFIED BY '...'
                     / CREATE DATABASE IF NOT EXISTS fengyu_<id>
                     / GRANT ALL PRIVILEGES ON fengyu_<id>.* TO '...'@'127.0.0.1'
      · PostgreSQL:  CREATE ROLE <name> LOGIN PASSWORD '...'
                     / CREATE SCHEMA IF NOT EXISTS fengyu_<id> AUTHORIZATION <name>
                     / GRANT USAGE, CREATE ON SCHEMA fengyu_<id> TO <name>
                     (同 database 内 schema 粒度，避免 CREATE DATABASE 权限)
      · SQLite:      不 provisioning，走文件分配（见 5.5）
   5. 机密存储 plugin 凭据到 plugin-db.properties（AES-GCM，复用 CryptoUtil + machineIdFile()）
   6. 返回 worker env 注入所需的 DB_URL / DB_USERNAME / DB_PASSWORD
```

**幂等性**：provisioning 记录存 `<config>/plugin-db.properties`（机密文件，`SensitiveFilePermissions` 保护）。结构：`plugin.<pluginId>.dbType / userName / schemaName / password(加密) / provisionedAt`。重复 provision 同一插件先查记录，已存在直接返回；CREATE 语句全部带 `IF NOT EXISTS`。

**错误处理**：
- admin 凭据权限不足（无 CREATE USER/SCHEMA）→ 抛 `DbProvisioningException`，UI 明确提示"admin 凭据缺少 CREATE USER/SCHEMA 权限，请在设置中修正"，**不降级到共享凭据**。
- DDL 中途失败 → 尽力回滚（DROP 已创建的 user/schema），记录失败，不写 plugin-db.properties。
- 远程 DB 不可达 → 复用现有连接探测错误信息。

### 5.4 worker env 注入点改造

`PluginRuntimeEnvironmentService.environmentFor()`（`:48-78`）是唯一注入点，改造为：

```
manifest 无 database 权限 → 仅 LOG_LEVEL + PLUGIN_DATA_DIR（不变）
manifest 有 database 权限:
  · SQLite → resolveWorkerDbUrl 现有逻辑（宿主分配独立文件，不变）
  · H2/MySQL/PG → 查 plugin-db.properties 该插件记录:
      - 已 provisioned → 注入该插件受限凭据 + 该插件专属 URL/schema
      - 未 provisioned（用户尚未授权）→ 不注入任何 DB env（插件无 DB 访问权；UI 引导授权）
```

worker 永远拿不到 admin 凭据。`PluginDatabaseConfig`（SDK）字段不变，仍是 type/driver/url/username/password/dataDirectory，只是这些值现在来自 provisioning 而非宿主全局凭据。

### 5.5 SQLite 例外（文件级隔离，宿主分配路径）

SQLite 因 `sqlite-jdbc` 无 TCP server、引擎无 `CREATE USER`/`GRANT`，无法做 DB 级隔离。保留现状：

- 宿主在 `FENGYU_PLUGIN_DATA_DIR`（`<dataRoot>/<pluginId>/`）下为该插件分配一个独立 `.db` 文件，路径由宿主决定（`resolveWorkerDbUrl` 现有逻辑，`PluginRuntimeEnvironmentService.java:90-107`）。
- 「不允许插件自建本地 DB 文件」在此的语义 = **插件不能自选路径建库**，必须使用宿主分配的文件路径。
- 文档明确声明 SQLite 是 RBAC 隔离的技术例外，隔离粒度为文件级。

### 5.6 触发时机与 UI 授权

- **触发**：用户首次授权某插件使用 `database` 权限时（镜像现有 `network.email` 的二次确认模式）。
- 前端 `Settings.vue` / 插件管理页：对声明 `database` 且未 provisioned 的插件显示「授权数据库访问」按钮；点击 → 确认对话框（说明将为该插件创建独立 DB 用户/schema）→ `POST /api/plugin-db/provision/{pluginId}` → 成功后该插件可正常用 DB。
- 未授权的插件在 worker 启动时拿不到 DB env，其 DB 操作应优雅失败（插件自己处理，宿主引导授权）。

### 5.7 Deprovisioning（卸载清理）

- 卸载插件（`PluginUninstallService` 既有流程）时，新增步骤：`PluginDbProvisioner.deprovision(pluginId)` 用 admin 凭据 `DROP USER` + `DROP SCHEMA`/`DROP DATABASE` + 删 plugin-db.properties 记录。
- deprovisioning 失败**不阻塞卸载**：记录遗留 + 日志告警，下次启动可重试清理。
- SQLite 无 deprovisioning（直接删数据目录下的 db 文件，属现有卸载清理范围）。

---

## 6. 日志落盘详细设计（#2）

### 6.1 方案：Logback SiftingAppender + MDC，per-plugin 文件

纯宿主侧改造，**不碰 SDK / worker / 捕获线程 / 脱敏 / 解析链路**。

现状 `forwardPluginLog`（`PluginProcessManager.java:312-326`）已把每条插件事件转发到宿主 SLF4J logger `plugin.<pluginId>.<source>`。改造点：

1. **`forwardPluginLog` 加 MDC**：进入时 `MDC.put("pluginId", pluginId)`，转发后 `finally` `MDC.remove("pluginId")`。MDC key 限单线程作用域，不影响并发。
2. **`logback.xml` 增 SiftingAppender**：针对 `plugin.*` logger，新增一个 `SiftingAppender`，按 MDC `pluginId` 分流到 `${LOG_DIR}/plugin-${pluginId}.log`：
   ```xml
   <appender name="PLUGIN_FILE" class="ch.qos.logback.classic.sift.SiftingAppender">
     <discriminator class="ch.qos.logback.classic.sift.MDCBasedDiscriminator">
       <key>pluginId</key>
       <defaultValue>unknown</defaultValue>
     </discriminator>
     <sift>
       <appender name="PLUGIN_FILE-${pluginId}" class="ch.qos.logback.core.rolling.RollingFileAppender">
         <file>${LOG_DIR}/plugin-${pluginId}.log</file>
         <rollingPolicy class="...SizeAndTimeBasedRollingPolicy">
           <fileNamePattern>${LOG_DIR}/plugin-${pluginId}.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
           <maxFileSize>10MB</maxFileSize>
           <maxHistory>7</maxHistory>
           <totalSizeCap>50MB</totalSizeCap>
         </rollingPolicy>
         <encoder>
           <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger - %msg%n</pattern>
           <charset>UTF-8</charset>
         </encoder>
       </appender>
     </sift>
   </appender>
   <logger name="plugin" level="INFO" additivity="true">
     <appender-ref ref="PLUGIN_FILE"/>
   </logger>
   ```
   - `additivity="true"`：事件同时落 `plugin-<id>.log` 与 `fengyu.log`（后者向后兼容，便于统一排查）。
   - 无日志的插件不产生空文件（SiftingAppender 惰性创建）。
3. **滚动**：按大小（10MB）+ 按天，保留 7 天，单插件总量 50MB（与宿主 200MB 风格一致，spec 定精确值）。

### 6.2 与现有链路关系

- **`PluginLogStore`（500 内存环形）不变**：仍是 SSE `/logs/stream` 的实时源；SSE 重放走内存历史。
- **磁盘是新增持久层**：重启后保留，供用户 / 运维事后查看；与内存解耦。
- **REST 暴露（首版最小）**：现有 `GET /api/plugin-runtime/{id}/logs` 仍返回内存历史。磁盘文件首版**不新增下载 endpoint**，用户可直接打开 `<LOG_DIR>/plugin-<id>.log`；后续视需求再加 `GET .../logs/file`。

### 6.3 卸载时的日志文件

卸载插件时**保留** `plugin-<id>.log`（便于事后排查），不自动删除。如需清理可后续加 `--purge-logs` 选项，首版不做。

---

## 7. 小修（#3、#4）

### 7.1 #3 — `PluginLogging.severity` 补 default

`toolchain/sdk-java/.../PluginLogging.java:47-55`：

```java
private static int severity(Level level) {
    return switch (level) {
        case TRACE -> 0;
        case DEBUG -> 1;
        case INFO  -> 2;
        case WARN  -> 3;
        case ERROR -> 4;
    };
}
```

改为补 `default` 抛 `IllegalArgumentException`，遵循同类 `Threshold.parse`（`:73`）"非法值抛异常而非静默放宽输出"的防御风格：

```java
private static int severity(Level level) {
    return switch (level) {
        case TRACE -> 0;
        case DEBUG -> 1;
        case INFO  -> 2;
        case WARN  -> 3;
        case ERROR -> 4;
        default -> throw new IllegalArgumentException("Unsupported plugin log level: " + level);
    };
}
```

> 注：当前 `isEnabled()` 在 `OFF` 时短路（`:38`），`OFF` 不会进此 switch，故现有行为不变；此 default 仅防御未来 SLF4J `Level` 扩展。新增 SDK 测试覆盖该 default。

### 7.2 #4 — 文档措辞 + 隔离文档重写

- `docs/en/plugins/manifest.md:87`：`"a datasource connection"` → `"connection coordinates (type/driver/url/username/password), provisioned by the host as an isolated DB user/schema"`。
- `docs/en/plugins/database.md`：重写 DB 隔离章节，与新架构对齐——
  - 删除"远程 DB 透传宿主同一凭据"的隐含描述；
  - 新增"H2/MySQL/PostgreSQL DB 级强制隔离（per-plugin user/schema/GRANT）"章节；
  - 新增"SQLite 文件级隔离例外"声明；
  - 新增"admin 凭据与 provisioning 流程"章节；
  - 表前缀约定从"隔离的唯一手段"降级为"DB 级隔离之上的命名整洁建议"。
- `docs/zh/plugins/manifest.md`、`docs/zh/plugins/database.md`：镜像同步（结构对齐）。
- 同步 `docs/en/plugins/worker.md`、`docs/zh/plugins/worker.md` 的日志章节，说明 per-plugin 落盘文件位置与滚动策略。

---

## 8. 受影响文件清单

**Java（宿主）**
- 新增 `FengYu/.../plugin/runtime/PluginDbProvisioner.java`
- 新增 `FengYu/.../config/H2TcpServerConfig.java`
- 新增 `FengYu/.../plugin/runtime/PluginDbController.java`（`/api/plugin-db/provision/{id}`）
- 改 `FengYu/.../plugin/runtime/PluginRuntimeEnvironmentService.java`（注入点按 provisioning 改造）
- 改 `FengYu/.../HeadlessLauncher.java`（H2 TCP server 启动前移到 probeAndDecide 之前）
- 改 `FengYu/.../setup/DataSourceConfig.java`（+admin 字段）
- 改 `FengYu/.../setup/DataSourceConfigService.java`（+admin 读写）
- 改 `FengYu/.../setup/WizardParams.java`（+admin 字段）
- 改 `FengYu/.../setup/SetupController.java`（向导 admin 字段 + 校验）
- 改 `FengYu/.../plugin/runtime/PluginProcessManager.java`（`forwardPluginLog` 加 MDC）
- 改 `FengYu/src/main/resources/logback.xml`（+PLUGIN_FILE SiftingAppender）
- 新增对应测试

**Java（SDK）**
- 改 `toolchain/sdk-java/.../PluginLogging.java`（#3 default）
- 新增 `toolchain/sdk-java/src/test/.../PluginLoggingTest.java` 用例

**前端**
- 改 `frontend/src/views/Settings.vue` / 插件管理（database 授权按钮 + 确认框）
- 改 `frontend/src/i18n/{en,zh}.json`（授权文案）

**文档**
- 改 `docs/en/plugins/{manifest,database,worker}.md`
- 改 `docs/zh/plugins/{manifest,database,worker}.md`

**Spec / Schema**
- `toolchain/spec/manifest.schema.json`：`database` 权限语义不变（仍 enum 之一），不改 schema。

## 9. 测试策略

- **SDK**：`PluginLoggingTest` 新增 default 抛异常用例。
- **Provisioner**：用 Testcontainers（MySQL / PG）+ 嵌入式 H2 TCP 跑 provision / 幂等 / deprovision / 权限不足错误。
- **环境注入**：`PluginRuntimeEnvironmentServiceTest` 扩展——已 provisioned 注入受限凭据、未 provisioned 不注入、SQLite 仍走文件。
- **H2 server**：单测 `H2TcpServerConfig` 启停 + 动态端口 + loopback 绑定 + host 自连 tcp://。
- **日志落盘**：集成测验证 `forwardPluginLog` 后 `plugin-<id>.log` 生成、滚动、与 `fengyu.log` 双写、重启不丢。
- **向导**：admin 凭据字段读写、加密、权限校验。
- **e2e**：`scripts/e2e-smoke.sh` 不覆盖 provisioning（需真实 DB），单独留 Testcontainers CI job。

## 10. 风险与回滚

- **H2 server 端口冲突**：动态端口 + 启动失败明确报错，不影响 setup（setup 阶段尚未起 server）。
- **probeAndDecide 顺序**：H2 server 必须先于探测启动；单测覆盖该顺序，CI 验证。
- **admin 凭据未配置**：provisioning 报错引导，不降级共享凭据（安全优先）。
- **现有 SQLite/H2 file 用户迁移**：H2 现有 file-mode 用户首次启动需切 server 模式——`datasource.properties` 的 `db.url` 在 setup/H2TcpServerConfig 启动时由宿主改写为 tcp://，附迁移说明；SQLite 用户零迁移。
- **回滚**：各改造点独立，DB 隔离与日志落盘可分别 revert；SDK #3 是独立小提交。
