# FengYu + Infinia Store Platform 全量代码审计报告

- 审计日期：2026-09-07
- 审计对象：
  - 主项目 `/Users/phoebej/Develop/Java/FengYu`（分支 `release/4.0.0`，含工作树未提交修改）——后端 301 个 Java 文件、前端 105 个源文件、桌面端 39 个 TS 文件
  - 商店服务 `/Users/phoebej/Develop/Java/infinia-store-platform`（分支 `feat/standalone-monitor`）——7 个 Maven 模块约 300 个 Java 文件
- 审计方式：6 路并行深入审计（Web/安全/配置、AI 子系统、插件系统、前端+桌面、商店平台核心、两仓库契约一致性），只读，未修改任何代码
- 严重度定义：P0 = 可触发的高危 bug/漏洞或链路完全断裂；P1 = 重要 bug；P2 = 一般问题/次要 bug；P3 = 增强建议

---

## 零、核心结论（直接回答「能否正常升级/安装」）

| 链路 | 结论 | 断点 |
|---|---|---|
| 主程序升级（GitHub 渠道） | ✅ 可用 | — |
| 主程序升级（商店渠道 · Windows portable ZIP） | ✅ 端到端可用 | 唯一打通的商店化更新链路 |
| 主程序升级（商店渠道 · deb） | ❌ 断裂 | 客户端请求 `/fengyu-updates/deb/latest-linux.yml`，商店无此路由 → 404（跨仓库 P1-2） |
| 主程序升级（商店渠道 · macOS / NSIS / 带 JRE 构建） | ❌ 断裂 | 客户端 `configureUpdateFeed` 直接抛错，无 GitHub 回退，更新检查彻底消失（跨仓库 P1-3） |
| 主程序升级（portable Web `java -jar`） | ⚠️ 仅 GitHub | 配置商店渠道即抛错；自更新脚本还会以 0755 权限落盘明文 token（主项目 P1-1） |
| 插件安装/升级（本地发布的 .fyp） | ✅ 端到端可用 | 但商店端 beta 会覆盖 stable 推给全量用户（商店 P1-1）等发布管线断点 |
| 插件升级（第三方 catalog 源） | ⚠️ 有严重 bug | 客户端更新门键用 catalog 名而非包内真实 id，可能把成功的安装静默回滚（主项目 P2-13） |
| Skill 安装/升级（商店 skills-catalog） | ❌ 100% 失败 | 服务端 DTO 缺 `sha256/signature/keyId`，客户端强制校验 → 一装就拒（跨仓库 P0-1） |
| Skill/MCP 安装（upstream 聚合条目） | ❌ 必失败 | download-ticket 不带摘要/签名，客户端拒绝下载（跨仓库 P0-2） |
| MCP registry 源（AUTO） | ❌ 必失败 | 商店 sync 与下载两端 adapter 解析不一致 → 下载 500（商店 P1-7） |
| CLAUDE 源（git clone） | ⚠️ 仅同机可用 | 商店导出 `file://` 服务器本地路径，远程部署 clone 必失败（跨仓库 P1-1） |
| 任意版本号防回退 | ❌ 有全局毒点 | 一个 ≥20 位纯数字 prerelease 版本号可让商店所有相关 listing 的排序/比较崩溃且无自愈（商店 P0-1） |

**总评**：两个代码库的安全基线与工程质量明显高于平均水平（常量时间 token 比较、DNS-rebinding 防火墙、zip-slip/炸弹防护、原子换包+journal 回滚、审批管线、DOMPurify、contextIsolation 等均已到位），未发现可远程直接利用的 RCE。但 **skill/MCP 的商店化安装升级链路当前三条通路全部断裂**，主程序升级在商店渠道下仅 Windows portable 可用；另有一批默认配置即可触发的功能性 P1。

---

# 第一部分：FengYu 主项目

## 1.1 后端 Web / 安全 / 配置层

### [P1-1] 自更新重启脚本以 0755 权限落地，且明文包含完整 `--token=` 命令行
`FengYu/src/main/java/fan/summer/fengyu/update/SelfUpdateService.java:372-376`（`rwxr-xr-x`）、`:380-406`（`buildRelaunchCommand` 把 `sun.java.command` 中的 token 原样重建）。
Portable 模式触发 `POST /api/updates/apply` 后，`self-update.sh/.bat` 写入 `.fengyu/runtime-files/`，group/other 可读，明文含 API token（全 API 唯一凭证）。多用户机器上其他本地账户可直接读取。
**修复**：脚本权限改 `rwx------`；bat 改用 `%FENGYU_AUTH_TOKEN%` 环境变量传递。

### [P2-1] MySQL 插件 DB 授权把账户限定为 `user@'127.0.0.1'`，远程 MySQL 下插件 worker 永远无法登录
`setup/DbDialectStatements.java:45-48`。宿主支持远程 MySQL，但授权 DDL 固定 host 为 127.0.0.1：provisioning 成功、状态 ACTIVE，运行时必然认证拒绝。
**修复**：按宿主 DB 的 host 生成账户 host 部分，或对远程 MySQL 拒绝并提示。

### [P2-2] 至少 4 处注释声称 `/api/setup/**` 被 TokenAuthFilter 豁免，与实现相反（安全属性文档漂移）
`FengYuApplication.java:58-62`、`setup/SetupController.java:24`、`web/controller/SkillController.java:51-52`、`web/GlobalExceptionHandler.java:25`。实际豁免列表（`TokenAuthFilter.java:74-86`）只有 OPTIONS、`/api/health`、`/api/workflow-hooks/*` POST、`/plugin-runtime/*` GET/HEAD，且 `TokenAuthFilterTest` 已钉死 setup 需 token。若有人按注释「修复」重新豁免 `/api/setup/**`，会真实引入 APP 模式匿名重配置/删库漏洞。
**修复**：同步四处注释。

