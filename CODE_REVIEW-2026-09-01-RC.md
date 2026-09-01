# FengYu 4.0.0 RC 全量代码审查报告

| 项目 | 结果 |
|---|---|
| 审查日期 | 2026-09-01 |
| 审查基线 | `4.0.0-folw`，`3c73f1b1`，并包含当前未提交工作区 |
| 距上一个版本 | `v4.0.0-beta.5-24-g3c73f1b1-dirty` |
| 当前应用版本 | `4.0.0-beta.5`，8 个版本镜像一致 |
| 目标候选 | `v4.0.0-rc.1`，release resolver 已验证可接受 |
| 审查范围 | 636 个 Java、310 个 TS/JS、71 个 Vue、4 个官方插件、Electron、工具链、构建/发布脚本与双语文档 |
| 结论 | **暂不建议直接打 RC tag** |

## 1. 执行摘要

本次审查以 2026-08-26 的全量审查为基线，重新核验其 8 个 Major，并覆盖其后的 24 个提交及当前未提交的 Store UI/API 重构。上一轮 8 个 Major 的主修复均已落地：子进程敏感环境清理、流中取消、Setup 鉴权、Host 防火墙、插件 UI 资产边界、`app://` CSP、Electron 默认拒绝权限、Browser 私网 URL 策略都已有代码和测试。

当前结论为：**Critical 0 / Major 8 / Minor 8 / RC 流程阻断 3 类**。

真正阻止立即发布的不是主体构建能力：后端跳过测试打包、前端/桌面/文档构建、桌面稳定 E2E、宿主 E2E、工具链 consumer smoke、Web 分发包 smoke 均通过。阻断集中在三处：

1. 后端测试套件仍有 1 个失败，release workflow 必然在 Maven test 阶段退出。
2. 当前工作区有大量未提交修改和 3 个未跟踪源文件，版本仍是 `beta.5`，没有覆盖当前 HEAD/工作区的远端 CI 证据。
3. 新增的 Store、云账号和技能市场代码尚未闭合其自身设计文档声明的依赖安装、运行时事务、签名验证与令牌存储安全边界。

如果 RC 必须尽快发布，最短的安全路径是先明确 Store/云账号/远程技能市场是否进入本次 RC：进入则修完本报告 M-1 至 M-7；不进入则应通过明确的 feature gate 隐藏这些远程入口，而不是带着半完成安装链路发布。

## 2. RC 硬门禁

### R-1 后端测试套件为红

- `./mvnw -B -f FengYu/pom.xml test` 失败；聚焦重跑 `StoreServiceTest` 同样稳定失败。
- 失败位置：`FengYu/src/test/java/fan/summer/fengyu/store/StoreServiceTest.java:108`。
- 产品代码现在返回 `missing or incompatible dependencies`，测试仍只匹配 `missing dependencies`。拒绝安装的产品行为正确，断言已过期，但 release workflow 运行测试，因此仍是硬阻断。
- 修复量很小：同步断言并补充 missing/incompatible 两种语义的测试。

### R-2 当前发布状态不可打 tag

- 工作区包含已修改、删除和未跟踪源文件；未跟踪文件包括 `PluginPackageController.java`、`SkillsMarketPanel.vue`、`UnifiedSourcesPanel.vue`。
- 当前仍是 `4.0.0-beta.5`：root POM、`.mvn/maven.config`、frontend、desktop 和四个官方插件镜像一致，但尚未变更为 `4.0.0-rc.1`，CHANGELOG 也没有 RC 条目。
- 分支 `4.0.0-folw` 没有 PR。最新远端成功构建是 2026-08-25 的手动 Windows portable，早于当前 HEAD；`frontend-ci`、`toolchain-ci`、Qodana 的普通 push 分支只覆盖 `4.0.0`/`main`，当前 24 个提交没有完整远端门禁证据。
- 必须先形成干净提交，再在 PR、临时受保护分支或手动 workflow 上取得当前 SHA 的完整绿灯。

