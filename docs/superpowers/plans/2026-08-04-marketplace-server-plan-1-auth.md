# FengYu 插件市场服务 — 计划 1：认证中心（支柱 1）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已落地的 `fengyu-marketplace-server` 脚手架（计划 0，tag `scaffold-v0.1`）上实现**认证中心**：真实用户/角色存储、JWT（access + refresh 轮换）、Spring Security 7 完整过滤链 + 授权矩阵、`/api/auth/*` 端点（注册/登录/刷新/登出/账户/设备）、首管理员引导、登录限流，并**修复计划 0 最终评审遗留的 §3.2 安全响应契约缺口**（让 Spring Security 的 401/403 也输出 `ApiError` JSON）。

**Architecture:** 计划 0 的脚手架 `SecurityConfig`（STATELESS + httpBasic 占位）被本计划替换为完整 JWT 链。新增 `auth/` 包：`entity`（UserEntity + UserRole 多对多 + RefreshTokenEntity）、`repository`、`service`（AuthService/JwtService/RefreshTokenService/RateLimitService）、`security`（JwtAuthenticationFilter/SecurityConfig 替换/MarketUserDetails/ApiErrorAuthenticationEntryPoint/ApiErrorAccessDeniedHandler）、`controller`（AuthController/AccountController/AdminUserController）、`bootstrap`（首管理员引导）。复用计划 0 的 `common/error/*`（ErrorCode/ApiException/ApiError/GlobalExceptionHandler）与 `ObjectMapper`。

**Tech Stack:** Spring Boot 4.1.0、Spring Security 7.1.0（已确认 classpath）、Jackson 3（`tools.jackson`）、JJWT（**本计划新增依赖**，见任务 1）、JPA/Hibernate、Flyway（**本计划首次写迁移**）、Java 21、BCrypt（Spring Security 自带 `BCryptPasswordEncoder`）、JUnit 5 + Spring Boot Test + `RestTestClient`（Boot 4.1 已无 `TestRestTemplate`）。

## 全局约束（所有任务隐含遵守）

- **仓库**：在 `fengyu-marketplace-server`（计划 0 已建）的 `main` 分支上继续，**不**新建分支（脚手架期沿用 main；正式分支策略由 release 流程决定）。
- **JDK 21**：所有 `./mvnw` 必须带 `JAVA_HOME=/Users/phoebej/Library/Java/JavaVirtualMachines/azul-21.0.12/Contents/Home`（系统默认是 25）。
- **Jackson 3**：凡用 ObjectMapper 走 `tools.jackson.databind.*`；**`@JsonInclude` 等注解仍用 `com.fasterxml.jackson.annotation.*`**（Jackson 3 databind 内部用 2.21 annotations，这是正确的，见计划 0 评审结论）。
- **Spring Security 7**：多 `SecurityFilterChain` bean 时必须用 `securityMatcher` 分域 + `@Order`（`UnreachableFilterChainException` 拒绝双 `anyRequest()`，见计划 0 `TestSecurityConfig`）。方法级授权 `@EnableMethodSecurity` + `@PreAuthorize`。
- **测试 HTTP 客户端**：用 `RestTestClient.bindToServer()`（spring-test，真实 socket），**不**用 `TestRestTemplate`（Boot 4.1 已移除）。测试态安全配置用 `securityMatcher` + `@Order` 模式。
- **TDD**：每个功能点 RED→GREEN。
- **提交规范**：conventional commits + emoji。每任务结束提交。
- **复用计划 0 契约**：`ErrorCode`（含 `UNAUTHORIZED/TOKEN_EXPIRED/FORBIDDEN/INVALID_CREDENTIALS/ACCOUNT_LOCKED/USERNAME_TAKEN/EMAIL_TAKEN` 等已定义）、`ApiException`、`ApiError.of(code,message,retryAfterSeconds)`、`PathSafety`、全局 `ObjectMapper` bean。
- **不删/不改本任务之外的文件**；匹配既有风格。

## 文件结构（本计划产出）

```
fengyu-marketplace-server/
├── pom.xml                                       # Task 1（加 jjwt 依赖）
├── src/main/java/fan/summer/marketplace/
│   ├── auth/
│   │   ├── entity/
│   │   │   ├── UserEntity.java                   # Task 2
│   │   │   ├── UserRoleEntity.java               # Task 2（多对多角色）
│   │   │   └── RefreshTokenEntity.java           # Task 4
│   │   ├── repository/
│   │   │   ├── UserRepository.java               # Task 2
│   │   │   └── RefreshTokenRepository.java       # Task 4
│   │   ├── security/
│   │   │   ├── MarketUserDetails.java            # Task 6
│   │   │   ├── JwtAuthenticationFilter.java      # Task 6
│   │   │   ├── ApiErrorAuthenticationEntryPoint.java  # Task 7（§3.2 缺口）
│   │   │   ├── ApiErrorAccessDeniedHandler.java       # Task 7（§3.2 缺口）
│   │   │   └── MarketSecurityConfig.java         # Task 8（替换脚手架 SecurityConfig）
│   │   ├── service/
│   │   │   ├── JwkLessJwtService.java            # Task 3（JJWT 签发/校验）
│   │   │   ├── AuthService.java                  # Task 5
│   │   │   ├── RefreshTokenService.java          # Task 4
│   │   │   └── RateLimitService.java             # Task 10
│   │   ├── controller/
│   │   │   ├── AuthController.java               # Task 9（register/login/refresh/logout/logout-all）
│   │   │   ├── AccountController.java            # Task 9（me GET/PATCH）
│   │   │   └── DeviceController.java             # Task 9（devices 列表 + 撤销）
│   │   ├── bootstrap/
│   │   │   └── BootstrapAdminRunner.java         # Task 11（首管理员引导）
│   │   └── dto/                                  # Task 9（请求/响应 DTO）
│   │       ├── LoginRequest.java, RegisterRequest.java, AuthTokens.java,
│   │       │   AccountView.java, DeviceView.java, ChangePasswordRequest.java
│   └── config/SecurityConfig.java                # Task 8（脚手架版被 MarketSecurityConfig 取代 → 本任务删除/改为空）
├── src/main/resources/
│   ├── application.yml                           # Task 1（追加 market.auth.* 段）
│   └── db/migration/
│       └── V1__init_auth.sql                     # Task 2（Flyway 首个迁移）
└── src/test/java/fan/summer/marketplace/auth/...  # 各任务测试
```

**设计原则**：`auth/` 内部分层清晰、单向依赖（entity ← repository ← service ← controller；security 横切）。复用 `common/error` 的 `ApiException` 表达业务错误（如 `USERNAME_TAKEN`），复用 `ApiError` 作为 HTTP 响应体。

---

### Task 1: 加 JJWT 依赖 + application.yml 认证配置段

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Produces: `pom.xml` 新增 `io.jsonwebtoken:jjwt-api/impl/jackson`(0.12.x) 三件套；`application.yml` 新增 `market.auth.jwt-secret/access-ttl-seconds/refresh-ttl-seconds/registration.enabled` 与 `market.bootstrap.admin-token` 配置键（均带默认值）。后续任务读这些配置。

- [ ] **Step 1: 在 `pom.xml` `<dependencies>` 末尾（测试依赖之前）加 JJWT 三件套**

JJWT 0.12.x 拆成 api（编译期）/ impl（运行期）/ jackson（JSON 解析，用 Jackson 做 claim 序列化——注意：JJWT 自带 jackson runtime，与我们的 `tools.jackson` 无关，它在 `io.jsonwebtoken.impl.jackson` 包内，不冲突）。

```xml
<!-- JWT（认证中心用，计划 1） -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 2: 在 `application.yml` 追加 `market.auth` + `market.bootstrap` 段**

在现有 `market:` 段（若计划 0 未建顶层 `market:`，则在文件末尾新增）下追加：

```yaml
market:
  auth:
    # HS256 密钥；为空时启动生成随机密钥并 WARN（仅 dev；生产必须显式配置，否则重启后所有 token 失效）
    jwt-secret: ${MARK_JWT_SECRET:}
    access-ttl-seconds: 900        # 15 分钟
    refresh-ttl-seconds: 2592000   # 30 天
    registration:
      enabled: true
    password:
      min-length: 8
      require-letter-and-digit: true
  bootstrap:
    # 首管理员引导令牌：仅 users 表为空时有效，用后失效。生产必改。
    admin-token: ${MARK_BOOTSTRAP_ADMIN_TOKEN:}
