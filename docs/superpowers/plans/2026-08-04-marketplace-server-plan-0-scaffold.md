# FengYu 插件市场服务 — 计划 0：仓库脚手架 + common 基础

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立全新独立仓库 `fengyu-marketplace-server` 的骨架——Spring Boot 4.1 + Java 21 + Spring Security 7 + Flyway + 多 DB 的可启动空服务，并落地 `common/` 共享基础（路径安全、有界 HTTP 读取、semver 比较、统一错误响应契约），为后续计划 1–4（认证 / 发布 / 聚合 / 前端）提供地基。

**Architecture:** 单模块 Maven 工程，包根 `fan.summer.marketplace`。`MarketplaceApplication` 是 main 类，默认监听 `:24057`，loopback 绑定（与主程序一致的起手安全姿态）。`common/` 下放与具体业务无关的工具（移植自 FengYu 主程序：`PluginContentPathSafety` → `PathSafety`、`BoundedHttp`、`compareVersions`），以及全局异常处理器输出的统一错误 JSON 契约（§3.2 错误响应契约）。本计划**不**实现认证/发布/聚合任何业务逻辑——只让 `mvn spring-boot:run` 能起、`/actuator/health` 返回 UP、`common/` 有测试覆盖。

**Tech Stack:** Spring Boot 4.1.0、Spring Framework 7.0.8、Spring Security 7（Boot 4 自带，本计划只引入 starter、不写过滤链——过滤链在计划 1）、Java 21、Maven（含 `spring-boot-maven-plugin`）、Flyway 11、H2（默认 dev DB）/ MySQL / PostgreSQL / SQLite 驱动、JUnit 5 + Spring Boot Test、`com.networknt:json-schema-validator`（计划 2 用，本计划先引入）。

> ## ⚠️ 执行后注记（2026-08-04，计划 0 已完成 + 最终评审通过）
>
> 计划 0 已在 `fengyu-marketplace-server` 仓库实现完成（tag `scaffold-v0.1`，20/20 测试通过，最终评审通过）。执行中发现的 **Boot 4.1 实际栈与计划/规格假设的差异**，记录在此供计划 1–4 参考（详见新仓库 `.superpowers/sdd/progress.md` 的 FORWARD-LOOKING FINDINGS）：
>
> 1. **Jackson 3（非 2）**：Boot 4.1 用 `tools.jackson.core:3.1.4`。本计划任务的 `JacksonConfig` 代码（`JavaTimeModule`/`WRITE_DATES_AS_TIMESTAMPS`）**不可编译**——实现时已改为 Jackson 3（java.time ISO 内置于 core，无需 module）。**`@JsonInclude` 等注解仍来自 `com.fasterxml.jackson.annotation`（Jackson 3 databind 内部用 2.21 的 annotations）**。计划 1–4 凡用 ObjectMapper 一律走 `tools.jackson.databind.*`。
> 2. **Spring Security 7 多链 fail-fast**：`UnreachableFilterChainException` 拒绝多个 `anyRequest()` SecurityFilterChain bean。测试态安全配置必须用 `securityMatcher("/path/**")` + `@Order(0)`（见新仓库 `TestSecurityConfig.java`）。
> 3. **`TestRestTemplate` 已移除**（Boot 4.1 迁到独立 artifact）。测试用 `RestTestClient.bindToServer()`（spring-test，真实 HTTP socket；见 `MarketplaceApplicationTests.java`）。
> 4. **计划 1 硬性验收（来自最终评审）**：`GlobalExceptionHandler` 只覆盖控制器内异常；Spring Security 自己的 401/403 当前返回**空 body**（过滤链在 DispatcherServlet 之前）。§3.2 的「统一错误契约」要求 401/403 也带 `code` 字段——计划 1 必须接自定义 `AuthenticationEntryPoint` + `AccessDeniedHandler` 输出 `ApiError`，并加 `SecurityFilterChainTest` 断言 `UNAUTHORIZED`/`TOKEN_EXPIRED`/`FORBIDDEN` 三种 code。
> 5. **JDK**：构建必须 `JAVA_HOME` 指向 JDK 21（系统默认可能是 25）。README 已写便携发现命令。
> 6. **计划文档自身滞后**：下面任务的若干代码片段（`TestRestTemplate`、Jackson 2 API）是 Boot 3.x 时代的写法；实现时已按上面 1–3 调整。后续计划作者请直接按 Boot 4.1 实际栈写。

## 全局约束（所有任务隐含遵守）

