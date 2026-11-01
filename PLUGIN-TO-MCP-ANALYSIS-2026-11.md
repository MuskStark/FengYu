# FengYu 插件体系全量转 MCP Apps 可行性分析（2026-11）

> **数据权威性**：本文基于 `release/4.1.0` 工作区实码核查（2026-11-01），行数/文件数为实测
> （`wc -l` 口径，非测试代码）；MCP Apps 规范状态来自 modelcontextprotocol 官方仓库与博客（见 §7）。
> 本文档是内部策略文档，不入 `docs/` 站点。结论服务于"为架构降低复杂度"这一唯一目标函数。

---

## TL;DR

1. **结论：不建议"全部转成 MCP App"。** MCP（含 Apps 扩展）是一个**线协议 + 聊天内 UI 规范**，
   不是打包/信任/权限/沙箱体系。FengYu 插件复杂度的大头——Ed25519 信任、原子安装事务、OS 级沙箱、
   权限门、FileRef 授予、每插件 DB、控制台整页、flowNodes、i18n——**恰恰是 MCP 不表达的部分**。
   全量替换等于：换掉自研体系中最稳定、最小的一块（stdio 线协议），再往 MCP 上叠 3–4 项自定义扩展
   补洞；净复杂度未必下降，产品能力确定降级（审批语义、文件选择器、控制台页）。
2. **宿主侧 MCP 已是一等公民，收敛其实已完成约 60%**：MCP 工具与内置/插件工具在 `AiToolRegistry`
   同一合流、过同一审批门（`ToolGuardService`）；**MCP Apps 宿主规范（`io.modelcontextprotocol/ui`）
   已经全量实现**（沙箱独立源、资源订阅、opt-in sampling、SSE 事件面）；商店已有 MCP 模板通道。
   真正"双轨"的只剩一层：**插件 worker 的自研 stdio JSON-RPC 线协议**。
3. **有收益的正确切法是"传输层收敛"**：让插件 worker 改说标准 MCP（stdio），`PluginProcessManager`
   与 `McpRuntimeManager` 长期合并为一个进程管理器，删掉三语言 SDK 的自研传输栈（实测 ~2.7–3.2k
   LOC + 宿主协议管线）。打包、信任、权限、UI 控制台壳全部保留。UI 双轨并存：控制台页继续走
   plugin bridge，聊天内轻交互走 MCP Apps。
4. **量化对比**（§4 账本）：全量转换理想情况下净删 ~3.2–3.7k LOC（占插件面 + 工具链 ~21k LOC 的
   15–18%），代价是 4 项自定义 MCP 扩展、审批降级（READ 不再免批）、文件选择器缺口、已装基数迁移
   断裂；**传输层收敛可拿到其中约 80% 的删减，几乎零产品回退、可走协议窗口渐进迁移**（先例：
   worker 协议 `{1,4}` 双版本窗口）。

---

## §1 现状盘点：插件面到底有多重（实码数据）

### 1.1 宿主侧（`FengYu/src/main/java/fan/summer/fengyu/plugin/`：36 文件 / 7,392 行；测试 22 文件 / 5,803 行）

| 子系统 | 代表文件 | 行数 | 职责 | MCP 有无对应 |
|---|---|---|---|---|
| 安装/市场 | `PluginPackageService` | 1,338 | zip 防护、大小上限、权限升级确认、原子换装 + 回滚日志 | ❌ 无 |
| 信任 | `PluginTrustStore` + `PluginIntegrityStore` | ~600 | Ed25519 发布者根、吊销、每次启动前完整性重验 | ❌ 无 |
| 进程管理 | `PluginProcessManager` | 1,733 | 生成 worker、管线并发、超时、崩溃退避、资源监控 | ⚠️ 协议部分有 |
| 线协议 | `PluginWorkerProtocol` | ~100 | `$/fengyu/initialize`、`$/cancelRequest`、日志帧 | ✅ MCP 标准 |
| 文件授予 | `PluginFileGrantService` | — | 不透明 `ref_` 授予、可读写根、版本失效 | ❌ 无 |
| 环境供给 | `PluginRuntimeEnvironmentService` | — | 数据目录、DB 凭据（仅 `database` 权限） | ⚠️ env 可平移 |
| 商店 | `store/` 12 文件 | ~2,000 | 统一目录、签名票据、安装分发 | ❌ 自有 |
| 相邻 | `AiToolRegistry` 865 / `ChatToolApprovalGate` 578 / `ToolLoopDriver`（插件感知） | — | 工具合流 + 审批 | ✅ 已合流 |

前端面约 771 行（`PluginPage.tsx` 318 + `services/plugin.ts` 176 + 后台任务 store 等）。