```

- [ ] **Step 3: 验证依赖可解析、应用仍能起、20/20 测试不回归**

Run（带 JDK 21 `JAVA_HOME`）：
```bash
cd /Users/phoebej/Develop/Java/fengyu-marketplace-server
JAVA_HOME=/Users/phoebej/Library/Java/JavaVirtualMachines/azul-21.0.12/Contents/Home ./mvnw -q test
```
Expected: 20/20 PASS（计划 0 的测试），BUILD SUCCESS。

- [ ] **Step 4: 提交**

```bash
git add pom.xml src/main/resources/application.yml
git commit -m "⬆️ deps(auth): add jjwt 0.12.6 + market.auth/bootstrap config keys"
```

---

### Task 2: UserEntity + UserRoleEntity + UserRepository + Flyway V1 迁移

**Files:**
- Create: `src/main/java/fan/summer/marketplace/auth/entity/UserEntity.java`
- Create: `src/main/java/fan/summer/marketplace/auth/entity/UserRoleEntity.java`
- Create: `src/main/java/fan/summer/marketplace/auth/repository/UserRepository.java`
- Create: `src/main/resources/db/migration/V1__init_auth.sql`
- Test: `src/test/java/fan/summer/marketplace/auth/repository/UserRepositoryTest.java`

**Interfaces:**
- Consumes: 计划 0 的 JPA + H2（测试）配置、Flyway（`baseline-on-migrate: true`）。
- Produces:
  - `UserEntity`（表 `users`：id、username unique、email unique、passwordHash、displayName、avatarUrl、status enum、createdAt、updatedAt；与 `user_roles` 多对多）。方法：`getRoles()` 返回 `Set<Role>`。
  - `UserRoleEntity`（表 `user_roles`：userId + role 枚举 `USER`/`AUTHOR`/`ADMIN`，复合主键）。
  - 枚举 `Role`（`USER`/`AUTHOR`/`ADMIN`，放 `auth/entity/Role.java` 或 `UserEntity` 内嵌）。
  - `UserRepository`：`findByUsername(String)`、`findByEmail(String)`、`existsByUsernameOrEmail(...)`。
  - Flyway `V1__init_auth.sql` 建表（与实体一致；`status` 用字符串列存枚举名）。

- [ ] **Step 1: 写 Flyway 迁移 `V1__init_auth.sql`**

```sql
-- V1__init_auth.sql — 认证中心初始 schema（用户 + 多对多角色）
CREATE TABLE users (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    username     VARCHAR(64)  NOT NULL UNIQUE,
    email        VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(100),
    avatar_url   VARCHAR(500),
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | DISABLED
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role    VARCHAR(20) NOT NULL,   -- USER | AUTHOR | ADMIN
    PRIMARY KEY (user_id, role),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX idx_user_roles_user ON user_roles(user_id);

-- refresh_tokens 表（Task 4 会用到；提前在 V1 建好避免多迁移文件）
CREATE TABLE refresh_tokens (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id      BIGINT NOT NULL,
    token_hash   CHAR(64) NOT NULL UNIQUE,   -- SHA-256 hex of the refresh token
    device_label VARCHAR(120),
    expires_at   TIMESTAMP NOT NULL,
    revoked_at   TIMESTAMP,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens(token_hash);
```

> 注意：用 `GENERATED BY DEFAULT AS IDENTITY` 兼容 H2（`MODE=PostgreSQL`）与 Postgres。`BIGINT` 主键。删除计划 0 的 `db/migration/.gitkeep`（被真实迁移取代，但保留无害；本步可不动它）。

- [ ] **Step 2: 写 `Role` 枚举**

`src/main/java/fan/summer/marketplace/auth/entity/Role.java`：
```java
package fan.summer.marketplace.auth.entity;

/** 用户角色（可叠加：作者也是 USER；管理员可同时是作者）。 */
public enum Role {
    USER, AUTHOR, ADMIN;

    /** Spring Security 的 authority 名（带 ROLE_ 前缀，便于 hasRole('ADMIN')）。 */
    public String authority() {
        return "ROLE_" + name();
    }
}
```

- [ ] **Step 3: 写 `UserEntity`**

```java
package fan.summer.marketplace.auth.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** 市场用户。角色走 user_roles 多对多（一人多角）。 */
@Entity
@Table(name = "users")
public class UserEntity {

    public enum Status { ACTIVE, DISABLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** 多对多角色，用中间表 user_roles。 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = new LinkedHashSet<>();

    // —— getters/setters（JPA 需要无参构造 + 可变字段；Lombok 可选，本计划手写以避免引入 Lombok）——
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = roles; }

    protected UserEntity() {}  // JPA
    public UserEntity(String username, String email, String passwordHash) {
        this.username = username; this.email = email; this.passwordHash = passwordHash;
    }
}
```

> 说明：用 `@ElementCollection` + `@Enumerated(STRING)` 表达角色集合（中间表 `user_roles` 由 `@CollectionTable` 映射），不需要独立的 `UserRoleEntity`（`@ElementCollection` 比 `@ManyToMany` + 角色实体更轻，角色就是枚举值）。因此**删除文件结构里的 `UserRoleEntity.java`**——本任务不创建它。

- [ ] **Step 4: 写 `UserRepository`**

```java
package fan.summer.marketplace.auth.repository;

import fan.summer.marketplace.auth.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByUsername(String username);
    Optional<UserEntity> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
}
```

- [ ] **Step 5: 写失败测试 `UserRepositoryTest`**

```java
package fan.summer.marketplace.auth.repository;

import fan.summer.marketplace.auth.entity.Role;
import fan.summer.marketplace.auth.entity.UserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserRepositoryTest {

    @Autowired UserRepository repo;

    @Test
    void savesAndFindsByUsernameAndEmail() {
        UserEntity u = new UserEntity("alice", "alice@example.com", "$2a$10$hash");
        u.setRoles(Set.of(Role.USER, Role.AUTHOR));
        repo.save(u);

        assertThat(repo.findByUsername("alice")).isPresent();
        assertThat(repo.findByEmail("alice@example.com")).isPresent();
        assertThat(repo.existsByUsername("alice")).isTrue();
        assertThat(repo.existsByEmail("bob@example.com")).isFalse();
    }

    @Test
    void rolesPersistAsCollection() {
        UserEntity u = new UserEntity("bob", "bob@example.com", "$2a$10$hash");
        u.setRoles(Set.of(Role.ADMIN));
        repo.save(u);
        repo.flush(); repo.clear();

        UserEntity reloaded = repo.findByUsername("bob").orElseThrow();
        assertThat(reloaded.getRoles()).containsExactlyInAnyOrder(Role.ADMIN);
    }
}
```

> 需要 `test` profile 让测试用独立的 H2（不污染 dev 的 `.market/db/market`）。新增 `src/test/resources/application-test.yml`：
```yaml
spring:
  datasource:
    url: jdbc:h2:mem:market-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: validate   # Flyway 建表，Hibernate 只校验
  flyway:
    enabled: true
    locations: classpath:db/migration
    clean-disabled: false
```
> 注意：`@SpringBootTest` 会启动整个上下文，包括脚手架 `SecurityConfig`（httpBasic + 随机用户）——这不影响 repository 测试（不打 HTTP）。但脚手架 `UserDetailsServiceAutoConfiguration` 的随机密码日志会出现在测试输出里（噪声但无害，Task 8 移除）。

- [ ] **Step 6: 跑测试，确认 RED（编译失败 / 表未建）→ 写迁移后 GREEN**

先跑（RED）：`./mvnw -q -Dtest=UserRepositoryTest test`。预期：可能因 `users` 表与 Flyway 迁移不匹配，或实体/迁移列定义漂移而失败。
按需要修正迁移或实体，直到 2/2 PASS（GREEN）。

- [ ] **Step 7: 跑全量测试确认不回归**

Run: `./mvnw -q test`
Expected: 22/22 PASS（20 + 2 新）。

- [ ] **Step 8: 提交**

```bash
git add -A
git commit -m "✨ feat(auth): UserEntity + Role + UserRepository + Flyway V1 migration"
```

---

### Task 3: JwkLessJwtService（JJWT 签发/校验 access token）

**Files:**
- Create: `src/main/java/fan/summer/marketplace/auth/service/JwkLessJwtService.java`
- Test: `src/test/java/fan/summer/marketplace/auth/service/JwkLessJwtServiceTest.java`

**Interfaces:**
- Consumes: `market.auth.jwt-secret`、`market.auth.access-ttl-seconds`（Task 1 配置）；JJWT（Task 1 依赖）。
- Produces:
  - `JwkLessJwtService.issueAccessToken(long userId, String username, Set<String> roles)` → `String`（JWT，载荷 `{sub, uid, roles, exp, iat, jti}`）。
  - `JwkLessJwtService.parseAndVerify(String token)` → `MarketClaims`（record：`long uid`、`String username`、`Set<String> roles`、`Instant exp`）；过期/篡改抛 `ApiException(ErrorCode.TOKEN_EXPIRED)` 或 `ApiException(ErrorCode.UNAUTHORIZED)`。
  - 任务 5/6 依赖它。

- [ ] **Step 1: 写失败测试**

```java
package fan.summer.marketplace.auth.service;

import fan.summer.marketplace.common.error.ApiException;
import fan.summer.marketplace.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwkLessJwtServiceTest {

    private JwkLessJwtService jwt;

    @BeforeEach
    void setup() {
        // 用固定密钥测试（生产从配置读，空则随机生成）
        jwt = new JwkLessJwtService("test-secret-key-at-least-32-bytes-long-aaaa", 900L);
    }

    @Test
    void issuesAndParsesRoundTrip() {
        String token = jwt.issueAccessToken(42L, "alice", Set.of("ROLE_USER", "ROLE_AUTHOR"));
        JwkLessJwtService.MarketClaims claims = jwt.parseAndVerify(token);
        assertThat(claims.uid()).isEqualTo(42L);
        assertThat(claims.username()).isEqualTo("alice");
        assertThat(claims.roles()).containsExactlyInAnyOrder("ROLE_USER", "ROLE_AUTHOR");
    }

    @Test
    void expiredTokenThrowsTokenExpired() throws Exception {
        jwt = new JwkLessJwtService("test-secret-key-at-least-32-bytes-long-aaaa", -10L); // 已过期
        String token = jwt.issueAccessToken(1L, "x", Set.of("ROLE_USER"));
        assertThatThrownBy(() -> jwt.parseAndVerify(token))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void tamperedTokenThrowsUnauthorized() {
        String token = jwt.issueAccessToken(1L, "x", Set.of("ROLE_USER"));
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertThatThrownBy(() -> jwt.parseAndVerify(tampered))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}
```

- [ ] **Step 2: 跑测试确认 RED（类不存在）**

Run: `./mvnw -q -Dtest=JwkLessJwtServiceTest test` → 编译失败。

- [ ] **Step 3: 写实现**

```java
package fan.summer.marketplace.auth.service;

import fan.summer.marketplace.common.error.ApiException;
import fan.summer.marketplace.common.error.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 用 JJWT 签发/校验 HS256 access token。Refresh token 是不透明随机串（见 RefreshTokenService），不走 JWT。
 *
 * <p>密钥来源：构造时传入（来自 market.auth.jwt-secret）。为空则生成临时随机密钥并 WARN（仅 dev）。
 */
public class JwkLessJwtService {

    public record MarketClaims(long uid, String username, Set<String> roles, Instant exp) {}

    private final SecretKey key;
    private final long accessTtlSeconds;

    public JwkLessJwtService(String secret, long accessTtlSeconds) {
        this.key = toKey(secret);
        this.accessTtlSeconds = accessTtlSeconds;
    }

    public String issueAccessToken(long userId, String username, Set<String> roles) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(accessTtlSeconds);
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .id(UUID.randomUUID().toString())
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public MarketClaims parseAndVerify(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            long uid = ((Number) c.get("uid")).longValue();
            Set<String> roles = new HashSet<>((List<String>) c.get("roles"));
            return new MarketClaims(uid, c.getSubject(), roles, c.getExpiration().toInstant());
        } catch (ExpiredJwtException e) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
    }

    private static SecretKey toKey(String secret) {
        String s = secret == null ? "" : secret;
        if (s.length() < 32) {
            // 补齐到 32 字节（HS256 要求 ≥256bit）；生产应显式配置长密钥
            s = (s + "                                ").substring(0, 32);
        }
        return Keys.hmacShaKeyFor(s.getBytes(StandardCharsets.UTF_8));
    }

    public Duration accessTtl() {
        return Duration.ofSeconds(accessTtlSeconds);
    }
}
```

> 注意：`Jwts.SIG.HS256` 是 JJWT 0.12.x API（0.11.x 用 `SignatureAlgorithm.HS256`）。若 0.12.6 API 有差异，以实际为准（实现者跑测试验证）。

- [ ] **Step 4: 跑测试确认 GREEN（3/3 PASS）**

Run: `./mvnw -q -Dtest=JwkLessJwtServiceTest test`

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "✨ feat(auth): JwkLessJwtService (JJWT HS256 access token issue/verify)"
```

---

### Task 4: RefreshTokenEntity + RefreshTokenRepository + RefreshTokenService

**Files:**
- Create: `src/main/java/fan/summer/marketplace/auth/entity/RefreshTokenEntity.java`
- Create: `src/main/java/fan/summer/marketplace/auth/repository/RefreshTokenRepository.java`
- Create: `src/main/java/fan/summer/marketplace/auth/service/RefreshTokenService.java`
- Test: `src/test/java/fan/summer/marketplace/auth/service/RefreshTokenServiceTest.java`

**Interfaces:**
- Consumes: `RefreshTokenEntity` 表（Task 2 的 V1 迁移已建）、`market.auth.refresh-ttl-seconds`、`UserRepository`（关联用户）。
- Produces: `RefreshTokenService.issue(long userId, String deviceLabel)` → `String`（不透明随机 token，256-bit hex）；`rotate(String oldToken)` → `String`（撤销旧的、发新的）；`revoke(String token)`；`revokeAllForUser(long userId)`；`findActive(String token)` → `Optional<RefreshTokenEntity>`（校验未撤销未过期）。任务 5 依赖。

- [ ] **Step 1: 写 `RefreshTokenEntity`**（表 `refresh_tokens`，V1 已建）

```java
package fan.summer.marketplace.auth.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) private String tokenHash;
    @Column(name = "device_label", length = 120) private String deviceLabel;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    // getters/setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getDeviceLabel() { return deviceLabel; }
    public void setDeviceLabel(String deviceLabel) { this.deviceLabel = deviceLabel; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public boolean isActive() { return revokedAt == null && expiresAt.isAfter(Instant.now()); }
    protected RefreshTokenEntity() {}
}
```

- [ ] **Step 2: 写 `RefreshTokenRepository`**

```java
package fan.summer.marketplace.auth.repository;

import fan.summer.marketplace.auth.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {
    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshTokenEntity r SET r.revokedAt = CURRENT_TIMESTAMP WHERE r.userId = :userId AND r.revokedAt IS NULL")
    int revokeAllForUser(Long userId);
}
```

- [ ] **Step 3: 写 `RefreshTokenService`**

```java
package fan.summer.marketplace.auth.service;

import fan.summer.marketplace.auth.entity.RefreshTokenEntity;
import fan.summer.marketplace.auth.repository.RefreshTokenRepository;
import fan.summer.marketplace.common.error.ApiException;
import fan.summer.marketplace.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;

@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final RefreshTokenRepository repo;
    private final long refreshTtlSeconds;

    public RefreshTokenService(RefreshTokenRepository repo,
            @Value("${market.auth.refresh-ttl-seconds:2592000}") long refreshTtlSeconds) {
        this.repo = repo;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    @Transactional
    public String issue(long userId, String deviceLabel) {
        String raw = randomToken();
        RefreshTokenEntity e = new RefreshTokenEntity();
        e.setUserId(userId);
        e.setDeviceLabel(deviceLabel);
        e.setTokenHash(sha256(raw));
        e.setExpiresAt(Instant.now().plusSeconds(refreshTtlSeconds));
        repo.save(e);
        return raw;
    }

    @Transactional
    public String rotate(String oldRaw) {
        RefreshTokenEntity e = findActive(oldRaw)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "refresh token 无效或已过期"));
        e.setRevokedAt(Instant.now());
        repo.save(e);
        return issue(e.getUserId(), e.getDeviceLabel());
    }

    @Transactional
    public void revoke(String raw) {
        repo.findByTokenHash(sha256(raw)).ifPresent(e -> { e.setRevokedAt(Instant.now()); repo.save(e); });
    }

    @Transactional
    public int revokeAllForUser(long userId) {
        return repo.revokeAllForUser(userId);
    }

    @Transactional(readOnly = true)
    public Optional<RefreshTokenEntity> findActive(String raw) {
        return repo.findByTokenHash(sha256(raw)).filter(RefreshTokenEntity::isActive);
    }

    private static String randomToken() {
        byte[] buf = new byte[32];
        RNG.nextBytes(buf);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 4: 写失败测试**（issue/rotate/revoke/revokeAll、不透明、哈希存储、过期无效）

```java
package fan.summer.marketplace.auth.service;

import fan.summer.marketplace.auth.entity.RefreshTokenEntity;
import fan.summer.marketplace.auth.repository.RefreshTokenRepository;
import fan.summer.marketplace.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RefreshTokenServiceTest {

    @Autowired RefreshTokenService svc;
    @Autowired RefreshTokenRepository repo;

    @Test
    void issueStoresHashNotRawAndIsVerifiable() {
        String raw = svc.issue(7L, "macbook");
        // 库里存的是 hash，不是明文
        assertThat(repo.findByTokenHash(raw)).isEmpty();
        assertThat(svc.findActive(raw)).isPresent();
        assertThat(svc.findActive(raw).get().getUserId()).isEqualTo(7L);
    }

    @Test
    void rotateRevokesOldAndIssuesNew() {
        String old = svc.issue(7L, "macbook");
        String next = svc.rotate(old);
        assertThat(next).isNotEqualTo(old);
        assertThat(svc.findActive(old)).isEmpty();        // 旧的撤销
        assertThat(svc.findActive(next)).isPresent();     // 新的有效
    }

    @Test
    void revokeMarksRevoked() {
        String raw = svc.issue(7L, "x");
        svc.revoke(raw);
        assertThat(svc.findActive(raw)).isEmpty();
    }

    @Test
    void revokeAllForUserRevokesAll() {
        String a = svc.issue(7L, "a");
        String b = svc.issue(7L, "b");
        svc.revokeAllForUser(7L);
        assertThat(svc.findActive(a)).isEmpty();
        assertThat(svc.findActive(b)).isEmpty();
    }

    @Test
    void expiredTokenIsNotActive() {
        String raw = svc.issue(7L, "x");
        // 手动把过期时间设到过去
        RefreshTokenEntity e = repo.findByTokenHash(RefreshTokenService.sha256(raw)).orElseThrow();
        e.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        repo.save(e);
        assertThat(svc.findActive(raw)).isEmpty();
    }

    @Test
    void rotateInvalidThrows() {
        assertThatThrownBy(() -> svc.rotate("garbage"))
                .isInstanceOf(ApiException.class);
    }
}
```

- [ ] **Step 5: 跑测试 RED→GREEN**

Run: `./mvnw -q -Dtest=RefreshTokenServiceTest test` → 6/6 PASS。

- [ ] **Step 6: 全量不回归**

Run: `./mvnw -q test` → 28/28 PASS（22 + 6）。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "✨ feat(auth): RefreshTokenEntity + service (issue/rotate/revoke, hash-stored, opaque)"
```

---

### Task 5: AuthService（注册/登录/密码校验/账户更新）

**Files:**
- Create: `src/main/java/fan/summer/marketplace/auth/service/AuthService.java`
- Test: `src/test/java/fan/summer/marketplace/auth/service/AuthServiceTest.java`

**Interfaces:**
- Consumes: `UserRepository`、`JwkLessJwtService`、`RefreshTokenService`、`PasswordEncoder`（BCrypt，Task 8 的 `MarketSecurityConfig` 提供 bean——**本任务先在测试里构造 `BCryptPasswordEncoder` 直接 new**，生产由 Task 8 注入）、`market.auth.password.*`、`market.auth.registration.enabled`。
- Produces:
  - `register(username,email,password)` → `AuthTokens`（校验冲突→`USERNAME_TAKEN`/`EMAIL_TAKEN`；校验密码策略；bcrypt 哈希；默认给 `ROLE_USER`；发 access+refresh）。
  - `login(usernameOrEmail,password,deviceLabel)` → `AuthTokens`（失败→`INVALID_CREDENTIALS`；禁用账户→`FORBIDDEN`）。
  - `refresh(refreshToken)` → `AuthTokens`（委托 `RefreshTokenService.rotate` + 重发 access）。
  - `changePassword(userId, oldRaw, newRaw)`、`updateProfile(userId, displayName, avatarUrl)`。
  - `AuthTokens`（record：`accessToken`、`refreshToken`、`expiresIn`、`AccountView user`）——放 `auth/dto/`。
- 任务 9（controller）依赖。

- [ ] **Step 1: 先建 DTO**（`auth/dto/`）

`AccountView.java`：
```java
package fan.summer.marketplace.auth.dto;
import fan.summer.marketplace.auth.entity.Role;
import java.util.Set;
public record AccountView(Long id, String username, String email, String displayName,
                          String avatarUrl, Set<Role> roles) {}
```
`AuthTokens.java`：
```java
package fan.summer.marketplace.auth.dto;
public record AuthTokens(String accessToken, String refreshToken, long expiresIn, AccountView user) {}
```

- [ ] **Step 2: 写 `AuthService`**

```java
package fan.summer.marketplace.auth.service;

import fan.summer.marketplace.auth.dto.AccountView;
import fan.summer.marketplace.auth.dto.AuthTokens;
import fan.summer.marketplace.auth.entity.Role;
import fan.summer.marketplace.auth.entity.UserEntity;
import fan.summer.marketplace.auth.repository.UserRepository;
import fan.summer.marketplace.common.error.ApiException;
import fan.summer.marketplace.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwkLessJwtService jwt;
    private final RefreshTokenService refreshSvc;
    private final boolean registrationEnabled;
    private final int passwordMinLength;
    private final boolean passwordRequireLetterAndDigit;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwkLessJwtService jwt,
            RefreshTokenService refreshSvc,
            @Value("${market.auth.registration.enabled:true}") boolean registrationEnabled,
            @Value("${market.auth.password.min-length:8}") int passwordMinLength,
            @Value("${market.auth.password.require-letter-and-digit:true}") boolean requireLetterAndDigit) {
        this.users = users; this.encoder = encoder; this.jwt = jwt; this.refreshSvc = refreshSvc;
        this.registrationEnabled = registrationEnabled;
        this.passwordMinLength = passwordMinLength;
        this.passwordRequireLetterAndDigit = requireLetterAndDigit;
    }

    @Transactional
    public AuthTokens register(String username, String email, String rawPassword) {
        if (!registrationEnabled) throw new ApiException(ErrorCode.FORBIDDEN, "注册已关闭");
        validatePassword(rawPassword);
        if (users.existsByUsername(username)) throw new ApiException(ErrorCode.USERNAME_TAKEN);
        if (users.existsByEmail(email)) throw new ApiException(ErrorCode.EMAIL_TAKEN);
        UserEntity u = new UserEntity(username, email, encoder.encode(rawPassword));
        u.setRoles(new LinkedHashSet<>(Set.of(Role.USER)));
        users.save(u);
        return issueTokens(u, null);
    }

    @Transactional
    public AuthTokens login(String usernameOrEmail, String rawPassword, String deviceLabel) {
        UserEntity u = users.findByUsername(usernameOrEmail)
                .or(() -> users.findByEmail(usernameOrEmail))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));
        if (u.getStatus() == UserEntity.Status.DISABLED) {
            throw new ApiException(ErrorCode.FORBIDDEN, "账户已禁用");
        }
        if (!encoder.matches(rawPassword, u.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        return issueTokens(u, deviceLabel);
    }

    @Transactional
    public AuthTokens refresh(String refreshToken) {
        String newRefresh = refreshSvc.rotate(refreshToken);
        // rotate 保留了 userId，据此重发 access
        long userId = refreshSvc.findActive(newRefresh)
                .map(e -> e.getUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        UserEntity u = users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        return new AuthTokens(
                jwt.issueAccessToken(u.getId(), u.getUsername(), toAuthorities(u)),
                newRefresh, jwt.accessTtl().getSeconds(), toView(u));
    }

    @Transactional
    public void changePassword(long userId, String oldRaw, String newRaw) {
        UserEntity u = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!encoder.matches(oldRaw, u.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "旧密码不正确");
        }
        validatePassword(newRaw);
        u.setPasswordHash(encoder.encode(newRaw));
        users.save(u);
        refreshSvc.revokeAllForUser(userId);  // 改密码吊销所有会话
    }

    @Transactional
    public AccountView updateProfile(long userId, String displayName, String avatarUrl) {
        UserEntity u = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        u.setDisplayName(displayName);
        u.setAvatarUrl(avatarUrl);
        users.save(u);
        return toView(u);
    }

    // —— helpers ——
    private AuthTokens issueTokens(UserEntity u, String deviceLabel) {
        String access = jwt.issueAccessToken(u.getId(), u.getUsername(), toAuthorities(u));
        String refresh = refreshSvc.issue(u.getId(), deviceLabel);
        return new AuthTokens(access, refresh, jwt.accessTtl().getSeconds(), toView(u));
    }

    private static Set<String> toAuthorities(UserEntity u) {
        java.util.Set<String> a = new java.util.HashSet<>();
        for (Role r : u.getRoles()) a.add(r.authority());
        return a;
    }

    private static AccountView toView(UserEntity u) {
        return new AccountView(u.getId(), u.getUsername(), u.getEmail(), u.getDisplayName(),
                u.getAvatarUrl(), u.getRoles());
    }

    private void validatePassword(String raw) {
        if (raw == null || raw.length() < passwordMinLength) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "密码至少 " + passwordMinLength + " 位");
        }
        if (passwordRequireLetterAndDigit) {
            boolean letter = false, digit = false;
            for (char c : raw.toCharArray()) { if (Character.isLetter(c)) letter = true; if (Character.isDigit(c)) digit = true; }
            if (!(letter && digit)) throw new ApiException(ErrorCode.BAD_REQUEST, "密码须同时包含字母与数字");
        }
    }
}
```

- [ ] **Step 3: 写失败测试**（register 成功/冲突/弱密码/关闭；login 成功/错密码/禁用；changePassword 吊销会话）

```java
package fan.summer.marketplace.auth.service;

import fan.summer.marketplace.auth.dto.AuthTokens;
import fan.summer.marketplace.auth.entity.Role;
import fan.summer.marketplace.auth.entity.UserEntity;
import fan.summer.marketplace.auth.repository.UserRepository;
import fan.summer.marketplace.common.error.ApiException;
import fan.summer.marketplace.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthServiceTest {

    @Autowired AuthService svc;
    @Autowired UserRepository users;

    @BeforeEach
    void seed() {
        // 用已禁用账户测 login；预先插一个
        UserEntity disabled = new UserEntity("zoe", "zoe@example.com", new BCryptPasswordEncoder().encode("pass1234"));
        disabled.setStatus(UserEntity.Status.DISABLED);
        users.save(disabled);
    }

    @Test
    void registerCreatesUserWithUserRoleAndReturnsTokens() {
        AuthTokens t = svc.register("alice", "alice@example.com", "pass1234");
        assertThat(t.accessToken()).isNotBlank();
        assertThat(t.refreshToken()).isNotBlank();
        assertThat(t.user().roles()).containsExactly(Role.USER);
        assertThat(users.existsByUsername("alice")).isTrue();
    }

    @Test
    void registerDuplicateUsernameThrows() {
        svc.register("alice", "a1@example.com", "pass1234");
        assertThatThrownBy(() -> svc.register("alice", "a2@example.com", "pass1234"))
                .extracting("code").isEqualTo(ErrorCode.USERNAME_TAKEN);
    }

    @Test
    void registerWeakPasswordThrows() {
        assertThatThrownBy(() -> svc.register("bob", "bob@example.com", "123"))
                .extracting("code").isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void loginSucceedsAndReturnsTokens() {
        svc.register("alice", "alice@example.com", "pass1234");
        AuthTokens t = svc.login("alice", "pass1234", "macbook");
        assertThat(t.accessToken()).isNotBlank();
    }

    @Test
    void loginWrongPasswordThrows() {
        svc.register("alice", "alice@example.com", "pass1234");
        assertThatThrownBy(() -> svc.login("alice", "wrong", "x"))
                .extracting("code").isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void loginDisabledAccountThrowsForbidden() {
        assertThatThrownBy(() -> svc.login("zoe", "pass1234", "x"))
                .extracting("code").isEqualTo(ErrorCode.FORBIDDEN);
    }
}
```

> 注意：`AuthService` 依赖 `PasswordEncoder` bean。本测试用 `@SpringBootTest` 会注入——但脚手架 `SecurityConfig`（Task 8 才替换）目前**没有** `PasswordEncoder` bean，Boot 也不会自动给（除非 `UserDetailsServiceAutoConfiguration`，它给的不能注入）。**因此本任务会失败于「找不到 PasswordEncoder bean」**。**解决**：在本任务新建一个临时 `@TestConfiguration` 提供 `BCryptPasswordEncoder` bean，或在 `MarketSecurityConfig`（Task 8）里提供——但 Task 8 还没到。**推荐**：在 `src/test/java/.../auth/AuthTestConfig.java` 里加 `@TestConfiguration` 暴露 `PasswordEncoder`，让本任务 + Task 4 的测试都能注入。**实现者按此办**。

- [ ] **Step 4: 跑测试 RED→GREEN（必要时加 AuthTestConfig 提供 PasswordEncoder bean）**

Run: `./mvnw -q -Dtest=AuthServiceTest test` → 6/6 PASS。

- [ ] **Step 5: 全量不回归**

Run: `./mvnw -q test` → 34/34 PASS（28 + 6）。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "✨ feat(auth): AuthService (register/login/refresh/changePassword/updateProfile)"
```

---

### Task 6: MarketUserDetails + JwtAuthenticationFilter

**Files:**
- Create: `src/main/java/fan/summer/marketplace/auth/security/MarketUserDetails.java`
- Create: `src/main/java/fan/summer/marketplace/auth/security/JwtAuthenticationFilter.java`
- Test: `src/test/java/fan/summer/marketplace/auth/security/JwtAuthenticationFilterTest.java`

**Interfaces:**
- Consumes: `JwkLessJwtService`。
- Produces:
  - `MarketUserDetails implements Authentication`（或 `extends AbstractAuthenticationToken`）：携带 `uid`、`username`、`authorities`。任务 8（SecurityConfig）用它填充 `SecurityContextHolder`。
  - `JwtAuthenticationFilter extends OncePerRequestFilter`：从 `Authorization: Bearer <token>` 取 token → `jwt.parseAndVerify` → 构造 `MarketUserDetails` → 放入 `SecurityContextHolder`；失败不抛（让链后续的 entry point 处理）。任务 8 装配它。

- [ ] **Step 1: 写 `MarketUserDetails`**

```java
package fan.summer.marketplace.auth.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/** JWT 解析后的认证主体。authenticated=true（已通过签名校验）。 */
public class MarketUserDetails extends AbstractAuthenticationToken {

    private final long uid;
    private final String username;

    public MarketUserDetails(long uid, String username, Set<String> roles) {
        super(toAuthorities(roles));
        this.uid = uid;
        this.username = username;
        setAuthenticated(true);
    }

    @Override public Object getPrincipal() { return username; }
    @Override public Object getCredentials() { return null; }
    public long getUid() { return uid; }
    public String getUsername() { return username; }

    private static Collection<? extends GrantedAuthority> toAuthorities(Set<String> roles) {
        return roles.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
    }
}
```

- [ ] **Step 2: 写 `JwtAuthenticationFilter`**

```java
package fan.summer.marketplace.auth.security;

import fan.summer.marketplace.auth.service.JwkLessJwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 从 Authorization: Bearer 头解析 JWT，构造 MarketUserDetails 放入 SecurityContext。
 * 解析失败不抛异常——交由 SecurityFilterChain 的 AuthenticationEntryPoint 统一输出 401 ApiError（Task 7/8）。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String BEARER = "Bearer ";

    private final JwkLessJwtService jwt;

    public JwtAuthenticationFilter(JwkLessJwtService jwt) { this.jwt = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            String token = header.substring(BEARER.length()).trim();
            try {
                JwkLessJwtService.MarketClaims claims = jwt.parseAndVerify(token);
                SecurityContextHolder.getContext().setAuthentication(
                        new MarketUserDetails(claims.uid(), claims.username(), claims.roles()));
            } catch (Exception ignored) {
                // 失败：清空 context，让后续 entry point 处理（不在这里写响应，避免重复）
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(req, resp);
    }
}
```

- [ ] **Step 3: 写测试**（用 MockMvc 风格直接 new filter，测有/无/坏 token 时 SecurityContext 状态）

```java
package fan.summer.marketplace.auth.security;

import fan.summer.marketplace.auth.service.JwkLessJwtService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JwtAuthenticationFilterTest {

    private final JwkLessJwtService jwt = new JwkLessJwtService("test-secret-key-at-least-32-bytes-long-aaaa", 900L);

    @Test
    void validBearerPopulatesSecurityContext() throws Exception {
        SecurityContextHolder.clearContext();
        String token = jwt.issueAccessToken(5L, "alice", Set.of("ROLE_USER"));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwt);
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(((MarketUserDetails) SecurityContextHolder.getContext().getAuthentication()).getUid()).isEqualTo(5L);
        SecurityContextHolder.clearContext();
    }

    @Test
    void noHeaderLeavesContextEmptyAndChainContinues() throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest req = new MockHttpServletRequest();
        FilterChain chain = mock(FilterChain.class);
        new JwtAuthenticationFilter(jwt).doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(req, org.mockito.ArgumentMatchers.any());
    }

    @Test
    void badTokenClearsContextAndChainContinues() throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer garbage");
        FilterChain chain = mock(FilterChain.class);
        new JwtAuthenticationFilter(jwt).doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(req, org.mockito.ArgumentMatchers.any());
    }
}
```

- [ ] **Step 4: 跑测试 RED→GREEN**

Run: `./mvnw -q -Dtest=JwtAuthenticationFilterTest test` → 3/3 PASS。

- [ ] **Step 5: 全量不回归**

Run: `./mvnw -q test` → 37/37 PASS（34 + 3）。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "✨ feat(auth): MarketUserDetails + JwtAuthenticationFilter (Bearer → SecurityContext)"
```

