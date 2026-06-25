# Qwen3-4B 本地 Tool-calling 切换设计

- **日期**:2026-06-22
- **状态**:已设计,待实施
- **分支**:v3.1.0
- **背景调研**:`docs/superpowers/specs/` 同目录无前置 spec;调研结论见会话记录(Qwen2.5-3B 判断力弱、7B 内存超限、Qwen3-4B ≈ Qwen2.5-7B 质量)

## 1. 目标与约束

### 目标
将 SwissKitJ 本地模式的 tool-calling 从当前的 **FunctionGemma(functiongemma-270m-it)专属路径**切换到 **Qwen3-4B-Instruct(GGUF, Q4_K_M)**,作为唯一的本地工具调用模型,并**展示模型的 thinking 过程**。

(说明:Qwen2.5-3B 是本次调研评估的对象,并非当前实现路径——当前线上本地 tool-calling 走的是 FunctionGemma adapter。)

### 硬约束(已与用户确认)
1. **内存上限**:目标机器 RAM 受限,4B(Q4 ≈ 2.5GB)是上限,7B(≈ 4.4GB)放不下。→ 必须用 Qwen3-4B,无 7B 退路。
2. **thinking 展示**:Qwen3 的 `<think>…</think>` 必须渲染成 AI 回复区的**可折叠"思考过程"块**(用户选 B)。
3. **彻底移除 FunctionGemma**:Qwen3 成为唯一本地 tool-calling 模型后,FunctionGemma 整条线连根拔掉。

### 非目标(YAGNI)
- 不做多模型并行(本地只一种 tool-calling 模型)。
- 不为 Qwen2.5 特殊 token 格式保留独立路径(其正则留在共享 `ToolCallParser` 作兜底即可)。
- 不做模型评测框架(验证用手动冒烟,见 §6)。

## 2. 背景与现状

本地模式是**自带的 GGUF 推理引擎**(非 Ollama):`LocalChatBackend` 走两条后端——
- **NATIVE**:llama.cpp JNI,经子进程 `NativeWorkerClient` 隔离原生崩溃;
- **JAVA**:纯 Java `LlamaRunner`,native 崩溃时回退。

tool-calling 完全靠两件事:
1. `ChatTemplate`——从 GGUF 的 `tokenizer.chat_template` 元数据自动识别 LLAMA3 / CHATML / MISTRAL / GEMMA,把对话 + 系统 prompt 拼成文本;
2. `ToolCallParser`——从模型**原始输出文本**正则抽取 tool call,现有两条:`<|tool_call_begin|>…<|tool_call_end|>`(Qwen2.5)和裸 JSON。

`FunctionGemmaAdapter` 是为 google/functiongemma-270m 的定制协议(非 JSON、🪙 分隔符、控制 token)做的完整 adapter,含 `chatFunctionGemmaNative` 专属路径。

## 3. Qwen3 行为分析

| 维度 | Qwen3 实际 | 现有代码是否已支持 |
|---|---|---|
| 基础模板 | ChatML(`<|im_start|>`/`<|im_end|>`) | ✅ `ChatTemplate` 检测 `im_start` → CHATML |
| 工具调用格式 | Hermes:`<tool_call>\n{"name":..,"arguments":{..}}\n</tool_call>` | ❌ 需新增正则 |
| 工具结果回填 | `<\|im_start\|>tool\n<result><\|im_end\|>` | ✅ `buildChatML` 已处理 TOOL 角色 |
| 推理块 | `<think>…</think>`(默认开) | ❌ 需流式分段 + 渲染 |
| 多语言 | 原生支持中文 | ✅(顺带让 `OfflineNlNormalizer` 变多余) |

**结论**:真正要新建的只有"Qwen3 专属流式分段 + thinking 通道";解析原语下沉到共享 `ToolCallParser`。

## 4. 架构与组件清单

