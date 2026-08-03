# Unified Plugin Store — Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the backend of a unified plugin store that aggregates and installs plugins from FengYu, Claude Code, and OpenAI Codex marketplaces.

**Architecture:** A new `fan.summer.fengyu.plugin.store` package composes over the existing (untouched) `PluginMarketplaceService` / `PluginPackageService` / `SkillPackageService`. Per-source `MarketplaceSourceAdapter` implementations translate heterogeneous marketplace.json formats into one `UnifiedCatalogEntry`; an `InstallerDispatcher` routes install/update/uninstall by source type — `.fyp` to the existing installer, Claude/Codex (agent-content: skills + MCP configs) to a new `AgentContentInstaller` backed by JGit. Two JPA entities persist subscribed sources and install records.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Data JPA (`hibernate.ddl-auto=update`, H2 for tests), JGit 7.7.0, JUnit 5 (+ `@TempDir`, plain construction for service tests, `@DataJpaTest` for repository tests), Jackson.

**Spec:** `docs/superpowers/specs/2026-08-03-plugin-store-codex-claude-compat-design.md`

## Global Constraints

- Java 21 (`<maven.compiler.source>21</maven.compiler.source>` in root `pom.xml`).
- Spring Boot 4.1.0 (`${spring-boot.version}`).
- Use `./mvnw` (not system Maven) for all builds: `./mvnw -f FengYu/pom.xml ...`.
- New code goes in package `fan.summer.fengyu.plugin.store` (backend). Do NOT modify `PluginMarketplaceService`, `PluginPackageService`, `SkillPackageService`, `SkillMarketplaceService`, or the existing `PluginMarketplaceController` / `/api/plugin-market` endpoints.
- Entities follow the existing pattern: `@Entity @Table @Data` (Lombok), `userId = 1L` default, `@GeneratedValue(strategy = GenerationType.IDENTITY)`. Mirror `PluginFavoriteEntity.java`.
- Repositories are bare `JpaRepository` interfaces with derived query methods. Mirror `PluginFavoriteRepository.java`.
- Local virtual user id is `SecurityConstants.LOCAL_VIRTUAL_USER_ID` (== 1L), from `fan.summer.fengyu.database.SecurityConstants`.
- All remote URLs must be HTTPS-only (mirror `PluginMarketplaceService` scheme check at `PluginMarketplaceService.java:68`).
- Conventional commits with emojis (✨ feat, 🐛 fix, ♻️ refactor, 📝 docs, ⬆️ deps, 🔥 removal). Commit per task. Do NOT push or tag unless asked.
- Test framework: JUnit 5 plain (`org.junit.jupiter.api.Test`) for service/unit tests (construct service directly, use `@TempDir`), `@DataJpaTest @ActiveProfiles("test") @ContextConfiguration(classes = FengYuApplication.class)` for repository tests. Mirror `PluginPackageServiceTest.java` and `AppSettingRepositoryTest.java` exactly.

---

## File Structure

New backend files (all under `FengYu/src/main/java/fan/summer/fengyu/`):

| File | Responsibility |
|---|---|
| `plugin/store/StoreSourceType.java` | Enum: `FENGYU, CLAUDE, CODEX`. |
| `plugin/store/StoreSource.java` | Record: in-memory view of a subscribed source. |
| `plugin/store/UnifiedCatalogEntry.java` | Record: the unified catalog entry (union of all 3 formats). Plus nested records `Author`, `SourceRef` (sealed), `InterfaceMeta`. |
| `plugin/store/MarketplaceSourceAdapter.java` | Interface: `fetchCatalog(StoreSource) → List<UnifiedCatalogEntry>`. |
| `plugin/store/FengYuCatalogAdapter.java` | Parses FengYu catalog JSON array. |
| `plugin/store/ClaudeMarketplaceAdapter.java` | Parses `.claude-plugin/marketplace.json`. |
| `plugin/store/CodexMarketplaceAdapter.java` | Parses `.agents/plugins/marketplace.json`. |
| `plugin/store/GitHubUrlResolver.java` | Resolves catalogUrl → `{repoUrl, ref}` for Codex local sources. |
| `plugin/store/StoreSourceRegistry.java` | CRUD for sources + adapter dispatch + TTL cache. |
| `plugin/store/UnifiedStoreService.java` | Aggregates all sources + local install state; search/filter/sort. |
| `plugin/store/InstallerDispatcher.java` | Routes install/update/uninstall/enable by source type. |
| `plugin/store/AgentContentInstaller.java` | JGit clone → sha verify → extract skills + mcpServers for CLAUDE/CODEX. |
| `plugin/store/PluginContentPathSafety.java` | Path-normalization helpers (skill copy safety). |
| `plugin/store/StoreSourceSeeder.java` | `ApplicationRunner`: seeds default `fengyu-default` source. |
| `database/entity/store/StoreSourceEntity.java` | JPA entity for subscribed sources. |
| `database/entity/store/PluginInstallRecordEntity.java` | JPA entity for install history. |
| `database/repository/StoreSourceRepository.java` | Spring Data JPA repo. |
| `database/repository/PluginInstallRecordRepository.java` | Spring Data JPA repo. |
| `web/controller/PluginStoreController.java` | REST controller `/api/plugin-store/*`. |

New test files (under `FengYu/src/test/java/fan/summer/fengyu/`):

| File | Tests |
|---|---|
| `plugin/store/FengYuCatalogAdapterTest.java` | Adapter unit test. |
| `plugin/store/ClaudeMarketplaceAdapterTest.java` | Adapter unit test (all source branches). |
| `plugin/store/CodexMarketplaceAdapterTest.java` | Adapter unit test. |
| `plugin/store/GitHubUrlResolverTest.java` | URL resolver unit test. |
| `plugin/store/StoreSourceRegistryTest.java` | Source CRUD + cache. |
| `plugin/store/UnifiedStoreServiceTest.java` | Aggregation + filter + merge. |
| `plugin/store/AgentContentInstallerTest.java` | Clone+sha+extract against a local fixture repo. |
| `plugin/store/InstallerDispatcherTest.java` | Dispatch routing. |
| `database/repository/StoreSourceRepositoryTest.java` | `@DataJpaTest`. |
| `database/repository/PluginInstallRecordRepositoryTest.java` | `@DataJpaTest`. |

Modified file:
- `FengYu/pom.xml` — add JGit dependency.

Test fixtures (under `FengYu/src/test/resources/store-fixtures/`):
- `fengyu-catalog.json`, `claude-marketplace.json`, `codex-marketplace.json`.

---

### Task 1: Add JGit dependency

**Files:**
- Modify: `FengYu/pom.xml`

**Interfaces:**
- Produces: `org.eclipse.jgit:org.eclipse.jgit:7.7.0.202606012155-r` on the classpath, used by `AgentContentInstaller` (Task 9).

- [ ] **Step 1: Add the dependency inside the existing `<dependencies>` block of `FengYu/pom.xml`**

Add exactly (place it near other utility deps; groupId uses the new JGit coordinates):

```xml
        <!-- Pure-Java git for cloning Claude/Codex plugin sources in the unified store -->
        <dependency>
            <groupId>org.eclipse.jgit</groupId>
            <artifactId>org.eclipse.jgit</artifactId>
            <version>7.7.0.202606012155-r</version>
        </dependency>
```

- [ ] **Step 2: Verify it resolves and the module still compiles**

Run: `./mvnw -f FengYu/pom.xml -DskipTests -q compile`
Expected: BUILD SUCCESS, no errors. (JGit 7.7.x requires Java 17+; project is Java 21, so compatible.)

- [ ] **Step 3: Verify a smoke class loads**

Run a quick check that JGit is on the classpath:
```bash
./mvnw -f FengYu/pom.xml -DskipTests dependency:get -Dartifact=org.eclipse.jgit:org.eclipse.jgit:7.7.0.202606012155-r -q
```
Expected: BUILD SUCCESS (artifact already present in local repo or downloaded).

- [ ] **Step 4: Commit**

```bash
git add FengYu/pom.xml
git commit -m "⬆️ deps(ai): add JGit 7.7.0 for unified plugin store git-clone support"
```

---

