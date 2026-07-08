# 多数据源初始化向导 + JPA 迁移 + 用户体系预留 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 ZhiFlow 的数据库层从 MyBatis 迁移到 JPA,实现首次启动的多数据源初始化向导(H2/SQLite/MySQL/PostgreSQL),并预留用户体系的行级数据隔离架构(本次不实现登录)。

**Architecture:** 启动时读 `~/.zhiflow/config/datasource.properties`——存在则进 APP 模式(全量 Spring 上下文 + JPA + 全部 bean),不存在则进 SETUP 模式(最小上下文,仅 SetupController + 静态前端,提供向导 API)。向导完成后写配置、建表、插入虚拟用户(id=1, `ZFlow-Summer`),进程退出触发 Tauri sidecar 重启。业务表全部加 `user_id` 行级隔离字段,通过 `SecurityContext.currentUserId()` 统一访问(本地离线恒返回 1)。

**Tech Stack:** Spring Boot 4.1.0,Hibernate 7(Spring Boot 管理),Spring Data JPA,HikariCP,JPA `ddl-auto=update`,AES-GCM 加密,H2/SQLite/MySQL/PostgreSQL 驱动,Vue 3 + Pinia + vue-router(前端向导),Tauri/Rust(sidecar 重启)。

## Global Constraints

- **Java 21**,Maven 多模块(parent + ZhiFlow-Api + plugin-markdown + ZhiFlow)。版本属性集中在父 `pom.xml`,`<revision>4.0.0-SNAPSHOT</revision>`。
- **Spring Boot 4.1.0**(非 3.x)。Hibernate 7 随之而来,dialect 包名为 `org.hibernate.dialect.*`(非旧 `org.hibernate.dialect`)。Actuator 端点暴露配置用 `management.endpoints.web.exposure.include`。
- **表名/列名沿用现有 snake_case**(显式 `@Table(name=)`/`@Column(name=)`),保留 `PUBLIC` schema 默认。主键 `@GeneratedValue(strategy = GenerationType.IDENTITY)`。
- **端口 `server.port` 与 `zhiflow.auth.token` 仍走命令行参数**(动态项,sidecar 部署语义)。只有静态配置(server.address、JPA、actuator)进 `application.yml`。
- **路径不用 `~`**(Java properties 不展开 home);相对路径相对 `user.dir` 解析,`DataSourceConfigService` 加载时转绝对路径。
- **虚拟用户常量**:`id=1`、`username=ZFlow-Summer`、`authProvider=local`、`userType=1`(管理员)、`status=1`。`SecurityConstants.LOCAL_VIRTUAL_USER_ID = 1L`。
- **`backup/` 目录是旧代码归档,不修改**——所有改动只在 `ZhiFlow/`、`frontend/`、`desktop/`。
- **提交信息用 emoji 前缀**(与现有约定一致):`✨ feat`/`♻️ refactor`/`🐛 fix`/`📝 docs`/`🗑️ chore`。
- **测试**:JUnit 5 + spring-boot-starter-test(已有)。集成测试用 `@SpringBootTest(webEnvironment=RANDOM_PORT)`。
- **DRY/YAGNI/TDD**:每个任务先写失败测试,再写实现。频繁提交。

---

## 文件结构

### 新建(后端)

| 文件 | 职责 |
|---|---|
| `ZhiFlow/src/main/resources/application.yml` | 静态 Spring 配置(JPA/actuator/server.address) |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/database/SecurityConstants.java` | 虚拟用户常量(id=1, ZFlow-Summer) |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/SysUserEntity.java` | 用户表实体 |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/SysSessionEntity.java` | 会话表实体(预留) |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/database/repository/*.java` | 12 个 Spring Data JPA Repository(替换 mapper) |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/security/AuthProvider.java` | 认证接口(可插拔) |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/security/SecurityContext.java` | 当前用户上下文接口 |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/security/NoopAuthProvider.java` | 本地离线认证实现 |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/security/NoopSecurityContext.java` | 本地离线 SecurityContext |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/security/SecurityConfig.java` | Spring bean 装配(Noop 实现) |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/database/VirtualUserInitializer.java` | 启动确保 id=1 虚拟用户存在 |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/DbType.java` | 数据库类型枚举 |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/DataSourceConfig.java` | 连接配置 record |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/WizardParams.java` | 向导参数 record |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/ConnectionTestResult.java` | 测试结果 record |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/CryptoUtil.java` | AES-GCM 加解密 |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/DataSourceConfigService.java` | 读写 datasource.properties + 加解密 |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/SetupController.java` | 向导 REST 端点 |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/SetupApplication.java` | SETUP 模式专用 Spring Boot 入口(最小上下文) |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/ExitCodes.java` | 退出码常量 |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/config/DataSourceAutoConfig.java` | APP 模式 DataSource bean |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/config/JpaConfig.java` | JPA 配置(dialect 注入) |

### 新建(前端)

| 文件 | 职责 |
|---|---|
| `frontend/src/views/SetupWizard.vue` | 三步式向导页 |
| `frontend/src/stores/setup.ts` | 向导状态 store |

### 修改

| 文件 | 改动 |
|---|---|
| `pom.xml`(父) | 新增 mysql/postgres/sqlite 驱动版本属性;移除 mybatis 属性 |
| `ZhiFlow/pom.xml` | 新增 JPA/actuator/驱动/community-dialects 依赖;移除 mybatis |
| `ZhiFlow/src/main/java/.../HeadlessLauncher.java` | 改为读 datasource.properties 分流 SETUP/APP 模式 |
| `ZhiFlow/src/main/java/.../ai/AiApplication.java` | 改为 APP 模式专用(加 exclude 配置) |
| `ZhiFlow/src/main/java/.../ai/AiConfigService.java` | 静态→@Component,改用 AppSettingRepository |
| `ZhiFlow/src/main/java/.../ai/service/AiConfigServiceHeadless.java` | 改用 AppSettingRepository + SecurityContext |
| `ZhiFlow/src/main/java/.../ai/spring/AiBackendInitializer.java` | 注入 AiConfigService(不再静态调用) |
| `ZhiFlow/src/main/java/.../web/controller/SettingsController.java` | 注入 AiConfigServiceHeadless |
| `ZhiFlow/src/main/java/.../utils/EmailUtil.java` | 静态→@Component,注入 ZhiFlowSettingEmailRepository |
| `ZhiFlow/src/main/java/.../web/filter/TokenAuthFilter.java` | 放行 `/api/setup/` |
| `desktop/src-tauri/src/main.rs` | sidecar 退出码 0 重启逻辑 |
| `frontend/src/api/client.ts` | 新增 setup 端点方法 |
| `frontend/src/api/types.ts` | 新增 setup 类型 |
| `frontend/src/router/index.ts` | 全局守卫 + /setup 路由 |
| `frontend/src/App.vue` | 向导页不套 AppShell |

### 删除

| 文件 | 原因 |
|---|---|
| `ZhiFlow/src/main/java/.../database/DatabaseInit.java` | JPA 接管 |
| `ZhiFlow/src/main/resources/mybatis-config.xml` | JPA 接管 |
| `ZhiFlow/src/main/resources/mapper/**/*.xml`(12 个) | JPA 接管 |
| `ZhiFlow/src/main/java/.../database/mapper/**/*.java`(12 个) | 替换为 repository |
| `ZhiFlow/src/test/.../web/HeadlessIntegrationTest.java` 中 `DatabaseInit.init()` 调用 | 改为 JPA 自动建表 |

---

## 任务排序总览

任务分四个阶段,严格顺序(后阶段依赖前阶段):

**阶段一:依赖与配置基础(Task 1-3)** — pom 依赖、application.yml、DataSource 配置基建
**阶段二:JPA 迁移(Task 4-9)** — 实体注解、Repository、调用点迁移、DatabaseInit 删除、用户表与隔离
**阶段三:安全与用户体系预留(Task 10-12)** — SecurityContext、AuthProvider、虚拟用户初始化
**阶段四:初始化向导(Task 13-19)** — SETUP 模式、SetupController、配置服务、前端向导、Tauri 重启

---

## 阶段一:依赖与配置基础

### Task 1: 更新 Maven 依赖(JPA + 驱动 + actuator,移除 mybatis)

**Files:**
- Modify: `pom.xml`(父)— 新增驱动版本属性,移除 mybatis 属性
- Modify: `ZhiFlow/pom.xml` — 新增 JPA/actuator/驱动/community-dialects,移除 mybatis

**Interfaces:**
- Produces: `pom.xml` 提供 `mysql.version`/`postgresql.version`/`sqlite.version` 属性供 ZhiFlow 模块引用

- [ ] **Step 1: 在父 pom.xml 的 `<properties>` 新增驱动版本属性**

在 `pom.xml:57`(`<playwright.version>` 行之后,`<!-- Spring Boot 4 ...` 注释之前)新增:

```xml
        <!-- Database drivers for multi-datasource support (Phase 4) -->
        <mysql.version>9.2.0</mysql.version>
        <postgresql.version>42.7.5</postgresql.version>
        <sqlite.version>3.49.1.0</sqlite.version>
```

- [ ] **Step 2: 在父 pom.xml 的 `<properties>` 移除 mybatis 版本属性**

删除 `pom.xml:44` 这一行(整行删除):

```xml
        <mybatis.version>3.5.19</mybatis.version>
```

- [ ] **Step 3: 在父 pom.xml 的 `<dependencyManagement>` 移除 mybatis,新增驱动**

删除 `pom.xml` 中 mybatis 的 dependencyManagement 条目:

```xml
            <dependency>
                <groupId>org.mybatis</groupId>
                <artifactId>mybatis</artifactId>
                <version>${mybatis.version}</version>
            </dependency>
```

在 `dependencyManagement` 的 H2 条目(`pom.xml:171-175`)之后新增:

```xml
            <!-- Multi-datasource drivers (Phase 4) -->
            <dependency>
                <groupId>com.mysql</groupId>
                <artifactId>mysql-connector-j</artifactId>
                <version>${mysql.version}</version>
            </dependency>
            <dependency>
                <groupId>org.postgresql</groupId>
                <artifactId>postgresql</artifactId>
                <version>${postgresql.version}</version>
            </dependency>
            <dependency>
                <groupId>org.xerial</groupId>
                <artifactId>sqlite-jdbc</artifactId>
                <version>${sqlite.version}</version>
            </dependency>
```

- [ ] **Step 4: 修改 ZhiFlow/pom.xml — 用 JPA/actuator 替换 mybatis 依赖**

在 `ZhiFlow/pom.xml` 中,删除 mybatis 依赖块(约 `ZhiFlow/pom.xml:102-106`):

```xml
        <!-- Mybatis -->
        <dependency>
            <groupId>org.mybatis</groupId>
            <artifactId>mybatis</artifactId>
        </dependency>
```

在该位置(原 mybatis 处)新增 JPA + actuator + 驱动 + community-dialects:

```xml
        <!-- Spring Data JPA + Hibernate (replaces MyBatis) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>

        <!-- Actuator for /restart endpoint (Web deployment setup completion) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Multi-datasource JDBC drivers (runtime) -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.xerial</groupId>
            <artifactId>sqlite-jdbc</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Hibernate community dialects: SQLiteDialect (not in core Hibernate) -->
        <dependency>
            <groupId>org.hibernate.orm</groupId>
            <artifactId>hibernate-community-dialects</artifactId>
        </dependency>
```

- [ ] **Step 5: 验证编译通过(此时旧 MyBatis 代码还在,会有编译错误,预期内)**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow compile -o 2>&1 | tail -20`
Expected: 编译错误(DatabaseInit/mapper 引用),这是预期的——本任务只改依赖,编译修复在后续任务。**确认错误是关于 mybatis 相关类找不到,而非依赖本身解析失败。**

- [ ] **Step 6: 提交**

```bash
git add pom.xml ZhiFlow/pom.xml
git commit -m "♻️ build(deps): swap mybatis for spring-data-jpa + multi-db drivers + actuator"
```

---

### Task 2: 新建 application.yml(静态配置)

**Files:**
- Create: `ZhiFlow/src/main/resources/application.yml`

**Interfaces:**
- Produces: JPA ddl-auto=update 配置、actuator restart 端点暴露、server.address=127.0.0.1

- [ ] **Step 1: 创建 application.yml**

创建 `ZhiFlow/src/main/resources/application.yml`:

```yaml
# ZhiFlow static configuration.
# Dynamic items (server.port, zhiflow.auth.token) stay on the command line — they are
# per-launch values passed by the Tauri sidecar / dev runner. Only static defaults live here.

server:
  address: 127.0.0.1            # loopback only — never 0.0.0.0 (security invariant)

spring:
  jpa:
    hibernate:
      ddl-auto: update          # entity changes auto-create tables / add columns
    properties:
      hibernate:
        format_sql: true
        jdbc:
          time_zone: UTC
    open-in-view: false

management:
  endpoint:
    restart:
      enabled: true             # Web deployment: setup wizard triggers context restart
  endpoints:
    web:
      exposure:
        include: restart,health
```

- [ ] **Step 2: 从 HeadlessLauncher 移除 server.address 命令行参数(已迁到 yml)**

编辑 `ZhiFlow/src/main/java/fan/summer/zhiflow/HeadlessLauncher.java`,在 `startWithFallback` 方法(`HeadlessLauncher.java:77-90`)中,删除 `baseArgs.add("--server.address=127.0.0.1");` 这一行(`HeadlessLauncher.java:79`),因为现在由 application.yml 提供。

修改后的 `startWithFallback` 开头:

```java
    private static void startWithFallback(String port) {
        List<String> baseArgs = new ArrayList<>();
        try {
            runSpring(baseArgs, port);
```

- [ ] **Step 3: 提交**

```bash
git add ZhiFlow/src/main/resources/application.yml ZhiFlow/src/main/java/fan/summer/zhiflow/HeadlessLauncher.java
git commit -m "✨ feat(config): add application.yml for static JPA/actuator/server config"
```

---

### Task 3: 新建 DbType 枚举与配置 record

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/DbType.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/DataSourceConfig.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/WizardParams.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/ConnectionTestResult.java`
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/setup/DbTypeTest.java`

**Interfaces:**
- Produces: `DbType` 枚举(driver/dialect/urlTemplate/embedded 字段)、`DataSourceConfig` record、`WizardParams` record、`ConnectionTestResult` record

- [ ] **Step 1: 写 DbType 的失败测试**

创建 `ZhiFlow/src/test/java/fan/summer/zhiflow/setup/DbTypeTest.java`:

```java
package fan.summer.zhiflow.setup;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DbTypeTest {

    @Test
    void h2_isEmbedded_withCorrectDriverAndDialect() {
        DbType h2 = DbType.H2;
        assertTrue(h2.embedded);
        assertEquals("org.h2.Driver", h2.driver);
        assertEquals("org.hibernate.dialect.H2Dialect", h2.dialect);
        assertTrue(h2.urlTemplate.contains("{path}"));
    }

    @Test
    void sqlite_isEmbedded_usesCommunityDialect() {
        DbType sqlite = DbType.SQLITE;
        assertTrue(sqlite.embedded);
        assertEquals("org.hibernate.community.dialect.SQLiteDialect", sqlite.dialect);
    }

    @Test
    void mysql_isNotEmbedded_hasHostPortDbTemplate() {
        DbType mysql = DbType.MYSQL;
        assertFalse(mysql.embedded);
        assertTrue(mysql.urlTemplate.contains("{host}"));
        assertTrue(mysql.urlTemplate.contains("{port}"));
        assertTrue(mysql.urlTemplate.contains("{db}"));
    }

    @Test
    void postgresql_isNotEmbedded() {
        assertFalse(DbType.POSTGRESQL.embedded);
    }

    @Test
    void fromName_resolvesAllTypes_caseInsensitive() {
        assertEquals(DbType.H2, DbType.fromName("h2"));
        assertEquals(DbType.SQLITE, DbType.fromName("SQLite"));
        assertEquals(DbType.MYSQL, DbType.fromName("MYSQL"));
        assertEquals(DbType.POSTGRESQL, DbType.fromName("postgresql"));
    }

    @Test
    void fromName_unknown_throws() {
        assertThrows(IllegalArgumentException.class, () -> DbType.fromName("oracle"));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow test -Dtest=DbTypeTest -o 2>&1 | tail -15`
Expected: FAIL — `DbType` 类不存在(编译错误)

- [ ] **Step 3: 实现 DbType 枚举**

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/DbType.java`:

```java
package fan.summer.zhiflow.setup;

/**
 * Supported database types for the multi-datasource setup wizard.
 *
 * <p>Each enum constant bundles its JDBC driver class, Hibernate dialect, URL template,
 * and whether it is an embedded (file-based) database. New database support is added by
 * extending this enum — the wizard UI and {@code DataSourceConfigService} derive everything
 * from these constants.
 *
 * <p>URL template placeholders: {@code {path}} for embedded databases,
 * {@code {host}}/{@code {port}}/{@code {db}} for remote servers.
 */
