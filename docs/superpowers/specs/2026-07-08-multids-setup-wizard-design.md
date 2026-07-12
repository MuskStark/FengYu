# 多数据源初始化向导 + JPA 迁移 + 用户体系预留

**Date:** 2026-07-08
**Branch:** 4.0.0-FengYu
**Status:** Design (pending implementation plan)
**Roadmap alignment:** Phase 4 (多数据源首次部署向导) + 前置的 ORM 现代化 + 用户体系架构预留

---

## 1. 背景与目标

### 1.1 现状

- **数据库层**:MyBatis 手写(XML mapper + 接口),12 个实体/mapper。`DatabaseInit.init()` 在 `main()` 里 Spring 上下文之前同步初始化,只支持 H2,连接 URL 硬编码在静态块。
- **配置**:无 `application.yml`。端口/地址/token 走命令行参数(`HeadlessLauncher` 解析)。这是 sidecar 部署模式的刻意设计,本 spec 不改变动态项的命令行策略。
- **生产代码 DB 调用点**:仅 3 处——`AiConfigService`(静态工具类)、`AiConfigServiceHeadless`(静态工具类)、`EmailUtil`。`AiBackendInitializer`(@ApplicationRunner)间接通过 `AiConfigService` 依赖 DB。
- **部署**:Tauri 桌面壳拉起 Java sidecar(`HeadlessLauncher`)。父进程通过 stdout 的 `FENGYU_PORT=<n>` 读端口,通过 `window.__FENGYU_TOKEN__` 注入 token。
- **用户体系**:无。当前是单用户本地应用。

### 1.2 目标

本次交付三件事:

1. **多数据源初始化向导**:首次启动(`~/.fengyu/config/datasource.properties` 不存在)时,后端以"最小上下文"启动提供向导 API,前端渲染全屏向导,用户选择数据库类型(H2 / SQLite / MySQL / PostgreSQL)并配置连接,后端建表、持久化配置、触发重启进入正常应用模式。
2. **全量 MyBatis → JPA 迁移**:12 个实体加 `@Entity`,12 个 mapper 接口改成 Spring Data JPA Repository,12 个 mapper XML 删除,`DatabaseInit` 删除,schema 交由 Hibernate `ddl-auto=update` 管理。动机:多数据源方言适配 + ORM 现代化。
3. **用户体系架构预留**:本地离线模式与未来 Web 多用户/SSO 统一为单体内 `AuthProvider` 接口(不拆独立服务)。本次只建 `sys_user`/`sys_session` 表、业务表加 `user_id` 行级隔离字段、定义接口与 Noop 实现,**不实现登录 UI 与认证流程**。

### 1.3 非目标(本次不做)

- 登录 UI、密码校验、session/JWT 颁发、OAuth2/SSO 对接(预留接口,后续 phase 实现)
- 多数据源运行时切换(本次单数据源)
- "导入已有数据库"功能(用户可直接手写 `datasource.properties` 跳过向导)
- 独立用户服务(user-service)——明确不做,单体内统一
- Flyway/Liquibase(schema 由 ddl-auto 管理,后续版本演进可再引入)

---

## 2. 关键设计决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 启动时序 | 方案 A:最小上下文 + 进程重启 | 未配置时连 DataSource bean 都不存在,JPA 自动配置完全跳过,时序最干净 |
| Schema 管理 | Hibernate `ddl-auto=update` | 零 DDL 文件维护,方言自适应 H2/SQLite/MySQL/PG |
| 配置存储 | `~/.fengyu/config/datasource.properties`,密码 AES 加密 | 本地桌面主场景,轻量,不引 Jasypt |
| Web 重启 | Spring Boot Actuator `/restart` | 不依赖进程退出码 |
| 用户架构 | 单体内统一,不拆服务 | 本地桌面是主场景,Web 多用户规模有限,避免过度设计 |
| 数据隔离 | 行级 `user_id` | 核心诉求是多账号数据隔离 |
| 本地虚拟用户 | id=1, username=`ZFlow-Summer`,管理员 | 固定常量,数据可移植,排查友好 |
| 认证抽象 | 可插拔 `AuthProvider` 接口 + Noop 实现 | 不堵死未来 SSO |
| JPA 迁移 | 全量迁移,AiConfigService 静态→@Component | 既然现代化就一次做对 |

