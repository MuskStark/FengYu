# FengYu 生产级代码审查报告（核验版）

- 审查日期：2026-09-19（部分测试执行于 09-18 晚间）
- 基准提交：`9b51d7514914c9978b13d609e659d0c3c86cc739`
- 应用版本：`4.0.0-rc.2`
- 审查范围：后端、Vue 前端、Electron、官方插件、插件 SDK/CLI/UI/dev 工具、构建与发布配置。
- 本次交付为审查，没有修改业务代码、提交、推送或发布。保留了工作区原有的另一份审查文档，本报告独立记录实际核验结果。

## 结论

**不建议当前状态直接正式发布。** 本轮确认 11 项可操作问题：4 项 P1、7 项 P2。优先处理宿主权限边界、产物/恢复备份丢失和 Windows 更新失败分支；其余涉及聊天一致性、插件核心功能、资源限制及工具链发布门禁。

P1 表示高影响安全/数据完整性或更新恢复缺陷，应作为正式发布阻塞；P2 表示应修复的功能、可靠性或防护缺陷。严重程度按触发条件和实际影响判断，并不表示已出现线上事故。静态确认与实际复现分别标注，未把推测写成已验证攻击。

## 问题清单

### 1. [P1] H2 TCP 允许创建新数据库，形成宿主 JVM 权限绕过

位置：[FengYu/src/main/java/fan/summer/fengyu/config/H2TcpServerConfig.java:87](/Users/phoebej/Develop/Java/FengYu/FengYu/src/main/java/fan/summer/fengyu/config/H2TcpServerConfig.java:87)

**触发与影响：** 启用内嵌 H2 TCP 服务且调用者能够连接其 loopback 端口时，`-ifNotExists` 允许建立新的数据库。已有业务数据库的用户名和密码不能阻止攻击者创建自己控制的数据库；新库管理员可通过 Java alias 在数据库所在的宿主 JVM 内执行 Java 方法。获得网络访问能力的插件因此可能越过进程沙箱边界。此问题不是公网默认暴露。

**证据：** 在隔离 H2 实例上复现：以自选凭据创建新内存数据库，并通过安全的 Java alias 读取宿主 java.version。未连接用户业务库或执行破坏性操作。

**建议与验收：** 在宿主内预创建必要数据库；TCP 服务拒绝未知数据库创建，并明确限制允许访问的数据库和权限。增加未知数据库连接拒绝测试。`-tcpPassword` 不能代替数据库创建权限控制。

### 2. [P1] 产物入库失败后仍删除唯一原文件

位置：[FengYu/src/main/java/fan/summer/fengyu/ai/ChatArtifactStore.java:112](/Users/phoebej/Develop/Java/FengYu/FengYu/src/main/java/fan/summer/fengyu/ai/ChatArtifactStore.java:112)

**触发与影响：** completeTurn 对单个 register 失败只记录日志，随后 finally 无条件 revoke staging grant。受管 staging 目录会被删除，因此存储配额耗尽或复制失败时，尚未持久化的生成文件也被清理。

**证据：** 使用临时目录与超过 2 GiB 配额的稀疏文件复现：registered=0，originalOutputExists=false。见 evidence/artifact-loss-current.log。

**建议与验收：** 仅在确认全部产物持久化后删除 staging；失败文件进入可重试、可导出的恢复状态，并将失败反馈给用户。回归覆盖配额与 I/O 故障。

### 3. [P1] 商店回滚失败后销毁事务日志及恢复备份

位置：[FengYu/src/main/java/fan/summer/fengyu/store/StoreService.java:508](/Users/phoebej/Develop/Java/FengYu/FengYu/src/main/java/fan/summer/fengyu/store/StoreService.java:508)

**触发与影响：** rollbackTransaction 捕获 rollbackItem 异常后继续执行 journal.delete。StoreInstallJournal.delete 同时删除备份目录。若恢复旧 skill 的文件移动失败，旧版本备份会在回滚失败后被清理，下一次启动也失去恢复依据。

**证据：** 静态控制流确认：StoreService 503–515、601–620，以及 StoreInstallJournal 171–178。未对真实用户安装目录注入故障。