- **独立仓库**：本计划在新仓库 `fengyu-marketplace-server/` 执行，**不**在 FengYu 主程序仓库内。所有路径都以新仓库根为基准。
- **版本线**：新仓库独立版本，起点 `0.1.0-SNAPSHOT`（脚手架阶段；正式发布走 `1.0.0`，由 release 流程决定）。
- **Java 21 / Spring Boot 4.1.0**：与主程序 `pom.xml` 一致（`spring-boot.version=4.1.0`、`spring-framework.version=7.0.8`、`maven.compiler.source=21`）。
- **包根**：`fan.summer.marketplace`（注意是 `marketplace`，**不**是主程序的 `fan.summer.fengyu`）。
- **默认端口 24057、loopback 绑定**：`server.address=127.0.0.1`、`server.port=24057`。
- **TDD**：每个功能点先写失败测试，再最小实现使其通过。
- **提交规范**：conventional commits + emoji（`✨` feat / `🐛` fix / `♻️` refactor / `📝` docs / `⬆️` deps / `🔧` chore）。每个任务结束提交一次。
- **不删/不改本任务之外的文件**；匹配既有风格。
- **`./mvnw`** 优先于系统 Maven。

## 文件结构（本计划产出）

```
fengyu-marketplace-server/
├── pom.xml                                          # Task 1
├── mvnw / mvnw.cmd / .mvn/wrapper/                  # Task 1（Maven Wrapper）
├── .gitignore                                       # Task 1
├── README.md                                        # Task 8
├── src/main/java/fan/summer/marketplace/
│   ├── MarketplaceApplication.java                  # Task 2（main 类）
│   ├── config/
│   │   └── JacksonConfig.java                       # Task 5（统一 ObjectMapper）
│   ├── common/
│   │   ├── paths/PathSafety.java                    # Task 3（移植路径安全）
│   │   ├── http/BoundedHttp.java                    # Task 4（移植有界读取）
│   │   ├── version/Semver.java                      # Task 6（semver 比较）
│   │   └── error/
│   │       ├── ErrorCode.java                       # Task 7（枚举，§3.2 错误契约）
│   │       ├── ApiError.java                        # Task 7（响应体 record）
│   │       └── GlobalExceptionHandler.java          # Task 7（@RestControllerAdvice）
│   └── web/controller/HealthController.java         # Task 2（极简 /actuator/health 替代；actuator 也可，见 Task 2）
├── src/main/resources/
│   ├── application.yml                              # Task 2
│   └── .gitkeep（占位，Flyway 迁移目录在计划 1 起填）
└── src/test/java/fan/summer/marketplace/
    ├── MarketplaceApplicationTests.java             # Task 2（上下文加载冒烟）
    ├── common/paths/PathSafetyTest.java             # Task 3
    ├── common/http/BoundedHttpTest.java             # Task 4
    ├── common/version/SemverTest.java               # Task 6
    └── common/error/GlobalExceptionHandlerTest.java # Task 7
```

---

### Task 1: 初始化 Maven 工程骨架（pom + wrapper + git）

**Files:**
- Create: `pom.xml`
- Create: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`
- Create: `.gitignore`

**Interfaces:**
- Produces: 一个可 `mvn -v` 的 Maven 工程；后续任务都依赖 `pom.xml` 里声明的依赖与 Java 21 编译设定。

- [ ] **Step 1: 建立仓库目录并 `git init`**

```bash
mkdir -p fengyu-marketplace-server
cd fengyu-marketplace-server
git init
git checkout -b main
```

- [ ] **Step 2: 写 `pom.xml`**

Spring Boot 4.1.0 的正确引入方式是用 `spring-boot-starter-parent` 作 parent（它会带入 BOM、插件管理、Java 编译默认值）。注意 Boot 4.x 要求 `java.version` 属性设为 21。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.0</version>
        <relativePath/>
    </parent>

    <groupId>fan.summer.marketplace</groupId>
    <artifactId>fengyu-marketplace-server</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>fengyu-marketplace-server</name>
    <description>FengYu 插件市场服务（认证中心 + 发布门户 + 聚合发布）</description>

    <properties>
        <java.version>21</java.version>
        <spring-ai.version>2.0.0</spring-ai.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>

        <!-- 多 DB 驱动（与主程序 DbType 对齐） -->
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>runtime</scope>
        </dependency>
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
        <dependency>
            <groupId>org.hibernate.orm</groupId>
            <artifactId>hibernate-community-dialects</artifactId>
        </dependency>

        <!-- JSON Schema 校验（计划 2 用，先引入） -->
        <dependency>
            <groupId>com.networknt</groupId>
            <artifactId>json-schema-validator</artifactId>
            <version>1.5.6</version>
        </dependency>

        <!-- 测试 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

> 说明：`flyway-database-postgresql` 是 Flyway 10+ 的按 DB 拆分模块（H2/SQLite 的支持在 `flyway-core` 内置）。`hibernate-community-dialects` 提供 SQLite 方言（与主程序一致）。本计划暂不引入 `spring-ai-starter-mcp-client`——聚合阶段（计划 3）若需 MCP 客户端再加，避免脚手架期引入无关依赖。

- [ ] **Step 3: 生成 Maven Wrapper**

在仓库根执行（需本机有 Maven 或用 `mvn -N io.takari:maven:wrapper`）：

```bash
mvn -N wrapper:wrapper -Dmaven=3.9.9
```

这会生成 `mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties`。验证：

```bash
./mvnw -v
```

Expected: 打印 Maven 3.9.x 与 Java 21。

- [ ] **Step 4: 写 `.gitignore`**

```gitignore
# Maven
target/
.mvn/wrapper/maven-wrapper.jar

