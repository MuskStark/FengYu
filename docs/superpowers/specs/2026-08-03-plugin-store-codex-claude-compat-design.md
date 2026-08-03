# 统一插件商店服务（兼容 Claude / Codex 插件商店）

- **状态**: 设计草案，待评审
- **日期**: 2026-08-03
- **作者**: MuskStark（AI 辅助设计）
- **目标版本**: FengYu 4.0.0+

## 1. 目标与背景

### 1.1 目标

构建一个**统一插件商店服务**，能够：

1. **订阅多个外部商店源** —— 包括 FengYu 自有的 catalog 格式，以及 **Claude Code** 和 **OpenAI Codex** 的 marketplace 格式。
2. **在统一目录里聚合展示**它们的插件，带来源徽章、分类、搜索。
3. **安装异构插件**：FengYu `.fyp` 走现有安装器；Claude/Codex 插件以「Agent 内容包」形态安装——提取 `SKILL.md` 技能到 `~/.fengyu/skills/`、提取 MCP server 配置到 `~/.fengyu/mcp-servers/`。
4. 提供**安装历史**、**sha/签名校验**、**更新检测**。

这是**单向导入**（聚合并安装它们的插件），不包含发布到它们的商店。

### 1.2 三种"插件"模型的关键差异

这是整个设计的认知基础：

| 维度 | Claude Code | OpenAI Codex | FengYu `.fyp` |
|---|---|---|---|
| 商店清单文件 | `.claude-plugin/marketplace.json` | `.agents/plugins/marketplace.json` | catalog JSON（单个 URL） |
| 插件来源模型 | `source` union: `git-subdir` / `url` / 本地路径字符串 | `source.source = "local"` + `path` + `policy` 块 | `.fyp` zip 下载 URL |
| 固定版本方式 | git commit `sha`（每个 entry 带） | marketplace 不带 sha（仓库 ref 隐含） | semver + 可选 `.sha256` sidecar |
| 插件内容 | skills (SKILL.md) + commands + hooks + agents + **mcpServers** + `interface` UX 元数据 | skills + hooks + **mcpServers** + `interface` UX 元数据 | iframe UI + 独立 JSON-RPC worker 进程 |
| 后端运行时 | 无（进程内声明式内容） | 无（skills + 引用外部 MCP server） | 真实沙箱 worker 进程 |

**核心洞察**: Claude/Codex 插件本质是「**声明式 Agent 内容包**」（核心是 `SKILL.md` 技能 + MCP server 配置），**没有**编译型 worker。FengYu 已有平行的 skill 系统（`fan.summer.fengyu.ai.skill`，`~/.fengyu/skills/`），因此 Claude/Codex 插件可原生映射为 FengYu skills + MCP 配置，无需强行包装成 `.fyp`。

### 1.3 标注的设计假设（可推翻）

以下假设由设计者在用户跳过澄清时按默认判断填入。审阅时可推翻：

- **A-1 落地形态**: Claude/Codex 插件「原生映射为 skills + MCP」，不包装成 `.fyp`。（理由：包装会产生空壳 UI + 不存在的 worker 进程，名实不符。）
- **A-2 端点策略**: 新增 `/api/plugin-store/*`，不动现有 `/api/plugin-market`。
- **A-3 MCP 范围控制**: 本 spec 只做「提取 MCP server 配置 + 落盘 + 登记」，不实现 MCP runtime（让 FengYu AI 真正调用 MCP server 是单独后续工作）。
- **A-4 uid 构造**: `uid = "<origin>:<sourceType>:<pluginName>"`。

## 2. 整体架构

### 2.1 概念模型

```
┌─────────────────────────────────────────────────────────────────┐
│              UnifiedStoreService (统一目录 + 聚合)              │
│  list(filter) → List<UnifiedCatalogEntry>（含 origin/sourceType)│
└──────────┬──────────────────────────────────────────────────┬───┘
           │ 委托拉取                                          │ 委托安装
           ▼                                                  ▼
┌─────────────────────┐                      ┌──────────────────────────────────┐
│ StoreSourceRegistry │                      │ InstallerDispatcher              │
│ (管理多个 StoreSource)│                      │  FENGYU → PluginPackageService   │
│ + adapter map        │                      │  CLAUDE/CODEX → AgentContentInstaller│
└──┬──────┬──────┬────┘                      └──────────────────────────────────┘
   │      │      │
   ▼      ▼      ▼
 Adapter Adapter Adapter      ← MarketplaceSourceAdapter 接口
 (FengYu)(Claude)(Codex)        fetchCatalog() → 统一条目
```

