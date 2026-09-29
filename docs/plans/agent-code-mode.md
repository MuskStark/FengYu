# 编码代理 code mode 专项（差距清单 ⑧）——设计文档与实施任务书

更新日期：2026-09-29（立项版 1；同日修订 1：`ToolLoopDriver`/`RolloutEvent` 架构批次已落地——
轮次编排与 rollout 记录自此单点化，§2 现状与 §4.4 接线描述已同步）

适用项目：Infinia / FengYu 4.1+，Spring Boot 后端（`FengYu` 模块）+ React 前端

编写时源码版本：`release/4.1.0` @ `8d3f2624` + 未提交的 codex 对齐批次（apply_patch/PTY 会话/
并行工具/子代理/review/rollout 等）。codex 参照实现在 `/tmp/openai-codex`（openai/codex @
`c248f6d`，`codex-rs/code-mode{,-host,-protocol,-runtime}` 四 crate 共约 3.1 万行 Rust，半数为测试）；
该检出在 /tmp，**可能被清理——重启工作时若已丢失，
`git clone https://github.com/openai/codex && git checkout c248f6d` 恢复**。

状态：独立专项（与 [[agent-os-sandbox]] 平行、无硬依赖；二者在"嵌套工具调用同走审批/沙箱"处交汇）。
本文是待执行任务书，不是已完成记录；实施时必须重新读取当时的真实源码。

## 1. 给实施 AI 的执行指令

只有用户明确要求启动本专项时才开始修改应用代码；生成本文的请求仅授权创建方案文档。
启动时按"阶段计划"从 S1 顺序执行，遵守：

1. 先读根 `AGENTS.md` 与 `git status`，保留用户已有修改。
2. 本文的 codex 文件索引是规格说明书：移植前打开对应源码与其测试逐条核对，**不得凭印象自创**
   （用户硬性要求；ZCode→React 与 codex 对齐两个批次的历史返工皆因臆造被驳回）。
3. 类名/包名建议不高于实际源码；命名可局部自定，不得静默删减必做验收项与安全不变量（§5）。
4. 新依赖必须坐维护线（GraalJS 选型论证见 §4.1，引入前复核版本与 JDK21 兼容）。
5. 不自动提交/推送/打标签；本专项预计无新 REST 控制器（若最终新增，必须登记
   `SetupApplication` 排除清单）。
6. 文档同步用 `docs-updater`；每阶段最小验证=相关测试类 + 针对性手工验证。

## 2. 背景与差距定位

2026-09-29 的 codex 对比结论（记忆 `fengyu-codex-gap-analysis`）第 ⑧ 号差距：code mode =
**模型写 JavaScript 来编排工具调用**，而不是一轮一个工具调用。模型发出单个自由文本 `exec`
工具调用，内含 JS 源码；JS 在无 IO 的沙箱引擎里跑，把常规工具当异步函数调用
（`await tools.workspace_exec(...)`），循环/分支/扇出确定性地展开，期间可 yield 部分输出，
最终结果作为一条工具输出回到会话。它不是子代理图——"被编排的就是模型自己的工具"，
code mode 是工具循环之上的一层编排 harness。

FengYu 现状（已核实）：

- 工具循环：`ToolLoopDriver`（轮次编排单点——`SpringAiCloudBackend`/`OllamaLocalBackend`
  已缩为只供模型差异的 Transport：标签、ChatModel/基选项、reasoning 片段语义、云端独有的
  多媒体降级。prompt 组装、动态工具加载、审批门、批执行调用、rollout 记录、压缩、取消
  都在这一处）+ `ToolBatchExecutor`（同轮 READ 并发/WRITE 屏障，结果按原序重组）+
  `ChatToolApprovalGate`（审批批处理，拒绝合成结果不终止轮次）+ `ToolGuardService` +
  `AiToolRegistry`（按 `WorkspaceContext.isBound()` 等过滤工具面）。
- 工具定义：Spring AI `@Tool` 注解反射生成 JSON schema（`ToolCallbacks.from`）。
- 并行粒度 = 模型一轮的多个 tool_calls；**跨轮编排只能靠模型多轮往返**——这正是 code mode 消除的
  往返（每轮都重发上下文、重建前缀缓存）。
