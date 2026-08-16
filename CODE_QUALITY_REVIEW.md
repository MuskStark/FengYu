# FengYu 代码质量审查报告

- 审查日期：2026-08-16
- 审查对象：当前工作区（包含未提交与未跟踪改动）
- 审查范围：Spring Boot 后端、Vue 前端、Electron 桌面端、官方插件、插件工具链、构建与 CI 配置
- 总体结论：**有条件通过；修复 CQ-01 前不建议发布**

## 1. 执行摘要

项目的模块边界、自动化测试广度和安全意识整体较好。本次执行的后端、前端、桌面端、官方插件、工具链及发布契约测试全部通过，说明当前工作区没有显性的编译或常规回归问题。代码中也能看到多项扎实的防御设计，例如 SSE 一次性票据、插件包大小与条目数限制、严格 JSON 解析、插件进程环境变量收敛、FileRef 隔离和更新包哈希校验。

但审查确认了 1 项发布阻断级安全问题、2 项高优先级运行时问题，以及若干中优先级质量风险：

| 编号 | 级别 | 结论 |
|---|---|---|
| CQ-01 | P0 | 灾难性命令硬拒绝可被换行、`${HOME}` 和参数顺序绕过；`FULL_ACCESS` 下可直接执行 |
| CQ-02 | P1 | SSE 重连与服务端生命周期不一致；AI 流无法恢复，Agent 流重连期间会丢事件 |
| CQ-03 | P1 | 插件崩溃循环保护把正常的主动重启计为崩溃，可能错误封禁健康插件 |
| CQ-04 | P2 | Windows 便携更新下载没有字节上限，也未正确传播文件流写入错误 |
| CQ-05 | P2 | Hook 超时只有下限没有上限，错误配置可让每次工具调用长时间阻塞 |
| CQ-06 | P2 | 诊断测试输出 API Key 前缀，断言失败时还会输出完整 Key |
| CQ-07 | P2 | 静态分析与覆盖率没有强制质量门禁，且多个核心文件已成为超大单体 |

建议先修复 CQ-01，再处理 CQ-02/CQ-03；CQ-04～CQ-07 可在同一发布周期内分批收敛。

## 2. 审查基线与方法

当前工作区改动规模较大：未暂存部分涉及 217 个文件、约 9,813 行新增和 29,979 行删除；另有大量已暂存文档删除。为避免破坏用户工作，本次没有修改现有实现，只新增本报告。

采用的方法包括：

1. 审查 Maven/Yarn/CI/Qodana 配置和模块边界。
2. 抽查鉴权、SSE、AI 工具权限、Hook、插件安装与进程隔离、数据库迁移、自动更新等高风险路径。
3. 搜索空异常处理、调试输出、动态 HTML、进程启动、线程与资源生命周期等常见风险模式。
4. 执行后端、前端、桌面端、官方插件、工具链和发布契约测试。
5. 使用 JShell 直接验证灾难性命令检测器的边界输入。

## 3. 详细发现

### CQ-01（P0）灾难性命令硬拒绝可绕过

位置：

- `FengYu/src/main/java/fan/summer/fengyu/ai/tools/ToolPermissionRules.java:382`
- `FengYu/src/main/java/fan/summer/fengyu/ai/tools/ToolPermissionRules.java:390`
- `FengYu/src/main/java/fan/summer/fengyu/ai/tools/ToolPermissionRules.java:402`
- `FengYu/src/main/java/fan/summer/fengyu/ai/tools/ToolApprovalPolicy.java:15`

代码注释承诺灾难性命令会在 Hook、规则和权限模式之前被无条件拒绝，且 `FULL_ACCESS` 也不能绕过。但当前实现存在两个缺口：

1. `normalizeSegment()` 对换行、`${...}` 等无法解析的文本返回 `null`，`segmentIsCatastrophic()` 却把 `null` 当作“不是灾难性命令”。
2. `rm` 检测只在已经遇到递归参数后才检查根目录目标，因此 GNU/Linux 上有效的 `rm / -rf`、`rm / --recursive` 不会命中。

实测结果：