| 组件 | 动作 | 职责 |
|---|---|---|
| `ToolCallParser` | **改** | 新增第 3 条正则 `<tool_call>\s*(\{.*?\})\s*</tool_call>`(复用现有 name/arguments 提取);新增 `stripThinking(text)`。成为三格式通用解析器。 |
| `Qwen3Adapter`(新) | **建** | ① `ThinkingStreamSegmenter`——有状态流分段器(见 §5);② thinking-mode 开关(默认开);③ `buildQwen3SystemPrompt`——仅当冒烟证明通用 prompt 激发不出 `<tool_call>` 时启用(YAGNI,默认不加)。 |
| `LocalChatBackend` | **改** | `detectModelType` 删 functiongemma、加 `qwen3`(文件名含 "qwen3");新增 `chatQwen3Native` + `generateQwen3Loop`(替代被删的 FG 路径);`buildSystemPrompt` 删 `if(isFunctionGemma) return ""`。 |
| `AiStreamCallback` | **改** | 加 `default void onThinking(String fragment) {}`(向后兼容)。 |
| `AiChatPlugin` | **改** | 重写 `onThinking`:把片段喂给 `MarkdownRenderer` 的"思考过程"折叠块。 |
| `MarkdownRenderer` | **改** | 新增 thinking 块渲染(可折叠 `<details>`,深色主题适配 `#1e1e2e`)。 |
| `StopDetector` | **改** | 删 4 条 FG 停止序列(`<end_function_call>` 等)。**不加** `<tool_call>` / `<think>` 为停止序列——这些由 segmenter 处理,不应中断生成。 |
| `OfflineNlNormalizer` + test + `/ai/nl-normalizer.properties` | **删** | FG 专属,孤儿;Qwen3 原生支持中文,该归一化对 Qwen3 有害无益。 |
| `FunctionGemmaAdapter` + test | **删** | FG 核心。 |
| `SlashCommandHandler` | **改** | 仅 javadoc 去掉 FG 举例(逻辑不动,模型无关的 `/命令` 解析器,仍作小模型兜底)。 |
| `docs/superpowers/specs/2026-06-19-functiongemma-adaptation-design.md` | **删** | 描述被删除的功能。 |
| `docs/superpowers/plans/2026-06-19-functiongemma-adaptation.md` | **删** | 同上。 |

## 5. 核心算法 — ThinkingStreamSegmenter

### 问题
流式生成时 token 逐个到达,而 `<think>` / `</think>` / `<tool_call>` 标记可能被 token 边界劈开(如 `<thi` + `nk>`)。不能对每个 token 做 `contains` 判断——会漏。

### 思路
复用 `StopDetector.endsWithPartialStop` 已验证的"尾部可能是某标记前缀"的处理逻辑。

### 状态机
```
CONTENT(正文) ⇄ THINK(思考) → TOOL_CALL(缓冲,不外发)
```

### 外发策略
- **THINK 片段** → `callback.onThinking(...)`(实时,边想边显示进折叠块)
- **CONTENT 片段** → `callback.onToken(...)`(正文区)
- **TOOL_CALL 片段** → **不外发**(用户不该看到裸 JSON 闪过),积攒在内部 buffer,`onDone` 后整体交 `ToolCallParser` 终态抽取 → `onToolCall` / `ToolExecutor`

