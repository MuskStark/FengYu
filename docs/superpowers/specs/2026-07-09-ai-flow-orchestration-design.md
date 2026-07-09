# AI 流程编排:插件系统重构 + Plan-and-Execute Agent 运行时

**日期:** 2026-07-09
**状态:** 设计中,待评审
**性质:** **4.0.0 破坏性更新,不兼容 3.x 及以前的任何代码与插件**
**范围阶段:** Phase 1(本次)= 插件系统重构 + 工具编排化 + Agent 运行时;Phase 2(后续)= 可视化画布
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

当前 `AiTool.execute(Map) → AiToolResult(success, String output)` 把输出塞进一个**不透明的 JSON 字符串**。没有正式的 output schema,没有类型化声明,工具之间无法按契约组合。这让任何形式的编排(无论 Agent 还是画布)在数据层都无从落地。

### 1.3 死代码

`ZhiFlow-Api` 下存在**两套并行包**:`fan.summer.api.*`(v1,JavaFX 时代)和 `fan.summer.zhiflow.api.*`(v2,当前)。经审计,v1 的 **44 个类无任何活跃引用**(运行时、plugin-markdown、v2 包都不 import 它),是纯死代码。

### 1.4 决策(用户拍板)

本次 **4.0.0 为破坏性更新**,为去除技术债,**不兼容 3.x 及以前**。五条硬约束:

1. **破坏性更新** —— 不为旧代码/旧插件保留兼容层。手搓工具层(`AiTool` 全家桶)全部删除。
2. **插件声明 AI 支持** —— 插件接口声明是否支持 AI 调用;支持则**按 Spring AI 2.0 标准实现**(直接实现 `ToolCallback`,不经适配层)。
3. **所有插件均独立运行 + 自带界面** —— UI 由主程序在指定位置渲染(沿用现有 ESM 微前端机制,但成为强制要求)。
4. **插件支持分类** —— 分类用于在主项目分组到指定目录下;固定枚举 + 后端驱动前端侧边栏(前端不再硬编码)。
5. **插件声明来源** —— 官方或第三方(`PluginSource` 枚举);分类名与来源标签**均需 i18n**(前端引入 vue-i18n)。

### 1.5 目标(本次 Phase 1)

1. **插件契约重构** —— 新 `ZhiFlowPlugin` 契约:UI 插件(后台+UI)与 AI 工具(可选,Spring AI 标准 bean)分离。
2. **工具编排化** —— 删除手搓层,AI 工具直接用 Spring AI 原生 `ToolCallback`;声明结构化 output schema。
3. **Plan-and-Execute Agent 运行时** —— LLM 先规划后执行,支持审批与失败重规划。
4. **去弃用化** —— `SpringAiCloudBackend.runToolLoop` 改 `ChatClient` + `ToolCallingAdvisor`。
5. **删除死代码** —— `fan.summer.api.*`(v1)+ `AiTool` 全家桶(手搓层)。
6. **分类与来源** —— `ToolCategory` 增 `AI` 分类 + 后端驱动侧边栏;新增 `PluginSource`(官方/第三方)。
7. **前端 i18n** —— 引入 vue-i18n,本地化分类名/来源标签/所有现有硬编码字符串;`language` 设置终于生效。

### 1.6 非目标(留给 Phase 2)

- 可视化节点画布(n8n/Dify 式拖拽 DAG)
- 条件分支 / 循环 / 人工审批节点(画布阶段)
- 多 Agent 编排器(Orchestrator + Specialists)
- 工具 marketplace 动态加载

---

## 2. 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│  Frontend (Vue 3)                                               │
│  AiAgent.vue (新) ─ Plan / Step 进度 / 审批                      │
│  AiChat.vue (现有) ─ 继续工作                                    │
│  PluginView.vue ─ 渲染插件 ESM 微前端 (现有机制, 强制)            │
└───────────────┬─────────────────────────────────────────────────┘
                │ SSE + REST