---

## 3. 启动时序(方案 A)

```
HeadlessLauncher.main(args)
  │
  ├─ primeLogDirectory()                              (现有,保留)
  ├─ LoggerBinder.bind(new Slf4jPluginLoggerBinder()) (现有,保留)
  ├─ 解析 --port= / --token=,设置 TOKEN_PROPERTY      (现有,保留)
  │
  ├─ 读 ~/.fengyu/config/datasource.properties
  │     │
  │     ├─【不存在/空/解析失败】→ SETUP 模式
  │     │     启动最小 Spring 上下文:
  │     │       • SetupController + DataSourceConfigService
  │     │       • 静态前端资源(Vue build 产物,含向导页)
  │     │       • PortAnnouncer(同现有,打印 FENGYU_PORT)
  │     │       • 排除:DataSource/JPA/AI/Plugin 自动配置
  │     │       • TokenAuthFilter 放行 /api/health + /api/setup/*
  │     │     前端检测 GET /api/setup/status → 渲染向导
  │     │     向导:选 DB → 填配置 → 测试 → 初始化
  │     │       → 写 datasource.properties(密码加密)
  │     │       → 建表(ddl-auto)+ 插入虚拟用户(id=1)
  │     │       → System.exit(SETUP_DONE=0)
  │     │
  │     └─【存在且有效】→ APP 模式
  │           解密密码 → 构造 DataSource bean(HikariCP)
  │           JPA EntityManagerFactory(ddl-auto=update)
  │           VirtualUserInitializer 确保 id=1 虚拟用户存在
  │           全量 bean:AI、Plugin、Controller…
  │           AiBackendInitializer 读 AI 配置(现状行为)
  │           PortAnnouncer 打印端口
  │
  └─ Tauri main.rs:
        sidecar 退出 → 读退出码
          ├─ SETUP_DONE(0) → 重新拉起 sidecar(进入 APP 模式)
          └─ 其他 → 报错
```

### 3.1 模式判据

`datasource.properties` 的存在性是唯一判据。文件不存在、为空、或 `db.type` 字段缺失 → SETUP 模式。文件存在且 `db.type` 合法 → APP 模式。

### 3.2 SETUP 上下文的纯净性

SETUP 模式的最小上下文**不加载任何 DB 依赖 bean**。`SetupController` 与 `DataSourceConfigService` 都不注入 EntityManager/Repository。DDL 执行通过临时 `EntityManagerFactory`(用向导提交的 DataSource 构造),用完即关,不污染上下文。这通过 `@SpringBootApplication(exclude=...)` 或 `spring.autoconfigure.exclude` 排除 DataSource/JPA 自动配置实现。

### 3.3 端口与 token 在两种模式下的一致性

SETUP 模式启动的也是同一个 `HeadlessLauncher.main()`,`--port=` / `--token=` 命令行参数照样解析生效。`PortAnnouncer` 是监听 `WebServerInitializedEvent` 的 `@Component`,SETUP 模式的嵌入式 Tomcat 照样触发事件,`FENGYU_PORT=<n>` 照样打印。**前端获取端口/token 的方式在两种模式下完全一致**,不需要改 `frontend/src/api/config.ts`。

---

## 4. JPA 迁移

### 4.1 依赖变更(`FengYu/pom.xml`)

| 变更 | 说明 |
|---|---|
| **新增** `spring-boot-starter-data-jpa` | JPA + Hibernate + Spring Data |
| **新增** `spring-boot-starter-actuator` | Web 部署的 `/restart` 端点 |
| **新增** `com.mysql:mysql-connector-j`(runtime) | MySQL 驱动 |
| **新增** `org.postgresql:postgresql`(runtime) | PostgreSQL 驱动 |
| **新增** `org.xerial:sqlite-jdbc`(runtime) | SQLite 驱动 |
| **新增** `org.hibernate.orm:hibernate-community-dialects` | SQLite 方言(`SQLiteDialect`) |
| **保留** `com.h2database:h2` | 已有 |
| **移除** `org.mybatis:mybatis` | 迁移完成后删除 |
| **删除** `src/main/resources/mybatis-config.xml` | 迁移后无用 |
| **删除** `src/main/resources/mapper/**/*.xml`(12 个) | 迁移后无用 |

