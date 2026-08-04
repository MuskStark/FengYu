# FengYu 插件市场服务 — 计划 2：发布门户（支柱 2）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已落地的认证中心（计划 1，main @ `a132ff6`，55/55 测试）上实现**发布门户**：作者上传 `.fyp` 制品包 → 服务端流式校验（schema/zip-slip/大小/worker-JAR/禁止条目，移植自主程序）→ 入库 → 管理员审核（批准/拒绝）→ 发布到 FengYu catalog 数据。**不**实现聚合/清单发布（计划 3）、前端（计划 4）。

**Architecture:** 新增 `publish/` 包：`validate/`（校验管线，移植 FengYu CLI/host 逻辑）、`storage/`（`ArtifactStore` FS 实现）、`entity/`（SubmissionEntity + ReviewRecordEntity + PluginEntity + PluginVersionEntity）、`repository/`、`service/`（SubmissionService + ReviewService + PublishService）、`controller/`（SubmissionController 作者 + ReviewController 管理员）。复用计划 1 的认证（`@PreAuthorize("hasRole('AUTHOR')")`/`ADMIN`、`MarketUserDetails`、`ErrorCode`/`ApiException`）与计划 0 的 `PathSafety`。

**Tech Stack:** Spring Boot 4.1.0、Spring Security 7（`@PreAuthorize` 方法级授权）、JPA/Hibernate 6（**严格 validate，新实体必须有 Flyway 迁移匹配**）、Jackson 3（`tools.jackson`，注解 `com.fasterxml.jackson.annotation`）、`com.networknt:json-schema-validator`（已在 pom）、Java 21 ZipFile/MessageDigest、JUnit 5 + Spring Boot Test + `RestTestClient.bindToServer()`。

## ⚠️ 计划 2 执行前须知（来自计划 0/1 评审，已确认的实际栈）

- **JDK 21**：`JAVA_HOME=/Users/phoebej/Library/Java/JavaVirtualMachines/azul-21.0.12/Contents/Home`（系统默认 25）。
- **Hibernate 6 严格 validate**：实体列必须与 Flyway 迁移列**完全匹配**——`CHAR(n)` 列要 `columnDefinition`；时间戳列 `Instant`；枚举 `@Enumerated(STRING)` + `VARCHAR(n)` 列。**先写 Flyway 迁移，再写实体，对齐每一列**（计划 1 Task 4 教训）。
- **Flyway**：用 `spring-boot-flyway` starter（计划 1 Task 2），新迁移 `V2__init_publish.sql`。
- **Jackson 3**：`tools.jackson.databind.*`；**`json-schema-validator:1.5.6` 内部用 Jackson 2 的 `com.fasterxml.jackson.databind.ObjectMapper`**——在校验代码里**按库自己的 API 用**（它接受 `JsonNode` 或字符串），**不要**把 Jackson 3 的 ObjectMapper 喂给它（类型不兼容）。计划 0 评审 #5：混合 Jackson 2+3 的风险点就在这里。`json-schema-validator` 的 `JsonSchemaFactory` 接受 `InputStream`/`String` 做校验，避开 ObjectMapper 类型冲突。
- **Spring Security 7 方法级授权**：`@PreAuthorize("hasRole('AUTHOR')")`/`hasRole('ADMIN')` 已随 `@EnableMethodSecurity`（计划 1 Task 8）启用。控制器方法加注解即可。
- **测试**：`RestTestClient.bindToServer()`（非 TestRestTemplate）；测试态 multipart 上传用 `MockMultipartFile` 或 `RestTestClient` 的 multipart builder。
- **multipart 配置**：`application.yml` 需配 `spring.servlet.multipart.max-file-size: 100MB` + `max-request-size: 100MB`（默认 1MB 太小）。

## 全局约束（所有任务隐含遵守）

- **仓库**：`fengyu-marketplace-server` 的 `main` 分支继续。
- **TDD**：每功能点 RED→GREEN；conventional commits + emoji。
- **不解压整个包到磁盘做校验**（§4.3 关键安全决策）：所有校验在 zip 流上（entry 名 + 大小 + 选中条目按需读字节）。
- **不删/不改本任务之外的文件**；匹配既有风格。
- **资源归属兜底**：作者只能操作自己的 submission（服务层校验 `submission.authorId == 当前用户`，防 IDOR——计划 1 §3.2 纪律）。

## 文件结构（本计划产出）

