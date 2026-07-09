# AI 流程编排:插件系统适配 + Plan-and-Execute Agent 运行时

**日期:** 2026-07-09
**状态:** 设计中,待评审
**范围阶段:** Phase 1(本次)= 工具编排化 + Agent 运行时;Phase 2(后续)= 可视化画布
**关联:** 承接 `2026-07-06-phase1-ai-strangler-spring-ai`(LangChain4j→Spring AI 迁移已完成)

---

## 1. 背景与动机

### 1.1 现状:并行手搓层 + 已弃用的 ChatModel 用法

ZhiFlow 已经在 Phase 1 迁移到 Spring AI 2.0(用 `ChatModel.stream()` 驱动云端对话),但在它之上**又手搓了一整套并行的工具机制**:

| ZhiFlow 手搓层 | 文件 | Spring AI 2.0 原生等价物 |
|---|---|---|
| `AiTool` 接口(execute→String) | `ZhiFlow-Api/.../api/ai/AiTool.java:28` | `@Tool` / `ToolCallback` |
| `AiToolParam` + `ToolSchemaJson` | `api/ai/AiToolParam.java:19`, `ZhiFlow/.../adapter/ToolSchemaJson.java:24` | `JsonSchemaGenerator`(自动生成) |
| `AiServiceProvider`(静态注册表) | `api/ai/AiServiceProvider.java:28` | `ToolCallbackProvider` / `ToolCallbackResolver` |
| `ToolExecutor`(静态派发器) | `ZhiFlow/.../ai/ToolExecutor.java:32` | `ToolCallingManager`(2.0 新增) |
| `AiToolCallback`(适配器) | `ZhiFlow/.../adapter/AiToolCallback.java:27` | 不再需要 |
| `SpringAiCloudBackend.runToolLoop` | `ZhiFlow/.../ai/service/SpringAiCloudBackend.java:172` | `ChatClient` + `ToolCallingAdvisor` |

**致命问题:** Spring AI 官方文档明确警告 —— "*The way `ChatModel` implementations handle tool execution internally is **deprecated since 2.0.0** and will be removed in 3.0.0.*" ZhiFlow 当前的 `chatModel.stream(prompt)` + 手动 `runToolLoop`(最大 5 轮)正是**已弃用的模式**。

### 1.2 编排的根本障碍

当前 `AiTool.execute(Map) → AiToolResult(success, String output)` 把输出塞进一个**不透明的 JSON 字符串**。没有正式的 output schema,没有类型化端口,工具之间无法按契约组合。这让任何形式的编排(无论 Agent 还是画布)在数据层都无从落地。

### 1.3 v1 遗留死代码

`ZhiFlow-Api` 下存在**两套并行包**:`fan.summer.api.*`(v1,JavaFX 时代)和 `fan.summer.zhiflow.api.*`(v2,当前)。经审计,v1 的 **44 个类无任何活跃引用**(运行时、plugin-markdown、v2 包都不 import 它),是纯死代码。

### 1.4 目标(本次 Phase 1)

1. **工具编排化** —— 迁移到 Spring AI 原生工具抽象,声明结构化 input/output schema,消除弃用风险。
2. **Plan-and-Execute Agent 运行时** —— LLM 先输出完整计划(多步,每步用哪个工具 + 输入),再顺序执行;支持人工审批与计划编辑。
3. **清理 v1 遗留** —— 删除 `fan.summer.api.*` 死代码。
4. **为画布阶段铺垫** —— 每个工具有标准 schema,Phase 2 的画布可直接基于 schema 连线。

### 1.5 非目标(留给 Phase 2)

- 可视化节点画布(n8n/Dify 式拖拽 DAG)
- 条件分支节点 / 循环节点 / 人工审批节点(画布阶段)
- 多 Agent 编排器(Orchestrator + Specialists)
- 工具 marketplace 动态加载(`PluginRegistryService` javadoc 已为 `~/.zhiflow/plugin/<id>/` 预留位置,本次不实现)