---

### Task 7: ApiErrorAuthenticationEntryPoint + ApiErrorAccessDeniedHandler（修复 §3.2 安全响应缺口）

**Files:**
- Create: `src/main/java/fan/summer/marketplace/auth/security/ApiErrorAuthenticationEntryPoint.java`
- Create: `src/main/java/fan/summer/marketplace/auth/security/ApiErrorAccessDeniedHandler.java`
- Test: `src/test/java/fan/summer/marketplace/auth/security/SecurityResponseShapeTest.java`

**Interfaces:**
- Consumes: `ApiError`、`ErrorCode`、全局 `ObjectMapper`。
- Produces:
  - `ApiErrorAuthenticationEntryPoint implements AuthenticationEntryPoint`：未认证（401）→ 写 `ApiError.of(UNAUTHORIZED,...)` JSON，状态 401。
  - `ApiErrorAccessDeniedHandler implements AccessDeniedHandler`：权限不足（403）→ 写 `ApiError.of(FORBIDDEN,...)` JSON，状态 403。
  - 这两个 bean 在 Task 8 装配进 `SecurityFilterChain`。
- **这是计划 0 最终评审遗留的 §3.2 缺口的修复**：Spring Security 的 401/403 之前返回空 body，现在统一输出 `ApiError`。