### 1.2 工具链（`toolchain/`：≈12,997 行非测试 + ~2,051 行模板 + 882 行 spec JSON）

| 包 | 行数 | 角色 | 转 MCP 后的命运 |
|---|---|---|---|
| `cli`（`fengyu`） | 4,032 | init/dev/check/build/add/generate/migrate/**sign** | **保留**（打包签名与协议无关） |
| `ui`（plugin-ui） | 2,193 | Infinia 设计语言组件套件 | **保留**（与桥接无关） |
| `sdk-java` | 2,189 | `JsonRpcWorker` 传输栈 + 契约注解 | 传输半 (~1,000) **可删**，注解可映射 MCP 元数据 |
| `dev` | 2,071 | Vite 模拟宿主 + worker 客户端 | 大部分**保留**（模拟器改为对 MCP server 调试） |
| `devkit-java` | 946 | IDE 回环调试服务器 + 注解处理器 | 传输 (~300) 可删，处理器保留 |
| `sdk-python` / `sdk-go` | 492 / 401 | 同一 worker 契约的他语言实现 | **可整体删**（改用官方 MCP SDK） |
| `sdk-ts` | 451 | iframe postMessage 桥（8 个宿主方法） | **仅当控制台页也消灭时才可删** → 产品回退 |

**关键观察**：把 `plugin/` 7,392 行按"线协议 vs 信任/安装/沙箱/服务"分解，纯协议相关约 1/4；
其余 3/4 是 MCP 根本不涉足的领域。自研线协议是这套体系里**最规范、最稳定、文档最全的部分**——
拿它去换标准，收益天花板从一开始就被限定了。

---

## §2 现状盘点：MCP 面已经有多深

`ai/mcp/`（含 `apps/`）+ `McpRuntimeManager`（1,884 行）已达到生产级：

- **三种传输**（STDIO / SSE / STREAMABLE_HTTP，官方 Java SDK `io.modelcontextprotocol.sdk:mcp:2.0.0`，
  规范线至 2025-11-25）；并发启动、指数退避自愈重连、`tools/list_changed` 热刷新。
- **同一 AI 环路**：MCP 工具并入 `AiToolRegistry`（`server__tool` 命名空间、去重防遮蔽、延迟加载、
  64 KiB 结果预算、用量打点、Flow 画布合成节点）。
- **同一审批门**：包装为 `ToolEffect.EXTERNAL`，走 `ToolGuardService`，支持 `Mcp(server__tool)`
  权限规则语法与"总是允许"会话授予。
- **MCP Apps 宿主全套**：`io.modelcontextprotocol/ui` 能力声明、`ui://` 资源（16 MiB 上限）、
  独立沙箱源（`McpSandboxController`，专用端口 + `default-src 'none'` CSP + 不透明源 iframe）、
  每调用确认对话框、资源订阅 SSE、opt-in sampling（桥到当前聊天后端，硬限额）、
  聊天卡片（`ToolCard` → `McpAppCard`）**以及超出规范的私有扩展——独立沙箱页**（`McpSandboxRegistry`）。
- **分发通道**：商店 MCP 条目 = URL 模板（拒绝 stdio/无 URL），导入即禁用、需确认后启用；
  可从 Claude/Codex/Grok 配置导入。
- **密钥安全**：`secrets.json` 机器绑定加密 + POSIX 600；env 拒绝表（`NODE_OPTIONS`/`LD_PRELOAD`…）
  与宿主秘密中和。

**MCP 面的缺口**：无 OAuth、无 elicitation、无 roots、prompts 仅诊断列举不入环、
**stdio 服务器不走 OS 级 `ProcessSandbox`**（只有 env 负面拒绝表；插件的 env 是正向白名单 +
Job Objects / sandbox-exec / bwrap + 内存/进程数上限）。这个不对称在 §4 账本里是"复杂度转移"项。

---

## §3 能力映射表：`.fyp` → MCP(+Apps) 逐项判定

| # | `.fyp` 能力 | MCP 对应物 | 判定 |
|---|---|---|---|
| 1 | worker stdio JSON-RPC 2.0 + initialize 版本握手（`{1,4}` 窗口） | MCP stdio 传输 + 协议版本协商 | ✅ **标准化的真实收益所在** |
| 2 | `rpc.methods`：inputSchema/outputSchema 双向校验 | `tools/list` inputSchema + structured output | ✅ 可映射（校验严格度改由宿主自理） |
| 3 | `$/cancelRequest` 合作取消 | `notifications/cancelled` | ✅ |
| 4 | `@fengyu-log:` 结构化日志帧 | MCP `logging` capability + `logging/setLevel` | ✅ 更标准 |
| 5 | `aiTools.effect`（read/write/external **强制**）驱动审批（READ 永不弹窗） | `annotations.readOnlyHint/destructiveHint/idempotentHint`（**可选提示**） | ⚠️ **降级**：宿主现在把 MCP 工具一律按 EXTERNAL 处理必弹审批；要么信任弱提示（安全语义弱化），要么自定义扩展 |
| 6 | `callTimeoutSeconds` + `memoryMb`/`maxProcesses` 资源上限 | 无 | ❌ 宿主侧自理（MCP 路径现无资源上限） |
| 7 | OS 级沙箱 + env 正向白名单 | env 负面拒绝表 + 宿主秘密中和 | ❌ **需把 `ProcessSandbox` 扩到 MCP stdio——复杂度转移而非删除** |
| 8 | FileRef 授予 + `files.open/inputDirectory/workspaceDirectory/outputDirectory/export` 原生选择器 | **无对应**（现有 `McpFileHandoffConfig` 只覆盖"聊天附件→路径注入"这一窄场景） | ❌ **最大产品缺口**：本地文件型插件（smoke-plugin 的 file_read/file_write 即此类）无法表达 |
| 9 | `ui.entry` 控制台整页（Tools 页、theme/locale 环境推送、独立 CSP、uiTicket 门） | MCP Apps 本义是工具结果附挂的聊天内卡片；独立沙箱页是 FengYu 的**私有扩展** | ⚠️ 半缺口：保留控制台 ⇒ `sdk-ts` 桥留下、UI 面**零简化**；砍掉控制台 ⇒ 产品降级 |
| 10 | `i18n` 清单解析（locale → 展示名，LLM 恒见英文） | 无（工具描述单语言） | ❌ 自定义资源约定 |
| 11 | `flowNodes`（画布节点/widget/context feed，安装期校验） | 无（`mcpFlowNode` 是合成节点，无 widget/上下文绑定） | ❌ 自定义约定 |
| 12 | `permissions` 9 项 + 安装期/升级期确认 | 无 | ❌ `.fyp` 壳保留 |
| 13 | hooks（可否决工具调用，信任门控） | 无 | ❌ |
| 14 | 每插件 DB 供给（嵌入文件或服务器 schema 凭据） | 无（但 stdio env 注入可行） | ⚠️ env 方式可平移 |
| 15 | 后台任务约定（`*_start`/`*_status` + 壳级指示器） | 无（与传输无关） | ✅ 平移 |
| 16 | `.fyp` 打包 + Ed25519 目录签名 + 完整性重验 + 原子安装日志 | **无**（商店 MCP 条目只是 URL 模板且**拒绝 stdio**） | ❌ 本地工具型插件必须保留打包分发 |
| 17 | `engines.fengyu` SemVer 兼容窗口 | 无 | ❌ |
| 18 | 通知管道 / `screen.capture` 授予 | 无 | ❌ |

**18 项中：干净映射 5 项（#1–4、15），降级/半缺口 4 项（#5、9、6、14），无对应 9 项。**
无对应的 9 项正是 `plugin/` 那 3/4 行数所守护的东西。

---

## §4 复杂度账本：三条路线对比

| 维度 | A. 全量转 MCP App | B. 传输层收敛（推荐） | C. 维持双轨 |
|---|---|---|---|
| 可删代码 | ~3.2–3.7k LOC（三语言 SDK 传输栈 ~2.2k + 宿主协议管线 ~0.8k + `sdk-ts` 0.45k） | ~2.7–3.2k LOC（同上但**不含** `sdk-ts`） | 0 |
| 必须新增 | ≥4 项 MCP 自定义扩展（effect 元数据、文件选择器桥、flowNodes/i18n 资源约定）+ `ProcessSandbox` 进 MCP 路径 + MCP-server 化迁移层 | 1 项：宿主以 MCP 客户端身份驱动插件 worker（v5 协议窗口）；沙箱扩展与 B 正交可后置 | 0 |
| 产品回退 | 控制台页消失或被迫保留（保留则 UI 零简化）；READ 工具审批必弹；本地文件工作流断裂 | 无 | 无 |
| 迁移风险 | 全体插件作者重写 worker；已装基数、`{1,4}` 窗口、商店、`fengyu-plugin-dev` skill、e2e 夹具全部断裂 | 走协议 v5 + 双版本窗口渐进（有 `{1,4}` 先例）；旧 worker 继续跑 | 无 |
| 生态对齐 | 与任意 MCP server 互通（但**本来就已互通**——MCP 面独立存在） | 同左 | 同左 |
| 规范耦合风险 | Apps 规范仍在 draft 演进（2026-01-26 快照 + 持续修订）；按 AGENTS.md 无 EOL 依赖规则需持续追 | 仅依赖 MCP 核心线（远比 Apps 稳定） | 无 |
| 净复杂度 | **未必降**：删 3.5k、增 4 扩展 + 沙箱转移 + 迁移层 | **确定降** ~2.7–3.2k LOC，双协议 → 单协议 | 不变 |

**账本结论**：A 路线的理论删减里，`sdk-ts`（0.45k）只有牺牲控制台页才拿得到；effect/文件/沙箱三项
要么降级要么变成"标准协议上的自研扩展"——后者比今天的自研协议**更糟**（自定义扩展要跟着上游
规范版本漂移）。B 路线拿走 A 路线 80% 的删减，却把所有无对应项原封留在经过实战的 `.fyp` 壳里。

---

## §5 MCP Apps 规范成熟度风险

- Apps 扩展位于 `modelcontextprotocol/ext-apps` 仓库，**draft + 2026-01-26 快照并行，仍在演进**；
  由 MCP-UI 社区方案收编而来。
- 规范的安全模型面向"聊天内的一次性交互件"：双 iframe 隔离、默认禁下载、`connectDomains` 白名单
  CSP——与"常驻控制台 + 原生文件对话框 + 主题跟随"的产品诉求不是同一物种。FengYu 的独立沙箱页
  （`McpSandboxRegistry`）已经是对该模型的**超前私有扩展**，继续把控制台压上去会让私有扩展面越滚越大，
  与"降复杂度"背道而驰。
- MCP 核心线（tools/resources/logging/cancel）稳定且 FengYu 已深度依赖——这正是 B 路线只押注
  核心线、不押注 Apps 承载控制台的原因。

## §6 建议

1. **不做全量替换**（否决 A 路线）。插件 = "打包分发 + 信任 + 沙箱 + 权限 + 控制台"的**应用壳**，
   MCP = **线协议**；两者是包含关系而非替代关系。
2. **立项传输层收敛**（B 路线）：定义 worker 协议 v5 = MCP stdio；宿主新增"插件 worker 即 MCP
   server"驱动模式；`PluginProcessManager` 逐步与 `McpRuntimeManager` 合并为单一进程管理器
   （沙箱、退避、资源上限、env 供给各留一份）。三语言 SDK 传输栈退役，改薄壳包官方 MCP SDK；
   `fengyu-plugin-dev` skill 与 `fengyu dev` 模拟器随之切换。
3. **产品分层顺势澄清**：控制台型/本地文件型插件 = `.fyp`；轻量聊天交互 = MCP Apps（已就绪）；
   商店 PLUGIN 条目未来可加 `mcpServer` 声明让工具面直接走标准 MCP，获得双生态位。
4. **与 MCP 深化 P0 合并排期**：OS 沙箱扩展到 stdio MCP 服务器、effect 元数据通道（annotation
   信任策略）这两项，无论是否转换都该做——它们是 §2 缺口清单，也是 A/B 两路线的共同前置。

## §7 来源

- 实码：`FengYu/src/main/java/fan/summer/fengyu/plugin/**`、`ai/mcp/**`、`ai/config/AiToolRegistry.java`、
  `ai/tools/ToolGuardService.java`、`toolchain/**`（2026-11-01 工作区实测）。
- MCP Apps 规范：[ext-apps 仓库 draft/apps.mdx](https://github.com/modelcontextprotocol/ext-apps/blob/main/specification/draft/apps.mdx)、
  [MCP 官方博客：MCP Apps – Bringing UI Capabilities To MCP Clients（2026-01-26）](https://blog.modelcontextprotocol.io/posts/2026-01-26-mcp-apps)、
  [MCP Apps in Practice](https://modelcontextprotocol.info/blog/mcp-apps-ui-capabilities)、
  [反编译 ChatGPT Apps iframe 沙箱](https://dev.to/infoxicator/i-reverse-engineered-chatgpt-apps-iframe-sandbox-2ok3)。

## §8 开放问题（决策前需补的数据）

1. 商店在售官方插件的形态分布：本地文件型（吃 FileRef 缺口）占比多少？若趋近于零，#8 缺口权重下调。
2. `PluginSettingEntity` 现处休眠（主代码无引用）——插件设置面若激活，控制台页的保留价值进一步上升。
3. 上游 ext-apps 是否会把"独立 app 页/文件系统桥"标准化；若标准化（时间窗 6–12 个月），控制台页
   迁移可重新评估，届时 B 路线已铺好的传输层使这次再迁移成本接近零。
