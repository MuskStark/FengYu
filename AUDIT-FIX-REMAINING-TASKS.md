# 审计修复——剩余任务清单（2026-09-07）

上下文：完整审计见 [code-audit-2026-09-07.md](code-audit-2026-09-07.md)。Wave 1 修复（FengYu 后端/AI/插件/前端/桌面 + 商店平台核心）已全部落盘；本文列出**尚未完成**的事项。商店平台位于 `/Users/phoebej/Develop/Java/infinia-store-platform`（分支 `feat/standalone-monitor`）。

---

## 1. 前端接线（后端字段已暴露，前端尚未消费）

| # | 任务 | 契约细节 |
|---|---|---|
| 1.1 | **gateId 审批凭证**：`frontend/src/api/client.ts` 的 `agentApprove` 在 body 中带上 `gateId`（来自 SSE 事件 `plan_approval_requested` / `step_approval_requested` payload 的 `gateId`，或 run 状态的 `approvalGateId`）；收到 409 时静默刷新 run 状态 | body 形状 `{goal, steps, reasoning, gateId}`（gateId 可选，旧客户端兼容）；重复/迟到/不匹配的 approve 返回 409 |
| 1.2 | **permissionsOsEnforced 披露**：安装确认 UI 在该字段为 `false` 时显示「此平台上权限声明不由操作系统强制执行」 | 字段位于 `GET /api/plugin-store/catalog`（UnifiedCatalogEntry）、`POST /api/plugin-packages/inspect[-native]`（PackageInspection）、`POST /api/store/install` 响应（InstallResult）；仅 Linux 沙箱为 true |
| 1.3 | **计划任务权限模式选择**：创建 schedule / webhook 的表单提供 `permissionMode` 选择（以 `AiPermissionContext` 实际枚举为准），并优雅展示后端 400 拒绝文案 | `POST /api/agent/schedules` 现在会拒绝「ask-for-approval + 含无 allow 规则覆盖的非 READ 工具」的无人值守创建（`UnattendedTriggerPolicy`），错误信息引导显式选择模式 |
| 1.4 | （可选）截断标记：`step_complete` 事件的 `resultTruncated`、execute_command 结果的 `outputTruncated` 为 true 时 UI 标注「已截断」 | 结果限幅 16KB / 摘录 4KB |
| 1.5 | invalidRules 横幅确认：`GET /api/settings` 同时返回 `invalidRules` 与 `invalidPermissionRules`（同值数组）；前端 settings store 已消费后者，确认横幅已生效即可 | 空数组 = 规则全部解析成功 |

## 2. FengYu 统一构建验证（本轮各修复代理按约束未运行构建）

```bash
./mvnw -f FengYu/pom.xml clean test          # 后端全量（本轮新增了大量测试）
cd frontend && yarn install && yarn run build  # 前端（test:unit 167 例已绿）
cd desktop/electron && yarn install && yarn test  # 桌面单测（新增用例分布：bootstrap-cwd / logger / session / permission-handlers / window-open-handler / update-feed / auto-updater / update / health / dev-frontend）
scripts/e2e-smoke.sh                          # JAR 端到端冒烟
node --test scripts/release-workflow.test.mjs # builder 配置未动，预计无需
```

另：`toolchain/sdk-ts/dist/src/` 构建产物已随本次提交入库（仓库本就跟踪 `dist/` 平铺文件）——确认是否需要调整 `.gitignore` 或 SDK 构建输出布局。

## 3. 商店平台 Wave 2：分发特性（未开始）