**建议与验收：** 只有全部回滚成功才能删除日志与备份；逐项记录失败并保留可重试恢复状态。用文件移动故障注入验证备份存活及启动恢复。

### 4. [P1] Windows 更新复制再次失败仍清理安装包并启动

位置：[desktop/electron/src/updater/portable-updater.ts:389](/Users/phoebej/Develop/Java/FengYu/desktop/electron/src/updater/portable-updater.ts:389)

**触发与影响：** 生成的批处理对首次 robocopy 错误执行重试，但第二次复制后直接进入 copydone，不再检查 RC。文件锁、磁盘不足或权限问题持续存在时，脚本仍删除 staging 并启动混合新旧文件的应用。

**证据：** 静态确认生成脚本控制流；当前 macOS 环境未执行 Windows 更新。现有测试通过不能证明该失败分支正确。

**建议与验收：** 第二次 RC≥8 时进入明确失败状态，保留 staging、日志与可用旧版本，停止成功清理和新版本启动。增加持续复制失败的 Windows 回归。

### 5. [P2] 聊天发送锁设置过晚，连续提交启动多个请求

位置：[frontend/src/stores/aiSession.ts:502](/Users/phoebej/Develop/Java/FengYu/frontend/src/stores/aiSession.ts:502)

**触发与影响：** send 在入口检查 busy，但在 await ensureScope、prepareChatSend 和附件准备之后才设置 busy=true。准备期间第二次提交也能通过检查，并生成不同 sendId；后端幂等机制无法合并它们，共享流状态还可能被覆盖。

**证据：** 临时 Vitest 用延迟 prepare 的方式复现：准备期间 busy=false，随后产生两次 aiChat 和两条流。复现测试已从源码目录移除并保存到证据目录。

**建议与验收：** 在第一次 await 前原子设置 preparing/sending 状态，所有失败路径释放；测试双击、Enter 连发和附件准备失败。

### 6. [P2] 会话保存失败被吞掉，切换会话后丢失新消息

位置：[frontend/src/stores/aiSession.ts:262](/Users/phoebej/Develop/Java/FengYu/frontend/src/stores/aiSession.ts:262)

**触发与影响：** 已有 backendId 的会话保存失败时 persist 只吞掉异常。流结束后 busy=false，切换会话会执行 unloadTurns 清空内存消息；重新打开从数据库读取旧内容，刚生成且保存失败的消息丢失。保存仍在进行时也缺少卸载保护。

**证据：** 临时 Vitest 注入 updateConversation 失败后切换会话，确认未保存 turns 从 2 条变成 0 条。

**建议与验收：** 维护 dirty、保存中与保存失败状态；成功落库前保留内存副本，串行化保存并提供重试与明确错误提示。

### 7. [P2] 便携 JAR 更新下载不处理 HTTP 重定向

位置：[FengYu/src/main/java/fan/summer/fengyu/update/SelfUpdateService.java:70](/Users/phoebej/Develop/Java/FengYu/FengYu/src/main/java/fan/summer/fengyu/update/SelfUpdateService.java:70)

**触发与影响：** 该 HttpClient 未配置 followRedirects，下载 checksums、签名和 JAR 又将所有非 2xx 状态作为失败。当下载 URL 返回 301/302/307/308 时，更新直接中止；这条路径没有后续 Location 处理。

**证据：** 静态确认 HttpClient 构造及 231–234、252–258、294–297 的状态码判断。未对线上 Release 执行更新，也未将线上下载成功率作为已测结论。

**建议与验收：** 在更新下载信任策略内实现有界重定向，逐跳校验协议和目标；用本地重定向服务测试校验文件、签名及 JAR 下载。

### 8. [P2] 邮件发送超过五分钟被误判为死任务

位置：[OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/PendingSendService.java:61](/Users/phoebej/Develop/Java/FengYu/OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/PendingSendService.java:61)

**触发与影响：** 每次 status/confirm 等入口都会回收五分钟前进入 SENDING 的记录，但活跃发送无续租。实际 JSON-RPC 可并发派发，查询能在发送过程中把任务置为 FAILED；发送结束的 finish 受 SENDING 条件约束，最终结果与持久化状态不一致。

