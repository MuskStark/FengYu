# FengYu 插件市场服务 — 计划 3：聚合 + 多格式发布（支柱 3）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已落地的发布门户（计划 2，main @ `787b37e`，97/97 测试）上实现**聚合 + 多格式发布**：聚合本服务已发布 FengYu 插件 + Claude 官方市场 + Codex 官方市场 → 统一 catalog；对外发布三种原生清单文件（`/marketplaces/fengyu.json`、`/marketplaces/claude.json`、`/marketplaces/codex.json`）供 Claude Code / Codex / 其他 FengYu 订阅；FengYu 主程序通过 HTTP 消费统一 catalog。**不**实现前端（计划 4）。

**Architecture:** 新增 `catalog/` 包：`dto/`（`UnifiedCatalogEntry` + `StoreSourceType` + sealed `SourceRef` + `StoreAuthor` + `InterfaceMeta`）、`adapter/`（`SourceAdapter` 接口 + `FengYuSelfCatalogAdapter` + `ClaudeUpstreamAdapter` + `CodexUpstreamAdapter` + `GitHubUrlResolver`）、`service/`（`SourceFetchService` TTL 缓存 + `CatalogAggregator`）、`publish/`（`FengYuCatalogPublisher` + `ClaudeMarketplacePublisher` + `CodexMarketplacePublisher`）、`controller/`（`CatalogController` 统一查询 + `MarketPublishController` 清单发布 + 下载端点）。复用计划 2 的 `PluginRepository`/`PluginVersionRepository` + `ArtifactStore`（下载）+ 计划 0 的 `BoundedHttp`（上游拉取有界）+ `PathSafety`。

**Tech Stack:** Spring Boot 4.1.0、Spring Security 7（清单端点 `permitAll`，已在计划 1 配置）、JPA、Jackson 3（`tools.jackson`；**上游 JSON 解析也用 Jackson 3，与 json-schema-validator 隔离**）、Java 21 `HttpClient`、JUnit 5 + Spring Boot Test + `RestTestClient.bindToServer()`。

## ⚠️ 计划 3 执行前须知（来自计划 0/1/2 评审）

- **JDK 21**：`JAVA_HOME=/Users/phoebej/Library/Java/JavaVirtualMachines/azul-21.0.12/Contents/Home`。
- **仓库**：`fengyu-marketplace-server`（**非** FengYu）。SDD 子 agent 必须 `cd /Users/phoebej/Develop/Java/fengyu-marketplace-server`（计划 2 Task 9 教训：子 agent 跑错仓库会 BLOCKED）。
- **Jackson 3**：`tools.jackson.databind.*`；上游 JSON 解析用 Jackson 3 的 `JsonNode`/`ObjectMapper`（**不**用 `json-schema-validator` 的 Jackson 2；本计划不涉及 schema 校验，无冲突）。
- **`@MockitoBean`**（非 `@MockBean`，Boot 4.1）。
- **RestTestClient.bindToServer()**（非 TestRestTemplate，非 `@Autowired RestTestClient.Builder`）。
- **上游 HTTP 拉取**：用 Java 21 `HttpClient`（计划 2 的 `ArtifactValidationService` 没用 HTTP，本计划首次用）。复用计划 0 `BoundedHttp.readAtMost(in, MAX_CATALOG_BYTES)` 限上游响应 16 MiB（§6 安全）。
- **清单端点 permitAll**：计划 1 `MarketSecurityConfig` 已放行 `/marketplaces/**`（无需改安全配置）。

## 全局约束（所有任务隐含遵守）

- **TDD**：每功能点 RED→GREEN；conventional commits + emoji。
- **上游聚合健壮性**：上游不可达 → 用缓存（哪怕过期）+ 标 `stale=true`，**不抛错**（§5.1）。
- **不启动 MCP server**（§5.2 v1）：Codex `mcpServers` 字符串形式解析是 v1 范围（解析为对象写入清单），但**不拉起进程**（YAGNI）。
- **不删/不改本任务之外的文件**。

## 文件结构（本计划产出）

