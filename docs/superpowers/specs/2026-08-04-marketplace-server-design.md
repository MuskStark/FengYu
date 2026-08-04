# FengYu 插件市场服务（独立项目 · 认证中心 + 发布门户 + 聚合发布）

- **状态**: 设计草案，待评审
- **日期**: 2026-08-04
- **作者**: MaskStark（AI 辅助设计）
- **目标版本**: 全新独立仓库 v1.0.0（独立版本线，与 FengYu 主程序解耦）
- **关联文档**: `2026-08-03-plugin-store-codex-claude-compat-design.md`（主程序侧的统一插件商店消费端，本服务是其上游数据源之一）

---

## 1. 目标与背景

### 1.1 目标

构建一个**全新独立仓库**的 Spring Boot + Java 服务，作为 FengYu 生态的**插件市场服务**，承担三大职责：

1. **认证中心** —— 为 FengYu 主程序用户、市场作者、管理员提供统一认证。主程序在网络可达时走市场认证；**不可达时回落本地账户**（沿用主程序现有 `SecurityContext` / `AuthProvider` / 前端 `AccountProvider` 的「本地离线 vs 已认证」设计接缝）。
2. **发布门户（仅 FengYu 生态上传）** —— 第三方作者上传完整 `.fyp` 制品包；服务端做 schema/zip-slip/大小/worker-JAR 校验、SHA256 摘要、入库；管理员审核（批准/拒绝）后发布到 FengYu catalog。
3. **聚合 + 多格式 marketplace 发布** —— 聚合 FengYu catalog（来自发布门户）+ Claude Code 官方市场 + Codex 官方市场（**镜像整合**上游，**不**接受作者上传 Claude/Codex 插件）；对外发布三种生态的原生 marketplace 清单文件，供 Claude Code / Codex / 其他 FengYu 实例订阅；FengYu 主程序通过 HTTP 消费统一 catalog。

### 1.2 与主程序现有统一插件商店的关系

主程序已有的 `fan.summer.fengyu.plugin.store`（`/api/plugin-store/*`）是**消费端**：它订阅外部 marketplace 源、聚合、安装。本服务是**供给端**：它是消费端可订阅的**一个源**（或多个：一个 FengYu catalog 源 + 可选的 Claude/Codex 镜像源）。

```
┌─────────────────────────── 本服务（独立仓库 / 独立进程）───────────────────────────┐
│  认证中心  │  发布门户（.fyp 上传→审核→发布）  │  聚合（FY+Claude+Codex）+ 发布清单 │
└───────────┬─────────────────────────────────────┬────────────────────────────────┘
            │ HTTP/REST（JWT）                    │ HTTP/REST（catalog/清单文件）
            ▼                                     ▼
┌──────────────────── FengYu 主程序（现有，本 spec 范围外修改最小化）──────────────────┐
│  AccountProvider(市场/本地)   ←→   plugin/store 消费端（订阅本服务 catalog 源）       │
└────────────────────────────────────────────────────────────────────────────────────-┘
```

### 1.3 明确不在本 spec 范围（YAGNI 边界）

- ❌ **安装生命周期** —— git clone、sha 校验、`.fyp` 解压安装、启用/禁用标记。这些**留在主程序**（`AgentContentInstaller` / `PluginPackageService`）。本服务只生产 catalog 元数据 + 制品下载 URL + 完整性摘要，**不**执行安装。
- ❌ **MCP runtime** —— 拉起 MCP server 进程、把工具注入 AI。主程序侧后续工作（见 `2026-08-03` spec 的 A-3 / §11）。
- ❌ **Claude/Codex 插件作者上传** —— v1 对 Claude/Codex **只做上游官方市场的镜像聚合**，不开放作者向本服务提交 Claude/Codex 插件。（理由：它们的制品是 git 仓库而非 tarball，作者上传语义不成立；聚合上游官方市场即可覆盖。）
- ❌ **FengYu 主程序的安装态/历史迁移** —— 主程序现有 `plugin_install_records` 表与本服务无关，不迁移。
- ❌ **实时推送 / WebSocket** —— catalog 走轮询 + 缓存（沿用主程序 `StoreSourceRegistry` 的 TTL 模型）。
- ❌ **支付 / 付费插件** —— v1 所有插件免费。
- ❌ **评论 / 评分 / 论坛** —— v1 不做社区功能（可在数据库 schema 预留扩展位，但不实现 UI/逻辑）。

### 1.4 标注的设计假设（可推翻）

以下假设由设计者在用户澄清基础上填入。审阅时可推翻：