### [P2-3] `OsCloudSecretStore` macOS 后端把密钥作为 argv 传给 `security` CLI（`ps` 可见）
`account/OsCloudSecretStore.java:91-92`。云账号 refresh token 以 `-w <value>` 进入命令行，任何本地用户可短暂读取。
**修复**：改用 stdin 形式。

### P3（Web/安全层）
- `ConversationController.create()` 缺 `@Transactional`（与 update/delete 不一致），中途失败留空会话（`:76-87`）。
- `replaceMessages` 无消息条数/体积上限，可灌任意大 body（`:118-133`）。
- SETUP 向导 `filePath` 接受任意绝对路径（`DataSourceConfigService.java:185-212`），auth-off 姿态下是本机任意进程可用的任意路径写原语；建议限制在 runtime root 内。
- `test-connection` / `/api/ai/config/test` 返回 `e.getMessage()` 原文，auth-off 下是本机页面可达的内网探测 oracle（`DataSourceConfigService.java:284-302`、`AiConfigController.java:245-266`）。
- `SelfUpdateService` 把 release 元数据未消毒拼入 shell/bat 注释（`:433,451`，当前 feed 恒 GitHub 实际难利用）；bat 以 UTF-8 写出而 cmd 默认 GBK，中文路径 Windows 自更新可能乱码失败；`logFile` 是死参数。
- `SetupApplication` 未排除 `PluginHookController`，SETUP 模式调用 `/api/plugin-hooks` 一律 500（`SetupApplication.java:73-89`）。
- SSE 长连接极端驻留：`NotificationStream` 心跳无中断联动（`NotificationController.java`），建议 emitter 终止回调里 interrupt。
- CORS 允许任意 loopback 端口 + `allowCredentials(true)`（`WebConfig.java:44-57`）——dev 权衡，建议文档明示「auth off = 信任本机所有进程与本地页面」。
- `--token=` CLI 参数进 argv（`HeadlessLauncher.java:73-77`）；已支持 `FENGYU_AUTH_TOKEN` env，建议引导优先用 env。
- `application.yml:15` 默认 store base 为 `http://localhost:8080`，生产需覆盖；建议非 loopback 命中时启动告警。
- `primeRuntimeDirectories` 的 `catch (Exception ignored)` 后仍设置 log.dir（`HeadlessLauncher.java:252-259`）；`SetupApplication.java:57-58` 与 `AgentController.java:158-167` javadoc 重复两遍；`EmailUtil.java:23` 仍引用遗留表名 `swiss_kit_setting_email`。
- `PluginDbProvisioner` 全局单锁串行 DDL（`:98,167,216`）——桌面负载可接受，备注即可。

**核实为无问题的高危面**（供对照）：`/plugin-runtime/**` 资产端点的路径遍历被 `normalize+startsWith` 挡住；编码路径绕过均为 fail-closed；SSE 一次性票据（单次兑现、端点绑定、TTL、上限）设计干净；共享可变状态（`pending`、`sinks`、`completedAttempts` 等）均有界且并发结构正确。

## 1.2 AI 子系统（agent / 调度 / workflow / MCP / skill / session）

### [P1-2] 计划任务/Webhook 在默认 ASK 权限模式下无人值守运行 → 每次 fire 挂死 15 分钟并自饿死任务队列（默认配置即可触发）
- `ai/tasks/BackgroundTaskScheduler.java:298-300`（`create` 捕获 ThreadLocal 的默认 `ASK_FOR_APPROVAL`）、`:510-517`（fire 时设权限上下文）
- `ai/workflow/WorkflowExecutionService.java:93-97`、`:107-127`（15 分钟超时才 markCancelled）
- `ai/agent/AgentRunner.java:444-450`（ASK → 审批门阻塞）

headless run 无 SSE sink、无通知渠道，审批门只能等 15 分钟超时。含非 READ 步骤的 schedule 每次 fire 都 FAILED；并发上限 16 + 最小间隔 60s，15 分钟可积累 15 个挂死任务占满全部槽位，后续所有后台任务（含聊天）被 `BackgroundTaskCapacityException` 拒绝。
**修复**：创建时校验 ASK + 非 READ 工具的组合并拒绝/要求显式选择；审批门独立短超时 fail-fast；触发审批时发通知。

### [P1-3] AgentRun 审批门是「无凭证的 count-1 latch」——迟到/重复的 approve 会放行下一个门
`ai/agent/AgentRun.java:193-213`、`web/controller/AgentController.java:289-300`。双击批准/前端重试时，若 run 已通过门 N 并 arm 了门 N+1，第二个 approve 直接放行 N+1 的步骤审批——外部/命令效果步骤在无人确认下执行；`approve(editedPlan)` 还会覆盖当前 plan。
**修复**：`requestApproval` 生成 gateId，`approve` 携带校验；非 `AWAITING_*` 状态返回 409。

### [P1-4] `GET /api/agent/stream?runId=` 不校验 run 归属（横向越权读取他人 run 流）
`web/controller/AgentController.java:269-280` 直接按 runId 取 sink；同文件 approve/cancel 都经 `AgentRunRegistry.get`（含 userId 校验）。多用户部署下可订阅他人 run 的 plan token、步骤参数与工具结果。
**修复**：`stream()` 先 `registry.get(runId)` 判归属。

### [P2-4] MCP 服务器死亡后无重连/健康检查
`ai/mcp/McpRuntimeManager.java:345-359, 437-465`。server 崩溃后死回调长期留在目录，调用只会等 30s 超时，直到手动 test/save 才恢复。
**修复**：error 状态指数退避重连；调用失败标记并触发重建。

### [P2-5] MCP 启动/刷新串行阻塞：最坏启动延迟 25 分钟，设置页保存卡 30s
`McpRuntimeManager.java:108-122`（串行 connect，每个 init 上限 300s）、`:437-465`（refreshProvider 每 client 30s round trip 且全程持锁）。
**修复**：并行 connect + 总预算；error client 跳过 round trip；listTools 结果缓存。