- [ ] **Step 1: 写 `ApiErrorAuthenticationEntryPoint`**

```java
package fan.summer.marketplace.auth.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import fan.summer.marketplace.common.error.ApiError;
import fan.summer.marketplace.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/** 未认证请求的 401 → 统一 ApiError JSON（修复计划 0 评审遗留的 §3.2 缺口）。 */
@Component
public class ApiErrorAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final tools.jackson.databind.ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder()
            .build();  // 简单起见自建；也可注入全局 ObjectMapper

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse resp, AuthenticationException ex)
            throws java.io.IOException {
        ApiError body = ApiError.of(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage(), null);
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.getWriter().write(json.writeValueAsString(body));
    }
}
```

> 注意：`@JsonInclude(NON_NULL)` 是计划 0 修复加在 `ApiError` record 上的，所以 `retryAfterSeconds=null` 不会输出——复核计划 0 的 `ApiError.java` 仍有该注解。

- [ ] **Step 2: 写 `ApiErrorAccessDeniedHandler`**

```java
package fan.summer.marketplace.auth.security;

import fan.summer.marketplace.common.error.ApiError;
import fan.summer.marketplace.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** 权限不足的 403 → 统一 ApiError JSON。 */
@Component
public class ApiErrorAccessDeniedHandler implements AccessDeniedHandler {

    private final tools.jackson.databind.ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder().build();

    @Override
    public void handle(HttpServletRequest req, HttpServletResponse resp, AccessDeniedException ex)
            throws java.io.IOException {
        ApiError body = ApiError.of(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage(), null);
        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.getWriter().write(json.writeValueAsString(body));
    }
}
```