- `agent/AgentRunner` 是宿主侧预编排（plan→steps），与 code mode（模型即席编排）正交，不冲突、不复用。
- 已有可复用资产：JSON-RPC over stdio 外部进程模式（插件 worker 体系）= 未来外置 host 的天然载体；
  SSE 流（`AiStreamCallback`）= yield 事件的通道；`AiRolloutService`/`RolloutEvent` = 类型化事件
  审计（sealed 事件模型 + 单一写路径；旧日志照常回放，未知事件类型回落 `Unknown` 跳过——
  本专项新增的 cell 事件/嵌套标记天然获得旧版本兼容）；`CodeModeCell` 的命令循环设计
  （S2）与 `ToolLoopDriver` 的"专属虚拟线程 + 命令/轮次驱动"是同一形状，一在工具调用层、
  一在会话层；`ApplyPatchTool`/`ReviewTool` 等已给出"自由文本/复杂参数工具"的先例。
- 无任何 JS 引擎依赖；JDK 基线 21（root pom `maven.compiler.release=21`）。

## 3. codex 参照实现索引（规格来源）

> 路径相对 `/tmp/openai-codex/codex-rs/`。四 crate 依赖方向：
> `code-mode-protocol` ← `code-mode-runtime`（嵌 V8）← `code-mode-host`（独立进程）；
> `code-mode`（客户端，不链 V8）← `core`（代理本体）。行数含测试约半。

### 3.1 code-mode-protocol（协议与提示面，S1 参照）

- `src/lib.rs` — `PUBLIC_TOOL_NAME="exec"`、`WAIT_TOOL_NAME="wait"`。
- `src/runtime.rs` — `ExecuteRequest{tool_call_id, enabled_tools[], source, yield_time_ms?,
  max_output_tokens?}`；`RuntimeResponse = Yielded{cell_id, content_items} | Terminated | Result{…,
  error_text?}`（每个 wire 响应必带 host 计时）；默认值 `DEFAULT_EXEC_YIELD_TIME_MS=10_000`、
  `DEFAULT_MAX_OUTPUT_TOKENS_PER_EXEC_CALL=10_000`。
- `src/session.rs` — 传输中立接口三件套：`CodeModeSession`（execute→StartedCell/wait/terminate/
  shutdown）、`CodeModeSessionDelegate`（invoke_tool/notify/cell_closed）、
  `CodeModeSessionProvider`（availability/create_session[_with_limits]）；
  `CodeModeSessionCellExecutionLimits{max_yield_time_ms, max_heap_size_bytes}`。
- `src/description.rs`（1043 行）— **模型面提示规格**：`EXEC_DESCRIPTION_TEMPLATE`（关键契约原文：
  "在全新 V8 isolate 中作为异步模块求值…纯 JS——无 Node、无文件系统、无网络、无 console…求值完毕
  isolate 生命周期结束，未 await 的 promise 被静默丢弃"）；首行 pragma
  `// @exec: {"yield_time_ms":…, "max_output_tokens":…}`（仅此二键）；嵌套工具以 TS 声明注入描述
  （`declare const tools: { name(args: T): Promise<R>; };`，按命名空间分组，MCP 走
  `CallToolResult<T>`）；`{{ default_exec_yield_time_ms }}`/`{{ image_helper }}` 占位。
- `src/json_schema_types.rs` — JSON Schema → TypeScript 渲染器（`DEFAULT_INPUT_SCHEMA_MAX_BYTES`）。
- `src/host/message.rs`/`codec.rs` — stdio wire：JSON 消息 + 4 字节 LE 长度前缀，帧上限 64 MiB，
  camelCase，`deny_unknown_fields`；`ClientToHost{connection/hello, operation/request|cancel|yield,
  delegate/response}` ↔ `HostToClient{connection/ready|rejected, operation/response,
  execute/initialResponse, delegate/request, delegate/cancel, cell/closed}`；能力协商
  （required ∪ optional ∩ 已实现，`session-cell-execution-resource-limits`、`yield-observation`）；
  `MAX_PENDING_DELEGATE_CALLS=1024`。
- `src/grpc/codex.code_mode.v1.proto` — 远端 host 的 gRPC 形态（V1 移植不需要，仅备 S5 参考）。
- 测试：`src/host/host_tests.rs`（847 行，握手/能力/消息语义）、`description_override_tests.rs`、
  `json_schema_types_tests.rs`。

### 3.2 code-mode-runtime（JS 引擎核心，S2 参照）