### R-3 API 迁移与文档/测试没有一起完成

- 当前未提交改动删除 `PluginMarketplaceController`、`PluginMarketplaceService` 及其测试，新增 `/api/plugin-packages` 的本地包生命周期控制器。
- `docs/en/reference/rest-api.md`、`docs/zh/reference/rest-api.md`、插件 marketplace/overview/build 文档仍将 `/api/plugin-market` 描述为有效的目录、安装、更新、启停和卸载 API。
- `STORE_PLATFORM_DESIGN.md` 还明确要求保留 `/api/plugin-market` 兼容层。当前实现既没有兼容别名，也没有迁移说明，新控制器还没有同等的 controller/update-gate 回归测试。
- 发布前应二选一：恢复兼容路由；或正式声明破坏性迁移、同步双语文档和客户端，并新增覆盖上传、更新预检/回滚、启停和卸载 gate 的测试。

## 3. Major findings

### M-1 Store 更新版本比较无法识别 beta → RC/stable

- 位置：`FengYu/src/main/java/fan/summer/fengyu/store/StoreService.java:327-353`。
- `version.split("[-+]]")` 没有正确移除 prerelease，后续只比较前三个数字段；`4.0.0-beta.5`、`4.0.0-rc.1` 和 `4.0.0` 最终都被视为 `4.0.0`。
- 影响：beta.5 用户不会看到本次 RC 或最终 4.0.0 更新，这与当前发布目标直接冲突。
- 修复：复用已有 `SemanticVersion`/`SemanticVersionRange`，并增加 beta.5 → rc.1 → stable、beta.10、build metadata 和降级测试。

### M-2 Store 只安装根制品，却把整个依赖计划报告为已安装

- 位置：`StoreService.java:156-209`。
- `/resolutions` 返回完整 `plan` 后，代码只定位 `rootItem`、下载并安装根制品；其他 plan item 从未下载、验证或交给相应 installer。
- 返回值 `dependenciesInstalled` 却直接列出所有非根坐标，向 UI 谎报依赖已安装。
- 影响：带必需 Plugin/Skill/MCP 依赖的包会显示安装成功，但运行时缺依赖；无法实现设计要求的 dependency closure 和 lock。
- 修复：按拓扑顺序执行完整 plan，每项独立校验并纳入同一 install journal；失败时逆序回滚，成功后再返回真实安装集合。

### M-3 Store 插件更新/卸载绕过运行时 gate、健康预检与提交/回滚

- 位置：`StoreService.java:181-187,212-227`；正确参照为 `PluginPackageController.java:96-123,157-172`。
- Store 直接调用 `PluginPackageService.install/uninstall`，没有 `beginUpdate/endUpdate`、Worker stop、`preflight`、`commitUpdate/rollbackUpdate` 和日志清理。
- `PluginPackageService` 对已有插件会保留 rollback 与 transaction journal；Store 不调用 `commitUpdate`，因此一次表面成功的更新可能在下次启动时被当成“中断事务”恢复旧版本。
- 并发 invoke 还可能继续使用旧 Worker，Windows 上运行中的 JAR 也可能阻止替换/删除。
- 修复：抽出一个被本地上传、统一 Store 和官方 seeder 共同调用的插件生命周期 orchestrator，禁止 controller 与 Store 各自复制事务逻辑。

### M-4 Store 制品信任链没有实现设计承诺

- 位置：`FengYu/src/main/java/fan/summer/fengyu/store/StoreClient.java:46-70,152-181,205-228`。
- 下载票据的 `sha256` 为空时会跳过完整性检查；`signature`/`keyId` 从未验证；票据 URL 接受明文 HTTP 和任意主机/私网地址。
- 设计文档要求 Host→Store TLS、Host→CDN HTTPS，以及 SHA-256 + Ed25519 双重校验和 SSRF allow policy；当前实现与该信任边界不一致。
- 大小上限在文件全部写完后才检查，SHA-256 又通过 `Files.readAllBytes` 读取最高 512 MB；恶意或故障响应可造成磁盘写满或 JVM 内存尖峰。目录/解析/token JSON 响应也使用无界 `BodyHandlers.ofString()`。
- 修复：仅允许 HTTPS（loopback 开发例外）、逐跳 DNS/IP 私网检查、流式限字节下载和流式摘要、强制 hash、验证平台签名与 key rotation/revocation，并为 JSON 响应加上限。