```
fengyu-marketplace-server/src/main/java/fan/summer/marketplace/catalog/
├── dto/
│   ├── StoreSourceType.java                     # Task 1
│   ├── UnifiedCatalogEntry.java                 # Task 1（record + nested sealed SourceRef + Author + InterfaceMeta）
│   └── CatalogQuery.java                        # Task 5（查询参数 record）
├── adapter/
│   ├── SourceAdapter.java                       # Task 2（接口）
│   ├── FengYuSelfCatalogAdapter.java            # Task 2（查本地已发布）
│   ├── ClaudeUpstreamAdapter.java               # Task 3（移植自主程序）
│   ├── CodexUpstreamAdapter.java                # Task 3（移植）
│   └── GitHubUrlResolver.java                   # Task 3（Codex local 源 → repo+ref+path）
├── service/
│   ├── SourceFetchService.java                  # Task 4（TTL 缓存拉取上游）
│   └── CatalogAggregator.java                   # Task 5（合并三类源）
├── publish/
│   ├── FengYuCatalogPublisher.java              # Task 6
│   ├── ClaudeMarketplacePublisher.java          # Task 6
│   └── CodexMarketplacePublisher.java           # Task 6
└── controller/
    ├── CatalogController.java                   # Task 7（GET /api/catalog 统一查询 + 下载）
    └── MarketPublishController.java             # Task 7（GET /marketplaces/{fengyu,claude,codex}.json）
```

---

### Task 1: `StoreSourceType` + `UnifiedCatalogEntry` DTO

**Files:**
- Create: `catalog/dto/StoreSourceType.java`、`catalog/dto/UnifiedCatalogEntry.java`

**Interfaces:**
- Produces: `StoreSourceType` 枚举（FENGYU/CLAUDE/CODEX）；`UnifiedCatalogEntry`（record，字段对齐主程序消费端，含 nested `Author`、sealed `SourceRef` permits `ZipUrlSource/GitUrlSource/GitSubdirSource/GitLocalInRepoSource`、`InterfaceMeta`）。所有 adapter/publisher 依赖它。

- [ ] **Step 1: 写 `StoreSourceType`**

```java
package fan.summer.marketplace.catalog.dto;

public enum StoreSourceType { FENGYU, CLAUDE, CODEX }
```

- [ ] **Step 2: 写 `UnifiedCatalogEntry`**（字段对齐主程序消费端 `UnifiedCatalogEntry`，但**独立**——不共享 Java 类，JSON 是契约）

```java
package fan.summer.marketplace.catalog.dto;

import java.time.Instant;
import java.util.List;

/**
 * 统一 catalog 条目（聚合三类源产出）。字段对齐主程序消费端 record（见 FengYu 主程序
 * plugin/store/UnifiedCatalogEntry.java），但本服务是**生产者**，独立 record，JSON 是契约。
 *
 * <p>installed/installedVersion/updateAvailable/enabled 是消费端合并字段，本服务**不填**（false/null）。
 */
public record UnifiedCatalogEntry(
        String uid,                    // "<origin>:<sourceType>:<name>"
        String origin,                 // 来源标识
        StoreSourceType sourceType,
        String name,                   // 安全 slug（用于 uid + 文件路径）
        String displayName,            // Codex interface.displayName；其余 = name
        String description,
        Author author,
        String category,
        List<String> keywords,
        String homepage,
        String pinnedSha,              // FengYu: 制品 sha256；Claude: git sha；Codex: resolved HEAD sha 或 null
        SourceRef sourceRef,
        List<String> declaredSkills,   // 聚合阶段空（消费端安装后填）
        List<String> mcpServers,
        InterfaceMeta interfaceMeta,
        boolean official,
        Instant aggregatedAt) {

    public record Author(String name, String email, String url) {}

    public sealed interface SourceRef permits ZipUrlSource, GitUrlSource, GitSubdirSource, GitLocalInRepoSource {}
    public record ZipUrlSource(String url) implements SourceRef {}
    public record GitUrlSource(String url, String sha) implements SourceRef {}
    public record GitSubdirSource(String url, String path, String ref, String sha) implements SourceRef {}
    public record GitLocalInRepoSource(String repoUrl, String ref, String path) implements SourceRef {}

    /** Codex 丰富 UX 元数据（v1 只透传，不解析资产二进制）。 */
    public record InterfaceMeta(String displayName, String shortDescription, String longDescription,
                                String developerName, String category, String brandColor,
                                String logo, String logoDark, List<String> screenshots,
                                List<String> defaultPrompt, String websiteUrl, String composerIcon) {}
}
```

- [ ] **Step 3: 全量测试不回归（97/97）**