public enum DbType {
    H2("org.h2.Driver", "org.hibernate.dialect.H2Dialect",
            "jdbc:h2:file:{path};AUTO_SERVER=TRUE", true),
    SQLITE("org.sqlite.JDBC", "org.hibernate.community.dialect.SQLiteDialect",
            "jdbc:sqlite:{path}", true),
    MYSQL("com.mysql.cj.jdbc.Driver", "org.hibernate.dialect.MySQLDialect",
            "jdbc:mysql://{host}:{port}/{db}", false),
    POSTGRESQL("org.postgresql.Driver", "org.hibernate.dialect.PostgreSQLDialect",
            "jdbc:postgresql://{host}:{port}/{db}", false);

    public final String driver;
    public final String dialect;
    public final String urlTemplate;
    /** {@code true} for file-based embedded databases (no host/port needed). */
    public final boolean embedded;

    DbType(String driver, String dialect, String urlTemplate, boolean embedded) {
        this.driver = driver;
        this.dialect = dialect;
        this.urlTemplate = urlTemplate;
        this.embedded = embedded;
    }

    /** Resolves a type by its lowercase name (case-insensitive). Throws on unknown. */
    public static DbType fromName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("db type is null");
        }
        return valueOf(name.trim().toUpperCase());
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow test -Dtest=DbTypeTest -o 2>&1 | tail -15`
Expected: PASS(6 个测试)

- [ ] **Step 5: 创建配套 record 类型**

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/DataSourceConfig.java`:

```java
package fan.summer.zhiflow.setup;

/**
 * Immutable datasource configuration — the fully-resolved connection descriptor.
 *
 * <p>Built by {@link DataSourceConfigService} from a {@link DbType} + {@link WizardParams},
 * persisted to {@code datasource.properties}, and used to construct the HikariCP DataSource
 * on APP-mode startup.
 *
 * @param type      the database type
 * @param url       the fully-assembled JDBC URL
 * @param driver    the JDBC driver class name
 * @param dialect   the Hibernate dialect class name
 * @param username  remote DB username; blank for embedded H2/SQLite
 * @param password  remote DB password (plaintext in-memory; encrypted on disk); blank for embedded
 * @param filePath  embedded DB file path (H2/SQLite); null for remote
 */
public record DataSourceConfig(
        DbType type,
        String url,
        String driver,
        String dialect,
        String username,
        String password,
        String filePath
) {}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/WizardParams.java`:

```java
package fan.summer.zhiflow.setup;

/**
 * Raw parameters submitted by the setup wizard frontend, before being assembled into a
 * {@link DataSourceConfig}. Field relevance depends on the {@link DbType#embedded} flag.
 *
 * @param filePath embedded DB file path (H2/SQLite); ignored for remote
 * @param host     remote DB hostname; ignored for embedded
 * @param port     remote DB port; ignored for embedded
 * @param database remote DB name; ignored for embedded
 * @param username remote DB username; ignored for embedded
 * @param password remote DB password; ignored for embedded
 */
public record WizardParams(
        String filePath,
        String host,
        Integer port,
        String database,
        String username,
        String password
) {}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/ConnectionTestResult.java`:

```java
package fan.summer.zhiflow.setup;

/**
 * Result of a {@code POST /api/setup/test-connection} attempt.
 *
 * @param success        whether the connection succeeded
 * @param dialect        the resolved Hibernate dialect (on success)
 * @param serverVersion  the database server version string (on success); null on failure
 * @param error          the error message (on failure); null on success
 */
public record ConnectionTestResult(
        boolean success,
        String dialect,
        String serverVersion,
        String error
) {
    public static ConnectionTestResult ok(String dialect, String serverVersion) {
        return new ConnectionTestResult(true, dialect, serverVersion, null);
    }

    public static ConnectionTestResult fail(String error) {
        return new ConnectionTestResult(false, null, null, error);
    }
}
```

- [ ] **Step 6: 提交**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/setup/ ZhiFlow/src/test/java/fan/summer/zhiflow/setup/
git commit -m "✨ feat(setup): add DbType enum and datasource config records"
```

---

## 阶段二:JPA 迁移

### Task 4: 实体加 JPA 注解(用户私有表加 user_id)

**Files:**
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/*.java`(12 个实体)

**Interfaces:**
- Produces: 12 个 `@Entity` 注解的实体类,user_id 字段(除 PluginManagerEntity)
- Consumes: 无(纯注解添加)

**注意**:`EmailSentLogEntity.id` 和几个实体的 id 是 `Long`(非 Integer),保留原类型。`EmailSentLogEntity`/`EmailMassSentConfigEntity`/`EmailTagEntity`/`ComplexSplitConfigEntity` 的 id 是 `Long`,其余是 `Integer`。`Date` 字段保留 `java.util.Date`(暂不强制改 LocalDateTime,减少触面;`@CreationTimestamp` 只用在新建的时间戳字段)。

- [ ] **Step 1: 给 AppSettingEntity 加注解 + user_id**

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/AppSettingEntity.java` 全文:

```java
package fan.summer.zhiflow.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * Entity representing a key-value application setting stored in the database.
 *
 * <p>User-scoped: each setting belongs to a user ({@code user_id}), enabling multi-account
 * data isolation. Local offline mode uses the virtual user id=1.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "app_setting",
        uniqueConstraints = @UniqueConstraint(name = "uk_app_setting_user_key",
                columnNames = {"user_id", "setting_key"}))
@Data
public class AppSettingEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "setting_key", nullable = false)
    private String settingKey;

    @Column(name = "setting_value", length = 1000)
    private String settingValue;

    /** User isolation field. Local offline mode = 1 (virtual user). */
    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
```

- [ ] **Step 2: 给 MenuOrderEntity 加注解 + user_id**

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/MenuOrderEntity.java` 全文:

```java
package fan.summer.zhiflow.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * Entity representing the display order of menu items in the sidebar.
 * User-scoped — each user has their own menu ordering.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "menu_order",
        uniqueConstraints = @UniqueConstraint(name = "uk_menu_order_user_page",
                columnNames = {"user_id", "page_class"}))
@Data
public class MenuOrderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "page_class", nullable = false)
    private String pageClass;

    @Column(name = "menu_order", nullable = false)
    private Integer menuOrder;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
```

- [ ] **Step 3: 给 PluginFavoriteEntity 加注解 + user_id**

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/PluginFavoriteEntity.java` 全文:

```java
package fan.summer.zhiflow.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Entity representing a favorited (bookmarked) plugin. User-scoped.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "plugin_favorites",
        uniqueConstraints = @UniqueConstraint(name = "uk_plugin_fav_user_plugin",
                columnNames = {"user_id", "plugin_id"}))
@Data
public class PluginFavoriteEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "plugin_id", nullable = false)
    private String pluginId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
```

- [ ] **Step 4: 给 PluginSettingEntity 加注解 + user_id**

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/PluginSettingEntity.java` 全文:

```java
package fan.summer.zhiflow.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * Entity for one plugin setting row: a key-value pair namespaced by plugin ID.
 * User-scoped — each user has independent plugin settings.
 *
 * @since 3.2.0
 */
@Entity
@Table(name = "plugin_setting",
        uniqueConstraints = @UniqueConstraint(name = "uk_plugin_setting_user",
                columnNames = {"user_id", "plugin_id", "setting_key"}))
@Data
public class PluginSettingEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "plugin_id", nullable = false)
    private String pluginId;

    @Column(name = "setting_key", nullable = false)
    private String settingKey;

    @Column(name = "setting_value", columnDefinition = "TEXT")
    private String settingValue;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
```

- [ ] **Step 5: 给 PluginManagerEntity 加注解(全局表,无 user_id)**

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/plugin/PluginManagerEntity.java` 全文:

```java
package fan.summer.zhiflow.database.entity.plugin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.sql.Timestamp;

/**
 * Entity representing a plugin managed by the built-in plugin manager.
 * Global table (no user_id) — installed plugins are system-wide, shared by all users.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "plugin_manager")
@Data
public class PluginManagerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "jar_name", nullable = false, unique = true)
    private String jarName;

    @Column(name = "plugin_name", nullable = false)
    private String pluginName;

    @Column(name = "plugin_version", nullable = false)
    private String pluginVersion;

    @Column(name = "is_disabled", nullable = false)
    private Integer isDisabled;

    @Column(name = "update_url")
    private String updateUrl;

    @Column(name = "last_check")
    private Timestamp lastCheck;

    @Column(name = "installed_at")
    private Timestamp installedAt;
}
```

- [ ] **Step 6: 给 ZhiFlowSettingEmailEntity 加注解 + user_id**

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/setting/email/ZhiFlowSettingEmailEntity.java` 全文:

```java
package fan.summer.zhiflow.database.entity.setting.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Entity representing the email SMTP configuration. User-scoped.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "swiss_kit_setting_email")
@Data
public class ZhiFlowSettingEmailEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false) private String email;
    @Column(nullable = false) private String password;
    @Column(name = "smtp_address", nullable = false) private String smtpAddress;
    @Column(name = "smtp_port", nullable = false) private Integer smtpPort;
    @Column(name = "need_tls", nullable = false) private Boolean needTLS;
    @Column(name = "need_ssl", nullable = false) private Boolean needSSL;
    @Column(name = "from_address") private String fromAddress;
    @Column(name = "imap_address") private String imapAddress;
    @Column(name = "imap_port") private Integer imapPort;
    @Column(name = "imap_ssl", nullable = false) private Boolean imapSSL;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
```

- [ ] **Step 7: 给 EmailAddressBookEntity 加注解 + user_id**

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/setting/email/EmailAddressBookEntity.java` 全文:

```java
package fan.summer.zhiflow.database.entity.setting.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Entity representing an entry in the email address book. User-scoped.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "email_address_book")
@Data
public class EmailAddressBookEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "email_address", nullable = false)
    private String emailAddress;

    private String nickname;

    @Column(length = 1000)
    private String tags;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
```

- [ ] **Step 8: 给 EmailTagEntity 加注解 + user_id**

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/setting/email/EmailTagEntity.java` 全文:

```java
package fan.summer.zhiflow.database.entity.setting.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * Entity representing an email tag. User-scoped.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "email_tag",
        uniqueConstraints = @UniqueConstraint(name = "uk_email_tag_user_tag",
                columnNames = {"user_id", "tag"}))
@Data
public class EmailTagEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private String tag;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
```

- [ ] **Step 9: 给 ComplexSplitConfigEntity 加注解 + user_id**

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/excel/ComplexSplitConfigEntity.java` 全文:

```java
package fan.summer.zhiflow.database.entity.excel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Entity representing a complex Excel split configuration. User-scoped.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "complex_split_config")
@Data
public class ComplexSplitConfigEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false) private String taskId;
    @Column(name = "field_name", nullable = false) private String fieldName;
    @Column(name = "sheet_name", nullable = false) private String sheetName;
    @Column(name = "header_index", nullable = false) private Integer headerIndex;
    @Column(name = "column_index", nullable = false) private Integer columnIndex;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
```

- [ ] **Step 10: 给 EmailMassSentConfigEntity 加注解 + user_id**

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/email/EmailMassSentConfigEntity.java` 全文:

```java
package fan.summer.zhiflow.database.entity.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * Entity representing the configuration for a mass email sending task. User-scoped.
 *
 * @author MuskStark
 */
@Entity
@Table(name = "email_mass_sent_config",
        uniqueConstraints = @UniqueConstraint(name = "uk_email_mass_user_task",
                columnNames = {"user_id", "task_id"}))
@Data
public class EmailMassSentConfigEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false) private String taskId;
    @Column(name = "to_tag") private String toTag;
    @Column(name = "cc_tag") private String ccTag;
    @Column(name = "is_sent_att", nullable = false) private boolean isSentAtt;
    @Column(name = "att_folder_path") private String attFolderPath;
    @Column(name = "send_by_filename", nullable = false) private boolean sendByFilename;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
```

- [ ] **Step 11: 给 EmailSentLogEntity 加注解 + user_id**

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/email/EmailSentLogEntity.java` 全文:

```java
package fan.summer.zhiflow.database.entity.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.Date;

/**
 * Entity representing a log entry for a sent email. User-scoped.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "email_sent_log")
@Data
public class EmailSentLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "\"to\"", length = 1000) private String to;
    @Column(name = "cc", length = 1000) private String cc;
    @Column(name = "bcc", length = 1000) private String bcc;
    @Column(length = 500) private String subject;
    @Column(columnDefinition = "TEXT") private String content;
    @Column(length = 1000) private String attachment;
    @Column(name = "send_time") private Date sendTime;
    @Column(name = "is_success", nullable = false) private boolean isSuccess;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
```

- [ ] **Step 12: 给 EmailArchiveEntity 加注解 + user_id**

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/email/EmailArchiveEntity.java` 全文:

```java
package fan.summer.zhiflow.database.entity.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.util.Date;

/**
 * Entity representing an archived email message. User-scoped.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "email_archive",
        uniqueConstraints = @UniqueConstraint(name = "uk_email_archive_user_uid",
                columnNames = {"user_id", "account_email", "folder", "message_uid"}))
@Data
public class EmailArchiveEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "account_email", nullable = false) private String accountEmail;
    @Column(nullable = false) private String folder;
    @Column(name = "message_uid", nullable = false) private String messageUid;
    @Column(length = 500) private String subject;
    @Column(name = "from_address", length = 500) private String fromAddress;
    @Column(name = "to_address", length = 1000) private String toAddress;
    @Column(name = "cc_address", length = 1000) private String ccAddress;
    @Column(name = "send_date") private Date sendDate;
    @Column(name = "has_attachment") private Boolean hasAttachment;
    @Column(name = "eml_path", length = 1000) private String emlPath;
    @Column(name = "body_preview", length = 500) private String bodyPreview;
    @Column(name = "archived_at") private Date archivedAt;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
```

- [ ] **Step 13: 验证实体编译通过(此时 mapper/DatabaseInit 引用还在,整体编译仍会失败,但实体本身应无注解错误)**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow compile -o 2>&1 | grep -E "(error|ERROR)" | head -20`
Expected: 错误来自 DatabaseInit/mapper 引用(预期),不应有实体注解相关错误。

- [ ] **Step 14: 提交**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/
git commit -m "♻️ refactor(db): add JPA annotations + user_id isolation to 12 entities"
```

---

### Task 5: 新建 sys_user / sys_session 实体

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/SysUserEntity.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/SysSessionEntity.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/database/SecurityConstants.java`

**Interfaces:**
- Produces: `SysUserEntity`、`SysSessionEntity`、`SecurityConstants.LOCAL_VIRTUAL_USER_ID`(1L)、`SecurityConstants.LOCAL_VIRTUAL_USERNAME`("ZFlow-Summer")

- [ ] **Step 1: 创建 SecurityConstants**

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/SecurityConstants.java`:

```java
package fan.summer.zhiflow.database;

/**
 * Security-related constants for the user system.
 *
 * <p>Local offline mode operates as a single virtual user (id=1, username "ZFlow-Summer").
 * All unauthenticated requests are attributed to this user. When login is enabled in a
 * future phase, real users start from id=2.
 */
public final class SecurityConstants {

    private SecurityConstants() {}

    /** The virtual user ID used in local offline mode. All unauthenticated data belongs here. */
    public static final long LOCAL_VIRTUAL_USER_ID = 1L;

    /** The fixed username of the local virtual user. */
    public static final String LOCAL_VIRTUAL_USERNAME = "ZFlow-Summer";
}
```

- [ ] **Step 2: 创建 SysUserEntity**

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/SysUserEntity.java`:

```java
package fan.summer.zhiflow.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * System user entity. Backs the user system groundwork (Phase 4 setup wizard).
 *
 * <p>In local offline mode, a single virtual user (id=1, username "ZFlow-Summer") owns all
 * data. When login is implemented in a later phase, real users are added here. The
 * {@code authProvider} field distinguishes local (username/password) from SSO sources.
 *
 * <p>{@code id} uses IDENTITY generation, but the virtual user is inserted with an explicit
 * id=1 by {@code VirtualUserInitializer} (via a flush-then-native-insert fallback).
 */
@Entity
@Table(name = "sys_user",
        uniqueConstraints = @UniqueConstraint(name = "uk_sys_user_username", columnNames = "username"))
@Data
public class SysUserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String username;

    /** bcrypt hash for local users; null for SSO users or the offline virtual user. */
    @Column(length = 255)
    private String passwordHash;

    /** Auth source: "local", "github", "google", "oidc", etc. Virtual user = "local". */
    @Column(length = 255)
    private String authProvider;

    /** SSO provider's unique user ID; null for local users. */
    @Column(length = 255)
    private String externalId;

    /** 1=enabled, 0=disabled. */
    @Column(nullable = false)
    private Integer status;

    /** 0=normal user, 1=admin. Virtual user is admin. */
    @Column(nullable = false)
    private Integer userType;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
```

- [ ] **Step 3: 创建 SysSessionEntity**

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/entity/SysSessionEntity.java`:

```java
package fan.summer.zhiflow.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * System session entity — reserved for future login implementation (session token or JWT jti).
 *
 * <p>Created in Phase 4 as empty groundwork; not populated until authentication is implemented.
 */