### 2.2 新增 Java 包结构与职责

全部放在新包 `fan.summer.fengyu.plugin.store`（与现有 `plugin.market` 并列，不污染它）：

| 组件 | 职责 | 对现有代码的影响 |
|---|---|---|
| `UnifiedCatalogEntry` (record) | 三种来源的并集条目（见 §4.1） | 新增 |
| `MarketplaceSourceAdapter` (接口) | `fetchCatalog(StoreSource)` → `List<UnifiedCatalogEntry>` | 新增 |
| `FengYuCatalogAdapter` / `ClaudeMarketplaceAdapter` / `CodexMarketplaceAdapter` | 各自解析对应 JSON 格式 | 新增 |
| `StoreSourceRegistry` (service) | 管理订阅源（CRUD）+ adapter map + 缓存拉取结果（TTL） | 新增 |
| `UnifiedStoreService` (service) | 聚合所有源 + 本地已装，合并 install 状态，搜索/过滤/排序 | 新增；复用 `PluginPackageService.installed()` 和 skill installed 扫描 |
| `InstallerDispatcher` (service) | 按 sourceType 分派 | 新增 |
| `AgentContentInstaller` (service) | clone git/url 源 → 验 sha → 提取 skills/mcpServers | 新增 |
| `StoreSourceEntity` / `PluginInstallRecordEntity` | 持久化：订阅源、安装历史 | 新增 |

**对现有代码的最小侵入**: `PluginMarketplaceService` / `PluginPackageService` / `SkillMarketplaceService` / `SkillPackageService` **都不改**——新服务在它们之上组合。

## 3. 数据持久化层

ORM 沿用项目现有设置：Spring Data JPA + `hibernate.ddl-auto=update`（无 Flyway，见 `FengYu/src/main/resources/application.yml:15`）。新增实体自动建表。

### 3.1 `StoreSourceEntity` —— 订阅的商店源

```
表: store_sources
  id            BIGINT PK AUTOINCREMENT
  name          VARCHAR      用户起的名字（如 "Anthropic Official"）
  origin        VARCHAR UQ   规范化源标识（uid 前缀，如 "anthropics-claude"）
  source_type   VARCHAR      枚举: FENGYU / CLAUDE / CODEX
  catalog_url   VARCHAR      marketplace.json / catalog.json 的 URL（HTTPS-only）
  enabled       BOOLEAN      可暂停某源（暂停后不在目录聚合）
  last_sync_at  TIMESTAMP
  last_sync_ok  BOOLEAN
  last_error    TEXT         拉取失败时的错误详情
  added_at      TIMESTAMP
唯一约束: (origin)  —— 同一源不能重复订阅
```

### 3.2 `PluginInstallRecordEntity` —— 安装历史/状态（真相源）

**关键设计**: Claude/Codex 插件没有可扫描的 manifest 文件，**安装状态必须落库**（不像 `.fyp` 能纯靠文件系统派生）。这张表是 agent-content 插件的唯一真相源。

```
表: plugin_install_records
  id              BIGINT PK
  uid             VARCHAR      全局唯一 = origin:sourceType:pluginName
  plugin_name     VARCHAR
  source_type     VARCHAR      FENGYU / CLAUDE / CODEX
  origin          VARCHAR      来自哪个源
  version         VARCHAR      安装版本（.fyp 从 manifest；Claude/Codex 从 plugin.json）
  pinned_sha      VARCHAR      Claude/Codex 的固定 sha；FengYu 可为空
  install_path    VARCHAR      .fyp = <root>/plugins/<id>/；Claude/Codex = <root>/agent-content/<uid>/
  declared_skills TEXT(JSON)   声明的 skill 路径列表（便于卸载清理）
  mcp_server_refs TEXT(JSON)   落盘的 mcp server 配置文件引用列表
  has_mcp_servers BOOLEAN      是否声明了 MCP server（前端展示警告 chip 用）
  enabled         BOOLEAN
  installed_at    TIMESTAMP
  updated_at      TIMESTAMP
  user_id         BIGINT       默认 1L（仿 PluginFavoriteEntity）
唯一约束: (uid, user_id)
```