```
fengyu-marketplace-server/src/main/java/fan/summer/marketplace/
├── publish/
│   ├── validate/
│   │   ├── ArtifactValidationService.java     # Task 3（校验编排 + 报告）
│   │   ├── ArchiveInspector.java              # Task 2（zip-slip/大小/禁止条目/不解压读 entry）
│   │   ├── ManifestSchemaValidator.java       # Task 4（JSON Schema + 语义校验）
│   │   └── ValidationReport.java              # Task 3（校验结果 record）
│   ├── storage/
│   │   ├── ArtifactStore.java                 # Task 5（接口）
│   │   ├── FileSystemArtifactStore.java       # Task 5（FS 实现）
│   │   └── Sha256.java                        # Task 2（流式 SHA256，移植 OfficialPluginSeeder）
│   ├── entity/
│   │   ├── SubmissionEntity.java              # Task 6
│   │   ├── ReviewRecordEntity.java            # Task 6
│   │   ├── PluginEntity.java                  # Task 6
│   │   └── PluginVersionEntity.java           # Task 6
│   ├── repository/
│   │   ├── SubmissionRepository.java          # Task 6
│   │   ├── ReviewRecordRepository.java        # Task 6
│   │   ├── PluginRepository.java              # Task 6
│   │   └── PluginVersionRepository.java       # Task 6
│   ├── service/
│   │   ├── SubmissionService.java             # Task 7（上传 + 状态机）
│   │   ├── ReviewService.java                 # Task 8（审核决策）
│   │   └── PublishService.java                # Task 8（发布：写 Plugin/Version，切 active）
│   └── controller/
│       ├── SubmissionController.java          # Task 9（作者端点）
│       └── ReviewController.java              # Task 9（管理员端点）
├── src/main/resources/
│   ├── schemas/manifest.schema.json           # Task 1（从 toolchain/spec 复制）
│   └── db/migration/V2__init_publish.sql      # Task 6
└── src/test/.../publish/...
```

---

### Task 1: 复制 manifest schema + multipart 配置

**Files:**
- Create: `src/main/resources/schemas/manifest.schema.json`（从 FengYu `toolchain/spec/manifest.schema.json` 原样复制）
- Modify: `src/main/resources/application.yml`（加 multipart 配置 + `market.artifacts.root`）

**Interfaces:**
- Produces: `resources/schemas/manifest.schema.json`（Task 4 的 `ManifestSchemaValidator` 加载它）；`market.artifacts.root` 配置键（Task 5 用）。

- [ ] **Step 1: 复制 manifest schema**

```bash
cp /Users/phoebej/Develop/Java/FengYu/toolchain/spec/manifest.schema.json \
   /Users/phoebej/Develop/Java/fengyu-marketplace-server/src/main/resources/schemas/manifest.schema.json
```
验证内容一致（`diff` 应无输出）。

- [ ] **Step 2: 在 `application.yml` 追加 multipart + artifacts 配置**

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 100MB        # .fyp 压缩上限（§4.2）
      max-request-size: 100MB
market:
  artifacts:
    root: ./.market/artifacts     # Task 5 的 FS 存储根
```
（合并到既有 `market:` 段，不重复顶层键。）

- [ ] **Step 3: 全量测试不回归（55/55）**

Run（JDK 21）: `./mvnw test` → 55/55 PASS。

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/schemas/manifest.schema.json src/main/resources/application.yml
git commit -m "📝 feat(publish): copy manifest schema + multipart/artifacts config"
```

---

### Task 2: `Sha256` + `ArchiveInspector`（流式 SHA256 + zip 安全检查，移植）

**Files:**
- Create: `src/main/java/fan/summer/marketplace/publish/storage/Sha256.java`
- Create: `src/main/java/fan/summer/marketplace/publish/validate/ArchiveInspector.java`
- Test: `src/test/java/fan/summer/marketplace/publish/validate/ArchiveInspectorTest.java`

**Interfaces:**
- Produces:
  - `Sha256.hex(InputStream)` → `String`（流式 SHA-256，移植 `OfficialPluginSeeder.sha256Hex`）。
  - `Sha256.hex(byte[])` → `String`。
  - `ArchiveInspector.inspect(InputStream zipBytes)` → `ArchiveReport`（record：`List<EntryInfo> entries`、`long totalExpandedBytes`）；或抛 `ApiException(BAD_REQUEST, ...)` 当 zip-slip/绝对路径/重复条目/超 100MB 压缩/超 300MB 解压/禁止条目。
  - `ArchiveInspector.readEntry(InputStream zipBytes, String name, int maxBytes)` → `byte[]`（按名读单个 entry，不解压到磁盘）。
  - `EntryInfo`（record：`String name`、`long compressedSize`、`long uncompressedSize`、`boolean directory`）。