---

## 2. 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│  Frontend (Vue 3)                                               │
│  AiAgent.vue (新) ─ 显示 Plan / Step 进度 / 审批按钮             │
│  AiChat.vue (现有, 继续工作)                                    │
└───────────────┬─────────────────────────────────────────────────┘
                │ SSE (/api/agent/stream) + REST (/api/agent/*)
┌───────────────▼─────────────────────────────────────────────────┐
│  ZhiFlow backend (Spring Boot 4.1)                              │
│                                                                  │
│  ┌─────────────────────────────────────────────┐                │
│  │ AgentController (新)                        │                │
│  │  POST /api/agent/run  → AgentRunRequest     │                │
│  │  GET  /api/agent/stream (SSE)               │                │
│  │  POST /api/agent/{runId}/approve            │                │
│  │  POST /api/agent/{runId}/cancel             │                │
│  └──────────────┬──────────────────────────────┘                │
│  ┌──────────────▼──────────────────────────────┐                │
│  │ AgentRunner (新, 核心运行时)                │                │
│  │  Plan-and-Execute:                          │                │
│  │   1. planning: LLM 生成 Plan(steps[])       │                │
│  │   2. approval gate (可配置)                 │                │
│  │   3. execution: 顺序执行 steps              │                │
│  │   4. re-planning: 失败/新信息后重新规划     │                │
│  └──────┬──────────────────────────┬───────────┘                │
│         │ 用 Spring AI 原生         │ 调用                       │
│  ┌──────▼─────────────┐   ┌────────▼────────────────┐           │
│  │ ChatClient (新)    │   │ ToolCallingManager      │           │
│  │ + ToolCallingAdvisor│   │ (2.0 原生, 取代         │           │
│  │ 取代手搓 runToolLoop│   │  ToolExecutor.execute)  │           │
│  └────────┬───────────┘   └─────────┬──────────────┘           │
│           │                         │ resolve by name            │
│  ┌────────▼─────────────────────────▼──────────────┐           │
│  │ ToolCallbackResolver (新, Spring bean)          │           │
│  │  从 PluginRegistryService 收集所有插件工具 +     │           │
│  │  内置工具, 适配为 ToolCallback[]                 │           │
│  └────────┬────────────────────────────────────────┘           │
│  ┌────────▼────────────────────────────────────────┐           │
│  │ AiTool (契约, 保留, 增强为可编排)               │           │
│  │  + getOutputSchema() (新, default null)         │           │
│  │  + isComposable() (新, default true)            │           │
│  │  execute 返回类型不变 (AiToolResult String)     │           │
│  └─────────────────────────────────────────────────┘           │
│                                                                  │
│  ┌─────────────────────────────────────────────────┐           │
│  │ PluginRegistryService (改, 去 AiServiceProvider)│           │
│  └─────────────────────────────────────────────────┘           │
└──────────────────────────────────────────────────────────────────┘
                │ ZhiFlowPluginV2 (契约, 不变)
┌───────────────▼─────────────────────────────────────────────────┐
│  插件 (plugin-markdown 等, 零改动)                              │
└──────────────────────────────────────────────────────────────────┘
```

**核心原则:** **插件作者面对的契约(`AiTool`)保留为稳定 SPI;但运行时内部全部改用 Spring AI 原生机制。** 这是方案 A(渐进式适配),避免破坏官方插件仓。

---

## 3. 详细设计

### 3.1 工具契约增强(`AiTool` 可编排化)

**目标:** 让工具**声明**结构化 input/output schema(供 LLM 决策与编排可见),同时**向后兼容**现有插件。说明:结构化体现在 **schema 声明**,而非改变 `execute()` 的返回类型(见 3.1.3,AiToolResult 不变)。

#### 3.1.1 `AiTool` 接口增强

文件:`ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/ai/AiTool.java`

```java
public interface AiTool {
    // ── 现有(不变)──
    String getName();
    String getDescription();
    List<AiToolParam> getParameters();          // = inputSchema(已有,Spring AI 适配器自动转 JSON Schema)
    AiToolResult execute(Map<String, Object> arguments);

    // ── 默认方法(已有,不变)──
    default String getLocalDescription()      { return getDescription(); }
    default List<AiToolParam> getLocalParameters() { return getParameters(); }
    default boolean supportsLocal()           { return true; }
    default boolean supportsCloud()           { return true; }

    // ── 新增:结构化输出 schema(可编排的核心)──
    /**
     * 声明此工具产出的数据结构(JSON Schema 字符串)。
     * 默认返回 {@code null} = 无声明(等同现状:opaque string),
     * 仅供 LLM 参考,运行时不强制校验。
     * Agent/画布据此判断工具输出是否适合喂给下游某步。
     */
    default String getOutputSchema() { return null; }

    /**
     * 是否适合被编排(出现在 Plan 的 step 里)。
     * 无副作用、确定性的工具返回 true;
     * 有副作用或需人工交互的工具(如浏览器自动化)返回 false,
     * 只能由聊天工具循环直接调用。
     * 默认 true。
     */
    default boolean isComposable() { return true; }
}
```

**关键决策:**
- **`getOutputSchema()` 默认 `null`** → 现有 4 个内置 tool + `MarkdownPlugin` 无需任何改动即编译通过。只有想被编排的工具才需实现它。
- **不强制编译期类型检查**。schema 是"声明"而非"契约校验"——贴合 Spring AI 的做法(schema 喂给 LLM 辅助决策,运行时不做强校验)。这是你选择的"参考 Spring AI 2.0 原生做法"。
- **`isComposable()`** 区分"可进 Plan 的工具"与"只能聊天的工具"。

#### 3.1.2 输出传递:Spring AI 原生方式

你明确选择"参考 Spring AI 2.0 原生做法"。因此 **不引入手搓端口/字段映射层**。输出传递由 Spring AI 的 `ToolCallingManager` + 消息历史自然完成:

- 工具结果作为 `ToolResponseMessage` 进入对话历史(`ChatClient` / `ToolCallingManager` 自动处理)。
- LLM 在下一步看到完整历史(含上一步工具产出的 JSON),自行决定如何使用。
- 类型保证通过 `inputSchema`(Spring AI 自动从参数生成) + `outputSchema`(声明)在 LLM 决策层提供,而非运行时强校验。

这与 ZhiFlow 现有 tool loop 的传递方式一致(都靠 LLM 解析历史),但**升级到了不弃用的 `ToolCallingManager` 路径**,且多了 schema 声明辅助。

#### 3.1.3 `AiToolResult` 保持不变

`AiToolResult(success, String output)` 不变。`output` 仍是 JSON 字符串(序列化的结构化对象)。理由:
- 向后兼容所有现有工具。
- Spring AI 的 `ToolCallback.call()` 本就返回 `String`。
- 结构化体现在 `getOutputSchema()` 的**声明**,而非运行时对象类型。

> **备选(不在本次):** 若 Phase 2 画布需要更强类型化,再引入 `Port` 抽象。本次保持最小化。

---

### 3.2 Spring AI 原生工具桥接(替代手搓层)

**目标:** 用 Spring AI 2.0 原生抽象替代 `ToolExecutor` / `ToolSchemaJson` / `AiServiceProvider` 的派发职责,消除弃用风险。`AiTool` 作为插件契约保留。

#### 3.2.1 `ToolCallbackResolver` 实现(新)

文件:`ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tool/PluginToolCallbackResolver.java`

```java
@Component
public class PluginToolCallbackResolver implements ToolCallbackResolver {