### 3.3 启动时 seed

`StoreSourceSeeder`（`ApplicationRunner`，仿 `OfficialPluginSeeder`）：
- 若 `fengyu.marketplace.catalog-url` 已配置且 `store_sources` 表无该 origin，seed 一个 `fengyu-default` 源（FENGYU 类型），保证向后兼容。
- 可通过 `fengyu.store.default-sources`（逗号分隔）声明额外默认源。

### 3.4 延后项

- **评分/评论**（原计划）延后到用户中心服务器。本 spec 不实现 `PluginRatingEntity`。
- `PluginFavoriteEntity` 表已存在（3.0.0 起），但前端 favorites 是本地 Set、未接入——本 spec 不接入，留待用户中心。

## 4. 适配器层与统一条目映射

### 4.1 `UnifiedCatalogEntry` record 字段

```
uid               String     全局唯一 = origin:sourceType:pluginName
origin            String     源标识
sourceType        enum       FENGYU / CLAUDE / CODEX
name              String
displayName       String     Codex 专有（interface.displayName），其他源 = name
description       String
author            Author     { name, email?, url? }（并集；FengYu 只有 name 字符串，映射到 name）
category          String     原始分类词（前端做归一化映射）
keywords          List<String>  Claude/Codex 专有（keywords 字段）；FengYu 为空
homepage          String
pinnedSha         String     Claude 专有；其他为空
sourceRef         SourceRef  规范化的安装源描述；是各 adapter 输出的 union（结构因 sourceType 而异，见 §4.3 各分支: zip-url / git-url / git-subdir / git-local-in-repo）。实现为带 `type` 标签的 sealed interface 或 record。
declaredSkills    List<String>  仅安装后填充（目录阶段为空，延迟 clone）
mcpServers        List<String>  仅安装后填充（server 名称列表）
interfaceMeta     InterfaceMeta Codex 专有 UX 元数据（screenshots/logo/brandColor 等）；其他为空
installed         boolean    （由 UnifiedStoreService 合并时填充）
installedVersion  String
updateAvailable   boolean
enabled           boolean
```

### 4.2 `MarketplaceSourceAdapter` 接口

```java
public interface MarketplaceSourceAdapter {
    StoreSourceType type();
    /** 拉取并翻译成统一条目。origin 由 src.origin() 提供，adapter 用它构造 uid 前缀。 */
    List<UnifiedCatalogEntry> fetchCatalog(StoreSource src);
}
```

`StoreSource` 是个 record: `(origin, sourceType, catalogUrl, name)`，由 `StoreSourceRegistry` 从 `StoreSourceEntity` 构造。

### 4.3 三种 source 模型的解析规则

#### FengYu CatalogAdapter
- 输入: catalog JSON array of `{ id, name, description, version, author, icon, category, permissions, homepage, downloadUrl, official }`（现有 `MarketplaceCatalogEntry` 格式）。
- 映射:
  - `uid = "<origin>:FENGYU:<id>"`
  - `sourceRef = { type: "zip-url", url: downloadUrl }`
  - `pinnedSha = null`
  - 几乎 1:1 包装，**复用** `PluginMarketplaceService` 的解析逻辑。

#### Claude MarketplaceAdapter
- 输入: `.claude-plugin/marketplace.json` → `{ $schema, name, description, owner, renames, plugins: [...] }`。
- 每个 plugin entry 的 `source` 是 union（**关键复杂性**）:
  - `source: "./path"` (string) → **跳过**（本地路径源不支持远程聚合；记录一条 skip 日志）。
  - `source: { source: "url", url, sha }` → git clone 整仓 + checkout sha。
  - `source: { source: "git-subdir", url, path, ref, sha }` → git clone 整仓 + checkout sha + 取子目录。