### 4.2 实体迁移(12 个 POJO → `@Entity`)

每个实体加 JPA 注解,沿用现有 `init.sql` 的 snake_case 表名/列名(显式 `@Table(name=)`/`@Column(name=)`)。主键统一 `@GeneratedValue(strategy = GenerationType.IDENTITY)`。

示例(`AppSettingEntity`):

```java
@Entity
@Table(name = "app_setting",
       uniqueConstraints = @UniqueConstraint(columnNames = {"userId", "settingKey"}))
@Data
public class AppSettingEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "setting_key", nullable = false) private String settingKey;
    @Column(name = "setting_value", length = 1000)  private String settingValue;

    /** 用户隔离字段。本地离线模式恒为 1(虚拟用户)。NULL 不允许,默认 1。 */
    @Column(name = "user_id", nullable = false) private Long userId = 1L;
}
```

**字段类型约定**:
- Boolean 字段(如 `needTLS`/`needSSL`)→ JPA 映射为 `BOOLEAN`(H2/PG)或 `TINYINT(1)`(MySQL),Hibernate 方言自动处理
- `LocalDateTime` 字段 → `TIMESTAMP`,时间戳默认值由应用层 `@PrePersist` 兜底(ddl-auto 对 DB 默认值支持弱)

### 4.3 Mapper → Repository(12 个接口)

MyBatis mapper 接口 → Spring Data JPA Repository,查询按 `userId` 过滤。示例:

```java
public interface AppSettingRepository extends JpaRepository<AppSettingEntity, Integer> {
    Optional<AppSettingEntity> findByUserIdAndSettingKey(Long userId, String key);
    List<AppSettingEntity> findAllByUserId(Long userId);
}
```

复杂查询(mapper XML 里的关键词搜索等)用 `@Query("JPQL")` 或方法名派生替代。每个 repository 独立文件。

### 4.4 `DatabaseInit` 删除

`DatabaseInit` 类整体删除:
- `init()` / `createTables()`(跑 init.sql)/ `initMyBatis()` → ddl-auto + EntityManagerFactory 接管
- `getSqlSession()` / `withSession` / `withMapper` / `getSqlSessionFactory` → Repository 注入替代

调用点迁移(生产代码仅 3 处):
- `AiConfigService`(静态工具类 → `@Component`,注入 `AppSettingRepository`)
- `AiConfigServiceHeadless`(静态工具类 → `@Component`,注入 `AppSettingRepository`)
- `EmailUtil`(注入 `FengYuSettingEmailRepository`)

### 4.5 `AiConfigService` 静态 → bean

`AiConfigService` 当前是静态工具类,被 `AiBackendInitializer`(@ApplicationRunner)等静态调用。转 JPA 后必须注入 Repository,本次改为 `@Component`,所有调用方注入它。触面:`AiBackendInitializer`、`AiConfigServiceHeadless`、其他静态调用点。

### 4.6 `init.sql` 的命运

`init.sql` 保留但**不再被代码执行**。作用:文档参考(记录表结构设计意图)、迁移期间可选 fallback、未来 Flyway 的 V1 基线。

### 4.7 `application.yml`(新增)

静态配置进入配置文件,动态项(port/token)仍走命令行:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        format_sql: true
    open-in-view: false
  # DataSource 不在此配 —— 由 DataSourceConfig bean 在 APP 模式动态提供

server:
  address: 127.0.0.1            # 从 HeadlessLauncher 命令行参数迁过来(静态安全不变量)

management:
  endpoint:
    restart:
      enabled: true
  endpoints:
    web:
      exposure:
        include: restart,health