# IDE
.idea/
*.iml
.vscode/
*.sublime-*
.eclipse/
.classpath
.project
.settings/

# OS
.DS_Store
Thumbs.db

# 运行时产物
.market/
*.log
```

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "🔧 chore(scaffold): init maven project (Spring Boot 4.1 + Java 21 + Security + Flyway)"
```

---

### Task 2: main 类 + application.yml + 冒烟测试

**Files:**
- Create: `src/main/java/fan/summer/marketplace/MarketplaceApplication.java`
- Create: `src/main/resources/application.yml`
- Test: `src/test/java/fan/summer/marketplace/MarketplaceApplicationTests.java`

**Interfaces:**
- Consumes: Task 1 的 `pom.xml`。
- Produces: `MarketplaceApplication`（main 类，后续计划挂控制器/服务）；`application.yml`（默认配置，后续计划追加 `market.*` 段）。

- [ ] **Step 1: 写 main 类**

```java
package fan.summer.marketplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FengYu 插件市场服务入口。
 *
 * <p>三大支柱（认证中心 / 发布门户 / 聚合发布）在后续计划中逐步挂载；本类只负责引导 Spring 容器。
 * 默认监听 {@code 127.0.0.1:24057}（见 {@code application.yml}），与主程序一致的 loopback 起手姿态。
 */
@SpringBootApplication
public class MarketplaceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MarketplaceApplication.class, args);
    }
}
```

- [ ] **Step 2: 写 `application.yml`**

```yaml
server:
  address: 127.0.0.1          # loopback only（与主程序一致；公网部署由反向代理负责 TLS/暴露）
  port: 24057

spring:
  application:
    name: fengyu-marketplace-server
  datasource:
    url: jdbc:h2:file:./.market/db/market;MODE=PostgreSQL
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate      # schema 由 Flyway 管（迁移目录在计划 1 起填充；脚手架期无实体，validate 空过）
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true # 仓库空库时建基线

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: never      # health 端点不泄露细节（公网友好）

logging:
  level:
    fan.summer.marketplace: INFO
```

> `spring-boot-starter-actuator` 已在 Task 1 的 `pom.xml` 声明，所以 `/actuator/health` 与 `/actuator/info` 端点可用。

- [ ] **Step 3: 写 `SecurityConfig`（脚手架版，放行 health/info）**

Spring Security 7 在 classpath 上时默认拦截**所有**非 `permitAll` 端点——包括 `/actuator/health`。因此冒烟测试（Step 4）会先因 401 失败，本步先把这个最小安全配置就位，让 health 可达。**完整 JWT 过滤链 + 授权矩阵在计划 1 落地，届时替换本类。**

Create `src/main/java/fan/summer/marketplace/config/SecurityConfig.java`:

```java
package fan.summer.marketplace.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 脚手架期的最小安全配置：STATELESS + 放行 health/info，其余端点暂要求认证。
 * 计划 1 会替换为完整的认证/授权过滤链（JWT + 授权矩阵 + CSRF 精细化）。
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())            // 脚手架期；计划 1 精细化
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .anyRequest().authenticated())
            .httpBasic(b -> {});                      // 脚手架期占位认证；计划 1 换 JWT
        return http.build();
    }
}
```

- [ ] **Step 4: 写冒烟测试（上下文能加载 + actuator health 可达）**

```java
package fan.summer.marketplace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** 脚手架冒烟：上下文加载、/actuator/health 返回 200 + UP（依赖 SecurityConfig 放行 health）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarketplaceApplicationTests {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Test
    void contextLoads() {
        // 仅验证上下文启动无异常；无断言即通过。
    }

    @Test
    void healthEndpointIsUp() {
        ResponseEntity<String> resp = http.getForEntity(
                "http://127.0.0.1:" + port + "/actuator/health", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"status\":\"UP\"");
    }
}
```

- [ ] **Step 5: 跑测试，确认全绿**

Run: `./mvnw -q test`
Expected: `contextLoads` 与 `healthEndpointIsUp` 均 PASS。

- [ ] **Step 6: 手动起服务确认端口**