- 映射:
  - `uid = "<origin>:CLAUDE:<plugin.name>"`
  - origin 规范化: 从顶层 `name` 字段（如 `claude-plugins-official` → `claude-plugins-official`）。
  - `sourceRef = { type: "git-url"|"git-subdir", url, path?, ref?, sha }`
  - `category` 取 entry 的 `category` 字段（如 `security` / `design` / `database`）。
  - `keywords` 取 entry 的 `keywords` 数组。
  - `pinnedSha = entry.source.sha`（若有）。
  - `declaredSkills` / `mcpServers`: 目录阶段**不 clone**，留空；安装时从 `.claude-plugin/plugin.json` 提取（延迟 clone 策略）。
- 处理 `renames` map: 若 entry.name 在 renames 里，用新名作 uid 的 pluginName 部分。

#### Codex MarketplaceAdapter
- 输入: `.agents/plugins/marketplace.json` → `{ name, interface: { displayName }, plugins: [...] }`。
- Codex 的 `source.source` 主要为 `"local"`（官方 workflow 是 repo 内 marketplace + 本地路径）。对 FengYu 聚合:
  - `source: { source: "local", path: "./plugins/x" }` → **需要仓库根**。从 catalogUrl 反推仓库 URL（见 `GitHubUrlResolver`，§4.4），clone 整仓后取 path。
- 映射:
  - `uid = "<origin>:CODEX:<plugin.name>"`
  - origin: 顶层 `name` 字段；展示名取 `interface.displayName`。
  - `sourceRef = { type: "git-local-in-repo", repoUrl: <反推仓库>, ref: <反推 ref>, path }`
  - `category` 取 entry `category`（如 `Productivity`）。
  - `policy` 字段（`installation` / `authentication` / `products`）: **只读记录**，不强制。Codex policy 是 ChatGPT 产品语义，对 FengYu 无意义（`NOT_AVAILABLE` 不阻塞安装；`products` 忽略）。
  - `interfaceMeta`: 提取 entry 的 `interface` 块（`displayName`, `shortDescription`, `longDescription`, `developerName`, `brandColor`, `composerIcon`, `logo`, `logoDark`, `screenshots`, `defaultPrompt`, `websiteURL` 等）。
  - `declaredSkills` / `mcpServers`: 目录阶段不 clone，留空；安装时从 `.codex-plugin/plugin.json` 提取。

### 4.4 `GitHubUrlResolver` 工具

Codex local 源需要从 catalogUrl 反推仓库。处理两类输入:
- `https://raw.githubusercontent.com/<owner>/<repo>/<ref>/.agents/plugins/marketplace.json`
  → `{ repoUrl: "https://github.com/<owner>/<repo>", ref: "<ref>" }`
- `https://github.com/<owner>/<repo>/blob/<ref>/<path>/.agents/plugins/marketplace.json`
  → `{ repoUrl: "https://github.com/<owner>/<repo>", ref: "<ref>" }`
- 其他主机（非 github.com / raw.githubusercontent.com）: 若 URL 是目录文件的父路径，尝试 `git ls-remote` 探测；失败则该源标记为 `last_error="无法解析仓库根"`。

### 4.5 安装时的 sha / 签名校验

#### Claude/Codex（git sha 校验）
```
AgentContentInstaller.install(entry):
  1. clone 源仓库（浅克隆 git clone --depth 1 --branch <ref>，若有 ref；否则 full clone）
  2. git checkout <sha>  (若有 sha)
  3. 若 pinnedSha != null:
       actualSha = git rev-parse HEAD
       if actualSha != pinnedSha: throw IntegrityException(pinnedSha, actualSha)
  4. 取子目录（git-subdir 的 path）
  5. 读 plugin.json:
       Claude: .claude-plugin/plugin.json
       Codex:  .codex-plugin/plugin.json
  6. 提取 skills（plugin.json 的 skills 字段，string 路径或数组）→ 复制到 ~/.fengyu/skills/<uid>/
  7. 提取 mcpServers（plugin.json 的 mcpServers，object 或指向 .mcp.json 的 string）
       → 落盘到 ~/.fengyu/mcp-servers/<uid>.json
  8. 写 PluginInstallRecordEntity
```

sha 校验是**硬约束**: Claude 源若声明 `sha`，不符则拒绝（防供应链篡改）。用户可经配置 `fengyu.store.allow-skip-sha=true` 全局允许跳过（默认 false）。