| # | 任务 | 说明 |
|---|---|---|
| 3.1 | **DeliveryController**（本轮明确未动）：① upstream/live 制品的 download-ticket 附**真实** sha256/size/signature（客户端 `StoreClient` 会拒绝无摘要 ticket，跨仓库 P0-2；建议在 sync 时将 upstream 制品物化为 blob）；② blob 下载补 Content-Length（S3 先 head / 本地 Files.size），Range 可作增强；③ 接通 `ListingRepository.incrementDownloads`（当前仍无调用者） | `store-application/.../web/DeliveryController.java` |
| 3.2 | **skills/mcp-catalog 完整性字段**（跨仓库 P0-1）：`FengYuSkillEntryDto` 补 `sha256/signature/keyId`；upstream 条目需真实摘要（与 3.1 物化联动） | `CompatFengYuController` |
| 3.3 | **deb generic feed**（跨仓库 P1-2）：`GET /fengyu-updates/deb/latest-linux.yml` + deb 制品直链；格式对齐 electron-updater（`version` / `files[].url + sha512(base64) + size` / `releaseDate` / `path`）；资产命名对照 `desktop/electron` 的 electron-builder 配置与 `src/updater/update-feed.ts` | 客户端已就绪（feedUrl=`{base}/fengyu-updates/deb`），服务端 404 |
| 3.4 | **claude-marketplace file:// → HTTP git**（跨仓库 P1-1）：JGit `GitServlet` 挂 `/git/**`（`SecurityConfig` 已预留 anonymous GET permitAll）；`LocalGitExporter` 支持 public base URL（新增 `store.export.git-public-base` 类配置，默认空回落 file://）；一并修 `EcosystemExportService.java:235` 的 `artifacts.get(0)` 回退 | 目前远程部署下 CLAUDE 源安装必失败 |
| 3.5 | **/api/v1/updates/app**：字段与客户端 `UpdateInfo` 对齐（`latestVersion/mandatory/rollout` ≠ `latest/updateAvailable/notes/downloadUrl`），或明确标注「预留、无消费者」 | 当前死接口，javadoc 声明不实 |
| 3.6 | 其他：scanner `SourceFetchGuard` DNS rebinding 钉扎；upstream 下载 re-discover 缓存与 `PreparedArtifact` tmp 清理；artifactId 构造/派生统一；`CatalogController` 的 `ListingType.valueOf` 换友好解析 | |
| 3.7 | 验证：`./mvnw test` 全 reactor（本轮 contract/domain/application 已 290 例全绿） | |

## 4. 收尾事项

- [ ] **docs-updater**：同步 README / CHANGELOG / `docs/{en,zh}`；桌面端「打包构建 runtime root 迁移至 userData（旧数据不迁移，重新走 setup）」必须写入发布说明。
- [ ] **Windows 手动验证**桌面端 `process.chdir` 行为（桌面 E2E 仅 macOS/Linux 门控，覆盖不到）。
- [ ] **CORS PATCH 确认**：`WebConfig` 的 `allowedMethods` 缺 `PATCH`，而 `SkillController` 有 `@PatchMapping`——桌面 `app://shell` 跨源直连时 PATCH 预检可能被拒；确认前端是否实际跨源使用 PATCH，需要则补。
- [ ] 观察项（已知、低优先）：`failUnattendedApproval` 不触发 `guard.observeRunComplete`；`AiToolRegistry.toolOwnerTags` 若未来启用 spring-ai MCP starter 需改用缓存列表；MySQL 经 unix socket 连接时授权 host 语义。

---

## 已完成状态总览（Wave 1，全部已落盘本仓库/商店仓库工作树）

| 审计范围 | 状态 | 关键测试 |
|---|---|---|
| FengYu 后端 Web/安全/配置层（P1-1、P2-1~3、全部 P3） | ✅ | SelfUpdateScriptTest(9)、DbDialectStatementsTest、OsCloudSecretStoreTest、DataSourceConfigServiceTest、ConversationControllerTest、NotificationControllerTest 等 |
| AI agent/调度/workflow/session（P1-2~4、P2-6~9、全部 P3） | ✅ | UnattendedTriggerPolicyTest、AgentRunnerTest（gateId/超时/64步）、AgentStreamOwnershipTest、BackgroundTaskSchedulerTest 等 |
| AI MCP/skill/tools/hooks/metrics（P2-4~5、全部 P3） | ✅ | McpRuntimeManagerTest（重连/并行）、SkillRegistryTest、HookDispatcherTest、AiUsageMetricsTest、AiControllerSseCallbackTest 等 |
| 插件系统 + store 客户端（P1-5~7、P2-10~14、全部 P3、契约客户端侧） | ✅ | PluginProcessManagerTest（stdin 看门狗）、AgentContentInstallerTest（穿越/回滚）、InstallerDispatcherTest（真实 id 更新门）、StoreServiceTest/StoreClientTest（ticket 参数/遥测/重试/分页）、SemanticVersionRangeTest |
| 前端（P2-16~20、全部 P3；P2-21 由桌面侧完成） | ✅ | vitest 22 文件 167 例绿 |
| 桌面端（P1-8/9、P2-21、P1-3 桌面侧、全部 P3） | ✅ | bootstrap-cwd / logger / session / permission-handlers / window-open-handler / update-feed / auto-updater / update / health / dev-frontend 单测 |
| 商店平台核心（P0-1、P1-1~8、P2-3~9、全部 P3） | ✅ | `./mvnw -pl store-contract,store-domain,store-application -am test` 两轮 290 例全绿（V11 乐观锁、V12 upstream adapter 迁移） |
| 商店 Wave 2 分发特性 | ❌ 未开始 | 见 §3 |