### [P2-6] AgentRunner 步骤无整体超时；插件工具 `timeoutSeconds` 缺省为无限等待
`ai/agent/AgentRunner.java:463-467`（`invokeAll` 无超时）、`ai/config/AiToolRegistry.java:561-567`（`-1` = 无限）。manifest 未声明超时的插件调用可无限阻塞；手动 run 无 15 分钟兜底、SSE 无超时，run 永停 EXECUTING，grants 只有依赖 terminal 事件的 10 分钟清理（不会到来）→ 泄漏直到重启。
**修复**：可配置的步骤墙钟超时，或对 `-1` 强制上限。

### [P2-7] AgentStreamSink 缓冲事件无单事件大小限制（潜在数百 MB 内存）
`web/controller/AgentController.java:627`（2000 条缓冲）、`:737-739`（result 原样入缓冲与 DB 事件）。插件工具输出无字符上限；`execute_command` 把 stdout+stderr 拼两遍（`CommandExecuteTool.java:141`）。
**修复**：缓冲与持久化路径对 result 截断（如 16KB）。

### [P2-8] 一次性 schedule 提交失败仍记 COMPLETED
`ai/tasks/BackgroundTaskScheduler.java:483-485, 501-533`。容量异常（非崩溃路径）也会把该次触发永久记为 COMPLETED，DB 里看似成功。
**修复**：submit 失败时终态改 FAILED。

### [P2-9] ConversationCompactor 摘要失败时 fail-open 发送超限历史
`ai/session/ConversationCompactor.java:85-86`。summarizer 两次失败（key 失效/模型掉线）→ 原样发送必然超窗的历史 → provider 400 整轮失败。
**修复**：失败时退化为硬截断最旧完整轮次。

### P3（AI 子系统）
- `claim()` 极端 overdue `multiplyExact` 溢出 → schedule 永久卡死 + 每秒刷日志（`BackgroundTaskScheduler.java:477-478`）。
- `POST /api/agent/run` 客户端直传 workflow 无 64 步上限，`validateReferences` O(n²)（`AgentRunner.java:872-928`）。
- `McpRuntimeManager.test()` 测试禁用 server 时短暂把其工具暴露进目录（`:202-223`）。
- 工具禁用通配符 `acc*` 会同时禁 `account*`（前缀按 char 匹配，`:478-494`）。
- `HookDispatcher` 超时只杀根进程不杀孙进程；drain join 1s 可能截断尾部 stdout 致 gate JSON 误判（`ai/hooks/HookDispatcher.java:226-235, 270-276`）。
- `ToolGuardService` 权限规则 JSON 损坏静默当空规则（fail-open，`:227-236`），建议 UI 告警 `invalidRules`。
- `WebTextClient` SSRF：DNS rebinding TOCTOU（注释已自认）+ IPv6 分支未覆盖 NAT64 `64:ff9b::/96`（`ai/tools/WebTextClient.java:73-101`）。
- `SkillRegistry` 每条消息全量重扫 classpath + 文件系统（`SkillRegistry.java:78-107`），建议 5-10s 快照缓存。
- `AiUsageMetrics` 以工具名作 meter tag，基数不受控（`:57-61`）。
- `ChatSession` 非线程安全且 trim 对 assistant/TOOL 序列处理粗糙（`:21, 122-142`）。
- `CommandExecuteTool` 非 Windows 用 `/bin/sh -lc`——`-l` 登录 shell source 用户 profile，命令语义与审批时审读不一致（`:200-205`）。
- `WorkflowService` 用 `LocalDateTime.now()`（无时区）而调度用 `Instant`，跨时区迁移语义不一致。
- `WorkflowExecutionService.waiters` 存在已自认的小泄漏窗口（`:129-141`）。
- `AiMemoryService.tokenize` 中文按非字母数字切分 → 中文关键词召回基本失效（`:160-166`）。

**核实为无问题**：stdio 管道满死锁（HookDispatcher 双管道 drain / CommandExecuteTool 虚拟线程 reader）、审批管线分层、MCP 子进程环境脱敏、skill zip-slip/解压上限、workflow 循环拒绝、schedule at-most-once claim、新增 calendar 调度的 DST/时区处理（有测试覆盖）。

## 1.3 插件系统与 Store 客户端

### [P1-5] Worker stdin 写入无超时，可无限阻塞宿主线程（管道满 + 取消失效）
`plugin/runtime/PluginProcessManager.java:1395-1400`（invoke）、`:1436-1440`（sendNotification）。超时只包住 `future.get`，`synchronized(this)` 内的 `writer.write/flush` 无时限；worker 停读 stdin 后写线程永久持锁阻塞，所有并发调用者（含尚未进入超时逻辑的）被卡死，`cancel()` 的 interrupt 无法解除管道阻塞 I/O。行为异常/恶意 worker 可耗尽 Tomcat 线程池使整个后端不可用。
**修复**：独立写线程 + 有界队列 + 写超时，写失败走 `failAll` 拆除路径。

### [P1-6] AgentContentInstaller 插件根目录解析缺少包含性校验（路径穿越）+ `file:` 克隆放行
`plugin/store/AgentContentInstaller.java:229-236`（`resolvePluginRoot` 对 `cloneDir.resolve(s.path()).normalize()` 未做 `isInside` 校验）、`:195-210`（`requireCloneableScheme` 允许 `file:`）。`path` 原样来自第三方 marketplace JSON（Claude/Grok/Codex adapter），`../../..` 可逃出临时目录指向任意本机目录，其 skills 内容被拷入 `skills/<uid>` 进入 AI 上下文——本地文件读取/外泄原语；`file:` + 内网 git URL 也无 SSRF 策略。
**修复**：`resolvePluginRoot` 加 `PluginContentPathSafety.isInside(cloneDir, …)`；`file:` 仅显式本地开发配置放行；git URL 套用 UrlPolicy。

