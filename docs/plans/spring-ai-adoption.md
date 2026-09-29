# Spring AI 连接层采用（专项）——执行记录与边界规则

更新日期：2026-09-29（执行版 1；本文是**已完成记录**，不是待执行任务书）

适用项目：Infinia / FengYu 4.1+，Spring Boot 4.1.1 + Spring AI 2.0.1（BOM）

编写/执行时源码版本：`release/4.1.0` @ `8d3f2624` + 未提交批次（架构批次 ToolLoopDriver/RolloutEvent
+ 本批次）。**每项采纳前均已获取并读取 Spring AI 2.0.1 对应源码**（sources jar 逐方法核对），
本文的证据栏标注源码出处；三轮严格审查纠正了三处凭文档摘要得出的错误结论（见 §4）。

## 1. 定位

Spring AI = **AI 连接层**：协议编解码、工具 schema/执行引擎、工具检索索引、结构化输出转换与校验、
瞬态错误分类、观测埋点。编排核心（审批、效果调度、rollout 事实源、压缩、取消）仍是 FengYu 的
（`ToolLoopDriver`），Spring AI 没有对应能力（逐项核实，见 §3）。

## 2. 已执行采纳（全部有测试钉住）

| # | 采纳 | 使用的 Spring AI / Spring Core API | 源码证据 | 验证 |
|---|---|---|---|---|
| A1 | 工具调用限额显式化 + 超限不杀轮次 | `ToolCallingManager.builder().maxCallsPerTool/maxTotalToolCalls/onLimitExceeded(RETURN_ERROR_RESPONSE)` | `DefaultToolCallingManager:100,107,131`（默认 40/150/**THROW**——原状态是隐式 THROW，超限会以异常杀死整个轮次）；`RETURN_ERROR_RESPONSE` 语义：合成错误文本 ToolResponse、跳过该调用、批内继续（方法体读毕） | 全量回归 |
| A2 | 模型调用瞬态重试 | Spring Core 7 `RetryTemplate/RetryPolicy`（Spring AI `RetryUtils` 自用的同一抽象）+ SDK 自带可重试标记 `OpenAIRetryableException`/`AnthropicRetryableException` + Spring AI 分类 `TransientAiException`/`ResourceAccessException` | 2.0.1 模型实现**零内建重试**（`OpenAiChatModel` 无 retry 引用；`spring-ai-retry` 只是工具模块）；策略过滤器**遍历 cause 链**（`ExceptionTypeFilter.match(t,true)`）——"已吐字不重试"用**错误作为返回值**实现而非包装类 | `ChatTransientRetryTest` 3 例：预吐字重试成功/吐字后零重试/耗尽=1+maxRetries 次 |
| A3 | 动态工具检索内核 | `RegexToolIndex`/`ToolIndex`/`ToolSearchRequest`（`spring-ai-tool-search-tool` 新依赖） | `ToolIndex` 接口 session-scoped、可独立于 advisor 使用（advisor 形态自带工具循环，与用户控制执行冲突——**不采纳 advisor，采纳索引**）；每次检索前按会话重建索引，名称→定义解析与激活记账仍是我们的 | `SearchToolsToolTest` 5 例全绿流经真实索引 |
| A4 | 结构化输出（flow_llm） | `ChatClient` + `StructuredOutputValidationAdvisor`（networknt DRAFT_2020-12 真校验 + `maxRepeatAttempts(1)` 单次定向修复）+ 自定义 `StructuredOutputConverter`（动态 schema，`getFormat()` 镜像 `BeanOutputConverter` 措辞，lenient 解析=官方文档认可的模式） | advisor 全文读毕：**校验严格**（原文直接 `readTree`，栅栏回复触发一次修复——语义已如实落测试）；全尝试失败**原样返回最后一次响应**（原文天然存活）；`entity()+validateSchema()` 集成路径不暴露 maxRepeatAttempts——故用显式 advisor 形态 | `FlowLlmToolTest` 10 例（结构化 5 例流经真 advisor） |
| A5 | AI 观测 | `ObservationRegistry`：`ChatModelObservabilityWiring` 桥接 Spring 托管 bean → `ChatModelConfig` 静态 holder → 三家模型 builder（OpenAI:1565/Anthropic:1873/Ollama:544 均有 `observationRegistry()` 钩子）+ OpenAI SDK 客户端 HTTP 观测 + `ToolLoopDriver` 的 `ToolCallingManager.observationRegistry(...)` | `internalCall`/`internalStream` 均有 `CHAT_MODEL_OPERATION` 埋点（方法体读毕）——直调 ChatModel 也出观测；manager 每次工具调用发观测（用户控制路径同样生效） | `ChatModelObservabilityTest`（registry 自检 + 真实工具轮观测落账） |
| A6 | 清理 | 删除两个 backend 从未使用的 `chatClient` 死字段（全仓 grep 证实零引用） | — | 编译+回归 |

## 3. 边界：保留的自研（Spring AI 无对应能力的核实记录）

| 保留 | 核实结论（源码级） |
|---|---|
| `ConversationCompactor`（token 压缩/缓存前缀记账/mid-turn） | `MessageWindowChatMemory` 仅条数窗口 + SystemMessage 保留，无 token 淘汰、无压缩；JDBC 仓库丢弃工具消息的断言仅为文档级（次要理由，主理由已源码证实） |
| `ToolBatchExecutor` 读写屏障、`ChatToolApprovalGate`、`ToolGuardService` | Spring AI 无效果分组/审批/守卫概念 |
| rollout（`AiRolloutService`/`RolloutEvent`）、`AiMessageBridge` 防腐层 | 无对应物；架构边界（连接层的另一半） |
| `ConnectionTester` 原始 HTTP 探测 | 无"测试连接"API；裸探测错误信息可操作性更好 |
| 线程本地上下文（`WorkspaceContext` 等继承式 ThreadLocal） | `ToolContext` 仅单调用传参；我们的机制跨虚拟线程 fan-out 有效，替换需动 40+ 工具且无收益。**新工具的增量上下文优先考虑 ToolContext，存量不迁移** |

## 4. 三轮严格审查纠正的错误（防止回潮）

1. "模型内建 RetryTemplate 已生效"——**证伪**：2.0.1 无内建重试（这正是 A2 的动机）。
2. "FlowLlmTool 用 `validateSchema()`"——**证伪**：它是 ChatClient `EntityParamSpec` 层能力，
   `FlowLlmTool` 的一次性模型直调路径不存在该开关；采纳形态改为显式 advisor（A4）。
3. "删 465 行动态工具加载自研"——**过强**：advisor 形态与用户控制循环冲突；组件级采纳（A3）
   只替换检索内核，激活记账/目录 prompt 仍是职责内的编排。

## 5. P2 评估决策（不采纳/延后，均为核实后结论）

- **`McpToolFilter`**（`BiPredicate<McpConnectionInfo, McpSchema.Tool>`）：接缝在
  `SyncMcpToolCallbackProvider`（`AiToolRegistry` 经 `ObjectProvider` 注入的是 autoconfigure
  属性路径的 provider）。默认全放行的过滤 bean 无意义；**真实策略需要产品决策**（哪些 MCP server
  可信）——归入 [[agent-os-sandbox]] 专项的安全评审一起定，本文不预置。
- **`spring-ai-test`**：artifact 不在我们的依赖图；我们手写的 `ScriptedChatModel`/
  `NeverCompletingModel` 已精确贴合 CAS/取消/工具循环流程（官方工具面向 provider 级
  Testcontainers 测试）。不采纳。
- **`RetryUtils.DEFAULT_RETRY_TEMPLATE`**：10 次重试/最长 3 分钟间隔——交互式聊天轮不可接受；
  且其分类集不含官方 SDK 异常。采用自建 policy（同一 Spring Core 抽象，A2）而非其模板实例。

## 6. 后续规则

1. 新增 AI 能力前先查 Spring AI 参考文档**并读对应源码**（方法体级），文档摘要不算证据。
2. "Spring AI 没有 X" 的论断必须带源码出处（§3 格式），无出处的不得作为保留自研的理由。
3. Spring AI 升级时重验：A1 的默认限额行为、A2 的过滤器 cause 链遍历、A4 的 advisor
   校验严格性（栅栏触发修复）——这三处语义敏感，升级 changelog 必须复查。

## 7. 验证记录

- 最终状态全量：**1416 tests, 0 failures, 0 errors, 2 skipped（既有 Windows-only），BUILD SUCCESS**。
- 新增测试：`ChatTransientRetryTest`(3)、`ChatModelObservabilityTest`(1)、`FlowLlmToolTest`
  重写(+1=10)、`SearchToolsToolTest` 全量流经真实索引(5)。
- 新依赖：`org.springframework.ai:spring-ai-tool-search-tool`、`org.springframework.ai:spring-ai-retry`
  （均 BOM 2.0.1 管版本，同一维护线）。