Run: `./mvnw -q spring-boot:run`（后台或另一终端），然后 `curl -s http://127.0.0.1:24057/actuator/health`。
Expected: `{"status":"UP"}`。停掉进程。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "✨ feat(scaffold): main app + application.yml + smoke test (health UP)"
```

---

### Task 3: `common/paths/PathSafety`（移植自主程序 `PluginContentPathSafety`）

**Files:**
- Create: `src/main/java/fan/summer/marketplace/common/paths/PathSafety.java`
- Test: `src/test/java/fan/summer/marketplace/common/paths/PathSafetyTest.java`

**Interfaces:**
- Consumes: 无（纯 JDK）。
- Produces: `PathSafety.slugify(String)` → `String`（把不可信名压成单个安全路径段）；`PathSafety.isInside(Path root, Path child)` → `boolean`（child 是否在 root 内，防穿越）。计划 2（制品存储）与计划 3（聚合缓存）依赖这俩。

**来源对照**：FengYu 主程序 `FengYu/src/main/java/fan/summer/fengyu/plugin/store/PluginContentPathSafety.java`（`slugify` 把非 `[a-z0-9-]` 的字符压成 `-`、折叠连续 `-`、去首尾 `-`；`isInside` 用 `root.resolve(child).normalize().startsWith(root.normalize())`）。本任务把它**移植**过来（包名改 `fan.summer.marketplace.common.paths`），逻辑一致。

- [ ] **Step 1: 写失败测试**

```java
package fan.summer.marketplace.common.paths;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class PathSafetyTest {

    @Test
    void slugifyLowersAndReplacesUnsafeChars() {
        assertThat(PathSafety.slugify("My Cool Plugin!")).isEqualTo("my-cool-plugin");
    }

    @Test
    void slugifyCollapsesAndTrimsDashes() {
        assertThat(PathSafety.slugify(" -- A !! B -- ")).isEqualTo("a-b");
    }

    @Test
    void slugifyRejectsBlankByFallback() {
        assertThat(PathSafety.slugify("   !!!   ")).isEqualTo("plugin");
    }

    @Test
    void isInsideAcceptsNestedChild() {
        Path root = Path.of("/var/market");
        assertThat(PathSafety.isInside(root, Path.of("/var/market/a/b"))).isTrue();
    }

    @Test
    void isInsideRejectsTraversal() {
        Path root = Path.of("/var/market");
        assertThat(PathSafety.isInside(root, Path.of("/var/market/../../etc/passwd"))).isFalse();
    }

    @Test
    void isInsideRejectsSibling() {
        Path root = Path.of("/var/market");
        assertThat(PathSafety.isInside(root, Path.of("/var/other"))).isFalse();
    }
}
```

- [ ] **Step 2: 跑测试，确认失败（类不存在）**

Run: `./mvnw -q -Dtest=PathSafetyTest test`
Expected: 编译失败，`PathSafety` 无法解析。

- [ ] **Step 3: 写实现**

```java
package fan.summer.marketplace.common.paths;

import java.nio.file.Path;

/**
 * 路径安全工具：把不可信字符串压成单个安全路径段，并防止路径穿越。
 *
 * <p>移植自 FengYu 主程序 {@code PluginContentPathSafety}（用于插件制品存储与聚合缓存键）。
 * 所有来自第三方（上传 manifest、上游 marketplace JSON）的名称在进入文件系统前必须经 {@link #slugify} 处理。
 */
public final class PathSafety {

    /** slugify 在输入全为非法字符时的兜底值。 */
    public static final String FALLBACK_SLUG = "plugin";

    private PathSafety() {}

    /**
     * 把任意字符串压成 {@code [a-z0-9-]+} 的单段：小写、非法字符→-、折叠连续-、去首尾-。
     * 结果为空时返回 {@link #FALLBACK_SLUG}，确保永远能用作路径段。
     */
    public static String slugify(String raw) {
        if (raw == null) return FALLBACK_SLUG;
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        boolean prevDash = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                prevDash = false;
            } else if (!prevDash) {
                sb.append('-');
                prevDash = true;
            }
        }
        // 去首尾 -
        int start = 0, end = sb.length();
        while (start < end && sb.charAt(start) == '-') start++;
        while (end > start && sb.charAt(end - 1) == '-') end--;
        String result = start >= end ? "" : sb.substring(start, end);
        return result.isEmpty() ? FALLBACK_SLUG : result;
    }

    /**
     * child 是否严格位于 root 之内（防 {@code ..} 穿越与符号链接外的绝对路径）。
     * 用 normalize 后的 startsWith 判定。
     */
    public static boolean isInside(Path root, Path child) {
        Path nRoot = root.toAbsolutePath().normalize();
        Path nChild = child.toAbsolutePath().normalize();
        return nChild.startsWith(nRoot) && !nChild.equals(nRoot);
    }
}
```

- [ ] **Step 4: 跑测试，确认全绿**

Run: `./mvnw -q -Dtest=PathSafetyTest test`
Expected: 6 个测试全 PASS。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "✨ feat(common): PathSafety (slugify + isInside) ported from main app"
```