- `src/runtime/mod.rs` — **每个 exec 调用 = 一个 cell = 一条专属 OS 线程 + 全新 isolate**
  （`spawn_runtime`，线程 catch_unwind）；命令循环 `RuntimeCommand{ToolResponse/ToolError{id},
  TimeoutFired, ObservePendingFrontier, Terminate}`——宿主与 isolate 单向命令驱动，镜像此模式。
- `src/runtime/globals.rs` — 从 globalThis **删除** `console/Atomics/SharedArrayBuffer/WebAssembly`；
  安装的全体全局（§5 不变量清单）；测试 `global_scope_contains_only_allowed_items`
  （`service_tests.rs:993-1107`）钉死允许集。
- `src/runtime/callbacks.rs` — `tools.<name>(args)`：参数 v8→JSON，分配 `tool-N` id + PromiseResolver，
  发 `RuntimeEvent::ToolCall`；**宿主事后 resolve**（成功→resolve，宿主错→reject）。
- `src/runtime/module_loader.rs` — 源码按 ES module `exec_main.mjs` 编译执行（顶层 await 可用）；
  **所有静态/动态 import 一律抛 "Unsupported import in exec"**；`exit()` 抛哨兵字符串
  `"__codex_code_mode_exit__"` 按**成功完成**处理。
- `src/runtime/value.rs` — `text(v)`（非字符串 JSON 化）；`image(url, detail?)` **仅接受 base64
  `data:` URI**，远程 http(s) 显式拒绝；`audio` 同构（WAV 时长计入 token 预算）。
- `src/session_runtime/` — 会话域 `store(key,value)/load(key)`：cell 完成时原子提交，同会话后续
  cell 可见、跨会话隔离。
- `src/cell_actor/` — 观察两模式：`YieldAfter(duration)`（墙钟，≥10s+1s 宽限）与
  `PendingFrontier`（静默+待决工具 id 集）；cell 状态机 Running→Terminating/Completed/
  CompletionClaimed/Tombstone，终态单一 lienarization point。
- 中断：`Terminate` 命令 + `isolate_handle.terminate_execution()` 双管齐下（杀 `while(true){}` CPU
  循环，测试 `runtime/mod.rs:414-448`）；`max_heap_size_bytes` 上 wire 但进程内会话目前不强制
  （诚实差距，照抄其行为边界）。
- `src/service.rs` — `InProcessCodeModeSession`（进程内实现，进程内场景把 heap limit 置空、
  clamp yield 时限）。
- 测试规格：`src/service_tests.rs`（1756 行——**行为规格圣经**：exit 语义、store 会话域、shutdown
  杀 CPU 循环、pending-frontier 语义、globals 白名单、console 缺席、ICU 可用、每个 helper 的
  接受/拒绝矩阵）、`service_contract_tests.rs`、`cell_actor/tests.rs`。

### 3.3 code-mode-host / code-mode（进程隔离与客户端，S5 参照）

- `code-mode-host/src/lib.rs` — 准入限额：`MAX_IN_FLIGHT_REQUESTS=256`、`MAX_ACTIVE_CELLS=128`、
  请求/会话 id 去重记忆 4096、`SHUTDOWN_TIMEOUT=5s`；V8 线程 panic → 整连接 fail-closed。
  `execute` 先回 `execution/started{cell_id}` 再异步推 `execute/initialResponse`。
- `code-mode/src/remote_session/` — 宿主进程 spawn（kill_on_drop、独立进程组、env 清洗、
  30s 启动握手超时；传输 deadline=yield+1s+60s）；连接死亡自动重开新会话；V1 可不移植，仅当
  选择外置 host 形态时照此规格。

### 3.4 core 集成（S3/S4 参照，`core/src/tools/code_mode/`）

- `execute_spec.rs` — `exec` 是 **Freeform 工具**（原始文本，非 JSON args；lark 文法仅容许
  pragma 首行）。
- `execute_handler.rs` — 收集+去重 registry 中嵌套工具定义（按 runtime 缓存）、组
  `ExecuteRequest`、只 await **首响应**（yield 后 cell 继续后台跑）。
- `wait_spec.rs`/`wait_handler.rs` — `wait(cell_id, yield_time_ms?, max_tokens?, terminate?)`。
- `delegate.rs` — 嵌套工具调用**重新进入常规工具运行时**（`ToolCallSource::CodeMode`）；
  `exec` 不能调自己（`mod.rs:368-370`）；`notify` 以 `CustomToolCallOutput` 注入当前轮（带截断）。