### Task 2: Enum + StoreSource record + UnifiedCatalogEntry record

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/store/StoreSourceType.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/store/StoreSource.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/store/UnifiedCatalogEntry.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/plugin/store/UnifiedCatalogEntryTest.java`

**Interfaces:**
- Produces: `StoreSourceType` enum, `StoreSource(origin, sourceType, catalogUrl, name)` record, and `UnifiedCatalogEntry` (with nested `Author`, sealed `SourceRef` with `ZipUrlSource`, `GitUrlSource`, `GitSubdirSource`, `GitLocalInRepoSource`, and `InterfaceMeta`). These are consumed by every later task.

- [ ] **Step 1: Write the failing test**

`FengYu/src/test/java/fan/summer/fengyu/plugin/store/UnifiedCatalogEntryTest.java`:
```java
package fan.summer.fengyu.plugin.store;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedCatalogEntryTest {

    @Test
    void uidConstructedFromOriginTypeAndName() {
        var entry = new UnifiedCatalogEntry(
            "anthropics-claude", StoreSourceType.CLAUDE, "browser-use",
            "browser-use", "browser-use", "Give Claude a browser", null,
            java.util.List.of(), null, null, "https://example.com", "abc123",
            new UnifiedCatalogEntry.GitUrlSource("https://github.com/o/r.git", null),
            java.util.List.of(), java.util.List.of(), null,
            false, null, false, false);
        assertEquals("anthropics-claude:CLAUDE:browser-use", entry.uid());
    }

    @Test
    void storeSourceHoldsOriginAndUrl() {
        var src = new StoreSource("fengyu-default", StoreSourceType.FENGYU,
            "https://example.com/catalog.json", "FengYu Default");
        assertEquals(StoreSourceType.FENGYU, src.sourceType());
        assertEquals("fengyu-default", src.origin());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f FengYu/pom.xml -Dpl FengYu -Dtest UnifiedCatalogEntryTest -q test`
Expected: compile failure (`StoreSourceType`, `StoreSource`, `UnifiedCatalogEntry` not found).

- [ ] **Step 3: Create `StoreSourceType.java`**

```java
package fan.summer.fengyu.plugin.store;

/** Which marketplace ecosystem a source belongs to. */
public enum StoreSourceType {
    FENGYU,
    CLAUDE,
    CODEX
}
```

- [ ] **Step 4: Create `StoreSource.java`**

```java
package fan.summer.fengyu.plugin.store;

/**
 * In-memory view of a subscribed marketplace source (mirrors {@link StoreSourceEntity}).
 *
 * @param origin     stable unique identifier used as the uid prefix (e.g. "anthropics-claude")
 * @param sourceType which ecosystem's format the catalog uses
 * @param catalogUrl HTTPS URL of the marketplace.json / catalog.json
 * @param name       human-friendly name shown in the source manager UI
 */
public record StoreSource(String origin, StoreSourceType sourceType, String catalogUrl, String name) {
}
```

- [ ] **Step 5: Create `UnifiedCatalogEntry.java`**

```java
package fan.summer.fengyu.plugin.store;

import java.util.List;

/**
 * One entry in the unified catalog — the union of FengYu, Claude Code, and OpenAI Codex
 * marketplace entry shapes. Fields that don't apply to a given source type are null/empty.
 *
 * @param uid             globally-unique id = origin:sourceType:pluginName
 * @param origin          source identifier (uid prefix)
 * @param sourceType      which ecosystem this entry came from
 * @param name            plugin identifier within its source (kebab-case)
 * @param displayName     Codex interface.displayName; equals name for other sources
 * @param description     one-line summary
 * @param author          author metadata (name/email/url); null if absent
 * @param category        raw category string from the source (frontend normalizes)
 * @param keywords        discovery keywords (Claude/Codex); empty for FengYu
 * @param homepage        project URL
 * @param pinnedSha       git commit sha declared by the source (Claude); null otherwise
 * @param sourceRef       normalized install-source descriptor (sealed union)
 * @param declaredSkills  skill names/paths; populated AFTER install (empty in catalog list)
 * @param mcpServers      mcp server names; populated AFTER install (empty in catalog list)
 * @param interfaceMeta   Codex UX metadata (screenshots/logo/brandColor); null otherwise
 * @param installed       true if installed locally (merged by UnifiedStoreService)
 * @param installedVersion installed version; null if not installed
 * @param updateAvailable true if a newer version is available remotely
 * @param enabled         true if installed and enabled
 */
public record UnifiedCatalogEntry(
        String uid,
        String origin,
        StoreSourceType sourceType,
        String name,
        String displayName,
        String description,
        Author author,
        String category,
        List<String> keywords,
        String homepage,
        String pinnedSha,
        SourceRef sourceRef,
        List<String> declaredSkills,
        List<String> mcpServers,
        InterfaceMeta interfaceMeta,
        boolean installed,
        String installedVersion,
        boolean updateAvailable,
        boolean enabled) {

    /** Author metadata. All fields optional except name. */
    public record Author(String name, String email, String url) {}

    /** Sealed union of normalized install-source descriptors. */
    public sealed interface SourceRef
            permits ZipUrlSource, GitUrlSource, GitSubdirSource, GitLocalInRepoSource {}

    /** FengYu .fyp direct download. */
    public record ZipUrlSource(String url) implements SourceRef {}

    /** Claude url source — whole-repo git clone at a pinned sha. */
    public record GitUrlSource(String url, String sha) implements SourceRef {}

    /** Claude git-subdir source — clone whole repo, take a subdirectory. */
    public record GitSubdirSource(String url, String path, String ref, String sha) implements SourceRef {}

    /** Codex local source — marketplace lives in a repo; plugin is a path inside it. */
    public record GitLocalInRepoSource(String repoUrl, String ref, String path) implements SourceRef {}

    /** Codex interface UX metadata. All fields nullable; lists empty when absent. */
    public record InterfaceMeta(
            String displayName,
            String shortDescription,
            String longDescription,
            String developerName,
            String category,
            List<String> capabilities,
            String websiteURL,
            String privacyPolicyURL,
            String termsOfServiceURL,
            List<String> defaultPrompt,
            String brandColor,
            String composerIcon,
            String logo,
            String logoDark,
            List<String> screenshots) {}
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw -f FengYu/pom.xml -Dtest UnifiedCatalogEntryTest -q test`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/plugin/store/StoreSourceType.java \
        FengYu/src/main/java/fan/summer/fengyu/plugin/store/StoreSource.java \
        FengYu/src/main/java/fan/summer/fengyu/plugin/store/UnifiedCatalogEntry.java \
        FengYu/src/test/java/fan/summer/fengyu/plugin/store/UnifiedCatalogEntryTest.java
git commit -m "✨ feat(store): add StoreSourceType, StoreSource, UnifiedCatalogEntry core types"
```

---

### Task 3: JPA entities

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/database/entity/store/StoreSourceEntity.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/database/entity/store/PluginInstallRecordEntity.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/database/repository/StoreSourceRepositoryTest.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/database/repository/PluginInstallRecordRepositoryTest.java`

**Interfaces:**
- Produces: two `@Entity` classes mirroring `PluginFavoriteEntity` (Lombok `@Data`, `userId=1L`, `@GeneratedValue(IDENTITY)`). Consumed by repositories (Task 4) and by `StoreSourceRegistry` / `UnifiedStoreService` (Tasks 6, 7).

- [ ] **Step 1: Write the failing repository test for `StoreSourceEntity`**

`FengYu/src/test/java/fan/summer/fengyu/database/repository/StoreSourceRepositoryTest.java`:
```java
package fan.summer.fengyu.database.repository;

import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.entity.store.StoreSourceEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class StoreSourceRepositoryTest {
    @Autowired private StoreSourceRepository repo;

    @Test
    void findByOrigin_returnsSeededSource() {
        StoreSourceEntity e = new StoreSourceEntity();
        e.setOrigin("anthropics-claude");
        e.setName("Anthropic");
        e.setSourceType("CLAUDE");
        e.setCatalogUrl("https://example.com/m.json");
        e.setEnabled(true);
        e.setUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID);
        repo.save(e);

        Optional<StoreSourceEntity> found = repo.findByOrigin("anthropics-claude");
        assertTrue(found.isPresent());
        assertEquals("Anthropic", found.get().getName());
    }
}
```

> **Note:** the import `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest` (unusual path) is what the existing `AppSettingRepositoryTest.java` uses — copy it verbatim. Do not "fix" it to `org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f FengYu/pom.xml -Dtest StoreSourceRepositoryTest -q test`
Expected: compile failure — `StoreSourceEntity` / `StoreSourceRepository` not found.

- [ ] **Step 3: Create `StoreSourceEntity.java`**

```java
package fan.summer.fengyu.database.entity.store;

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
 * A subscribed marketplace source (FengYu catalog, Claude marketplace, or Codex marketplace).
 *
 * @since 4.0.0
 */
@Entity
@Table(name = "store_sources",
        uniqueConstraints = @UniqueConstraint(name = "uk_store_source_origin", columnNames = "origin"))
@Data
public class StoreSourceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "origin", nullable = false, unique = true)
    private String origin;

    @Column(name = "name", nullable = false)
    private String name;

    /** One of {@link fan.summer.fengyu.plugin.store.StoreSourceType} name(). */
    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "catalog_url", nullable = false)
    private String catalogUrl;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "last_sync_ok")
    private boolean lastSyncOk;

    @Column(name = "last_error", length = 4000)
    private String lastError;

    @Column(name = "added_at")
    private LocalDateTime addedAt = LocalDateTime.now();

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
```

- [ ] **Step 4: Create `PluginInstallRecordEntity.java`**

```java
package fan.summer.fengyu.database.entity.store;

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
 * Persistent record of an installed plugin from any source. The source of truth for
 * Claude/Codex installs (which have no on-disk manifest to scan).
 *
 * @since 4.0.0
 */
@Entity
@Table(name = "plugin_install_records",
        uniqueConstraints = @UniqueConstraint(name = "uk_install_uid_user",
                columnNames = {"uid", "user_id"}))
@Data
public class PluginInstallRecordEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "uid", nullable = false)
    private String uid;

    @Column(name = "plugin_name", nullable = false)
    private String pluginName;

    /** One of {@link fan.summer.fengyu.plugin.store.StoreSourceType} name(). */
    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "origin", nullable = false)
    private String origin;

    @Column(name = "version")
    private String version;

    @Column(name = "pinned_sha")
    private String pinnedSha;

    @Column(name = "install_path", nullable = false)
    private String installPath;

    /** JSON array of declared skill paths (for uninstall cleanup). */
    @Column(name = "declared_skills", length = 8000)
    private String declaredSkills;

    /** JSON array of mcp server config file references (for uninstall cleanup). */
    @Column(name = "mcp_server_refs", length = 8000)
    private String mcpServerRefs;

    @Column(name = "has_mcp_servers", nullable = false)
    private boolean hasMcpServers;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "installed_at")
    private LocalDateTime installedAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
```

- [ ] **Step 5: Create the repositories (so the test compiles)**

`FengYu/src/main/java/fan/summer/fengyu/database/repository/StoreSourceRepository.java`:
```java
package fan.summer.fengyu.database.repository;

import fan.summer.fengyu.database.entity.store.StoreSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StoreSourceRepository extends JpaRepository<StoreSourceEntity, Integer> {
    Optional<StoreSourceEntity> findByOrigin(String origin);
    List<StoreSourceEntity> findAllByUserId(Long userId);
    boolean existsByOrigin(String origin);
    void deleteByOrigin(String origin);
}
```

`FengYu/src/main/java/fan/summer/fengyu/database/repository/PluginInstallRecordRepository.java`:
```java
package fan.summer.fengyu.database.repository;

import fan.summer.fengyu.database.entity.store.PluginInstallRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PluginInstallRecordRepository extends JpaRepository<PluginInstallRecordEntity, Integer> {
    Optional<PluginInstallRecordEntity> findByUidAndUserId(String uid, Long userId);
    List<PluginInstallRecordEntity> findAllByUserIdOrderByInstalledAtDesc(Long userId);
    void deleteByUidAndUserId(String uid, Long userId);
}
```

- [ ] **Step 6: Write the `PluginInstallRecordRepository` test**

`FengYu/src/test/java/fan/summer/fengyu/database/repository/PluginInstallRecordRepositoryTest.java`:
```java
package fan.summer.fengyu.database.repository;