@Entity
@Table(name = "sys_session")
@Data
public class SysSessionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 500, nullable = false, unique = true)
    private String token;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "client_ip", length = 100)
    private String clientIp;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
```

- [ ] **Step 4: 提交**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/database/
git commit -m "✨ feat(db): add sys_user/sys_session entities + SecurityConstants for user groundwork"
```

---

### Task 6: 新建 12 个 Spring Data JPA Repository

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/database/repository/*.java`(12 个 + 2 个 sys)
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/database/repository/AppSettingRepositoryTest.java`

**Interfaces:**
- Produces: 各 Repository 接口,查询方法带 userId 参数
- Consumes: Task 4/5 的实体类

**注意**:Repository 包用 `repository`(复数不用,与现有 `mapper` 包平行)。所有用户私有表的 Repository 查询方法带 `userId` 参数;全局表(PluginManager)不带。

- [ ] **Step 1: 写 AppSettingRepository 的失败测试**

创建 `ZhiFlow/src/test/java/fan/summer/zhiflow/database/repository/AppSettingRepositoryTest.java`:

```java
package fan.summer.zhiflow.database.repository;

import fan.summer.zhiflow.database.SecurityConstants;
import fan.summer.zhiflow.database.entity.AppSettingEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies AppSettingRepository persists and isolates settings per user_id.
 * Uses @DataJpaTest (in-memory H2, auto-create schema from entities).
 */
@DataJpaTest
class AppSettingRepositoryTest {

    @Autowired
    private AppSettingRepository repo;

    @Test
    void findByUserIdAndSettingKey_returnsValue_forVirtualUser() {
        AppSettingEntity e = new AppSettingEntity();
        e.setSettingKey("theme");
        e.setSettingValue("dark");
        e.setUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID);
        repo.save(e);

        Optional<AppSettingEntity> found =
                repo.findByUserIdAndSettingKey(SecurityConstants.LOCAL_VIRTUAL_USER_ID, "theme");