#### FengYu `.fyp`（sha256 sidecar 推广）
现有 `OfficialPluginSeeder.verifySha256` 只对 official seeder 路径校验 `.sha256` sidecar。本 spec 把该校验**推广到所有 `.fyp` 安装路径**（`PluginPackageService.installFromUrl` / `install(MultipartFile)` / `install(Path)`）: 若存在同名 `<file>.sha256` sidecar，校验之；不符则拒绝。补上当前安全缺口。

### 4.6 缓存与刷新

`StoreSourceRegistry` 对每个源的拉取结果做 **TTL 缓存**（默认 10 分钟，`fengyu.store.cache-ttl-seconds=600`）。`refresh(origin)` 强制刷新并清缓存。安装/卸载操作不触发缓存刷新（install 状态合并从 DB 读）。

## 5. REST API

新控制器 `PluginStoreController`，路径前缀 `/api/plugin-store`，与现有 `PluginMarketplaceController`（`/api/plugin-market`）并存。

### 5.1 商店源管理

| Method | Path | 功能 |
|---|---|---|
| `GET` | `/api/plugin-store/sources` | 列出所有订阅源（含 last_sync 状态） |
| `POST` | `/api/plugin-store/sources` | 添加源: body `{ name, sourceType, catalogUrl }` → 后端规范化 origin、落库、首次拉取校验 |
| `DELETE` | `/api/plugin-store/sources/{origin}` | 取消订阅（不卸载已装插件） |
| `POST` | `/api/plugin-store/sources/{origin}/refresh` | 强制重新拉取并清缓存 |

### 5.2 统一目录

| Method | Path | 功能 |
|---|---|---|
| `GET` | `/api/plugin-store/catalog` | 聚合目录: `List<UnifiedCatalogEntry>`（含 install 状态合并） |
| `GET` | `/api/plugin-store/catalog?sourceType=CLAUDE&category=security&q=text&page=1&size=50` | 过滤: 按源类型、分类、关键字（keywords + name + description 子串）+ 分页 |

### 5.3 安装/卸载/更新

| Method | Path | 功能 |
|---|---|---|
| `POST` | `/api/plugin-store/{uid}/install` | 分派安装: `InstallerDispatcher` 按 sourceType 走对应 installer |
| `POST` | `/api/plugin-store/{uid}/update` | 更新（Claude/Codex = 重新 clone + 校验新 sha；.fyp = 现有 update 路径） |
| `DELETE` | `/api/plugin-store/{uid}` | 危险操作 | 卸载: 删 skills 目录、删 mcp 配置文件、停 worker（若 .fyp）、删 install record |
| `PATCH` | `/api/plugin-store/{uid}/enabled` | enable/disable（body `{ enabled: bool }`） |

### 5.4 安装历史

| Method | Path | 功能 |
|---|---|---|
| `GET` | `/api/plugin-store/history` | 安装记录列表（`PluginInstallRecordEntity` 视图） |

### 5.5 约定

- 所有端点走现有认证（loopback token）。
- `uid` 作为 path 参数，URL-encode 冒号（`anthropics:CLAUDE:browser-use` → `anthropics%3ACLAUDE%3Abrowser-use`）。Spring MVC 自动解码。
- **向后兼容**: 现有 `/api/plugin-market` 保留不动。未来可在其内部委托给新服务，但不在本 spec 范围。

## 6. 前端

### 6.1 新 Pinia store

新文件 `frontend/src/stores/pluginStore.ts`（不复用现有 `stores/plugins.ts`——后者只管 enabled-runtime 列表，避免污染）。持有:
- `sources: StoreSource[]`
- `catalog: UnifiedCatalogEntry[]`（聚合）
- `history: InstallRecord[]`
- filters state（sourceType、category、search、page）

### 6.2 API client 扩展

`frontend/src/api/client.ts` 新增方法: `getStoreSources`, `addStoreSource`, `deleteStoreSource`, `refreshStoreSource`, `getUnifiedCatalog`, `installUnified(uid)`, `updateUnified(uid)`, `uninstallUnified(uid)`, `setUnifiedEnabled`, `getInstallHistory`。
`frontend/src/api/types.ts` 新增 `UnifiedCatalogEntry`, `StoreSource`, `InstallRecord` 类型。

### 6.3 UI 改造（`PluginMarket.vue` 扩展）