- 移植自：`archive.mjs`（zip-slip/大小/重复/禁止条目）+ `OfficialPluginSeeder.sha256Hex`（流式摘要）+ `PluginPackageService.readArchiveManifest`（按名读 entry）。
- 禁止条目清单（移植 `manifest.mjs:182-183`）：顶层 `.git`、`node_modules`、`target`、`src`、`settings.xml`、`.npmrc`、`.env`；symlink entry（`entry.getMethod()` 为 `ZipEntry` 且是符号链接——`ZipEntry` 无直接 symlink 标志，但可检查 entry 名含 `..` 或 Unix 权限位；v1 检查 zip-slip + 路径规范化已覆盖大部分，symlink 的额外检查用 `entry.getName()` 含 `\` 或绝对路径拒绝）。

- [ ] **Step 1: 写 `Sha256`**

```java
package fan.summer.marketplace.publish.storage;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 流式 SHA-256 摘要（移植自 FengYu OfficialPluginSeeder）。 */
public final class Sha256 {
    private Sha256() {}

    /** 流式读取 in，返回十六进制摘要。调用方关闭 in。 */
    public static String hex(InputStream in) throws IOException {
        MessageDigest md = newDigest();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
        return toHex(md.digest());
    }

    public static String hex(byte[] bytes) {
        return toHex(newDigest().digest(bytes));
    }

    private static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    private static String toHex(byte[] hash) {
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}
```

- [ ] **Step 2: 写 `ArchiveInspector`（移植 archive.mjs 的安全检查到 JDK ZipInputStream）**

```java
package fan.summer.marketplace.publish.validate;

import fan.summer.marketplace.common.error.ApiException;
import fan.summer.marketplace.common.error.ErrorCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 不解压到磁盘地检查 .fyp zip：zip-slip、绝对路径、重复条目、大小上限、禁止条目；
 * 按需按名读单个 entry。移植自 FengYu toolchain/cli/src/archive.mjs + host PluginPackageService。
 *
 * <p>关键安全决策（§4.3）：服务端永不把整个包解压到磁盘做校验——所有检查在 zip 流上。
 */
public final class ArchiveInspector {

    public static final long MAX_COMPRESSED_BYTES = 100L * 1024 * 1024;   // 100 MB
    public static final long MAX_EXPANDED_BYTES = 300L * 1024 * 1024;     // 300 MB
    public static final int MAX_MANIFEST_BYTES = 1 * 1024 * 1024;          // 1 MB

    /** 顶层禁止条目（manifest.mjs:182-183）：runtime 不该带的目录/敏感文件。按前缀匹配。 */
    private static final Set<String> FORBIDDEN_TOPLEVEL = Set.of(
            ".git", "node_modules", "target", "src", "settings.xml", ".npmrc", ".env");

    public record EntryInfo(String name, long compressedSize, long uncompressedSize, boolean directory) {}
    public record ArchiveReport(List<EntryInfo> entries, long totalExpandedBytes) {}