- **A-1 仓库形态**: 全新独立 Git 仓库，独立版本线 `v1.0.0` 起，**不**进 FengYu reactor。与 FengYu 主程序通过 HTTP + 共享 JSON 契约耦合，**不**共享 Java 模块。
- **A-2 技术栈**: Spring Boot 3.x + Java 21（与主程序一致），JPA/Hibernate，多 DB（H2/SQLite/MySQL/PostgreSQL，与主程序 `DbType` 对齐）。Maven。
- **A-3 认证回落**: 主程序访问认证中心失败（网络/宕机）→ 回落本地虚拟用户（`LOCAL_VIRTUAL_USER_ID=1`）。这是**优雅降级**，不是双写。
- **A-4 上传物形态**: FengYu 作者上传**完整 `.fyp` zip + `.sha256` sidecar**（GNU `sha256sum` 格式）；服务端不重新构建。
- **A-5 聚合范围**: v1 聚合 FengYu catalog（自有）+ Claude 官方市场 + Codex 官方市场；未来生态以新增 adapter 扩展。
- **A-6 部署形态**: 可自托管 + 官方实例。v1 出一个可独立启动、本地可跑、可配置指向任意部署实例的服务；官方实例地址作为默认值。
- **A-7 资产存储**: FengYu `.fyp` 的 `icon` 是 Iconify 名字符串（无需二进制存储）；Codex/Claude 的 logo/screenshots 是 git 仓库内相对路径 → 聚合时**不**拉取二进制，只保留 URL/路径引用并在发布清单里按各生态原生规则输出（下游安装时自行解引用）。

---

## 2. 整体架构

### 2.1 模块/包结构（独立仓库）

```
fengyu-marketplace-server/                   （新仓库根）
├── pom.xml                                   （Spring Boot 3 + Java 21；独立版本线）
├── src/main/java/fan/summer/marketplace/
│   ├── MarketplaceApplication.java           （main 类）
│   ├── config/                               （Spring 配置：安全、CORS、Jackson、多 DB）
│   ├── auth/                                 【支柱 1】认证中心
│   │   ├── controller/   AuthController, AccountController, AdminUserController
│   │   ├── service/      AuthService, JwtService, TokenService, RefreshTokenService
│   │   ├── security/     JwtAuthenticationFilter, SecurityConfig, MarketUserDetails
│   │   ├── entity/       UserEntity, RefreshTokenEntity, DeviceEntity
│   │   ├── repository/   UserRepository, RefreshTokenRepository, DeviceRepository
│   │   └── dto/          LoginRequest, RegisterRequest, AuthTokens, AccountView, ...
│   ├── publish/                             【支柱 2】发布门户
│   │   ├── controller/   SubmissionController（作者）、ReviewController（管理员）
│   │   ├── service/      SubmissionService, ArtifactValidationService, ReviewService,
│   │   │                 PublishService
│   │   ├── validate/     ManifestSchemaValidator（用 `com.networknt:json-schema-validator`，对应 CLI 的 Ajv）, ArchiveInspector,
│   │   │                 WorkerJarInspector, ForbiddenEntryGuard
│   │   ├── entity/       PluginEntity, PluginVersionEntity, SubmissionEntity,
│   │   │                 ReviewRecordEntity, ArtifactAssetEntity
│   │   ├── repository/   PluginRepository, PluginVersionRepository, ...
│   │   ├── storage/      ArtifactStore（本地 FS / S3 抽象）, Sha256Sidecar
│   │   └── dto/          UploadResponse, SubmissionView, ReviewDecision, ...
│   ├── catalog/                             【支柱 3】聚合 + 发布
│   │   ├── controller/   CatalogController（统一查询）, MarketPublishController（清单发布）
│   │   ├── service/      CatalogAggregator, SourceFetchService
│   │   ├── adapter/      SourceAdapter（接口）, FengYuSelfCatalogAdapter（查本地已发布）,
│   │   │                 ClaudeUpstreamAdapter, CodexUpstreamAdapter（镜像上游官方市场）,
│   │   │                 GitHubUrlResolver
│   │   ├── publish/      FengYuCatalogPublisher, ClaudeMarketplacePublisher,
│   │   │                 CodexMarketplacePublisher（三种清单文件生成）
│   │   ├── resolve/      CodexMcpStringResolver（"./.mcp.json" 字符串形式解析）
│   │   ├── entity/       CachedUpstreamSourceEntity, CacheEntryEntity
│   │   ├── repository/   ...
│   │   └── dto/          UnifiedCatalogEntry, CatalogQuery, SourceBadge, ...
│   ├── common/                              （共享：异常、PathSafety、BoundedHttp、版本比较）
│   └── integration/                         （仅放契约示例 JSON / OpenAPI 文档，供主程序 spec 参考；无运行时代码）
├── src/main/resources/
│   ├── application.yml                       （默认配置）
│   ├── schemas/manifest.schema.json          （从 FengYu toolchain/spec 复制，作为权威源）
│   └── db/migration/                         （Flyway 迁移；不依赖 ddl-auto）
└── src/test/...
```

**设计原则**：三大支柱分包清晰、单向依赖（`auth` 不依赖 `publish`/`catalog`；`publish` 和 `catalog` 可读 `auth` 的用户身份；`catalog` 的发布器读 `publish` 的已发布版本）。共享逻辑下沉 `common/`。

### 2.2 概念数据流