- `mod.rs` — 输出模板："Script running with cell ID X"/"Script completed"/"Script failed"/
  "Script terminated" + `Script error:\n…` + 10k token 截断；`interrupt_active_cells`（轮次打断
  时终止活动 cell）。
- `core/src/tools/mod.rs:75-97` — `ToolMode::{CodeModeOnly, CodeMode, Direct}`：CodeModeOnly 只暴露
  exec+wait（嵌套工具文档嵌进 exec 描述）；CodeMode 两者并存；不可用时 CodeMode 降级 Direct。

## 4. 设计方案

新包 `fan.summer.fengyu.ai.codemode`。

### 4.1 JS 引擎选型（决策 D1）

**GraalJS**（`org.graalvm.polyglot:org.graalvm.js` + `polyglot`，Maven Central，Oracle 维护线活跃，
ECMAScript 2024 兼容）。理由：纯 JAR 依赖、无原生库分发问题（与 shaded fat JAR 兼容）、
JDK 21 即可用（非 GraalVM JDK 上以解释模式运行——工具编排场景无 JIT 性能足够）；
Context 权限模型（`HostAccess.NONE`、禁 IO/线程/native）天然实现 codex 的能力模型——
**引擎里没有的 API 就是不存在**，等价于 V8 isolate 删 globalThis。
备选已排除：Javet（V8 JNI，需按平台分发原生库，与桌面打包/交叉发布冲突）、quickjs 绑定
（社区绑定维护弱，违反 EOL 规则）。**S1 验证项**（写代码前跑通 spike）：
(a) module 模式求值 + 顶层 await；非 GraalVM JDK 上 `import` 动态语法在无模块加载器时的报错可
捕获；(b) Context 单线程进入约束下的跨线程 promise resolve 方案（见 4.2）；
(c) `context.interrupt`/`close(true)` 杀 CPU 循环的时延；(d) 大输出/深结构的 JSON 序列化边界。

### 4.2 运行时核心（对应 3.2）

- `CodeModeCell`：**每 exec 一 cell**，专属虚拟线程持有一个全新 polyglot `Context`
  （共享 `Engine` 跨 cell 复用编译缓存——对应 codex 共进程多 isolate）。线程跑命令循环：
  `BlockingQueue<CellCommand>`，命令=`ToolResponse/ToolError(id)`、`TimeoutFired`、`Terminate`——
  **照抄 codex runtime 命令循环结构**。工具 promise 桥：JS 侧 `tools.<name>(args)` 经
  `ProxyObject` 成员函数 → 序列化参数 → 生成 `tool-N` id + 宿主侧 `CompletableFuture` →
  事件发往执行管道；完成时把 `ToolResponse` 命令入队，**cell 线程内** resolve JS Promise
  （polyglot Value 只能在进入线程触碰——cell 线程命令循环正是为此存在，勿改成宿主线程直接
  resolve）。
- globals（照 3.2 清单）：`tools`、`ALL_TOOLS`、`text`、`image`（仅 data: URI，远程拒绝）、
  `audio`（V1 可延后，先拒绝并文档说明）、`store`/`load`（会话域，cell 完成原子提交）、
  `notify`、`setTimeout`/`clearTimeout`（未决定时器不延长 cell 寿命）、`yield_control()`、
  `exit()`（哨兵=成功）。源码以 module 求值；import 全拒（哨兵错误消息照抄）
  ；globalThis 不注入 console/WebAssembly 等（GraalJS 默认 JS 语义面即近此目标，S2 用
  「允许集枚举」测试钉死，发现多余能力就地移除/禁用）。
- 观察语义：V1 实现 `YieldAfter`（墙钟 yield + 首观察=initialResponse 语义）；
  `PendingFrontier` 模式 V1 可简化为「yield 时限到→返回已产出内容+待决工具数」，S2 单测钉住
  实际选定语义并在本文档变更记录里写明取舍。
- 中断：`Terminate`（命令循环退出 + `context.close(true)` 强制中断）双保险；会话 shutdown 与
  轮次打断（对应 `interrupt_active_cells`）都走到这。
- `InProcessCodeModeSession` 对应物：`GraalJsCodeModeSession implements CodeModeSession`。

### 4.3 协议与客户端接口（对应 3.1）