    /** 全量扫描 zip 流，返回条目报告；遇违规抛 BAD_REQUEST。 */
    public static ArchiveReport inspect(byte[] zipBytes) {
        List<EntryInfo> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long totalExpanded = 0;
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                validateName(name);
                if (entry.isDirectory()) {
                    entries.add(new EntryInfo(name, entry.getCompressedSize(), entry.getSize(), true));
                    drain(zip);
                    continue;
                }
                if (!seen.add(name)) {
                    throw new ApiException(ErrorCode.BAD_REQUEST, "重复的包内条目: " + name);
                }
                long uncompressed = entry.getSize() >= 0 ? entry.getSize() : 0;
                totalExpanded += uncompressed;
                if (uncompressed > MAX_EXPANDED_BYTES || totalExpanded > MAX_EXPANDED_BYTES) {
                    throw new ApiException(ErrorCode.BAD_REQUEST, "解压后包超过 300 MB 上限");
                }
                entries.add(new EntryInfo(name, entry.getCompressedSize(), uncompressed, false));
                drain(zip);
            }
        } catch (IOException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "无法读取 zip: " + e.getMessage());
        }
        return new ArchiveReport(List.copyOf(entries), totalExpanded);
    }

    /** 按名读取单个 entry 字节（用于读 manifest.json / 检查 ui.entry 存在）；未找到返回 null。 */
    public static byte[] readEntry(byte[] zipBytes, String name, int maxBytes) {
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (name.equals(entry.getName()) && !entry.isDirectory()) {
                    if (entry.getSize() > maxBytes) {
                        throw new ApiException(ErrorCode.BAD_REQUEST, "条目 " + name + " 超过 " + maxBytes + " 字节");
                    }
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n, total = 0;
                    while ((n = zip.read(buf)) >= 0) {
                        total += n;
                        if (total > maxBytes) {
                            throw new ApiException(ErrorCode.BAD_REQUEST, "条目 " + name + " 超过 " + maxBytes + " 字节");
                        }
                        out.write(buf, 0, n);
                    }
                    return out.toByteArray();
                }
            }
        } catch (IOException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "无法读取 zip: " + e.getMessage());
        }
        return null;
    }

    private static void validateName(String name) {
        if (name == null || name.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "zip 内有空条目名");
        }
        // 绝对路径（Unix 或 Windows 盘符）
        if (name.startsWith("/") || name.startsWith("\\") || name.matches("^[A-Za-z]:.*")) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "不安全的包内路径: " + name);
        }
        // 反斜杠（统一拒绝，仅允许正斜杠）
        if (name.contains("\\")) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "不安全的包内路径: " + name);
        }
        // zip-slip：规范化后与原名不一致，或含 ../
        String normalized = name.startsWith("./") ? name.substring(2) : name;
        if (!normalized.equals(name) && !name.startsWith("./")) {
            // 仅当不是 ./ 前缀导致的差异才报；./ 前缀容忍
        }
        if (normalized.contains("../") || normalized.startsWith("..")) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "zip-slip 路径穿越: " + name);
        }
        // 禁止的顶层条目
        String top = name.contains("/") ? name.substring(0, name.indexOf('/')) : name;
        if (FORBIDDEN_TOPLEVEL.contains(top)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "禁止的包内条目: " + name);
        }
    }

    private static void drain(InputStream in) throws IOException {
        byte[] buf = new byte[8192];
        while (in.read(buf) >= 0) { /* drain, 不保留字节 */ }
    }
}
```

> 实现者：上面 `validateName` 的规范化检查有点绕（archive.mjs 用 `path.posix.normalize`）。**关键不变式**：拒绝任何能逃出 zip 根的路径（含 `..` 段、绝对路径、反斜杠）。跑测试验证穿越用例被拒。

- [ ] **Step 3: 写失败测试**（合法包通过；zip-slip/绝对路径/重复/超大/禁止条目被拒；按名读 manifest 成功；未找到返 null）

夹具：从 FengYu `toolchain/spec/fixtures/valid-full.json` 在测试里**动态构造**一个最小合法 zip（`manifest.json` + `ui/index.html`），不依赖外部文件。用 `java.util.zip.ZipOutputStream` 写内存 zip。构造违规用例：往 zip 里塞 `../evil.txt`、`/abs.txt`、`C:\x`、重复 `manifest.json`、`.git/config`。

```java
package fan.summer.marketplace.publish.validate;