```
【作者上传 .fyp】         【管理员审核】         【发布】
   SubmissionController ──→ SubmissionService ──→ ReviewService ──→ PublishService
        │  校验 + SHA256        │  状态机              │  写 PluginEntity/Version
        ▼                       ▼                      ▼
   ArtifactStore            ReviewRecordEntity     PluginEntity(published=true)
   (本地FS/S3)                                     PluginVersionEntity(active=true)
                                                          │
                                                          ▼
                            ┌──────────── CatalogAggregator ────────────┐
                            │  本服务已发布版本  ←─ publish.PluginRepository │
                            │  Claude 官方市场  ←─ ClaudeUpstreamAdapter   │
                            │  Codex 官方市场   ←─ CodexUpstreamAdapter    │
                            └──────────────────┬────────────────────────-┘
                                               │ 合并 + 去重 + 排序
                                               ▼
                                     UnifiedCatalogEntry 列表
                          ┌────────────────────┼─────────────────────┐
                          ▼                     ▼                     ▼
              CatalogController          MarketPublishController   （供主程序消费）
              GET /api/catalog           生成三种清单文件            （HTTP + JWT）
              （搜索/过滤/分页）          /marketplaces/fengyu.json
                                         /marketplaces/claude.json
                                         /marketplaces/codex.json
```

---

## 3. 支柱 1：认证中心（带本地回落）

### 3.1 用户模型

- **`UserEntity`**（表 `users`）：`id`、`username`（唯一）、`email`（唯一）、`passwordHash`（bcrypt）、`displayName`、`avatarUrl`、`status`（active/disabled）、`createdAt`、`updatedAt`。
  - **角色**用独立的 `user_roles` 多对多表（`userId` + `role` 枚举 `USER`/`AUTHOR`/`ADMIN`），角色可叠加（作者自动也是 USER；管理员可同时是作者）。**不用**单个 `userType` 整数字段——避免主程序 `SysUserEntity.userType` 的 0/1 单值歧义，且支持一人多角。
  - 每个 USER 隐含 `USER` 角色（注册时写入）；`AUTHOR`/`ADMIN` 由管理员授予。
- **`RefreshTokenEntity`**（表 `refresh_tokens`）：`id`、`userId`、`tokenHash`（SHA256 of refresh token）、`deviceLabel`、`expiresAt`、`revokedAt`、`createdAt`。支持多设备 + 撤销。
- **`DeviceEntity`**（表 `devices`，可选 v1 精简）：`userId`、`deviceId`、`label`、`lastSeenAt`。支撑「多设备登录列表」。
- **首管理员引导**：首次启动若 `users` 表为空 → 通过一次性引导令牌（配置项 `market.bootstrap.admin-token`，仅首次有效）创建第一个 ADMIN。**不**用开放注册的第一个用户自动成管理员（防止公网实例被抢占）。

### 3.2 认证流程

**JWT 设计**（HS256 起步，后续可升 RS256）：
- **Access Token**：短期（默认 15 分钟），载荷 `{ sub, uid, roles[], exp, iat, jti }`。
- **Refresh Token**：长期（默认 30 天，可配置），不透明随机串（256-bit），仅 `/auth/refresh` 接受；存哈希不存明文；刷新时**轮换**（旧的 revoke，发新的）。