**证据：** 临时 H2、阻塞的模拟发送器及时间戳推进复现：statusWhileSenderStillActive=FAILED；解除阻塞后 confirmResult=COMPLETED，persistedStatus=FAILED。未发送实际邮件。

**建议与验收：** 采用带所有者/代次的租约并续期，或仅回收确认属于已退出 worker 的任务；检查 finish 更新行数。覆盖长耗时批量发送与并发状态查询。

### 9. [P2] 离线 Python 部署给 pip 传入错误 wheel 路径

位置：[OfficialPlugins/plugin-offlinepython/src/main/java/fan/summer/fengyu/plugin/offlinepython/command/DeployService.java:87](/Users/phoebej/Develop/Java/FengYu/OfficialPlugins/plugin-offlinepython/src/main/java/fan/summer/fengyu/plugin/offlinepython/command/DeployService.java:87)

**触发与影响：** 代码检查 wheelsDir.resolve(whlFile) 存在，却将 whlFile basename 传给 pip install。运行器未切换到 wheelsDir；pip 把 .whl 参数作为相对文件路径，--find-links 不会替代该路径解析，正常部署因此失败。

**证据：** 构造有效的临时纯 Python wheel，执行 pip --dry-run --no-index --no-deps：basename 退出 1（文件不存在），完整路径退出 0（Would install）。没有安装包或访问网络。

**建议与验收：** 传入已计算出的 whlPath 完整路径；使用真实 pip 的离线小 wheel 做集成回归，不仅验证命令参数或使用总是成功的 StubRunner。

### 10. [P2] Webhook 在鉴权及大小检查前完整读取请求体

位置：[FengYu/src/main/java/fan/summer/fengyu/web/controller/WorkflowWebhookController.java:83](/Users/phoebej/Develop/Java/FengYu/FengYu/src/main/java/fan/summer/fengyu/web/controller/WorkflowWebhookController.java:83)

**触发与影响：** 此 POST 入口绕过启动 token，依赖方法内 secret 检查；但 @RequestBody byte[] 会在进入方法前完成请求体物化。parsePayload 的长度上限同样在物化之后，未授权的大请求仍可消耗大量堆内存。默认 loopback 限制了攻击面，但没有解决本地可达调用者造成的资源耗尽。

**证据：** 静态确认参数绑定、TokenAuthFilter 对该入口的豁免和方法内检查顺序。未对正在运行的用户实例发送大请求进行压力攻击。

**建议与验收：** 在读取 body 前验证 secret，并用有界流读取实施实际字节上限；同时覆盖缺失/伪造 Content-Length 和 chunked 请求。

### 11. [P2] TypeScript SDK 包测试与实际 TypeScript 版本冲突

位置：[toolchain/sdk-ts/test/package.test.mjs:8](/Users/phoebej/Develop/Java/FengYu/toolchain/sdk-ts/test/package.test.mjs:8)

**触发与影响：** 包测试只接受 TypeScript 5 的版本字符串，但 package.json 已使用 ^6.0.3。测试会稳定失败；toolchain CI、发布流程和 prepack 都调用 yarn test，因此这项陈旧断言阻塞正常工具链发布。

**证据：** 当前 checkout 执行 yarn test：18 项中 17 通过、1 失败，失败点即版本正则断言。

**建议与验收：** 将测试改为验证实际支持的版本契约，并与构建配置保持一致；重新执行 SDK 测试和 prepack。

## 验证结果

以下是本轮实际运行结果；测试通过仅说明现有测试覆盖的行为通过，不能抵消上述故障路径。