    private final Map<String, ToolCallback> callbacks;  // name → callback

    public PluginToolCallbackResolver(PluginRegistryService plugins,
                                      List<AiTool> builtinTools) {
        // 收集:插件 aiTools() + 内置 tool bean
        Map<String, ToolCallback> map = new HashMap<>();
        for (AiTool t : allTools) {
            map.put(t.getName(), new AiToolCallback(t));  // 复用现有适配器(增强版)
        }
        this.callbacks = Map.copyOf(map);
    }

    @Override public ToolCallback resolve(String name) { return callbacks.get(name); }
    public Collection<ToolCallback> all() { return callbacks.values(); }
}
```

**作用:** 替代静态 `AiServiceProvider.getTool(name)`。Spring bean,可注入。`AiToolCallback`(现有)略作增强:用 Spring AI 的 `JsonSchemaGenerator` 替代手搓 `ToolSchemaJson` 构建 inputSchema(见 3.2.3)。

#### 3.2.2 `ToolCallingManager` 接入(替代 `ToolExecutor`)

文件:`ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tool/AgentToolCallingManager.java`(或直接用 Spring AI 的 `DefaultToolCallingManager`)

- 用 Spring AI 2.0 原生 [`DefaultToolCallingManager`](https://docs.spring.io/spring-ai/reference/api/tools.html#tool-calling-manager) 处理工具执行生命周期(`executeToolCalls`)。
- 这是 Spring AI 推荐的"user-controlled tool execution"路径,取代 ZhiFlow 手搓的 `ToolExecutor.executeAndFeed` 顺序循环。
- `AgentRunner`(3.3)和现有聊天循环(3.4)都通过它执行工具。

#### 3.2.3 适配器增强(`AiToolCallback`)

文件:`ZhiFlow/.../adapter/AiToolCallback.java`(现有,增强)

- `getToolDefinition()`:用 `JsonSchemaGenerator` 从 `AiToolParam` 生成 inputSchema(替代 `ToolSchemaJson.build`)。如可行,让 `outputSchema` 也走 `ToolDefinition`(Spring AI 2.0 的 `DefaultToolDefinition` 支持 outputSchema 字段)。
- `call()`:逻辑不变(解析 JSON args → `aiTool.execute(args)` → 返回 output string)。

#### 3.2.4 `AiServiceProvider` 的归宿

静态单例 `AiServiceProvider` 的**派发职责移除**,但其**模式过滤**(`supportsLocal`/`supportsCloud`/`constrainedTool`)和**后端切换**(`switchMode`)逻辑仍有用。处理:
- **工具派发/查询** → 迁移到 `PluginToolCallbackResolver`(Spring bean)。
- **模式过滤 / 后端状态** → 保留 `AiServiceProvider` 但瘦身为一个 `@Component`(去 static),或拆出独立的 `AiModeService`。本次倾向拆为 `AiModeService`(职责单一),`AiServiceProvider` 标记 `@Deprecated`。

> **决策点(写实现计划时定):** `AiServiceProvider` 改 bean 还是拆分。倾向拆分(单一职责),但需评估 `SlashCommandHandler` 等调用点。

---

### 3.3 Plan-and-Execute Agent 运行时(核心)

**目标:** 新建 `AgentRunner`,实现"先规划后执行"。这是本次最大的新模块。

#### 3.3.1 数据模型

```java
// ── 计划模型(运行时, 先不持久化)──
public record AgentPlan(String goal, List<AgentStep> steps, String reasoning) {}
public record AgentStep(int index, String toolName, Map<String,Object> args,
                        String description, boolean requiresApproval) {}