```

`server.port` 与 `fengyu.auth.token` **仍走命令行**(运行时动态)。

---

## 5. 配置存储与数据源管理

### 5.1 配置文件:`~/.fengyu/config/datasource.properties`

```properties
# 数据库类型: h2 | sqlite | mysql | postgresql
db.type=h2

# JDBC 连接(由 DataSourceConfigService 根据 type + 参数拼装)
# 路径为相对于 user.dir 的相对路径,或绝对路径(DataSourceConfigService 加载时解析)
db.url=jdbc:h2:file:.fengyu/data/fengyu;AUTO_SERVER=TRUE
db.driver=org.h2.Driver
db.dialect=org.hibernate.dialect.H2Dialect

# 远程数据库才有(H2/SQLite 留空)
db.username=
db.password=ENC(<aes加密的base64>)

# 嵌入式数据库的文件位置(H2/SQLite 用),相对 user.dir 或绝对路径
db.file.path=.fengyu/data/fengyu
```

`db.type` 是顶层判别字段,SETUP 与 APP 启动都先读它。

**路径解析**:`db.url` / `db.file.path` 中的相对路径相对 `user.dir` 解析;`DataSourceConfigService` 加载时把相对路径转绝对路径后注入 DataSource,避免工作目录差异导致找不到文件。不使用 `~`(Java properties 不自动展开 home 目录)。

### 5.2 `DataSourceConfigService`(新建,`setup/` 包)

```java
@Service
public class DataSourceConfigService {
    DataSourceConfig load();                  // 不存在/无效返回 null → 触发 SETUP
    void save(DataSourceConfig cfg);          // 写文件,密码加密
    DataSourceConfig buildFromWizard(DbType type, WizardParams params);  // 不落盘
    ConnectionTestResult testConnection(DataSourceConfig cfg);           // 不落盘
}
```

`DataSourceConfig` 是 record,封装 type/url/driver/dialect/username/password/filepath。

### 5.3 密码加密(不引 Jasypt)

轻量内置 AES 工具 `CryptoUtil`:
- **密钥派生**:固定项目常量 + 机器特征(`~/.fengyu/config/.machineid`,首次生成随机 UUID)拼接,SHA-256 → AES-256 key。加密文件换机器解不开。
- **算法**:`AES/GCM/NoPadding`
- **向后兼容**:读取时检测 `ENC(...)` 前缀,没有当明文(支持开发期手写)

### 5.4 `DataSource` bean(APP 模式)

```java
@Configuration
public class DataSourceAutoConfig {
    @Bean
    @ConditionalOnProperty(name = "fengyu.mode", havingValue = "app")
    public DataSource dataSource(DataSourceConfigService svc) {
        DataSourceConfig cfg = svc.load();
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(cfg.url());
        ds.setDriverClassName(cfg.driver());
        if (!cfg.username().isBlank()) {
            ds.setUsername(cfg.username());
            ds.setPassword(svc.decryptPassword(cfg));
        }
        return ds;
    }
}
```

用 HikariCP 替代 MyBatis 的 POOLED。`EntityManagerFactory` 由 Spring Boot JPA 自动配置创建,`hibernate.dialect` 从 `db.dialect` 注入。

### 5.5 四种 DB 的方言与连接模板

| type | driver | dialect | url 模板 | embedded |
|---|---|---|---|---|
| h2 | `org.h2.Driver` | `H2Dialect` | `jdbc:h2:file:{path};AUTO_SERVER=TRUE` | true |
| sqlite | `org.sqlite.JDBC` | `SQLiteDialect`(community) | `jdbc:sqlite:{path}` | true |
| mysql | `com.mysql.cj.jdbc.Driver` | `MySQLDialect` | `jdbc:mysql://{host}:{port}/{db}` | false |
| postgresql | `org.postgresql.Driver` | `PostgreSQLDialect` | `jdbc:postgresql://{host}:{port}/{db}` | false |

---

## 6. 用户体系预留(本次只留架构,不实现登录)

### 6.1 架构定性:不拆服务,单体内统一