### [P1-7] Windows/macOS 下插件 worker 没有文件系统/网络安全边界，与权限模型不符
`security/ProcessSandbox.java:55-73, 277-371`。仅 Linux bwrap 是真隔离；macOS 是 `(allow default)` + 黑名单（仍可读用户文档、浏览器 profile、zsh_history）；Windows Job Object 只管进程树——未声明 `network` 的插件在 Windows 拥有全部网络与文件读取能力。manifest 权限模型与安装确认 UI 在这两个平台基本不被 OS 强制。
**修复**：安装确认/运行状态/文档显式声明「权限未由 OS 强制」；推进 Windows AppContainer 与更严 macOS profile。

### [P2-10] `PluginPackageService.installFromUrl` 无 SSRF/私网防护
`plugin/market/PluginPackageService.java:375-396`。仅校验 scheme 即下载，URL 来自第三方 catalog；可让宿主 GET `169.254.169.254`/内网。`UrlPolicy` 恰恰没用在插件下载路径（store 与 skill 市场都用了）。
**修复**：下载前 `UrlPolicy.requireTraversable(...)`。

### [P2-11] UrlPolicy 存在 DNS rebinding TOCTOU
`store/UrlPolicy.java:33-39`。自行解析校验后 `HttpClient.send` 再次独立解析，TTL=0 域名可绕过。类注释声称已缓解 rebinding，实际未固定解析。
**修复**：钉住已解析地址（自定义连接器带 Host/SNI）。

### [P2-12] 任意路径文件授权端点仅由 manifest 权限把关
`web/controller/PluginRuntimeFileController.java:63-67` + `PluginFileGrantService.grantNative:131-141`。`POST /api/plugin-runtime/{id}/files/native` 接受任意绝对路径，`write` 直接 LIVE 授予原路径写权限；REST 端点不校验路径来源（桌面流程走原生选择器）。被攻陷的 renderer/SPA XSS 可授予 `~/.ssh` 写权限再经 worker invoke 构成任意文件写原语。
**修复**：限定桌面 IPC 专用通道或要求选择回执；至少加审计日志。

### [P2-13] InstallerDispatcher 以 catalog 名而非包内真实 id 作为更新门键 → 可能静默回滚成功安装
`plugin/store/InstallerDispatcher.java:104-119`（对照正确姿势 `StoreService.java:352-355`）。catalog id ≠ 包内 manifest id 时：`beginUpdate(错误id)` 停掉另一个插件的 worker；真实插件目录被换而其 worker 未停（Windows ATOMIC_MOVE 失败 / unix 旧 worker 继续跑旧代码）；若 `entry.name()` 未安装则不 preflight/不 commit → journal 悬置，**下次重启 `recoverInterruptedUpdates` 把成功安装静默回滚**。
**修复**：统一先读包 manifest 拿真实 id 再开门。

### [P2-14] AgentContentInstaller 安装非原子：先删旧再拷新，失败留半安装且无回滚
`plugin/store/AgentContentInstaller.java:68-77`。对比 store 侧 skill 安装有 journal+backup 回滚（`StoreService.java:388-398`）。
**修复**：staging 目录 + 原子 rename。

### [P2-15] 插件 iframe 授予 `display-capture; camera` 权限策略，无对应 manifest 权限
`frontend/src/views/PluginView.vue:301`。manifest 权限枚举无摄像头/屏幕捕获项，属超范围授予。
**修复**：移除该 allow 或仅对声明相应权限的插件授予。

### P3（插件系统）
- 免 token 的 `/plugin-runtime/**` GET 可被任意网站跨站枚举已装插件/加载其 UI JS（建议 Sec-Fetch-Site/Origin 校验）。
- `Worker.close()` 不关 `process.getErrorStream()`，逃逸孙进程持 stderr 写端时读线程与 FD 永久泄漏（`PluginProcessManager.java:1492-1499`）。
- 损坏的更新 journal 直接阻断宿主启动（`PluginPackageService.recoverInterruptedUpdates:621-641` 抛异常炸构造器），建议隔离并继续（store 侧 ledger 有隔离先例）。
- runtime-files 根目录无启动清扫，崩溃后上传目录永久残留。
- `StoreClient.browse` 的 `type` 参数未 URL 编码（`StoreClient.java:161`）。
- 下载无重试/断点续传/镜像回退；更新检查无节流，前端轮询放大（`StoreService.updates():145-171` 每坐标串行 resolve）。
- `SemanticVersionRange` 不支持 `^`/`~`/`*`/连字符区间，fail-closed 但挫败 npm 习惯的作者且报错不指明原因。
- 内置信任锚为空（`plugin/trusted-publishers.json`、`store/trusted-store-keys.json` 均 `keys: []`）——`require-signature` 默认 true 时云端签名下载在注入 key 前必然失败。
- 插件日志 SSE 在 token 模式下不可用（路径不在 StreamTicketService 白名单，疑似死代码）。
- store 事务回滚给官方插件写 uninstall 墓碑 → `OfficialPluginSeeder` 永久跳过重播种，需手工重装。
- `setEnabled` 并发两次 disable 抛 FileAlreadyExistsException → 500（`PluginPackageService.java:449-454`）。
- `PluginRuntimeFileController.export` 要求 `files.write` 才能导出（读操作语义错误）。
- `StoreService` 构造器 `host-version` 默认 `4.1.0` 而应用是 4.0.0——dev 构建向 store 谎报版本。
- slugify 碰撞：`My Plugin` 与 `my-plugin` 同 uid，安装记录互相覆盖（`UnifiedStoreService.java:59-76`）。
- `/api/plugin-packages/upload-native`、`inspect-native` 接受任意本地路径无来源校验（与 P2-12 同族）。