**端点**（`/api/auth/*`，除注册/登录外都要 Access Token）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/register` | 开放注册（可由 `market.registration.enabled` 关闭）。body: `{username,email,password}`。返回 tokens。 |
| POST | `/api/auth/login` | `{usernameOrEmail, password, deviceLabel?}` → `{accessToken, refreshToken, expiresIn, user}` |
| POST | `/api/auth/refresh` | `{refreshToken}` → 新 tokens（轮换） |
| POST | `/api/auth/logout` | 撤销当前 refresh token（当前 device） |
| POST | `/api/auth/logout-all` | 撤销该用户所有 refresh token |
| GET  | `/api/auth/me` | 当前用户视图（roles、displayName 等） |
| PATCH | `/api/auth/me` | 更新 displayName / avatarUrl / 改密码（需旧密码） |
| GET  | `/api/auth/devices` | 列出活跃设备（label、lastSeen、可单独撤销） |

**安全**：
- Spring Security + `SecurityFilterChain`：`/api/auth/register|login|refresh` 放行；`/api/admin/**` 要 `ADMIN`；其余要已认证。
- `JwtAuthenticationFilter`（`@Order` 在 Spring Security 链内）：解析 Bearer JWT → 填充 `MarketUserDetails`（含 roles）→ 方法级 `@PreAuthorize("hasRole('ADMIN')")`。
- 登录限流：同 IP + 同 username 滑窗（默认 5 次/分钟），失败 5 次锁定 15 分钟。
- 密码策略：≥8 位，含字母+数字（可配置）。

**错误响应契约**（统一 JSON，供主程序集成 spec 依赖）：

| HTTP | `code` | 触发 |
|---|---|---|
| 401 | `UNAUTHORIZED` | 缺/坏 access token |
| 401 | `TOKEN_EXPIRED` | access token 过期（主程序据此触发 refresh） |
| 403 | `FORBIDDEN` | 角色不足 |
| 409 | `USERNAME_TAKEN` / `EMAIL_TAKEN` | 注册冲突 |
| 422 | `INVALID_CREDENTIALS` | 登录失败 |
| 423 | `ACCOUNT_LOCKED` | 限流锁定（带 `retryAfterSeconds`） |

响应体：`{ "code": "...", "message": "...", "retryAfterSeconds"?: N }`。主程序 `MarketAuthClient`（§3.3）按 `code` 决定是否回落本地：`TOKEN_EXPIRED` → 尝试 refresh；refresh 也失败或其它 401 → 回落。

### 3.3 主程序集成（回落本地）

**主程序现状**（已勘探）：单虚拟用户 `id=1 "ZFlow-Summer"`、静态每启动 token、无 Spring Security、`SecurityContext`/`AuthProvider` 是 Noop、前端 `AccountProvider` 是 local stub（`signIn()` 抛异常）。

**集成方案（最小改动主程序）**：

1. **主程序新增 `MarketAuthClient`**（HTTP 客户端，调用市场 `/api/auth/*`），实现 `AuthProvider`：
   - `authenticate(login, password)` → 调市场 `/auth/login`，存 access/refresh token 到本地安全存储。
   - `isEnabled()`：配置 `fengyu.auth.market-url` 非空时返回 true。
   - `currentUserId()`：从 JWT 解析；token 失效时尝试 refresh；refresh 也失败 → **回落** `LOCAL_VIRTUAL_USER_ID=1` 并标记 `offline=true`。
2. **`MarketAccountProvider`**（前端，实现 `accountProvider.ts` 的 `AccountProvider` 接口），在应用初始化时通过 `setAccountProvider()` 注入，替换 local stub：
   - `getCurrentUser()` → 调主程序代理的 `/api/account/me`（主程序再转发市场）。
   - `signIn()` / `signOut()` → 同上代理。
3. **回落策略（A-3）**：
   - 主程序启动时探活市场 `/actuator/health`（超时 2s）。
   - 不可达 → 沿用现有 Noop `AuthProvider`（虚拟用户 id=1），前端 AccountProvider 退回 local stub（`authenticated:false`）。**应用完全可用**，只是不区分真实用户。
   - 可达但 token 失效且 refresh 失败 → 同上回落 + 提示「认证中心不可达，已切换本地模式」。
   - 这是**优雅降级**，不是数据双写：回落期间产生的数据归属虚拟用户 id=1；下次认证成功后，这些数据**不**自动迁移归属（v1 明确不做，避免数据冲突）。

**契约**：主程序代理市场的 `/api/auth/me` → 暴露为主程序 `/api/account/me`（透传 JWT）。这样前端只跟主程序说话，JWT 不直接进浏览器（桌面端 token 仍由 Electron 主进程托管，renderer 经 `contextBridge` 取，与现有 `preload.ts` 模式一致）。

> **关于主程序的改动**：本 spec 承认主程序需要**配套改动**（新增 MarketAuthClient、MarketAccountProvider、TokenAuthFilter 扩展校验 JWT）。这部分**单独成 spec**（`主程序市场认证集成`），不在本服务 spec 内详述；本 spec 只定义**市场侧的 HTTP 契约**（§3.2 端点 + JWT 载荷 + 错误码），作为集成 spec 的上游依据。

---

## 4. 支柱 2：发布门户（FengYu `.fyp` 上传 + 审核）

### 4.1 提交状态机

```
        作者上传                管理员
 DRAFT ────────→ PENDING_REVIEW ──→ APPROVED ──→ PUBLISHED
  │                  │   │              │
  │（作者撤回）       │   │（拒绝）       │（管理员下架）
  └←── WITHDRAWN ←───┘   ↓              ↓
                      REJECTED     UNPUBLISHED
```

- 状态存在 `SubmissionEntity.status`。
- `PUBLISHED` 时写入 `PluginEntity`（如不存在）+ `PluginVersionEntity(active=true)`，并把同插件旧版本 `active` 置 false。
- `UNPUBLISHED` 保留制品（可重新发布）；`REJECTED`/`WITHDRAWN` 保留审计记录但制品不可下载。

### 4.2 上传契约

**端点**（`/api/submissions`，需 `AUTHOR` 角色）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/submissions` | multipart：`file`（`.fyp`）+ 可选 `sha256`（`.sha256` sidecar 文本，或单独字段）。返回 `{submissionId, status:DRAFT, validation: {...}}`。 |
| POST | `/api/submissions/{id}/submit` | DRAFT → PENDING_REVIEW（触发完整校验）。 |
| POST | `/api/submissions/{id}/withdraw` | 作者撤回。 |
| GET  | `/api/submissions` | 列自己的提交（作者视角）。 |
| GET  | `/api/submissions/{id}` | 提交详情（含校验报告）。 |

**上传流程（`SubmissionService.upload`）**：
1. 接收文件，**立即**算 SHA256（流式，`MessageDigest("SHA-256")`）。
2. 与上传的 sidecar 摘要比对（若提供）；不符 → 拒绝（422）。
3. 大小上限：压缩 ≤100 MB，解压 ≤300 MB（移植 `archive.mjs` 的 cap）。
4. **不落正式存储**前先过 `ArtifactValidationService`（见 §4.3）；通过才写 `ArtifactStore`。
5. 写 `SubmissionEntity(status=DRAFT)` + 校验报告 JSON。

### 4.3 校验管线（`ArtifactValidationService`）

把 FengYu 现有 CLI/host 的校验逻辑**移植到服务端**，作为上传的硬门禁。复用清单（已在勘探中确认）：

| 校验项 | 来源 | 端口到服务端 |
|---|---|---|
| JSON Schema（schemaVersion/id/name/...） | `toolchain/spec/manifest.schema.json` | 复制 schema 到本仓库 `resources/schemas/`，用 `com.networknt:json-schema-validator`（Java，对应 Ajv）。 |
| 语义校验（aiTool 唯一性、schema 是 object、timeout 1–600） | `manifest.mjs:19-64` + `PluginPackageService.validate:220-288` | 移植为 Java。 |
| zip-slip / 绝对路径 / 重复条目 / 大小 cap | `archive.mjs:36-63` | 移植；用 JDK `ZipFile` 流式遍历。 |
| 不解压读取 `manifest.json`（≤1 MB） | `archive.mjs` + `PluginPackageService.readArchiveManifest:108-119` | 移植；按 entry 名查找，不解压。 |
| `ui.entry` 在包内存在 | `validatePluginArchive` | 同上。 |
| backend 存在时 `command == "java -jar backend/worker.jar"`、`backend/worker.jar` 存在、`Main-Class` 非空 | `manifest.mjs:115-140` | 移植；读 jar 的 `META-INF/MANIFEST.MF` 不执行。 |
| 禁止条目（`.git`、`node_modules`、`target`、`settings.xml`、`.npmrc`、`.env`、symlink） | `manifest.mjs:182-183,256` | 移植；遍历 entry 时拒绝。 |
| SHA256 摘要 | `OfficialPluginSeeder.sha256Hex:88-103` | 服务端**主动生成**（不信任作者 sidecar，只作交叉校验）。 |

**关键安全决策**：服务端**永不解压整个包到磁盘做校验**——所有校验在 zip 流上完成（entry 名 + 大小 + 选中条目按需读字节）。解压只在「发布后、作者/管理员下载制品」时按需做（且走 `PluginContentPathSafety` 的路径 containment）。

### 4.4 审核（管理员，`/api/admin/reviews`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/reviews?status=PENDING_REVIEW` | 待审队列（分页）。 |
| GET | `/api/admin/reviews/{submissionId}` | 详情：校验报告、manifest 预览、作者信息。 |
| POST | `/api/admin/reviews/{submissionId}/decision` | body `{decision: APPROVE|REJECT, note?}` → 写 `ReviewRecordEntity`、状态流转。 |
| POST | `/api/admin/plugins/{pluginId}/unpublish` | 已发布下架（全版本或指定版本）。 |

- 审核记录 `ReviewRecordEntity`：`submissionId`、`reviewerId`、`decision`、`note`、`createdAt`。不可改（追加式审计）。
- 管理员可在审核界面看到**完整 manifest** 和**校验报告**，但**不**自动执行包内任何东西。

### 4.5 制品存储（`ArtifactStore`）

- **抽象**：`ArtifactStore` 接口，`save(idempotencyKey, bytes)` / `openStream(artifactId)` / `delete(artifactId)`。
- **v1 实现**：本地文件系统，根目录 `market.artifacts.root`（默认 `<工作目录>/.market/artifacts/`），按 `pluginId/version/<sha256-prefix>/<filename>.fyp` 分层存放（prefix 散列避免单目录爆炸）。
- **预留 S3**：接口设计兼容 S3（`putObject`/`getObject`），v2 实现 `S3ArtifactStore`，配置 `market.artifacts.backend=fs|s3`。
- **幂等**：以 `SHA256(制品)` 为去重键；同一作者重复上传同 sha 的包 → 复用，不重复存储（content-addressable）。
- **下载**：发布后的制品通过 `GET /api/catalog/{pluginId}/{version}/download`（短链/重定向到签名 URL，v1 直接流式返回 + Content-Disposition）。

### 4.6 版本与升级检测

- `PluginVersionEntity`：`pluginId`、`version`（semver）、`submissionId`、`sha256`、`downloadUrl`、`manifestJson`、`active`、`createdAt`。
- 唯一约束 `(pluginId, version)`。
- `active` 版本即 catalog 里该插件的「最新发布版本」；历史版本保留可查（`GET /api/catalog/{pluginId}/versions`）。
- 升级检测：主程序侧 `compareVersions`（`PluginMarketplaceService:113-131`）已经实现；本服务 catalog 每条带 `version`，主程序自行比较。本服务**不**实现升级推送。

---

## 5. 支柱 3：聚合 + 多格式 marketplace 发布

### 5.1 聚合模型

**`CatalogAggregator`** 合并三类来源，产出统一 `UnifiedCatalogEntry` 列表：

```java
record UnifiedCatalogEntry(
    String uid,                    // "<origin>:<sourceType>:<pluginName>"（与主程序消费端 uid 规则一致）
    StoreSourceType sourceType,    // FENGYU / CLAUDE / CODEX
    String origin,                 // 来源标识（"marketplace.fengyu.app" / 上游 url host）
    String name, String description, String version,
    String author, String icon,    // FengYu: Iconify 名；Claude/Codex: 可能空
    String category, List<String> keywords,
    StoreAuthor authorInfo,        // {name,email,url}
    SourceRef sourceRef,           // sealed: ZipUrlSource | GitUrlSource | GitSubdirSource | GitLocalInRepoSource
    String pinnedSha,              // FengYu: 制品 SHA256；Claude: git sha；Codex: 解析后 HEAD sha 或 null
    InterfaceMeta interfaceMeta,   // Codex 的丰富 UX 元数据（v1 只透传，不解析资产二进制）
    boolean official,              // 是否官方源
    Instant aggregatedAt
) {}
```

> **与主程序 `UnifiedCatalogEntry` 的关系**：字段对齐（主程序消费端 record 见 `FengYu/.../plugin/store/UnifiedCatalogEntry.java`）。本服务是**生产者**，主程序是**消费者**；二者通过 HTTP + JSON 契约耦合。**不**共享 Java 类（独立仓库），JSON schema 是契约。

**三类来源**：

| 来源 | adapter | 数据出处 |
|---|---|---|
| 本服务已发布 FengYu 插件 | `FengYuSelfCatalogAdapter`（内部，直接查 `PluginRepository`） | `publish` 支柱的 `PluginEntity` + active `PluginVersionEntity` |
| Claude 官方市场 | `ClaudeUpstreamAdapter` | 上游 `.claude-plugin/marketplace.json`（配置的 URL） |
| Codex 官方市场 | `CodexUpstreamAdapter` | 上游 `.agents/plugins/marketplace.json` |

- **上游聚合是镜像/缓存**：`SourceFetchService` 按 TTL（默认 600s，配置 `market.aggregate.cache-ttl-seconds`）拉取上游，存 `CachedUpstreamSourceEntity`（原始 JSON + 拉取时间 + ok/error）。
- **上游不可达时**：用上次缓存（哪怕过期）+ 在 catalog 响应里标记 `stale=true`。**不**抛错（聚合要健壮）。
- **adapter 复用**：Claude/Codex adapter 的解析逻辑与主程序 `ClaudeMarketplaceAdapter`/`CodexMarketplaceAdapter` 一致（**移植**，因为独立仓库不共享代码）。包括 `GitHubUrlResolver`（Codex local 源 → repo+ref+path 解析）。

### 5.2 Codex `mcpServers` 字符串形式解析（`CodexMcpStringResolver`）

**问题**（勘探确认）：Codex `plugin.json` 的 `mcpServers` 可以是 `"./.mcp.json"` 字符串路径，指向仓库内另一个文件。主程序 `AgentContentInstaller.writeMcpConfig` 直接落盘原始节点，**不**解引用——下游拿到字面路径串。

**本服务职责**：在**聚合阶段**解析字符串形式 → 真正的 server 定义对象，写入发布的 `marketplace.json`。流程：
1. adapter 遇到 `mcpServers: "<path>"` → 标记需解析。
2. `CodexMcpStringResolver`：用 `GitHubUrlResolver` 得到 repo+ref，通过 GitHub raw API（或浅克隆临时目录）读取 `<repo>/<path>@<ref>` 的内容。
3. 解析为 server 定义对象，回填到 catalog entry 的 mcpServers。
4. 失败（仓库私有/路径不存在）→ entry 标记 `mcpResolutionError`，catalog 里仍展示但置警告徽章；**不**让单个坏插件拖垮整个聚合。

> v1 范围：只解析字符串形式。字符串解析后得到的是 STDIO/SSE/HTTP server 定义——**本服务不启动它们**（YAGNI，§1.3）。启动是主程序 MCP runtime 的事。

### 5.3 对外发布（`MarketPublishController`）

**三类清单文件，一一对应生态原生格式**（让 Claude Code / Codex / FengYu 直接订阅）：

| 路径 | 格式 | 内容 |
|---|---|---|
| `GET /marketplaces/fengyu.json` | FengYu catalog JSON 数组（`MarketplaceCatalogEntry[]`） | 本服务已发布的 FengYu 插件（**不**含 Claude/Codex，因为它们不是 `.fyp`）。每条 `downloadUrl` 指向本服务下载端点。**新增字段**：`sha256`（制品摘要，补齐主程序 catalog 缺失的字段）。 |
| `GET /marketplaces/claude.json` | `.claude-plugin/marketplace.json` 结构 | 从本服务聚合的 Claude 源透传/重组；可选地把 FengYu 插件也映射进来（v1 **不**做反向映射，YAGNI）。 |
| `GET /marketplaces/codex.json` | `.agents/plugins/marketplace.json` 结构 | 同上，Codex 源透传。 |

- 这些端点**公开**（无认证，或只限流），供任意客户端订阅。
- **缓存**：清单生成走内存缓存（同 TTL），避免每次请求全量聚合。
- **FengYu catalog 字段补齐**：主程序 `MarketplaceCatalogEntry`（`FengYu/.../plugin/market/MarketplaceCatalogEntry.java`）当前**无 sha256 字段**。本服务发布时**新增** `sha256`；主程序消费端后续 spec 补齐读取（向后兼容，主程序旧版忽略未知字段）。

### 5.4 主程序消费（契约）

主程序现有 `StoreSourceRegistry`（订阅多源）配置一个新源指向本服务：

```
fengyu.marketplace.catalog-url = https://marketplace.fengyu.app/marketplaces/fengyu.json
```

主程序 `FengYuCatalogAdapter` 解析该 JSON → `UnifiedCatalogEntry`，`downloadUrl` 走本服务下载。**主程序零改动**即可消费（adapter 已支持 JSON 数组 + downloadUrl）。

**Claude/Codex 镜像消费**（可选）：主程序也可订阅本服务的 `/marketplaces/claude.json`、`/marketplaces/codex.json`，等价于直接订阅上游官方市场（本服务作为缓存镜像，降低主程序对上游的直连依赖 + 统一审计）。

---

## 6. 安全

### 6.1 威胁模型（关键项）

| 威胁 | 缓解 |
|---|---|
| 恶意 `.fyp`（zip-slip、超大、伪装 manifest） | §4.3 服务端流式校验，**不解压到磁盘**做校验；大小 cap；禁止条目。 |
| 恶意 worker.jar（在服务端被加载/执行） | 服务端**只读** `META-INF/MANIFEST.MF` 取 `Main-Class`，**永不** `ClassLoader` 加载作者代码。worker 只在**主程序**沙箱里跑。 |
| 上游 Claude/Codex 市场投毒（恶意 `mcpServers.command`） | 聚合**只缓存元数据**，不执行；执行在主程序侧（其 `AgentContentInstaller` 已有 sha 校验 + scheme 白名单 + 路径 containment）。本服务 `BoundedHttp`（16 MiB cap，移植）限上游响应大小。 |
| 上游返回畸形 JSON 撑爆内存 | `BoundedHttp.readAtMost`（移植）；adapter 解析失败 → 标记 stale，不崩。 |
| 第三方插件名注入 XSS / 路径穿越（slug） | 发布器 slugify 插件名（移植 `PluginContentPathSafety.slugify`）；catalog JSON 输出做 JSON-escape（Jackson 默认）。 |
| 认证 brute-force | §3.2 登录限流 + 锁定。 |
| JWT 泄露 | 短期 access + refresh 轮换 + 设备撤销；主程序回落本地是降级不是无鉴权（主程序仍 loopback + 本地 token）。 |
| 公网实例被抢占首管理员 | §3.1 引导令牌（`market.bootstrap.admin-token`，仅首次有效）。 |

### 6.2 上游聚合的安全（复用主程序经验）

- 上游 URL 只允许 `http/https`（移植 `requireCloneableScheme`）。
- 上游响应大小 cap 16 MiB。
- 不信任上游字段名 → slugify 后用作缓存键。
- GitHub raw API 调用（§5.2）带 timeout + 大小 cap + 重试退避。

### 6.3 资产存储安全

- FengYu `.fyp` 的 `icon` 是 Iconify 名字符串，**无二进制资产**（A-7）。
- 制品下载端点对公开 catalog 走签名 URL（v1 直接流式 + 限流；v2 加短时签名）。
- 制品存储根目录与运行目录隔离；`ArtifactStore` 实现强制 path containment。

---

## 7. 配置

`application.yml` 关键项（都有默认值，可自托管覆盖）：

```yaml
market:
  base-url: http://localhost:24057          # 对外基址（生成 downloadUrl、清单里绝对 URL）
  auth:
    jwt-secret: ${MARK_JWT_SECRET:}         # HS256 密钥；启动时为空 → 生成随机并警告（仅 dev）
    access-ttl-seconds: 900
    refresh-ttl-seconds: 2592000
    registration:
      enabled: true
  bootstrap:
    admin-token: ${MARK_BOOTSTRAP_ADMIN_TOKEN:}   # 首次创建 ADMIN 用，用后失效
  artifacts:
    root: ./.market/artifacts
    backend: fs                              # fs | s3（v2）
    max-compressed-mb: 100
    max-expanded-mb: 300
  aggregate:
    cache-ttl-seconds: 600
    upstreams:
      - type: CLAUDE
        url: https://raw.githubusercontent.com/anthropics/claude-code/main/.claude-plugin/marketplace.json
      - type: CODEX
        url: https://raw.githubusercontent.com/openai/codex/main/.agents/plugins/marketplace.json
  publish:
    fengyu-catalog-path: /marketplaces/fengyu.json
    claude-catalog-path: /marketplaces/claude.json
    codex-catalog-path: /marketplaces/codex.json
spring:
  datasource:                                # 与主程序 DbType 对齐：H2/SQLite/MySQL/PostgreSQL
    url: jdbc:h2:file:./.market/db/market
    ...
  jpa:
    hibernate:
      ddl-auto: validate                     # 用 Flyway 管理迁移（不依赖 ddl-auto:update）
  flyway:
    enabled: true
    locations: classpath:db/migration
```

---

## 8. 测试策略

遵循主程序仓库的 TDD 风格（RED→GREEN）。

- **认证**：`AuthServiceTest`（注册/登录/刷新/撤销/限流/锁定）、`JwtServiceTest`（签发/校验/过期）、`MarketUserDetailsTest`（roles 映射）、`SecurityFilterChainTest`（端点鉴权矩阵）。
- **发布**：
  - `ArtifactValidationServiceTest`：对每个校验项一个红→绿（zip-slip 拒绝、超大拒绝、schema 不符拒绝、Main-Class 缺失拒绝、禁止条目拒绝、合法包通过）。**夹具**：从 FengYu `toolchain/spec/fixtures/` 复制 valid/invalid 样本。
  - `Sha256SidecarTest`、`SubmissionServiceTest`（状态机）、`ReviewServiceTest`（决策流转）、`PublishServiceTest`（active 版本切换）。
- **聚合**：`CatalogAggregatorTest`（三类源合并 + 去重 + stale 标记）、`ClaudeUpstreamAdapterTest`、`CodexUpstreamAdapterTest`、`GitHubUrlResolverTest`（移植主程序测试）、`CodexMcpStringResolverTest`（字符串→对象，私有仓库失败降级）。
- **发布器**：`FengYuCatalogPublisherTest`（输出含 sha256）、`ClaudeMarketplacePublisherTest`、`CodexMarketplacePublisherTest`（格式合规）。
- **集成**：`MarketplaceApplicationTests`（全链路：注册→上传→审核→发布→catalog 可见→清单文件生成）。
- **契约测试**：把 catalog/清单的 JSON 输出固化为 golden file，防回归。

---

## 9. 与主程序的边界（关键不变式）

1. **独立版本线**：本服务 `v1.0.0` 起，**不**随 FengYu `${revision}` 变。JSON 契约（catalog/清单/auth 响应）有独立版本号字段，主程序按兼容性消费。
2. **不共享 Java 模块**：`UnifiedCatalogEntry`、adapter、`ProcessSandbox` 等**移植**到本仓库，不引主程序 jar。理由：独立仓库 + 独立部署 + 独立发布节奏。
3. **主程序侧配套改动单独成 spec**：`MarketAuthClient`、`MarketAccountProvider`、`TokenAuthFilter` JWT 校验扩展、catalog sha256 字段读取——这些是**主程序**的改动，在主程序仓库另立 spec，依赖本 spec 的 §3.2/§5.3 契约。
4. **契约演进**：本服务契约变更走 semver；破坏性变更需主程序 spec 同步。新增字段向后兼容（主程序旧版忽略）。

---

## 10. 开放问题（待评审决议）

- **Q-1 多租户**：可自托管场景下，是否支持「一个实例多个组织」？v1 假设**单租户**（一个实例 = 一个市场 = 一份用户/插件集）。多租户留 v2。（建议：v1 单租户，YAGNI。）
- **Q-2 Claude/Codex 镜像是否做内容过滤**：聚合上游官方市场时，是否允许管理员「屏蔽某些插件」？v1 假设**全量镜像**，不做过滤。（建议：v1 全量，过滤留后续。）
- **Q-3 制品签名升级**：v1 用 SHA256（与主程序一致）。是否在 v1 就引入 GPG/_sigstore 内容签名？建议**不做**（YAGNI；SHA256 + HTTPS + 管理员审核在 v1 已足够）。
- **Q-4 主程序回落期间的归属**：回落本地虚拟用户期间产生的数据，认证恢复后是否迁移归属？§3.3 已决议**不迁移**（避免冲突）。请确认。
- **Q-5 前端**：本服务是否带自己的管理/上传前端？还是只做 API、前端在主程序或独立仓库？v1 建议**只做 API + 最小 admin 审核页**（作者上传走 API，审核用服务端渲染的简单页或主程序后续接）。请决议。

---

## 11. 不在本 spec 范围（重申）

- 主程序侧的安装/MCP runtime（主程序后续 spec）。
- 主程序侧的认证集成代码（主程序后续 spec）。
- 支付/付费、评论评分、多租户、OIDC IdP 模式（v2+）。
- FengYu 主程序现有 `/api/plugin-store/*` 的迁移（不迁；本服务是它的新数据源）。