现有 `PluginMarket.vue` 已有 plugins/skills 双 tab + 卡片网格 + 详情抽屉。改造点:

1. **新增"商店源"管理区**（顶部 toolbar 或单独 sub-view）: 列源、添加源（表单: name + sourceType 下拉 + catalogUrl）、刷新、删除。每源显示 last_sync 状态（绿/红 + 错误详情）。
2. **卡片增加来源徽章**: 每张卡片标注 `CLAUDE` / `CODEX` / `FENGYU`（小 chip，颜色区分）。
3. **筛选栏增强**: sourceType 多选、category 下拉（聚合三种源的 category 词表，归一化映射到统一显示）、search 框、分页。
4. **详情抽屉扩展**: 对 Claude/Codex 插件显示 `declaredSkills` 列表、`mcpServers` 名称、pinned sha（可复制）、`interfaceMeta`（Codex 的 screenshots/logo/brandColor 用 `<v-img>` + 主题色展示）、`homepage` 链接。若 `has_mcp_servers` 显示警告 chip「声明了 MCP server，待 runtime 启用」。
5. **安装按钮**: 统一入口，后端分派。安装中对 Claude/Codex 显示「clone + 校验中」进度（git clone 比 .fyp 下载慢，需 loading 状态 + 可能的耗时提示）。
6. **布局**: 保留 plugins/skills 双 tab。Claude/Codex 插件与 `.fyp` **在同一 plugins tab 里用来源徽章区分**（不新增第三个 tab）。

### 6.4 category 归一化映射

三种源的 category 词表不同，前端做映射到统一显示。映射表（不精确的归到 `OTHER`）:

| 源 category | 统一 ToolCategory |
|---|---|
| FengYu: `text` | TEXT |
| FengYu: `dev` / Claude: `development` / Codex: `"Development"` | DEV |
| Claude: `security` | 其他（复用现有 `ToolCategory` 无 security，归 OTHER 或新增；spec 实现时决定） |
| Claude: `design` | IMAGE（最接近） |
| Claude: `database` / Codex: `"Database"` | 其他 |
| Claude: `productivity` / Codex: `"Productivity"` | 其他 |
| Claude: `monitoring` | NET |
| 其他 | OTHER |

> 实现阶段会审视是否给 `ToolCategory` 枚举新增 `SECURITY` / `DATA` / `PRODUCTIVITY` 值，或保持现有枚举 + 自由 category 字符串过滤。这是实现细节决策。

## 7. 错误处理

| 场景 | 处理 |
|---|---|
| 商店源拉取失败（网络/HTTP 非 2xx/JSON 解析失败） | `StoreSourceEntity.last_sync_ok=false` + `last_error` 记录；目录请求时该源条目**不出现**（不阻塞其他源）；前端源管理区显示红色状态 + 错误详情 |
| 安装时 git clone 失败 | 抛 `InstallException`，REST 返回 400，前端显示具体错误；**不留半装状态**（agent-content 装失败要回滚已复制的 skills/mcp） |
| sha 校验失败 | 抛 `IntegrityException`（含 expected vs actual），REST 422；提示用户该插件可能被篡改 |
| uninstall 失败中途 | best-effort 清理 + install record 标记 `enabled=false`，记录 error |
| 同 uid 跨源冲突 | uid 含 origin 前缀，不会冲突；前端若展示重名可加 origin 区分 |
| Claude/Codex plugin.json 缺失或格式错 | 安装阶段报错「插件清单无效」，不装 |
| Codex local 源、catalogUrl 无法解析仓库根 | 源标记 `last_error`，该源所有条目不出现 |

## 8. 安全

聚合外部源 = 供应链风险，必须重视:

1. **sha 校验硬约束**: Claude 源若声明 `sha`，clone 后必须 `git rev-parse HEAD == sha`，不符拒绝。默认 `allow-skip-sha=false`。
2. **.fyp sha256 sidecar 推广**: 所有 `.fyp` 安装检查同名 `.sha256` sidecar（不止 official seeder）。
3. **git clone 沙箱**: clone 到临时目录，校验通过后才 copy skills 到 `~/.fengyu/skills/`；clone 的临时仓库用完即删，不留 `.git`。
4. **路径穿越防护**: 复用 `PluginPackageService` 的 zip-slip 保护；agent-content 的 skill 文件复制做 path normalization（`~/.fengyu/skills/<uid>/` 之外的路径拒绝）。
5. **MCP server 配置执行风险**: mcpServers 落盘的 JSON 含 `command`/`args`——**本 spec 只落盘登记，不执行**（执行是 MCP runtime 职责，已声明在范围外）。install record 标记该插件「声明了 N 个 MCP server，待 runtime 启用」，前端展示警告 chip。
6. **HTTPS-only**: 所有 catalogUrl 强制 HTTPS（复用现有 `PluginMarketplaceService` 的 scheme 校验）。

## 9. 测试策略

| 层 | 测试 |
|---|---|
| Adapter 单测 | 用 fixture JSON（Claude marketplace.json 片段、Codex marketplace.json、FengYu catalog）验证翻译成 `UnifiedCatalogEntry` 的字段映射，含 Claude source union 各分支（url / git-subdir / 本地路径跳过） |
| `AgentContentInstaller` 单测 | mock git clone（用本地 fixture 仓库），验证 sha 校验通过/失败、skills 提取、mcpServers 落盘、install record 写入、失败回滚 |
| `InstallerDispatcher` 单测 | 验证按 sourceType 正确分派 |
| `UnifiedStoreService` 集成 | 多源聚合 + 过滤 + install 状态合并 |
| `GitHubUrlResolver` 单测 | raw.githubusercontent.com / github.com/blob 各类 URL 反推 |
| 安全测试 | sha 不匹配拒绝、zip-slip 拒绝、路径穿越拒绝、HTTPS-only |
| 不写 | 前端 E2E 暂不在范围（手动验证）；现有 `.fyp` 路径回归用 `scripts/e2e-smoke.sh` |

## 10. 配置项

```yaml
fengyu:
  store:
    cache-ttl-seconds: 600            # 目录缓存 TTL
    git-clone-timeout-seconds: 120    # clone 超时
    default-sources:                  # 启动时 seed 的默认源（逗号分隔 name|type|url，可空）
    allow-skip-sha: false             # 是否允许用户跳过 sha 校验
```

## 11. 明确不在本 spec 范围（YAGNI 边界）

- ❌ **MCP runtime**: 让 FengYu AI 真正调用提取出的 MCP server（单独后续 spec）。
- ❌ **评分/评论**: 延后到用户中心服务器。
- ❌ **收藏持久化接入**: `PluginFavoriteEntity` 表已存在但不接入前端（留待用户中心）。
- ❌ **发布到 Claude/Codex 商店**（反向，已排除）。
- ❌ **原生运行 Claude/Codex hooks/commands**（深度集成，已排除）。
- ❌ **Codex `policy.installation = NOT_AVAILABLE` 强制**: 只读记录，不阻塞。
- ❌ **Claude 本地路径源**（`source: "./path"`）: 跳过，只支持远程源。
- ❌ **现有 `/api/plugin-market` 迁移**: 保留不动。
- ❌ **前端 E2E 自动化测试**: 手动验证。

## 12. 交付与验证

- **构建验证**: `./mvnw clean package -f FengYu/pom.xml -DskipTests` 通过；相关单测通过。
- **前端构建**: `cd frontend && npm run build` 通过。
- **回归**: `scripts/e2e-smoke.sh` 不回归（现有 `.fyp` 端点未改）。
- **手动 smoke**: 添加一个 Claude marketplace 源（如 `https://raw.githubusercontent.com/anthropics/claude-plugins-public/main/.claude-plugin/marketplace.json`），列目录，安装一个小型 skills-only 插件，验证 skills 落到 `~/.fengyu/skills/`。

## 13. 未决问题（实现阶段决定）

1. `ToolCategory` 枚举是否新增值（`SECURITY` / `DATA` / `PRODUCTIVITY`）还是保持现有 + 自由字符串过滤？→ 实现时看现有 i18n labelKey 的扩展成本。
2. Codex `interfaceMeta` 的 `screenshots`/`logo` 是相对路径，需 clone 后才能解析为可展示资源——目录阶段如何展示占位？→ 先用 `developerName` + `brandColor` 渲染卡片，screenshots 留到安装后。
3. 同一 skill id 被 Claude 插件和 FengYu 已有 skill 撞名怎么办？→ 用 `<uid>` 作为目录名隔离（`~/.fengyu/skills/<uid>/<skill>/SKILL.md`），但需确认 FengYu skill 发现逻辑是否支持嵌套目录。实现时验证 `SkillDiscoveryService` 的扫描规则。