Run: `./mvnw test` → 97/97 PASS（纯 record，无测试需要；编译过即可）。

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "✨ feat(catalog): StoreSourceType + UnifiedCatalogEntry DTO (field-aligned with main app consumer)"
```

---

### Task 2: `SourceAdapter` 接口 + `FengYuSelfCatalogAdapter`

**Files:**
- Create: `catalog/adapter/SourceAdapter.java`、`catalog/adapter/FengYuSelfCatalogAdapter.java`
- Test: `src/test/java/fan/summer/marketplace/catalog/adapter/FengYuSelfCatalogAdapterTest.java`

**Interfaces:**
- Produces: `SourceAdapter`（接口：`StoreSourceType type()`、`List<UnifiedCatalogEntry> fetch(...)`）；`FengYuSelfCatalogAdapter`（`@Component`，查 `PluginRepository` + active `PluginVersionEntity`，转 `UnifiedCatalogEntry` with `ZipUrlSource(downloadUrl, sha256)`）。

- [ ] **Step 1: 写 `SourceAdapter` 接口**

```java
package fan.summer.marketplace.catalog.adapter;

import fan.summer.marketplace.catalog.dto.StoreSourceType;
import fan.summer.marketplace.catalog.dto.UnifiedCatalogEntry;
import java.util.List;

/** 一个 catalog 源的适配器（本服务已发布 / Claude 上游 / Codex 上游）。 */
public interface SourceAdapter {
    StoreSourceType type();
    /** 拉取/查询该源的 catalog 条目。origin 是来源标识（用于 uid 前缀）。 */
    List<UnifiedCatalogEntry> fetch(String origin);
}
```

- [ ] **Step 2: 写 `FengYuSelfCatalogAdapter`**

```java
package fan.summer.marketplace.catalog.adapter;

import fan.summer.marketplace.catalog.dto.StoreSourceType;
import fan.summer.marketplace.catalog.dto.UnifiedCatalogEntry;
import fan.summer.marketplace.publish.entity.PluginEntity;
import fan.summer.marketplace.publish.entity.PluginVersionEntity;
import fan.summer.marketplace.publish.repository.PluginRepository;
import fan.summer.marketplace.publish.repository.PluginVersionRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 本服务已发布 FengYu 插件 → UnifiedCatalogEntry。查所有 PluginEntity + 其 active 版本。
 * origin 固定为 "self"（本服务自身）。
 */
@Component
public class FengYuSelfCatalogAdapter implements SourceAdapter {

    private final PluginRepository plugins;
    private final PluginVersionRepository versions;

    public FengYuSelfCatalogAdapter(PluginRepository plugins, PluginVersionRepository versions) {
        this.plugins = plugins;
        this.versions = versions;
    }

    @Override public StoreSourceType type() { return StoreSourceType.FENGYU; }

    @Override
    public List<UnifiedCatalogEntry> fetch(String origin) {
        List<UnifiedCatalogEntry> out = new ArrayList<>();
        for (PluginEntity p : plugins.findAll()) {
            PluginVersionEntity active = versions.findByPluginIdAndActiveTrue(p.getPluginId()).orElse(null);
            if (active == null) continue;
            out.add(toEntry(origin, p, active));
        }
        return out;
    }