**核实为无问题**：.fyp 解压（zip-slip、100MB/300MB/10k 条目上限）、staging→journal→ATOMIC_MOVE→preflight→commit/rollback 完整事务、官方命名空间保护、worker 命令 allowlist 无注入面、JSON-RPC 多路复用/16MiB 帧上限/超时即杀/指数退避/进程树回收、env 正向白名单、包目录对 worker 只读 + 双摘要。

## 1.4 前端 + 桌面端

### [P1-8] AI 自动化浏览器窗口的会话分区没有权限处理器 → 摄像头/麦克风/地理位置/通知被静默自动放行
`desktop/electron/src/window/permission-handlers.ts:41-46`（只给 defaultSession 注册了默认拒绝）、`desktop/electron/src/browser/session.ts:41-52`（`persist:fengyu-browser` 分区加载任意第三方网站）。Electron 对未注册 handler 的 session 自动批准所有权限请求——代码注释自己引用了 electron#12931 并给 defaultSession 做了防护，恰恰漏了自动化窗口。`browser_navigate` 允许 AI 驱动跳转任意页面，恶意页面可无提示开摄像头/取地理位置。
**修复**：对 `persist:fengyu-browser` 分区同样注册默认拒绝 handler（发布阻断级）。

### [P1-9] `runtimeRoot()` 锚定 `process.cwd()` 且 `initLogger()` 的 mkdir 无容错 —— macOS 从 Finder/Dock 启动主进程模块初始化即崩
`desktop/electron/src/desktop/runtime-paths.ts:4-6`、`desktop/logger.ts:20-21`、`main.ts:36`。macOS .app 从 Finder 启动时 cwd=`/`（只读根卷）→ `EROFS` → 启动即崩。项目在 `uos.ts:16-18` 注释里承认了这个失败模式但只给 UOS 构建做了 chdir；普通 Linux .desktop 与 macOS dmg 未覆盖。CI E2E 与 dev 模式都测不到该路径。
**修复**：runtime root 锚定 `app.getPath('userData')`（或所有打包构建 chdir），logger mkdir 包 try/catch（发布阻断级）。

### [P2-16] 聊天会话删除无任何确认，一次误点即不可逆删除
`frontend/src/shell/Sidebar.vue:175-179`（X 按钮直接 DELETE）、`frontend/src/views/AiChat.vue:354-356`（扫帚直接 clear）。其他破坏性操作都走 `confirmAction`，唯独聊天删除没有。
**修复**：接 `confirmAction` 或软删除。

### [P2-17] axios 客户端无 401/认证失效处理——token 失效后整个 UI 只剩零散原始错误，无恢复路径
`frontend/src/api/client.ts:104-113`。后端重启（新 token）后所有 REST 401、SSE 票据失败，无「凭据失效 → 重启/重连」的统一分支。
**修复**：响应拦截器识别 401 集中处理，桌面触发重新握手/重启后端。

### [P2-18] Store 搜索无防抖 + `loadCatalog` 无时序保护——快速输入时旧响应覆盖新响应
`frontend/src/views/StoreView.vue:327-329`、`frontend/src/stores/storeStore.ts:24-35`。每个字符触发 3 个请求，乱序返回时目录与输入不符（`settings.ts:78-91` 已有 seq 守卫先例未复用）。
**修复**：300ms 防抖 + seq 守卫或 AbortController。

### [P2-19] 侧栏「新建聊天」无限堆积空会话；流式期间切换会话被静默吞掉
`frontend/src/shell/Sidebar.vue:69-72`、`frontend/src/stores/aiSession.ts:102-116, 143`。
**修复**：复用空会话；busy 时给 toast。

### [P2-20] `aiSession` 会话/轮次内存无上限增长（桌面长驻）
`frontend/src/stores/aiSession.ts:40-49, 127-134`。每个打开会话的全部 turns 常驻且永不释放；每轮全量 PUT 整个会话，请求体线性膨胀。
**修复**：非活跃会话懒卸载。

### [P2-21] `create-window.ts` 头部 CSP 含多余的 `script-src 'unsafe-inline'`
`desktop/electron/src/window/create-window.ts:51-53`。meta CSP（无 unsafe-inline）目前兜住了，但头部策略自我削弱——渲染进程持有 `window.fengyu.token()` 与全部后端能力，XSS 后果被放大。
**修复**：删除头部 CSP 中的 `'unsafe-inline'`。

### P3（前端/桌面）
- `SetupWizard.vue` 整页硬编码英文文案，唯一漏接 i18n 的页面（en/zh 其余 1053 个 key 完全对齐）。
- 账号登录轮询无取消机制（`auth/apiAccountProvider.ts:41-53`）；`account.ts` 兜底显示名硬编码 `'Summer'`。
- ToolGrid 收藏不持久化（刷新即丢）。
- AI 设置保存可能把「打码后的 apiKey」原样 PUT 回后端（`Settings.vue:375-377`），完全依赖后端识别 `***` 掩码——建议前端显式过滤。
- Web 部署形态 token 是构建期内嵌常量 `VITE_FENGYU_TOKEN`（会打进公开 bundle）——需文档警示。
- `openAiStream` 的重连机制对 AI 流是死代码（后端断开即取消生成，重连只会收到错误），有误导性。
- 主进程 env 中的 token 会被 dev 模式 spawn 的 Vite 子进程继承（`dev-frontend.ts:97-105`），Linux `/proc/<pid>/environ` 可读——建议显式剥离。
- 桌面健康探测与前端 axios 对 `/api/health` 是否带头约定不一致（无实际风险）。
- AiChat 三个浮层菜单无 outside-click 关闭。

**核实为无问题**：preload 暴露面（17 个细粒度方法，无 ipcRenderer 泄漏）、contextIsolation/sandbox、导航拦截与外链、sidecar 全树击杀与优雅退出、更新器签名门控与 portable SHA-256 强校验、票据化 SSE、DOMPurify 全覆盖 v-html、插件 iframe sandbox+origin 校验、浏览器桥防护、监听器/定时器成对清理。