### 核心流程(伪代码)
```java
class ThinkingStreamSegmenter {
    enum Type { THINK, CONTENT }
    record Segment(Type type, String text) {}

    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder toolCallBuf = new StringBuilder();
    private boolean inThink = false, inToolCall = false;

    List<Segment> feed(String fragment) {           // 每个 token 调一次
        pending.append(fragment);
        List<Segment> out = new ArrayList<>();
        while (true) {
            if (inToolCall) {
                int close = pending.indexOf("</tool_call>");
                if (close < 0) { holdbackTail("</tool_call>".length()); break; }
                toolCallBuf.append(pending, 0, close);    // 吸进缓冲,不外发
                pending.delete(0, close + 11); inToolCall = false; continue;
            }
            if (inThink) {
                int close = pending.indexOf("</think>");
                if (close < 0) { holdbackTail(8); break; }
                out.add(new Segment(THINK, pending.substring(0, close)));
                pending.delete(0, close + 8); inThink = false; continue;
            }
            // CONTENT:找下一个开标记
            int tOpen = pending.indexOf("<think>"), cOpen = pending.indexOf("<tool_call>");
            int next = firstNonNeg(tOpen, cOpen);
            if (next < 0) { holdbackTail(11); break; }    // 11 = max 标记长
            if (next > 0) out.add(new Segment(CONTENT, pending.substring(0, next)));
            if (next == tOpen) { pending.delete(0, 7); inThink = true; }
            else { pending.delete(0, 11); inToolCall = true; }
        }
        return out;
    }

    private void holdbackTail(int maxMarkerLen) {
        // 尾部若不是任何标记前缀 → 全吐;若是 → 扣住等下个 token 拼上再判
        // (regionMatches 技巧,同 StopDetector)
    }

    List<Segment> flush() { /* EOS:未闭合 think 按 THINK 吐;未闭合 tool_call 留 buffer */ }
    String drainToolCalls() { return toolCallBuf.toString(); }  // onDone 后交 ToolCallParser
}
```

### 关键约束 — thinking 不进对话历史
`<think>` 内容**只用于展示**,不回填进下一轮 prompt。否则 8192 上下文会被冗长思考吃掉。`generateQwen3Loop` 回填时只放 assistant 最终正文 + tool calls,thinking 丢弃。(主流 agent 实践,用户已确认。)

## 6. 数据流(Qwen3 一次带工具的对话)

```
用户输入 → ChatTemplate.buildChatML(已支持) → llama.cpp 流式生成
  → Qwen3Adapter.ThinkingStreamSegmenter 逐 token 分段:
       <think>…</think>            → callback.onThinking(片段) → MarkdownRenderer 折叠块
       正文                          → callback.onToken(片段)      → 正文区
       <tool_call>{json}</tool_call> → 收集到 buffer(不外发)
  → onDone:drainToolCalls() → ToolCallParser 终态解析
  → 若有 tool call:ToolExecutor 执行 → 回填 <|im_start|>tool → generateQwen3Loop 下一轮(≤8)
  → 无 tool call(或工具结果已足以作答):onComplete
```

## 7. 错误处理与边界

| 场景 | 处理 |
|---|---|
| `<tool_call>` 里 JSON 畸形 | `ToolCallParser.buildCall` 已有 try/catch → 存 `_raw`,不崩。复用。 |
| 模型吐裸 JSON(没用 `<tool_call>` 包) | `ToolCallParser` 的 GENERIC 正则兜底。降级但仍可用。 |
| EOS 时 `<think>`/`<tool_call>` 未闭合 | `flush()`:未闭合 think 按 THINK 吐完;未闭合 tool_call 留 buffer 交终态解析,失败则丢弃(不执行错工具)。 |
| 一个 turn 内多个 `<tool_call>` | segmenter 全缓冲;`ToolCallParser` 抽全部;`ToolExecutor` 逐个执行(沿用 FG loop 批量模式)。 |
| native worker 崩溃 → Java 回退 | Java 回退**不带 segmenter**,thinking 不显示;但工具调用仍走共享 `ToolCallParser`(Hermes 正则已加),**功能不丢**。日志提示降级。可接受(回退是保命模式,不值得复制流式分段)。 |
| thinking 过长 | 不进历史(见 §5),只占 UI,不挤占 8192 上下文。 |
| `/no_think` 模式 | 模型不吐 `<think>`,segmenter 全程 CONTENT 态,透明直通。 |

## 8. 风险与退路