| 范围 | 结果 | 备注 |
|---|---|---|
| 后端 Maven 测试 | 1,210 项，0 失败，0 错误，2 跳过 | `./mvnw -f FengYu/pom.xml test` |
| 官方插件 Maven 测试 | 249 项，0 失败，0 错误，2 跳过 | markdown 6、excel 57、email 87、offlinepython 99 |
| 前端单元测试 | 23 文件、184 通过 | typecheck 同时通过 |
| Electron 单元测试 | 34 文件、263 通过 | TypeScript 构建通过 |
| 发布脚本契约测试 | 52 通过 | `node --test scripts/*.test.mjs` |
| Java SDK | 56 通过 | Maven 模块测试 |
| Java DevKit | 12 通过 | Maven 模块测试 |
| TypeScript SDK | 17 通过、1 失败 | 上述第 11 项实际门禁缺陷 |
| toolchain/dev | 27 通过 | 包测试 |
| toolchain/ui | 10 文件、100 通过 | Vitest |
| Python SDK | 5 通过 | 使用可用 Python 3.12 |
| CLI | 159 通过、1 失败 | 已切换 Python 3.12；剩余失败为本机缺少 Go，未据此判定 Go 代码缺陷 |
| 原有 Electron launch E2E | 跳过 | 调用时未设置 FENGYU_JAR，不记为通过 |
| 补充隔离 Electron 启动 | 通过 | 临时 cwd/profile，启动已有 JAR；验证 preload、后端 health、app://shell 200 |
| 新增故障复现 | 聊天并发/保存、产物删除、邮件状态、pip 路径确认 | 使用临时数据；复现用例未留在业务源码中 |

补充 Electron 验证使用已有构建产物，不能替代从干净 checkout 生成的签名安装包验证。当前没有完成 Windows/Linux 的真实安装、升级、沙箱及卸载矩阵，亦没有进行线上账号、真实邮件发送或用户数据库破坏性演练。

## 覆盖范围与边界

| 模块 | 重点检查 |
|---|---|
| 后端与入口 | 启动/SETUP、loopback、token、CORS、REST、Webhook、文件及会话接口 |
| 插件宿主 | 进程与沙箱、权限/文件授权、包安装、商店事务和回滚、运行时调用 |
| AI/工作流 | 工具批准、命令执行边界、产物生命周期、会话、调度/取消/重试路径 |
| 前端 | SSE、会话持久化和并发、Markdown、插件 iframe 桥及资源授权 |
| Electron | preload/IPC、窗口、后端 supervisor、浏览器桥、产物打开、自更新 |
| 官方插件 | email 状态机与并发、offlinepython 部署、Excel 资源处理、Markdown 及各模块测试 |
| 工具链 | Java/TS/Python/Go SDK、CLI 打包/构建、UI/dev、CI 与发布门禁 |

这是对全仓库模块进行覆盖、对高风险路径深入检查并运行验证的审查，不代表每一行代码都经过形式化证明。Go 编译/运行验证、跨平台沙箱逃逸测试和真实更新故障矩阵仍是明确的验证缺口。插件 iframe 消息源切换、原生文件打开扩展名策略等值得追加针对性测试，但本报告未将尚未充分验证的攻击链计入 11 项确认问题。

## 依赖检查

本轮执行前端和 Electron 的 `yarn npm audit --all --recursive --json`：前端未返回 advisory；Electron 返回 28 条 advisory 记录（20 high、8 moderate）。这些是审计输出记录数，**不是 28 个独立、可利用的生产漏洞**；其中的唯一公告、传递路径、构建依赖与运行时可达性尚需逐条归类。原始输出见证据目录。未完成全 Maven/所有 JS 工作区依赖的实时 CVE 与上游维护状态核验，因此不能声明依赖全面安全，也不在此断言某个依赖已 EOL。

## 修复顺序与复验要求

1. 先修复 4 项 P1：拒绝 H2 任意建库；产物仅成功落盘后清理；回滚失败保留恢复材料；更新复制失败禁止成功清理。
2. 修复聊天准备锁及保存失败保护、邮件租约和 wheel 完整路径。这些问题已有小规模确定性复现，可直接转为回归测试。
3. 完善重定向与 Webhook 有界读取，修复 SDK 门禁断言。
4. 跑受影响模块回归，再在实际 Windows/Linux/macOS 发布产物上验证升级和恢复；补齐 Go 与依赖审计。全部完成后重新评估正式发布资格。

## 证据

已保留本轮测试日志、非破坏性复现程序及输出于本报告旁的 `review-evidence-2026-09-19/`。早期未落盘的前端/Electron/脚本测试结果来自本任务工具输出；不伪造对应日志。证据文件中的临时目录路径只描述复现环境。