```
FengYu 单体应用(同一 JAR,两种部署)
  ┌──────────────────────────────────────────┐
  │  AuthProvider 接口(可插拔)              │
  │    ├─ NoopAuthProvider   (本地离线)      │  ← 本次实现
  │    ├─ LocalAuthProvider  (本地多账号)    │  ← 后续 phase
  │    └─ SsoAuthProvider    (OAuth2/OIDC)   │  ← 后续 phase
  └──────────────────────────────────────────┘
                  │
                  ▼
  ┌──────────────────────────────────────────┐
  │  SecurityContext.currentUserId()         │
  │    (本地离线恒=1;登录态=真实 user_id)   │
  └──────────────────────────────────────────┘
                  │
                  ▼
  ┌──────────────────────────────────────────┐
  │  sys_user / sys_session 表(本次建表)   │
  │  业务表 user_id 字段(本次加,行级隔离) │
  └──────────────────────────────────────────┘

部署形态:
  • Tauri 本地桌面(离线 OR 本地多账号)
  • Web 服务器(多用户,同一套表 + session)
```

### 6.2 数据模型

**`sys_user` 表**(本次建表,不用):

```java
@Entity @Table(name = "sys_user")
@Data
public class SysUserEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String username;           // 登录名

    @Column(length = 255)              // bcrypt 哈希;SSO 用户 / 虚拟用户留空
    private String passwordHash;

    @Column(length = 255)              // SSO 来源:local / github / google / oidc
    private String authProvider;       // 虚拟用户为 "local"

    @Column(length = 255)              // SSO 提供商的用户唯一 ID
    private String externalId;

    @Column(nullable = false)
    private Integer status;            // 1=启用 0=禁用

    @Column(nullable = false)
    private Integer userType;          // 0=普通 1=管理员

    @CreationTimestamp
    private LocalDateTime createdAt;
}
```

**`sys_session` 表**(本次建表,不用):

```java
@Entity @Table(name = "sys_session")
@Data
public class SysSessionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 500, nullable = false, unique = true)
    private String token;              // session token 或 JWT jti
    private Long userId;
    @Column(length = 100)
    private String clientIp;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
}
```

### 6.3 业务表 `user_id` 行级隔离

本次迁移的 12 个实体分两类:

**用户私有数据(加 `user_id`,查询过滤)**:
- `app_setting`(主题/语言等个性化)
- `swiss_kit_setting_email`(邮件账号配置)
- `email_address_book` / `email_tag` / `email_mass_sent_config` / `email_sent_log` / `email_archive`(邮件相关)
- `complex_split_config`(Excel 任务配置)
- `plugin_favorites`(收藏)
- `plugin_setting`(插件设置)
- `menu_order`(菜单顺序)

**全局/系统数据(不加 `user_id`)**:
- `plugin_manager`(插件清单,系统级共享)

**唯一约束调整**(用户私有表,全局唯一 → 复合唯一):
- `app_setting`: `UNIQUE(setting_key)` → `UNIQUE(user_id, setting_key)`
- `plugin_favorites`: `UNIQUE(plugin_id)` → `UNIQUE(user_id, plugin_id)`
- `plugin_setting`: `UNIQUE(plugin_id, setting_key)` → `UNIQUE(user_id, plugin_id, setting_key)`
- 其他私有表同理

### 6.4 虚拟用户常量

```java
public final class SecurityConstants {
    private SecurityConstants() {}

    /** 本地离线模式的虚拟用户 ID。所有未登录请求归属此用户。 */
    public static final long LOCAL_VIRTUAL_USER_ID = 1L;

    /** 本地虚拟用户的固定用户名。 */
    public static final String LOCAL_VIRTUAL_USERNAME = "ZFlow-Summer";
}
```

**虚拟用户记录**(启动时确保存在):
- `id` = 1
- `username` = `ZFlow-Summer`
- `passwordHash` = NULL(离线模式无需密码)
- `authProvider` = `local`
- `externalId` = NULL
- `status` = 1(启用)
- `userType` = 1(管理员)

**实现注意**:`sys_user` 的 `@GeneratedValue(IDENTITY)` 通常会忽略显式 id,固定 id=1 需特殊处理(SEQUENCE/TABLE 策略,或 `existsById` 后用原生 SQL 插入固定 id)。实现细节在 plan 里定。