| 风险 | 概率 | 退路 |
|---|---|---|
| 通用 markdown prompt 激发不出 Qwen3 的 `<tool_call>`(唯一不确定性) | 中 | 触发 `Qwen3Adapter.buildQwen3SystemPrompt` 的 Hermes 感知增强(§4 标记 YAGNI 的退路);最坏情况模型吐裸 JSON,GENERIC 正则兜底。 |
| Qwen3-4B 仍"过度调用"(3B 老毛病) | 低(加 thinking 后改善) | 缩小挂载工具集(≤5);`ToolExecutor` 执行前校验 name 在册、参数合 schema。 |
| thinking 流式分段边界 bug(标记被劈) | 中 | `ThinkingStreamSegmenterTest` 专门覆盖劈开用例(见 §9)。 |
| **thinking 流式片段的 WebView 增量渲染** | 中 | `MarkdownRenderer` 现按"整段已定稿 Markdown"渲染;流式 thinking 片段是逐步到达的,不能每来一片就重渲染整篇(闪烁 + 性能)。实施时需解决:要么 thinking 收齐后再一次性渲染折叠块(简单,牺牲"边想边看"实时性),要么 MarkdownRenderer 支持对某个稳定 DOM 节点做 append 式增量更新(保留实时性,前端改动更大)。**计划阶段二选一并写清**。 |
| Java 回退路径体验降级 | 低 | 接受(§7);仅工具功能不丢。 |

## 9. 测试与验证矩阵

### 第 1 层:解析器单元测试(确定性,必做)
**`ToolCallParserTest`(新建)**:
- Hermes 单工具 / 多工具 / 与 Qwen2.5 特殊 token 混合 / 裸 JSON 兜底 / `stripThinking` / 畸形 JSON 不崩

**`ThinkingStreamSegmenterTest`(新建,最关键)**:
- 标记被劈开(`<thi`+`nk>` 等,必测回归用例)
- 三标记都劈 / think 后接正文 / 正文后接 tool_call / 多 tool_call 串行
- `flush()` 未闭合 think(吐 THINK)/ 未闭合 tool_call(留 buffer 不外发)
- `/no_think` 直通(全 CONTENT)

### 第 2 层:适配器集成测试(确定性)
**`Qwen3AdapterTest`(新建,仿 FunctionGemmaAdapterTest)**:
- 模拟完整 Qwen3 输出(`<think>` + 正文 + `<tool_call>`),断言 thinking 抽出、正文干净、tool calls 解析对
- thinking-mode 开关:默认开吐 think;`/no_think` 不吐

### 第 3 层:端到端冒烟(半手动,验证 B)
**前提**:Qwen3-4B GGUF(Q4_K_M,~2.5GB)。本地没有则计划第一步下载(HuggingFace `Qwen/Qwen3-4B-Instruct-GGUF` 或 Ollama `qwen3:4b` 拉取)。

**冒烟清单**:
1. 加载 GGUF,日志确认 `ChatTemplate: CHATML` + `Qwen3 detected`
2. 挂 1 工具(base64),中文"把 hello 编码成 base64" → 折叠块 + 工具调用 + 结果正确
3. 英文同指令 → 同上
4. **关键回归**:闲聊("你好")不触发 tool_call(验证不"过度调用")
5. 多工具:挂 2 工具,选其中一个 → 选对
6. 多轮:tool call → 回填 → 总结 → 链路通,thinking 不进历史
7. `/no_think` → 无折叠块,直通,延迟降

**通过标准**:1-4 必过;5-7 有问题记录但不阻塞(调优项)。

## 10. 实施顺序(给 writing-plans 的输入)

1. **移除 FunctionGemma**(独立可验证):删 FG 核心 + 附属件 + 文档,编译通过,现有测试不回归。
2. **共享解析层**:扩 `ToolCallParser`(Hermes 正则 + `stripThinking`)+ `StopDetector` 清理。第 1 层测试。
3. **Qwen3 流式核心**:`ThinkingStreamSegmenter` + `Qwen3Adapter` + `AiStreamCallback.onThinking`。第 1/2 层测试。
4. **接入 LocalChatBackend**:`detectModelType` + `chatQwen3Native` + `generateQwen3Loop`。
5. **前端**:`AiChatPlugin.onThinking` + `MarkdownRenderer` 折叠块。
6. **GGUF 获取 + 端到端冒烟**(第 3 层)。
7. **退路触发判定**:冒烟不过则加 `buildQwen3SystemPrompt`。

每步独立可编译可测,验证 B 的"小规模"体现在第 1/2 层先行、端到端冒烟作为收尾验收。