- [ ] **Step 3: 写测试**（未认证→401 ApiError；用 MockMvc + 一个受保护端点。本任务先把 entry/handler 写好，Task 8 装配后才能端到端测；本任务的测试用直接调用 handler.write 的方式验证 JSON 形状）

```java
package fan.summer.marketplace.auth.security;

import fan.summer.marketplace.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityResponseShapeTest {

    @Test
    void entryPointWrites401ApiErrorJson() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        new ApiErrorAuthenticationEntryPoint()
                .commence(new MockHttpServletRequest(), resp, new BadCredentialsException("x"));
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("\"code\":\"" + ErrorCode.UNAUTHORIZED.name() + "\"");
        assertThat(resp.getContentAsString()).doesNotContain("retryAfterSeconds");
    }

    @Test
    void deniedHandlerWrites403ApiErrorJson() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        new ApiErrorAccessDeniedHandler()
                .handle(new MockHttpServletRequest(), resp, new org.springframework.security.access.AccessDeniedException("x"));
        assertThat(resp.getStatus()).isEqualTo(403);
        assertThat(resp.getContentAsString()).contains("\"code\":\"" + ErrorCode.FORBIDDEN.name() + "\"");
    }
}
```

- [ ] **Step 4: 跑测试 RED→GREEN**