### 6.5 认证抽象层

```java
public interface AuthProvider {
    AuthResult authenticate(AuthRequest req);   // 失败抛 AuthException
    boolean isEnabled();                         // 本地离线返回 false(免登录)
}

public interface SecurityContext {
    Long currentUserId();      // 本地模式恒返回 1L
    boolean isAuthenticated();
}
```

**Noop 实现**(本次唯一实现):
- `NoopAuthProvider.isEnabled()` = false
- `NoopSecurityContext.currentUserId()` = `LOCAL_VIRTUAL_USER_ID`(1L),`isAuthenticated()` = true

### 6.6 Filter 层协同

```
请求 → TokenAuthFilter(@Order(1),进程级,防隔壁进程)
     → AuthFilter(@Order(2),用户级,本次预留接口但不挂载实际拦截)
     → Controller
```

现有 `TokenAuthFilter` 是进程级 token,与用户级认证正交。本次不新增 AuthFilter 的实际拦截,但 spec 明确:后续 phase 实现登录后,AuthFilter 在 TokenAuthFilter 之后执行,从 session/JWT 解析 userId 塞进 SecurityContext。

### 6.7 本地模式 user_id 取值约定

| 模式 | 认证 | user_id |
|---|---|---|
| 本地离线(默认) | NoopAuthProvider | 恒 1(虚拟用户) |
| 本地多账号(后续) | LocalAuthProvider | 登录后真实 id(2,3,4...) |
| Web 多用户(后续) | LocalAuthProvider 或 SsoAuthProvider | 登录后真实 id |

本地离线下 user_id 恒为 1,效果等价于全局共享,但代码路径统一走 `SecurityContext.currentUserId()`,切换登录态零改动。

### 6.8 Repository 查询模式

```java
public interface AppSettingRepository extends JpaRepository<AppSettingEntity, Integer> {
    Optional<AppSettingEntity> findByUserIdAndSettingKey(Long userId, String key);
}

// 调用方
Long uid = securityContext.currentUserId();
appSettingRepo.findByUserIdAndSettingKey(uid, key);
```

---

## 7. 后端 Setup API

### 7.1 包结构

```
fan/summer/fengyu/setup/
  ├── SetupController.java           REST 端点
  ├── DataSourceConfigService.java   读写 datasource.properties + 加解密
  ├── DataSourceConfig.java          连接配置 record
  ├── DbType.java                    枚举:H2/SQLITE/MYSQL/POSTGRESQL
  ├── WizardParams.java              向导提交的原始参数 record
  ├── ConnectionTestResult.java      测试连接结果 record
  └── SetupMode.java                 SETUP 模式判断
```

### 7.2 `DbType` 枚举

```java
public enum DbType {
    H2("org.h2.Driver", "org.hibernate.dialect.H2Dialect",
       "jdbc:h2:file:{path};AUTO_SERVER=TRUE", true),
    SQLITE("org.sqlite.JDBC", "org.hibernate.community.dialect.SQLiteDialect",
       "jdbc:sqlite:{path}", true),
    MYSQL("com.mysql.cj.jdbc.Driver", "org.hibernate.dialect.MySQLDialect",
       "jdbc:mysql://{host}:{port}/{db}", false),
    POSTGRESQL("org.postgresql.Driver", "org.hibernate.dialect.PostgreSQLDialect",
       "jdbc:postgresql://{host}:{port}/{db}", false);

    public final String driver, dialect, urlTemplate;
    public final boolean embedded;   // true=本地嵌入式,无需 host/port
}
```

新增数据库类型只改这一处。

### 7.3 REST 端点(均在 `/api/setup` 下,TokenAuthFilter 放行)

**`GET /api/setup/status`** —— 前端启动时第一个调用:
```json
SETUP 模式: { "initialized": false, "supportedTypes": ["h2","sqlite","mysql","postgresql"], "embeddedTypes": ["h2","sqlite"] }
APP 模式:   { "initialized": true }
```