┌───────────────▼─────────────────────────────────────────────────┐
│  ZhiFlow backend (Spring Boot 4.1)                              │
│                                                                  │
│  ┌───────────────────────────────────┐                          │
│  │ AgentController / AgentRunner (新)│  Plan-and-Execute         │
│  │   plan → approval → execute →     │  (复用 ToolCallingManager)│
│  │   re-plan(失败≤3次)               │                           │
│  └──────┬───────────────────────────┘                           │
│  ┌──────▼────────────┐   ┌─────────────────────┐                │
│  │ ChatClient (新)   │   │ ToolCallingManager  │                │
│  │ +ToolCallingAdvisor│   │ (Spring AI 2.0 原生)│                │
│  │ 取代 runToolLoop  │   │ 取代 ToolExecutor   │                │
│  └──────┬────────────┘   └─────────┬───────────┘                │
│         │ tools[]                   │ resolve by name             │
│  ┌──────▼──────────────────────────▼──────────────┐             │
│  │ Spring AI 原生工具 bean 机制                     │             │
│  │  - 所有 ToolCallback bean (含 @Tool 方法)        │             │
│  │    由 Spring AI 自动发现                         │             │
│  │  - ToolCallbackResolver / ToolCallbackProvider  │             │
│  └─────────────────────────────────────────────────┘             │
│                                                                  │
│  ┌─────────────────────────────────────────────────┐             │
│  │ PluginRegistryService (改)                       │             │
│  │  - 收集 ZhiFlowPlugin bean (UI 插件)             │             │
│  │  - /api/plugins, /api/plugins/{id}/invoke        │             │
│  │  - /plugin-ui/{id}/** 服务 ESM bundle            │             │
│  └─────────────────────────────────────────────────┘             │
└──────────────────────────────────────────────────────────────────┘
                │ ZhiFlowPlugin (新契约)  +  ToolCallback (Spring AI)
┌───────────────▼─────────────────────────────────────────────────┐
│  插件模块 (plugin-markdown 等)                                  │
│   ├── XxxPlugin implements ZhiFlowPlugin   (UI + 后台 invoke)    │
│   └── XxxTools (可选, @Component + @Tool)  (AI 工具, 原生标准)   │
│   └── ui-src/ → resources/ui/<id>/        (ESM 微前端, 强制)     │
└──────────────────────────────────────────────────────────────────┘

  删除: fan.summer.api.*(v1, 44类) + AiTool/AiToolParam/AiToolResult/
        ToolExecutor/ToolSchemaJson/AiToolCallback/AiServiceProvider(工具部分)
```

**核心原则:**
- **破坏性更新** —— 不保留任何兼容垫片。旧插件需按新契约重写。
- **UI 与 AI 分离** —— 插件 = UI 插件(必有) + AI 工具 bean(可选)。两者独立,Spring AI 部分纯粹标准。
- **Spring AI 原生** —— AI 工具直接实现 `ToolCallback`(或用 `@Tool` 注解),不经任何 ZhiFlow 自定义适配层。

---

## 3. 详细设计

### 3.1 插件契约重构

**目标:** 新 `ZhiFlowPlugin` 契约:UI 插件(后台逻辑 + UI 元数据)+ 可选 AI 工具(独立 Spring AI bean)。

#### 3.1.1 新 `ZhiFlowPlugin` 接口(UI 插件,必有)

文件:`ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/plugin/ZhiFlowPlugin.java`

> 注:重命名 `ZhiFlowPluginV2` → `ZhiFlowPlugin`(破坏性,去掉 V2 后缀,v1 同步删除)。废弃旧的 `ZhiFlowPlugin`(JavaFX createView)。

```java
package fan.summer.zhiflow.api.plugin;

/**
 * 4.0.0 插件契约(UI 插件)。每个插件 = 后台逻辑(invoke RPC)+ 自带 UI(ESM 微前端)。
 * 界面由主程序在指定位置渲染,基于 {@link PluginDescriptor#uiEntry()} 加载 ESM bundle。
 *
 * <p>AI 能力是可选的、独立的:若插件支持 AI 调用,在同一模块内提供
 * Spring AI 标准的 ToolCallback bean(见 3.2),不在此接口声明。
 * {@link PluginDescriptor#supportsAi()} 仅作元数据声明,供前端/Agent 发现。
 */
public interface ZhiFlowPlugin {

    /** 插件元数据:id、显示名、分类、UI 入口、是否支持 AI 等。 */
    PluginDescriptor descriptor();