```text
rm -rf /                 => true
rm / -rf                 => false
rm / --recursive         => false
sudo rm / -rf            => false
echo safe\nrm -rf /       => false
rm -rf ${HOME}           => false
```

当工具权限模式为 `FULL_ACCESS` 时，`ToolApprovalPolicy` 直接返回不需要审批，因此这些漏检形式不会被后续的“危险命令需询问”逻辑兜底。

影响：在用户启用全权限模式后，模型生成的等价命令可能绕过代码明确声明的最后安全边界，造成用户目录、文件系统根目录或重要数据被删除。

建议：

- 硬拒绝层对无法可靠解析的命令应失败关闭，至少在文本包含换行、命令替换、变量展开或未平衡引号时返回“拒绝”，而不是 `false`。
- 对 `rm` 先完整收集所有选项和所有操作数，再判断“存在递归选项且任一目标为根目录”，不要依赖参数顺序。
- 将命令解析与执行使用同一规范化结果；更稳妥的做法是为允许执行的 shell 语法定义明确子集。
- 增加回归矩阵：换行链、`${HOME}`、选项后置、长短参数混排、`sudo`/`env`/`sh -c` 包装，以及 Windows 驱动器根目录。

### CQ-02（P1）SSE 重连语义与服务端生命周期不一致

位置：

- `frontend/src/api/sse.ts:57`
- `frontend/src/api/sse.ts:91`
- `FengYu/src/main/java/fan/summer/fengyu/web/controller/AiController.java:179`
- `FengYu/src/main/java/fan/summer/fengyu/web/controller/AiController.java:221`
- `FengYu/src/main/java/fan/summer/fengyu/web/controller/AgentController.java:477`
- `FengYu/src/main/java/fan/summer/fengyu/web/controller/AgentController.java:566`

AI 聊天前端在原生 EventSource 断线后申请新票据，并使用同一个 `streamId` 重连；但后端在第一次连接时已通过 `pending.remove(streamId)` 消费请求，并在传输断开时取消模型生成。因此第二次连接只能得到 `Unknown or expired streamId`，并不能恢复流。另一个细节是 AI 前端没有在连接成功后重置 `retries`，与注释声称的“连续失败次数”也不一致。

Agent 流允许重新 attach，但第一次 drain 后 `drained` 永远为 `true`；旧客户端死亡时 `emitter` 仍指向旧对象，`clientDead` 使后续事件直接被丢弃而不是重新进入缓冲区。新客户端连接后只能收到未来事件，无法补回断线窗口内的计划/步骤事件。

影响：短暂网络/WebView/SSE 中断会导致聊天被取消、错误信息失真，或 Agent UI 缺少关键步骤状态。当前测试只验证终止清理一次，没有覆盖断线—重连—补发链路。

建议：

- AI 聊天二选一：明确“不支持恢复”并立即报告断线/取消；或引入可重放事件序号和服务端流状态，真正支持 resume。
- Agent sink 在客户端断开后应清空/替换 emitter，并把新事件重新缓冲；使用单调事件序号或 `Last-Event-ID` 进行补发和去重。
- 为前端和控制器增加跨层契约测试，覆盖票据消耗、断线、重连、终止事件和重复连接。

### CQ-03（P1）正常插件重启被计入崩溃循环

位置：

- `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginProcessManager.java:273`
- `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginProcessManager.java:283`
- `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginProcessManager.java:418`

Worker 不复用的原因不仅包括进程死亡，还包括 FileRef grant 版本变化、沙箱模式变化、manifest 版本变化和包摘要变化。这些都是预期的主动重启条件，但当前代码对所有 `current != null` 都先调用 `recordRapidDeath()`，再主动关闭 Worker。

因此，用户在 20 秒内连续授权文件、切换相关设置或更新同版本包三次，就可能触发 30 秒崩溃冷却，即使 Worker 始终健康。日志还会错误记录为“worker crashed”。

建议：

- 仅在 `!current.alive()` 或获得明确的非预期退出原因时累计 rapid death。
- 将“主动重启”“调用超时后终止”“进程异常退出”建模为不同原因。
- 正常运行超过窗口后主动清除计数；增加 FileRef 变化、包升级和沙箱切换不触发封禁的测试。