---

## 附录 A: Claude marketplace.json 字段速查

来源: `https://github.com/anthropics/claude-code/blob/main/.claude-plugin/marketplace.json` + `systemprompt.io` 指南。

顶层:
```json
{
  "$schema": "https://anthropic.com/claude-code/marketplace.schema.json",
  "name": "<marketplace-id>",
  "description": "...",
  "owner": { "name": "...", "email": "..." },
  "renames": { "old-name": "new-name" },
  "plugins": [ /* 见下 */ ]
}
```

plugin entry（`source` 是 union）:
```json
// 形态 1: 本地路径（跳过）
{ "name": "x", "description": "...", "category": "dev", "source": "./plugins/x" }

// 形态 2: url（整仓）
{ "name": "x", "description": "...", "category": "dev",
  "source": { "source": "url", "url": "https://github.com/o/r.git", "sha": "abc123" } }

// 形态 3: git-subdir（整仓 + 子目录）
{ "name": "x", "description": "...", "category": "dev",
  "source": { "source": "git-subdir", "url": "https://github.com/o/r.git",
              "path": "plugins/x", "ref": "main", "sha": "abc123" } }

// 可选字段: author{name,email,url}, homepage, keywords[], version,
//           strict(bool), skills[](相对路径), lspServers{}
```

Claude plugin.json (`.claude-plugin/plugin.json`):
```json
{
  "name": "kebab-case-name",
  "version": "1.0.0",
  "description": "...",
  "author": { "name": "...", "email": "...", "url": "..." },
  "homepage": "...", "repository": "...", "license": "MIT",
  "keywords": ["..."],
  "skills": ["skills/SKILL.md"],
  "hooks": "hooks/hooks.json",
  "commands": ["commands/x.md"],
  "agents": ["agents/x.md"],
  "mcpServers": { "name": { ... } }
}
```

## 附录 B: Codex marketplace.json + plugin.json 字段速查

来源: `https://github.com/openai/codex/blob/main/codex-rs/skills/src/assets/samples/plugin-creator/references/plugin-json-spec.md` + `developers.openai.com/plugins/build/plugins`。

marketplace.json (`.agents/plugins/marketplace.json`):
```json
{
  "name": "openai-curated",
  "interface": { "displayName": "ChatGPT Official" },
  "plugins": [
    {
      "name": "linear",
      "source": { "source": "local", "path": "./plugins/linear" },
      "policy": {
        "installation": "AVAILABLE",          // NOT_AVAILABLE | AVAILABLE | INSTALLED_BY_DEFAULT
        "authentication": "ON_INSTALL"        // ON_INSTALL | ON_USE
      },
      "category": "Productivity"
    }
  ]
}
```

plugin.json (`.codex-plugin/plugin.json`):
```json
{
  "name": "plugin-name",
  "version": "1.2.0",
  "description": "...",
  "author": { "name": "...", "email": "...", "url": "..." },
  "homepage": "...", "repository": "...", "license": "MIT",
  "keywords": ["..."],
  "skills": "./skills/",
  "hooks": "./hooks.json",
  "mcpServers": "./.mcp.json",                 // 或 object: { "counter": { "type":"http","url":"..." } }
  "apps": "./.app.json",
  "interface": {
    "displayName": "...", "shortDescription": "...", "longDescription": "...",
    "developerName": "...", "category": "...",
    "capabilities": ["Interactive","Write"],
    "websiteURL": "...", "privacyPolicyURL": "...", "termsOfServiceURL": "...",
    "defaultPrompt": ["...", "...", "..."],     // 至多 3 条，每条 ≤128 字符
    "brandColor": "#3B82F6",
    "composerIcon": "./assets/icon.png",
    "logo": "./assets/logo.png", "logoDark": "./assets/logo-dark.png",
    "screenshots": ["./assets/s1.png", "./assets/s2.png"]
  }
}
```