---

# 第二部分：infinia-store-platform（商店服务）

### [P0-1] SemVer 巨大数字 prerelease 标识符使比较崩溃——一个版本号毒化整条分发链路
`store-contract/src/main/java/dev/infinia/store/contract/semver/SemVer.java:102-115`。`Long.parseLong` 遇 ≥20 位纯数字 prerelease 标识符抛 `NumberFormatException`（已实证 `1.0.0-99999999999999999999.1` 解析成功但比较崩溃）。该 listing 的详情页、catalog、resolution、依赖解析、FengYu compat 目录全部 500/400，**无自愈路径**（发布者无删除 release 的 API）。若发生在 host app listing，**所有桌面客户端更新检查直接失败**。
**修复**：`BigInteger` 比较或降级字符串比较 + 补测试。

### [P1-1] FENGYU 兼容目录不按渠道过滤——beta/alpha 预发布覆盖 stable，全量用户被推 beta
`store-application/.../web/CompatFengYuController.java:232-239`（`latestPublishedByListing` 取最高版本不滤渠道）、`NativeInstallController.java:139/179`。同 listing 发 stable 4.0.0 与 beta 4.1.0 后，所有 compat 目录用户看到并可安装 beta。同文件 `portableRelease`（:175-189）注释明确写了要先过滤 prerelease，其余 4 个端点漏掉。
**修复**：目录聚合统一按渠道过滤。

### [P1-2] submit 之后仍能完成 pending 上传会话——未扫描制品进入评审/发布链
`service/PublisherService.java:389-421`、`service/ScanPipeline.java:109-112`。session 有效期覆盖 submit 时刻；`finalizeUpload` 对 SCANNING/IN_REVIEW 状态直接 `artifacts.add` 无状态机校验；ScanPipeline 只扫第一个 PACKAGE artifact——后加制品完全绕过扫描随 approve 一起签名发布。
**修复**：finalize 校验 release 仍在 DRAFT/UPLOADING，否则拒绝并作废 session。

### [P1-3] CHANGES_REQUESTED / REJECTED 恢复路径事实上不可用——重复上传撞主键 500
`V6__app_artifact_variants.sql`（`pk_release_artifact (release_id, platform, arch, kind, variant)`）+ `PublisherService.java:309-313, 405-412`。评审要求修改是设计内流程，但同 route 再上传触发 `DataIntegrityViolationException` → 500；无删除 artifact 的 API；REJECTED 追加制品不回 DRAFT，submit 409，且该版本号被 DUPLICATE_VERSION 占位无法重发。
**修复**：同 route 制品做替换语义；REJECTED→DRAFT 显式转换。

### [P1-4] 发布状态机丢失更新竞态——reviewer 的 REJECT 可被异步扫描覆盖回 IN_REVIEW
`service/ScanPipeline.java:97-149`、`service/ReviewService.java:94-116`。`ReleaseEntity` 无 `@Version`；扫描持旧快照 save 会把 REJECTED 覆盖回 IN_REVIEW，被拒的包重新进入审批流；并发 APPROVE 双提交会重复签名/重复发事件。
**修复**：乐观锁版本列或条件 UPDATE。

### [P1-5] DependencySolver 复用已选节点时不校验新约束——装出破损组合且无告警
`store-domain/.../service/DependencySolver.java:84-87`。A 依赖 `B@^1.0.0`、C 依赖 `B@^2.0.0`：先选 B 1.x，解析 C 时 B 已 chosen 直接返回 true，C 带着不满足的约束被标记 resolvable——客户端按 plan 安装后 C 运行时依赖损坏。
**修复**：chosen 命中时用 `SemVerRange` 复验并报冲突。

### [P1-6] Channel 枚举没有 rc——客户端按 rc 渠道查询更新检查直接 400
`store-contract/.../type/Channel.java`、`web/UpdatesController.java:42`（裸 `valueOf`）。FengYu 主程序发布流程用 `vX.Y.Z-rc.N` 标签；服务端 `inferChannel` 把 rc 归入 beta，但契约无 rc。客户端若按自身后缀推导渠道发送 `channel=rc` → 400，该渠道升级检查全部失败。
**修复**：渠道词表两端对齐；valueOf 给出明确枚举错误。

### [P1-7] 上游 AUTO 源 adapter 两端解析不一致——MCP registry 源下载必失败
`upstream/UpstreamArtifactService.java:131-147`（下载端 AUTO 只特判 SkillHub，其余一律 CLAUDE_MARKETPLACE）vs `service/UpstreamSyncService.java:203-224`（sync 端 AUTO 探测可选 MCP_REGISTRY）。MCP registry 源能 sync 进目录，但用户下载时用错 adapter → externalId 匹配失败 → 500，**skill/MCP 安装链路断**。
**修复**：`upstream_item` 持久化 sync 判定的 adapter 类型，下载端复用。

### [P1-8] SeedData 每次启动把演示账户密码重置为公开的 Password123!
`seed/SeedData.java:103-105, 299-313`（`repairDemoPasswords`）。`store.seed.enabled=true`（local/dev/test 默认开）时，只要 admin 存在就把 5 个演示账户（含 PLATFORM_ADMIN）密码**强制重置**回公开值——不是补建，是覆盖管理员已改的密码。生产误开即交出 admin。
**修复**：只创建缺失账户，绝不重置已有凭据；非 dev profile 拒绝 seed。