### M-5 桌面 OAuth 客户端与令牌存储不符合 native-app 边界

- 位置：`application.yml:10-18`、`HttpStoreAuthGateway.java:31-40,60-90`、`CloudAccountService.java:218-239,297-328`、`CloudAccountBindingEntity.java:45-54`。
- 应用自称 OAuth 2.1 public client，却把默认 `dev-only-desktop-secret` 编入配置，并在 token/revoke 请求发送静态 shared secret。分发给所有用户的 native app 无法保守该秘密；[RFC 8252](https://www.rfc-editor.org/rfc/rfc8252.html) 要求此类客户端按 public client 处理，并允许 loopback redirect 使用临时端口。
- access token 与 refresh token 都持久化进主数据库；`CloudTokenConverter` 使用与数据库同盘的 `.machineid` 派生密钥。`CryptoUtil` 自身说明这无法抵御 same-user reader，而当前 macOS/Windows 插件 worker 并不具备足够的文件系统隔离。
- 这也直接违背 `STORE_PLATFORM_DESIGN.md:338,367-369,721` 的“access token 仅内存、refresh token 仅 OS Keychain、随机一次性回调端口”约束。
- 修复：注册 PKCE public client、移除静态 secret、绑定临时 loopback port；access token 仅保存在内存并串行化 refresh，refresh token 使用 macOS Keychain/Windows Credential Manager/Linux Secret Service。

### M-6 技能市场允许未认证远程指导覆盖内置 Skill

- 位置：`SkillMarketplaceService.java:77-96,119-137`、`SkillPackageService.java:116-131,223-239`、`SkillRegistry.java:35-37,76-81`。
- catalog 和 `.fys` 下载允许 HTTP，无 checksum/signature/host policy；Skill 内容会进入 AI 的指导上下文，供应链敏感度高于普通展示数据。
- 任意包只要使用 `fan.summer.*` id 就可声明 `official=true`；installed Skill 会以相同 id 覆盖 builtin，而 Store 设计明确写着“内置 Skill 不可覆盖”。这允许远程包伪装官方并替换内置指导。
- `compareVersions` 也不是完整 SemVer：稳定版与 prerelease、`beta.10` 与 `beta.2` 的顺序不可靠。
- 修复：远程 Skill 复用 Store 签名 envelope；官方身份只来自可信签名/受信 seeder；禁止覆盖 builtin，或把 override 设计成显式本地开发模式；统一使用 `SemanticVersion`。

### M-7 损坏的 Store ledger 会在应用启动阶段抛异常

- 位置：`StoreInstallLedger.java:76-87`。
- 注释称“损坏 ledger 不应让 Store 不可用”，实现却在 bean 构造期间抛 `IllegalStateException`。由于 `StoreService`/controller 依赖该 bean，一个截断的 `installs.json` 可能阻止整个 APP context 启动。
- 修复：将损坏文件原子隔离为带时间戳的 `.corrupt`，记录告警并以空 ledger 启动；补充截断 JSON、权限错误和恢复测试。

### M-8 Store 安装结果与 ledger 缺少共同事务

- 位置：`StoreService.java:177-209`、`StoreInstallLedger.java:60-101`。
- 制品先完成安装，最后才写 ledger；ledger 写失败会留下已安装但无法通过 Store 更新/卸载的孤儿。反向地，MCP 文件写入后 `syncImportedServers` 或 ledger 失败也没有恢复先前定义。
- 设计的 install journal、staging、commit/rollback 没有覆盖统一 Store orchestrator。
- 修复：先写可恢复 journal，记录每个 plan item 的 old/new state；只有所有 installer、runtime preflight 和 ledger 持久化都成功后 commit，启动时恢复未完成事务。

## 4. Minor findings

| # | 位置 | 问题 |
|---|---|---|
| N-1 | `StoreService.java:142-144` | 捕获 `InterruptedException` 后没有恢复线程 interrupt。 |
| N-2 | `CloudAccountService.java:209-216` | `completedAttempts` 注释称仅保留 grace period，实际从不淘汰，长期登录尝试会累积。 |
| N-3 | `CloudAccountService.java:297-328` | 并发 Store 请求可同时 refresh；若服务端轮换 refresh token，会出现竞态并静默降级为匿名。 |
| N-4 | `SkillMarketplaceService.java:62,67-70` | 恶意 catalog 的 null id/name 可触发 NPE；远端输入缺少 DTO 约束和条目数上限。 |
| N-5 | `UnifiedSourcesPanel.vue:118-127` | switch 的可见文本与实际状态相反：enabled 时显示“启用”，title 却正确显示“禁用”。 |
| N-6 | `StoreSourceManager.vue:17-26`、`pluginStore.ts:65-74,136-139` | store action 吞掉 add 错误后 dialog 仍关闭；toggle 则不捕获错误，形成两种相反且都不可靠的错误体验。 |
| N-7 | `pluginBackgroundJobs.ts:82-117` | unknown/error job 每 2 秒永久轮询，没有退避、上限或插件卸载清理。 |
| N-8 | `PluginView.vue:294-302`、`displayMedia.ts:8-25` | `camera` delegation 被重新加入；当前 permission handler 会拒绝实际摄像头请求，因此不是旧 M-7 的直接回归，但所有插件仍自动获得主屏 display-capture 能力，尚无 manifest 权限与应用级选择器。 |

另外，新 `PluginPackageController`、`SkillsMarketPanel`、`UnifiedSourcesPanel` 没有对应的专门回归测试；前端现有通用测试通过，但没有覆盖新增交互和 API 迁移。

## 5. 验证结果

| 门禁 | 结果 | 说明 |
|---|---:|---|
| 版本镜像 | PASS | 8 处均为 `4.0.0-beta.5` |
| RC tag resolver | PASS | `v4.0.0-rc.1` → version `4.0.0-rc.1`、prerelease `true` |
| Release contract | PASS | `node --test scripts/*.test.mjs`，50/50 |
| `git diff --check` | PASS | 无 whitespace error |
| 插件依赖边界 | PASS | `scripts/check-plugin-dependency-boundaries.sh` |
| 后端测试 | **FAIL** | `StoreServiceTest` 8 项中 1 项失败；全套因此退出 1 |
| 后端 clean package | PASS | `./mvnw -B clean package -f FengYu/pom.xml -DskipTests` |
| 前端 clean install | PASS | 隔离的全新 `git archive HEAD` 中 `yarn install --immutable` 通过 |
| 前端 typecheck | PASS | `vue-tsc` |
| 前端 Node tests | PASS | 28/28 |
| 前端 Vitest | PASS | 20 files / 160 tests |
| 前端 build | PASS | Vite 7.3.6；主 JS 819 KB、主 CSS 878 KB，有 chunk warning |
| Desktop immutable/audit | PASS | 无 audit suggestion |
| Desktop unit/build:ts | PASS | 28 files / 208 tests；TypeScript build 通过 |
| Desktop stable E2E | PASS | release-equivalent frontend staging 后 1/1，后端、preload、token、`app://` 均通过 |
| 宿主 E2E | PASS | health、4 个官方插件、Flow/AI/文件授权、worker shutdown 均通过 |
| 工具链 local consumer smoke | PASS | SDK/UI/dev/CLI、脚手架、Java worker、`.fyp` 内容检查通过 |
| 文档 build | PASS | VitePress EN/ZH |
| Web 分发包 | PASS | ZIP/TAR 组装；layout、sidecar、启动、SPA、token auth smoke 全通过 |
| JS production audit | PASS | 11 个 Yarn package root 全部无建议 |

### 验证说明

- 当前机器是 Node `26.1.0`，CI 固定 Node `24.18.0`。release contract 已验证所有 workflow 使用固定版本，但最终仍应在当前 SHA 的远端 Node 24 runner 上复跑。
- 同一工作区并行运行工具链 smoke 时，前端 `file:../toolchain/sdk-ts` 的 Yarn hash 会因本地构建产物被触碰而使 immutable install 暂时失败；隔离的新 checkout 稳定通过。因此这不是当前 lockfile 的 RC 阻断，但说明本地 `file:` 依赖的可复现性较脆弱。
- 所有 Yarn production audit 均通过。Electron 43 官方支持计划到 2027-01-05，当前不属于 EOL；Spring Boot 4.1 也在维护线。参考：[Electron release schedule](https://releases.electronjs.org/schedule)、[Spring Boot](https://spring.io/projects/spring-boot/)、[Node releases](https://nodejs.org/en/about/previous-releases)。
- 本机没有 gitleaks、Trivy、OSV-Scanner、Grype/Syft 等专用扫描器；本次没有得到 Java 依赖 CVE 扫描、secret scan 或 SBOM policy 的新证据。当前分支也没有新的 Qodana run。这是审查覆盖限制，不能把“Yarn audit 通过”解释成全栈无漏洞。

## 6. 最短修复与发布顺序

### 路径 A：Store 进入 RC

1. 先修 R-1；让 Maven 全套回归变绿。
2. 修 M-1、M-2、M-3、M-8，并为完整依赖 plan、插件预检失败回滚、重启恢复添加集成测试。
3. 修 M-4、M-5、M-6、M-7；Store/账号安全边界不应仅靠 UI 提示补偿。
4. 完成 `/api/plugin-market` 兼容决策和双语文档；新增 controller/UI 回归测试。
5. 形成干净提交，运行 docs-updater，再把 8 个应用版本镜像和 CHANGELOG 更新到 `4.0.0-rc.1`。
6. 在当前 SHA 上跑完整远端 release dry-run/CI，确认 macOS + Linux desktop E2E 和所有 packaging matrix。
7. 所有门禁绿后再创建并推送 `v4.0.0-rc.1`；本次审查未执行 commit、push、tag 或 publish。

### 路径 B：最快安全 RC

1. 修 R-1，并完成当前本地包 API 迁移及文档。
2. 用后端 feature gate 同时关闭远程 Store install/update、云账号和远程 Skill catalog；前端不展示不可用入口。保留已验证的本地 `.fyp/.fys` 安装、现有插件和 Flow 能力。
3. 将 Store 标记为后续 RC 的实验能力；不要只隐藏按钮而保留可调用 API。
4. 完成干净提交、RC 版本/CHANGELOG、当前 SHA 远端 CI 后再打 tag。

路径 B 改动面更小，也避免在临近 RC 时仓促实现签名根密钥、OS Keychain 和跨类型事务。若产品承诺本次 RC 已包含 Store，则不能采用路径 B，应完成路径 A。

## 7. 结论

FengYu 的核心 host、Electron、插件隔离、AI/Flow 和分发链路已经具备 RC 基础，上一轮高风险缺陷的修复也有较好回归覆盖。当前不应直接发布的原因是新 Store 平台客户端仍处于“功能已接通、信任和事务边界未闭合”的阶段，同时发布树本身不干净且测试门禁为红。

在修复单个失败测试后，真正的 go/no-go 决策只有一个：**Store 是否属于本次 RC 的承诺范围**。明确这一点后，按路径 A 或 B 收敛，可避免无边界地继续扩大审查与修复范围。