**`GET /api/setup/types`** —— 每种数据库类型的表单元数据:
```json
[
  { "type":"h2", "label":"H2 (本地嵌入式)", "embedded":true, "fields":[
      {"name":"filePath","label":"数据文件位置","required":true,"default":".fengyu/data/fengyu"} ]},
  { "type":"mysql", "label":"MySQL (远程)", "embedded":false, "fields":[
      {"name":"host","required":true},{"name":"port","default":3306},
      {"name":"database","required":true},{"name":"username","required":true},
      {"name":"password","required":true,"secret":true} ]}
]
```

**`POST /api/setup/test-connection`** —— 不落盘,临时测连通性:
```json
请求: { "type":"mysql", "params":{"host":"...","port":3306,"database":"...","username":"...","password":"..."} }
成功: { "success":true, "dialect":"MySQLDialect", "serverVersion":"8.0.36" }
失败: { "success":false, "error":"Connection refused: localhost:3306" }
```
实现:临时 HikariDataSource 连 `SELECT 1`(或方言等价),3 秒超时,立即关闭。

**`POST /api/setup/initialize`** —— 正式初始化(原子操作):
```json
请求: { "type":"h2", "params":{"filePath":".fengyu/data/fengyu"} }
成功: { "success":true, "action":"restart" }
失败: { "success":false, "error":"...", "step":"ddl" }   // step: connection | ddl | save
```
执行步骤(全部成功才提交):
1. `buildFromWizard` 构造完整 DataSourceConfig
2. 二次验证连接(防 test 与 initialize 间环境变化)
3. 临时 EntityManagerFactory 触发 ddl-auto 建表 + 插入虚拟用户(id=1)
4. `save` 写 datasource.properties(密码加密)
5. 响应发出后延迟 1 秒 `System.exit(SETUP_DONE=0)`

失败时回滚:删除已写的 datasource.properties,保持"未初始化"可重入。

### 7.4 退出码约定

```java
public final class ExitCodes {
    public static final int SETUP_DONE = 0;   // 向导完成,请求重启进入 APP
    public static final int FATAL = 1;        // 致命错误
}
```

Tauri `main.rs`:sidecar 退出码 0 → 重启;其他 → 报错。Web 场景用 actuator 不依赖退出码。

### 7.5 `TokenAuthFilter` 改动

放行路径增加 `/api/setup/`(前缀匹配),使向导端点在 SETUP 模式可被前端访问。

---

## 8. 前端向导 UI

### 8.1 启动期路由分流

```ts
// router/index.ts —— 新增全局守卫
router.beforeEach(async (to) => {
  if (to.path === '/setup') return true
  const status = await api.getSetupStatus()
  if (!status.initialized) return '/setup'
  if (to.path.startsWith('/setup')) return '/'
})
```

`/setup` 路由独立于 AppShell(全屏向导):

```ts
{ path: '/setup', name: 'setup', component: () => import('@/views/SetupWizard.vue') }
```

App.vue 层面向导页不套 AppShell:

```vue
<template>
  <router-view v-if="$route.path === '/setup'" />
  <AppShell v-else />
</template>
```

### 8.2 向导页面结构(三步式)

```
SetupWizard.vue
  ├─ Step 1: 选择数据库类型(卡片:H2 / SQLite / MySQL / PostgreSQL)
  ├─ Step 2: 填写连接信息(动态表单,按 type 渲染字段)
  │    嵌入式:  [数据文件位置]
  │    远程:    [host] [port] [database] [username] [password]
  │             [ 测试连接 ] → 成功(绿)/失败(红)
  └─ Step 3: 确认并初始化
       汇总 + [ 完成初始化 ] → initialize → "重启中"遮罩
```

### 8.3 关键交互

- **测试连接**:测试通过才允许进入 Step 3(按钮禁用)
- **完成初始化**:
  1. 调 `POST /api/setup/initialize`
  2. 成功后显示"配置完成,正在重启后端…"全屏遮罩
  3. 轮询 `GET /api/health`(指数退避 500ms/1s/2s...),直到后端重新起来
  4. 后端起来后重新 `GET /api/setup/status` 确认 `initialized: true`
  5. `router.replace('/')` 进入主应用