### P2（商店平台）
- 下载端点无 Content-Length、无 Range 断点续传（`DeliveryController.java:154-162`）——1 GiB APP 包中断从零重下（upstream 路径反而有 Content-Length）。
- 下载计数端到端断裂：`incrementDownloads` 无任何调用者，`downloads` 永远是 seed 假数据，DOWNLOADS/RELEVANCE 排序失真。
- resolution 的 installed map 键未规范化（客户端坐标原样作键 vs solver 规范化小写键）→ `alreadyInstalled` 恒 false → 重复安装（`ResolutionController.java:57-61`）。
- 扫描管线整包读入内存 + 1 GiB 上限（`ScanPipeline.java:157-161`）——OOM 面。
- 生产弱默认 secret（`ticket-secret`/`rollout-secret`/`cli-client-secret` 默认 `"dev-only-..."`）无启动强校验，可伪造 download ticket / 操纵 rollout；prod profile 默认 H2 文件库承载发布事务。
- 扫描等待窗口只有 2s（100×20ms 轮询等事务提交），超时即永久卡 SCANNING 无 watchdog；async 队列 100 满时同样卡。
- 客户端上报非法 version 使 `/library/installed` 500（`LibraryService.java:141` `SemVer.parse` 无保护）。
- `packageArtifact` 回退 `artifacts.get(0)` 可能把 CHECKSUMS/SBOM 当安装包下发（`CompatFengYuController.java:241-246`）。
- 版本唯一性应用层与 DB 不一致：yank 后重建同版本应用检查放行、DB 唯一索引拒绝 → 500；`4.0.0` 与 `4.0.0+build` 字符串唯一但 SemVer 等价 → latest tie 不确定。

### P3（商店平台）
- scanner SSRF DNS rebinding TOCTOU（`SourceFetchGuard.java:41-50`）；redirect 逐跳复验已做。
- upstream 每次下载全量 re-discover；`PreparedArtifact` tmp 泄漏窗口；上游 metadata 任何变化使既有 release 下载 409（drift）。
- artifactId 构造与持久化派生不一致（`UuidV7.generate()` vs 路由派生）。
- CLI 无 CI 账户时 fallback 直接授 PUBLISHER+REVIEWER+USER（`SecurityConfig.java:240-242`）。
- ticket TTL 不一致：update feed 300s vs compat 目录 24h——feed 用户延迟点下载易 403。
- 依赖 range 无 host 兼容门禁：发布者写 `^1.0.0` 商店可解析而 host 端只认 `>= <= > < =`，违背「审批通过 = 安装时不会被拒」承诺。
- `findVisibleByType` 全表载入，catalog 规模增长后 compat 目录变慢。
- enum 参数裸 `valueOf` 报错「No enum constant」不友好。
- admin 硬删除 release 不发事件；LocalFsBlobStorage 并发同内容上传竞态；`declaredSize` 无强制校验；死代码若干。

**核实为无问题**：主升级 feed 本身（channel+mode+variant+rollout 路由、HMAC 短时 ticket、SHA-256/Ed25519 随响应下发、checksums.txt）、上传 claim 原子与内容寻址幂等、SafeZip/TarGz zip-slip/炸弹防护、SSRF guard 的 redirect 复验、JDBC URL 参数黑名单、MCP 模板命令注入检查。

---

# 第三部分：跨仓库契约一致性（升级/安装链路端到端）

## 断裂点（按严重度）

### [P0-1] skills-catalog 缺少完整性字段 → 商店技能安装/升级 100% 失败
- 服务端 `CompatFengYuController.java:275-285`（`FengYuSkillEntryDto` 无 `sha256/signature/keyId`，javadoc 自述 "minus ... integrity fields"）
- 客户端 `ai/skill/SkillMarketplaceService.java:164-171` 强制 sha256 非空 + `require-signature` 默认 true 时强制 keyId+signature
- **影响**：`fengyu.skills.catalog-url` 指向商店 skills-catalog 后，列表可见，任何安装/升级必报 "Catalog entry carries no SHA-256; refusing an unattested skill download"。
- **修复**：服务端 DTO 补齐字段（upstream 条目须用重建制品的真实摘要，当前存的是 identity hash）；或客户端降级为 `X-Checksum-SHA256` 响应头校验。

### [P0-2] upstream（聚合）制品的 download-ticket 无 sha256/signature → 原生商店安装聚合 skill/MCP 必失败
- 服务端 `DeliveryController.java:83-88`（live 制品三连 null）；upstream blobKey 的 sha256 是目录 identity key 而非制品摘要
- 客户端 `StoreClient.java:221-229`（ticket 无 sha256 即抛错拒绝下载）
- **影响**：经 `/api/store/install` 安装任何 upstream 同步的 SKILL/MCP listing，resolve 成功但下载第一步即被客户端自身拒绝。
- **修复**：ticket 附带重建制品的真实 sha256 + 平台签名，或客户端改用响应头校验。

### [P1-1] claude-marketplace.json 导出 `file://` URL → 远程部署下 CLAUDE 源安装 skill/MCP 必失败
- 服务端 `LocalGitExporter.java:26-28, 41-44`（`Path.toUri()` → `file://`，javadoc 明示 same-machine）；`EcosystemExportService.java:110-113` 有意跳过 upstream 条目
- 客户端 `AgentContentInstaller.java:149-187` git clone 发生在客户端本机，file:// 指向的是商店服务器磁盘
- **影响**：桌面应用指向远程商店时，CLAUDE 源目录可见但 clone 失败。仅同机部署可用。
- **修复**：商店提供 HTTP git 端点（`{base}/git/{repoKey}.git`）并输出该 URL。

### [P1-2] lite deb 更新 feed `/fengyu-updates/deb/latest-linux.yml` 商店未提供
- 客户端 `desktop/electron/src/updater/update-feed.ts:110-119`；服务端全仓库无 `/fengyu-updates/**` 路由 → 404 problem+json
- **影响**：升级渠道指向商店的 lite Debian 包，update check 直接 404。客户端注释称「商店取代了 FY-Proxy 分发中心」，但商店没有继承 deb feed 端点。
- **修复**：商店新增 generic feed 端点，或客户端禁用商店渠道 deb feed 并回退 GitHub。