Run: `./mvnw -q -Dtest=SecurityResponseShapeTest test` → 2/2 PASS。

- [ ] **Step 5: 全量不回归**

Run: `./mvnw -q test` → 39/39 PASS（37 + 2）。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "✨ feat(auth): ApiError entry point + access denied handler (§3.2 security-response contract)"
```

---

### Task 8: MarketSecurityConfig（替换脚手架 SecurityConfig）+ PasswordEncoder bean

**Files:**
- Create: `src/main/java/fan/summer/marketplace/auth/security/MarketSecurityConfig.java`
- Delete/Replace: `src/main/java/fan/summer/marketplace/config/SecurityConfig.java`（脚手架版）
- Modify: `src/main/java/fan/summer/marketplace/config/JacksonConfig.java`（无，保留）
- Test: `src/test/java/fan/summer/marketplace/auth/security/SecurityFilterChainTest.java`

**Interfaces:**
- Consumes: `JwtAuthenticationFilter`、`ApiErrorAuthenticationEntryPoint`、`ApiErrorAccessDeniedHandler`、`market.auth.jwt-secret`、`market.auth.access-ttl-seconds`。
- Produces:
  - `MarketSecurityConfig`：`@EnableMethodSecurity`；`SecurityFilterChain` bean（STATELESS、加 `JwtAuthenticationFilter` 在 `UsernamePasswordAuthenticationFilter` 前、`@Order` 默认、放行 `/api/auth/register|login|refresh` + `/actuator/health|info` + `/marketplaces/**`，其余 `authenticated()`、`exceptionHandling` 接 entry/denied handler）。
  - `PasswordEncoder` bean（`BCryptPasswordEncoder`）。
  - `JwkLessJwtService` bean（从配置读 secret/ttl）。
  - 删除脚手架 `config/SecurityConfig`（被 `MarketSecurityConfig` 取代）。
- **端到端验证 §3.2 契约**：受保护端点未带 token→401 ApiError；带 token→通过。

- [ ] **Step 1: 写 `MarketSecurityConfig`**

```java
package fan.summer.marketplace.auth.security;

import fan.summer.marketplace.auth.service.JwkLessJwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.security.SecureRandom;

/** 完整认证/授权过滤链（取代脚手架 SecurityConfig）。 */
@Configuration
@EnableMethodSecurity
public class MarketSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(MarketSecurityConfig.class);

    private final JwtAuthenticationFilter jwtFilter;
    private final ApiErrorAuthenticationEntryPoint entryPoint;
    private final ApiErrorAccessDeniedHandler deniedHandler;

    public MarketSecurityConfig(JwtAuthenticationFilter jwtFilter,
            ApiErrorAuthenticationEntryPoint entryPoint,
            ApiErrorAccessDeniedHandler deniedHandler) {
        this.jwtFilter = jwtFilter; this.entryPoint = entryPoint; this.deniedHandler = deniedHandler;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())            // 纯 JWT API；CSRF token 不适用
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(
                    "/api/auth/register",
                    "/api/auth/login",
                    "/api/auth/refresh",
                    "/actuator/health",
                    "/actuator/info",
                    "/marketplaces/**"   // 计划 3 的清单文件，公开
                ).permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint(entryPoint)
                .accessDeniedHandler(deniedHandler))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwkLessJwtService jwtService(
            @Value("${market.auth.jwt-secret:}") String secret,
            @Value("${market.auth.access-ttl-seconds:900}") long accessTtl) {
        String s = (secret == null || secret.isBlank()) ? randomDevSecret() : secret;
        return new JwkLessJwtService(s, accessTtl);
    }

    private static String randomDevSecret() {
        log.warn("market.auth.jwt-secret 未配置——生成临时随机密钥（仅 dev；重启后所有 token 失效）");
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(64);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
```

> **注意 `JwtAuthenticationFilter` 需要是 bean** 才能注入。在本任务加一个 `@Bean JwtAuthenticationFilter`（或给 filter 类加 `@Component`）。**实现者**：给 `JwtAuthenticationFilter` 加 `@Component`（构造注入 `JwkLessJwtService`）。

- [ ] **Step 2: 删除脚手架 `config/SecurityConfig.java`**

```bash
git rm src/main/java/fan/summer/marketplace/config/SecurityConfig.java
```

> 删除后，`MarketSecurityConfig` 成为唯一的 `SecurityFilterChain` 提供者。脚手架 `UserDetailsServiceAutoConfiguration` 的随机密码日志也随之消失（因为没有 httpBasic + 没有 `UserDetailsService`，Boot 不再自动配置内存用户）。

- [ ] **Step 3: 写端到端测试 `SecurityFilterChainTest`**（用 `RestTestClient.bindToServer`，测未带 token→401 ApiError；带有效 token→200）

这个测试需要先有 Task 9 的 `/api/auth/me` 端点才能测「带 token→200」。**因此本任务的测试只覆盖「未带 token→401 ApiError」**（不需要 Task 9），「带 token」的端到端在 Task 9 完成后补。

```java
package fan.summer.marketplace.auth.security;

import fan.summer.marketplace.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SecurityFilterChainTest {

    @LocalServerPort int port;
    @Autowired RestTestClient.Builder builder;  // Boot 4.1 自动配置 RestTestClient.Builder

    private RestTestClient client() {
        return builder.baseUrl("http://127.0.0.1:" + port).build();
    }

    @Test
    void unprotectedEndpointPermitted() {
        // /actuator/health 放行
        String body = client().get().uri("/actuator/health")
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("\"status\":\"UP\"");
    }

    @Test
    void protectedEndpointWithoutTokenReturns401ApiError() {
        // /api/auth/me 需要 token；未带 → 401 + ApiError{code:UNAUTHORIZED}
        String body = client().get().uri("/api/auth/me")
                .exchange().expectStatus().isUnauthorized()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("\"code\":\"" + ErrorCode.UNAUTHORIZED.name() + "\"");
    }
}
```

> 注意：`/api/auth/me` 端点在 Task 9 才建。本任务测试它时会得到 404（端点不存在）而不是 401（安全在控制器之前，所以即使端点不存在，安全层也会先 401）。**验证**：Spring Security 在 `anyRequest().authenticated()` 下，未认证请求先 401，不到 DispatcherServlet——所以即使 `/api/auth/me` 控制器不存在，未带 token 也返回 401（安全优先）。**实现者跑测试确认这一点**；若返回 404，说明安全链没生效，需排查。

- [ ] **Step 4: 跑测试 RED→GREEN**

Run: `./mvnw -q -Dtest=SecurityFilterChainTest test` → 2/2 PASS。

- [ ] **Step 5: 全量不回归**（删除脚手架 SecurityConfig 后，计划 0 的 `MarketplaceApplicationTests.healthEndpointIsUp` 应仍 PASS——health 放行）

Run: `./mvnw -q test` → 41/41 PASS（39 + 2）。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "✨ feat(auth): MarketSecurityConfig (JWT filter chain + authz matrix + PasswordEncoder)"
```

---

### Task 9: AuthController + AccountController + DeviceController（端点）

**Files:**
- Create: `src/main/java/fan/summer/marketplace/auth/controller/AuthController.java`
- Create: `src/main/java/fan/summer/marketplace/auth/controller/AccountController.java`
- Create: `src/main/java/fan/summer/marketplace/auth/controller/DeviceController.java`
- Create: `src/main/java/fan/summer/marketplace/auth/dto/LoginRequest.java`
- Create: `src/main/java/fan/summer/marketplace/auth/dto/RegisterRequest.java`
- Create: `src/main/java/fan/summer/marketplace/auth/dto/ChangePasswordRequest.java`
- Create: `src/main/java/fan/summer/marketplace/auth/dto/DeviceView.java`
- Test: `src/test/java/fan/summer/marketplace/auth/controller/AuthControllerTest.java`

**Interfaces:**
- Consumes: `AuthService`、`RefreshTokenService`、`MarketUserDetails`（从 `SecurityContextHolder` 取当前用户）、`UserRepository`。
- Produces: §3.2 的全部 `/api/auth/*` + `/api/account/*` 端点（见 spec §3.2 表）。

- [ ] **Step 1: 写 DTOs**（`LoginRequest{usernameOrEmail,password,deviceLabel?}`、`RegisterRequest{username,email,password}`、`ChangePasswordRequest{oldPassword,newPassword}`、`DeviceView{id,deviceLabel,lastSeenAt,active}`）

```java
package fan.summer.marketplace.auth.dto;
public record LoginRequest(String usernameOrEmail, String password, String deviceLabel) {}
```
```java
package fan.summer.marketplace.auth.dto;
public record RegisterRequest(String username, String email, String password) {}
```
```java
package fan.summer.marketplace.auth.dto;
public record ChangePasswordRequest(String oldPassword, String newPassword) {}
```
```java
package fan.summer.marketplace.auth.dto;
import java.time.Instant;
public record DeviceView(Long id, String deviceLabel, Instant lastSeenAt, boolean active) {}
```

- [ ] **Step 2: 写 `AuthController`**

```java
package fan.summer.marketplace.auth.controller;

import fan.summer.marketplace.auth.dto.AuthTokens;
import fan.summer.marketplace.auth.dto.LoginRequest;
import fan.summer.marketplace.auth.dto.RegisterRequest;
import fan.summer.marketplace.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/register")
    public AuthTokens register(@Valid @RequestBody RegisterRequest req) {
        return auth.register(req.username(), req.email(), req.password());
    }

    @PostMapping("/login")
    public AuthTokens login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req.usernameOrEmail(), req.password(), req.deviceLabel());
    }

    @PostMapping("/refresh")
    public AuthTokens refresh(@RequestBody java.util.Map<String,String> body) {
        return auth.refresh(body.get("refreshToken"));
    }

    @PostMapping("/logout")
    public void logout(@RequestBody java.util.Map<String,String> body) {
        auth.revokeRefresh(body.get("refreshToken"));  // AuthService 加这个委托方法
    }
}
```

> AuthService 需补 `revokeRefresh(String)` 委托 `RefreshTokenService.revoke`，与 `revokeAllForUser`。实现者补。

- [ ] **Step 3: 写 `AccountController`**（`GET /api/auth/me`、`PATCH /api/auth/me`、`GET /api/auth/devices`、`POST /api/auth/logout-all`）

```java
package fan.summer.marketplace.auth.controller;

import fan.summer.marketplace.auth.dto.AccountView;
import fan.summer.marketplace.auth.dto.ChangePasswordRequest;
import fan.summer.marketplace.auth.dto.DeviceView;
import fan.summer.marketplace.auth.entity.RefreshTokenEntity;
import fan.summer.marketplace.auth.repository.RefreshTokenRepository;
import fan.summer.marketplace.auth.security.MarketUserDetails;
import fan.summer.marketplace.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AccountController {

    private final AuthService auth;
    private final RefreshTokenRepository refreshRepo;

    public AccountController(AuthService auth, RefreshTokenRepository refreshRepo) {
        this.auth = auth; this.refreshRepo = refreshRepo;
    }

    @GetMapping("/me")
    public AccountView me(@org.springframework.security.core.annotation.AuthenticationPrincipal MarketUserDetails user) {
        return auth.view(user.getUid());  // AuthService 加 view(uid)
    }

    @PatchMapping("/me")
    public AccountView updateMe(@org.springframework.security.core.annotation.AuthenticationPrincipal MarketUserDetails user,
                                @RequestBody java.util.Map<String,String> body) {
        return auth.updateProfile(user.getUid(), body.get("displayName"), body.get("avatarUrl"));
    }

    @PatchMapping("/me/password")
    public void changePassword(@org.springframework.security.core.annotation.AuthenticationPrincipal MarketUserDetails user,
                               @Valid @RequestBody ChangePasswordRequest req) {
        auth.changePassword(user.getUid(), req.oldPassword(), req.newPassword());
    }

    @GetMapping("/devices")
    public List<DeviceView> devices(@org.springframework.security.core.annotation.AuthenticationPrincipal MarketUserDetails user) {
        return refreshRepo.findAllByUserIdOrderByCreatedAtDesc(user.getUid()).stream()
                .map(e -> new DeviceView(e.getId(), e.getDeviceLabel(),
                        e.getRevokedAt() == null ? e.getCreatedAt() : e.getRevokedAt(), e.isActive()))
                .toList();
    }

    @PostMapping("/logout-all")
    public void logoutAll(@org.springframework.security.core.annotation.AuthenticationPrincipal MarketUserDetails user) {
        auth.revokeAllSessions(user.getUid());
    }
}
```

> `RefreshTokenRepository` 需补 `findAllByUserIdOrderByCreatedAtDesc(long)`（`List<RefreshTokenEntity>`）。`AuthService` 需补 `view(uid)`（查 user 返 AccountView）、`revokeAllSessions(uid)`（委托 refreshSvc.revokeAllForUser）。**实现者补这些**。

- [ ] **Step 4: 写测试 `AuthControllerTest`**（端到端：register→login→me→devices→changePassword→logout-all；未带 token→401 ApiError）

```java
package fan.summer.marketplace.auth.controller;

import fan.summer.marketplace.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthControllerTest {

    @LocalServerPort int port;
    @Autowired RestTestClient.Builder builder;

    private RestTestClient client() { return builder.baseUrl("http://127.0.0.1:" + port).build(); }

    @Test
    void fullAuthFlow() {
        // register
        String reg = "{\"username\":\"alice\",\"email\":\"alice@example.com\",\"password\":\"pass1234\"}";
        String body = client().post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON).body(reg)
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("accessToken").contains("refreshToken");

        // 提取 accessToken（简单字符串切；实现者可用 ObjectMapper 解析）
        String accessToken = extract(body, "accessToken");

        // me（带 token）
        String me = client().get().uri("/api/auth/me")
                .header("Authorization", "Bearer " + accessToken)
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(me).contains("alice");
    }

    @Test
    void meWithoutTokenReturns401ApiError() {
        String body = client().get().uri("/api/auth/me")
                .exchange().expectStatus().isUnauthorized()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("\"code\":\"" + ErrorCode.UNAUTHORIZED.name() + "\"");
    }

    private static String extract(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"") + key.length() + 4;
        return json.substring(i, json.indexOf("\"", i));
    }
}
```

- [ ] **Step 5: 跑测试 RED→GREEN**

Run: `./mvnw -q -Dtest=AuthControllerTest test` → 2/2 PASS。

- [ ] **Step 6: 全量不回归**

Run: `./mvnw -q test` → 43/43 PASS（41 + 2）。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "✨ feat(auth): AuthController + AccountController (register/login/refresh/me/devices/password)"
```

---

### Task 10: RateLimitService（登录限流）

> **注：本任务与 Task 11 为「概要任务」**——上面 Task 1–9 已包含完整测试与实现代码。Task 10/11 的模式明确（滑动窗口计数器 / `ApplicationRunner` + `users.count()==0`），由执行者在 SDD 循环中按 TDD 展开为完整的失败测试→实现→绿。执行者读 brief 时把本节的描述当作需求，按既有代码风格（看 `RefreshTokenService`/`AuthService` 的写法）补全测试 + 实现。

**Files:**
- Create: `src/main/java/fan/summer/marketplace/auth/service/RateLimitService.java`
- Modify: `src/main/java/fan/summer/marketplace/auth/service/AuthService.java`（login 调用限流）
- Test: `src/test/java/fan/summer/marketplace/auth/service/RateLimitServiceTest.java`

**Interfaces:**
- Produces: `RateLimitService.checkAndRecordFailure(String key)` → 抛 `ApiException(ACCOUNT_LOCKED, retryAfterSeconds)` 当超阈值；`recordSuccess(String key)` 清零。key = IP + username。AuthService.login 在失败时调 checkAndRecordFailure，成功调 recordSuccess。**v1 用内存 ConcurrentHashMap 滑窗**（足够单实例；多实例部署需 Redis，留 v2）。

- [ ] **Step 1: 写失败测试 + 实现**（5 次/分钟阈值，第 6 次抛 ACCOUNT_LOCKED 带 retryAfterSeconds；成功清零）

```java
// RateLimitServiceTest —— 4 个测试：under-limit ok、6th throws ACCOUNT_LOCKED with retryAfter、success clears、different key independent
```

实现：`ConcurrentHashMap<String, Deque<Instant>>`，清理 >60s 的记录，size≥5 抛。

- [ ] **Step 2: 跑测试 RED→GREEN**（4/4 PASS）
- [ ] **Step 3: AuthService.login 接入**（失败调 checkAndRecordFailure(ip+username)，成功 recordSuccess）
- [ ] **Step 4: 全量不回归** → 47/47 PASS（43 + 4）
- [ ] **Step 5: 提交**

```bash
git commit -m "✨ feat(auth): RateLimitService (5/min login throttle + ACCOUNT_LOCKED)"
```

> AuthService.login 需要 IP——从 `HttpServletRequest` 取（controller 传入）。修改 login 签名为 `login(usernameOrEmail,password,deviceLabel,clientIp)`。实现者调整。

---

### Task 11: BootstrapAdminRunner（首管理员引导）

**Files:**
- Create: `src/main/java/fan/summer/marketplace/auth/bootstrap/BootstrapAdminRunner.java`
- Test: `src/test/java/fan/summer/marketplace/auth/bootstrap/BootstrapAdminRunnerTest.java`

**Interfaces:**
- Consumes: `UserRepository`、`PasswordEncoder`、`market.bootstrap.admin-token`。
- Produces: `ApplicationRunner`：启动时若 `users` 表为空 **且** `market.bootstrap.admin-token` 非空 **且** 请求的 token 匹配 → 创建一个 ADMIN 用户（用户名 `admin`，随机密码写入日志 WARN）。**token 不匹配或为空 → 不创建**（防公网抢占）。一次性（第二次启动 users 非空，跳过）。

- [ ] **Step 1: 写测试 + 实现**（空表+匹配 token→创建 ADMIN；非空表→跳过；token 不匹配→不创建）
- [ ] **Step 2: 跑测试 RED→GREEN**（3/3 PASS）
- [ ] **Step 3: 全量不回归** → 50/50 PASS（47 + 3）
- [ ] **Step 4: 提交**

```bash
git commit -m "✨ feat(auth): BootstrapAdminRunner (first-admin via one-time token)"
```

---

## 完成判据

- `./mvnw test`（JDK 21）全绿，约 50 个测试。
- 端到端流程跑通：register → login → /me（带 token）→ devices → changePassword → logout-all；未带 token 访问受保护端点 → 401 + `ApiError{code:UNAUTHORIZED}`（§3.2 缺口已修）。
- Spring Security 7 过滤链生效：JWT 解析、角色授权、entry/denied handler 输出统一 ApiError。
- Flyway V1 迁移在 H2 测试库与（未来）Postgres 上都能跑。
- `git log` 清晰 conventional commits。
- 删除脚手架 `config/SecurityConfig.java`，`UserDetailsServiceAutoConfiguration` 随机密码日志消失。

## ⚠️ 计划 1 执行前须知（来自计划 0 评审）

- **JDK 21**：`JAVA_HOME=/Users/phoebej/Library/Java/JavaVirtualMachines/azul-21.0.12/Contents/Home`。
- **Jackson 3**：`tools.jackson.databind.*`；注解用 `com.fasterxml.jackson.annotation.*`。
- **Security 7**：多 chain 用 `securityMatcher`+`@Order`；测试态安全用同模式。
- **RestTestClient**（非 TestRestTemplate）。
- **§3.2 缺口**：Task 7 + Task 8 是本计划的核心修复点——验证受保护端点未带 token 返回 401 ApiError JSON（非空 body）。

## 下一步

认证中心落地后，进入**计划 2（发布门户）**——`.fyp` 上传 + 校验管线 + 审核工作流。计划 2 会引用本计划产出的真实签名（`UserEntity`、`MarketUserDetails`、`@PreAuthorize("hasRole('AUTHOR')")`、`ErrorCode`）。