        assertTrue(found.isPresent());
        assertEquals("dark", found.get().getSettingValue());
    }

    @Test
    void findByUserIdAndSettingKey_isolatesUsers() {
        Long userA = 1L, userB = 2L;
        AppSettingEntity a = new AppSettingEntity();
        a.setSettingKey("theme"); a.setSettingValue("dark"); a.setUserId(userA);
        AppSettingEntity b = new AppSettingEntity();
        b.setSettingKey("theme"); b.setSettingValue("light"); b.setUserId(userB);
        repo.save(a);
        repo.save(b);

        assertEquals("dark",
            repo.findByUserIdAndSettingKey(userA, "theme").orElseThrow().getSettingValue());
        assertEquals("light",
            repo.findByUserIdAndSettingKey(userB, "theme").orElseThrow().getSettingValue());
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow test -Dtest=AppSettingRepositoryTest -o 2>&1 | tail -15`
Expected: FAIL — `AppSettingRepository` 不存在(编译错误)

- [ ] **Step 3: 创建所有 Repository 接口**

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/repository/AppSettingRepository.java`:

```java
package fan.summer.zhiflow.database.repository;

import fan.summer.zhiflow.database.entity.AppSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppSettingRepository extends JpaRepository<AppSettingEntity, Integer> {
    Optional<AppSettingEntity> findByUserIdAndSettingKey(Long userId, String key);
    List<AppSettingEntity> findAllByUserId(Long userId);
}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/repository/MenuOrderRepository.java`:

```java
package fan.summer.zhiflow.database.repository;

import fan.summer.zhiflow.database.entity.MenuOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuOrderRepository extends JpaRepository<MenuOrderEntity, Integer> {
    List<MenuOrderEntity> findAllByUserIdOrderByMenuOrderAsc(Long userId);
}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/repository/PluginFavoriteRepository.java`:

```java
package fan.summer.zhiflow.database.repository;

import fan.summer.zhiflow.database.entity.PluginFavoriteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PluginFavoriteRepository extends JpaRepository<PluginFavoriteEntity, Integer> {
    Optional<PluginFavoriteEntity> findByUserIdAndPluginId(Long userId, String pluginId);
    List<PluginFavoriteEntity> findAllByUserId(Long userId);
    void deleteByUserIdAndPluginId(Long userId, String pluginId);
}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/repository/PluginSettingRepository.java`:

```java
package fan.summer.zhiflow.database.repository;

import fan.summer.zhiflow.database.entity.PluginSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PluginSettingRepository extends JpaRepository<PluginSettingEntity, Integer> {
    Optional<PluginSettingEntity> findByUserIdAndPluginIdAndSettingKey(Long userId, String pluginId, String key);
    List<PluginSettingEntity> findAllByUserIdAndPluginId(Long userId, String pluginId);
}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/repository/plugin/PluginManagerRepository.java`:

```java
package fan.summer.zhiflow.database.repository.plugin;

import fan.summer.zhiflow.database.entity.plugin.PluginManagerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PluginManagerRepository extends JpaRepository<PluginManagerEntity, Integer> {
    Optional<PluginManagerEntity> findByJarName(String jarName);
}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/repository/setting/email/ZhiFlowSettingEmailRepository.java`:

```java
package fan.summer.zhiflow.database.repository.setting.email;

import fan.summer.zhiflow.database.entity.setting.email.ZhiFlowSettingEmailEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ZhiFlowSettingEmailRepository extends JpaRepository<ZhiFlowSettingEmailEntity, Integer> {
    Optional<ZhiFlowSettingEmailEntity> findFirstByUserIdOrderByIdDesc(Long userId);
}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/repository/setting/email/EmailAddressBookRepository.java`:

```java
package fan.summer.zhiflow.database.repository.setting.email;

import fan.summer.zhiflow.database.entity.setting.email.EmailAddressBookEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmailAddressBookRepository extends JpaRepository<EmailAddressBookEntity, Integer> {
    List<EmailAddressBookEntity> findAllByUserId(Long userId);
}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/repository/setting/email/EmailTagRepository.java`:

```java
package fan.summer.zhiflow.database.repository.setting.email;

import fan.summer.zhiflow.database.entity.setting.email.EmailTagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmailTagRepository extends JpaRepository<EmailTagEntity, Long> {
    Optional<EmailTagEntity> findByUserIdAndTag(Long userId, String tag);
    List<EmailTagEntity> findAllByUserId(Long userId);
}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/repository/excel/ComplexSplitConfigRepository.java`:

```java
package fan.summer.zhiflow.database.repository.excel;

import fan.summer.zhiflow.database.entity.excel.ComplexSplitConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComplexSplitConfigRepository extends JpaRepository<ComplexSplitConfigEntity, Long> {
    List<ComplexSplitConfigEntity> findAllByUserIdAndTaskId(Long userId, String taskId);
    void deleteByUserIdAndTaskId(Long userId, String taskId);
}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/repository/email/EmailMassSentConfigRepository.java`:

```java
package fan.summer.zhiflow.database.repository.email;

import fan.summer.zhiflow.database.entity.email.EmailMassSentConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailMassSentConfigRepository extends JpaRepository<EmailMassSentConfigEntity, Long> {
    Optional<EmailMassSentConfigEntity> findByUserIdAndTaskId(Long userId, String taskId);
}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/repository/email/EmailSentLogRepository.java`:

```java
package fan.summer.zhiflow.database.repository.email;

import fan.summer.zhiflow.database.entity.email.EmailSentLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailSentLogRepository extends JpaRepository<EmailSentLogEntity, Long> {
}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/repository/email/EmailArchiveRepository.java`:

```java
package fan.summer.zhiflow.database.repository.email;

import fan.summer.zhiflow.database.entity.email.EmailArchiveEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Date;
import java.util.List;
import java.util.Optional;

public interface EmailArchiveRepository extends JpaRepository<EmailArchiveEntity, Integer> {

    Optional<EmailArchiveEntity> findByUserIdAndAccountEmailAndFolderAndMessageUid(
            Long userId, String accountEmail, String folder, String messageUid);

    /**
     * Flexible search across archived emails for a user. All filter params are optional
     * (null = no filter). Ordered by send_date desc, limited to 100 results.
     */
    @Query("SELECT e FROM EmailArchiveEntity e WHERE e.userId = :userId " +
           "AND (:accountEmail IS NULL OR e.accountEmail = :accountEmail) " +
           "AND (:fromAddress IS NULL OR e.fromAddress LIKE CONCAT('%', :fromAddress, '%')) " +
           "AND (:subject IS NULL OR e.subject LIKE CONCAT('%', :subject, '%')) " +
           "AND (:startDate IS NULL OR e.sendDate >= :startDate) " +
           "AND (:endDate IS NULL OR e.sendDate <= :endDate) " +
           "ORDER BY e.sendDate DESC LIMIT 100")
    List<EmailArchiveEntity> searchByQuery(@Param("userId") Long userId,
                                           @Param("accountEmail") String accountEmail,
                                           @Param("fromAddress") String fromAddress,
                                           @Param("subject") String subject,
                                           @Param("startDate") Date startDate,
                                           @Param("endDate") Date endDate);
}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/repository/SysUserRepository.java`:

```java
package fan.summer.zhiflow.database.repository;

import fan.summer.zhiflow.database.entity.SysUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUserEntity, Long> {
    Optional<SysUserEntity> findByUsername(String username);
    boolean existsById(Long id);
}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/repository/SysSessionRepository.java`:

```java
package fan.summer.zhiflow.database.repository;

import fan.summer.zhiflow.database.entity.SysSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SysSessionRepository extends JpaRepository<SysSessionEntity, Long> {
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow test -Dtest=AppSettingRepositoryTest -o 2>&1 | tail -15`
Expected: PASS(2 个测试)。注意:`@DataJpaTest` 会尝试加载完整 Spring 上下文,如果此时 `AiApplication` 还引用旧的 DatabaseInit/mapper,测试可能因上下文加载失败而报错。**如果出现上下文加载错误,临时把 `@DataJpaTest` 的测试类移到独立包或加 `@SpringBootTest(classes=...)` 指向最小配置**。若失败,记录错误信息,Task 8 删除 DatabaseInit/mapper 后会自然解决——此时可暂时标记此测试为预期失败,继续后续任务。

- [ ] **Step 5: 提交**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/database/repository/ ZhiFlow/src/test/java/fan/summer/zhiflow/database/repository/
git commit -m "✨ feat(db): add 14 Spring Data JPA repositories (replace mybatis mappers)"
```

---

### Task 7: 安全抽象层(AuthProvider / SecurityContext + Noop 实现)

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/security/AuthProvider.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/security/SecurityContext.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/security/NoopAuthProvider.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/security/NoopSecurityContext.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/security/SecurityConfig.java`
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/security/NoopSecurityContextTest.java`

**Interfaces:**
- Produces: `SecurityContext.currentUserId()` 返回 `Long`、`AuthProvider.isEnabled()`
- Consumes: `SecurityConstants.LOCAL_VIRTUAL_USER_ID`

- [ ] **Step 1: 写 NoopSecurityContext 的失败测试**

创建 `ZhiFlow/src/test/java/fan/summer/zhiflow/security/NoopSecurityContextTest.java`:

```java
package fan.summer.zhiflow.security;

import fan.summer.zhiflow.database.SecurityConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NoopSecurityContextTest {

    private final SecurityContext ctx = new NoopSecurityContext();

    @Test
    void currentUserId_returnsVirtualUserId() {
        assertEquals(SecurityConstants.LOCAL_VIRTUAL_USER_ID, ctx.currentUserId());
    }

    @Test
    void isAuthenticated_alwaysTrue() {
        assertTrue(ctx.isAuthenticated());
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow test -Dtest=NoopSecurityContextTest -o 2>&1 | tail -10`
Expected: FAIL — 类不存在

- [ ] **Step 3: 创建接口与实现**

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/security/AuthProvider.java`:

```java
package fan.summer.zhiflow.security;

/**
 * Pluggable authentication provider. Implementations handle specific auth mechanisms
 * (local username/password, OAuth2/OIDC SSO). The Noop implementation covers local
 * offline mode (no login required).
 *
 * <p>This is groundwork — only {@link NoopAuthProvider} is implemented in Phase 4.
 * LocalAuthProvider and SsoAuthProvider arrive in later phases.
 */
public interface AuthProvider {

    /**
     * Authenticates credentials. Throws {@code AuthenticationException} (to be defined
     * when login is implemented) on failure.
     *
     * @param request the authentication request (username/password, OAuth token, etc.)
     * @return the authenticated user identity
     */
    AuthResult authenticate(AuthRequest request);

    /**
     * Whether login is enabled. Local offline mode returns {@code false} (no login required);
     * enabled providers return {@code true}.
     */
    boolean isEnabled();
}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/security/AuthRequest.java`:

```java
package fan.summer.zhiflow.security;

/** Authentication request payload. Groundwork — not used until login is implemented. */
public record AuthRequest(String username, String password, String oauthToken, String provider) {}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/security/AuthResult.java`:

```java
package fan.summer.zhiflow.security;

/** Authenticated user identity returned by {@link AuthProvider#authenticate}. */
public record AuthResult(long userId, String username, String authProvider) {}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/security/SecurityContext.java`:

```java
package fan.summer.zhiflow.security;

/**
 * Provides the current user identity. All business code reads the current user via
 * {@link #currentUserId()} rather than a static or thread-local, so swapping the
 * implementation (offline virtual user vs. real logged-in user) requires no call-site changes.
 */
public interface SecurityContext {

    /** The current user's ID. Local offline mode always returns the virtual user id (1). */
    Long currentUserId();

    /** Whether a user is authenticated. Local offline mode always returns true. */
    boolean isAuthenticated();
}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/security/NoopAuthProvider.java`:

```java
package fan.summer.zhiflow.security;

import fan.summer.zhiflow.database.SecurityConstants;

/**
 * No-operation auth provider for local offline mode. Login is disabled — every request
 * is treated as the virtual user (id=1, "ZFlow-Summer").
 */
public class NoopAuthProvider implements AuthProvider {

    @Override
    public AuthResult authenticate(AuthRequest request) {
        return new AuthResult(SecurityConstants.LOCAL_VIRTUAL_USER_ID,
                SecurityConstants.LOCAL_VIRTUAL_USERNAME, "local");
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/security/NoopSecurityContext.java`:

```java
package fan.summer.zhiflow.security;

import fan.summer.zhiflow.database.SecurityConstants;

/**
 * SecurityContext for local offline mode. Always returns the virtual user identity.
 */
public class NoopSecurityContext implements SecurityContext {

    @Override
    public Long currentUserId() {
        return SecurityConstants.LOCAL_VIRTUAL_USER_ID;
    }

    @Override
    public boolean isAuthenticated() {
        return true;
    }
}
```

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/security/SecurityConfig.java`:

```java
package fan.summer.zhiflow.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Noop auth/security implementations as Spring beans. When login is implemented,
 * this config will switch to real AuthProvider/SecurityContext beans (or use @ConditionalOnProperty
 * to choose between local-offline and authenticated modes).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public AuthProvider authProvider() {
        return new NoopAuthProvider();
    }

    @Bean
    public SecurityContext securityContext() {
        return new NoopSecurityContext();
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow test -Dtest=NoopSecurityContextTest -o 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/security/ ZhiFlow/src/test/java/fan/summer/zhiflow/security/
git commit -m "✨ feat(security): add pluggable AuthProvider/SecurityContext + Noop implementation"
```

---

### Task 8: 迁移 DB 调用点 + 删除 DatabaseInit/mapper/mybatis 资源

**Files:**
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/AiConfigService.java`(静态→@Component)
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/AiConfigServiceHeadless.java`
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiBackendInitializer.java`
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/web/controller/SettingsController.java`
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/utils/EmailUtil.java`(静态→@Component)
- Delete: `ZhiFlow/src/main/java/fan/summer/zhiflow/database/DatabaseInit.java`
- Delete: `ZhiFlow/src/main/java/fan/summer/zhiflow/database/mapper/**/*.java`(12 个)
- Delete: `ZhiFlow/src/main/resources/mybatis-config.xml`
- Delete: `ZhiFlow/src/main/resources/mapper/**/*.xml`(12 个)
- Modify: `ZhiFlow/src/test/java/fan/summer/zhiflow/web/HeadlessIntegrationTest.java`(移除 DatabaseInit.init())

**Interfaces:**
- Produces: `AiConfigService` / `EmailUtil` 作为 Spring bean,注入 Repository + SecurityContext
- Consumes: Task 6 的 Repository、Task 7 的 SecurityContext

**关键**:`AiConfigService` 与 `EmailUtil` 原是静态工具类。转 @Component 后,所有静态调用点改为注入。`AiBackendInitializer` 原静态调用 `AiConfigService.getXxx()`,改为注入 bean。`SettingsController` 原静态调用 `AiConfigServiceHeadless`,改为注入。

- [ ] **Step 1: 重写 AiConfigService 为 @Component,注入 AppSettingRepository + SecurityContext**

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/AiConfigService.java` 全文。**保留所有 getter 的方法名和返回值**(调用方依赖它们),只改实现从静态→实例 + Repository:

```java
package fan.summer.zhiflow.ai;

import fan.summer.zhiflow.database.entity.AppSettingEntity;
import fan.summer.zhiflow.database.repository.AppSettingRepository;
import fan.summer.zhiflow.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reads AI configuration from the database via JPA.
 *
 * <p>Converted from a static utility to a Spring {@code @Component} so it can inject
 * {@link AppSettingRepository} and {@link SecurityContext} (user-scoped reads).
 * All public getter signatures are unchanged — callers now inject this bean instead of
 * calling statically.
 *
 * @since 3.0.0
 */
@Component
public class AiConfigService {

    private static final Logger log = LoggerFactory.getLogger(AiConfigService.class);

    private final AppSettingRepository appSettingRepo;
    private final SecurityContext securityContext;

    public AiConfigService(AppSettingRepository appSettingRepo, SecurityContext securityContext) {
        this.appSettingRepo = appSettingRepo;
        this.securityContext = securityContext;
    }

    // ── Setting keys ─────────────────────────────────────────────
    private static final String AI_MODE_KEY = "ai.mode";
    private static final String AI_OPENAI_ENDPOINT_KEY = "ai.openai.endpoint";
    private static final String AI_OPENAI_API_KEY_KEY = "ai.openai.api_key";
    private static final String AI_OPENAI_MODEL_KEY = "ai.openai.model";
    private static final String AI_ANTHROPIC_ENDPOINT_KEY = "ai.anthropic.endpoint";
    private static final String AI_ANTHROPIC_API_KEY_KEY = "ai.anthropic.api_key";
    private static final String AI_ANTHROPIC_MODEL_KEY = "ai.anthropic.model";
    private static final String AI_DEEPSEEK_ENDPOINT_KEY = "ai.deepseek.endpoint";
    private static final String AI_DEEPSEEK_API_KEY_KEY = "ai.deepseek.api_key";
    private static final String AI_DEEPSEEK_MODEL_KEY = "ai.deepseek.model";
    private static final String AI_TEMPERATURE_KEY = "ai.temperature";
    private static final String AI_TOP_P_KEY = "ai.top_p";
    private static final String AI_MAX_TOKENS_KEY = "ai.max_tokens";
    private static final String AI_SYSTEM_PROMPT_KEY = "ai.system_prompt";
    private static final String AI_LOCAL_BACKEND_KEY = "ai.local.backend";
    private static final String AI_MODEL_PATH_KEY = "ai.model.path";
    private static final String AI_OLLAMA_BASE_URL_KEY = "ai.ollama.base_url";
    private static final String AI_OLLAMA_MODEL_KEY = "ai.ollama.model";

    // ── Core read ─────────────────────────────────────────────
    private String readSetting(String key, String defaultValue) {
        try {
            Long uid = securityContext.currentUserId();
            Optional<AppSettingEntity> entity = appSettingRepo.findByUserIdAndSettingKey(uid, key);
            if (entity.isPresent()) {
                String v = entity.get().getSettingValue();
                if (v != null && !v.isBlank()) return v;
            }
        } catch (Exception e) {
            log.debug("Could not read AI setting: {}", key, e);
        }
        return defaultValue;
    }

    // ── Public getters (signatures unchanged) ────────────────
    public String getAiMode() { return readSetting(AI_MODE_KEY, "local"); }
    public String getAiOpenAiEndpoint() { return readSetting(AI_OPENAI_ENDPOINT_KEY, "https://api.openai.com"); }
    public String getAiOpenAiApiKey() { return readSetting(AI_OPENAI_API_KEY_KEY, ""); }
    public String getAiOpenAiModel() { return readSetting(AI_OPENAI_MODEL_KEY, "gpt-4o"); }
    public String getAiAnthropicEndpoint() { return readSetting(AI_ANTHROPIC_ENDPOINT_KEY, "https://api.anthropic.com"); }
    public String getAiAnthropicApiKey() { return readSetting(AI_ANTHROPIC_API_KEY_KEY, ""); }
    public String getAiAnthropicModel() { return readSetting(AI_ANTHROPIC_MODEL_KEY, "claude-sonnet-4-20250514"); }
    public String getAiDeepSeekEndpoint() { return readSetting(AI_DEEPSEEK_ENDPOINT_KEY, "https://api.deepseek.com"); }
    public String getAiDeepSeekApiKey() { return readSetting(AI_DEEPSEEK_API_KEY_KEY, ""); }
    public String getAiDeepSeekModel() { return readSetting(AI_DEEPSEEK_MODEL_KEY, "deepseek-chat"); }

    public float getAiTemperature() {
        String val = readSetting(AI_TEMPERATURE_KEY, null);
        if (val != null) { try { return Float.parseFloat(val); } catch (NumberFormatException ignored) {} }
        return 0.7f;
    }
    public float getAiTopP() {
        String val = readSetting(AI_TOP_P_KEY, null);
        if (val != null) { try { return Float.parseFloat(val); } catch (NumberFormatException ignored) {} }
        return 0.9f;
    }
    public int getAiMaxTokens() {
        String val = readSetting(AI_MAX_TOKENS_KEY, null);
        if (val != null) { try { return Integer.parseInt(val); } catch (NumberFormatException ignored) {} }
        return 2048;
    }
    public String getAiSystemPrompt() {
        String val = readSetting(AI_SYSTEM_PROMPT_KEY, null);
        return (val != null && !val.isBlank()) ? val : "You are a helpful assistant.";
    }
    public String getAiLocalBackend() { return readSetting(AI_LOCAL_BACKEND_KEY, "java"); }
    public String getAiModelPath() { return readSetting(AI_MODEL_PATH_KEY, null); }
    public String getAiOllamaBaseUrl() { return readSetting(AI_OLLAMA_BASE_URL_KEY, "http://localhost:11434"); }
    public String getAiOllamaModel() { return readSetting(AI_OLLAMA_MODEL_KEY, "qwen3:4b"); }
}
```

- [ ] **Step 2: 重写 AiConfigServiceHeadless 为注入式**

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/AiConfigServiceHeadless.java` 全文:

```java
package fan.summer.zhiflow.ai.service;

import fan.summer.zhiflow.ai.AiConfigService;
import fan.summer.zhiflow.database.entity.AppSettingEntity;
import fan.summer.zhiflow.database.repository.AppSettingRepository;
import fan.summer.zhiflow.security.SecurityContext;
import org.springframework.stereotype.Component;

/**
 * Headless AI/UI configuration service. Wraps {@link AiConfigService} reads and provides
 * write methods that persist via {@link AppSettingRepository}. Converted from static utility
 * to {@code @Component} for DI. All operations are user-scoped via {@link SecurityContext}.
 */
@Component
public class AiConfigServiceHeadless {

    private static final String AI_TEMPERATURE_KEY = "ai.temperature";
    private static final String AI_TOP_P_KEY       = "ai.top_p";
    private static final String AI_MAX_TOKENS_KEY  = "ai.max_tokens";
    private static final String AI_SYSTEM_PROMPT_KEY = "ai.system_prompt";
    private static final String THEME_KEY    = "theme";
    private static final String LANGUAGE_KEY = "language";
    private static final String SIDEBAR_COLLAPSED_KEY = "sidebar.collapsed";

    private final AppSettingRepository appSettingRepo;
    private final SecurityContext securityContext;
    private final AiConfigService aiConfigService;

    public AiConfigServiceHeadless(AppSettingRepository appSettingRepo,
                                   SecurityContext securityContext,
                                   AiConfigService aiConfigService) {
        this.appSettingRepo = appSettingRepo;
        this.securityContext = securityContext;
        this.aiConfigService = aiConfigService;
    }

    // ── Generic UI-shell settings ─────────────────────────
    public String getSetting(String key, String defaultValue) {
        Long uid = securityContext.currentUserId();
        return appSettingRepo.findByUserIdAndSettingKey(uid, key)
                .filter(e -> e.getSettingValue() != null && !e.getSettingValue().isBlank())
                .map(AppSettingEntity::getSettingValue)
                .orElse(defaultValue);
    }

    public String getTheme()    { return getSetting(THEME_KEY, "dark"); }
    public String getLanguage() { return getSetting(LANGUAGE_KEY, "en"); }
    public boolean getSidebarCollapsed() {
        return Boolean.parseBoolean(getSetting(SIDEBAR_COLLAPSED_KEY, "false"));
    }
    public void setSidebarCollapsed(boolean collapsed) {
        writeSetting(SIDEBAR_COLLAPSED_KEY, String.valueOf(collapsed));
    }

    // ── Reads (delegate to AiConfigService) ───────────────
    public float getAiTemperature() { return aiConfigService.getAiTemperature(); }
    public float getAiTopP()        { return aiConfigService.getAiTopP(); }
    public int getAiMaxTokens()     { return aiConfigService.getAiMaxTokens(); }
    public String getAiSystemPrompt() { return aiConfigService.getAiSystemPrompt(); }

    // ── Writes ─────────────────────────────────────────────
    public void setAiTemperature(float value) { writeSetting(AI_TEMPERATURE_KEY, String.valueOf(value)); }
    public void setAiTopP(float value)        { writeSetting(AI_TOP_P_KEY, String.valueOf(value)); }
    public void setAiMaxTokens(int value)     { writeSetting(AI_MAX_TOKENS_KEY, String.valueOf(value)); }
    public void setAiSystemPrompt(String value) { writeSetting(AI_SYSTEM_PROMPT_KEY, value); }
    public void setTheme(String theme)        { writeSetting(THEME_KEY, theme); }
    public void setLanguage(String language)  { writeSetting(LANGUAGE_KEY, language); }

    private void writeSetting(String key, String value) {
        Long uid = securityContext.currentUserId();
        AppSettingEntity entity = appSettingRepo.findByUserIdAndSettingKey(uid, key)
                .orElseGet(() -> {
                    AppSettingEntity e = new AppSettingEntity();
                    e.setSettingKey(key);
                    e.setUserId(uid);
                    return e;
                });
        entity.setSettingValue(value);
        appSettingRepo.save(entity);
    }
}
```

- [ ] **Step 3: 重写 AiBackendInitializer 注入 AiConfigService**

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiBackendInitializer.java` 全文:

```java
package fan.summer.zhiflow.ai.spring;

import fan.summer.zhiflow.ai.AiConfigService;
import fan.summer.zhiflow.ai.service.SpringAiCloudBackend;
import fan.summer.zhiflow.api.ai.AiServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Initializes the configured AI backend once the Spring context is up.
 * Injects {@link AiConfigService} (now a bean) instead of static calls.
 */
@Component
public class AiBackendInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiBackendInitializer.class);

    private final AiConfigService aiConfigService;

    public AiBackendInitializer(AiConfigService aiConfigService) {
        this.aiConfigService = aiConfigService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String mode = aiConfigService.getAiMode();
        log.info("AI backend mode: {}", mode);
        switch (mode) {
            case "openai" -> AiServiceProvider.switchMode(mode, SpringAiCloudBackend.openAi(
                aiConfigService.getAiOpenAiEndpoint(),
                aiConfigService.getAiOpenAiApiKey(),
                aiConfigService.getAiOpenAiModel()));
            case "anthropic" -> AiServiceProvider.switchMode(mode, SpringAiCloudBackend.anthropic(
                aiConfigService.getAiAnthropicEndpoint(),
                aiConfigService.getAiAnthropicApiKey(),
                aiConfigService.getAiAnthropicModel()));
            case "deepseek" -> AiServiceProvider.switchMode(mode, SpringAiCloudBackend.deepSeek(
                aiConfigService.getAiDeepSeekEndpoint(),
                aiConfigService.getAiDeepSeekApiKey(),
                aiConfigService.getAiDeepSeekModel()));
            default -> log.info("AI backend: local (deferred, initializes on first use)");
        }
    }
}
```

- [ ] **Step 4: 重写 SettingsController 注入 AiConfigServiceHeadless**

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/web/controller/SettingsController.java` 全文:

```java
package fan.summer.zhiflow.web.controller;

import fan.summer.zhiflow.ai.service.AiConfigServiceHeadless;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * UI-shell settings — theme, language, sidebar-collapsed. Now injects
 * {@link AiConfigServiceHeadless} (a bean) instead of static calls.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final AiConfigServiceHeadless config;

    public SettingsController(AiConfigServiceHeadless config) {
        this.config = config;
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> out = new HashMap<>();
        out.put("theme", config.getTheme());
        out.put("language", config.getLanguage());
        out.put("sidebarCollapsed", config.getSidebarCollapsed());
        return out;
    }

    @PutMapping
    public Map<String, Object> put(@RequestBody Map<String, Object> body) {
        if (body.get("theme") instanceof String t) config.setTheme(t);
        if (body.get("language") instanceof String l) config.setLanguage(l);
        Object collapsed = body.get("sidebarCollapsed");
        if (collapsed instanceof Boolean b) config.setSidebarCollapsed(b);
        else if (collapsed instanceof String s) config.setSidebarCollapsed(Boolean.parseBoolean(s));
        return get();
    }
}
```

- [ ] **Step 5: 重写 EmailUtil 为 @Component(注入 ZhiFlowSettingEmailRepository + SecurityContext)**

**注意**:`EmailUtil` 原是纯静态工具类,被多处静态调用。本次改为 @Component,但为最小化触面,**保留一个静态外观委托方法**——通过 Spring 的 `ApplicationContext` 桥接。但更干净的做法是直接改实例方法,让调用方注入。检查 EmailUtil 的调用点。

先看 EmailUtil 当前的方法签名(都是 static)。改为 @Component 后,`loadConfig()` 改用注入的 repository。**由于 EmailUtil 的生产调用点极少(主要是邮件发送流程,且当前 Phase 1 的 controller 未直接调用它),本次改为 @Component 实例方法,调用方按需注入**。

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/utils/EmailUtil.java` 的类声明部分(从 `public class EmailUtil {` 到 `loadConfig` 方法)。先 Read 当前文件确认行号范围:

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && grep -n "class EmailUtil\|private EmailUtil\|loadConfig\|DatabaseInit\|SqlSession\|getMapper\|static" ZhiFlow/src/main/java/fan/summer/zhiflow/utils/EmailUtil.java | head -30`

根据输出,做以下改动:
1. 类上加 `@Component`
2. 删除 `private EmailUtil() {}` 私有构造,改为注入构造器:`public EmailUtil(ZhiFlowSettingEmailRepository repo, SecurityContext ctx)`
3. 新增字段 `private final ZhiFlowSettingEmailRepository emailRepo;` 和 `private final SecurityContext securityContext;`
4. `loadConfig()` 方法(`EmailUtil.java:199-213`)改为用 `emailRepo.findFirstByUserIdOrderByIdDesc(securityContext.currentUserId())`
5. 删除 `import org.apache.ibatis.session.SqlSession;` 和 `import fan.summer.zhiflow.database.DatabaseInit;` 和 `import fan.summer.zhiflow.database.mapper.setting.email.ZhiFlowSettingEmailMapper;`
6. 把所有 `public static` 方法改为 `public`(去 static)

新的 `loadConfig()` 方法体:

```java
    private ZhiFlowSettingEmailEntity loadConfig() throws EmailException {
        log.debug("Loading SMTP config from database");
        Long uid = securityContext.currentUserId();
        ZhiFlowSettingEmailEntity config = emailRepo.findFirstByUserIdOrderByIdDesc(uid)
                .orElse(null);
        if (config == null) {
            throw new EmailException(
                    "No email configuration found. Please configure SMTP settings first.", null);
        }
        log.debug("Loaded SMTP config | host={}:{} tls={} ssl={}",
                config.getSmtpAddress(), config.getSmtpPort(),
                config.getNeedTLS(), config.getNeedSSL());
        return config;
    }
```

**由于 EmailUtil 改为实例方法,所有 `EmailUtil.sendXxx()` 静态调用需改为注入调用。** 搜索调用点:

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && grep -rn "EmailUtil\." ZhiFlow/src/main/java/ | grep -v "backup"`

预期:调用点都在 ZhiFlow 模块内。如果有调用点,改为通过构造器注入 `EmailUtil` bean 调用。**如果调用点数量多导致本任务过大,可降级方案:保留 EmailUtil 静态,内部用一个 static holder 持有 Spring 注入的 repository(通过 @PostConstruct)。** 优先评估调用点数量后决定。

- [ ] **Step 6: 修改 HeadlessIntegrationTest,移除 DatabaseInit.init()**

编辑 `ZhiFlow/src/test/java/fan/summer/zhiflow/web/HeadlessIntegrationTest.java`:
- 删除 `import fan.summer.zhiflow.database.DatabaseInit;`
- 删除整个 `@BeforeAll static void initDb() { ... }` 方法(第 27-31 行)
- JPA ddl-auto 会自动建表,无需手动初始化

- [ ] **Step 7: 删除 DatabaseInit、所有 mapper、mybatis-config.xml、mapper XML**

```bash
cd /Users/phoebej/Develop/Java/SwissKitJ
rm ZhiFlow/src/main/java/fan/summer/zhiflow/database/DatabaseInit.java
rm -r ZhiFlow/src/main/java/fan/summer/zhiflow/database/mapper
rm ZhiFlow/src/main/resources/mybatis-config.xml
rm -r ZhiFlow/src/main/resources/mapper
```

- [ ] **Step 8: 编译验证**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow compile -o 2>&1 | tail -30`
Expected: BUILD SUCCESS。若有编译错误(遗漏的静态调用点),逐一修复:把 `AiConfigService.getXxx()` 静态调用改为注入的 bean 调用,把 `DatabaseInit.xxx` 调用改为 repository 调用。

- [ ] **Step 9: 运行已有测试**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow test -o 2>&1 | tail -30`
Expected: 测试通过(或至少编译通过,集成测试可能因缺少 datasource.properties 而失败——这会在 Task 13+ 的 SETUP/APP 模式分流中解决)。**如果集成测试因 DataSource 缺失失败,这是预期的**——后续任务补上。

- [ ] **Step 10: 提交**

```bash
git add -A ZhiFlow/
git commit -m "♻️ refactor(db): migrate DB call sites to JPA repositories, remove DatabaseInit + mybatis"
```

---

### Task 9: 虚拟用户初始化器(APP 模式启动确保 id=1 存在)

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/database/VirtualUserInitializer.java`
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/database/VirtualUserInitializerTest.java`

**Interfaces:**
- Produces: APP 模式启动后 `sys_user` 表必有 id=1 的 `ZFlow-Summer` 虚拟用户
- Consumes: `SysUserRepository`、`SecurityConstants`

**关键**:`IDENTITY` 策略下显式 setId(1) 会被 Hibernate 忽略。解决方案:先检查 `existsById(1L)`,若不存在,用原生 SQL `INSERT INTO sys_user (id, username, ...) VALUES (1, 'ZFlow-Summer', ...)` 插入,保证 id 固定。

- [ ] **Step 1: 写失败测试**

创建 `ZhiFlow/src/test/java/fan/summer/zhiflow/database/VirtualUserInitializerTest.java`:

```java
package fan.summer.zhiflow.database;

import fan.summer.zhiflow.database.entity.SysUserEntity;
import fan.summer.zhiflow.database.repository.SysUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class VirtualUserInitializerTest {

    @Autowired
    private SysUserRepository sysUserRepo;

    @Autowired
    private VirtualUserInitializer initializer;

    @Test
    void ensureVirtualUser_createsIdOneWithCorrectAttributes() {
        initializer.ensureVirtualUser();

        SysUserEntity u = sysUserRepo.findById(SecurityConstants.LOCAL_VIRTUAL_USER_ID)
                .orElseThrow(() -> new AssertionError("virtual user id=1 not found"));
        assertEquals(SecurityConstants.LOCAL_VIRTUAL_USERNAME, u.getUsername());
        assertEquals(1, u.getStatus());
        assertEquals(1, u.getUserType());
        assertEquals("local", u.getAuthProvider());
        assertNull(u.getPasswordHash());
    }

    @Test
    void ensureVirtualUser_idempotent_doesNotDuplicate() {
        initializer.ensureVirtualUser();
        initializer.ensureVirtualUser();   // second call should be a no-op

        long count = sysUserRepo.findAll().stream()
                .filter(u -> u.getId() == SecurityConstants.LOCAL_VIRTUAL_USER_ID)
                .count();
        assertEquals(1, count);
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow test -Dtest=VirtualUserInitializerTest -o 2>&1 | tail -15`
Expected: FAIL — `VirtualUserInitializer` 不存在

- [ ] **Step 3: 实现 VirtualUserInitializer**

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/database/VirtualUserInitializer.java`:

```java
package fan.summer.zhiflow.database;

import fan.summer.zhiflow.database.entity.SysUserEntity;
import fan.summer.zhiflow.database.repository.SysUserRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Ensures the local virtual user (id=1, "ZFlow-Summer") exists after the Spring context
 * starts in APP mode. All unauthenticated (local offline) requests are attributed to this user.
 *
 * <p>The id is fixed at 1 via a native INSERT because Hibernate's IDENTITY generation ignores
 * explicit ids set on entities. We use {@link EntityManager#createNativeQuery} to insert the
 * row with an explicit id, then the virtual user is stable across restarts.
 */
@Component
public class VirtualUserInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VirtualUserInitializer.class);

    private final SysUserRepository sysUserRepo;
    private final EntityManager entityManager;

    public VirtualUserInitializer(SysUserRepository sysUserRepo, EntityManager entityManager) {
        this.sysUserRepo = sysUserRepo;
        this.entityManager = entityManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureVirtualUser();
    }

    /**
     * Public for testing. Idempotent — safe to call multiple times.
     */
    public void ensureVirtualUser() {
        if (sysUserRepo.existsById(SecurityConstants.LOCAL_VIRTUAL_USER_ID)) {
            return;
        }
        log.info("Creating local virtual user (id=1, username={})", SecurityConstants.LOCAL_VIRTUAL_USERNAME);
        // Native insert to force id=1 (IDENTITY strategy would otherwise assign its own id).
        entityManager.createNativeQuery(
                "INSERT INTO sys_user (id, username, password_hash, auth_provider, status, user_type) " +
                "VALUES (?, ?, NULL, ?, ?, ?)")
                .setParameter(1, SecurityConstants.LOCAL_VIRTUAL_USER_ID)
                .setParameter(2, SecurityConstants.LOCAL_VIRTUAL_USERNAME)
                .setParameter(3, "local")
                .setParameter(4, 1)
                .setParameter(5, 1)
                .executeUpdate();
        entityManager.clear();
        log.info("Local virtual user created");
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow test -Dtest=VirtualUserInitializerTest -o 2>&1 | tail -15`
Expected: PASS。**注意**:此测试需要完整 Spring 上下文 + DataSource。若此时 DataSource 配置(Task 11)尚未就绪,测试会失败——可暂时跳过,在 Task 12 DataSource 就绪后回填验证。

- [ ] **Step 5: 提交**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/database/VirtualUserInitializer.java ZhiFlow/src/test/java/fan/summer/zhiflow/database/VirtualUserInitializerTest.java
git commit -m "✨ feat(db): add VirtualUserInitializer to ensure virtual user id=1 exists on startup"
```

---

### Task 10: APP 模式 DataSource + JPA 配置 bean

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/config/DataSourceAutoConfig.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/config/JpaConfig.java`
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiApplication.java`(标记为 APP 模式入口)

**Interfaces:**
- Produces: `DataSource` bean(从 datasource.properties 构造 HikariCP)、`hibernate.dialect` 注入
- Consumes: Task 12 的 `DataSourceConfigService`(读 datasource.properties)

**依赖关系**:本任务的 `DataSourceAutoConfig` 注入 `DataSourceConfigService`(Task 12 创建)。为避免循环,Task 12 必须先于本任务的可运行验证。**实现顺序:先写 Task 12(DataSourceConfigService),再写本任务**。但文档顺序按逻辑分块,执行时调整。

- [ ] **Step 1: 创建 DataSourceAutoConfig**

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/config/DataSourceAutoConfig.java`:

```java
package fan.summer.zhiflow.config;

import com.zaxxer.hikari.HikariDataSource;
import fan.summer.zhiflow.setup.DataSourceConfig;
import fan.summer.zhiflow.setup.DataSourceConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Constructs the {@link DataSource} bean in APP mode from the persisted
 * {@code datasource.properties}. Uses HikariCP. Only active when
 * {@code zhiflow.mode=app} (set by {@code HeadlessLauncher}).
 *
 * <p>In SETUP mode this bean is absent — Spring's DataSource auto-config is excluded via
 * {@code AiApplication}'s excludes, so the minimal context starts without any DB dependency.
 */
@Configuration
@ConditionalOnProperty(name = "zhiflow.mode", havingValue = "app")
public class DataSourceAutoConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceAutoConfig.class);

    @Bean
    public DataSource dataSource(DataSourceConfigService configService) {
        DataSourceConfig cfg = configService.load();
        if (cfg == null) {
            throw new IllegalStateException(
                    "datasource.properties missing but zhiflow.mode=app — corrupted state");
        }
        log.info("Configuring DataSource: type={}, url={}", cfg.type(), cfg.url());
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(cfg.url());
        ds.setDriverClassName(cfg.driver());
        if (cfg.username() != null && !cfg.username().isBlank()) {
            ds.setUsername(cfg.username());
            ds.setPassword(cfg.password());   // already decrypted by load()
        }
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        return ds;
    }
}
```

- [ ] **Step 2: 创建 JpaConfig(方言注入)**

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/config/JpaConfig.java`:

```java
package fan.summer.zhiflow.config;

import fan.summer.zhiflow.setup.DataSourceConfig;
import fan.summer.zhiflow.setup.DataSourceConfigService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Injects the Hibernate dialect from {@code datasource.properties} into JPA properties.
 * Active only in APP mode. The dialect is determined by the chosen {@link
 * fan.summer.zhiflow.setup.DbType} during setup and persisted.
 *
 * <p>Uses a {@code JpaPropertySource}-style approach: we set
 * {@code spring.jpa.properties.hibernate.dialect} as a system property before context refresh,
 * because Spring Boot's JPA auto-config reads it during EMF construction.
 */
@Configuration
@ConditionalOnProperty(name = "zhiflow.mode", havingValue = "app")
public class JpaConfig {

    public JpaConfig(DataSourceConfigService configService) {
        DataSourceConfig cfg = configService.load();
        if (cfg != null && cfg.dialect() != null) {
            // Set before EMF auto-config reads it.
            System.setProperty("spring.jpa.properties.hibernate.dialect", cfg.dialect());
        }
    }
}
```

- [ ] **Step 3: 修改 AiApplication 标记 APP 模式 + 排除 SETUP 不需要的自动配置**

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiApplication.java` 全文:

```java
package fan.summer.zhiflow.ai.spring;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * APP-mode Spring Boot application — the full ZhiFlow context with JPA, AI, plugins.
 *
 * <p>Scans {@code fan.summer.zhiflow}, picking up web controllers, the plugin registry,
 * the AI {@code ChatModel} beans, JPA repositories, and the security config. The DataSource
 * is provided by {@code DataSourceAutoConfig} (conditional on {@code zhiflow.mode=app}).
 *
 * <p>SETUP mode uses a separate minimal entry point ({@code SetupApplication}) that excludes
 * DataSource/JPA auto-configuration entirely.
 */
@SpringBootApplication
@ComponentScan(basePackages = "fan.summer.zhiflow")
public class AiApplication {
}
```

(注:不在此处加 exclude,因为 APP 模式需要 JPA 自动配置。SETUP 模式的 SetupApplication 才 exclude。)

- [ ] **Step 4: 提交**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/config/ ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiApplication.java
git commit -m "✨ feat(config): add APP-mode DataSource + JPA dialect config beans"
```

---

## 阶段三:初始化向导(SETUP 模式)

### Task 11: CryptoUtil(AES-GCM 加解密)

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/CryptoUtil.java`
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/setup/CryptoUtilTest.java`

**Interfaces:**
- Produces: `CryptoUtil.encrypt(String) -> String`(ENC(...) 前缀)、`CryptoUtil.decrypt(String) -> String`
- Consumes: 机器特征文件 `~/.zhiflow/config/.machineid`

- [ ] **Step 1: 写失败测试(往返 + ENC 前缀 + 明文兼容)**

创建 `ZhiFlow/src/test/java/fan/summer/zhiflow/setup/CryptoUtilTest.java`:

```java
package fan.summer.zhiflow.setup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CryptoUtilTest {

    @Test
    void encrypt_decrypt_roundtrip_recoversPlaintext() {
        String plain = "mySecretPassword123!";
        String cipher = CryptoUtil.encrypt(plain);
        assertNotEquals(plain, cipher);
        assertTrue(cipher.startsWith("ENC("));
        assertEquals(plain, CryptoUtil.decrypt(cipher));
    }

    @Test
    void decrypt_plainTextWithoutPrefix_returnsAsIs() {
        // Plaintext without ENC(...) prefix is returned as-is (backward compat for hand-written config)
        assertEquals("rawPassword", CryptoUtil.decrypt("rawPassword"));
    }

    @Test
    void encrypt_emptyString_stillEncrypted() {
        String cipher = CryptoUtil.encrypt("");
        assertTrue(cipher.startsWith("ENC("));
        assertEquals("", CryptoUtil.decrypt(cipher));
    }

    @Test
    void decrypt_nullOrBlank_returnsInput() {
        assertNull(CryptoUtil.decrypt(null));
        assertEquals("", CryptoUtil.decrypt(""));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow test -Dtest=CryptoUtilTest -o 2>&1 | tail -10`
Expected: FAIL — 类不存在

- [ ] **Step 3: 实现 CryptoUtil**

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/CryptoUtil.java`:

```java
package fan.summer.zhiflow.setup;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Lightweight AES-GCM encryption for sensitive datasource config fields (e.g. db.password).
 *
 * <p>Key derivation: a fixed project constant XOR'd with a per-machine random UUID
 * (stored at {@code ~/.zhiflow/config/.machineid}), SHA-256'd to a 256-bit AES key.
 * This means an encrypted config file cannot be decrypted on a different machine,
 * reducing the value of a stolen config file.
 *
 * <p>Encrypted values are prefixed with {@code ENC(...)} so {@link #decrypt} can detect
 * them; values without the prefix are returned as-is (supports hand-written plaintext configs).
 */
public final class CryptoUtil {

    private static final String PREFIX = "ENC(";
    private static final String SUFFIX = ")";
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private static final String PROJECT_CONSTANT = "ZhiFlow-4.0-Phase4-SetupKey";

    private CryptoUtil() {}

    private static SecretKeySpec deriveKey() {
        try {
            Path machineIdFile = Path.of(System.getProperty("user.dir"), ".zhiflow", "config", ".machineid");
            Files.createDirectories(machineIdFile.getParent());
            String machineId;
            if (Files.exists(machineIdFile)) {
                machineId = Files.readString(machineIdFile).trim();
            } else {
                machineId = UUID.randomUUID().toString();
                Files.writeString(machineIdFile, machineId);
            }
            String material = PROJECT_CONSTANT + ":" + machineId;
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, ALGORITHM);
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive crypto key", e);
        }
    }

    public static String encrypt(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            // Prepend IV to cipher text, base64 the whole thing.
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            String b64 = Base64.getEncoder().encodeToString(combined);
            return PREFIX + b64 + SUFFIX;
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public static String decrypt(String value) {
        if (value == null || value.isBlank()) return value;
        if (!value.startsWith(PREFIX) || !value.endsWith(SUFFIX)) {
            return value;   // plaintext passthrough
        }
        try {
            String b64 = value.substring(PREFIX.length(), value.length() - SUFFIX.length());
            byte[] combined = Base64.getDecoder().decode(b64);
            byte[] iv = new byte[IV_BYTES];
            byte[] cipherText = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            System.arraycopy(combined, IV_BYTES, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(cipherText);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow test -Dtest=CryptoUtilTest -o 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/setup/CryptoUtil.java ZhiFlow/src/test/java/fan/summer/zhiflow/setup/CryptoUtilTest.java
git commit -m "✨ feat(setup): add AES-GCM CryptoUtil for datasource password encryption"
```

---

### Task 12: DataSourceConfigService(读写 datasource.properties)

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/DataSourceConfigService.java`
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/setup/DataSourceConfigServiceTest.java`

**Interfaces:**
- Produces: `load() -> DataSourceConfig`(null 表示未配置)、`save(DataSourceConfig)`、`buildFromWizard(DbType, WizardParams) -> DataSourceConfig`、`testConnection(DataSourceConfig) -> ConnectionTestResult`、`decryptPassword(DataSourceConfig) -> String`

- [ ] **Step 1: 写失败测试**

创建 `ZhiFlow/src/test/java/fan/summer/zhiflow/setup/DataSourceConfigServiceTest.java`:

```java
package fan.summer.zhiflow.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class DataSourceConfigServiceTest {

    @TempDir
    Path tempDir;

    private DataSourceConfigService newService() {
        return new DataSourceConfigService(tempDir.toString());
    }

    @Test
    void load_fileMissing_returnsNull() {
        assertNull(newService().load());
    }

    @Test
    void save_thenLoad_roundtripsH2Config() {
        DataSourceConfigService svc = newService();
        WizardParams params = new WizardParams(
                tempDir.resolve("data/zhiflow").toString(), null, null, null, null, null);
        DataSourceConfig cfg = svc.buildFromWizard(DbType.H2, params);
        svc.save(cfg);

        DataSourceConfig loaded = svc.load();
        assertNotNull(loaded);
        assertEquals(DbType.H2, loaded.type());
        assertEquals("org.h2.Driver", loaded.driver());
        assertTrue(loaded.url().startsWith("jdbc:h2:file:"));
        assertEquals("", loaded.username());   // embedded: no credentials
    }

    @Test
    void save_mysqlConfig_passwordIsEncrypted() {
        DataSourceConfigService svc = newService();
        WizardParams params = new WizardParams(
                null, "db.example.com", 3306, "zhiflow", "admin", "s3cret");
        DataSourceConfig cfg = svc.buildFromWizard(DbType.MYSQL, params);
        svc.save(cfg);

        // Read the raw file — password must NOT be plaintext
        Properties props = svc.readRawForTest();
        String storedPw = props.getProperty("db.password");
        assertNotNull(storedPw);
        assertNotEquals("s3cret", storedPw);
        assertTrue(storedPw.startsWith("ENC("));

        // And load() decrypts it back
        DataSourceConfig loaded = svc.load();
        assertEquals("s3cret", loaded.password());
    }

    @Test
    void buildFromWizard_mysql_assemblesCorrectUrl() {
        WizardParams params = new WizardParams(null, "localhost", 3306, "zhiflow", "root", "pw");
        DataSourceConfig cfg = newService().buildFromWizard(DbType.MYSQL, params);
        assertEquals("jdbc:mysql://localhost:3306/zhiflow", cfg.url());
        assertEquals("com.mysql.cj.jdbc.Driver", cfg.driver());
        assertEquals("root", cfg.username());
    }

    @Test
    void buildFromWizard_embedded_resolvesRelativePathToAbsolute() {
        DataSourceConfigService svc = newService();
        WizardParams params = new WizardParams(".zhiflow/data/zhiflow", null, null, null, null, null);
        DataSourceConfig cfg = svc.buildFromWizard(DbType.H2, params);
        // Relative path resolved against user.dir (test tempDir here)
        assertTrue(cfg.url().contains(tempDir.toString().replace("\\", "/")));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow test -Dtest=DataSourceConfigServiceTest -o 2>&1 | tail -10`
Expected: FAIL — 类不存在

- [ ] **Step 3: 实现 DataSourceConfigService**

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/DataSourceConfigService.java`:

```java
package fan.summer.zhiflow.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.util.Properties;

/**
 * Reads/writes {@code datasource.properties} and assembles {@link DataSourceConfig} from
 * wizard params. Also handles password encryption via {@link CryptoUtil} and connection testing.
 *
 * <p>Config file location defaults to {@code <userDir>/.zhiflow/config/datasource.properties}.
 * The base dir is injectable for testing.
 */
@Service
public class DataSourceConfigService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfigService.class);

    private final String baseDir;

    /** Production constructor — uses {@code <user.dir>/.zhiflow} as base. */
    public DataSourceConfigService() {
        this(Path.of(System.getProperty("user.dir"), ".zhiflow").toString());
    }

    /** Test constructor — injects base dir (temp dir). */
    public DataSourceConfigService(String baseDir) {
        this.baseDir = baseDir;
    }

    private Path configFile() {
        return Path.of(baseDir, "config", "datasource.properties");
    }

    /** Loads the datasource config. Returns {@code null} if the file is missing or invalid. */
    public DataSourceConfig load() {
        Path file = configFile();
        if (!Files.exists(file)) return null;
        try (InputStream in = Files.newInputStream(file)) {
            Properties props = new Properties();
            props.load(in);
            String typeStr = props.getProperty("db.type");
            if (typeStr == null || typeStr.isBlank()) return null;
            DbType type = DbType.fromName(typeStr);
            return new DataSourceConfig(
                    type,
                    props.getProperty("db.url"),
                    props.getProperty("db.driver"),
                    props.getProperty("db.dialect"),
                    props.getProperty("db.username", ""),
                    CryptoUtil.decrypt(props.getProperty("db.password", "")),
                    props.getProperty("db.file.path", ""));
        } catch (Exception e) {
            log.warn("Failed to load datasource.properties: {}", e.getMessage());
            return null;
        }
    }

    /** Persists the config, encrypting the password. */
    public void save(DataSourceConfig cfg) {
        try {
            Path file = configFile();
            Files.createDirectories(file.getParent());
            Properties props = new Properties();
            props.setProperty("db.type", cfg.type().name().toLowerCase());
            props.setProperty("db.url", cfg.url());
            props.setProperty("db.driver", cfg.driver());
            props.setProperty("db.dialect", cfg.dialect());
            if (cfg.username() != null) props.setProperty("db.username", cfg.username());
            if (cfg.password() != null && !cfg.password().isBlank()) {
                props.setProperty("db.password", CryptoUtil.encrypt(cfg.password()));
            }
            if (cfg.filePath() != null) props.setProperty("db.file.path", cfg.filePath());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "ZhiFlow datasource configuration (generated by setup wizard)");
            }
            log.info("Saved datasource.properties: type={}", cfg.type());
        } catch (IOException e) {
            throw new RuntimeException("Failed to write datasource.properties", e);
        }
    }

    /**
     * Assembles a {@link DataSourceConfig} from wizard params, resolving paths/URL.
     * Does NOT persist — call {@link #save} after testing.
     */
    public DataSourceConfig buildFromWizard(DbType type, WizardParams params) {
        String url;
        String filePath = null;
        if (type.embedded) {
            // Resolve relative path against base dir, make absolute.
            String rawPath = (params.filePath() == null || params.filePath().isBlank())
                    ? Path.of(baseDir, "data", "zhiflow").toString()
                    : params.filePath();
            Path resolved = Path.of(rawPath);
            if (!resolved.isAbsolute()) {
                resolved = Path.of(baseDir).resolve(rawPath).toAbsolutePath();
            }
            filePath = resolved.toString().replace("\\", "/");
            url = type.urlTemplate.replace("{path}", filePath);
        } else {
            int port = params.port() != null ? params.port() : defaultPort(type);
            url = type.urlTemplate
                    .replace("{host}", params.host())
                    .replace("{port}", String.valueOf(port))
                    .replace("{db}", params.database());
        }
        return new DataSourceConfig(
                type, url, type.driver, type.dialect,
                params.username() == null ? "" : params.username(),
                params.password() == null ? "" : params.password(),
                filePath);
    }

    private int defaultPort(DbType type) {
        return switch (type) {
            case MYSQL -> 3306;
            case POSTGRESQL -> 5432;
            default -> 0;
        };
    }

    /**
     * Tests a connection WITHOUT persisting. Opens a raw JDBC connection (3s timeout),
     * runs {@code SELECT 1}, returns metadata. Connection is always closed.
     */
    public ConnectionTestResult testConnection(DataSourceConfig cfg) {
        try {
            Class.forName(cfg.driver());
        } catch (ClassNotFoundException e) {
            return ConnectionTestResult.fail("Driver not found: " + cfg.driver());
        }
        String sql = "SELECT 1";
        // SQLite uses a different validation query syntax but SELECT 1 works on all four.
        try (Connection conn = DriverManager.getConnection(cfg.url(),
                cfg.username().isBlank() ? null : cfg.username(),
                cfg.password().isBlank() ? null : cfg.password())) {
            conn.createStatement().execute(sql);
            DatabaseMetaData md = conn.getMetaData();
            return ConnectionTestResult.ok(cfg.dialect(),
                    md.getDatabaseProductName() + " " + md.getDatabaseProductVersion());
        } catch (Exception e) {
            return ConnectionTestResult.fail(e.getMessage());
        }
    }

    /** Decrypts the password field (load() already decrypts; this is for explicitness). */
    public String decryptPassword(DataSourceConfig cfg) {
        return CryptoUtil.decrypt(cfg.password());
    }

    /** Test-only: read raw properties (with encrypted password) for assertions. */
    Properties readRawForTest() throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configFile())) {
            props.load(in);
        }
        return props;
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow test -Dtest=DataSourceConfigServiceTest -o 2>&1 | tail -10`
Expected: PASS(5 个测试)

- [ ] **Step 5: 提交**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/setup/DataSourceConfigService.java ZhiFlow/src/test/java/fan/summer/zhiflow/setup/DataSourceConfigServiceTest.java
git commit -m "✨ feat(setup): add DataSourceConfigService for reading/writing datasource.properties"
```

---

### Task 13: SetupController + SetupApplication(SETUP 模式)

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/SetupController.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/SetupApplication.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ExitCodes.java`
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/web/filter/TokenAuthFilter.java`(放行 /api/setup/)

**Interfaces:**
- Produces: `/api/setup/{status,types,test-connection,initialize}` 端点、SETUP 模式 Spring 入口、`ExitCodes.SETUP_DONE`(0)

- [ ] **Step 1: 创建 ExitCodes**

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/ExitCodes.java`:

```java
package fan.summer.zhiflow;

/**
 * Process exit codes used by {@link HeadlessLauncher} to coordinate with the Tauri sidecar
 * supervisor (or Web deployment restart logic).
 */
public final class ExitCodes {

    private ExitCodes() {}

    /** Setup wizard completed successfully — parent process should restart into APP mode. */
    public static final int SETUP_DONE = 0;

    /** Fatal startup error. */
    public static final int FATAL = 1;
}
```

- [ ] **Step 2: 创建 SetupApplication(SETUP 模式最小上下文)**

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/SetupApplication.java`:

```java
package fan.summer.zhiflow.setup;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * SETUP-mode Spring Boot application — a minimal context that serves only the setup wizard.
 *
 * <p>Excludes {@link DataSourceAutoConfiguration} and {@link HibernateJpaAutoConfiguration} so the
 * context starts with zero DB dependency. The wizard's test/initialize endpoints construct
 * temporary {@code DataSource}/{@code EntityManagerFactory} instances on demand and close them
 * immediately — they never pollute this context.
 *
 * <p>Scans only the {@code setup} package (plus {@link fan.summer.zhiflow.web.PortAnnouncer} and
 * the token filter, which live under {@code web}).
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class
})
@ComponentScan(basePackages = {"fan.summer.zhiflow.setup", "fan.summer.zhiflow.web"})
public class SetupApplication {
}
```

- [ ] **Step 3: 创建 SetupController**

创建 `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/SetupController.java`:

```java
package fan.summer.zhiflow.setup;

import fan.summer.zhiflow.ExitCodes;
import jakarta.servlet.ServletContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Setup wizard REST endpoints. Only active in SETUP mode (served by {@link SetupApplication}).
 * Token auth is bypassed for these paths (see {@code TokenAuthFilter}).
 *
 * <p>The {@code initialize} endpoint performs DDL via a temporary EntityManagerFactory
 * (constructed from the wizard-submitted DataSource), then writes datasource.properties and
 * signals the process to exit so the parent supervisor restarts into APP mode.
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private static final Logger log = LoggerFactory.getLogger(SetupController.class);

    private final DataSourceConfigService configService;

    public SetupController(DataSourceConfigService configService) {
        this.configService = configService;
    }

    /** Frontend's first call on startup — determines whether to show the wizard or main shell. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        DataSourceConfig cfg = configService.load();
        boolean initialized = cfg != null;
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("initialized", initialized);
        if (!initialized) {
            out.put("supportedTypes", Arrays.stream(DbType.values())
                    .map(e -> e.name().toLowerCase()).toList());
            out.put("embeddedTypes", Arrays.stream(DbType.values())
                    .filter(e -> e.embedded).map(e -> e.name().toLowerCase()).toList());
        }
        return out;
    }

    /** Returns per-type form metadata for the wizard UI. */
    @GetMapping("/types")
    public List<Map<String, Object>> types() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DbType t : DbType.values()) {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("type", t.name().toLowerCase());
            entry.put("label", labelFor(t));
            entry.put("embedded", t.embedded);
            List<Map<String, Object>> fields = new ArrayList<>();
            if (t.embedded) {
                fields.add(Map.of("name", "filePath", "label", "Data file location",
                        "required", true, "secret", false));
            } else {
                fields.add(Map.of("name", "host", "required", true, "secret", false));
                fields.add(Map.of("name", "port", "required", false, "secret", false,
                        "default", defaultPort(t)));
                fields.add(Map.of("name", "database", "required", true, "secret", false));
                fields.add(Map.of("name", "username", "required", true, "secret", false));
                fields.add(Map.of("name", "password", "required", true, "secret", true));
            }
            entry.put("fields", fields);
            out.add(entry);
        }
        return out;
    }

    /** Tests a connection WITHOUT persisting. Frontend "Test connection" button. */
    @PostMapping("/test-connection")
    public ConnectionTestResult testConnection(@RequestBody TestRequest req) {
        DbType type = DbType.fromName(req.type());
        DataSourceConfig cfg = configService.buildFromWizard(type, req.params());
        return configService.testConnection(cfg);
    }

    /**
     * Final initialization: re-test, run DDL, persist config, signal restart.
     * Atomic — on failure, rolls back (deletes any partially-written config).
     */
    @PostMapping("/initialize")
    public Map<String, Object> initialize(@RequestBody TestRequest req) {
        DbType type = DbType.fromName(req.type());
        DataSourceConfig cfg = configService.buildFromWizard(type, req.params());

        // 1. Re-verify connection
        ConnectionTestResult test = configService.testConnection(cfg);
        if (!test.success()) {
            return Map.of("success", false, "error", test.error(), "step", "connection");
        }

        // 2. Run DDL via temporary EMF (ddl-auto=update)
        try {
            runDdl(cfg);
        } catch (Exception e) {
            log.error("DDL failed", e);
            return Map.of("success", false, "error", e.getMessage(), "step", "ddl");
        }

        // 3. Persist config
        try {
            configService.save(cfg);
        } catch (Exception e) {
            log.error("Failed to persist datasource config", e);
            return Map.of("success", false, "error", e.getMessage(), "step", "save");
        }

        // 4. Schedule process exit so parent restarts into APP mode.
        // Delay 1s so the HTTP response is flushed to the frontend first.
        log.info("Setup complete; exiting in 1s for restart (type={})", type);
        Thread exitHook = new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            System.exit(ExitCodes.SETUP_DONE);
        }, "setup-exit");
        exitHook.setDaemon(true);
        exitHook.start();

        return Map.of("success", true, "action", "restart");
    }

    /** Runs ddl-auto=update against a fresh EMF built from the wizard config, then closes it. */
    private void runDdl(DataSourceConfig cfg) {
        Map<String, Object> props = new java.util.HashMap<>();
        props.put("javax.persistence.jdbc.driver", cfg.driver());
        props.put("javax.persistence.jdbc.url", cfg.url());
        if (!cfg.username().isBlank()) {
            props.put("javax.persistence.jdbc.user", cfg.username());
            props.put("javax.persistence.jdbc.password", cfg.password());
        }
        props.put("hibernate.dialect", cfg.dialect());
        props.put("hibernate.hbm2ddl.auto", "update");
        props.put("hibernate.show_sql", "false");
        jakarta.persistence.EntityManagerFactory emf =
                jakarta.persistence.Persistence.createEntityManagerFactory("zhiflow-setup", props);
        try {
            emf.createEntityManager().close();   // forces schema gen
            createVirtualUserNative(emf, cfg);
        } finally {
            emf.close();
        }
    }

    /** Inserts the virtual user (id=1, ZFlow-Summer) via native query on the fresh schema. */
    private void createVirtualUserNative(jakarta.persistence.EntityManagerFactory emf, DataSourceConfig cfg) {
        // Re-open a raw JDBC connection to insert the virtual user with a fixed id.
        try (java.sql.Connection conn = cfg.username().isBlank()
                ? java.sql.DriverManager.getConnection(cfg.url())
                : java.sql.DriverManager.getConnection(cfg.url(), cfg.username(), cfg.password())) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO sys_user (id, username, password_hash, auth_provider, status, user_type) " +
                        "SELECT 1, 'ZFlow-Summer', NULL, 'local', 1, 1 " +
                        "WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 1)");
            }
        } catch (Exception e) {
            log.warn("Could not pre-create virtual user during setup (will be created on APP start): {}", e.getMessage());
        }
    }

    private String labelFor(DbType t) {
        return switch (t) {
            case H2 -> "H2 (local embedded)";
            case SQLITE -> "SQLite (local embedded)";
            case MYSQL -> "MySQL (remote)";
            case POSTGRESQL -> "PostgreSQL (remote)";
        };
    }

    private int defaultPort(DbType t) {
        return switch (t) {
            case MYSQL -> 3306;
            case POSTGRESQL -> 5432;
            default -> 0;
        };
    }

    /** Wizard request body — type + raw params. */
    public record TestRequest(String type, WizardParams params) {}
}
```

- [ ] **Step 4: 修改 TokenAuthFilter 放行 /api/setup/**

编辑 `ZhiFlow/src/main/java/fan/summer/zhiflow/web/filter/TokenAuthFilter.java`,在 `doFilterInternal` 方法(`TokenAuthFilter.java:38-42`)的放行判断中,把:

```java
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || "/api/health".equals(path)) {
            chain.doFilter(request, response);
            return;
        }
```

改为:

```java
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())
                || "/api/health".equals(path)
                || path.startsWith("/api/setup/")) {
            chain.doFilter(request, response);
            return;
        }
```

- [ ] **Step 5: 提交**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/setup/SetupController.java ZhiFlow/src/main/java/fan/summer/zhiflow/setup/SetupApplication.java ZhiFlow/src/main/java/fan/summer/zhiflow/ExitCodes.java ZhiFlow/src/main/java/fan/summer/zhiflow/web/filter/TokenAuthFilter.java
git commit -m "✨ feat(setup): add SetupController + SetupApplication (minimal SETUP-mode context)"
```

---

### Task 14: HeadlessLauncher 改为 SETUP/APP 分流

**Files:**
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/HeadlessLauncher.java`

**Interfaces:**
- Produces: `main()` 根据 datasource.properties 存在性,启动 SetupApplication 或 AiApplication(带 `zhiflow.mode=app` 属性)

- [ ] **Step 1: 重写 HeadlessLauncher 的 main + startWithFallback**

替换 `ZhiFlow/src/main/java/fan/summer/zhiflow/HeadlessLauncher.java` 全文:

```java
package fan.summer.zhiflow;

import fan.summer.zhiflow.ai.spring.AiApplication;
import fan.summer.zhiflow.api.log.LoggerBinder;
import fan.summer.zhiflow.log.Slf4jPluginLoggerBinder;
import fan.summer.zhiflow.setup.DataSourceConfigService;
import fan.summer.zhiflow.setup.SetupApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 4 headless entry point. Boots ZhiFlow as a loopback Spring Boot web server in one of
 * two modes, determined by the presence of {@code ~/.zhiflow/config/datasource.properties}:
 *
 * <ul>
 *   <li><b>SETUP mode</b> (config missing): boots {@link SetupApplication} — a minimal context
 *       with only the setup wizard endpoints. No DataSource/JPA. After the wizard completes,
 *       the process exits with {@link ExitCodes#SETUP_DONE} so the Tauri supervisor restarts
 *       into APP mode.</li>
 *   <li><b>APP mode</b> (config present): boots {@link AiApplication} with
 *       {@code zhiflow.mode=app} — the full context with JPA, AI, plugins.</li>
 * </ul>
 *
 * <p>Both modes bind loopback ({@code server.address=127.0.0.1} from application.yml) and accept
 * the same {@code --port} / {@code --token} CLI args. {@link fan.summer.zhiflow.web.PortAnnouncer}
 * prints {@code ZHIFLOW_PORT=<n>} in both modes, so the Tauri sidecar reads the port identically.
 */
public final class HeadlessLauncher {

    /** System property the {@code TokenAuthFilter} reads. */
    public static final String TOKEN_PROPERTY = "zhiflow.auth.token";

    /** Fixed loopback port the backend binds by default. Overridable via {@code --port=<n>}. */
    public static final String DEFAULT_PORT = "24056";

    private HeadlessLauncher() {}

    public static void main(String[] args) {
        primeLogDirectory();

        String port = DEFAULT_PORT;
        String token = "";
        for (String a : args) {
            if (a.startsWith("--port=")) {
                port = a.substring("--port=".length()).trim();
            } else if (a.startsWith("--token=")) {
                token = a.substring("--token=".length()).trim();
            }
        }
        if (!token.isBlank()) {
            System.setProperty(TOKEN_PROPERTY, token);
        }

        LoggerBinder.bind(new Slf4jPluginLoggerLoggerBinder());

        boolean configured = isDatasourceConfigured();
        startWithFallback(port, configured);
        // main() returns; the embedded Tomcat's non-daemon threads keep the JVM alive.
    }

    /** True if {@code datasource.properties} exists and is loadable. */
    private static boolean isDatasourceConfigured() {
        try {
            return new DataSourceConfigService().load() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Boots Spring Boot on the given port, retrying on {@code --server.port=0} if the requested
     * port cannot be bound. Selects SETUP vs APP context based on {@code configured}.
     */
    private static void startWithFallback(String port, boolean configured) {
        List<String> baseArgs = new ArrayList<>();
        try {
            runSpring(baseArgs, port, configured);
        } catch (RuntimeException e) {
            if ("0".equals(port)) {
                throw e;
            }
            System.err.println("WARN: could not bind port " + port + " (" + e.getMessage()
                    + "); retrying on an OS-assigned free port (--server.port=0).");
            runSpring(baseArgs, "0", configured);
        }
    }

    private static void runSpring(List<String> baseArgs, String port, boolean configured) {
        List<String> springArgs = new ArrayList<>(baseArgs);
        springArgs.add("--server.port=" + port);
        Class<?> appClass = configured ? AiApplication.class : SetupApplication.class;
        SpringApplicationBuilder builder = new SpringApplicationBuilder(appClass);
        if (configured) {
            // APP mode marker — DataSourceAutoConfig / JpaConfig are conditional on it.
            System.setProperty("zhiflow.mode", "app");
        }
        builder.run(springArgs.toArray(new String[0]));
    }

    private static void primeLogDirectory() {
        if (System.getProperty("zhiflow.log.dir") != null) return;
        Path logDir = Path.of(System.getProperty("user.dir"), ".zhiflow", "logs");
        try {
            Files.createDirectories(logDir);
        } catch (Exception ignored) {
        }
        System.setProperty("zhiflow.log.dir", logDir.toAbsolutePath().toString());
    }
}
```

**修正 typo**:`LoggerBinder.bind(new Slf4jPluginLoggerLoggerBinder())` 多了个 Logger,应为 `new Slf4jPluginLoggerBinder()`。实现时注意。

- [ ] **Step 2: 修正 typo(Slf4jPluginLoggerBinder)**

在 Step 1 的代码中,把 `new Slf4jPluginLoggerLoggerBinder()` 改为 `new Slf4jPluginLoggerBinder()`。(实现时直接写对。)

- [ ] **Step 3: 移除 DatabaseInit.init() 调用(已在 Task 8 删除,这里确认 HeadlessLauncher 不再引用)**

确认 `HeadlessLauncher.java` 不再 import 或调用 `DatabaseInit`(已在 Task 8 的重写中移除)。

- [ ] **Step 4: 编译验证**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow compile -o 2>&1 | tail -20`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/HeadlessLauncher.java
git commit -m "♻️ refactor(launcher): split SETUP/APP mode boot by datasource.properties presence"
```

---

### Task 15: 前端 setup store + API client 扩展

**Files:**
- Modify: `frontend/src/api/types.ts` — 新增 setup 类型
- Modify: `frontend/src/api/client.ts` — 新增 setup 方法
- Create: `frontend/src/stores/setup.ts`

**Interfaces:**
- Produces: `api.getSetupStatus()`、`api.getSetupTypes()`、`api.testConnection()`、`api.initializeSetup()`、`useSetupStore`

- [ ] **Step 1: 在 api/types.ts 新增 setup 类型**

在 `frontend/src/api/types.ts` 末尾追加:

```ts
// ── Setup wizard (Phase 4) ──────────────────────────────────

export interface SetupStatus {
  initialized: boolean
  supportedTypes?: string[]
  embeddedTypes?: string[]
}

export interface DbTypeField {
  name: string
  label?: string
  required: boolean
  secret?: boolean
  default?: number | string
}

export interface DbTypeMeta {
  type: string
  label: string
  embedded: boolean
  fields: DbTypeField[]
}

export interface WizardParams {
  filePath?: string
  host?: string
  port?: number
  database?: string
  username?: string
  password?: string
}

export interface ConnectionTestRequest {
  type: string
  params: WizardParams
}

export interface ConnectionTestResult {
  success: boolean
  dialect?: string
  serverVersion?: string
  error?: string
}

export interface InitializeResult {
  success: boolean
  action?: string
  error?: string
  step?: string
}
```

- [ ] **Step 2: 在 api/client.ts 新增 setup 方法**

在 `frontend/src/api/client.ts` 的 `export const api = { ... }` 对象内,在 `aiChat` 方法之后(闭合 `}` 之前)新增:

```ts
  async getSetupStatus(): Promise<import('./types').SetupStatus> {
    const { data } = await http.get<import('./types').SetupStatus>('/api/setup/status')
    return data
  },

  async getSetupTypes(): Promise<import('./types').DbTypeMeta[]> {
    const { data } = await http.get<import('./types').DbTypeMeta[]>('/api/setup/types')
    return data
  },

  async testConnection(
    req: import('./types').ConnectionTestRequest,
  ): Promise<import('./types').ConnectionTestResult> {
    const { data } = await http.post<import('./types').ConnectionTestResult>(
      '/api/setup/test-connection',
      req,
    )
    return data
  },

  async initializeSetup(
    req: import('./types').ConnectionTestRequest,
  ): Promise<import('./types').InitializeResult> {
    const { data } = await http.post<import('./types').InitializeResult>(
      '/api/setup/initialize',
      req,
    )
    return data
  },
```

并在顶部 import 区,把:
```ts
import type {
  AppSettings,
  ChatMessage,
  ChatStartResponse,
  HealthResponse,
  PartialSettings,
  PluginDescriptor,
  PluginInvokeResult,
} from './types'
```
改为(新增 setup 相关类型):
```ts
import type {
  AppSettings,
  ChatMessage,
  ChatStartResponse,
  ConnectionTestRequest,
  ConnectionTestResult,
  DbTypeMeta,
  HealthResponse,
  InitializeResult,
  PartialSettings,
  PluginDescriptor,
  PluginInvokeResult,
  SetupStatus,
} from './types'
```
然后把上面新增方法里的 `import('./types').X` 简化为直接用类型名(因为已 import)。最终新增方法形如:
```ts
  async getSetupStatus(): Promise<SetupStatus> {
    const { data } = await http.get<SetupStatus>('/api/setup/status')
    return data
  },
```
(其余三个同理。)

- [ ] **Step 3: 创建 setup store**

创建 `frontend/src/stores/setup.ts`:

```ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type {
  ConnectionTestResult,
  DbTypeMeta,
  SetupStatus,
  WizardParams,
} from '@/api/types'

export const useSetupStore = defineStore('setup', () => {
  const status = ref<SetupStatus | null>(null)
  const types = ref<DbTypeMeta[]>([])
  const selectedType = ref<string>('')
  const params = ref<WizardParams>({})
  const testResult = ref<ConnectionTestResult | null>(null)
  const testing = ref(false)
  const initializing = ref(false)
  const error = ref('')

  async function loadStatus() {
    status.value = await api.getSetupStatus()
    return status.value
  }

  async function loadTypes() {
    types.value = await api.getSetupTypes()
  }

  function selectType(t: string) {
    selectedType.value = t
    // Reset params to defaults for the selected type
    const meta = types.value.find((x) => x.type === t)
    params.value = {}
    if (meta) {
      for (const f of meta.fields) {
        if (f.default !== undefined) {
          ;(params.value as Record<string, unknown>)[f.name] = f.default
        }
      }
    }
    testResult.value = null
  }

  async function testConnection() {
    if (!selectedType.value) return
    testing.value = true
    testResult.value = null
    error.value = ''
    try {
      testResult.value = await api.testConnection({
        type: selectedType.value,
        params: params.value,
      })
    } catch (e) {
      error.value = String(e)
      testResult.value = { success: false, error: String(e) }
    } finally {
      testing.value = false
    }
  }

  async function initialize(): Promise<boolean> {
    if (!selectedType.value) return false
    initializing.value = true
    error.value = ''
    try {
      const res = await api.initializeSetup({
        type: selectedType.value,
        params: params.value,
      })
      if (!res.success) {
        error.value = res.error ?? 'Initialization failed'
        return false
      }
      return true
    } catch (e) {
      error.value = String(e)
      return false
    } finally {
      initializing.value = false
    }
  }

  return {
    status,
    types,
    selectedType,
    params,
    testResult,
    testing,
    initializing,
    error,
    loadStatus,
    loadTypes,
    selectType,
    testConnection,
    initialize,
  }
})
```

- [ ] **Step 4: 验证前端类型检查**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ/frontend && npm run build 2>&1 | tail -20`
Expected: 构建成功(SetupWizard.vue 还没创建,但 store/api 已就绪,类型检查应通过——因为 store 是独立模块,未引用处不影响编译)。**如果有 vue-tsc 错误关于未使用变量,可忽略;若有类型错误则修复。**

- [ ] **Step 5: 提交**

```bash
git add frontend/src/api/types.ts frontend/src/api/client.ts frontend/src/stores/setup.ts
git commit -m "✨ feat(web): add setup API client methods + setup store"
```

---

### Task 16: 前端 SetupWizard.vue 向导页

**Files:**
- Create: `frontend/src/views/SetupWizard.vue`

**Interfaces:**
- Produces: 三步式向导组件(选型 → 配置测试 → 确认初始化)

- [ ] **Step 1: 创建 SetupWizard.vue**

创建 `frontend/src/views/SetupWizard.vue`:

```vue
<script setup lang="ts">
import { onMounted, computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSetupStore } from '@/stores/setup'
import { api } from '@/api/client'

const router = useRouter()
const setup = useSetupStore()

const step = ref<1 | 2 | 3>(1)
const restartMessage = ref('')
const restartFailed = ref(false)

const selectedMeta = computed(
  () => setup.types.find((t) => t.type === setup.selectedType) ?? null,
)
const canProceedToConfig = computed(() => !!setup.selectedType)
const canInitialize = computed(() => setup.testResult?.success === true)

onMounted(async () => {
  await setup.loadTypes()
  // Default-select H2 (first embedded / recommended)
  const h2 = setup.types.find((t) => t.type === 'h2')
  if (h2) setup.selectType('h2')
})

function chooseType(t: string) {
  setup.selectType(t)
  step.value = 2
}

function backToSelect() {
  step.value = 1
}

async function onTest() {
  await setup.testConnection()
}

async function onInitialize() {
  const ok = await setup.initialize()
  if (!ok) return
  // Show restart overlay, poll health until backend is back.
  step.value = 3
  restartMessage.value = 'Configuration complete. Restarting backend…'
  await waitForRestart()
}

async function waitForRestart() {
  const deadline = Date.now() + 30_000
  let back = false
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 500))
    try {
      await api.health()
      // Health passed — confirm setup status is now initialized.
      const status = await api.getSetupStatus()
      if (status.initialized) {
        back = true
        break
      }
    } catch {
      // Backend still down — keep polling.
    }
  }
  if (back) {
    router.replace('/')
  } else {
    restartFailed.value = true
    restartMessage.value = 'Restart timed out. Please manually restart the application.'
  }
}
</script>

<template>
  <div class="setup-root">
    <div class="setup-card">
      <h1 class="setup-title">ZhiFlow Setup</h1>
      <p class="setup-subtitle">Choose how to store your data.</p>

      <!-- Step 1: choose type -->
      <div v-if="step === 1" class="step">
        <div class="type-grid">
          <button
            v-for="t in setup.types"
            :key="t.type"
            class="type-card"
            :class="{ active: setup.selectedType === t.type }"
            @click="chooseType(t.type)"
          >
            <span class="type-label">{{ t.label }}</span>
            <span class="type-tag">{{ t.embedded ? 'local' : 'remote' }}</span>
          </button>
        </div>
      </div>

      <!-- Step 2: configure + test -->
      <div v-else-if="step === 2" class="step">
        <button class="link-btn" @click="backToSelect">← Back</button>
        <h2 class="step-title">{{ selectedMeta?.label }} configuration</h2>

        <div v-for="f in selectedMeta?.fields ?? []" :key="f.name" class="form-row">
          <label class="form-label">{{ f.label ?? f.name }}</label>
          <input
            v-if="!f.secret"
            class="sk-input"
            v-model="(setup.params as Record<string, unknown>)[f.name] as string"
            :placeholder="f.name"
          />
          <input
            v-else
            type="password"
            class="sk-input"
            v-model="(setup.params as Record<string, unknown>)[f.name] as string"
            :placeholder="f.name"
          />
        </div>

        <div class="test-row">
          <button class="sk-btn" :disabled="setup.testing" @click="onTest">
            {{ setup.testing ? 'Testing…' : 'Test connection' }}
          </button>
          <span
            v-if="setup.testResult"
            class="test-result"
            :class="setup.testResult.success ? 'ok' : 'fail'"
          >
            {{ setup.testResult.success
              ? `✓ Connected (${setup.testResult.serverVersion})`
              : `✗ ${setup.testResult.error}` }}
          </span>
        </div>

        <button class="sk-btn primary" :disabled="!canInitialize" @click="onInitialize">
          Initialize
        </button>
      </div>

      <!-- Step 3: restart overlay -->
      <div v-else class="step restart-step">
        <div class="spinner" />
        <p>{{ restartMessage }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.setup-root {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  width: 100%;
  background: var(--sk-bg);
  padding: 24px;
}
.setup-card {
  max-width: 560px;
  width: 100%;
  background: var(--sk-surface, var(--sk-bg));
  border: 1px solid var(--sk-border);
  border-radius: 12px;
  padding: 32px;
}
.setup-title {
  margin: 0 0 4px;
  font-size: 24px;
}
.setup-subtitle {
  margin: 0 0 24px;
  color: var(--sk-text-dim, #888);
  font-size: 14px;
}
.type-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.type-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 18px;
  border: 1px solid var(--sk-border);
  border-radius: 8px;
  background: transparent;
  color: var(--sk-text);
  cursor: pointer;
  transition: border-color 0.15s;
}
.type-card:hover {
  border-color: var(--sk-accent, #4a9);
}
.type-card.active {
  border-color: var(--sk-accent, #4a9);
  background: var(--sk-surface-alt, rgba(68, 170, 153, 0.08));
}
.type-label {
  font-size: 15px;
  font-weight: 600;
}
.type-tag {
  font-size: 11px;
  text-transform: uppercase;
  color: var(--sk-text-dim, #888);
}
.step-title {
  margin: 12px 0 16px;
  font-size: 17px;
}
.form-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 14px;
}
.form-label {
  font-size: 13px;
  color: var(--sk-text-dim, #aaa);
}
.sk-input {
  padding: 8px 10px;
  border: 1px solid var(--sk-border);
  border-radius: 6px;
  background: var(--sk-bg);
  color: var(--sk-text);
  font-size: 14px;
}
.test-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 0 20px;
}
.test-result.ok {
  color: #4a9;
  font-size: 13px;
}
.test-result.fail {
  color: #e55;
  font-size: 13px;
}
.sk-btn {
  padding: 8px 16px;
  border: 1px solid var(--sk-border);
  border-radius: 6px;
  background: transparent;
  color: var(--sk-text);
  cursor: pointer;
  font-size: 14px;
}
.sk-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.sk-btn.primary {
  background: var(--sk-accent, #4a9);
  color: #fff;
  border-color: var(--sk-accent, #4a9);
}
.link-btn {
  background: none;
  border: none;
  color: var(--sk-text-dim, #888);
  cursor: pointer;
  font-size: 13px;
  padding: 0;
  margin-bottom: 8px;
}
.restart-step {
  text-align: center;
  padding: 40px 0;
}
.spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--sk-border);
  border-top-color: var(--sk-accent, #4a9);
  border-radius: 50%;
  margin: 0 auto 16px;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/views/SetupWizard.vue
git commit -m "✨ feat(web): add SetupWizard.vue three-step setup wizard"
```

---

### Task 17: 前端路由分流 + App.vue 向导渲染

**Files:**
- Modify: `frontend/src/router/index.ts` — 加全局守卫 + /setup 路由
- Modify: `frontend/src/App.vue` — 向导页不套 AppShell
- Modify: `frontend/src/main.ts` — 启动先查 setup status

**Interfaces:**
- Produces: 启动期根据 `/api/setup/status` 决定渲染向导或主 shell

- [ ] **Step 1: 修改 router/index.ts 加 /setup 路由 + 全局守卫**

替换 `frontend/src/router/index.ts` 全文:

```ts
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { api } from '@/api/client'

const routes: RouteRecordRaw[] = [
  { path: '/setup', name: 'setup', component: () => import('@/views/SetupWizard.vue') },
  { path: '/', name: 'tools', component: () => import('@/views/ToolGrid.vue') },
  { path: '/ai', name: 'ai', component: () => import('@/views/AiChat.vue') },
  { path: '/settings', name: 'settings', component: () => import('@/views/Settings.vue') },
  {
    path: '/plugin/:id',
    name: 'plugin',
    component: () => import('@/views/PluginView.vue'),
    props: true,
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

// Global guard: redirect to /setup when the backend reports uninitialized.
// The setup route itself is always allowed; initialized backends bounce /setup back to /.
router.beforeEach(async (to) => {
  if (to.name === 'setup') return true
  try {
    const status = await api.getSetupStatus()
    if (!status.initialized) {
      return { name: 'setup' }
    }
  } catch {
    // Backend unreachable — allow navigation; StatusBar surfaces connectivity.
  }
  return true
})
```

- [ ] **Step 2: 修改 App.vue 向导页不套 AppShell**

替换 `frontend/src/App.vue` 全文:

```vue
<script setup lang="ts">
import { watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import { useThemeStore } from './stores/theme'
import AppShell from './shell/AppShell.vue'

const theme = useThemeStore()
const route = useRoute()

// Keep the <html> theme class in sync with the store.
watchEffect(() => {
  const root = document.documentElement
  root.classList.remove('theme-dark', 'theme-light')
  root.classList.add(theme.theme === 'light' ? 'theme-light' : 'theme-dark')
})
</script>

<template>
  <!-- Setup wizard renders full-screen without the app shell -->
  <router-view v-if="route.name === 'setup'" />
  <AppShell v-else />
</template>
```

- [ ] **Step 3: 修改 main.ts 启动先查 setup status(可选,守卫已覆盖;此处保持轻量)**

`main.ts` 现有的 `useSettingsStore().load()` 在未初始化时会失败(后端在 SETUP 模式无 /api/settings)。改为容错。编辑 `frontend/src/main.ts`,把:

```ts
useSettingsStore()
  .load()
  .catch(() => {
    /* StatusBar surfaces connectivity; keep defaults */
  })
```

保持不变(catch 已容错)。无需改动。

- [ ] **Step 4: 验证前端构建**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ/frontend && npm run build 2>&1 | tail -20`
Expected: 构建成功

- [ ] **Step 5: 提交**

```bash
git add frontend/src/router/index.ts frontend/src/App.vue
git commit -m "✨ feat(web): add setup-status router guard + bare render for wizard route"
```

---

### Task 18: Tauri sidecar 重启逻辑

**Files:**
- Modify: `desktop/src-tauri/src/main.rs`

**Interfaces:**
- Produces: sidecar 退出码 0(SETUP_DONE)→ 重新拉起 sidecar;其他 → 报错

**关键**:把 `spawn_backend` 的结果从一次性变为可重启。当 sidecar 退出码为 0 时,重新 spawn 一次(此时 datasource.properties 已写入,会进入 APP 模式)。

- [ ] **Step 1: 重构 main.rs 支持重启**

在 `desktop/src-tauri/src/main.rs` 中,把 `main()` 函数(`main.rs:108-165`)的 sidecar spawn + wait_for_health 逻辑包进一个循环:首次启动可能是 SETUP 模式,完成后 sidecar 以退出码 0 退出,此时重新 spawn。

替换 `main()` 函数(`main.rs:108-165`)全文:

```rust
fn main() {
    let token = gen_token();

    // The backend may start in SETUP mode (first launch, no datasource.properties).
    // When the setup wizard completes, it exits with code SETUP_DONE (0) to signal
    // us to restart it into APP mode. We loop: spawn → wait for health → if it exits
    // with 0 and we haven't entered APP mode yet, respawn.
    let mut entered_app_mode = false;
    let (mut child, port) = loop {
        let (c, p) = match spawn_backend(&token) {
            Ok(v) => v,
            Err(e) => {
                eprintln!("FATAL: {e}");
                std::process::exit(1);
            }
        };
        if let Err(e) = wait_for_health(p, &token) {
            eprintln!("FATAL: {e}");
            let mut cc = c;
            let _ = cc.kill();
            std::process::exit(1);
        }
        // If we already entered APP mode on a previous iteration and the backend exited,
        // that's an unexpected crash — don't loop forever.
        if entered_app_mode {
            eprintln!("FATAL: backend exited unexpectedly after entering APP mode");
            std::process::exit(1);
        }
        // Check whether the backend is in setup mode by probing /api/setup/status.
        match check_setup_mode(p, &token) {
            Ok(true) => {
                // SETUP mode: wait for the sidecar to exit (it will exit 0 when done),
                // then loop to respawn into APP mode.
                println!("[desktop] backend in SETUP mode; waiting for wizard to complete…");
                let mut waiter = c;
                let status = waiter.wait().expect("failed to wait for setup sidecar");
                if status.code() == Some(0) {
                    println!("[desktop] setup complete; restarting backend into APP mode");
                    entered_app_mode = true;
                    continue;   // respawn
                } else {
                    eprintln!("FATAL: setup sidecar exited with code {:?}", status.code());
                    std::process::exit(1);
                }
            }
            Ok(false) => break (c, p),   // APP mode — proceed to window
            Err(e) => {
                // Probe failed — assume APP mode and proceed (best effort).
                eprintln!("[desktop] could not determine setup mode ({}); assuming APP", e);
                break (c, p);
            }
        }
    };

    let init_script = format!(
        "window.__ZHIFLOW_TOKEN__ = '{token}'; window.__ZHIFLOW_PORT__ = {port}; \
         window.__ZHIFLOW_API_BASE__ = 'http://127.0.0.1:{port}';"
    );

    tauri::Builder::default()
        .manage(Sidecar(Mutex::new(Some(child))))
        .setup(move |app| {
            tauri::WebviewWindowBuilder::new(
                app,
                "main",
                tauri::WebviewUrl::default(),
            )
            .title("ZhiFlow")
            .inner_size(1280.0, 820.0)
            .min_inner_size(960.0, 640.0)
            .resizable(true)
            .initialization_script(&init_script)
            .build()?;
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::Destroyed = event {
                if let Some(state) = window.try_state::<Sidecar>() {
                    kill_sidecar(&state);
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running ZhiFlow desktop");
}

/// Probes GET /api/setup/status. Returns Ok(true) if in SETUP mode (not initialized).
fn check_setup_mode(port: u16, token: &str) -> Result<bool, String> {
    let url = format!("http://127.0.0.1:{port}/api/setup/status");
    let resp = ureq::get(&url)
        .set("X-ZhiFlow-Token", token)
        .timeout(Duration::from_secs(2))
        .call()
        .map_err(|e| format!("setup status request failed: {e}"))?;
    let body = resp.into_string().map_err(|e| format!("read body: {e}"))?;
    // Crude parse: SETUP mode if "initialized":false appears.
    Ok(body.contains("\"initialized\":false") || body.contains("\"initialized\": false"))
}
```

- [ ] **Step 2: 验证 Rust 编译**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ/desktop/src-tauri && cargo check 2>&1 | tail -20`
Expected: 编译通过。若 `check_setup_mode` 函数有借用/类型错误,修复。

- [ ] **Step 3: 提交**

```bash
git add desktop/src-tauri/src/main.rs
git commit -m "✨ feat(desktop): restart sidecar after SETUP_DONE to enter APP mode"
```

---

### Task 19: 端到端验证 + 文档更新

**Files:**
- Modify: `CHANGELOG.md` — 记录 Phase 4 向导 + JPA 迁移
- Modify: `README.md` — 更新数据库配置说明
- Modify: `docs/architecture.md`(若存在) — 更新启动时序

**Interfaces:** 无(文档任务)

- [ ] **Step 1: 全量后端测试**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow test -o 2>&1 | tail -30`
Expected: 所有单元测试通过。集成测试(`HeadlessIntegrationTest`)需要 APP 模式上下文——若因缺少 datasource.properties 失败,**在测试 resources 下加一个 `application-test.yml` 或用 `@TestPropertySource` 提供测试用 H2 配置**。

若集成测试失败,在 `HeadlessIntegrationTest` 类上加测试配置:

```java
@SpringBootTest(classes = AiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "zhiflow.mode=app",
        "spring.datasource.url=jdbc:h2:mem:zhiflowtest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
    })
```

- [ ] **Step 2: 前端构建验证**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ/frontend && npm run build 2>&1 | tail -20`
Expected: 构建成功,vue-tsc 无类型错误

- [ ] **Step 3: 手动端到端冒烟测试(SETUP 流程)**

```bash
# 1. 清除现有 datasource 配置(模拟首次启动)
rm -f .zhiflow/config/datasource.properties

# 2. 构建后端 JAR
cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow package -DskipTests -o
cp ZhiFlow/target/ZhiFlow-*.jar desktop/src-tauri/binaries/ZhiFlow.jar

# 3. 前端构建
cd frontend && npm run build && cp -r dist ../desktop/

# 4. 启动桌面应用,观察:
cd ../desktop/src-tauri && cargo run
```

预期行为:
- 后端以 SETUP 模式启动,前端显示向导
- 选择 H2,默认路径,点 Test connection → 成功
- 点 Initialize → 后端建表、写配置、退出
- Tauri 重启 sidecar → 进入 APP 模式 → 前端显示主 shell

若行,记录成功;若失败,记录失败步骤与错误,反馈给开发者修复。

- [ ] **Step 4: 更新 CHANGELOG.md**

在 `CHANGELOG.md` 顶部(最新版本区)新增 Phase 4 条目:

```markdown
## [4.0.0-Phase4] - 2026-07-08

### ✨ Added
- **Multi-datasource setup wizard**: first launch now guides users through database
  selection (H2 / SQLite / MySQL / PostgreSQL) with connection testing and automatic
  schema initialization.
- **JPA migration**: database layer migrated from MyBatis to Spring Data JPA + Hibernate
  (ddl-auto=update). All 12 entities ported with `@Entity` annotations.
- **User system groundwork**: `sys_user` / `sys_session` tables, `user_id` row-level
  isolation on all user-scoped tables, `AuthProvider`/`SecurityContext` interfaces with
  Noop implementation (login UI deferred to a later phase).
- `application.yml` for static configuration (JPA, actuator, server.address).
- AES-GCM encryption for datasource password fields.

### ♻️ Changed
- `HeadlessLauncher` now boots in SETUP mode (minimal context) when
  `datasource.properties` is absent, APP mode (full context) otherwise.
- `AiConfigService` / `AiConfigServiceHeadless` / `EmailUtil` converted from static
  utilities to Spring `@Component` beans with injected repositories.
- Setup wizard endpoints (`/api/setup/*`) bypass token auth.

### 🗑️ Removed
- `DatabaseInit`, all MyBatis mappers (12), `mybatis-config.xml`, all mapper XML (12).
- MyBatis dependency.
```

- [ ] **Step 5: 更新 README.md 数据库章节**

在 `README.md` 找到数据库相关章节(若有),新增/更新:

```markdown
## Database

On first launch, ZhiFlow runs a setup wizard that lets you choose a database:

- **H2** (default, local embedded) — zero configuration, data stored under `.zhiflow/data/`.
- **SQLite** (local embedded) — single-file database.
- **MySQL** (remote) — for multi-user or server deployment.
- **PostgreSQL** (remote) — for multi-user or server deployment.

The wizard tests the connection, creates the schema automatically (Hibernate ddl-auto),
and persists the configuration to `~/.zhiflow/config/datasource.properties` (passwords
AES-encrypted). Subsequent launches skip the wizard.

To reconfigure, delete `datasource.properties` and restart — the wizard will appear again.
```

- [ ] **Step 6: 提交文档**

```bash
git add CHANGELOG.md README.md
git commit -m "📝 docs(4.0.0): document multi-datasource setup wizard + JPA migration (Phase 4)"
```

---

## Self-Review

(执行计划写完后,对照 spec 自检:)

**1. Spec 覆盖:**
- ✅ 启动时序方案 A — Task 14(HeadlessLauncher 分流)+ Task 18(Tauri 重启)
- ✅ JPA 迁移 — Task 1(依赖)、Task 4(实体)、Task 6(Repository)、Task 8(调用点 + 删除)
- ✅ 配置存储 — Task 11(CryptoUtil)、Task 12(DataSourceConfigService)
- ✅ DataSource bean — Task 10(DataSourceAutoConfig)
- ✅ application.yml — Task 2
- ✅ 用户体系预留 — Task 5(sys 实体)、Task 7(安全抽象)、Task 9(虚拟用户)
- ✅ user_id 行级隔离 — Task 4(实体加字段)、Task 6(Repository 查询带 userId)
- ✅ Setup API — Task 13(SetupController + SetupApplication)
- ✅ 前端向导 — Task 15(store/api)、Task 16(SetupWizard.vue)、Task 17(路由)
- ✅ TokenAuthFilter 放行 — Task 13 Step 4

**2. Placeholder 扫描:** 已检查,无 TBD/TODO。Task 8 Step 5 的 EmailUtil 静态调用点处理给出了降级方案(若调用点多),非 placeholder。

**3. 类型一致性:**
- `DbType` 字段名(driver/dialect/urlTemplate/embedded)在 Task 3/12/13 一致 ✅
- `DataSourceConfig` record 字段(type/url/driver/dialect/username/password/filePath)在 Task 3/10/12 一致 ✅
- `SecurityContext.currentUserId()` 返回 `Long`,实体 `userId` 字段为 `Long` ✅
- `SecurityConstants.LOCAL_VIRTUAL_USER_ID = 1L`(Long)✅
- Repository 方法名(`findByUserIdAndSettingKey` 等)在 Task 6 定义,Task 8 调用一致 ✅

**已知风险点(实现时注意):**
- Spring Boot 4.1 的 JPA 自动配置类名(`HibernateJpaAutoConfiguration`)需验证——Task 13 Step 2 的 exclude 可能需要调整类名。
- `EmailUtil` 静态调用点数量未知——Task 8 Step 5 给了降级方案。
- Hibernate 7 的 dialect 包名(`org.hibernate.dialect.*`)在 DbType 枚举里硬编码——若 SB4.1 用了不同包,Task 3 的 dialect 字符串需修正。

---

## 执行交接

计划已完成并保存到 `docs/superpowers/plans/2026-07-08-multids-setup-wizard.md`。两种执行选项:

**1. Subagent-Driven(推荐)** — 每个 Task 派发独立 subagent,任务间 review,迭代快

**2. Inline Execution** — 在当前会话用 executing-plans 批量执行,带 checkpoint review

选哪种?