### [P1-3] macOS / Windows NSIS / 带 JRE 构建 + 商店渠道 → 完全没有更新检查
- 客户端 `update-feed.ts:105-109`（非「无 JRE 的 deb」即 throw）、`auto-updater.ts:49-55`（捕获后 return）、`ipc/update.ts:98-113`（向 renderer 抛错，无 GitHub 回退）
- **影响**：这些平台的「检查更新」要么静默无结果要么报错，应用升级链路对该配置完全断开。
- **修复**：明确产品语义；至少报错说明该渠道仅支持 Windows 便携版，或提供 GitHub 回退。

### [P2-1] download-ticket 不传 artifactId/os/arch → 平台特定制品的 release 404
- 客户端 `StoreClient.java:204-209`（无查询参数；`StoreModels.java:82-90` 丢弃了 resolution 返回的 artifacts 列表）
- 服务端 os/arch 为 null 时按 UNIVERSAL 匹配，仅含平台制品的 release 返回 404
- **影响**：多平台 worker 插件、APP 类型制品 404；当前 UNIVERSAL 常态插件不受影响。
- **修复**：客户端带上 os/arch 并利用 artifacts 传 artifactId。

### [P2-2] 签名应用更新 feed `/api/v1/updates/app` 是死接口，且「字段兼容」声明不实
- 服务端 javadoc 称 field-compatible with FengYu UpdateInfo，实际 `latestVersion/mandatory/rollout` ≠ 客户端 `latest/updateAvailable/notes/downloadUrl`；客户端无任何调用，`UpdateCheckService.java:89-100` 显式拒绝商店渠道
- **影响**：portable Web 用户配置商店渠道后 `/api/updates/check` 抛错；服务端维护无人消费的 feed。
- **修复**：对齐字段并接入，或标注预留。

### [P2-3] 客户端不上报 install-events → 商店「我的库 / 可更新」对 FengYu 用户永远为空
- 服务端 `POST /api/v1/install-events`（匿名也接受）；客户端 `StoreService` 安装成功后无任何上报
- **修复**：StoreService.install/uninstall 后批量上报（已有 Bearer 通道）。

### P3（契约）
- 目录分页被忽略：客户端固定 `limit=60` 丢弃 `nextCursor`，目录超 60 条不完整且无提示。
- problem+json 错误码不解析：用户看到 "HTTP 403" 而非「蜜蜂等级不足」等具体原因。
- channel 恒为 stable：客户端硬编码；商店发的 beta 原生路径永远解析不到，UI 也无渠道选择。
- 兜底 `hostVersion 4.1.0` 高于当前 4.0.0-rc.1（`StoreService.java:72`）：dev 装成功、正式机失败的隐患。
- 服务端 Native API（install-manifest / mcp-catalog / codex catalog）无客户端消费方——生态聚合计划的 Native 链路未接入。

## 确认一致的关键点（无需改动）
- 核心 API 形状（catalog/listings/resolutions/download-ticket/blobs 路径、方法、参数、字段名）逐一对齐；无 envelope 包装，错误统一 problem+json。
- **SemVer 语义两侧一致**（规范 2.0，prerelease < release），不会把 beta 当比 stable 新；compat 便携 feed 服务端先过滤非 STABLE 再取最大版本。
- 制品校验算法一致（hex sha256 + base64 Ed25519 over 字节流）；ticket 相对路径 + apiBase 解析一致。
- OAuth/账号全链路匹配（PKCE、RFC 8252 loopback 任意端口已验证 SAS 7.1.1 实现、refresh/desktop-session/revoke/me 字段逐一匹配）。
- Windows 便携版更新（当前唯一端到端打通的商店化更新链路）：路径、资产命名、digest 格式、http 无 digest 拒绝策略全部匹配。
- 「probe a dead store up front」（最近提交）：2s TCP 探测 + SSRF 策略前置 + 可操作报错，健壮性良好。

---

# 第四部分：修复优先级建议

**立即（发布阻断 / 链路完全断裂）**
1. 商店 SemVer 大数字崩溃（商店 P0-1）——一个恶意/失误版本号可打挂所有更新检查。
2. Skill/MCP 商店链路三断裂：skills-catalog 缺完整性字段（契约 P0-1）、upstream ticket 无摘要（契约 P0-2）、AUTO adapter 不一致（商店 P1-7）。
3. 桌面端两个启动/权限级崩溃面：自动化浏览器窗口权限自动放行（P1-8）、macOS Finder 启动 cwd 崩溃（P1-9）。
4. SeedData 密码重置（商店 P1-8）——生产误开即交出 admin。

**高优先（默认配置可触发 / 安全语义）**
5. headless ASK 审批挂死 + 队列自饿死（P1-2）；审批门无 gateId（P1-3）；agent stream 越权（P1-4）。
6. Worker stdin 无限阻塞（P1-5）；AgentContentInstaller 路径穿越（P1-6）。
7. 自更新脚本 0755 + token 明文（P1-1）。
8. 商店：beta 覆盖 stable（P1-1）、绕过扫描的追加上传（P1-2）、REJECT 被覆盖竞态（P1-4）、CHANGES_REQUESTED 恢复断裂（P1-3）、Channel 无 rc（P1-6）。
9. 契约：deb feed 404（P1-2）、macOS/NSIS 无更新检查（P1-3）、claude-marketplace file://（P1-1）。
10. InstallerDispatcher 更新门键错误 → 静默回滚安装（P2-13）。

**中优先（体验/健壮性）**：MCP 重连与并行启动、步骤超时、前端 401 处理、删除确认、搜索竞态、MySQL 远程授权、SSRF 补齐（installFromUrl/UrlPolicy TOCTOU）、下载 Content-Length/Range、依赖约束复验、install-events 上报。

**低优先（增强）**：各 P3 项——i18n 补齐、收藏持久化、指标 tag 归一、skill 扫描缓存、semver range 语法扩展、错误码解析、渠道选择 UI 等。