`ai/codemode/protocol/`：Java record 集——`ExecuteRequest/WaitRequest/WaitOutcome/RuntimeResponse/
CodeModeNestedToolCall/ToolDefinition{name, toolName, kind(Function|Freeform), inputSchema}/
ContentItem(text|image{dataUri,detail})`；接口 `CodeModeSession`/`CodeModeSessionDelegate`/
`CodeModeSessionProvider`（**为 S5 外置 host 预留的缝**：V1 只有 in-process 实现，接口签名
按传输中立设计，勿把 GraalJS 类型漏进接口）。提示面：`ExecToolDescription` 模板类——
`EXEC_DESCRIPTION_TEMPLATE` 逐句翻译为中文/英文双份（i18n 资源，英文为准、中文对照——模型提示
以英文为准，与 FengYu 现有 SystemPrompts 惯例对齐），嵌套工具 TS 声明由
`JsonSchemaToTs` 渲染器生成（镜像 `json_schema_types.rs`：字段类型映射、联合、可选、
嵌套对象/数组；输入 schema 超 16KB 截断策略照抄）。`@exec:` pragma 解析器（仅二键、安全整数、
必须后跟源码）。

### 4.4 core 集成（对应 3.4）

- 新工具 `exec`（`CodeModeExecTool`）+ `wait`（`CodeModeWaitTool`），注册进 `AiToolRegistry`，
  仅当 `WorkspaceContext.isBound()`（与编码工具同面）**且** `ai.code-mode.enabled=true`（默认
  false）。`exec` 效果=COMMAND（走 COMMAND 审批档）；嵌套调用各自再过门（见下）。
- **嵌套工具调用管道**：`tools.<name>` 的 CompletableFuture 提交到与正常工具调用完全相同的
  执行面——`ChatToolApprovalGate` + `ToolGuardService` + `WorkspaceContext`/权限模式线程上下文
  （虚拟线程继承已有机制）+ OS 沙箱（若 [[agent-os-sandbox]] 已启用）。模型轮的该执行面自
  4.1.0 架构批次起由 `ToolLoopDriver` 单点驱动；嵌套调用对齐同一语义即可（审批门 +
  守卫 + `ToolBatchExecutor` 的效果分组），无需（也不应）各自在 backend 里重新接线。
  **安全铁律：code mode 不是审批旁路**——嵌套的 `workspace_exec` 仍按各自 ToolEffect 审批/沙箱。
  `exec` 自身不可被嵌套调用（registry 过滤，照 `mod.rs:368-370`）；`delegate_task` 等长任务
  可被编排（这正是价值）。嵌套并发：同一 cell 的多个未决工具调用并发执行——V1 沿用
  `ToolBatchExecutor` 的效果分组语义（READ 并发/WRITE 屏障）套用于同 cell 批次，防两个
  "并行读"里混一个写。已知后续项（V1 不做）：cell 先返回首响应后在后台跨轮存活，其嵌套
  写与后续轮次写的交错需要会话级效果协调器（`ToolBatchExecutor` 语义从轮内升格到会话内），
  属独立架构步骤，S2/S3 设计时不得提前引入。
- 输出处理（照 3.4 模板）：结果前缀 "Script running with cell ID X"/completed/failed/terminated，
  失败附 `Script error:`，10k token 截断（复用 `ToolResultContextLimiter` 语义）。
- 流事件：yield → `AiStreamCallback` 增量（前端 transcript 的 exec 卡片滚动显示已产出内容+
  cell 状态徽标 running/completed/failed/terminated）；`wait` 轮询补充后续产出。
- rollout：扩展 `RolloutEvent` 类型体系（sealed 事件模型是唯一写路径）——exec 调用照常落
  `MessageAppended`（源码全文）；嵌套调用的 `ToolResultRecorded` 增 `nestedIn=cellId` 字段
  （记录调用点在 `ToolLoopDriver` 的轮次编排里）。旧版本读新事件由 `RolloutEvent.Unknown`
  回落保证兼容，无需日志迁移。
- `ToolMode` 概念 V1 **不引入**（FengYu 无模型目录 pin 需求；exec 做并列工具即可）。
  记为未来可选：按模型配置切 CodeModeOnly。

### 4.5 S5（可选）外置 host

复用插件 worker 的 JSON-RPC over stdio 基建起 sidecar（GraalJS 或 Node 均可），
帧协议照 3.1 `host/message.rs`（4 字节 LE 长度+JSON、64MiB、camelCase、未知字段拒收），
握手/能力协商/准入限额（256/128/4096/5s）照 3.3。V1 明确不做，本文只留接口缝。

## 5. 安全不变量（验收必查，逐条镜像 codex）