// ── 执行状态(发往 SSE)──
public enum StepStatus { PENDING, RUNNING, AWAITING_APPROVAL, COMPLETED, FAILED, SKIPPED }
public record StepExecution(int index, StepStatus status, AiToolResult result) {}

// ── Agent 运行句柄(对应一次 run)──
public class AgentRun {
    String runId;
    AgentPlan plan;
    List<StepExecution> executions;
    AgentRunStatus status;  // PLANNING, AWAITING_PLAN_APPROVAL, EXECUTING, COMPLETED, FAILED, CANCELLED
    // ... + 同步原语(CountDownLatch for approval gate)
}
```

#### 3.3.2 `AgentRunner` 核心流程

文件:`ZhiFlow/src/main/java/fan/summer/zhiflow/ai/agent/AgentRunner.java`

```
run(goal, config):
  1. PLANNING
     - 构造 planning prompt(含目标 + 可用工具的 name/desc/inputSchema/outputSchema)
     - 用 ChatClient.stream() 流式生成 Plan
     - SSE 事件: onPlanToken / onPlanReady(plan)
  2. AWAITING_PLAN_APPROVAL  (仅当 config.requirePlanApproval == true)
     - SSE: onPlanApprovalRequested
     - 等待 POST /api/agent/{runId}/approve(可带编辑后的 plan)
     - 或超时/取消 → CANCELLED
  3. EXECUTING
     - 顺序执行 steps:
       for each step:
         if step.requiresApproval && config.requireStepApproval:
            AWAITING_STEP_APPROVAL → 等待 approve
         SSE: onStepStart(index)
         result = toolCallingManager.execute(step.toolName, step.args)
         SSE: onStepComplete(index, result)
         if result.failed && config.replanOnFailure:
            → 回到 PLANNING(带"第N步失败,重新规划"的上下文)
  4. COMPLETED / FAILED
     - SSE: onComplete(finalSummary) / onError