---

### Task 4: `common/http/BoundedHttp`（移植自主程序，按 §6.1 防 OOM）

**Files:**
- Create: `src/main/java/fan/summer/marketplace/common/http/BoundedHttp.java`
- Test: `src/test/java/fan/summer/marketplace/common/http/BoundedHttpTest.java`

**Interfaces:**
- Consumes: 无。
- Produces: `BoundedHttp.readAtMost(InputStream, int)` → `String`（UTF-8，超限抛 `IOException`）；常量 `BoundedHttp.MAX_CATALOG_BYTES`（16 MiB）。计划 3（聚合上游）依赖它。

**来源对照**：FengYu 主程序 `FengYu/src/main/java/fan/summer/fengyu/plugin/store/BoundedHttp.java`（流式读、超 max 抛 IOException、16 MiB 硬上限）。逻辑原样移植，包名改、可见性从包级提为 `public`。

- [ ] **Step 1: 写失败测试**

```java
package fan.summer.marketplace.common.http;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedHttpTest {

    @Test
    void readsUnderLimit() throws IOException {
        String out = BoundedHttp.readAtMost(
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), 16);
        assertThat(out).isEqualTo("hello");
    }

    @Test
    void readsExactlyAtLimit() throws IOException {
        byte[] data = "abcdef".getBytes(StandardCharsets.UTF_8);
        String out = BoundedHttp.readAtMost(new ByteArrayInputStream(data), data.length);
        assertThat(out).isEqualTo("abcdef");
    }

    @Test
    void throwsWhenOverLimit() {
        byte[] data = new byte[11];
        assertThatThrownBy(() -> BoundedHttp.readAtMost(new ByteArrayInputStream(data), 10))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void maxCatalogBytesIs16MiB() {
        assertThat(BoundedHttp.MAX_CATALOG_BYTES).isEqualTo(16 * 1024 * 1024);
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `./mvnw -q -Dtest=BoundedHttpTest test`
Expected: 编译失败（`BoundedHttp` 不存在）。

- [ ] **Step 3: 写实现**

```java
package fan.summer.marketplace.common.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 有界 HTTP body 读取器：把第三方（上游 marketplace、GitHub raw）的响应控制在 {@link #MAX_CATALOG_BYTES}
 * 以内，防止恶意/损坏的响应 OOM。移植自 FengYu 主程序。
 */
public final class BoundedHttp {

    /** 上游 catalog 响应的硬上限（16 MiB——远超任何真实 marketplace JSON）。 */
    public static final int MAX_CATALOG_BYTES = 16 * 1024 * 1024;

    private BoundedHttp() {}

    /**
     * 从 {@code in} 读取至多 {@code max} 字节并按 UTF-8 解码；流若超出 {@code max} 抛 {@link IOException}。
     * 调用方负责关闭 {@code in}。
     */
    public static String readAtMost(InputStream in, int max) throws IOException {
        byte[] buf = new byte[Math.min(8192, max + 1)];
        byte[] out = new byte[Math.min(max, 8192)];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            if (total + n > max) {
                throw new IOException("Response body exceeds the " + max + "-byte limit");
            }
            if (total + n > out.length) {
                byte[] grown = new byte[Math.min(max, out.length * 2)];
                System.arraycopy(out, 0, grown, 0, total);
                out = grown;
            }
            System.arraycopy(buf, 0, out, total, n);
            total += n;
        }
        return new String(out, 0, total, StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 4: 跑测试，确认全绿**

Run: `./mvnw -q -Dtest=BoundedHttpTest test`
Expected: 4 个测试全 PASS。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "✨ feat(common): BoundedHttp (16 MiB bounded read) ported from main app"
```

---

### Task 5: `config/JacksonConfig`（统一 ObjectMapper）

**Files:**
- Create: `src/main/java/fan/summer/marketplace/config/JacksonConfig.java`
- Test: 无单独测试（被后续任务间接覆盖；本任务只确保 Bean 存在 + JavaTimeModule 注册）

**Interfaces:**
- Produces: 一个全局 `ObjectMapper` Bean（注册 `JavaTimeModule`、`FAIL_ON_UNKNOWN_PROPERTIES=false`、`WRITE_DATES_AS_TIMESTAMPS=false`）。后续所有 JSON 序列化（错误响应、catalog、清单文件）共享它，保证 `Instant` 等 Java 时间类型一致序列化为 ISO 字符串。

- [ ] **Step 1: 写实现**（无失败测试先行——这是一个无逻辑的装配 Bean，TDD 价值低；按 writing-plans 的「装配步骤折叠进任务」原则直接写）

```java
package fan.summer.marketplace.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全局 ObjectMapper：Java 8+ 时间类型（Instant/LocalDateTime）作为 ISO 字符串、
 * 忽略未知属性（向前兼容契约新增字段，见 spec §10 边界不变式 4）。
 */
@Configuration
public class JacksonConfig {

    @Bean
    ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }
}
```

> 注意：`jackson-datatype-jsr310`（`JavaTimeModule`）由 `spring-boot-starter-web` 的 Jackson 传递依赖带入，无需额外声明。若编译失败说明未传递，则在 `pom.xml` 显式加 `com.fasterxml.jackson.datatype:jackson-datatype-jsr310`。

- [ ] **Step 2: 跑全量测试，确认无回归**

Run: `./mvnw -q test`
Expected: 全 PASS。

- [ ] **Step 3: 提交**

```bash
git add -A
git commit -m "✨ feat(config): global ObjectMapper (JavaTimeModule + lenient unknown props)"
```

---

### Task 6: `common/version/Semver`（semver 比较，移植自主程序 `compareVersions`）

**Files:**
- Create: `src/main/java/fan/summer/marketplace/common/version/Semver.java`
- Test: `src/test/java/fan/summer/marketplace/common/version/SemverTest.java`

**Interfaces:**
- Consumes: 无。
- Produces: `Semver.compare(String a, String b)` → `int`（语义同主程序：丢弃 prerelease/build 后比前三段数字，相同则 0）；`Semver.isNewer(String candidate, String baseline)` → `boolean`。计划 2（active 版本切换）与计划 3（catalog 排序）依赖。

**来源对照**：FengYu 主程序 `FengYu/src/main/java/fan/summer/fengyu/plugin/market/PluginMarketplaceService.java:113-131` 的 `compareVersions`：split `[-+]` 取第 0 段、按 `.` 切前三段为 int 比较。

- [ ] **Step 1: 写失败测试**

```java
package fan.summer.marketplace.common.version;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SemverTest {

    @Test
    void equalVersions() {
        assertThat(Semver.compare("1.2.3", "1.2.3")).isZero();
    }

    @Test
    void higherMinorIsNewer() {
        assertThat(Semver.compare("1.3.0", "1.2.9")).isPositive();
        assertThat(Semver.isNewer("1.3.0", "1.2.9")).isTrue();
    }

    @Test
    void higherPatchIsNewer() {
        assertThat(Semver.compare("1.2.10", "1.2.9")).isPositive();
    }

    @Test
    void prereleaseIsStripped() {
        // 主程序语义：prerelease/build 后缀丢弃后比较。1.0.0-alpha 与 1.0.0 视为相等。
        assertThat(Semver.compare("1.0.0-alpha", "1.0.0")).isZero();
    }

    @Test
    void shorterVersionPadsWithZero() {
        assertThat(Semver.compare("1.2", "1.2.0")).isZero();
    }

    @Test
    void isNewerFalseForEqual() {
        assertThat(Semver.isNewer("2.0.0", "2.0.0")).isFalse();
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `./mvnw -q -Dtest=SemverTest test`
Expected: 编译失败（`Semver` 不存在）。

- [ ] **Step 3: 写实现**

```java
package fan.summer.marketplace.common.version;

/**
 * 语义版本比较（与 FengYu 主程序 {@code PluginMarketplaceService.compareVersions} 一致）。
 *
 * <p>语义：丢弃 prerelease/build 后缀（{@code -alpha}/{@code +build}），取前三段按整数比较；
 * 段数不足 3 按 0 补。这与主程序的升级检测逻辑对齐，确保本服务发布的 version 主程序能正确比较。
 */
public final class Semver {

    private Semver() {}

    /** a &gt; b 返回正数；a &lt; b 负数；相等 0。 */
    public static int compare(String a, String b) {
        int[] pa = core(a);
        int[] pb = core(b);
        for (int i = 0; i < 3; i++) {
            if (pa[i] != pb[i]) return Integer.compare(pa[i], pb[i]);
        }
        return 0;
    }

    /** candidate 是否严格新于 baseline。 */
    public static boolean isNewer(String candidate, String baseline) {
        return compare(candidate, baseline) > 0;
    }

    private static int[] core(String v) {
        // 丢弃 prerelease/build：按 - 或 + 切，取第 0 段
        String core = v.split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            out[i] = i < parts.length ? parseIntOrZero(parts[i]) : 0;
        }
        return out;
    }

    private static int parseIntOrZero(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
```

- [ ] **Step 4: 跑测试，确认全绿**

Run: `./mvnw -q -Dtest=SemverTest test`
Expected: 6 个测试全 PASS。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "✨ feat(common): Semver.compare/isNewer ported from main app"
```

---

### Task 7: `common/error/*` — 错误码枚举 + ApiError 响应体 + 全局异常处理器（§3.2 错误契约）

**Files:**
- Create: `src/main/java/fan/summer/marketplace/common/error/ErrorCode.java`
- Create: `src/main/java/fan/summer/marketplace/common/error/ApiError.java`
- Create: `src/main/java/fan/summer/marketplace/common/error/GlobalExceptionHandler.java`
- Test: `src/test/java/fan/summer/marketplace/common/error/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces:
  - `ErrorCode`（枚举，spec §3.2 错误响应契约的全部 code：`UNAUTHORIZED`、`TOKEN_EXPIRED`、`FORBIDDEN`、`USERNAME_TAKEN`、`EMAIL_TAKEN`、`INVALID_CREDENTIALS`、`ACCOUNT_LOCKED`，外加通用 `BAD_REQUEST`/`NOT_FOUND`/`CONFLICT`/`INTERNAL`）。
  - `ApiError`（record：`String code`、`String message`、`Long retryAfterSeconds`（nullable））。
  - `GlobalExceptionHandler`（`@RestControllerAdvice`，把异常翻译成 `ResponseEntity<ApiError>`，状态码由 ErrorCode 决定）。
- 后续计划 1（认证：401/422/423 等）、计划 2（上传：422/409）都抛带 ErrorCode 的异常，由本处理器统一输出。

- [ ] **Step 1: 写 `ErrorCode` 枚举**

```java
package fan.summer.marketplace.common.error;

import org.springframework.http.HttpStatus;

/**
 * 统一错误码（spec §3.2 错误响应契约 + 通用码）。
 * 每个 code 绑定一个 HTTP 状态码；{@link ApiException} 由 code 构造，{@link GlobalExceptionHandler} 据此输出。
 */
public enum ErrorCode {

    // —— 认证（§3.2）——
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "缺/坏 access token"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "access token 过期"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "角色不足"),
    INVALID_CREDENTIALS(HttpStatus.UNPROCESSABLE_ENTITY, "用户名或密码错误"),
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "账户因多次失败被锁定"),
    USERNAME_TAKEN(HttpStatus.CONFLICT, "用户名已被占用"),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "邮箱已被占用"),

    // —— 通用 ——
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "请求格式错误"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    CONFLICT(HttpStatus.CONFLICT, "冲突"),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "内部错误");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
```

- [ ] **Step 2: 写 `ApiException` 与 `ApiError`**

在 `common/error/` 新建 `ApiException.java`：

```java
package fan.summer.marketplace.common.error;

/**
 * 业务异常：带 {@link ErrorCode}（决定 HTTP 状态）+ 可选自定义 message + 可选 retryAfterSeconds。
 * 服务层抛出，由 {@link GlobalExceptionHandler} 翻译为 {@link ApiError}。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Long retryAfterSeconds;

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage(), null);
    }

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ErrorCode code, String message, Long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ErrorCode code() {
        return code;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
```

写 `ApiError.java`：

```java
package fan.summer.marketplace.common.error;

/**
 * 统一错误响应体（spec §3.2）：{@code { code, message, retryAfterSeconds? }}。
 * {@code retryAfterSeconds} 仅在 {@link ErrorCode#ACCOUNT_LOCKED} 等场景出现，其余为 null（序列化时省略）。
 */
public record ApiError(String code, String message, Long retryAfterSeconds) {
    public static ApiError of(ErrorCode code, String message, Long retryAfterSeconds) {
        return new ApiError(code.name(), message, retryAfterSeconds);
    }
}
```

- [ ] **Step 3: 写 `GlobalExceptionHandler`**

```java
package fan.summer.marketplace.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常→统一错误响应翻译。
 * 业务异常走 {@link ApiException}；未捕获异常兜底为 500 INTERNAL（不泄露堆栈到响应体）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest req) {
        log.debug("API error {} on {}: {}", ex.code(), req.getRequestURI(), ex.getMessage());
        ApiError body = ApiError.of(ex.code(), ex.getMessage(), ex.retryAfterSeconds());
        return ResponseEntity.status(ex.code().status()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {}", req.getRequestURI(), ex);
        ApiError body = ApiError.of(ErrorCode.INTERNAL, ErrorCode.INTERNAL.defaultMessage(), null);
        return ResponseEntity.status(ErrorCode.INTERNAL.status()).body(body);
    }
}
```

- [ ] **Step 4: 写失败测试**

```java
package fan.summer.marketplace.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {GlobalExceptionHandlerTest.TestCtrlConfig.class})
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @TestConfiguration
    static class TestCtrlConfig {
        @Bean
        RaisingController raisingController() {
            return new RaisingController();
        }
    }

    @RestController
    static class RaisingController {
        @GetMapping("/test/raise-api")
        public String raiseApi() {
            throw new ApiException(ErrorCode.ACCOUNT_LOCKED, "locked", 900L);
        }

        @GetMapping("/test/raise-bug")
        public String raiseBug() {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void apiExceptionMapsToApiErrorWithStatusAndRetryAfter() {
        ResponseEntity<Map> resp = http.getForEntity(
                "http://127.0.0.1:" + port + "/test/raise-api", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.LOCKED); // 423
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("ACCOUNT_LOCKED");
        assertThat(resp.getBody().get("retryAfterSeconds")).isEqualTo(900);
    }

    @Test
    void unexpectedMapsTo500InternalWithoutStackTrace() {
        ResponseEntity<Map> resp = http.getForEntity(
                "http://127.0.0.1:" + port + "/test/raise-bug", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("INTERNAL");
        assertThat(resp.getBody().toString()).doesNotContain("boom"); // 不泄露内部细节
    }
}
```

> 说明：Security 默认会拦截 `/test/**`（需认证）。本测试需要一个放行 `/test/**` 的测试态 Security 配置。新增 `src/test/java/fan/summer/marketplace/common/error/TestSecurityConfig.java`：

```java
package fan.summer.marketplace.common.error;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** 测试态：放行 /test/**，让 GlobalExceptionHandlerTest 直达控制器。 */
@TestConfiguration
class TestSecurityConfig {
    @Bean
    SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            .authorizeHttpRequests(a -> a.requestMatchers("/test/**", "/actuator/**").permitAll()
                .anyRequest().permitAll());
        return http.build();
    }
}
```

并在 `GlobalExceptionHandlerTest` 的 `@SpringBootTest(classes=...)` 里加上它：

把 `classes = {GlobalExceptionHandlerTest.TestCtrlConfig.class}` 改为
`classes = {GlobalExceptionHandlerTest.TestCtrlConfig.class, TestSecurityConfig.class}`。

- [ ] **Step 5: 跑测试，确认全绿**

Run: `./mvnw -q -Dtest=GlobalExceptionHandlerTest test`
Expected: 2 个测试 PASS。

- [ ] **Step 6: 跑全量测试，确认无回归**

Run: `./mvnw -q test`
Expected: 全 PASS。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "✨ feat(common): ErrorCode + ApiError + ApiException + global handler (§3.2 error contract)"
```

---

### Task 8: README + 提交脚手架里程碑

**Files:**
- Create: `README.md`

- [ ] **Step 1: 写 README（精简，说明仓库定位与起手命令）**

```markdown
# fengyu-marketplace-server

FengYu 插件市场服务（独立项目）：认证中心 + 发布门户 + 聚合发布。
设计文档见 FengYu 主程序仓库 `docs/superpowers/specs/2026-08-04-marketplace-server-design.md`。

> 当前阶段：**脚手架（计划 0）**。认证 / 发布 / 聚合 / 前端在后续计划逐步落地。

## 起手

```bash
./mvnw spring-boot:run        # 启动，监听 127.0.0.1:24057
./mvnw test                   # 跑测试
curl http://127.0.0.1:24057/actuator/health   # → {"status":"UP"}
```

## 技术栈

Spring Boot 4.1.0 · Java 21 · Spring Security 7 · Flyway · JPA · 多 DB（H2/MySQL/PostgreSQL/SQLite）。

## 模块

- `common/` — 共享基础（路径安全、有界 HTTP、semver、错误契约）
- `auth/` — 认证中心（计划 1）
- `publish/` — 发布门户（计划 2）
- `catalog/` — 聚合 + 发布（计划 3）
- `frontend/` — 自带前端（计划 4）
```

- [ ] **Step 2: 提交**

```bash
git add README.md
git commit -m "📝 docs: scaffold README"
```

- [ ] **Step 3: 打脚手架里程碑 tag**

```bash
git tag -a scaffold-v0.1 -m "脚手架 + common 基础完成（计划 0）"
```

---

## 完成判据

- `./mvnw test` 全绿。
- `./mvnw spring-boot:run` 起后 `curl http://127.0.0.1:24057/actuator/health` 返回 `{"status":"UP"}`。
- `common/` 四个组件（PathSafety / BoundedHttp / Semver / error 契约）均有测试覆盖。
- `git log` 有清晰的 conventional commits，`scaffold-v0.1` tag 已打。
- 仓库根包含：`pom.xml`、`mvnw`、`MarketplaceApplication`、`application.yml`、`SecurityConfig`（脚手架版）、`JacksonConfig`、`common/*`、`README.md`。

## 下一步

脚手架落地后，进入**计划 1（认证中心）**——把脚手架版 `SecurityConfig` 替换为完整 JWT 过滤链 + 授权矩阵，落地用户/角色/refresh-token/device 实体与 `/api/auth/*` 端点。计划 1 会引用本计划产出的真实签名（`ErrorCode`、`ApiException`、`ApiError`、`PathSafety`、`ObjectMapper`）。
