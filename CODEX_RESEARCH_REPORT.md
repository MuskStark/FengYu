# OpenAI Codex 调研报告：面向 FengYu 4.0.0 的优化建议

> 调研对象：[openai/codex](https://github.com/openai/codex)（codex-rs + Codex App Server）
> 目标：从「主项目架构 / 上下文管理 / 浏览器自动化 / 插件系统 / Agent 调用 / 命令执行 / 审批策略 / MCP 扩展」八个维度，对照 FengYu 现状，给出可落地的优化建议。
> 编写日期：2026-08-13　·　对照分支：`4.0.0`
>
> 说明：本报告中 FengYu 侧的结论均基于对仓库实际源码的阅读（标注了 `文件路径`）；Codex 侧结论基于其公开仓库、官方博客与架构分析文献。任何落地前，请以 `openai/codex` 最新源码为准复核。

---

## 0. 一句话结论

FengYu 与 Codex 在**审批门控、OS 沙箱、进程隔离**上已经基本对齐，甚至在「平台隔离诚实度」「崩溃隔离」「Plan-and-Execute」上有自己的优势；但 FengYu 在**上下文压缩（compaction）、Agent 循环的成本治理、工具结果回流、MCP 的运行时管理**这四块与 Codex 有明显差距。**最高优先级 = 给 `ChatSession` 加上真正的 compaction（而非按轮次截断）。**

---

## 1. Codex 项目总览

### 1.1 组成结构

| 层 | 角色 | 对应 FengYu 的什么 |
|---|---|---|
| **codex-rs / core** | 共享 Rust 库：Agent 循环、Thread 生命周期、config、auth、**沙箱化工具执行** | FengYu 的 `ai/agent/` + `ai/service/` + `ai/tools/` + `security/` |
| **Codex App Server** | 常驻进程，对外暴露**双向 JSON-RPC（JSONL over stdio）**，4 个内部组件：stdio reader / 消息处理器 / thread manager / core threads | FengYu 的 `web/controller/`（SSE/REST）—— 但协议形态不同 |
| **Codex CLI / TUI** | 终端客户端 | FengYu 的 `frontend/`（Vue SPA）+ `desktop/electron/` |
| **codex-rs / mcp-client** | MCP 客户端 | FengYu 的 `web/controller/McpController.java`（基于 Spring AI MCP） |

> 关键洞察：Codex 把「core 运行时」抽成一个**进程内 Rust 库 + JSON-RPC 协议**，从而 CLI、IDE 插件、Codex Web 三种前端共用同一个 core。FengYu 的「core」是 Spring Boot 进程内的服务层，前端通过 REST/SSE 访问——形态不同，但「一个 core、多前端」的分层思想值得借鉴（见 §3.1）。

### 1.2 三个原语（Item / Turn / Thread）

Codex 的运行时建立在三个原语上，事件流清晰、可重放：

- **Item**：原子 I/O 单元（消息、工具调用、审批、diff）。生命周期事件：`item/started` →（可选）`item/*/delta` 流 → `item/completed`。
- **Turn**：「一次 Agent 工作」，从客户端提交输入开始，到所有输出结束。
- **Thread**：持久容器，承载多个 Turn，**把事件历史落盘**，客户端断线可重连恢复。

FengYu 的对应物：`AgentRun / AgentStep / StepExecution`（`ai/agent/`）+ `AgentRunPersistenceService`，已经有「落盘恢复」的概念（`getRestoredExecutions()`），方向一致。差距在于**事件协议的标准化与前端可重放性**（见 §3.5）。

---

## 2. 维度对照与优化建议

### 2.1 主项目架构（harness / 多前端复用）

**Codex 做法**
- core 是纯库，App Server 用 JSON-RPC 把它暴露成「可被任意前端驱动的 Agent 运行时」。Codex Web 把 App Server 跑在容器里，浏览器经 HTTP+SSE 与之通信；本地 CLI 直接把它当子进程拉起。
- 一个双向 JSON-RPC 接口同时承载：流式进度、工具调用、审批、diff。

**FengYu 现状**
- `HeadlessLauncher`（loopback Spring Boot）是唯一的 core；前端（Vue/Electron）通过 `web/controller/` 的 REST/SSE 访问。
- 已经是「headless + 多前端」架构，方向正确。

**差距 / 建议**
- ✅ **保持现状即可**，无需照搬 Codex 的 JSON-RPC-over-stdio。FengYu 的 REST/SSE 对 Web/Electron 前端更自然。
- 💡 **可选增强**：把 Agent 事件抽象成「Item/Turn/Thread」式的**统一事件协议**（建议在 `AgentEventSink` 之上定义一套与传输无关的事件 schema，SSE 只是它的一个编码）。好处：未来做「断线重连续跑」「回放调试」「第三方接入 headless core」时无需改动运行时。当前 `AgentEventSink` 已是很好的解耦点，再向前推一步即可。

---

### 2.2 上下文管理（Context Management / Compaction）⭐ 最高优先级

**Codex 做法**
- 每一轮都把**完整历史**重新发给模型（理论上 O(n²) 字节），靠 prefix caching 与 **compaction** 兜底。
- 当 token 超过 `auto_compact_limit`，调用 `/responses/compact`：**返回一个更小的 input items 列表** + 一个 `type=compaction` 的条目，其中包含**不透明的 `encrypted_content` blob**（编码了模型的「潜在理解」）。ZDR 模式下解密钥留在 provider 侧——**模型的推理被保留，但不存储明文对话**。
- prompt 按**稳定性排序**以最大化 prefix cache 命中：开发者沙箱声明 → `~/.codex/config.toml` 开发者消息 → 聚合的 `AGENTS.md` → 环境上下文（cwd/shell）→ 用户 prompt。

**FengYu 现状（已读源码确认）**
- `ai/session/ChatSession.java`：只有**按轮次截断**（`maxHistoryRounds`，默认 20）。`trim()` 直接 `history.remove(最老的 user/assistant 轮)`，**不做摘要**——被丢掉的上下文永久丢失，模型再也看不到。
- `ai/service/SpringAiCloudBackend.java#runToolLoop`：每轮把 FengYu history 完整转成 Spring AI `Message` 列表发给模型，**没有任何压缩、没有 token 计数、没有 prefix-cache 友好的排序**。
- token 估算仅用于 `onComplete` 的展示（`finalText.length()/4`），不参与治理。

**差距**
| 点 | Codex | FengYu |
|---|---|---|
| 压缩 | 真·compaction（保留潜在理解） | ❌ 仅轮次截断（丢信息） |
| 触发 | token 阈值 `auto_compact_limit` | ❌ 固定轮次 20 |
| 压缩后保留 | 摘要 + 加密 latent blob | ❌ 无 |
| prefix cache | prompt 按稳定性排序 | ❌ 顺序随机 |

这是 FengYu 与 Codex **最大的功能性差距**：长对话会突然「失忆」且无摘要兜底。

**建议（按投入产出排序）**
1. **[P0] 给 `ChatSession` 引入 token 估算 + 触发式摘要压缩。**
   - 新增 `ConversationCompactor`：当估算 token 超过阈值（如上下文窗口的 60%），调用一次 `ChatBackend.chatWithoutTools(...)` 让模型把「最早的 N 轮」总结成一条 `ASSISTANT` 摘要消息，替换原始消息。
   - 保留：系统消息、最近 K 轮原文、摘要消息。**这一步就能把 FengYu 从「轮次截断」升级到「摘要压缩」，追上 Codex 的核心能力。**
   - 复用现有 `chatWithoutTools()`（`ChatBackend` 已有此默认方法，`SpringAiCloudBackend` 已实现），改动面小。
2. **[P1] prompt 按稳定性排序以吃 prefix cache。** 在 `SpringAiCloudBackend#buildSpringAiMessages` 中固定顺序：SystemMessage（含沙箱/权限声明、AGENTS.md、skills 目录）→ 历史摘要 → 最近对话。系统部分尽量稳定（不要把易变的东西塞进系统 prompt 前缀）。
3. **[P2] 工具结果回流时的体积治理**（见 §2.5）。Codex 对长输出有截断/折叠策略，FengYu 的 `CommandExecuteTool` 已有 `MAX_OUTPUT_CHARS`（256KB），但 BrowserTool 的 snapshot/text 应同样做「结构化折叠」而非纯字符截断。
4. **[P2] latent-style compaction**：若后续接入支持 `reasoning_encryption` 的 provider（OpenAI Responses API），可探索 `type=compaction` 的加密 blob，实现「无明文存储的推理保留」。这是 Codex 的差异化能力，可作为 roadmap 远期项。

---

### 2.3 浏览器自动化（Browser Automation）

**Codex 做法**
- 浏览器操作作为内置工具暴露给模型（`web_search` 等），通过 domSnapshot 风格的「带稳定 ref 的可交互元素」描述页面，模型用 ref 而非脆弱的 CSS 选择器去 click/type。
- 工具结果受审批策略与沙箱统一约束。

**FengYu 现状（已读源码确认）**
- `ai/tools/BrowserTool.java`：**已经参照 Codex 的 domSnapshot 模型设计**——`browser_snapshot`/`browser_find` 返回稳定 `[ref]`，`browser_click`/`browser_type`/`browser_press` 声明「ref wins over selector」，docstring 直接写明「like Codex domSnapshot」。12 个工具，经 loopback HTTP bridge 委托给 Electron shell（CDP）。
- 经 `ApprovalRequiredTool` 门控，`@ConditionalOnProperty("fengyu.desktop")` 仅桌面态注册。
- `TEXT_CAP=64000`、`SAMPLE_LIMIT=5` 在宿主侧裁剪。

**差距 / 建议**
- ✅ **设计已经对齐 Codex，方向非常正确**，无需大改。
- 💡 **[P1] 让 browser 工具在「纯 Web 模式」也可用。** 当前仅 `fengyu.desktop=true` 注册，意味着浏览器跑后端 JAR（无 Electron）时 AI 没有 browser 工具。可增加一个「宿主内嵌的无头浏览器后端」（如宿主进程内起一个 CDP/Playwright for Java 的可控 Chromium），让 `BrowserTool` 在 Web 模式也能工作。这正是 AGENTS.md 所说「Browser automation is a host-embedded backend capability」的完整形态——目前只完成了一半（依赖 Electron）。
- 💡 **[P2] snapshot 的结构化压缩。** Codex 的 domSnapshot 对大页面有层级折叠。FengYu 目前 `browser_get_text` 是纯字符截断（`capText`），可改为「按可交互元素分块 + 视口优先」，减少 token 占用、提升模型命中率。
- 💡 **[P2] 把 `browser_eval_js` 的危险度显式标为 `EXTERNAL`。** 当前它是 `ApprovalRequiredTool` 一员，但 eval_js 可执行任意页面脚本，应在 `ToolEffect` 上区别对待（见 §2.7）。

---

### 2.4 插件系统（Plugin System）

**Codex 做法**
- Codex 本身没有 FengYu 这种「带 UI iframe + 独立市场」的插件系统；它的扩展面是 **MCP servers**（见 §2.8）+ 配置驱动的工具。可认为 Codex 走的是「**MCP-first，无独立插件容器**」路线。

**FengYu 现状（已读源码确认）**
- `plugin/runtime/PluginProcessManager.java`：每个插件 = 一个**独立进程 Worker**，经**换行分隔的 JSON-RPC 2.0 over stdio** 通信，崩溃绝不可能拖垮宿主。已经实现：
  - 单 worker 单线程化派发 + **流水线并发**（写锁只护 stdin，常驻 reader 虚拟线程按 JSON-RPC `id` 多路分解）。
  - **有界帧**（stdout 16MiB / stderr 1MiB 超限即杀进程，防 OOM）。
  - **超时即杀 + 懒重启**；**协作式取消**（`$/cancelRequest` 优雅窗口 → 线程中断兜底）。
  - **环境变量正向白名单**（`applyEnvironmentAllowlist`，排除 `OPENAI_API_KEY`/`GH_TOKEN` 等所有宿主秘密）。
  - **完整性校验**（安装时记录 manifest + 整包 digest，启动前重验，防篡改提权）。
  - **包目录只读**（`P0-2`，防 Worker 改自己的 manifest 提权）。
- `plugin/store/`：已有 `CodexMarketplaceAdapter` / `ClaudeMarketplaceAdapter`——**FengYu 已经在消费 Codex/Claude 的市场源**，这点很有前瞻性。

**差距 / 建议**
- ✅ **FengYu 的插件隔离在多项指标上强于 Codex**（Codex 没有等价的独立插件容器）。这块 FengYu 是领先方，应作为产品差异化亮点。
- 💡 **[P1] 插件 → AI 工具的统一注册。** 当前 `ChatBackend` 的 javadoc 提到「插件想暴露工具就声明一个 `implements FengYuTool` 的 Spring `@Component`」——但这是**进程内**注册，与「Worker 是独立进程」的事实矛盾（Worker 无法向宿主 Spring 上下文注入 bean）。建议显式定义一个「**插件 manifest 声明 tools → 宿主运行时把它们注册成 `ToolCallback`，调用时经 `PluginProcessManager.invoke` 转发**」的桥接层。这样插件就能既进程隔离、又向 AI 暴露工具，与 MCP 形成统一抽象（见 §2.8）。
- 💡 **[P2] network.email / database 的诚实隔离。** `PluginProcessManager.start()` 注释已承认：这两个权限当前等同完全出网（无 SMTP/IMAP/DB 代理）。建议引入「宿主代理broker」做细粒度网络白名单，消除这段「顾问式权限」。

---

### 2.5 Agent 调用 / 循环（Agent Loop & Tool Result Flow）

**Codex 做法**
- Agent 循环：构造 prompt → 调 Responses API（SSE）→ 模型若输出工具调用则执行、把结果 append 到 input、再问模型；**直到模型输出非工具的 assistant 消息，一个 Turn 结束**。
- 内置 `update_plan` 工具：**模型在循环内、边做边更新待办清单**（动态、增量）。
- `apply_patch`：基于上下文行的补丁格式（`*** Begin Patch` / `*** End Patch`，`+`/`-`/`!` 行，`*** Update File:` / `*** Add File:` / `*** Delete File:` 头），单次原子改多文件，失败有结构化诊断。

**FengYu 现状（已读源码确认）**
- **两条路径**：
  1. **普通聊天**：`SpringAiCloudBackend#runToolLoop` 用 Spring AI 的 `ToolCallingManager`（user-controlled），流式聚合 → 有工具调用就 `executeToolCalls` → 重新流式；`maxToolRounds` 上限兜底；`cancelled` 标志每轮顶部检查。
  2. **Plan-and-Execute**：`ai/agent/AgentRunner.java` 先规划出 `AgentPlan`（DAG），按依赖层级在虚拟线程上并发执行，失败可重规划（`maxReplans`），每步/计划可选审批门。
- 工具结果回流：`mirrorToolResultsToHistory` 把 Spring AI 的 `ToolResponseMessage` 镜像回 FengYu history，保持 UI 一致。
- 没有模型可调用的 `update_plan`/todo 工具（Plan 是规划阶段一次性生成的，非循环内增量更新）；也没有 `apply_patch` 工具。

**差距 / 建议**
- ✅ FengYu 的双路径（chat loop + plan-execute DAG）其实**比 Codex 单一循环更丰富**，DAG 并发执行是亮点。
- 💡 **[P1] 引入模型驱动的 `update_plan` / todo 工具（chat 路径）。** 在长任务里，模型边做边勾选待办，对用户可见、对模型自身也是「外部记忆」，能显著降低跑偏。实现：新增一个 `@Tool` 维护一个内存中的待办列表，UI 经现有 SSE 事件渲染进度。这与现有的 Plan-and-Execute 不冲突——一个用于「自由聊天里的轻量任务管理」，一个用于「显式编排」。
- 💡 **[P1] 工具结果的上下文治理。** Codex 在循环里对工具输出做体积控制。FengYu 的 `runToolLoop` 把每轮的 `ToolResponseMessage` 全量塞进 `conversation`，长输出会快速吃满窗口而又**没有 compaction**（见 §2.2）——两者叠加问题更严重。配合 §2.2 的 compaction，建议对超长工具结果做「首尾保留 + 中间折叠」。
- 💡 **[P2] 评估引入 `apply_patch` 式的批量文件编辑工具。** 如果 FengYu 的 AI 要做代码/文档编辑，`apply_patch` 的「单次多文件原子补丁 + 结构化失败」远优于「多次单文件写」。可作为新的 `@Tool`（读现有 `@Tool` bean 模式即可）。若 FengYu 暂不涉及代码编辑，可跳过。

---

### 2.6 命令执行与沙箱（Command Execution & Sandbox）

**Codex 做法**
- 沙箱模式三档：`read-only` / `workspace-write` / `danger-full-access`。
- **macOS**：`sandbox-exec`（seatbelt），生成 **`(deny default)` 严格 allowlist** profile（只允许工作区写、按需开网）。因为 Codex 是**原生 Rust 二进制**，能在 deny-default 下启动。
- **Linux**：**Landlock + seccomp**（进程内内核级隔离），非外部 wrapper。
- approval 与 sandbox 是**两层正交**机制：sandbox 是 OS 强制边界，approval 是人在回路门控。

**FengYu 现状（已读源码确认）**
- `security/ProcessSandbox.java`：三后端——**Linux=bubblewrap**（外部 wrapper）、**macOS=sandbox-exec**、**Windows=Job Object**。
- **诚实度做得很好**：
  - 只有 `BUBBLEWRAP` 算 `providesSecurityIsolation()`（真·最小只读视图，**排除整个 user home**，只 bind `/usr /bin /lib /etc` + JDK + 插件包）。
  - macOS `sandbox-exec` 明确标为 `reducedIsolation()`——**`(allow default)` + deny 敏感目录**（`.ssh/.aws/.kube`、runtime 的 config/database/logs/skills），因为 **JVM 无法在 deny-default 下启动**（`~/Library` 缓存/偏好读写需求）。代码注释把这个权衡讲得非常清楚。
  - Windows Job Object 仅生命周期隔离，**非**安全边界，`isNativeSandboxAvailable()` 对 Windows 诚实返回 false。
- `ai/tools/CommandExecuteTool.java`：`/bin/sh -lc`（或 `cmd.exe`）执行；敏感环境变量按名 denylist 清除；有界超时（默认 30s/上限 600s）+ 有界输出（64KB 默认/256KB 上限）；进程树清理（Job Object tree-kill + descendants destroyForcibly）。
- approval 由 `ChatToolApprovalGate` + `AiPermissionContext` 驱动，与沙箱解耦。

**差距 / 建议**
- ✅ **FengYu 的沙箱在「诚实度」上反而优于一个「假装全平台都安全」的实现**。与 Codex 思路一致（OS 边界 + 审批门控两层），无需推翻。
- 💡 **[P1] Linux：评估从 bubblewrap 迁移/补充 Landlock。** Codex 用 Landlock+seccomp 是**进程内、无外部依赖、CI 容器友好**（bwrap 在很多托管/容器环境需要特权，常装不上或被禁）。FengYu 当前 `detect()` 依赖 `bwrap` 在 PATH，若缺失直接退化为 `NONE`。可引入基于 JNA 的 Landlock 绑定作为 bwrap 不可用时的回退，显著扩大「有真沙箱」的部署面。这是对命令/插件隔离的最直接加强。
- 💡 **[P2] macOS：探索「deny-default + 精细 allow」能否用于非 JVM 场景。** FengYu 的 macOS 弱隔离根因是「JVM 启动需要 `~/Library`」。若未来某些 Worker 不是 JVM（如 Python/Node 原生 worker），可对它们用 deny-default profile，达到接近 Codex 的强度。当前 `PluginProcessManager` 固定 `java -jar backend/worker.jar`，所以短期内 macOS 维持 reduced isolation 是正确的。
- 💡 **[P2] 命令 denylist → 与 Codex 的 approval 语义对齐。** `ChatToolApprovalGate.commandPotentiallyUnsafe` 用正则匹配 `sudo/rm -r/git reset --hard/curl|sh` 等——这是**可用性优化**（无沙箱时全部需审批）。注释也承认「这只是 usability 优化，真正边界靠 OS 沙箱」。建议保持，并在 UI 明确告知用户「当前平台是否有 OS 沙箱」（`isNativeSandboxAvailable()` 已有此布尔，直接透出）。

---

### 2.7 审批策略（Approval Policy）

**Codex 做法**
- approval 四档：`untrusted` / `on-failure` / `on-request` / `never`。
- approval 可**中断一个 Turn 中段**：服务器暂停 → 发审批请求 → 等 allow/deny。
- 沙箱模式与 approval 策略**正交组合**，覆盖「全自动危险」到「每步都问」的光谱。

**FengYu 现状（已读源码确认）**
- `ai/tools/AiPermissionMode.java`：三档 `ask-for-approval` / `approve-for-me` / `full-access`（注释自称「Codex-aligned」）。
- `ChatToolApprovalGate`：基于 `ToolEffect`（`READ/WRITE/COMMAND/EXTERNAL`）决策——
  - `FULL_ACCESS`：全部放行；
  - `ASK_FOR_APPROVAL`：非 READ 都问；
  - 默认（≈ approve-for-me）：READ/WRITE 放行、`EXTERNAL` 必问、`COMMAND` 看 `commandPotentiallyUnsafe`。
- 审批门用 `CountDownLatch` 阻塞工具执行，5 分钟超时，支持 reject/cancel；Agent 路径另有 plan 级 + step 级审批。

**差距 / 建议**
- ✅ **语义已对齐 Codex**，三档 vs 四档只是粒度，无需照搬。
- 💡 **[P1] 把「on-failure」语义补成显式档位。** Codex 的 `on-failure`（先放行，失败再问）在「信任沙箱但想兜底」场景很有用。FengYu 的 `approve-for-me` 已接近，但触发点是「调用前预测风险」而非「失败后回看」。可考虑：当 OS 沙箱可用时，默认走「先放行 + 失败/可疑副作用再审批」的 on-failure 模式，降低打扰。
- 💡 **[P1] 统一 Agent 路径与 chat 路径的审批模型。** 当前 `AgentRunner.toolRequiresApproval` 与 `ChatToolApprovalGate.requiresApproval` 是**两套并行的决策逻辑**（一个看 `AuditedToolCallback.effect()`，一个看 `AuditedToolCallback.effect()`——逻辑相似但有细微分支差异）。建议抽一个 `ToolApprovalPolicy` 单一决策点，两条路径共用，避免漂移。

---

### 2.8 MCP / 工具扩展（MCP & Extensibility）

**Codex 做法**
- MCP 是 Codex 的**主扩展面**：在 `config.toml` 配 `mcp_servers`（支持 stdio / HTTP / SSE 传输），启动时拉起，MCP 工具转成模型工具列表，受同一 approval/sandbox 约束；支持组织级「trusted MCP」策略。

**FengYu 现状（已读源码确认）**
- `web/controller/McpController.java`：基于 **Spring AI MCP**（`io.modelcontextprotocol` + `SyncMcpToolCallbackProvider`）。
- 配置经 `spring.ai.mcp.client.*`，**启动时定 scope**（环境变量/外部配置文件，凭证不落库）。
- MCP 工具经 `SyncMcpToolCallbackProvider` **自动注册进 AI 工具目录**（与 `@Tool` bean 同构）。
- 但 `McpController` **只读**（`/api/mcp/status` 诊断），**无运行时增删 server、无 UI 管理**。

**差距 / 建议**
- 💡 **[P1] 运行时 MCP 管理（动态增删 + UI）。** 当前改 MCP server 必须改启动配置 + 重启。Codex 也是静态配置，但 FengYu 作为「带 UI 的桌面应用」用户期望更高。建议：扩展 `McpController` 为可写（增删/启停 server），结合 §2.4 的「插件→工具桥接」，让「MCP server」和「FengYu 插件」在 UI 上是**同一类「可安装、可启停、可授权」的可扩展单元**。
- 💡 **[P1] MCP 工具的 approval 标注。** Spring AI 注入的 MCP `ToolCallback` 默认不是 `AuditedToolCallback`，因此 `ChatToolApprovalGate.requiresApproval` 对它们**返回 false（直接放行，不审批）**——这是一个**安全空隙**：任意 MCP server 的副作用工具在非 FULL_ACCESS 下也会静默执行。建议：给 MCP 工具统一包一层 `AuditedToolCallback`（默认 `ToolEffect.EXTERNAL`，即默认需审批），与内置工具一致的门控。
- 💡 **[P2] MCP 传输覆盖。** 确认 Spring AI MCP 是否已支持 streamable HTTP（不只是 stdio/SSE）；若否，补齐，避免接入新 server 受限。

---

## 3. 优先级路线图

| 优先级 | 事项 | 预期收益 | 涉及模块 |
|---|---|---|---|
| **P0** | `ChatSession` 引入 token 估算 + 触发式**摘要压缩**（compaction） | 解决长对话「突然失忆」——与 Codex 最大差距 | `ai/session/`、`ai/service/` |
| **P1** | MCP 工具默认包 `AuditedToolCallback`（默认 EXTERNAL 需审批） | 关闭 MCP 副作用工具的审批空隙 | `ai/tools/`、`config/AiToolRegistry` |
| **P1** | 工具结果体积治理 + 配合 compaction | 控制上下文膨胀 | `ai/service/SpringAiCloudBackend` |
| **P1** | Linux Landlock 作为 bwrap 不可用时的回退沙箱 | 扩大「有真 OS 沙箱」的部署面 | `security/` |
| **P1** | 插件 → AI 工具的统一注册桥接（manifest 声明 tools） | 插件既隔离又能暴露工具 | `plugin/`、`ai/config/` |
| **P1** | 模型驱动的 `update_plan`/todo 工具（chat 路径） | 长任务外部记忆、降低跑偏 | `ai/tools/` |
| **P1** | 统一 Agent/chat 两条路径的审批决策 | 消除逻辑漂移 | `ai/agent/`、`ai/tools/` |
| **P1** | 运行时 MCP 管理（UI 增删启停） | 与插件统一的扩展体验 | `web/controller/`、`frontend/` |
| **P2** | Web 模式下的宿主内嵌无头浏览器后端 | browser 工具脱离 Electron 可用 | `ai/tools/BrowserTool` |
| **P2** | prompt 按 prefix-cache 友好顺序排序 | 降本、降延迟 | `ai/service/` |
| **P2** | network.email/database 的 broker 细粒度隔离 | 消除「顾问式权限」 | `plugin/runtime/` |
| **P2** | browser snapshot 结构化折叠；apply_patch 式批量编辑 | 降 token、提命中 | `ai/tools/` |
| **P2** | 「on-failure」显式审批档 | 减少打扰、信任沙箱 | `ai/tools/AiPermissionMode` |

---

## 4. FengYu 已经做得比 Codex 好的地方（不要丢）

落地时**不要**为了「对齐 Codex」而削弱以下既有优势：

1. **插件进程隔离 + 完整性校验 + 环境白名单**：Codex 无等价的独立插件容器。`PluginProcessManager` 的流水线并发、有界帧、协作取消、digest 校验、包目录只读，是产品级差异化。
2. **沙箱诚实度**：`ProcessSandbox` 明确区分「真隔离 / reduced / 仅生命周期 / 无」，不在 macOS/Windows 上谎报安全。这比「假装全平台安全」的实现更可信。
3. **Plan-and-Execute DAG**：`AgentRunner` 的依赖层级并发 + 重规划 + plan/step 审批，比 Codex 的单线循环更强，应保留并继续作为「显式编排」路径。
4. **多市场源适配**：`CodexMarketplaceAdapter` / `ClaudeMarketplaceAdapter` 已让 FengYu 能消费外部生态，前瞻性强。

---

## 5. 附录

### 5.1 关键源码锚点（FengYu，均已实际阅读）

- Agent 运行时：`FengYu/src/main/java/fan/summer/fengyu/ai/agent/AgentRunner.java`
- 工具循环：`FengYu/src/main/java/fan/summer/fengyu/ai/service/SpringAiCloudBackend.java`（`runToolLoop`）
- 后端契约：`FengYu/src/main/java/fan/summer/fengyu/ai/ChatBackend.java`
- 会话/截断：`FengYu/src/main/java/fan/summer/fengyu/ai/session/ChatSession.java`
- 命令执行：`FengYu/src/main/java/fan/summer/fengyu/ai/tools/CommandExecuteTool.java`
- 浏览器工具：`FengYu/src/main/java/fan/summer/fengyu/ai/tools/BrowserTool.java`
- 审批门控：`FengYu/src/main/java/fan/summer/fengyu/ai/tools/ChatToolApprovalGate.java`、`AiPermissionMode.java`
- OS 沙箱：`FengYu/src/main/java/fan/summer/fengyu/security/ProcessSandbox.java`
- 插件运行时：`FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginProcessManager.java`
- MCP：`FengYu/src/main/java/fan/summer/fengyu/web/controller/McpController.java`

### 5.2 Codex 参考

- 仓库：https://github.com/openai/codex（codex-rs/core 为 Agent 运行时；codex-rs/mcp-client 为 MCP）
- 架构分析：https://swequiz.com/articles/openai-codex-architecture（Agent 循环、compaction、App Server JSON-RPC、prefix-cache prompt 排序）
- 官方 App Server 介绍：https://openai.com/so-DJ/index/unlocking-the-codex-harness/
- 沙箱/审批文档：https://github.com/openai/codex/tree/main/docs（sandbox / config）

### 5.3 落地前的复核清单

- compaction 设计前，确认目标 provider 是否支持 `reasoning`/`store`（决定能否上 latent-style 加密压缩）。
- Landlock 绑定前，确认目标内核版本（≥5.13）与 JNA 引入对包体积的影响。
- MCP 运行时管理前，复核 Spring AI MCP 的 `McpSyncClient` 是否支持热增删（可能需切到 `McpClient` async API 或自管进程）。