    /**
     * 后台 JSON-RPC。UI 微前端通过 POST /api/plugins/{id}/invoke 调用。
     * @param action 插件自定义动作字符串(如 "render")
     * @param args   动作参数(JSON 反序列化的 map)
     * @return JSON 可序列化结果(controller 自动序列化)
     * @throws IllegalArgumentException 未知 action 或参数非法
     */
    Object invoke(String action, java.util.Map<String, Object> args);
}
```

**与 v2 差异:** 删除 `aiTools()`(手搓层,迁移到独立 Spring AI bean)。`descriptor()` + `invoke()` 保留(已是 v2 形态,稳定)。

#### 3.1.2 `PluginDescriptor` 增强(声明 AI 支持 + 来源)

文件:`ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/plugin/PluginDescriptor.java`

```java
public record PluginDescriptor(
    String id,            // 反向域名,全局唯一
    String name,
    String description,
    ToolCategory category,
    String icon,          // MDI 图标名
    IconStyle iconStyle,
    String version,
    String uiEntry,       // ESM bundle 入口路径,强制非空(所有插件必须有 UI)
    boolean supportsAi,   // 新增:是否支持 AI 调用(声明性,实际工具由 ToolCallback bean 提供)
    PluginSource source   // 新增:来源 OFFICIAL / THIRD_PARTY(见 3.1.5)
) {
    // 注:4.0.0 破坏性更新,不提供旧签名兼容构造。
    //    本仓内所有 descriptor() 调用点按新签名一次性改完。
}
```

**设计要点:**
- `uiEntry` **强制**:所有插件必须自带 UI(满足约束 3)。`PluginRegistryService` 在注册时校验 `uiEntry` 非空。
- `supportsAi` **纯声明**:告诉前端"这个插件有 AI 工具",前端据此在卡片上显示 AI 标识。实际 AI 能力由同模块的 `ToolCallback` bean 提供(见 3.2)。声明与实现的一致性由启动校验保证(见 3.1.3)。
- `source` **声明性**:插件作者在 descriptor 里标明自己是官方还是第三方。前端据此区分显示(徽标/分组/筛选)。`PluginRegistryService` 可对官方源做校验(如 id 前缀 `fan.summer.*`),但默认信任声明(见 3.1.5)。
- **i18n 由前端 vue-i18n 处理**(见 3.7):后端只发 `ToolCategory` 枚举名 + `PluginSource` 枚举名 + i18n key,前端用 `t(key)` 翻译。后端是"有哪些分类/来源"的真相源,前端是"怎么显示"的翻译者。
- **不提供旧构造**:4.0.0 破坏性更新,本仓所有 `descriptor()` 一次性迁移。

#### 3.1.3 注册与一致性校验

`PluginRegistryService`(改):
- 收集所有 `ZhiFlowPlugin` bean → 按 id 索引(现状不变)。
- **新增校验**(启动时):`descriptor.uiEntry()` 为空 → 拒绝注册并记错(约束 3:所有插件必须有 UI)。
- **新增 AI 一致性校验**:对每个 `supportsAi()==true` 的插件,检查是否存在归属同一模块/同 id 前缀的 `ToolCallback` bean;不一致则告警(不阻断,因为 bean 归属判定较松)。实现细节在计划阶段定。
- 删除 `@PostConstruct registerAiTools()`(手搓层,不再需要 —— Spring AI 自己发现 `ToolCallback` bean)。

#### 3.1.4 分类(`ToolCategory`)—— 后端驱动侧边栏

**现状问题:** 前端侧边栏的分类列表是**硬编码字面量**(`frontend/src/shell/Sidebar.vue:20-28`),与后端枚举手动同步,且不存在 `AI` 分类。`ToolCategory.getI18nKey()` 是死代码(从不调用)。

**改动:**

1. **`ToolCategory` 枚举新增 `AI` 分类**(文件 `ZhiFlow-Api/.../api/ToolCategory.java`):
```java
DEV("dev", "category.dev"),       // i18n key 改为统一前缀(见下)
TEXT("text", "category.text"),
IMAGE("image", "category.image"),
NET("net", "category.net"),
AI("ai", "category.ai"),          // 新增:AI 类插件(Agent 工具、提示工具等)
OTHER("other", "category.other");
```
   - **统一 i18n key 前缀**为 `category.*`(替代三套不一致的旧 key:`sidebar.label.*`/`detail.category.*`/`store.online.category.*`)。旧 key 是 JavaFX 时代遗留,本仓已无消费者(headless 后端不用、前端无 i18n),破坏性删除。
   - 删除死代码 `getI18nKey()` 的旧值,改用新 key —— 但实际调用还是在前端 vue-i18n。

2. **后端成为分类列表的真相源** —— 新增端点 `GET /api/plugin-categories`:
```json
[
  {"id": "dev",  "labelKey": "category.dev",  "icon": "⚙"},
  {"id": "text", "labelKey": "category.text", "icon": "¶"},
  ...
]
```
   - 返回**稳定 id + i18n key + 图标**,不含翻译文本(翻译在前端)。
   - 由一个 `PluginCategoryController`(或并入 `PluginController`)从 `ToolCategory` 枚举静态生成。`all`/`favorites` 是前端侧边栏特有项,**前端自加**(不从后端来)。

3. **前端侧边栏改为动态**:删除 `Sidebar.vue:20-28` 的硬编码 `categories`,改为启动时 `GET /api/plugin-categories` 拉取 + 追加 `all`/`favorites`,渲染时 `t(labelKey)` 翻译。这样新增分类只改后端枚举,前端自动跟上。

**"分入指定目录下"的含义:** 分类驱动侧边栏导航目录 —— 每个分类是侧边栏一个目录项,选中后 `ToolGrid` 过滤该分类插件。不是文件系统目录。

#### 3.1.5 来源(`PluginSource`)—— 官方/第三方

**新增枚举:** 文件 `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/plugin/PluginSource.java`
```java
public enum PluginSource {
    OFFICIAL("official", "source.official"),       // 官方插件(本仓 + 官方插件仓)
    THIRD_PARTY("third_party", "source.third_party"); // 第三方插件