### CQ-04（P2）Windows 便携更新下载可无界占用磁盘

位置：`desktop/electron/src/updater/portable-updater.ts:205`

`downloadFile()` 仅用 `Content-Length` 计算进度，没有校验声明长度，也没有对实际接收字节设置上限。恶意或损坏的更新源可以持续写入临时 ZIP，直至磁盘耗尽。后端 `SelfUpdateService` 已有 512 MB 的实际字节硬上限，桌面便携路径应保持同等防护。

此外，`createWriteStream()` 的写入回调忽略错误参数，也没有显式监听 `error`；磁盘写满或权限异常时，错误可能表现为未处理事件，而不是可控地进入手动更新降级路径。

建议：为声明长度和实际接收长度设置同一硬上限；使用 `pipeline()` 或正确处理 `write/end/error` 的 Promise；失败时删除完整 staging 目录。

### CQ-05（P2）Hook 超时没有上限

位置：

- `FengYu/src/main/java/fan/summer/fengyu/ai/tools/ToolGuardService.java:257`
- `FengYu/src/main/java/fan/summer/fengyu/ai/tools/ToolGuardService.java:265`
- `FengYu/src/main/java/fan/summer/fengyu/ai/tools/ToolGuardService.java:272`

Hook 配置只做 `Math.max(1, timeout)`，没有最大值。HTTP Hook 和命令 Hook 都在工具调用关键路径串行等待，因此一个误填的超大值可让每次工具调用阻塞极长时间；最多 50 个 Hook 时影响会叠加。

建议：保存和加载时都限制到明确范围（例如 1～60 秒），插件贡献 Hook 使用同一上限，并在 UI 显示范围。建议同时限制 HTTP 响应体大小。

### CQ-06（P2）诊断测试泄露 API Key 内容

位置：`FengYu/src/test/java/fan/summer/fengyu/ai/AiConfigServiceReadDiagTest.java:42`

测试每次运行都会输出 Key 长度和前 6 个字符；更严重的是断言失败信息会拼接完整 Key。当前值虽然是测试夹具，但这种测试模式容易在后续替换为环境/真实凭据时把秘密写入 CI 日志，也会弱化仓库已有的“秘密不得进入日志”约束。

建议：删除诊断输出，使用 `assertEquals()` 与固定假值验证；断言消息只描述字段名和是否为空，永不包含实际值。Excel 插件测试中的 `DBG` 输出也应一并清理。

### CQ-07（P2）质量门禁和可维护性不足

位置：

- `qodana.yaml:13`
- `qodana.yaml:39`
- `.github/workflows/qodana_code_quality.yml:26`
- `frontend/package.json`
- `desktop/electron/package.json`

Qodana 使用 JVM Community Starter 配置，但严重度和覆盖率失败条件全部注释；TypeScript/Vue 区域没有 ESLint/Prettier 脚本或覆盖率阈值。测试很多，但“测试通过”目前不能阻止新增未覆盖分支或静态分析债务。

复杂度也已经集中到少数超大文件：

| 文件 | 行数 |
|---|---:|
| `frontend/src/views/AiAgent.vue` | 2,818 |
| `OfficialPlugins/plugin-excel/ui-src/src/ExcelSplitter.vue` | 1,606 |
| `frontend/src/views/Settings.vue` | 1,473 |
| `FengYu/.../plugin/runtime/PluginProcessManager.java` | 1,079 |
| `FengYu/.../ai/agent/AgentRunner.java` | 771 |
| `FengYu/.../plugin/market/PluginPackageService.java` | 747 |
| `FengYu/.../web/controller/AgentController.java` | 664 |

这些文件同时承担状态机、网络协议、UI、权限判断和生命周期管理，CQ-02/CQ-03 正是跨职责状态组合导致、单元测试不易覆盖的典型问题。

建议：