    private UnifiedCatalogEntry toEntry(String origin, PluginEntity p, PluginVersionEntity v) {
        String name = p.getPluginId();
        return new UnifiedCatalogEntry(
                origin + ":FENGYU:" + name, origin, StoreSourceType.FENGYU,
                name, name, null, null, null, List.of(), null,
                v.getSha256(), new UnifiedCatalogEntry.ZipUrlSource(v.getDownloadUrl()),
                List.of(), List.of(), null, false, Instant.now());
    }
}
```

> 注意：description/category/homepage/author 在 `PluginEntity` 里没有（Task 6 实体只存 pluginId+name）。若要填这些，需从 `PluginVersionEntity.manifestJson`（原始 manifest）解析。**v1 简化**：只填 name/version/sha256/downloadUrl，其余 null（消费端容忍 null）。若要更完整，可注入 ObjectMapper 解析 manifestJson 取 description 等——**实现者酌情**，但测试要覆盖所选字段。

- [ ] **Step 3: 写测试**（`@SpringBootTest @ActiveProfiles("test") @Transactional`：seed Plugin + active Version → fetch 返回对应 entry，校验 uid/name/sha256/downloadUrl）

- [ ] **Step 4: RED→GREEN + 全量（~99/99）+ 提交**

```bash
git commit -m "✨ feat(catalog): SourceAdapter interface + FengYuSelfCatalogAdapter (query published plugins)"
```

---

### Task 3: `ClaudeUpstreamAdapter` + `CodexUpstreamAdapter` + `GitHubUrlResolver`（移植）

**Files:**
- Create: `catalog/adapter/ClaudeUpstreamAdapter.java`、`CodexUpstreamAdapter.java`、`GitHubUrlResolver.java`
- Test: 三者的单元测试（用样本 JSON 字符串，不打真实 HTTP）

**Interfaces:**
- Produces: 两个上游 adapter（接收**原始 JSON 字符串**，解析为 `UnifiedCatalogEntry` 列表；HTTP 拉取由 Task 4 的 `SourceFetchService` 做，adapter 只解析）。`GitHubUrlResolver`（Codex `source.source="local"` + `path` → 从 catalogUrl 反推 repoUrl+ref，移植自主程序）。
- **移植自**：主程序 `ClaudeMarketplaceAdapter` / `CodexMarketplaceAdapter` / `GitHubUrlResolver`（本计划文档已附 Claude adapter 源码）。**用 Jackson 3 解析**（`tools.jackson.databind.JsonNode`），非主程序的 Jackson 2。

- [ ] **Step 1: 写 `GitHubUrlResolver`**（移植：从 `catalogUrl`（github raw blob url）反推 repoUrl + ref）

- [ ] **Step 2: 写 `ClaudeUpstreamAdapter`**（移植：解析 `.claude-plugin/marketplace.json`，`source` union: `url`→`GitUrlSource`、`git-subdir`→`GitSubdirSource`、字符串本地路径→跳过；slugify name；提取 author/category/keywords/homepage/pinnedSha）

- [ ] **Step 3: 写 `CodexUpstreamAdapter`**（移植：解析 `.agents/plugins/marketplace.json`，`source.source="local"`→`GitLocalInRepoSource` via `GitHubUrlResolver`；提取 `interface` UX 元数据 → `InterfaceMeta`；**mcpServers 字符串形式解析 v1 简化**：遇字符串 → 标记 `mcpServers=["<unresolved:" + path + ">"]` + 在 description 注入警告；真正的 GitHub raw API 解析留 v2——**见 §5.2 注记**）

- [ ] **Step 4: 测试**（用主程序勘探时的样本 JSON 字符串作 fixture；校验三类 source 形式解析正确、slugify、uid 构造）

- [ ] **Step 5: RED→GREEN + 全量 + 提交**

```bash
git commit -m "✨ feat(catalog): Claude/Codex upstream adapters + GitHubUrlResolver (ported from main app)"
```

> **§5.2 Codex mcpServers 字符串形式**：真正的 `CodexMcpStringResolver`（GitHub raw API 或浅克隆读 `<repo>/<path>@<ref>`）是一个独立子系统，v1 **简化处理**——字符串形式标记为未解析并在清单里注释，不让单个坏插件拖垮聚合。完整的 resolver 留 v2（实现者：在本任务的 adapter 里对字符串形式做降级处理即可，不实现 GitHub raw API 调用）。

---

### Task 4: `SourceFetchService`（TTL 缓存拉取上游）

**Files:**
- Create: `catalog/service/SourceFetchService.java`
- Create: `catalog/entity/CachedUpstreamSourceEntity.java` + `catalog/repository/CachedUpstreamSourceRepository.java`
- Create: `src/main/resources/db/migration/V3__init_catalog_cache.sql`
- Test: `SourceFetchServiceTest`

**Interfaces:**
- Produces: `SourceFetchService.fetch(String url)` → `String`（原始 JSON，TTL 缓存）。用 `HttpClient` + `BoundedHttp.readAtMost`（16 MiB）。缓存存 `CachedUpstreamSourceEntity`（url unique、rawJson、fetchedAt、ok、lastError）。TTL 内返缓存；超 TTL 重新拉取；**上游不可达 → 返上次缓存（哪怕过期）+ 标 stale**（不抛）。
- 配置：`market.aggregate.cache-ttl-seconds`（默认 600）、`market.aggregate.upstreams`（Claude/Codex 上游 URL 列表）。

- [ ] **Step 1: V3 迁移（cached_upstream_sources 表：id, url UNIQUE, raw_json TEXT, fetched_at TIMESTAMP, ok BOOLEAN, last_error TEXT, created_at TIMESTAMP）+ 实体 + repo**

- [ ] **Step 2: 写 `SourceFetchService`**（HttpClient + BoundedHttp + 缓存逻辑 + 降级）

- [ ] **Step 3: 测试**（`@MockitoBean HttpClient` 或注入；TTL 内命中缓存、超 TTL 重拉、上游 500 → 返缓存 + stale、首次拉取失败 → 空或抛——**v1：首次无缓存且失败 → 返空列表，不抛**）

- [ ] **Step 4: RED→GREEN + 全量 + 提交**

```bash
git commit -m "✨ feat(catalog): SourceFetchService (TTL-cached upstream fetch, stale-on-failure, BoundedHttp 16MiB)"
```

---

### Task 5: `CatalogAggregator`（合并三类源）

**Files:**
- Create: `catalog/service/CatalogAggregator.java`
- Test: `CatalogAggregatorTest`

**Interfaces:**
- Produces: `CatalogAggregator.aggregate()` → `List<UnifiedCatalogEntry>`（合并 FengYuSelfCatalogAdapter + 上游 Claude/Codex via SourceFetchService + adapter；按 sourceType/name 去重；标 stale）。
- 配置读 `market.aggregate.upstreams` 列表（type + url），对每个上游：`SourceFetchService.fetch(url)` → 对应 adapter.parse(rawJson, origin=url host)。

- [ ] **Step 1: 写 `CatalogAggregator`**（合并 + 去重 + stale 标记）

- [ ] **Step 2: 测试**（mock 三个 adapter + SourceFetchService；校验合并、去重、stale）

- [ ] **Step 3: RED→GREEN + 全量 + 提交**

```bash
git commit -m "✨ feat(catalog): CatalogAggregator (merge FY+Claude+Codex, dedupe, stale)"
```

---

### Task 6: 三 publisher（`FengYuCatalogPublisher` + `ClaudeMarketplacePublisher` + `CodexMarketplacePublisher`）

**Files:**
- Create: `catalog/publish/{FengYuCatalogPublisher,ClaudeMarketplacePublisher,CodexMarketplacePublisher}.java`
- Test: 三者的输出格式测试

**Interfaces:**
- `FengYuCatalogPublisher.publish(List<UnifiedCatalogEntry>)` → `String`（JSON 数组，**只含 FENGYU 条目**，形状 = 主程序 `MarketplaceCatalogEntry[]` + 新增 `sha256` 字段；`downloadUrl` 用 entry 的 ZipUrlSource.url）。
- `ClaudeMarketplacePublisher.publish(...)` → `String`（`.claude-plugin/marketplace.json` 结构，透传 CLAUDE 条目）。
- `CodexMarketplacePublisher.publish(...)` → `String`（`.agents/plugins/marketplace.json` 结构，透传 CODEX 条目）。

- [ ] **Step 1-3: 三 publisher + 测试（格式合规、字段正确、sha256 在 FengYu catalog）+ 提交**

```bash
git commit -m "✨ feat(catalog): FengYu/Claude/Codex marketplace publishers (3 native manifest formats)"
```

---

### Task 7: `CatalogController` + `MarketPublishController` + 下载端点

**Files:**
- Create: `catalog/controller/CatalogController.java`（`GET /api/catalog` 统一查询 + `GET /api/catalog/{pluginId}/versions` + `GET /api/catalog/{pluginId}/{version}/download`）
- Create: `catalog/controller/MarketPublishController.java`（`GET /marketplaces/{fengyu,claude,codex}.json`，permitAll）
- Test: `CatalogControllerTest`（端到端：发布插件 → catalog 可见 → 下载；三清单文件可访问）

**端点：**
- `GET /api/catalog`（permitAll 或认证；返回聚合 catalog，可带 `?sourceType=&category=&q=` 过滤）。
- `GET /marketplaces/fengyu.json` / `claude.json` / `codex.json`（**permitAll**，计划 1 已放行 `/marketplaces/**`；输出对应 publisher 的 JSON，内存缓存）。
- `GET /api/catalog/{pluginId}/{version}/download`（流式返回制品，`Content-Disposition: attachment`；查 `PluginVersionEntity.sha256` → `ArtifactStore.openStream`）。

- [ ] **Step 1-3: 控制器 + 端到端测试 + 提交**

```bash
git commit -m "✨ feat(catalog): CatalogController + MarketPublishController (unified query + 3 manifest files + download)"
```

---

## 完成判据

- `./mvnw test`（JDK 21）全绿，约 110+ 测试。
- 端到端：计划 2 发布的插件 → `/api/catalog` 可见 → `/marketplaces/fengyu.json` 含 sha256 + downloadUrl → 下载端点返回制品字节。
- 三清单文件可公开访问（无认证）。
- 上游 Claude/Codex 不可达 → catalog 仍返回（FengYu 部分 + stale 标记的上游缓存或空）。
- `git log` 清晰 conventional commits。

## 下一步

聚合 + 发布落地后，进入**计划 4（前端）**——Vue 3 SPA（8 路由、silent-refresh、i18n EN/ZH）。计划 4 是最后一个支柱。