- **重连超时**:上限 30 秒,超时显示"重启超时,请手动重启应用"

### 8.4 API client 扩展

`api/client.ts` 新增:
```ts
async getSetupStatus(): Promise<SetupStatus>
async getSetupTypes(): Promise<DbTypeMeta[]>
async testConnection(req: ConnectionTestRequest): Promise<ConnectionTestResult>
async initializeSetup(req: InitializeRequest): Promise<InitializeResult>
```

`api/types.ts` 新增对应类型。token 拦截器对 `/api/setup/*` 照常生效(SETUP 模式 token 照常),无需改动。

### 8.5 视觉

复用现有 `tokens.css` 设计变量(`var(--sk-bg)` 等),原生 Vue SFC + CSS,与 `Settings.vue`/`ToolGrid.vue` 风格对齐。不引新组件库。

---

## 9. 实现边界汇总

| 项 | 本次做 | 后续 phase |
|---|---|---|
| SETUP 模式最小上下文 + 进程重启 | ✅ | — |
| SetupController + 4 个端点 | ✅ | — |
| DataSourceConfigService + AES 加密 | ✅ | — |
| Tauri main.rs sidecar 重启 | ✅ | — |
| Actuator /restart(Web 部署) | ✅ | — |
| 12 实体 @Entity + 12 Repository | ✅ | — |
| DatabaseInit 删除 + 调用点迁移 | ✅ | — |
| AiConfigService 静态→@Component | ✅ | — |
| application.yml(静态配置) | ✅ | — |
| sys_user / sys_session 建表 | ✅ | — |
| 业务表 user_id + 复合唯一约束 | ✅ | — |
| AuthProvider / SecurityContext 接口 | ✅ | — |
| NoopAuthProvider / NoopSecurityContext | ✅ | — |
| VirtualUserInitializer(id=1 虚拟用户) | ✅ | — |
| Repository 查询按 userId 过滤 | ✅ | — |
| 前端向导页 + 路由分流 + 重连 | ✅ | — |
| 登录 UI / 密码校验 / session 颁发 | ❌ | ✅ |
| LocalAuthProvider / SsoAuthProvider | ❌ | ✅ |
| AuthFilter 实际拦截 | ❌ | ✅ |
| 多数据源运行时切换 | ❌ | ✅ |

---

## 10. 风险与缓解

| 风险 | 缓解 |
|---|---|
| ddl-auto 跨 4 种 DB 的列类型/默认值差异 | 时间戳默认值用 `@PrePersist` 兜底;复杂列定义用 `@Column(columnDefinition=)` |
| SQLite 方言依赖 community 包 | 显式引入 `hibernate-community-dialects` 依赖,plan 里验证 |
| `sys_user` 固定 id=1 与 IDENTITY 策略冲突 | SEQUENCE/TABLE 策略或原生 SQL 插入,plan 里定 |
| SETUP→APP 重启期间前端断连 | 轮询 health + 30 秒超时 + 手动重启兜底 |
| AES 密钥派生依赖机器特征,迁移机器解不开 | 明确为设计意图(防泄露);文档说明迁移时需重新初始化 |
| 现有 H2 数据库升级(用户已有数据) | ddl-auto=update 保留现有表数据;user_id 加列默认 1(归属虚拟用户) |

---

## 11. 测试策略

- **单元测试**:`DbType` 元数据、`CryptoUtil` 加解密往返、`DataSourceConfigService` buildFromWizard 拼装
- **集成测试**:
  - SETUP 模式启动:无 datasource.properties → `/api/setup/status` 返回 initialized:false
  - initialize 流程:四种 DB type 各跑一遍(嵌入式用真连,远程用 Testcontainers)
  - APP 模式启动:有 datasource.properties → 全量 bean 加载、虚拟用户存在
  - 重启:SETUP_DONE 退出码验证
- **前端**:向导页 mount、表单校验、测试连接 mock、重连轮询

---

**下一步:** 本 spec 经用户审阅通过后,转入 writing-plans skill 生成实现计划。