- 为 Java 设置可执行的静态分析失败阈值，并增加 JaCoCo 新代码覆盖率门禁。
- 为 TS/Vue 引入 ESLint（含 Vue/TypeScript 规则）和 Vitest 覆盖率阈值。
- 按领域拆分：Agent SSE transport/store/composables、AgentController 的 run/workflow/task 子控制器、PluginProcessManager 的 worker lifecycle/crash policy/transport。
- 优先拆分变化频繁且超过 700 行的生产文件，不做一次性全仓格式化。

## 4. 已通过的验证

| 验证 | 结果 |
|---|---|
| `./mvnw test -f FengYu/pom.xml` | 通过：633 tests，0 failure，0 error，2 skipped |
| 前端 `yarn test:unit && yarn test && yarn typecheck` | 通过：39 Vitest + 15 Node tests，类型检查通过 |
| 桌面端 `yarn test && yarn build:ts` | 通过：160 tests，TypeScript 编译通过 |
| `./mvnw test -f OfficialPlugins/pom.xml` | 四个当前官方插件全部通过 |
| 四个官方插件 UI 的 `yarn test && yarn typecheck` | 全部通过 |
| Toolchain CLI/dev/sdk-ts/ui 测试 | 全部通过 |
| Java SDK 与 DevKit 测试 | 全部通过 |
| 发布、Node 版本、版本解析、便携启动器契约测试 | 38 tests 全部通过 |
| `git diff --check` | 通过 |

测试运行中观察到但未导致失败的警告：

- Flyway 提示当前 H2 2.4.240 高于其已验证的 2.3.232。
- Maven resources 插件提示过滤资源编码未显式配置。
- 前端测试使用已弃用的 `theme.global.name.value` Vuetify API。
- Excel 插件测试提示 Log4j API 未找到 provider，并存在 `DBG` 标准输出。

## 5. 做得较好的方面

- 后端、桌面端、插件与工具链均有较丰富的自动化测试，且本次全线通过。
- SSE 鉴权从完整 Token 查询参数迁移到短期单次票据，显著降低凭据进入 URL 日志的风险。
- 插件包安装具有压缩包大小、展开大小、条目数、路径穿越和摘要校验等多层限制。
- 插件 Worker 环境变量采用白名单式收敛，并对日志中的敏感值进行脱敏。
- AI 工具权限新增 deny/ask/allow、严格 JSON 参数解析和结果解析一致性检查，设计方向正确。
- 数据库引入 Flyway baseline，为后续非增量式迁移建立了版本化通道。
- 后端便携更新已实现实际字节上限和可选 Ed25519 校验，具备继续强化的基础。

## 6. 残余风险与未执行项

以下项目本次未执行，不能由“单元测试全绿”推断为已验证：

- `scripts/e2e-smoke.sh`（会启动完整 JAR 并探测端点）。
- Electron Playwright E2E，尤其是发布构建 `file://` 前端、sidecar 启动链和 opt-in browser bridge。
- UI 视觉回归与无障碍全量扫描。
- Maven/Yarn 依赖 CVE 与许可证审计。
- 多数据库真实实例迁移（SQLite/MySQL/PostgreSQL）；本次 Flyway 验证主要基于 H2。

另外，Ed25519 更新签名目前仍是可选能力：仓库未包含 `/update/release-signing-public.pem`，CI 在没有 `FENGYU_SIGNING_KEY` 时会发布仅有 checksum 的未签名版本。这与当前文档声明的“未签名 Beta”一致，但 checksum 与制品来自同一更新源，不能抵御更新源被攻陷。正式发布前应把公钥资源和 CI 私钥配置变为强制发布条件。

## 7. 建议整改顺序

1. **立即修复 CQ-01**，新增绕过矩阵测试，并让 `FULL_ACCESS` 也经过同一不可覆盖硬拒绝层。
2. 修复 CQ-02，明确 AI/Agent SSE 的可恢复协议并补跨层测试。
3. 修复 CQ-03，将正常重启与非预期崩溃分离建模。
4. 为桌面更新和 Hook 增加资源上限，清理秘密/调试输出。
5. 建立 Java + TypeScript 的静态分析和新代码覆盖率门禁。
6. 在后续迭代中拆分超大组件与生命周期管理类，再运行完整 JAR smoke 和 Electron E2E。