import fan.summer.marketplace.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchiveInspectorTest {

    private static byte[] zip(String... nameContents) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (int i = 0; i < nameContents.length; i += 2) {
                z.putNextEntry(new ZipEntry(nameContents[i]));
                z.write(nameContents[i + 1].getBytes());
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void inspectsValidArchive() throws Exception {
        byte[] z = zip("manifest.json", "{}", "ui/index.html", "<html>");
        ArchiveInspector.ArchiveReport report = ArchiveInspector.inspect(z);
        assertThat(report.entries()).hasSize(2);
        assertThat(report.entries()).anyMatch(e -> e.name().equals("manifest.json"));
    }

    @Test
    void rejectsZipSlip() {
        assertThatThrownBy(() -> ArchiveInspector.inspect(zip("../evil.txt", "x")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("zip-slip");
    }

    @Test
    void rejectsAbsolutePath() {
        assertThatThrownBy(() -> ArchiveInspector.inspect(zip("/etc/passwd", "x")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不安全");
    }

    @Test
    void rejectsForbiddenToplevel() {
        assertThatThrownBy(() -> ArchiveInspector.inspect(zip(".git/config", "x")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("禁止");
    }

    @Test
    void readEntryReturnsBytesWhenFound() throws Exception {
        byte[] z = zip("manifest.json", "{\"id\":\"x\"}", "ui/index.html", "<html>");
        byte[] manifest = ArchiveInspector.readEntry(z, "manifest.json", 1024);
        assertThat(manifest).isNotNull();
        assertThat(new String(manifest)).contains("\"id\":\"x\"");
    }

    @Test
    void readEntryReturnsNullWhenMissing() throws Exception {
        byte[] z = zip("a.txt", "x");
        assertThat(ArchiveInspector.readEntry(z, "manifest.json", 1024)).isNull();
    }
}
```

- [ ] **Step 4: RED→GREEN**

Run: `./mvnw -Dtest=ArchiveInspectorTest test` → 6/6。全量 61/61（55+6）。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "✨ feat(publish): Sha256 + ArchiveInspector (streaming SHA256 + zip-slip/size/forbidden, ported)"
```

---

### Task 3: `ArtifactValidationService`（校验编排 + 报告）

**Files:**
- Create: `src/main/java/fan/summer/marketplace/publish/validate/ValidationReport.java`
- Create: `src/main/java/fan/summer/marketplace/publish/validate/ArtifactValidationService.java`
- Test: `src/test/java/fan/summer/marketplace/publish/validate/ArtifactValidationServiceTest.java`

**Interfaces:**
- Consumes: `ArchiveInspector`、`ManifestSchemaValidator`（Task 4——但本任务先用 `ArtifactValidationService` 调一个暂时的 schema 校验占位，Task 4 实装时接线）。
- Produces: `ArtifactValidationService.validate(byte[] zipBytes)` → `ValidationReport`（record：`boolean passed`、`String sha256`、`List<String> errors`、`ManifestSummary manifest`）。编排：① Sha256 算摘要 → ② ArchiveInspector.inspect（zip 安全）→ ③ readEntry(manifest.json, ≤1MB) → ④ ManifestSchemaValidator 校验 manifest（Task 4）→ ⑤ 读 ui.entry 存在 → ⑥ backend 存在时检查 worker.jar + Main-Class（Task 4 接线或本任务先做 ⑤）。任一失败 → `passed=false` + errors 记录原因（不抛异常，收集所有错误）。

- [ ] **Step 1: 写 `ValidationReport` + `ManifestSummary`**

```java
package fan.summer.marketplace.publish.validate;

import java.util.List;

/** 校验结果：passed=false 时 errors 非空。 */
public record ValidationReport(boolean passed, String sha256, List<String> errors, ManifestSummary manifest) {
    public static ValidationReport fail(String sha256, List<String> errors) {
        return new ValidationReport(false, sha256, errors, null);
    }
    public static ValidationReport ok(String sha256, ManifestSummary manifest) {
        return new ValidationReport(true, sha256, List.of(), manifest);
    }
}

/** 从 manifest.json 提取的摘要（发布用：id/name/version/...）。 */
public record ManifestSummary(String id, String name, String description, String version,
                              String author, String icon, String category, String homepage,
                              String uiEntry, boolean official) {}
```

- [ ] **Step 2: 写 `ArtifactValidationService`**（编排；schema 校验部分先用占位，Task 4 实装）

```java
package fan.summer.marketplace.publish.validate;

import fan.summer.marketplace.common.error.ApiException;
import fan.summer.marketplace.common.error.ErrorCode;
import fan.summer.marketplace.publish.storage.Sha256;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 编排 .fyp 上传校验（§4.3）：SHA256 → zip 安全 → 读 manifest → schema/语义 → ui.entry 存在。
 * 任一步失败收集到 errors，最终 passed=false（不抛——上传端点据 passed 决定 422）。
 *
 * <p>关键安全决策：永不解压整个包到磁盘——所有校验在内存 zip 流上。
 */
@Service
public class ArtifactValidationService {

    private final ManifestSchemaValidator schemaValidator;

    public ArtifactValidationService(ManifestSchemaValidator schemaValidator) {
        this.schemaValidator = schemaValidator;
    }

    public ValidationReport validate(byte[] zipBytes) {
        List<String> errors = new ArrayList<>();
        // ① 压缩大小
        if (zipBytes.length > ArchiveInspector.MAX_COMPRESSED_BYTES) {
            errors.add("压缩包超过 100 MB 上限");
            return ValidationReport.fail("", errors); // 太大，后续无意义
        }
        // ② SHA256
        String sha256;
        try {
            sha256 = Sha256.hex(new ByteArrayInputStream(zipBytes));
        } catch (IOException e) {
            errors.add("无法计算 SHA256: " + e.getMessage());
            return ValidationReport.fail("", errors);
        }
        // ③ zip 安全检查（zip-slip/禁止条目/解压大小/重复）——inspect 内部抛 ApiException，转成 error
        try {
            ArchiveInspector.inspect(zipBytes);
        } catch (ApiException e) {
            errors.add(e.getMessage());
            return ValidationReport.fail(sha256, errors);
        }
        // ④ 读 manifest.json（≤1MB）
        byte[] manifestBytes = ArchiveInspector.readEntry(zipBytes, "manifest.json", ArchiveInspector.MAX_MANIFEST_BYTES);
        if (manifestBytes == null) {
            errors.add("包内缺少 manifest.json");
            return ValidationReport.fail(sha256, errors);
        }
        // ⑤ schema + 语义校验（Task 4 实装）
        List<String> schemaErrors = schemaValidator.validate(new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8));
        errors.addAll(schemaErrors);
        // ⑥ ui.entry 存在（需先解析 manifest 拿 entry 名——schemaValidator 解析后返回 summary）
        ManifestSummary summary = schemaErrors.isEmpty() ? schemaValidator.parseSummary(new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8)) : null;
        if (summary != null && summary.uiEntry() != null) {
            byte[] uiEntry = ArchiveInspector.readEntry(zipBytes, summary.uiEntry(), ArchiveInspector.MAX_MANIFEST_BYTES);
            if (uiEntry == null) {
                errors.add("manifest.ui.entry 指向的文件不存在: " + summary.uiEntry());
            }
        }
        if (!errors.isEmpty()) return ValidationReport.fail(sha256, errors);
        return ValidationReport.ok(sha256, summary);
    }
}
```

> **依赖 Task 4**：本任务的 `ManifestSchemaValidator` 引用在 Task 4 实装。**执行顺序**：可先做 Task 4（schema 校验器，纯函数无依赖）再做 Task 3（编排）。**实现者按 Task 4 → Task 3 顺序执行更顺**——本计划文档按「校验管线」逻辑顺序排，但 SDD 执行时可调换 3/4。**推荐**：先 Task 4（schema 校验器，独立可测），再 Task 3（编排接线）。

- [ ] **Step 3-5: 测试 + RED→GREEN + 提交**（合法包→passed=true+summary；缺 manifest→fail；坏 schema→fail；ui.entry 不存在→fail）

> 实现者：因 Task 3 依赖 Task 4，本任务的测试在 Task 4 完成后一起跑。

```bash
git commit -m "✨ feat(publish): ArtifactValidationService (orchestrates §4.3 validation pipeline)"
```

---

### Task 4: `ManifestSchemaValidator`（JSON Schema + 语义校验，移植）

**Files:**
- Create: `src/main/java/fan/summer/marketplace/publish/validate/ManifestSchemaValidator.java`
- Test: `src/test/java/fan/summer/marketplace/publish/validate/ManifestSchemaValidatorTest.java`

**Interfaces:**
- Produces:
  - `validate(String manifestJson)` → `List<String>` errors（空=通过）。用 `com.networknt:json-schema-validator` 加载 `resources/schemas/manifest.schema.json` 校验；再跑语义校验（移植 `PluginPackageService.validate`：id 正则、必填、semver、permission 白名单、aiTool 唯一性、inputSchema/outputSchema 是 object、timeout 1–600、effect 白名单、official id 前缀）。
  - `parseSummary(String manifestJson)` → `ManifestSummary`（提取发布用字段）。
- **Jackson 注意**：`json-schema-validator` 1.5.6 内部用 Jackson 2。**用它自己的 `JsonNode`/`JsonSchemaFactory`**：
  ```java
  JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
  JsonSchema schema = factory.getSchema(getClass().getResourceAsStream("/schemas/manifest.schema.json"));
  ValidationResult result = schema.validate(jsonNode);  // 或 schema.validate(manifestJson, InputFormat.JSON)
  ```
  **避坑**：不要把 Jackson 3 的 `tools.jackson.databind.JsonNode` 喂给它。用 `schema.validate(String, InputFormat.JSON)`（字符串入参，库内部解析）避开 ObjectMapper 类型冲突。语义校验里要解析 manifest 取字段时，用 `tools.jackson.databind.ObjectMapper`（我们的全局 bean）——**与 schema 库的解析隔离**（各自解析同一份字符串，互不干扰）。

- [ ] **Step 1: 写 `ManifestSchemaValidator`**（schema 校验 + 语义校验 + parseSummary）

实现者参照 `PluginPackageService.validate`（计划文档已附其源码）移植语义规则；schema 用 `json-schema-validator` 的字符串入参 API。

- [ ] **Step 2: 测试**（valid-full.json 通过；各 invalid-*.json fixture 失败并给具体错误；缺必填/坏 semver/未知 permission/重复 aiTool name 各报错）

夹具：从 FengYu `toolchain/spec/fixtures/` 复制 `valid-full.json`、`valid-ui-only.json`、`invalid-ai-schema.json`、`invalid-permission.json`、`invalid-timeout.json` 到本仓库 `src/test/resources/fixtures/`。

- [ ] **Step 3-5: RED→GREEN + 全量 + 提交**

```bash
git commit -m "✨ feat(publish): ManifestSchemaValidator (JSON Schema + semantic rules, ported from host)"
```

---

### Task 5: `ArtifactStore` 接口 + `FileSystemArtifactStore`（FS 实现）

**Files:**
- Create: `src/main/java/fan/summer/marketplace/publish/storage/ArtifactStore.java`
- Create: `src/main/java/fan/summer/marketplace/publish/storage/FileSystemArtifactStore.java`
- Test: `src/test/java/fan/summer/marketplace/publish/storage/FileSystemArtifactStoreTest.java`

**Interfaces:**
- Produces:
  - `ArtifactStore`（接口）：`save(String sha256, byte[] bytes)` → `String artifactId`（返回存储路径/键）；`InputStream openStream(String artifactId)`；`boolean exists(String sha256)`；`void delete(String artifactId)`。
  - `FileSystemArtifactStore`：`@Service`，根 `market.artifacts.root`，按 `<sha256-prefix(2)>/<sha256>.fyp` 存（content-addressable，幂等——同 sha 复用）。`exists(sha256)` 先查文件。所有路径走 `PathSafety.isInside` 防 traversal。

- [ ] **Step 1-5: 接口 + FS 实现 + 测试（save 幂等/读回一致/exists/delete/path-containment）+ 提交**

```bash
git commit -m "✨ feat(publish): ArtifactStore (FS content-addressable, path-contained, S3-ready interface)"
```

---

### Task 6: 实体 + repository + Flyway V2 迁移

**Files:**
- Create: `V2__init_publish.sql`（`submissions`、`review_records`、`plugins`、`plugin_versions` 四张表）
- Create: `SubmissionEntity`、`ReviewRecordEntity`、`PluginEntity`、`PluginVersionEntity` + 对应 repository

**Interfaces:**
- `SubmissionEntity`（表 `submissions`）：`id`、`authorId`、`sha256`、`originalFilename`、`manifestJson`、`validationReportJson`、`status`（枚举 DRAFT/PENDING_REVIEW/APPROVED/REJECTED/WITHDRAWN/PUBLISHED/UNPUBLISHED）、`pluginId`（发布后填）、`createdAt`、`updatedAt`。
- `ReviewRecordEntity`（表 `review_records`）：`id`、`submissionId`、`reviewerId`、`decision`（APPROVE/REJECT）、`note`、`createdAt`。
- `PluginEntity`（表 `plugins`）：`id`、`pluginId`（manifest id，unique）、`name`、`createdAt`、`updatedAt`。
- `PluginVersionEntity`（表 `plugin_versions`）：`id`、`pluginId`（FK plugins）、`version`（semver）、`submissionId`（FK）、`sha256`、`downloadUrl`、`manifestJson`、`active`（boolean）、`createdAt`。唯一约束 `(plugin_id, version)`。

- [ ] **Step 1: 写 V2 迁移**（4 张表，列类型与实体对齐——CHAR 不需要，全 VARCHAR/BIGINT/TIMESTAMP/BOOLEAN）

- [ ] **Step 2: 写 4 实体**（`@Enumerated(STRING)` 状态；`@ElementCollection` 不需要；外键关系按需 `@ManyToOne` 或只存 id）

- [ ] **Step 3-5: repository + 测试（save/find/状态枚举持久化）+ 提交**

```bash
git commit -m "✨ feat(publish): submission/review/plugin/version entities + Flyway V2 migration"
```

---

### Task 7: `SubmissionService`（上传 + 状态机）

**Files:**
- Create: `src/main/java/fan/summer/marketplace/publish/service/SubmissionService.java`
- Test: `src/test/java/fan/summer/marketplace/publish/service/SubmissionServiceTest.java`

**Interfaces:**
- `upload(long authorId, String filename, byte[] bytes)` → `SubmissionEntity`（status=DRAFT）：校验 → 通过则 save 到 ArtifactStore + 写 submission（含 validationReport）；不通过仍写 submission 记录但 status=DRAFT + report.passed=false（作者看到报告，可重传）。
- `submit(long authorId, long submissionId)` → PENDING_REVIEW（校验 report.passed 必须为 true，否则 BAD_REQUEST；状态机 DRAFT→PENDING_REVIEW）。
- `withdraw(long authorId, long submissionId)` → WITHDRAWN（作者归属校验）。
- `listMine(long authorId)`、`get(long authorId, long submissionId)`（作者视角，归属校验）。
- 状态机违规抛 `ApiException(BAD_REQUEST, "非法状态流转")`。

- [ ] **Step 1-5: 服务 + 测试（上传合法→DRAFT+passed；上传坏→DRAFT+passed=false；submit 需 passed；withdraw 归属；状态机违规）+ 提交**

```bash
git commit -m "✨ feat(publish): SubmissionService (upload + state machine DRAFT→PENDING→...)"
```

---

### Task 8: `ReviewService` + `PublishService`（审核 + 发布）

**Files:**
- Create: `ReviewService.java`、`PublishService.java`
- Test: `ReviewServiceTest.java`、`PublishServiceTest.java`

**Interfaces:**
- `ReviewService.approve(long reviewerId, long submissionId, String note)` → APPROVED + 写 ReviewRecord + 触发 `PublishService.publish(submission)`。
- `ReviewService.reject(long reviewerId, long submissionId, String note)` → REJECTED + 写 ReviewRecord。
- `ReviewService.listPending()`、`get(long submissionId)`（管理员视角）。
- `PublishService.publish(SubmissionEntity)` → 写 PluginEntity（如不存在）+ PluginVersionEntity(active=true) + 旧版本 active=false + submission.status=PUBLISHED + downloadUrl。事务内。

- [ ] **Step 1-5: 服务 + 测试（approve→PUBLISHED+active 切换；reject→REJECTED；重复版本 unique 冲突；旧版本 active 置 false）+ 提交**

```bash
git commit -m "✨ feat(publish): ReviewService + PublishService (approve/reject + active version switch)"
```

---

### Task 9: `SubmissionController`（作者）+ `ReviewController`（管理员）端点

**Files:**
- Create: `SubmissionController.java`、`ReviewController.java`
- Test: `SubmissionControllerTest.java`（端到端：作者上传→submit→管理员 approve→catalog 可见；归属/角色鉴权）

**端点**（§4.2 + §4.4）：
- 作者（`@PreAuthorize("hasRole('AUTHOR')")`）：`POST /api/submissions`（multipart）、`POST /{id}/submit`、`POST /{id}/withdraw`、`GET /api/submissions`、`GET /{id}`。
- 管理员（`@PreAuthorize("hasRole('ADMIN')")`）：`GET /api/admin/reviews`、`GET /{submissionId}`、`POST /{submissionId}/decision`、`POST /api/admin/plugins/{pluginId}/unpublish`。

- [ ] **Step 1-5: 控制器 + 端到端测试 + 提交**

```bash
git commit -m "✨ feat(publish): SubmissionController + ReviewController (author upload/admin review endpoints)"
```

---

## 完成判据

- `./mvnw test`（JDK 21）全绿，约 80+ 测试。
- 端到端：作者上传合法 `.fyp` → submit → 管理员 approve → `plugins`/`plugin_versions` 表有记录 + active 版本正确；上传坏包（zip-slip/坏 schema）→ 校验失败、不落存储、报告可查。
- 作者不能操作别人的 submission（归属校验）；非 AUTHOR 不能上传；非 ADMIN 不能审核。
- 制品存储 content-addressable（同 sha 幂等）、path-contained。
- `git log` 清晰 conventional commits。

## 下一步

发布门户落地后，进入**计划 3（聚合 + 发布）**——`CatalogAggregator` + 三 adapter + 三清单发布器 + Codex mcp 字符串解析。计划 3 消费计划 2 的已发布 `PluginVersionEntity`。