    private final String id;
    private final String labelKey;  // vue-i18n key
    // getId() / getLabelKey()
}
```

**设计要点:**
- **声明性**:插件作者在 `PluginDescriptor.source` 标明。官方插件标 `OFFICIAL`,第三方标 `THIRD_PARTY`。
- **可选校验**:`PluginRegistryService` 可对 `OFFICIAL` 做宽松校验(如 id 以 `fan.summer.` 开头),不符则降级为 `THIRD_PARTY` 并告警。严格签名校验留给 Phase 6(marketplace),本期不做。
- **前端用途**:插件卡片显示来源徽标(`官方`/`第三方`),侧边栏或筛选器可按来源过滤。`GET /api/plugins` 已含 `source` 字段。
- **i18n**:前端 vue-i18n 翻译 `source.official`/`source.third_party`。对齐遗留字符串 `detail.tag.builtin`/`detail.tag.plugin`(破坏性重命名,旧 key 无消费者)。

---

### 3.2 AI 工具:Spring AI 2.0 原生实现

**目标:** 插件的 AI 能力直接用 Spring AI 标准,不经 ZhiFlow 适配层。这是约束 2 的核心。

#### 3.2.1 AI 工具的两种实现形态(插件作者任选)

**形态 A:`@Tool` 注解(推荐,最简)**

插件模块内一个 `@Component` 类,方法标 `@Tool`:

```java
@Component
public class MarkdownAiTools {

    @Tool(description = "将 Markdown 文本渲染为 HTML")
    public String renderMarkdown(String markdown) {
        // Spring AI 自动从方法签名生成 inputSchema;
        // 返回值类型自动成为 outputSchema(这里是 String)。
        Parser parser = Parser.builder().build();
        HtmlRenderer renderer = HtmlRenderer.builder().build();
        return renderer.render(parser.parse(markdown));
    }
}
```

Spring AI 2.0 自动发现 `@Tool` 方法 → 构造 `MethodToolCallback` → 注册。**零 ZhiFlow 框架代码。**

**形态 B:实现 `ToolCallback`(更细控制)**

```java
@Component
public class HashTool implements ToolCallback {
    @Override public ToolDefinition getToolDefinition() {
        return DefaultToolDefinition.builder()
            .name("hash_calculate")
            .description("计算文本的哈希值")
            .inputSchema(JsonSchemaGenerator...)   // Spring AI 生成
            .build();
    }
    @Override public String call(String toolInput, ToolContext ctx) {
        // 解析 JSON → 执行 → 返回 String
    }
}
```

#### 3.2.2 结构化 output schema

- **形态 A(`@Tool`)**:Spring AI 自动从方法返回类型推导 outputSchema(2.0 支持)。返回 record/POJO 即得结构化 schema。
- **形态 B(`ToolCallback`)**:在 `ToolDefinition` 显式声明(2.0 的 `DefaultToolDefinition` 支持 outputSchema)。
- 这是"参考 Spring AI 2.0 原生做法"的落地 —— 不自定义 schema 机制。

#### 3.2.3 工具发现与解析

- Spring AI 2.0 自动收集上下文里所有 `ToolCallback` bean + `@Tool` 方法(经 `MethodToolCallback` 包装)。
- 用 `ToolCallbackResolver` bean 做按名解析(供 `ToolCallingManager` / Agent / 聊天循环用)。
- ZhiFlow 提供一个 `@Configuration` 把可用工具汇总(若需按 mode/visibility 过滤,在此处加 advisor 或自定义 resolver)。
- **删除**:`AiServiceProvider` 的工具注册表部分(`registerTool`/`getTool`/`getTools` 等)。后端模式管理部分拆出独立 bean(见 3.5)。

#### 3.2.4 工具可见性(mode 过滤)的迁移

旧 `AiServiceProvider.getTools()` 按 `supportsLocal()`/`supportsCloud()` 过滤。迁移方案:
- **默认:不做 mode 过滤**(简化)。所有 `ToolCallback` bean 对所有后端可见。
- 若需保留(如本地小模型要隐藏部分工具):在 ChatClient 构造时按 mode 选择性传入 `tools(...)`,或用一个 `ToolCallbackProvider` 做过滤包装。**实现计划阶段评估是否本期需要** —— 倾向本期不做,减少复杂度。

---

### 3.3 Plan-and-Execute Agent 运行时(核心新模块)

**目标:** 新建 `AgentRunner`,实现"先规划后执行"。

#### 3.3.1 数据模型(运行时,本期不持久化)

```java
public record AgentPlan(String goal, List<AgentStep> steps, String reasoning) {}
public record AgentStep(int index, String toolName, Map<String,Object> args,
                        String description, boolean requiresApproval) {}