```

**关键设计点:**
- **复用 `ToolCallingManager`** 执行每个 step(不另起一套执行器)。
- **Re-planning 容错:** 失败后允许 LLM 重新规划(带失败上下文),上限 3 次,防死循环。
- **审批 gate 可配置:** `requirePlanApproval`(整计划审批)/`requireStepApproval`(每步审批)默认都 `false`(自动跑),用户可开。
- **流式:** planning 阶段 `ChatClient.stream()`,SSE 推 `onPlanToken`,前端实时显示 LLM 在"想计划"。
- **取消:** `AgentRun` 持有取消标志,执行循环每步前检查;`POST /cancel` 设置标志。

#### 3.3.3 与现有聊天循环的关系

`AgentRunner` **不替代**现有聊天 `ChatBackend.chat()`。两者并存:
- **聊天模式**(现有):ReAct 式即时工具调用,用户和 LLM 多轮对话。保留 `SpringAiCloudBackend` 但内部改用 `ChatClient` + `ToolCallingAdvisor`(去掉弃用的 `runToolLoop`)。
- **Agent 模式**(新):Plan-and-Execute,独立入口 `/api/agent/*`,独立前端视图 `AiAgent.vue`。

> Phase 2 画布出现后,画布执行器也会复用 `ToolCallingManager`,成为第三种"驱动方式"。

---

### 3.4 现有聊天循环的去弃用化

**目标:** 把 `SpringAiCloudBackend.runToolLoop`(已弃用模式)改为 Spring AI 推荐的 `ChatClient` + `ToolCallingAdvisor`。

文件:`ZhiFlow/.../ai/service/SpringAiCloudBackend.java`

- 用 `ChatClient.builder(chatModel).defaultAdvisors(ToolCallingAdvisor.builder().build()).build()` 替代裸 `chatModel.stream(prompt)` + 手动 loop。
- 工具通过 `ChatClient.prompt().tools(toolCallbacks)` 注入(来自 `PluginToolCallbackResolver`)。
- 保留 `AiStreamCallback` 的 `onToken`/`onToolCall`/`onToolResult` 事件(适配 `ChatClient` 的响应式流)。
- `ToolSchemaJson` 标记 `@Deprecated`(由 `JsonSchemaGenerator` 取代),本次不删(避免回归),实现计划里列为清理项。

> **本地后端 `OllamaLocalBackend`:** 同样去弃用化(它也走 `ChatModel`)。但本地模型 tool-calling 能力弱(见 `2026-06-22-qwen3-4b-toolcall-design.md`),Agent 模式默认只对云端后端开放。

---

### 3.5 API 与前端

#### 3.5.1 REST / SSE 端点

文件:`ZhiFlow/.../web/controller/AgentController.java`(新)

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/agent/run` | body: `{goal, config}`,返回 `{runId}` |
| GET | `/api/agent/stream?runId=` | SSE:plan token / plan ready / step 事件 / complete / error |
| POST | `/api/agent/{runId}/approve` | body: `{plan?}`(可带编辑后的 plan);用于 plan & step 审批 |
| POST | `/api/agent/{runId}/cancel` | 取消运行 |
| GET | `/api/agent/tools` | 列出可编排工具(name/desc/inputSchema/outputSchema/composable),供前端展示与 Phase 2 画布 |

SSE 事件类型:`plan_token` / `plan_ready` / `plan_approval_requested` / `step_start` / `step_complete` / `step_approval_requested` / `complete` / `error`。

#### 3.5.2 前端 `AiAgent.vue`(新,最小)

- 目标输入框 + "规划"按钮 + 配置(审批开关)。
- Plan 展示区:步骤列表(可编辑,若开启审批)。
- 执行进度区:每步状态(PENDING/RUNNING/DONE/FAILED)+ 审批按钮。
- 复用现有 `aiSession` store 的 SSE 消费模式。

> 前端不是本次重点,做最小可用版。Phase 2 画布才是前端大头。

---

### 3.6 v1 遗留清理

**删除范围(已审计,全部无活跃引用):**

- `ZhiFlow-Api/src/main/java/fan/summer/api/` —— 整个 v1 包(44 个类)。
- `ZhiFlow-Api/src/test/java/fan/summer/api/` —— 对应的 v1 测试。

**验证清单(删除前后都要确认):**
- [ ] `grep -r "fan.summer.api\." ZhiFlow/src plugin-markdown/src` → 无结果(已确认)
- [ ] v2 包 `fan.summer.zhiflow.api` 不 import v1(已确认)
- [ ] 删除后 `mvn -q compile` 全模块通过
- [ ] `.idea/workspace.xml` 中 `PropertiesPluginSettingsTest` 运行配置会失效(无害,可顺手删该条目)

**不删除:** `backup/` 目录(历史快照,非活跃源码,不在构建路径)。

---

## 4. 数据流示例

**场景:** 用户输入"把这个 JSON 格式化后算个 hash"。

```
1. POST /api/agent/run {goal: "格式化JSON后算hash"}
   → AgentRunner 进入 PLANNING
2. ChatClient.stream(planning prompt + 工具列表[json_format, hash_calculate])
   → SSE plan_token: "我需要两步..."
3. SSE plan_ready: {steps: [
     {tool: "json_format", args: {mode:"pretty"}, desc:"美化JSON"},
     {tool: "hash_calculate", args: {algo:"sha256", input:"{{step0.output}}"}, desc:"计算hash"}
   ]}
   (注:input 里的 {{step0.output}} 是给 LLM 的提示约定;实际传递靠消息历史)
4. (若开审批) SSE plan_approval_requested → 用户点"批准"
5. EXECUTING:
   step 0: toolCallingManager.execute("json_format", {mode:"pretty"}) → result
           SSE step_start(0) / step_complete(0, result)
           历史追加 ToolResponseMessage(result)
   step 1: ChatClient 读历史,知道上一步输出 → 用它调 hash_calculate
           SSE step_start(1) / step_complete(1, result)
6. SSE complete: {summary: "已格式化并计算,sha256=..."}
```

---

## 5. 向后兼容性

| 组件 | 影响 | 处理 |
|---|---|---|
| 现有插件(`MarkdownPlugin` 等) | 无 | `AiTool` 契约只新增 default 方法;`ZhiFlowPluginV2` 不变 |
| 4 个内置 tool(`json_format` 等) | 无 | 不实现 `getOutputSchema()` 即可继续工作(但不被 Agent 编排,除非补 schema) |
| `AiChat.vue` 聊天界面 | 编译无影响,运行时去弃用化 | `SpringAiCloudBackend` 内部改 `ChatClient`,对外 `AiStreamCallback` 不变 |
| `SlashCommandHandler` | 调用点从 `AiServiceProvider.getTool` 改为 `PluginToolCallbackResolver.resolve` | 适配,行为不变 |
| 配置 / DB | 无 | Agent run 本次不持久化(在内存);Phase 2 再加 JPA 实体 |

---

## 6. 测试策略

- **单元测试:**
  - `AiToolCallback` schema 生成(inputSchema 从 AiToolParam,outputSchema 透传)。
  - `PluginToolCallbackResolver` 收集 + resolve。
  - `AgentRunner` 的状态机(PLANNING → APPROVAL → EXECUTING → COMPLETE),用 mock `ToolCallingManager` 和 mock `ChatClient`。
  - Re-planning 容错(失败 3 次后 FAILED)。
- **集成测试:**
  - 端到端:mock 一个 echo tool + `goal="echo hello"` → 验证 plan + execution + SSE 事件序列。
  - 审批 gate:block 在 AWAITING_PLAN_APPROVAL,模拟 approve → 继续。
- **回归测试:**
  - 现有 `AiTool` 实现不经任何改动仍通过(编译 + 既有测试)。
  - 删除 v1 后全模块 `mvn test` 通过。

---

## 7. 风险与未决项

| 风险 | 缓解 |
|---|---|
| `AiServiceProvider` 去 static 可能波及多处调用点 | 写实现计划时先 grep 全部 `AiServiceProvider.` 调用,逐一适配 |
| Spring AI `ChatClient` 流式 + 工具循环与现有 `AiStreamCallback` 适配有坑 | 先做 spike:用一个简单 tool 跑通 `ChatClient.stream()` + `ToolCallingAdvisor` + 事件回调 |
| Plan-and-Execute 对弱模型(本地 Ollama)规划质量差 | Agent 模式默认仅云端;本地仍走聊天循环 |
| `outputSchema` 在 `ToolDefinition` 里的支持程度待验证 | spike 阶段确认 Spring AI 2.0 `DefaultToolDefinition` 是否暴露 outputSchema;不支持则 schema 仅在 `/api/agent/tools` 自定义返回 |
| re-planning 死循环 | 硬上限 3 次 + step 间取消检查 |

**未决项(留给实现计划):**
1. `AiServiceProvider` 是改 `@Component` 还是拆 `AiModeService`。
2. Agent run 是否本期就持久化(倾向否,Phase 2 随画布一起)。
3. `{{step0.output}}` 这类占位约定是否要形式化(倾向否,纯靠 LLM 读历史)。

---

## 8. 分阶段交付建议(供实现计划参考)

1. **清理 v1** —— 删 `fan.summer.api.*` + 测试,验证编译(低风险,先做)。
2. **工具桥接** —— `PluginToolCallbackResolver` + `AiToolCallback` 增强用 `JsonSchemaGenerator`;`AiTool` 加 default 方法。
3. **去弃用化聊天循环** —— `SpringAiCloudBackend` 改 `ChatClient` + `ToolCallingAdvisor`,spike 验证流式 + 工具事件。
4. **Agent 运行时** —— `AgentRunner` + 数据模型 + `AgentController` + SSE。
5. **前端 `AiAgent.vue`** —— 最小可用。
6. **测试 + 回归**。

每步可独立提交、独立验证。

---

## 参考

- [Spring AI Tool Calling Reference](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring AI Composable Tool Architecture (2.0)](https://spring.io/blog/2026/06/15/spring-ai-composable-tool-calling)
- 现有代码:`ZhiFlow/.../ai/service/SpringAiCloudBackend.java:172`(弃用的 runToolLoop)、`ZhiFlow/.../ai/ToolExecutor.java`、`ZhiFlow-Api/.../api/ai/AiTool.java:28`
- 前序迁移:`docs/superpowers/specs/2026-07-06-phase1-ai-strangler-spring-ai`(LangChain4j→Spring AI)