1. 每 exec 全新 Context，无跨 cell 状态残留（store 除外——会话域、cell 完成原子提交、跨会话隔离）。
2. import 一律拒绝；无 console/fs/network/process/timer 之外的宿主能力；globalThis 允许集以
   枚举测试钉死。
3. 效果只能经 `tools` 对象发生，且每个嵌套调用过审批门+守卫+沙箱（继承当前会话权限模式）。
4. `exec` 不可嵌套调用自身；输出恒截断；媒体仅 base64 `data:` URI（远程 URL 显式拒绝）。
5. `exit()` 哨兵=成功；未决 promise 静默丢弃。
6. CPU 循环可被 Terminate+强中断杀死（有测试）；运行时异常 fail 该 cell 而非宿主
   （cell 线程异常兜底 catch，进程不崩）。
7. `notify` 注入的中间输出同样过截断预算。
8. 未决 setTimeout 不延长 cell 寿命（cell 终止即取消）。

## 6. 阶段计划与验收

**S1 协议+提示面（纯函数，无引擎依赖）**
交付：`protocol/` record 集、三个接口、`JsonSchemaToTs` 渲染器、`@exec:` pragma 解析器、
`ExecToolDescription` 模板（含嵌套工具声明拼装）。
验收：单测镜像 `description_override_tests.rs`/`json_schema_types_tests.rs` 语义（schema→TS 快照、
pragma 合法/非法矩阵、描述占位替换）；全部纯 JVM 无新依赖。

**S2 GraalJS 运行时核心**
交付：`CodeModeCell`（线程+命令循环）、`GraalJsCodeModeSession`、globals 全套、promise 桥、
store/notify/timers、Terminate+强中断；GraalJS 依赖引入（含 EOL 复核记录）。
验收：单测镜像 `service_tests.rs` 核心子集——§5 全部不变量逐条成测（globals 枚举、exit 成功语义、
import 拒绝、`while(true)` 可杀、store 跨 cell 同会话可见/跨会话隔离、image data:-only 矩阵）；
S1 spike 四验证项结论写入本文变更记录。

**S3 嵌套调用管道**
交付：`tools` 对象从 registry 动态构建（去重、排除 exec 自身）、CompletableFuture→审批门/守卫/
工作区上下文透传、同 cell 并发的效果分组调度。
验收：嵌套 `workspace_exec` 写命令仍触发审批卡（端到端测试，可用 fake 模型或直接调
session.execute）；嵌套调用结果按原序进 JS；审批拒绝→JS 侧 promise reject 且脚本可 catch 继续。

**S4 工具注册+流+rollout+配置+前端**
交付：`exec`/`wait` 注册（含描述构建）、输出模板+截断、yield 流事件、rollout 事件、
`ai.code-mode.enabled`（默认 false）、前端 exec 卡片（代码块+cell 徽标+yield 增量+wait 续读）。
验收：配置关闭时工具面与现状完全一致（回归）；开启后端到端——模型一次 exec 内完成
"读文件→改→跑测试→按结果分支"的多工具编排，转录可见 cell 状态流；轮次打断终止活动 cell。

**S5（可选）外置 host sidecar**：照 4.5；验收=stdio 帧回环测试（握手、限额、fail-closed）。

最终验收（专项收官）：默认关闭零行为变化；开启后模型可用 JS 编排既有工具完成多步工作，
全程安全不变量（§5）有测试钉住，审批/沙箱零旁路；文档经 `docs-updater` 同步
（README/文档"编排"节 + CHANGELOG）。

## 7. 风险与开放问题

- GraalJS 在非 GraalVM JDK 上解释执行的性能与顶 await 语义（S1 spike 消解，失败则备选
  降级为"scriptEngine=graaljs|node-sidecar"双实现，S5 提前）。
- 嵌套调用的审批体验：一次 exec 编排 N 个写命令= N 张审批卡；V1 接受（诚实安全），
  S6 候选=会话级"本 cell 预授权清单"（另评审，不得默认静默放行）。
- 模型提示适配：codex 模板面向其工具名；FengYu 版必须改写嵌套工具清单与示例（`workspace_exec`
  等），S1 验收含"模板中无 codex 专属名词残留"检查。
- 内存上限：codex 进程内会话同样未强制 heap limit（wire 字段存在）——V1 对齐此行为并在配置里
  留 `max-heap-mb` 死键（GraalJS 资源限额在其 JDK21 支持面上的可用性列入 spike）。