public enum StepStatus { PENDING, RUNNING, AWAITING_APPROVAL, COMPLETED, FAILED, SKIPPED }
public record StepExecution(int index, StepStatus status, String result) {}

public enum AgentRunStatus { PLANNING, AWAITING_PLAN_APPROVAL, EXECUTING,
                             AWAITING_STEP_APPROVAL, COMPLETED, FAILED, CANCELLED }
public class AgentRun {
    String runId; AgentPlan plan; List<StepExecution> executions;
    AgentRunStatus status;
    // + 同步原语(CountDownLatch for approval gate)+ 取消标志
}
```

#### 3.3.2 `AgentRunner` 核心流程

文件:`ZhiFlow/src/main/java/fan/summer/zhiflow/ai/agent/AgentRunner.java`

```
run(goal, config):
  1. PLANNING
     - 构造 planning prompt(目标 + 可用工具的 name/desc/inputSchema/outputSchema,
       由 ToolCallback.getToolDefinition() 提供)
     - ChatClient.stream() 流式生成 Plan
     - SSE: onPlanToken / onPlanReady(plan)
  2. AWAITING_PLAN_APPROVAL (仅当 config.requirePlanApproval)
     - SSE: onPlanApprovalRequested
     - 等待 POST /api/agent/{runId}/approve(可带编辑后的 plan)
     - 超时/取消 → CANCELLED
  3. EXECUTING — 顺序执行 steps:
     for each step:
       if step.requiresApproval && config.requireStepApproval:
          AWAITING_STEP_APPROVAL → 等 approve
       SSE: onStepStart(index)
       result = toolCallingManager.executeToolCalls(step)   // Spring AI 原生
       SSE: onStepComplete(index, result)
       // 工具结果作为 ToolResponseMessage 自动进对话历史,
       // LLM 下一步读历史决定如何用(Spring AI 原生传递方式)
       if result.failed && config.replanOnFailure:
          → 回 PLANNING(带"第N步失败"上下文,上限 3 次防死循环)
  4. COMPLETED / FAILED → SSE: onComplete(summary) / onError