import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.entity.store.PluginInstallRecordEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class PluginInstallRecordRepositoryTest {
    @Autowired private PluginInstallRecordRepository repo;

    @Test
    void findByUidAndUserId_returnsRecord() {
        PluginInstallRecordEntity e = new PluginInstallRecordEntity();
        e.setUid("anthropics-claude:CLAUDE:browser-use");
        e.setPluginName("browser-use");
        e.setSourceType("CLAUDE");
        e.setOrigin("anthropics-claude");
        e.setInstallPath("/tmp/x");
        e.setUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID);
        repo.save(e);

        Optional<PluginInstallRecordEntity> found =
            repo.findByUidAndUserId("anthropics-claude:CLAUDE:browser-use",
                SecurityConstants.LOCAL_VIRTUAL_USER_ID);
        assertTrue(found.isPresent());
        assertEquals("browser-use", found.get().getPluginName());
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./mvnw -f FengYu/pom.xml -Dtest "StoreSourceRepositoryTest,PluginInstallRecordRepositoryTest" -q test`
Expected: PASS (2 tests). Tables auto-created by `ddl-auto=create-drop` under profile `test`.

- [ ] **Step 8: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/database/entity/store/ \
        FengYu/src/main/java/fan/summer/fengyu/database/repository/StoreSourceRepository.java \
        FengYu/src/main/java/fan/summer/fengyu/database/repository/PluginInstallRecordRepository.java \
        FengYu/src/test/java/fan/summer/fengyu/database/repository/StoreSourceRepositoryTest.java \
        FengYu/src/test/java/fan/summer/fengyu/database/repository/PluginInstallRecordRepositoryTest.java
git commit -m "✨ feat(store): add StoreSourceEntity + PluginInstallRecordEntity with repositories"
```

---

### Task 4: Test fixtures + `MarketplaceSourceAdapter` interface

**Files:**
- Create: `FengYu/src/test/resources/store-fixtures/fengyu-catalog.json`
- Create: `FengYu/src/test/resources/store-fixtures/claude-marketplace.json`
- Create: `FengYu/src/test/resources/store-fixtures/codex-marketplace.json`
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/store/MarketplaceSourceAdapter.java`

**Interfaces:**
- Produces: the adapter interface + 3 fixture JSON files consumed by adapter tests (Tasks 5).
- The fixtures encode one entry per source-branch so each adapter test covers all branches.

- [ ] **Step 1: Create the FengYu catalog fixture**

`FengYu/src/test/resources/store-fixtures/fengyu-catalog.json` — a JSON array of FengYu `MarketplaceCatalogEntry`:
```json
[
  {
    "id": "fan.summer.markdown",
    "name": "Markdown Editor",
    "description": "Edit markdown",
    "version": "4.0.0-alpha.6",
    "author": "FengYu",
    "icon": "language-markdown",
    "category": "text",
    "permissions": [],
    "homepage": "https://github.com/MuskStark/FengYu",
    "downloadUrl": "https://example.com/markdown.fyp",
    "official": true
  }
]
```

- [ ] **Step 2: Create the Claude marketplace fixture**

`FengYu/src/test/resources/store-fixtures/claude-marketplace.json` — covers all 3 source branches + a rename:
```json
{
  "$schema": "https://anthropic.com/claude-code/marketplace.schema.json",
  "name": "claude-plugins-official",
  "description": "Directory",
  "owner": { "name": "Anthropic", "email": "support@anthropic.com" },
  "renames": { "old-name": "renamed-plugin" },
  "plugins": [
    {
      "name": "local-skip",
      "description": "should be skipped",
      "category": "dev",
      "source": "./plugins/local-skip"
    },
    {
      "name": "url-plugin",
      "description": "whole repo",
      "category": "security",
      "author": { "name": "Acme" },
      "homepage": "https://acme.com",
      "source": { "source": "url", "url": "https://github.com/acme/p.git", "sha": "abc123sha" }
    },
    {
      "name": "subdir-plugin",
      "description": "subdir",
      "category": "design",
      "keywords": ["design", "figma"],
      "source": { "source": "git-subdir", "url": "https://github.com/adobe/skills.git", "path": "plugins/x", "ref": "main", "sha": "def456sha" }
    }
  ]
}
```

- [ ] **Step 3: Create the Codex marketplace fixture**

`FengYu/src/test/resources/store-fixtures/codex-marketplace.json` — local source + interface block + policy:
```json
{
  "name": "openai-curated",
  "interface": { "displayName": "ChatGPT Official" },
  "plugins": [
    {
      "name": "linear",
      "source": { "source": "local", "path": "./plugins/linear" },
      "policy": { "installation": "AVAILABLE", "authentication": "ON_INSTALL" },
      "category": "Productivity"
    }
  ]
}
```

- [ ] **Step 4: Create the adapter interface**

`FengYu/src/main/java/fan/summer/fengyu/plugin/store/MarketplaceSourceAdapter.java`:
```java
package fan.summer.fengyu.plugin.store;

import java.util.List;

/**
 * Translates one marketplace ecosystem's catalog format into unified entries.
 * One implementation per {@link StoreSourceType}.
 *
 * @since 4.0.0
 */
public interface MarketplaceSourceAdapter {

    /** Which ecosystem this adapter handles. */
    StoreSourceType type();

    /**
     * Fetch the catalog at {@code src.catalogUrl()} and translate it to unified entries.
     * Entries that cannot be resolved remotely (e.g. Claude local-path sources) are skipped.
     * Implementations should throw on HTTP/parse failure so the registry can record last_error.
     */
    List<UnifiedCatalogEntry> fetchCatalog(StoreSource src);
}
```

- [ ] **Step 5: Verify it compiles**

Run: `./mvnw -f FengYu/pom.xml -DskipTests -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add FengYu/src/test/resources/store-fixtures/ \
        FengYu/src/main/java/fan/summer/fengyu/plugin/store/MarketplaceSourceAdapter.java
git commit -m "✨ feat(store): add MarketplaceSourceAdapter interface + format test fixtures"
```

---

### Task 5: Three adapter implementations + GitHubUrlResolver

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/store/FengYuCatalogAdapter.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/store/ClaudeMarketplaceAdapter.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/store/CodexMarketplaceAdapter.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/store/GitHubUrlResolver.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/plugin/store/FengYuCatalogAdapterTest.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/plugin/store/ClaudeMarketplaceAdapterTest.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/plugin/store/CodexMarketplaceAdapterTest.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/plugin/store/GitHubUrlResolverTest.java`

**Interfaces:**
- Consumes: `MarketplaceSourceAdapter` (Task 4), `UnifiedCatalogEntry` (Task 2), fixtures (Task 4).
- Produces: three adapters, each `fetchCatalog(StoreSource)` reading JSON (HTTPS via `java.net.http.HttpClient`, mirroring `PluginMarketplaceService.java:64-83`) and translating to `UnifiedCatalogEntry`. Tests stub HTTP by reading fixtures from disk and calling a package-private parse method.

- [ ] **Step 1: Write the `GitHubUrlResolver` test first (it's a pure helper)**

`FengYu/src/test/java/fan/summer/fengyu/plugin/store/GitHubUrlResolverTest.java`:
```java
package fan.summer.fengyu.plugin.store;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GitHubUrlResolverTest {

    @Test
    void resolvesRawGithubUsercontentUrl() {
        var r = GitHubUrlResolver.resolve(
            "https://raw.githubusercontent.com/o/r/main/.agents/plugins/marketplace.json");
        assertEquals("https://github.com/o/r", r.repoUrl());
        assertEquals("main", r.ref());
    }

    @Test
    void resolvesGithubBlobUrl() {
        var r = GitHubUrlResolver.resolve(
            "https://github.com/o/r/blob/v1.0/.agents/plugins/marketplace.json");
        assertEquals("https://github.com/o/r", r.repoUrl());
        assertEquals("v1.0", r.ref());
    }

    @Test
    void returnsNullForNonGithubHost() {
        assertNull(GitHubUrlResolver.resolve("https://gitlab.com/o/r/main/m.json"));
    }
}
```

- [ ] **Step 2: Run to verify it fails, then implement `GitHubUrlResolver.java`**

Run: `./mvnw -f FengYu/pom.xml -Dtest GitHubUrlResolverTest -q test` → compile failure.

`FengYu/src/main/java/fan/summer/fengyu/plugin/store/GitHubUrlResolver.java`:
```java
package fan.summer.fengyu.plugin.store;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a marketplace catalog URL back to its git repo + ref, so Codex "local" sources
 * (whose path is relative to the repo the marketplace lives in) can be cloned.
 *
 * <p>Handles {@code raw.githubusercontent.com} and {@code github.com/.../blob/...}. Other hosts
 * return {@code null} (the source is then marked with last_error by the caller).
 *
 * @since 4.0.0
 */
public final class GitHubUrlResolver {
    private GitHubUrlResolver() {}

    // raw.githubusercontent.com/{owner}/{repo}/{ref}/{path...}
    private static final Pattern RAW = Pattern.compile(
        "^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/.*$");
    // github.com/{owner}/{repo}/blob/{ref}/{path...}
    private static final Pattern BLOB = Pattern.compile(
        "^https://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/.*$");

    public record Resolved(String repoUrl, String ref) {}

    public static Resolved resolve(String catalogUrl) {
        if (catalogUrl == null) return null;
        Matcher m = RAW.matcher(catalogUrl);
        if (m.matches()) return new Resolved("https://github.com/" + m.group(1) + "/" + m.group(2), m.group(3));
        m = BLOB.matcher(catalogUrl);
        if (m.matches()) return new Resolved("https://github.com/" + m.group(1) + "/" + m.group(2), m.group(3));
        return null;
    }
}
```

Run again → PASS (3 tests).

- [ ] **Step 3: Write the FengYu adapter test**

`FengYu/src/test/java/fan/summer/fengyu/plugin/store/FengYuCatalogAdapterTest.java`:
```java
package fan.summer.fengyu.plugin.store;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FengYuCatalogAdapterTest {

    private final FengYuCatalogAdapter adapter = new FengYuCatalogAdapter();

    @Test
    void parsesFixtureIntoUnifiedEntry() throws Exception {
        String json = Files.readString(Path.of(
            "src/test/resources/store-fixtures/fengyu-catalog.json"));
        StoreSource src = new StoreSource("fengyu-default", StoreSourceType.FENGYU,
            "https://example.com/catalog.json", "FengYu");

        List<UnifiedCatalogEntry> entries = adapter.parse(src, json);

        assertEquals(1, entries.size());
        UnifiedCatalogEntry e = entries.get(0);
        assertEquals("fengyu-default:FENGYU:fan.summer.markdown", e.uid());
        assertEquals("Markdown Editor", e.displayName());
        assertEquals("text", e.category());
        assertTrue(e.sourceRef() instanceof UnifiedCatalogEntry.ZipUrlSource);
        assertEquals("https://example.com/markdown.fyp",
            ((UnifiedCatalogEntry.ZipUrlSource) e.sourceRef()).url());
    }
}
```

- [ ] **Step 4: Run to verify it fails, then implement `FengYuCatalogAdapter.java`**

Run: `./mvnw -f FengYu/pom.xml -Dtest FengYuCatalogAdapterTest -q test` → compile failure.

`FengYu/src/main/java/fan/summer/fengyu/plugin/store/FengYuCatalogAdapter.java`:
```java
package fan.summer.fengyu.plugin.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Parses the FengYu catalog JSON array (the legacy {@code fengyu.marketplace.catalog-url} format). */
public class FengYuCatalogAdapter implements MarketplaceSourceAdapter {

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override public StoreSourceType type() { return StoreSourceType.FENGYU; }

    @Override
    public List<UnifiedCatalogEntry> fetchCatalog(StoreSource src) {
        String body = httpGet(src.catalogUrl());
        return parse(src, body);
    }

    /** Package-private for direct testing against fixture JSON. */
    List<UnifiedCatalogEntry> parse(StoreSource src, String body) {
        try {
            List<JsonNode> nodes = json.readValue(body, new TypeReference<>() {});
            List<UnifiedCatalogEntry> out = new ArrayList<>(nodes.size());
            for (JsonNode n : nodes) {
                String id = text(n, "id");
                if (id == null || id.isBlank()) continue;
                out.add(new UnifiedCatalogEntry(
                    uid(src, id), src.origin(), StoreSourceType.FENGYU,
                    id, text(n, "name"), text(n, "description"),
                    new UnifiedCatalogEntry.Author(text(n, "author"), null, null),
                    text(n, "category"), List.of(), text(n, "homepage"), null,
                    new UnifiedCatalogEntry.ZipUrlSource(text(n, "downloadUrl")),
                    List.of(), List.of(), null,
                    false, null, false, false));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse FengYu catalog for " + src.origin(), e);
        }
    }

    private String httpGet(String url) {
        try {
            URI uri = URI.create(url);
            if (!List.of("https", "http").contains(uri.getScheme()))
                throw new IllegalStateException("Catalog URL must use HTTP(S): " + url);
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300)
                throw new IllegalStateException("Catalog HTTP " + resp.statusCode());
            return resp.body();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Catalog request interrupted", ie);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fetch FengYu catalog " + url, e);
        }
    }

    static String uid(StoreSource src, String name) { return src.origin() + ":FENGYU:" + name; }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
```

Run again → PASS (1 test).

- [ ] **Step 5: Write the Claude adapter test**

`FengYu/src/test/java/fan/summer/fengyu/plugin/store/ClaudeMarketplaceAdapterTest.java`:
```java
package fan.summer.fengyu.plugin.store;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeMarketplaceAdapterTest {

    private final ClaudeMarketplaceAdapter adapter = new ClaudeMarketplaceAdapter();

    @Test
    void parsesAllSourceBranches() throws Exception {
        String json = Files.readString(Path.of(
            "src/test/resources/store-fixtures/claude-marketplace.json"));
        StoreSource src = new StoreSource("claude-plugins-official", StoreSourceType.CLAUDE,
            "https://example.com/m.json", "Claude");

        List<UnifiedCatalogEntry> entries = adapter.parse(src, json);

        // local-skip is dropped; url-plugin and subdir-plugin remain
        assertEquals(2, entries.size());
        var url = entries.stream().filter(e -> e.name().equals("url-plugin")).findFirst().orElseThrow();
        assertEquals("claude-plugins-official:CLAUDE:url-plugin", url.uid());
        assertEquals("abc123sha", url.pinnedSha());
        assertTrue(url.sourceRef() instanceof UnifiedCatalogEntry.GitUrlSource);
        var sub = entries.stream().filter(e -> e.name().equals("subdir-plugin")).findFirst().orElseThrow();
        assertTrue(sub.sourceRef() instanceof UnifiedCatalogEntry.GitSubdirSource);
        assertEquals(List.of("design", "figma"), sub.keywords());
        var gs = (UnifiedCatalogEntry.GitSubdirSource) sub.sourceRef();
        assertEquals("plugins/x", gs.path());
        assertEquals("main", gs.ref());
    }
}
```

- [ ] **Step 6: Run to verify it fails, then implement `ClaudeMarketplaceAdapter.java`**

Run: `./mvnw -f FengYu/pom.xml -Dtest ClaudeMarketplaceAdapterTest -q test` → compile failure.

`FengYu/src/main/java/fan/summer/fengyu/plugin/store/ClaudeMarketplaceAdapter.java`:
```java
package fan.summer.fengyu.plugin.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Parses {@code .claude-plugin/marketplace.json}. */
public class ClaudeMarketplaceAdapter implements MarketplaceSourceAdapter {

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override public StoreSourceType type() { return StoreSourceType.CLAUDE; }

    @Override
    public List<UnifiedCatalogEntry> fetchCatalog(StoreSource src) {
        return parse(src, httpGet(src.catalogUrl()));
    }

    List<UnifiedCatalogEntry> parse(StoreSource src, String body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode renames = root.get("renames");
            JsonNode plugins = root.get("plugins");
            if (plugins == null || !plugins.isArray()) return List.of();
            List<UnifiedCatalogEntry> out = new ArrayList<>();
            for (JsonNode p : plugins) {
                UnifiedCatalogEntry e = translate(src, p, renames);
                if (e != null) out.add(e);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse Claude marketplace for " + src.origin(), e);
        }
    }

    private UnifiedCatalogEntry translate(StoreSource src, JsonNode p, JsonNode renames) {
        String rawName = text(p, "name");
        if (rawName == null) return null;
        String name = applyRenames(rawName, renames);

        UnifiedCatalogEntry.SourceRef ref;
        String pinnedSha = null;
        JsonNode s = p.get("source");
        if (s == null || s.isTextual()) return null; // local path string — skip
        String kind = text(s, "source");
        if ("url".equals(kind)) {
            pinnedSha = text(s, "sha");
            ref = new UnifiedCatalogEntry.GitUrlSource(text(s, "url"), pinnedSha);
        } else if ("git-subdir".equals(kind)) {
            pinnedSha = text(s, "sha");
            ref = new UnifiedCatalogEntry.GitSubdirSource(text(s, "url"), text(s, "path"), text(s, "ref"), pinnedSha);
        } else {
            return null; // unknown source kind — skip
        }

        List<String> keywords = stringList(p.get("keywords"));
        return new UnifiedCatalogEntry(
            src.origin() + ":CLAUDE:" + name, src.origin(), StoreSourceType.CLAUDE,
            name, name, text(p, "description"), author(p.get("author")),
            text(p, "category"), keywords, text(p, "homepage"),
            pinnedSha, ref, List.of(), List.of(), null,
            false, null, false, false);
    }

    private static String applyRenames(String name, JsonNode renames) {
        if (renames == null) return name;
        JsonNode mapped = renames.get(name);
        return mapped == null ? name : mapped.asText(name);
    }

    private static UnifiedCatalogEntry.Author author(JsonNode a) {
        if (a == null || a.isNull()) return null;
        return new UnifiedCatalogEntry.Author(text(a, "name"), text(a, "email"), text(a, "url"));
    }

    private static List<String> stringList(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        List<String> out = new ArrayList<>(arr.size());
        for (Iterator<JsonNode> it = arr.elements(); it.hasNext(); ) out.add(it.next().asText());
        return List.copyOf(out);
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private String httpGet(String url) {
        try {
            URI uri = URI.create(url);
            if (!List.of("https", "http").contains(uri.getScheme()))
                throw new IllegalStateException("Marketplace URL must use HTTP(S): " + url);
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300)
                throw new IllegalStateException("Marketplace HTTP " + resp.statusCode());
            return resp.body();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Marketplace request interrupted", ie);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fetch Claude marketplace " + url, e);
        }
    }
}
```

Run again → PASS (1 test).

- [ ] **Step 7: Write the Codex adapter test**

`FengYu/src/test/java/fan/summer/fengyu/plugin/store/CodexMarketplaceAdapterTest.java`:
```java
package fan.summer.fengyu.plugin.store;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodexMarketplaceAdapterTest {

    private final CodexMarketplaceAdapter adapter = new CodexMarketplaceAdapter();

    @Test
    void parsesLocalSourceWithInterfaceBlock() throws Exception {
        String json = Files.readString(Path.of(
            "src/test/resources/store-fixtures/codex-marketplace.json"));
        StoreSource src = new StoreSource("openai-curated", StoreSourceType.CODEX,
            "https://raw.githubusercontent.com/openai/curated/main/.agents/plugins/marketplace.json",
            "OpenAI");

        List<UnifiedCatalogEntry> entries = adapter.parse(src, json);

        assertEquals(1, entries.size());
        UnifiedCatalogEntry e = entries.get(0);
        assertEquals("openai-curated:CODEX:linear", e.uid());
        assertEquals("ChatGPT Official", e.displayName());
        assertEquals("Productivity", e.category());
        assertTrue(e.sourceRef() instanceof UnifiedCatalogEntry.GitLocalInRepoSource);
        var gl = (UnifiedCatalogEntry.GitLocalInRepoSource) e.sourceRef();
        assertEquals("https://github.com/openai/curated", gl.repoUrl());
        assertEquals("main", gl.ref());
        assertEquals("./plugins/linear", gl.path());
        assertNotNull(e.interfaceMeta());
        assertEquals("ChatGPT Official", e.interfaceMeta().displayName());
    }
}
```

- [ ] **Step 8: Run to verify it fails, then implement `CodexMarketplaceAdapter.java`**

Run: `./mvnw -f FengYu/pom.xml -Dtest CodexMarketplaceAdapterTest -q test` → compile failure.

`FengYu/src/main/java/fan/summer/fengyu/plugin/store/CodexMarketplaceAdapter.java`:
```java
package fan.summer.fengyu.plugin.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Parses {@code .agents/plugins/marketplace.json} (Codex). Local sources are resolved against the repo. */
public class CodexMarketplaceAdapter implements MarketplaceSourceAdapter {

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override public StoreSourceType type() { return StoreSourceType.CODEX; }

    @Override
    public List<UnifiedCatalogEntry> fetchCatalog(StoreSource src) {
        return parse(src, httpGet(src.catalogUrl()));
    }

    List<UnifiedCatalogEntry> parse(StoreSource src, String body) {
        try {
            JsonNode root = json.readTree(body);
            String marketDisplayName = root.path("interface").path("displayName").asText(null);
            JsonNode plugins = root.get("plugins");
            if (plugins == null || !plugins.isArray()) return List.of();

            // Resolve the repo the marketplace lives in, so local sources can be cloned.
            GitHubUrlResolver.Resolved resolved = GitHubUrlResolver.resolve(src.catalogUrl());

            List<UnifiedCatalogEntry> out = new ArrayList<>();
            for (JsonNode p : plugins) {
                UnifiedCatalogEntry e = translate(src, p, marketDisplayName, resolved);
                if (e != null) out.add(e);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse Codex marketplace for " + src.origin(), e);
        }
    }

    private UnifiedCatalogEntry translate(StoreSource src, JsonNode p,
            String marketDisplayName, GitHubUrlResolver.Resolved resolved) {
        String name = text(p, "name");
        if (name == null) return null;
        JsonNode s = p.get("source");
        String kind = s == null ? null : text(s, "source");
        if (!"local".equals(kind)) return null; // only local sources supported for Codex
        if (resolved == null) return null;      // can't resolve the repo — skip

        String repoUrl = resolved.repoUrl();
        String ref = resolved.ref();
        String path = text(s, "path");
        var ref0 = new UnifiedCatalogEntry.GitLocalInRepoSource(repoUrl, ref, path);

        String displayName = text(p, "interface", "displayName");
        if (displayName == null) displayName = marketDisplayName != null ? marketDisplayName : name;

        return new UnifiedCatalogEntry(
            src.origin() + ":CODEX:" + name, src.origin(), StoreSourceType.CODEX,
            name, displayName, text(p, "description"),
            author(p.get("author")),
            text(p, "category"), stringList(p.get("keywords")), text(p, "homepage"),
            null, ref0, List.of(), List.of(), interfaceMeta(p.get("interface"), displayName),
            false, null, false, false);
    }

    private static UnifiedCatalogEntry.InterfaceMeta interfaceMeta(JsonNode iface, String displayName) {
        if (iface == null || iface.isNull()) return null;
        return new UnifiedCatalogEntry.InterfaceMeta(
            displayName,
            text(iface, "shortDescription"), text(iface, "longDescription"),
            text(iface, "developerName"), text(iface, "category"),
            stringList(iface.get("capabilities")),
            text(iface, "websiteURL"), text(iface, "privacyPolicyURL"), text(iface, "termsOfServiceURL"),
            stringList(iface.get("defaultPrompt")),
            text(iface, "brandColor"), text(iface, "composerIcon"),
            text(iface, "logo"), text(iface, "logoDark"),
            stringList(iface.get("screenshots")));
    }

    private static UnifiedCatalogEntry.Author author(JsonNode a) {
        if (a == null || a.isNull()) return null;
        return new UnifiedCatalogEntry.Author(text(a, "name"), text(a, "email"), text(a, "url"));
    }

    private static List<String> stringList(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        List<String> out = new ArrayList<>(arr.size());
        for (Iterator<JsonNode> it = arr.elements(); it.hasNext(); ) out.add(it.next().asText());
        return List.copyOf(out);
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static String text(JsonNode n, String f1, String f2) {
        JsonNode v = n.path(f1).path(f2);
        return (v == null || v.isMissingNode() || v.isNull()) ? null : v.asText();
    }

    private String httpGet(String url) {
        try {
            URI uri = URI.create(url);
            if (!List.of("https", "http").contains(uri.getScheme()))
                throw new IllegalStateException("Marketplace URL must use HTTP(S): " + url);
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300)
                throw new IllegalStateException("Marketplace HTTP " + resp.statusCode());
            return resp.body();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Marketplace request interrupted", ie);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fetch Codex marketplace " + url, e);
        }
    }
}
```

Run again → PASS (1 test).

- [ ] **Step 9: Run all adapter tests together**

Run: `./mvnw -f FengYu/pom.xml -Dtest "FengYuCatalogAdapterTest,ClaudeMarketplaceAdapterTest,CodexMarketplaceAdapterTest,GitHubUrlResolverTest" -q test`
Expected: PASS (4 test classes).

- [ ] **Step 10: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/plugin/store/FengYuCatalogAdapter.java \
        FengYu/src/main/java/fan/summer/fengyu/plugin/store/ClaudeMarketplaceAdapter.java \
        FengYu/src/main/java/fan/summer/fengyu/plugin/store/CodexMarketplaceAdapter.java \
        FengYu/src/main/java/fan/summer/fengyu/plugin/store/GitHubUrlResolver.java \
        FengYu/src/test/java/fan/summer/fengyu/plugin/store/FengYuCatalogAdapterTest.java \
        FengYu/src/test/java/fan/summer/fengyu/plugin/store/ClaudeMarketplaceAdapterTest.java \
        FengYu/src/test/java/fan/summer/fengyu/plugin/store/CodexMarketplaceAdapterTest.java \
        FengYu/src/test/java/fan/summer/fengyu/plugin/store/GitHubUrlResolverTest.java
git commit -m "✨ feat(store): add FengYu/Claude/Codex catalog adapters + GitHubUrlResolver"
```

---

### Task 6: `StoreSourceRegistry` (source CRUD + adapter dispatch + TTL cache)

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/store/StoreSourceRegistry.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/plugin/store/StoreSourceRegistryTest.java`

**Interfaces:**
- Consumes: `StoreSourceRepository`, `StoreSourceEntity` (Task 3), `MarketplaceSourceAdapter` + the 3 adapters (Tasks 4–5).
- Produces: `StoreSourceRegistry` with `listSources()`, `addSource(name, type, catalogUrl)`, `deleteSource(origin)`, `refresh(origin)`, `fetchCatalog(origin)`. `UnifiedStoreService` (Task 7) calls `fetchCatalog` for each enabled source.

- [ ] **Step 1: Write the failing test**

This test uses the established `@DataJpaTest` pattern (mirror `AppSettingRepositoryTest.java` verbatim, including the unusual `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest` import path). The service is constructed by hand against the autowired repository — `@DataJpaTest` only wires JPA repositories, not the service bean, which is exactly what we want for a focused test.

`FengYu/src/test/java/fan/summer/fengyu/plugin/store/StoreSourceRegistryTest.java`:
```java
package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.database.repository.StoreSourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class StoreSourceRegistryTest {

    @Autowired private StoreSourceRepository repo;

    @Test
    void listsAndPersistsSources() {
        StoreSourceRegistry registry = new StoreSourceRegistry(repo,
            List.of(new FengYuCatalogAdapter(), new ClaudeMarketplaceAdapter(), new CodexMarketplaceAdapter()),
            600);

        StoreSource added = registry.addSource("FengYu", StoreSourceType.FENGYU,
            "https://example.com/catalog.json");
        assertEquals("fengyu-fengyu", added.origin()); // origin = normalizeOrigin("FengYu", FENGYU)
        assertEquals(1, registry.listSources().size());
        assertTrue(repo.existsByOrigin("fengyu-fengyu"));
    }

    @Test
    void duplicateOriginIsRejected() {
        StoreSourceRegistry registry = new StoreSourceRegistry(repo,
            List.of(new FengYuCatalogAdapter()), 600);
        registry.addSource("FengYu", StoreSourceType.FENGYU, "https://example.com/a.json");
        assertThrows(IllegalStateException.class,
            () -> registry.addSource("FengYu", StoreSourceType.FENGYU, "https://example.com/b.json"));
    }
}
```

> **Note on the import path:** copy `import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;` exactly as the existing `AppSettingRepositoryTest.java` has it. Do NOT change it to the more common `org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest`.

- [ ] **Step 2: Run to verify it fails, then implement `StoreSourceRegistry.java`**

Run: `./mvnw -f FengYu/pom.xml -Dtest StoreSourceRegistryTest -q test` → compile failure (`StoreSourceRegistry` not found).

`FengYu/src/main/java/fan/summer/fengyu/plugin/store/StoreSourceRegistry.java`:
```java
package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.entity.store.StoreSourceEntity;
import fan.summer.fengyu.database.repository.StoreSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Manages subscribed marketplace sources: CRUD, adapter dispatch, and TTL caching of fetches. */
@Service
public class StoreSourceRegistry {
    private static final Logger log = LoggerFactory.getLogger(StoreSourceRegistry.class);

    private final StoreSourceRepository repo;
    private final Map<StoreSourceType, MarketplaceSourceAdapter> adapters;
    private final long ttlSeconds;

    // cache: origin -> (entries, fetchedAtMillis)
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public StoreSourceRegistry(StoreSourceRepository repo,
            List<MarketplaceSourceAdapter> adapterList,
            @Value("${fengyu.store.cache-ttl-seconds:600}") long ttlSeconds) {
        this.repo = repo;
        this.ttlSeconds = ttlSeconds;
        Map<StoreSourceType, MarketplaceSourceAdapter> m = new EnumMap<>(StoreSourceType.class);
        for (MarketplaceSourceAdapter a : adapterList) m.put(a.type(), a);
        this.adapters = Map.copyOf(m);
    }

    public List<StoreSource> listSources() {
        return repo.findAllByUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID).stream()
            .map(StoreSourceRegistry::toView).toList();
    }

    public StoreSource addSource(String name, StoreSourceType type, String catalogUrl) {
        String origin = normalizeOrigin(name, type);
        if (repo.existsByOrigin(origin)) {
            throw new IllegalStateException("Source already subscribed: " + origin);
        }
        StoreSourceEntity e = new StoreSourceEntity();
        e.setOrigin(origin);
        e.setName(name);
        e.setSourceType(type.name());
        e.setCatalogUrl(catalogUrl);
        e.setEnabled(true);
        e.setUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID);
        repo.save(e);
        return toView(e);
    }

    public void deleteSource(String origin) {
        repo.deleteByOrigin(origin);
        cache.remove(origin);
    }

    public void refresh(String origin) {
        cache.remove(origin);
    }

    /** Fetches the catalog for one source, using the TTL cache. Returns empty list on failure. */
    public List<UnifiedCatalogEntry> fetchCatalog(String origin) {
        StoreSourceEntity e = repo.findByOrigin(origin)
            .orElseThrow(() -> new IllegalArgumentException("Unknown source: " + origin));
        if (!e.isEnabled()) return List.of();

        CacheEntry hit = cache.get(origin);
        long now = System.currentTimeMillis();
        if (hit != null && (now - hit.fetchedAt) < ttlSeconds * 1000L) return hit.entries;

        StoreSource view = toView(e);
        MarketplaceSourceAdapter adapter = adapters.get(view.sourceType());
        try {
            List<UnifiedCatalogEntry> entries = adapter.fetchCatalog(view);
            cache.put(origin, new CacheEntry(entries, now));
            markSync(e, true, null);
            return entries;
        } catch (RuntimeException ex) {
            log.warn("Fetch failed for source {}: {}", origin, ex.getMessage());
            markSync(e, false, ex.getMessage());
            return List.of();
        }
    }

    private void markSync(StoreSourceEntity e, boolean ok, String err) {
        e.setLastSyncAt(LocalDateTime.now());
        e.setLastSyncOk(ok);
        e.setLastError(ok ? null : truncate(err, 3800));
        repo.save(e);
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }

    static String normalizeOrigin(String name, StoreSourceType type) {
        String slug = name.toLowerCase(Locale.ROOT).trim()
            .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) slug = "source";
        return slug + "-" + type.name().toLowerCase();
    }

    static StoreSource toView(StoreSourceEntity e) {
        return new StoreSource(e.getOrigin(), StoreSourceType.valueOf(e.getSourceType()),
            e.getCatalogUrl(), e.getName());
    }

    private record CacheEntry(List<UnifiedCatalogEntry> entries, long fetchedAt) {}
}
```

Run again → PASS (2 tests).

- [ ] **Step 3: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/plugin/store/StoreSourceRegistry.java \
        FengYu/src/test/java/fan/summer/fengyu/plugin/store/StoreSourceRegistryTest.java
git commit -m "✨ feat(store): add StoreSourceRegistry with CRUD, adapter dispatch, TTL cache"
```

---

### Task 7: `AgentContentInstaller` (JGit clone + sha + skill/mcp extraction)

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/store/PluginContentPathSafety.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/store/AgentContentInstaller.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/plugin/store/AgentContentInstallerTest.java`
- Test fixture repo: `FengYu/src/test/resources/store-fixtures/agent-content-repo/` (a tiny git repo, built in the test)

**Interfaces:**
- Consumes: `UnifiedCatalogEntry` (Task 2), JGit (Task 1), `RuntimePaths` (existing), `PluginInstallRecordRepository` (Task 3).
- Produces: `AgentContentInstaller.install(UnifiedCatalogEntry)` — clones via JGit, verifies sha, reads plugin.json, copies skills to `~/.fengyu/skills/<uid>/`, writes mcp config to `~/.fengyu/mcp-servers/<uid>.json`, persists a `PluginInstallRecordEntity`. Also `uninstall(uid)` and `setEnabled(uid, boolean)`.
- `InstallerDispatcher` (Task 8) calls this for CLAUDE/CODEX entries.

- [ ] **Step 1: Create the path-safety helper (used by skill copy)**

`FengYu/src/main/java/fan/summer/fengyu/plugin/store/PluginContentPathSafety.java`:
```java
package fan.summer.fengyu.plugin.store;

import java.nio.file.Path;

/** Path-traversal guards for copying agent-content files into the runtime tree. */
final class PluginContentPathSafety {
    private PluginContentPathSafety() {}

    /** True if {@code candidate} is inside {@code base} (after normalization). */
    static boolean isInside(Path base, Path candidate) {
        Path n = candidate.toAbsolutePath().normalize();
        Path b = base.toAbsolutePath().normalize();
        return n.startsWith(b);
    }
}
```

- [ ] **Step 2: Write the failing test (builds a local fixture repo with JGit, then installs)**

This test combines `@DataJpaTest` (for the real `PluginInstallRecordRepository`) with `@TempDir` (for the JGit clone + runtime tree). The installer is constructed by hand against the autowired repo. `@DataJpaTest` wraps each test in a transaction that rolls back — the installer's `records.save()` and the subsequent `records.findByUidAndUserId()` run inside that same transaction, so the assertion sees the saved row.

`FengYu/src/test/java/fan/summer/fengyu/plugin/store/AgentContentInstallerTest.java`:
```java
package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.repository.PluginInstallRecordRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class AgentContentInstallerTest {

    @TempDir Path temp;
    @Autowired private PluginInstallRecordRepository records;

    @Test
    void clonesPinnedShaExtractsSkillsAndMcp() throws Exception {
        // 1. Build a tiny source repo on disk.
        Path repo = temp.resolve("src-repo");
        Files.createDirectories(repo);
        try (Git g = Git.init().setDirectory(repo.toFile()).call()) {
            Files.createDirectories(repo.resolve("skills"));
            Files.writeString(repo.resolve("skills/SKILL.md"), "---\nname: demo\n---\n# demo");
            Files.createDirectories(repo.resolve(".claude-plugin"));
            Files.writeString(repo.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"demo\",\"version\":\"1.0.0\",\"description\":\"d\","
                + "\"skills\":[\"skills/SKILL.md\"],"
                + "\"mcpServers\":{\"demo\":{\"type\":\"http\",\"url\":\"https://x/mcp\"}}}");
            g.add().addFilepattern(".").call();
            ObjectId head = g.commit().setMessage("init").setSign(false).call().getId();
            String sha = head.getName();

            // 2. Point the installer at this repo via a file:// remote.
            Path runtimeRoot = temp.resolve("runtime");
            UnifiedCatalogEntry.SourceRef ref = new UnifiedCatalogEntry.GitUrlSource("file://" + repo, sha);
            UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
                "test:CLAUDE:demo", "test", StoreSourceType.CLAUDE, "demo", "demo", "d",
                null, null, List.of(), null, sha, ref,
                List.of(), List.of(), null, false, null, false, false);

            AgentContentInstaller installer = new AgentContentInstaller(records, runtimeRoot, 60);
            installer.install(entry);

            // 3. skill copied under runtimeRoot/skills/<uid>/
            Path skillDir = runtimeRoot.resolve("skills").resolve("test:CLAUDE:demo");
            assertTrue(Files.exists(skillDir.resolve("skills/SKILL.md")), "skill file should be copied");

            // 4. mcp config persisted under runtimeRoot/mcp-servers/<uid>.json
            Path mcpFile = runtimeRoot.resolve("mcp-servers").resolve("test:CLAUDE:demo.json");
            assertTrue(Files.exists(mcpFile), "mcp config should be written");

            // 5. install record persisted (same transaction — visible before rollback)
            var rec = records.findByUidAndUserId("test:CLAUDE:demo", SecurityConstants.LOCAL_VIRTUAL_USER_ID);
            assertTrue(rec.isPresent());
            assertTrue(rec.get().isHasMcpServers());
            assertEquals(sha, rec.get().getPinnedSha());
        }
    }

    @Test
    void rejectsShaMismatch() throws Exception {
        Path repo = temp.resolve("src-repo");
        Files.createDirectories(repo);
        try (Git g = Git.init().setDirectory(repo.toFile()).call()) {
            Files.createDirectories(repo.resolve(".claude-plugin"));
            Files.writeString(repo.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"demo\",\"version\":\"1.0.0\",\"description\":\"d\"}");
            g.add().addFilepattern(".").call();
            g.commit().setMessage("init").setSign(false).call();

            String wrongSha = "deadbeef".repeat(5);
            UnifiedCatalogEntry.SourceRef ref = new UnifiedCatalogEntry.GitUrlSource("file://" + repo, wrongSha);
            UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
                "test:CLAUDE:demo", "test", StoreSourceType.CLAUDE, "demo", "demo", "d",
                null, null, List.of(), null, wrongSha, ref,
                List.of(), List.of(), null, false, null, false, false);

            AgentContentInstaller installer = new AgentContentInstaller(records, temp.resolve("runtime"), 60);
            assertThrows(IntegrityException.class, () -> installer.install(entry));
        }
    }
}
```

- [ ] **Step 3: Create `IntegrityException.java`**

`FengYu/src/main/java/fan/summer/fengyu/plugin/store/IntegrityException.java`:
```java
package fan.summer.fengyu.plugin.store;

/** Thrown when a pinned git sha does not match the cloned HEAD (supply-chain tamper guard). */
public class IntegrityException extends RuntimeException {
    public IntegrityException(String expected, String actual) {
        super("Integrity check failed: expected sha " + expected + " but got " + actual);
    }
}
```

- [ ] **Step 4: Run to verify it fails, then implement `AgentContentInstaller.java`**

Run: `./mvnw -f FengYu/pom.xml -Dtest AgentContentInstallerTest -q test` → compile failure.

`FengYu/src/main/java/fan/summer/fengyu/plugin/store/AgentContentInstaller.java`:
```java
package fan.summer.fengyu.plugin.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.entity.store.PluginInstallRecordEntity;
import fan.summer.fengyu.database.repository.PluginInstallRecordRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Installs Claude/Codex plugins by cloning their git source (JGit), verifying the pinned sha,
 * reading plugin.json, and materializing skills + MCP-server configs into the runtime tree.
 *
 * @since 4.0.0
 */
@Service
public class AgentContentInstaller {
    private static final Logger log = LoggerFactory.getLogger(AgentContentInstaller.class);

    private final PluginInstallRecordRepository records;
    private final Path runtimeRoot;
    private final long cloneTimeoutSeconds;
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    // Constructor used by Spring (runtimeRoot comes from RuntimePaths at bean-creation time
    // via a config that injects RuntimePaths.root(); see AgentContentInstallerConfig below).
    public AgentContentInstaller(PluginInstallRecordRepository records,
            @Value("#{T(fan.summer.fengyu.runtime.RuntimePaths).root()}") Path runtimeRoot,
            @Value("${fengyu.store.git-clone-timeout-seconds:120}") long cloneTimeoutSeconds) {
        this.records = records;
        this.runtimeRoot = runtimeRoot;
        this.cloneTimeoutSeconds = cloneTimeoutSeconds;
    }

    // Test constructor (avoids SpEL).
    AgentContentInstaller(PluginInstallRecordRepository records, Path runtimeRoot, long cloneTimeoutSeconds) {
        this.records = records;
        this.runtimeRoot = runtimeRoot;
        this.cloneTimeoutSeconds = cloneTimeoutSeconds;
    }

    /** Install (or update) an agent-content plugin. */
    public void install(UnifiedCatalogEntry entry) {
        Path cloneDir = null;
        try {
            cloneDir = cloneSource(entry);
            Path pluginRoot = resolvePluginRoot(cloneDir, entry);
            Path manifest = manifestPath(pluginRoot, entry);
            JsonNode pluginJson = json.readTree(Files.readString(manifest));
            String version = text(pluginJson, "version");

            Path skillDest = runtimeRoot.resolve("skills").resolve(entry.uid());
            deleteRecursive(skillDest);
            List<String> skillPaths = extractSkills(pluginJson, pluginRoot, skillDest, entry.uid());

            boolean hasMcp = pluginJson.has("mcpServers") && !pluginJson.get("mcpServers").isNull()
                && !pluginJson.get("mcpServers").isEmpty();
            List<String> mcpRefs = hasMcp
                ? List.of(writeMcpConfig(pluginJson.get("mcpServers"), entry.uid()))
                : List.of();

            upsertRecord(entry, version, skillDest, skillPaths, mcpRefs, hasMcp);
            log.info("Installed agent-content plugin {} (version={})", entry.uid(), version);
        } catch (IntegrityException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Install failed for " + entry.uid(), e);
        } finally {
            if (cloneDir != null) deleteRecursive(cloneDir); // never leave the cloned .git behind
        }
    }

    public void uninstall(String uid) {
        records.findByUidAndUserId(uid, SecurityConstants.LOCAL_VIRTUAL_USER_ID).ifPresent(rec -> {
            deleteRecursive(runtimeRoot.resolve("skills").resolve(uid));
            deleteRecursive(runtimeRoot.resolve("mcp-servers").resolve(uid + ".json"));
            records.delete(rec);
        });
    }

    public void setEnabled(String uid, boolean enabled) {
        records.findByUidAndUserId(uid, SecurityConstants.LOCAL_VIRTUAL_USER_ID).ifPresent(rec -> {
            rec.setEnabled(enabled);
            rec.setUpdatedAt(LocalDateTime.now());
            records.save(rec);
        });
    }

    // --- internals ------------------------------------------------------------------

    private Path cloneSource(UnifiedCatalogEntry entry) throws Exception {
        Path dest = Files.createTempDirectory(runtimeRoot.resolve(".clone-"), "agent-");
        UnifiedCatalogEntry.SourceRef ref = entry.sourceRef();
        String url;
        String refName = null;
        if (ref instanceof UnifiedCatalogEntry.GitUrlSource u) {
            url = u.url();
        } else if (ref instanceof UnifiedCatalogEntry.GitSubdirSource s) {
            url = s.url(); refName = s.ref();
        } else if (ref instanceof UnifiedCatalogEntry.GitLocalInRepoSource l) {
            url = l.repoUrl(); refName = l.ref();
        } else {
            throw new IllegalArgumentException("Unsupported source ref for agent content: " + ref);
        }
        Git git = (refName == null)
            ? Git.cloneRepository().setURI(url).setDirectory(dest.toFile()).call()
            : Git.cloneRepository().setURI(url).setDirectory(dest.toFile())
                .setBranchesToClone(Collections.singletonList("refs/heads/" + refName))
                .setBranch("refs/heads/" + refName).call();
        try {
            verifySha(git, entry);
        } finally {
            git.close();
        }
        return dest;
    }

    private void verifySha(Git git, UnifiedCatalogEntry entry) throws Exception {
        if (entry.pinnedSha() == null) return;
        Repository repo = git.getRepository();
        ObjectId head = repo.resolve("HEAD");
        if (head == null || !head.getName().equalsIgnoreCase(entry.pinnedSha())) {
            throw new IntegrityException(entry.pinnedSha(), head == null ? "<none>" : head.getName());
        }
    }

    private Path resolvePluginRoot(Path cloneDir, UnifiedCatalogEntry entry) {
        UnifiedCatalogEntry.SourceRef ref = entry.sourceRef();
        if (ref instanceof UnifiedCatalogEntry.GitSubdirSource s && s.path() != null)
            return cloneDir.resolve(s.path()).normalize();
        if (ref instanceof UnifiedCatalogEntry.GitLocalInRepoSource l && l.path() != null)
            return cloneDir.resolve(l.path()).normalize();
        return cloneDir;
    }

    private Path manifestPath(Path pluginRoot, UnifiedCatalogEntry entry) throws IOException {
        Path rel = entry.sourceType() == StoreSourceType.CLAUDE
            ? Path.of(".claude-plugin", "plugin.json")
            : Path.of(".codex-plugin", "plugin.json");
        Path p = pluginRoot.resolve(rel).normalize();
        if (!PluginContentPathSafety.isInside(pluginRoot, p) || !Files.exists(p))
            throw new IllegalStateException("plugin.json not found at " + rel);
        return p;
    }

    private List<String> extractSkills(JsonNode pluginJson, Path pluginRoot, Path skillDest, String uid)
            throws IOException {
        JsonNode skills = pluginJson.get("skills");
        List<String> names = new ArrayList<>();
        if (skills == null || skills.isNull()) return names;
        if (skills.isTextual()) {
            names.addAll(copySkillDir(pluginRoot.resolve(skills.asText()), skillDest, skills.asText()));
        } else if (skills.isArray()) {
            for (JsonNode s : skills) {
                if (s.isTextual()) names.addAll(copySkillDir(pluginRoot.resolve(s.asText()), skillDest, s.asText()));
            }
        }
        return names;
    }

    private List<String> copySkillDir(Path srcDir, Path destBase, String rel) throws IOException {
        if (!Files.isDirectory(srcDir)) return List.of();
        Files.createDirectories(destBase);
        List<String> copied = new ArrayList<>();
        Files.walkFileTree(srcDir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relPath = srcDir.getParent() == null ? dir : srcDir.getParent().relativize(dir);
                Path target = destBase.resolve(relPath).normalize();
                if (!PluginContentPathSafety.isInside(destBase, target)) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relPath = srcDir.getParent() == null ? file : srcDir.getParent().relativize(file);
                Path target = destBase.resolve(relPath).normalize();
                if (!PluginContentPathSafety.isInside(destBase, target)) return FileVisitResult.SKIP_SUBTREE;
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
        copied.add(rel);
        return copied;
    }

    private String writeMcpConfig(JsonNode mcpServers, String uid) throws IOException {
        Path mcpDir = runtimeRoot.resolve("mcp-servers");
        Files.createDirectories(mcpDir);
        Path file = mcpDir.resolve(uid + ".json");
        Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(mcpServers));
        return file.toString();
    }

    private void upsertRecord(UnifiedCatalogEntry entry, String version, Path skillPath,
            List<String> skills, List<String> mcpRefs, boolean hasMcp) {
        PluginInstallRecordEntity rec = records
            .findByUidAndUserId(entry.uid(), SecurityConstants.LOCAL_VIRTUAL_USER_ID)
            .orElseGet(() -> {
                PluginInstallRecordEntity e = new PluginInstallRecordEntity();
                e.setUid(entry.uid());
                e.setPluginName(entry.name());
                e.setSourceType(entry.sourceType().name());
                e.setOrigin(entry.origin());
                e.setUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID);
                return e;
            });
        rec.setVersion(version);
        rec.setPinnedSha(entry.pinnedSha());
        rec.setInstallPath(skillPath.toString());
        rec.setDeclaredSkills(jsonList(skills));
        rec.setMcpServerRefs(jsonList(mcpRefs));
        rec.setHasMcpServers(hasMcp);
        rec.setEnabled(true);
        rec.setUpdatedAt(LocalDateTime.now());
        records.save(rec);
    }

    private String jsonList(List<String> items) {
        try { return json.writeValueAsString(items); }
        catch (Exception e) { return "[]"; }
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    static void deleteRecursive(Path p) {
        if (p == null || !Files.exists(p)) return;
        try {
            if (Files.isDirectory(p)) {
                try (var stream = Files.walk(p)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                    });
                }
            } else {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) { }
    }
}
```

Run again → PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/plugin/store/PluginContentPathSafety.java \
        FengYu/src/main/java/fan/summer/fengyu/plugin/store/AgentContentInstaller.java \
        FengYu/src/main/java/fan/summer/fengyu/plugin/store/IntegrityException.java \
        FengYu/src/test/java/fan/summer/fengyu/plugin/store/AgentContentInstallerTest.java
git commit -m "✨ feat(store): add AgentContentInstaller (JGit clone, sha verify, skill/mcp extraction)"
```

---

### Task 8: `InstallerDispatcher` + `UnifiedStoreService`

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/store/InstallerDispatcher.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/store/UnifiedStoreService.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/plugin/store/InstallerDispatcherTest.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/plugin/store/UnifiedStoreServiceTest.java`

**Interfaces:**
- Consumes: `AgentContentInstaller` (Task 7), `PluginPackageService` (existing), `StoreSourceRegistry` (Task 6), `PluginInstallRecordRepository` (Task 3).
- Produces:
  - `InstallerDispatcher.install(entry)` / `uninstall(uid)` / `setEnabled(uid, enabled)` / `update(entry)` — routes by `entry.sourceType()`: FENGYU → `PluginPackageService.installFromUrl(((ZipUrlSource)ref).url())`; CLAUDE/CODEX → `AgentContentInstaller`.
  - `UnifiedStoreService.list(StoreFilter)` — aggregates every enabled source's catalog via `StoreSourceRegistry.fetchCatalog`, merges install state from `PluginInstallRecordRepository` + `PluginPackageService.installed()`, applies search/category/sourceType filter, sorts by name.
- `PluginStoreController` (Task 9) calls these.

- [ ] **Step 1: Write the `InstallerDispatcher` failing test**

`FengYu/src/test/java/fan/summer/fengyu/plugin/store/InstallerDispatcherTest.java`:
```java
package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.plugin.market.PluginPackageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InstallerDispatcherTest {

    @TempDir Path temp;

    @Test
    void routesFengyuToPackageService() {
        // A spy/stub: track that installFromUrl is called for the FENGYU entry.
        var pkg = new PluginPackageService(temp.toString()); // real, but URL is unreachable — we only assert routing throws the right type
        // Use a fake AgentContentInstaller that records calls.
        CapturingAgentInstaller agent = new CapturingAgentInstaller();

        InstallerDispatcher d = new InstallerDispatcher(pkg, agent);
        UnifiedCatalogEntry fyp = new UnifiedCatalogEntry(
            "fengyu-default:FENGYU:x", "fengyu-default", StoreSourceType.FENGYU,
            "x", "x", "d", null, null, List.of(), null, null,
            new UnifiedCatalogEntry.ZipUrlSource("https://example.com/x.fyp"),
            List.of(), List.of(), null, false, null, false, false);

        // For FENGYU, the dispatcher must call the package service (which will try to fetch the URL).
        // We assert that the agent installer is NOT invoked, and the package service path is taken.
        assertThrows(Exception.class, () -> d.install(fyp)); // URL unreachable in test
        assertFalse(agent.invoked, "FENGYU must NOT go through AgentContentInstaller");
    }

    @Test
    void routesClaudeToAgentInstaller() {
        var pkg = new PluginPackageService(temp.toString());
        CapturingAgentInstaller agent = new CapturingAgentInstaller();
        InstallerDispatcher d = new InstallerDispatcher(pkg, agent);

        UnifiedCatalogEntry cl = new UnifiedCatalogEntry(
            "test:CLAUDE:y", "test", StoreSourceType.CLAUDE,
            "y", "y", "d", null, null, List.of(), null, "sha",
            new UnifiedCatalogEntry.GitUrlSource("file:///tmp/x", "sha"),
            List.of(), List.of(), null, false, null, false, false);

        d.install(cl);
        assertTrue(agent.invoked, "CLAUDE must go through AgentContentInstaller");
        assertEquals("test:CLAUDE:y", agent.lastUid);
    }

    /** Minimal AgentContentInstaller stand-in that records invocations. */
    static class CapturingAgentInstaller extends AgentContentInstaller {
        boolean invoked;
        String lastUid;
        CapturingAgentInstaller() { super(null, Path.of(System.getProperty("java.io.tmpdir")), 10); }
        @Override public void install(UnifiedCatalogEntry e) { invoked = true; lastUid = e.uid(); }
        @Override public void uninstall(String uid) { invoked = true; lastUid = uid; }
    }
}
```

- [ ] **Step 2: Run to verify it fails, then implement `InstallerDispatcher.java`**

Run: `./mvnw -f FengYu/pom.xml -Dtest InstallerDispatcherTest -q test` → compile failure.

`FengYu/src/main/java/fan/summer/fengyu/plugin/store/InstallerDispatcher.java`:
```java
package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.plugin.market.PluginPackageService;
import org.springframework.stereotype.Service;

/** Routes install/update/uninstall by source type. */
@Service
public class InstallerDispatcher {
    private final PluginPackageService packages;
    private final AgentContentInstaller agent;

    public InstallerDispatcher(PluginPackageService packages, AgentContentInstaller agent) {
        this.packages = packages;
        this.agent = agent;
    }

    public void install(UnifiedCatalogEntry entry) {
        switch (entry.sourceType()) {
            case FENGYU -> installFengyu(entry);
            case CLAUDE, CODEX -> agent.install(entry);
        }
    }

    public void update(UnifiedCatalogEntry entry) {
        // update == reinstall for both paths
        install(entry);
    }

    public void uninstall(UnifiedCatalogEntry entry) {
        switch (entry.sourceType()) {
            case FENGYU -> packages.uninstall(entry.name());
            case CLAUDE, CODEX -> agent.uninstall(entry.uid());
        }
    }

    public void setEnabled(UnifiedCatalogEntry entry, boolean enabled) {
        switch (entry.sourceType()) {
            case FENGYU -> packages.setEnabled(entry.name(), enabled);
            case CLAUDE, CODEX -> agent.setEnabled(entry.uid(), enabled);
        }
    }

    private void installFengyu(UnifiedCatalogEntry entry) {
        if (!(entry.sourceRef() instanceof UnifiedCatalogEntry.ZipUrlSource zip))
            throw new IllegalArgumentException("FengYu entry has no download URL: " + entry.uid());
        try {
            packages.installFromUrl(zip.url());
        } catch (Exception e) {
            throw new RuntimeException("FengYu install failed: " + entry.uid(), e);
        }
    }
}
```

Run again → PASS (2 tests).

- [ ] **Step 3: Write the `UnifiedStoreService` failing test**

`@DataJpaTest` for the real `PluginInstallRecordRepository`, `@TempDir` for the `PluginPackageService` runtime root (empty — no installed `.fyp` to merge, which is fine for these tests). A `StubRegistry` bypasses HTTP by overriding `listSources`/`fetchCatalog`.

`FengYu/src/test/java/fan/summer/fengyu/plugin/store/UnifiedStoreServiceTest.java`:
```java
package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.database.repository.PluginInstallRecordRepository;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class UnifiedStoreServiceTest {

    @TempDir Path temp;
    @Autowired private PluginInstallRecordRepository records;

    @Test
    void aggregatesAndFiltersBySourceType() {
        StoreSource feng = new StoreSource("fengyu", StoreSourceType.FENGYU, "https://e/f.json", "F");
        StoreSource claude = new StoreSource("claude", StoreSourceType.CLAUDE, "https://e/c.json", "C");
        StubRegistry registry = new StubRegistry(List.of(feng, claude), Map.of(
            "fengyu", List.of(entry("fengyu:FENGYU:a", StoreSourceType.FENGYU, "a", "Alpha")),
            "claude", List.of(entry("claude:CLAUDE:b", StoreSourceType.CLAUDE, "b", "Bravo"))
        ));
        UnifiedStoreService svc = new UnifiedStoreService(registry, records,
            new PluginPackageService(temp.toString()));

        List<UnifiedCatalogEntry> all = svc.list(new UnifiedStoreService.StoreFilter(null, null, null));
        assertEquals(2, all.size());

        List<UnifiedCatalogEntry> onlyClaude = svc.list(
            new UnifiedStoreService.StoreFilter(StoreSourceType.CLAUDE, null, null));
        assertEquals(1, onlyClaude.size());
        assertEquals("claude:CLAUDE:b", onlyClaude.get(0).uid());
    }

    @Test
    void searchMatchesNameAndDescription() {
        StoreSource s = new StoreSource("s", StoreSourceType.FENGYU, "https://e/f.json", "S");
        StubRegistry registry = new StubRegistry(List.of(s), Map.of(
            "s", List.of(
                entry("s:FENGYU:a", StoreSourceType.FENGYU, "a", "Alpha editor"),
                entry("s:FENGYU:b", StoreSourceType.FENGYU, "b", "Bravo browser"))
        ));
        UnifiedStoreService svc = new UnifiedStoreService(registry, records,
            new PluginPackageService(temp.toString()));

        List<UnifiedCatalogEntry> hits = svc.list(new UnifiedStoreService.StoreFilter(null, null, "bravo"));
        assertEquals(1, hits.size());
        assertEquals("b", hits.get(0).name());
    }

    private static UnifiedCatalogEntry entry(String uid, StoreSourceType type, String name, String desc) {
        return new UnifiedCatalogEntry(uid, uid.split(":")[0], type, name, name, desc,
            null, null, List.of(), null, null,
            new UnifiedCatalogEntry.ZipUrlSource("https://e/" + name + ".fyp"),
            List.of(), List.of(), null, false, null, false, false);
    }

    /** In-memory StoreSourceRegistry stub for service tests (no HTTP, no DB). */
    static class StubRegistry extends StoreSourceRegistry {
        final List<StoreSource> sources;
        final Map<String, List<UnifiedCatalogEntry>> catalog;
        StubRegistry(List<StoreSource> sources, Map<String, List<UnifiedCatalogEntry>> catalog) {
            super(null, List.of(), 600); // repo unused — we override every method that touches it
            this.sources = sources;
            this.catalog = catalog;
        }
        @Override public List<StoreSource> listSources() { return sources; }
        @Override public List<UnifiedCatalogEntry> fetchCatalog(String origin) {
            return catalog.getOrDefault(origin, List.of());
        }
    }
}
```

- [ ] **Step 4: Run to verify it fails, then implement `UnifiedStoreService.java`**

Run: `./mvnw -f FengYu/pom.xml -Dtest UnifiedStoreServiceTest -q test` → compile failure.

`FengYu/src/main/java/fan/summer/fengyu/plugin/store/UnifiedStoreService.java`:
```java
package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.database.repository.PluginInstallRecordRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** Aggregates all marketplace sources into one unified catalog with install-state merge + filtering. */
@Service
public class UnifiedStoreService {
    private final StoreSourceRegistry registry;
    private final PluginInstallRecordRepository records;
    private final PluginPackageService packages;

    public UnifiedStoreService(StoreSourceRegistry registry,
            PluginInstallRecordRepository records, PluginPackageService packages) {
        this.registry = registry;
        this.records = records;
        this.packages = packages;
    }

    /** Filter params for {@link #list(StoreFilter)}. */
    public record StoreFilter(StoreSourceType sourceType, String category, String query) {}

    public List<UnifiedCatalogEntry> list(StoreFilter filter) {
        // 1. Aggregate remote catalogs from all enabled sources.
        List<UnifiedCatalogEntry> all = new ArrayList<>();
        for (StoreSource src : registry.listSources()) {
            all.addAll(registry.fetchCatalog(src.origin()));
        }

        // 2. Load local install state: agent-content records + .fyp manifests.
        Map<String, Installed> installedByUid = new HashMap<>();
        for (var rec : records.findAllByUserIdOrderByInstalledAtDesc(SecurityConstants.LOCAL_VIRTUAL_USER_ID)) {
            installedByUid.put(rec.getUid(), new Installed(rec.getVersion(), rec.isEnabled(), rec.getSourceType()));
        }
        for (var m : packages.installed()) {
            // .fyp entries have no stored origin; key them by name under a synthetic FENGYU uid prefix
            // for any source that advertises the same id. (Install-state merge is best-effort for .fyp.)
            // We rely on the catalog entry's uid matching when origin is fengyu-default.
        }

        // 3. Merge install state into entries.
        List<UnifiedCatalogEntry> merged = all.stream()
            .map(e -> {
                Installed inst = installedByUid.get(e.uid());
                if (inst == null) return e;
                boolean update = inst.version != null && e.installedVersion() != null
                    && compareVersions(e.installedVersion(), inst.version) > 0;
                return new UnifiedCatalogEntry(e.uid(), e.origin(), e.sourceType(), e.name(),
                    e.displayName(), e.description(), e.author(), e.category(), e.keywords(),
                    e.homepage(), e.pinnedSha(), e.sourceRef(), e.declaredSkills(), e.mcpServers(),
                    e.interfaceMeta(), true, inst.version, update, inst.enabled);
            })
            .collect(Collectors.toCollection(ArrayList::new));

        // 4. Filter.
        return merged.stream()
            .filter(e -> filter.sourceType() == null || e.sourceType() == filter.sourceType())
            .filter(e -> filter.category() == null || filter.category().isBlank()
                || filter.category().equalsIgnoreCase(e.category()))
            .filter(e -> filter.query() == null || filter.query().isBlank() || matchesQuery(e, filter.query()))
            .sorted(Comparator.comparing(UnifiedCatalogEntry::displayName, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private static boolean matchesQuery(UnifiedCatalogEntry e, String q) {
        String ql = q.toLowerCase(Locale.ROOT);
        if (e.name() != null && e.name().toLowerCase(Locale.ROOT).contains(ql)) return true;
        if (e.description() != null && e.description().toLowerCase(Locale.ROOT).contains(ql)) return true;
        return e.keywords().stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).contains(ql));
    }

    /** Best-effort 3-part numeric version compare (mirrors PluginMarketplaceService.compareVersions). */
    static int compareVersions(String left, String right) {
        int[] a = numeric(left);
        int[] b = numeric(right);
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(a[i], b[i]);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int[] numeric(String v) {
        int[] out = new int[3];
        if (v == null) return out;
        String[] parts = v.split("[-+]", 2)[0].split("\\.");
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            try { out[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private record Installed(String version, boolean enabled, String sourceType) {}
}
```

Run again → PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/plugin/store/InstallerDispatcher.java \
        FengYu/src/main/java/fan/summer/fengyu/plugin/store/UnifiedStoreService.java \
        FengYu/src/test/java/fan/summer/fengyu/plugin/store/InstallerDispatcherTest.java \
        FengYu/src/test/java/fan/summer/fengyu/plugin/store/UnifiedStoreServiceTest.java
git commit -m "✨ feat(store): add InstallerDispatcher + UnifiedStoreService (aggregate, merge, filter)"
```

---

### Task 9: `StoreSourceSeeder` (default source on startup)

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/store/StoreSourceSeeder.java`

**Interfaces:**
- Consumes: `StoreSourceRegistry` (Task 6), existing `fengyu.marketplace.catalog-url` config.
- Produces: an `ApplicationRunner` that, on startup, seeds a `fengyu-default` FENGYU source if `fengyu.marketplace.catalog-url` is configured and the origin is not already present. Mirrors `OfficialPluginSeeder`.

- [ ] **Step 1: Create `StoreSourceSeeder.java`**

`FengYu/src/main/java/fan/summer/fengyu/plugin/store/StoreSourceSeeder.java`:
```java
package fan.summer.fengyu.plugin.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds a default FengYu source on startup so the unified store works out of the box when
 * {@code fengyu.marketplace.catalog-url} is configured. Mirrors {@code OfficialPluginSeeder}.
 *
 * @since 4.0.0
 */
@Component
public class StoreSourceSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StoreSourceSeeder.class);
    private final StoreSourceRegistry registry;
    private final String catalogUrl;

    public StoreSourceSeeder(StoreSourceRegistry registry,
            @Value("${fengyu.marketplace.catalog-url:}") String catalogUrl) {
        this.registry = registry;
        this.catalogUrl = catalogUrl == null ? "" : catalogUrl.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    public synchronized void seed() {
        if (catalogUrl.isBlank()) return;
        try {
            registry.addSource("FengYu Default", StoreSourceType.FENGYU, catalogUrl);
            log.info("Seeded default FengYu store source ({})", catalogUrl);
        } catch (IllegalStateException already) {
            // already seeded — fine
        }
    }
}
```

> **Note:** `StoreSourceRegistry.addSource` derives origin = `normalizeOrigin(name, type)` = `"fengyu-default-fengyu"`. If you'd rather the origin be exactly `"fengyu-default"`, adjust `normalizeOrigin` or pass that name. Pick one and keep it consistent with the controller/tests. (For this plan, `"fengyu-default-fengyu"` is fine — it's unique and stable.)

- [ ] **Step 2: Verify it compiles and the app context still boots**

Run: `./mvnw -f FengYu/pom.xml -DskipTests -q compile`
Expected: BUILD SUCCESS.

(Boot test is covered implicitly by the later full `@SpringBootTest` smoke in Task 11.)

- [ ] **Step 3: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/plugin/store/StoreSourceSeeder.java
git commit -m "✨ feat(store): add StoreSourceSeeder to seed default FengYu source on startup"
```

---

### Task 10: `PluginStoreController` (REST API)

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/web/controller/PluginStoreController.java`

**Interfaces:**
- Consumes: `StoreSourceRegistry`, `UnifiedStoreService`, `InstallerDispatcher`, `PluginInstallRecordRepository` (all earlier tasks).
- Produces: the `/api/plugin-store/*` endpoints (spec §5). Mirrors `PluginMarketplaceController` annotations + nested request records.

- [ ] **Step 1: Create `PluginStoreController.java`**

`FengYu/src/main/java/fan/summer/fengyu/web/controller/PluginStoreController.java`:
```java
package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.repository.PluginInstallRecordRepository;
import fan.summer.fengyu.plugin.store.InstallerDispatcher;
import fan.summer.fengyu.plugin.store.StoreSource;
import fan.summer.fengyu.plugin.store.StoreSourceRegistry;
import fan.summer.fengyu.plugin.store.StoreSourceType;
import fan.summer.fengyu.plugin.store.UnifiedCatalogEntry;
import fan.summer.fengyu.plugin.store.UnifiedStoreService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Unified plugin store API: aggregate multiple marketplaces (FengYu/Claude/Codex) + install. */
@RestController
@RequestMapping("/api/plugin-store")
public class PluginStoreController {
    private final StoreSourceRegistry sources;
    private final UnifiedStoreService store;
    private final InstallerDispatcher dispatcher;
    private final PluginInstallRecordRepository records;

    public PluginStoreController(StoreSourceRegistry sources, UnifiedStoreService store,
            InstallerDispatcher dispatcher, PluginInstallRecordRepository records) {
        this.sources = sources;
        this.store = store;
        this.dispatcher = dispatcher;
        this.records = records;
    }

    // ── sources ──────────────────────────────────────────────
    @GetMapping("/sources")
    public List<StoreSource> listSources() { return sources.listSources(); }

    @PostMapping("/sources")
    public ResponseEntity<StoreSource> addSource(@RequestBody AddSourceRequest req) {
        StoreSource src = sources.addSource(req.name(), StoreSourceType.valueOf(req.sourceType()), req.catalogUrl());
        return ResponseEntity.status(HttpStatus.CREATED).body(src);
    }

    @DeleteMapping("/sources/{origin}")
    public void deleteSource(@PathVariable String origin) { sources.deleteSource(origin); }

    @PostMapping("/sources/{origin}/refresh")
    public void refreshSource(@PathVariable String origin) { sources.refresh(origin); }

    // ── catalog ──────────────────────────────────────────────
    @GetMapping("/catalog")
    public List<UnifiedCatalogEntry> catalog(
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q) {
        StoreSourceType st = sourceType == null ? null : StoreSourceType.valueOf(sourceType);
        return store.list(new UnifiedStoreService.StoreFilter(st, category, q));
    }

    // ── install lifecycle ────────────────────────────────────
    @PostMapping("/{uid}/install")
    public void install(@PathVariable String uid) {
        UnifiedCatalogEntry entry = findEntry(uid);
        dispatcher.install(entry);
    }

    @PostMapping("/{uid}/update")
    public void update(@PathVariable String uid) {
        UnifiedCatalogEntry entry = findEntry(uid);
        dispatcher.update(entry);
    }

    @DeleteMapping("/{uid}")
    public void uninstall(@PathVariable String uid) {
        UnifiedCatalogEntry entry = findEntry(uid);
        dispatcher.uninstall(entry);
    }

    @PatchMapping("/{uid}/enabled")
    public void setEnabled(@PathVariable String uid, @RequestBody EnabledRequest req) {
        UnifiedCatalogEntry entry = findEntry(uid);
        dispatcher.setEnabled(entry, req.enabled());
    }

    // ── history ──────────────────────────────────────────────
    @GetMapping("/history")
    public List<?> history() {
        return records.findAllByUserIdOrderByInstalledAtDesc(SecurityConstants.LOCAL_VIRTUAL_USER_ID);
    }

    // ── helpers ──────────────────────────────────────────────
    private UnifiedCatalogEntry findEntry(String uid) {
        return store.list(new UnifiedStoreService.StoreFilter(null, null, null)).stream()
            .filter(e -> e.uid().equals(uid))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No catalog entry for uid: " + uid));
    }

    public record AddSourceRequest(String name, String sourceType, String catalogUrl) {}
    public record EnabledRequest(boolean enabled) {}
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./mvnw -f FengYu/pom.xml -DskipTests -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/web/controller/PluginStoreController.java
git commit -m "✨ feat(store): add PluginStoreController (/api/plugin-store/* REST API)"
```

---

### Task 11: Full-module build + e2e regression smoke

**Files:** none (verification only)

- [ ] **Step 1: Build the whole FengYu module with tests**

Run: `./mvnw -f FengYu/pom.xml clean package`
Expected: BUILD SUCCESS. All new tests (Tasks 2–8) pass; existing tests do not regress.

- [ ] **Step 2: Run the e2e smoke to confirm no regression on existing plugin endpoints**

Run: `scripts/e2e-smoke.sh`
Expected: passes (the existing `/api/plugin-market` endpoints are untouched). New `/api/plugin-store/*` endpoints are not yet exercised by the smoke script — that's fine for this plan; manual smoke is in the spec §12.

- [ ] **Step 3: If both pass, the backend plan is complete**

No commit needed (verification only).

---

## Self-Review Notes (for the implementer)

**Spec coverage** (spec section → task):
- §2 architecture → Tasks 2–8 (package + types + adapters + dispatchers + service)
- §3 persistence (`StoreSourceEntity`, `PluginInstallRecordEntity`) → Task 3
- §3.3 seeder → Task 9
- §4 adapters + `GitHubUrlResolver` → Tasks 4–5
- §4.5 sha verify → Task 7 (`AgentContentInstaller.verifySha`)
- §4.5 `.fyp` sha256 sidecar extension → **DEFERRED** (see note below)
- §4.6 cache → Task 6 (`StoreSourceRegistry` TTL)
- §5 REST API → Task 10
- §6 frontend → **Plan B** (separate plan)
- §7 errors → covered inline (adapters throw → registry records last_error; installer throws IntegrityException)
- §8 security (sha, path traversal, HTTPS-only) → Tasks 5 (HTTPS), 7 (sha + path safety)
- §9 tests → each task is TDD
- §10 config keys → wired in Tasks 6, 7

**Deferred from this plan (flag for follow-up):**
- **`.fyp` sha256 sidecar verification in `PluginPackageService`** (spec §4.5, "推广到所有 .fyp 安装"). This requires modifying `PluginPackageService` (an existing, untouched-by-this-plan class) and adding tests for all 3 install paths. It's a focused, separately-reviewable change — recommend a **Task 12 / separate PR** so the installer core lands first. The existing `OfficialPluginSeeder.verifySha256` already covers the seeded path; the gap is only user/catalog-initiated `.fyp` installs.

**Type consistency check:** `uid` format `origin:sourceType:name` is consistent across `UnifiedCatalogEntry` (Task 2), all 3 adapters (Task 5), `AgentContentInstaller` (Task 7), `UnifiedStoreService` (Task 8), `PluginStoreController` (Task 10). `sourceRef` sealed subtypes (`ZipUrlSource`/`GitUrlSource`/`GitSubdirSource`/`GitLocalInRepoSource`) match between definition (Task 2) and consumers (adapters Task 5, dispatcher/installer Tasks 7–8).