```

**关键设计点:**
- **复用 `ToolCallingManager`** 执行每个 step(不另起执行器)。
- **输出传递用 Spring AI 原生方式**:工具结果进对话历史,LLM 下一步读历史自行提取。不引入手搓端口/字段映射层(贴合"参考 Spring AI 2.0 原生做法")。
- **Re-planning 容错**:失败后 LLM 重规划(带上一步失败上下文),上限 3 次。
- **审批 gate 可配置**:`requirePlanApproval`(整计划)/`requireStepApproval`(每步)默认 false。
- **取消**:`AgentRun` 持取消标志,执行循环每步前检查。

#### 3.3.3 与现有聊天循环的关系

`AgentRunner` **不替代**聊天 `ChatBackend.chat()`。两者并存:
- **聊天模式**(现有):ReAct 式即时工具调用,多轮对话。保留但去弃用化(3.4)。
- **Agent 模式**(新):Plan-and-Execute,独立入口 `/api/agent/*`,独立前端 `AiAgent.vue`。
- 两者都通过 Spring AI `ToolCallingManager` 执行工具(统一执行层)。

---

### 3.4 现有聊天循环去弃用化

**目标:** `SpringAiCloudBackend.runToolLoop`(已弃用)改 `ChatClient` + `ToolCallingAdvisor`。

文件:`ZhiFlow/.../ai/service/SpringAiCloudBackend.java`
- `ChatClient.builder(chatModel).defaultAdvisors(ToolCallingAdvisor.builder().build()).build()` 替代裸 `chatModel.stream()` + 手动 loop。
- 工具经 `ChatClient.prompt().tools(toolCallbacks)` 注入(Spring AI 自动收集的 bean)。
- 保留 `AiStreamCallback` 的 `onToken`/`onToolCall`/`onToolResult` 事件(适配 `ChatClient` 响应式流)。
- 本地后端 `OllamaLocalBackend` 同样去弃用化。Agent 模式默认仅云端(本地模型规划能力弱)。

---

### 3.5 后端模式管理(从 `AiServiceProvider` 拆出)

`AiServiceProvider` 混了两职责:工具注册表(删)+ 后端模式管理(留)。

**保留部分**拆为独立 Spring bean:
文件:`ZhiFlow/.../ai/service/AiModeService.java`(新,`@Component`)

```java
@Component
public class AiModeService {
    private volatile ChatBackend activeBackend;
    private volatile String mode;  // "local" / "openai" / "anthropic" / "deepseek"
    // switchMode / getService / getCurrentMode / 状态监听
    // (从 AiServiceProvider 迁移,去 static,改 bean)
}
```

**删除部分**:`AiServiceProvider` 的工具 `registerTool`/`getTool`/`getTools`/`constrainedTool`。`constrainedTool`(slash-command 引导小模型)迁移到 `SlashCommandHandler` 局部状态或评估是否仍需要。

> **调用点适配:** 所有 `AiServiceProvider.getService()`/`switchMode()` 静态调用改为注入 `AiModeService`。grep `AiServiceProvider.` 全部调用点逐一改。实现计划阶段列清单。

---

### 3.6 API 与前端

#### 3.6.1 REST / SSE 端点

文件:`ZhiFlow/.../web/controller/AgentController.java`(新)

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/agent/run` | body: `{goal, config}`,返回 `{runId}` |
| GET | `/api/agent/stream?runId=` | SSE:plan token / plan ready / step 事件 / complete / error |
| POST | `/api/agent/{runId}/approve` | body: `{plan?}`;plan & step 审批 |
| POST | `/api/agent/{runId}/cancel` | 取消 |
| GET | `/api/agent/tools` | 列出可编排工具(name/desc/schema),供前端 + Phase 2 画布 |
| GET | `/api/plugin-categories` | 新增:分类列表(id + labelKey + icon),驱动前端侧边栏(见 3.1.4) |

SSE 事件:`plan_token` / `plan_ready` / `plan_approval_requested` / `step_start` / `step_complete` / `step_approval_requested` / `complete` / `error`。

现有 `/api/plugins`(改:descriptor 带 `supportsAi` + `source` + `category`)、`/api/plugins/{id}/invoke`(不变)、`/plugin-ui/{id}/**`(不变)。

#### 3.6.2 前端

- `AiAgent.vue`(新,最小):目标输入 + 配置 + Plan 展示(可编辑)+ 执行进度 + 审批按钮。
- 现有 `PluginView.vue` 渲染插件 ESM 微前端(不变,已是强制 UI 机制)。
- **侧边栏改造**(`Sidebar.vue`):删除硬编码分类列表,改从 `GET /api/plugin-categories` 动态拉取 + 追加 `all`/`favorites`(见 3.1.4)。
- 复用现有 `aiSession` store 的 SSE 消费模式。

> 前端做最小可用版,Phase 2 画布才是大头。

---

### 3.7 前端 i18n(vue-i18n)

**现状问题:** 前端**无任何 i18n**(无 `vue-i18n` 依赖、无 locale 文件、所有字符串硬编码英文,如 `Sidebar.vue:21-27` 的 `'All Tools'`/`'Text'`/`'Dev'`)。持久化的 `language` 设置存到 DB 但**无任何代码读取**。

**改动:引入 vue-i18n,后端驱动语言切换。**

1. **依赖与配置**:`frontend/package.json` 加 `vue-i18n`;新增 `frontend/src/i18n/` 目录:
```
frontend/src/i18n/
├── index.ts        # createI18n,默认 locale 从后端 language 设置取
├── en.json         # 英文(默认 fallback)
└── zh.json         # 中文
```

2. **翻译 key 结构**(对齐后端枚举的 key):
```json
{
  "category": { "dev": "Developer", "text": "Text", "image": "Image",
                "net": "Network", "ai": "AI", "other": "Other" },
  "source":   { "official": "Official", "third_party": "Third-party" },
  "sidebar":  { "all": "All Tools", "favorites": "Favorites" },
  "agent":    { "title": "AI Agent", "run": "Run", "approve": "Approve", ... }
}
```
   - `category.*` / `source.*` 与后端 `ToolCategory.labelKey` / `PluginSource.labelKey` 一一对应。
   - 现有硬编码英文字符串(`Sidebar.vue`/`ToolGrid.vue`/`Settings.vue`)迁移到 locale 文件。

3. **语言切换闭环**:
   - 后端 `language` 设置(DB)→ 启动时前端 `GET /api/settings` 读 language → 设为 vue-i18n 的 locale。
   - 用户在 `Settings.vue` 切语言 → `POST /api/settings` 存 DB + 前端即时切 locale。
   - 这样 `language` 设置**终于生效**(目前是死设置)。

4. **插件微前端 i18n**:`PluginView.vue:54` 现在的 `i18n: (key) => key`(identity 空操作)改为传入真实的 vue-i18n `t` 函数,让插件微前端也能用 `ctx.i18n('category.dev')` 得到翻译。插件自身 UI 字符串的 i18n 由插件自带 locale 资源处理(与主程序 i18n 解耦,Phase 6 SDK 再规范)。

**设计要点:**
- 后端是"有哪些分类/来源"的真相源(枚举 + 端点);前端是"怎么翻译显示"的执行者(vue-i18n locale 文件)。两边用 **i18n key** 对齐契约。
- 后端不返回翻译文本,只返回 key —— 避免后端维护 UI 字符串翻译表(后端 `I18n` 类与 JavaFX 耦合,headless 无法用)。
- 旧 `messages*.properties` 的 JavaFX UI 文本(`sidebar.label.*`/`detail.*`/`store.*`)**破坏性废弃**(本仓 headless 已无消费者);仅保留后端非 UI 用途的 key(若有)。

---

### 3.8 删除清单(破坏性更新)

**全部删除,无兼容保留:**

| 范围 | 内容 | 理由 |
|---|---|---|
| v1 包 | `ZhiFlow-Api/.../fan/summer/api/`(44 类)+ 对应测试 | 死代码,零活跃引用(已审计) |
| 手搓工具层 | `fan.summer.zhiflow.api.ai.AiTool` / `AiToolParam` / `AiToolResult` / `AiToolCall` | 由 Spring AI 原生 `ToolCallback` 取代 |
| 手搓执行层 | `ZhiFlow/.../ai/ToolExecutor` | 由 `ToolCallingManager` 取代 |
| 手搓 schema | `ZhiFlow/.../ai/adapter/ToolSchemaJson` | 由 `JsonSchemaGenerator` 取代 |
| 手搓适配器 | `ZhiFlow/.../ai/adapter/AiToolCallback` | 不再需要(插件直接实现 ToolCallback) |
| 手搓注册表 | `AiServiceProvider` 的工具部分 | 由 Spring AI bean 发现取代 |
| 旧插件契约 | `fan.summer.zhiflow.api.ZhiFlowPlugin`(JavaFX createView) | 由新 `plugin.ZhiFlowPlugin` 取代 |
| 旧 `ToolType` 枚举 | `BUILTIN`/`PLUGIN`(V1 概念,V2 本就无) | 由新 `PluginSource`(OFFICIAL/THIRD_PARTY)取代 |
| 旧不一致 i18n key | `sidebar.label.*`/`detail.category.*`/`store.online.category.*`/`detail.tag.*` | 统一为 `category.*`/`source.*`,前端 vue-i18n 接管 |
| 4 个内置 AI tool | `BuiltinJsonFormatTool` 等(本就未注册) | 改写为新 `@Tool` 形态或删 |

**保留改造:**
- `PluginDescriptor`(加 `supportsAi` + `source`)、`ToolCategory`(加 `AI`、改 labelKey 前缀)、`PluginRegistryService`(去 `registerAiTools` + `uiEntry`/`source` 校验)、`ZhiFlowPluginV2` → 重命名 `ZhiFlowPlugin`。
- 新增 `PluginSource` 枚举。
- `AiChatMessage` / `ChatBackend` / `AiStreamCallback` / `AiServiceException`(聊天契约,仍需要)。

---

## 4. 数据流示例

**场景:** 用户输入"把这个 JSON 格式化后算个 hash"。

```
1. POST /api/agent/run {goal:"格式化JSON后算hash"}
   → AgentRunner: PLANNING
2. ChatClient.stream(planning prompt + 工具列表[json_format, hash_calculate])
   → SSE plan_token: "我需要两步..."
3. SSE plan_ready: {steps:[
     {tool:"json_format", args:{mode:"pretty"}, desc:"美化JSON"},
     {tool:"hash_calculate", args:{algo:"sha256"}, desc:"计算hash"}
   ]}
4. (若开审批) SSE plan_approval_requested → 用户批准
5. EXECUTING:
   step 0: toolCallingManager 执行 json_format → result
           SSE step_start(0)/step_complete(0);历史追加 ToolResponseMessage
   step 1: LLM 读历史拿上步输出 → toolCallingManager 执行 hash_calculate
           SSE step_start(1)/step_complete(1)
6. SSE complete: {summary:"已格式化并计算,sha256=..."}
```

---

## 5. 向后兼容性(无)

本次为破坏性更新,**不保证任何向后兼容**:
- 旧 3.x 插件(实现旧 `ZhiFlowPlugin` JavaFX 契约)需按新契约重写:后台 `invoke` + ESM 微前端 UI +(可选)`@Tool` AI。
- 旧 `AiTool` 实现改写为 `@Tool` 方法或 `ToolCallback` bean。
- 本仓内 `MarkdownPlugin`、4 个内置 tool 随本次改造。
- 官方插件仓(Excel/PDF/Email/Browser)需同步迁移(另行计划,不在本次范围)。

---

## 6. 测试策略

- **单元测试:**
  - 新 `ZhiFlowPlugin` 契约:`descriptor()`/`invoke()`。
  - `@Tool` 方法被 Spring AI 正确发现并生成 schema(spike 验证 + 测试)。
  - `AgentRunner` 状态机(PLANNING→APPROVAL→EXECUTING→COMPLETE),mock `ToolCallingManager` + mock `ChatClient`。
  - Re-planning 容错(失败 3 次 → FAILED)。
  - `PluginRegistryService` 的 `uiEntry` 强制校验、`source` 降级校验(OFFICIAL 但 id 不符 → 降 THIRD_PARTY)。
  - `GET /api/plugin-categories` 返回的分类 id/labelKey 与 `ToolCategory` 枚举一致(含新增 `AI`)。
- **集成测试:**
  - 端到端:mock echo tool + `goal="echo hello"` → 验证 plan + execution + SSE 事件序列。
  - 审批 gate:block 在 AWAITING_PLAN_APPROVAL,模拟 approve → 继续。
- **前端测试:**
  - vue-i18n locale 文件含 `category.*`/`source.*` 全部 key。
  - 切换 `language` → vue-i18n locale 即时变化。
  - 侧边栏从 `/api/plugin-categories` 动态渲染(无硬编码)。
- **回归:**
  - 删除手搓层后全模块 `mvn test` 通过。
  - `MarkdownPlugin` 按新契约改造后仍工作(UI render + 可选 @Tool + source=OFFICIAL + category=TEXT)。

---

## 7. 风险与未决项

| 风险 | 缓解 |
|---|---|
| 删除手搓层波及多处调用点(`AiServiceProvider` 静态调用遍布) | 实现计划先 grep 全部调用点,逐一适配到 `AiModeService` + Spring AI bean |
| Spring AI `@Tool` 自动发现 + ChatClient 流式 + 工具循环有坑 | 先 spike:一个 `@Tool` 跑通发现→schema→ChatClient.stream→ToolCallingAdvisor→事件回调 |
| `supportsAi` 声明与实际 ToolCallback bean 一致性难严格校验 | 启动时宽松校验(告警不阻断);bean 归属判定松 |
| mode 过滤(supportsLocal/Cloud)迁移后本地模型工具过多 | 本期默认不过滤;若需,用 ChatClient 构造时选择性传 tools |
| Plan-and-Execute 对弱模型(本地 Ollama)规划质量差 | Agent 模式默认仅云端 |
| re-planning 死循环 | 硬上限 3 次 + 每步取消检查 |
| 前端 i18n 迁移面广(所有硬编码字符串) | 先迁移分类/来源/侧边栏(Agent UI 顺带);其余现有视图字符串按文件逐步迁,不阻塞主功能 |

**未决项(留给实现计划):**
1. `constrainedTool`(slash-command 引导)是否保留及落点。
2. Agent run 是否本期持久化(倾向否,Phase 2 随画布)。
3. mode 过滤是否本期实现。
4. `OFFICIAL` 降级校验的 id 前缀规则(倾向 `fan.summer.*`,计划阶段定)。

---

## 8. 分阶段交付建议(供实现计划参考)

1. **删除死代码** — `fan.summer.api.*`(v1)+ 旧 `ZhiFlowPlugin` JavaFX 契约 + `ToolType`。验证编译。
2. **删除手搓工具层** — `AiTool`/`AiToolParam`/`AiToolResult`/`ToolExecutor`/`ToolSchemaJson`/`AiToolCallback`。拆 `AiServiceProvider` → `AiModeService`。适配所有调用点。
3. **新插件契约** — `ZhiFlowPlugin`(重命名)+ `PluginDescriptor`(加 `supportsAi` + `source`)+ `ToolCategory`(加 `AI`、改 labelKey)+ `PluginSource`(新)+ `uiEntry`/`source` 校验。改造 `MarkdownPlugin`、4 内置 tool 为新形态。
4. **Spring AI 工具接入** — `@Tool`/`ToolCallback` bean 发现 + `ToolCallbackResolver`。spike 验证 schema 生成。
5. **去弃用化聊天循环** — `SpringAiCloudBackend` → `ChatClient` + `ToolCallingAdvisor`。spike 流式 + 工具事件。
6. **Agent 运行时** — `AgentRunner` + 数据模型 + `AgentController` + SSE。
7. **分类端点 + 前端 i18n** — `GET /api/plugin-categories` + vue-i18n 引入 + locale 文件 + 侧边栏动态渲染 + language 设置闭环。
8. **前端 `AiAgent.vue`** — 最小可用。
9. **测试 + 回归**。

每步可独立提交、独立验证。

---

## 参考

- [Spring AI Tool Calling Reference](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring AI Composable Tool Architecture (2.0)](https://spring.io/blog/2026/06/15/spring-ai-composable-tool-calling)
- 现有代码:`ZhiFlow/.../ai/service/SpringAiCloudBackend.java:172`(弃用的 runToolLoop)、`ZhiFlow/.../ai/ToolExecutor.java`、`ZhiFlow-Api/.../api/ai/AiTool.java:28`、`ZhiFlow-Api/.../api/plugin/ZhiFlowPluginV2.java:15`、`plugin-markdown/.../MarkdownPlugin.java`
- 前序迁移:`docs/superpowers/specs/2026-07-06-phase1-ai-strangler-spring-ai`(LangChain4j→Spring AI)
