# FunctionGemma-270m-it 适配设计（离线本地模式）

- **日期**: 2026-06-19
- **分支**: v3.0.1
- **状态**: 设计已确认，待实现
- **关联**: Excel 拆分工具链（`fan.summer.buildintool.ai.*` / `fan.summer.buildintool.excelsplitter.*`）

## 1. 背景与动机

本地模式使用 `google/functiongemma-270m-it`（GGUF）作为 function-call 执行模型。目标：在激活本地模式后，用户通过**一条自然语言指令**即可驱动内置工具的全部功能，以 Excel 拆分为标杆——一句话完成「读取/分析 → 拆分配置 → 执行拆分」。

现有代码已有 FunctionGemma 适配骨架（`FunctionGemmaAdapter`）、6 个 Excel AI 工具、以及工具调用循环（`ToolExecutor`）。但审查发现：**现有实现无法达成目标**，障碍分两层且叠加。

### 1.1 模型层硬限制（不可改）

依据官方 model card（HuggingFace）与 formatting guide（ai.google.dev）：

- FunctionGemma-270m-it **只训练了 Single-Turn 与 Parallel** 函数调用。
- **明确未训练 Multi-Step（链式）与 Multi-Turn**——原文：「Multi-Step (Chaining): the output of one tool is required as the argument for a subsequent tool … FunctionGemma is not trained to reason through this dependency chain automatically.」
- 模型**英文训练**，评估仅含英文 prompt（中文输入命中率极低）。
- 基线准确率有限（BFCL）：Simple 61.6 / Multiple 63.5 / **Parallel 39 / Parallel Multiple 29.5** / Live Simple 36.2。官方建议特定领域**微调**（Mobile Actions 58%→85%）。
- 模型「不是对话模型」，职责是输出函数调用 + 简短摘要。

Excel 流程恰恰是典型链式调用：`excel_configure` 必须拿到 `excel_analyze` 返回的 sheet/列名；`excel_execute` 依赖 `excel_configure`。模型开箱无法自主完成。

### 1.2 代码层限制（可改）

`AiServiceImpl.chatFunctionGemmaNative`（`SwissKit/.../ai/service/AiServiceImpl.java`）：

- 解析到工具调用后**只取 `toolCalls.getFirst()`** 执行一次，随后调用 `generateFinalAnswer`（再生成一次即 `onComplete` 收尾）。**没有多轮递归循环**——而普通 native 路径 `generateNativeWithToolLoop` 是有递归的（上限 `MAX_TOOL_ROUNDS`）。
- 即使模型愿意连续调用，代码也只跑第一步就停 → 多步流程在单轮内**结构性不可能**。
- 并行调用（模型已训练）被丢弃（只取 first）。

### 1.3 其它已识别问题

- **缺 enum 约束**：`excel_configure.mode`（BY_SHEET/BY_COLUMN/COMPLEX）、`excel_complex_config.action`（add/list/clear）是有界枚举，但 `buildToolDeclarations` 不输出 `enum:[…]`（官方格式支持）。小模型只能从描述猜合法值。
- **17 个工具一次性灌入 developer prompt**：base64/hash/json_format/color_convert + 6 excel + 2 email + 3 pdf + `browser_automate`。对 270M 模型选择噪声大。
- **工具描述偏术语化**：官方 #1 缓解措施是 Enriched Tool Definition；当前描述满是 BY_SHEET/COMPLEX/UUID/DB-backed 等术语，缺语义关键词与示例。
- **解析鲁棒性**：正则 `call:(\w+)\{([^}]*)}` 用 `[^}]*`，参数块不能含 `}`；模型漏 🪙/漏参数/枚举拼错 → 解析空 → 直接 `onComplete` 吐乱码，无重试/澄清。（`convertValue` 失败回退字符串这点是好的，保留。）
- **ctx=4096** 偏紧（模型支持 32K），声明+多轮历史占用大。

## 2. 目标与非目标

### 目标
- 本地 FunctionGemma 模式下，一条自然语言指令驱动 Excel `analyze→configure→execute` 全流程。
- 全程**离线**（不引入在线翻译、不引入第二模型）。
- 中文输入经**离线规则层**归一化后可用（覆盖范围=词典内，诚实声明上限）。

### 非目标（YAGNI / 延后）
- **不微调模型**——这是把 ~60% 提到 ~85% 的根本路径，留作后续。
- **不做停止序列**（`<end_function_call>`）——需 native 侧改造（`GenerateParams`+`NativeWorkerClient`+`NativeWorkerMain`+JNI），延后；parser 取首匹配已够用。
- 不重构非 FG 路径（OpenAI/Anthropic/普通 native/java）。
- 不解决模型基线准确率上限（偶发选错工具/参数属模型能力上限，文档注明）。

## 3. 方案选型

| 方案 | 思路 | 取舍 |
|---|---|---|
| **A. Host 驱动多轮循环（选定）** | 模型只做 single-turn 调用；多步下放到 host 循环；中文用离线规则层 | 改动集中、贴现有架构、离线、轻量 |
| B. Host 脚本编排 + 模型仅抽取参数 | host 识别意图后跑固定流水线，模型只填参数 | 可靠性最高，但需意图识别+每工具抽取模板，灵活度低 |
| C. 微调 | 用本项目工具集微调 | 长期/产品化路线，工作量大 |

**选定 A**：复用现有 `SplitConfig` 共享状态与工具间 `"Call X first"` 报错引导，多步逻辑安全下放；每步都是模型擅长的单调用。

## 4. 总体设计

### 4.1 数据流

```
用户 NL（中/英）
  → [离线归一化层] 动作关键词映射为英文、标识符(路径/sheet/列名)原样透传 → 规范化英文 NL
  → [FunctionGemma 多轮循环]
       每轮: 模型输出 1~N 个 call → host 执行全部 → 结果逐个回喂为 function_response → 重建 prompt → 下一轮
  → 某轮无 call（即最终摘要）或达到 MAX_TOOL_ROUNDS → onComplete
```

### 4.2 设计原则
- 模型职责收敛到 single-turn 调用；host 负责循环、状态、错误兜底。
- 中间工具调用轮**不向前端流式** `call:…{…}` 噪声 token；仅最终答案流式（保留 `onToolCall/onToolResult` 供 UI 展示步骤）。
- 声明/描述面向小模型：英文、含语义关键词、含 enum、含 1 行示例。

## 5. 详细改动

### 5.1 `AiServiceImpl` — FunctionGemma 多轮循环（P0，核心）

- 新增递归 `generateFunctionGemmaLoop(prompt, temperature, topP, maxTokens, callback, round)`，镜像现有 `generateNativeWithToolLoop`。
- 上限：`MAX_TOOL_ROUNDS` 由 **5 提到 8**（链 3 步 + 自纠余量）。
- 每轮 `onDone(fullText)`：
  1. `functionGemmaAdapter.parseToolCalls(fullText)`。
  2. 若有调用：执行**全部**解析到的调用（支持 Parallel）→ 每个 `callback.onToolCall(tc)` / 执行 / `callback.onToolResult(tc.id(), result)` → `history.add(toolResult)` 逐个回喂 → `functionGemmaAdapter.buildPrompt(history, toolDecls)` 重建 → 递归 `round+1`。
  3. 若无调用：该轮文本即最终答案 → `callback.onComplete(fullText, …)`。
- **删除**现有 `chatFunctionGemmaNative` 里"只取 first + `generateFinalAnswer`"的单步收尾逻辑。
- 中间轮（有调用）抑制 token 流式；最终轮（无调用）正常流式。
- 工具声明 `toolDecls` 在循环外构建一次（不每轮重建声明内容，只重建 prompt）。

### 5.2 `FunctionGemmaAdapter` — 协议加固

- `buildToolDeclarations`：
  - 输出 `enum:[…]`（当参数有 enum 值时）。
  - 压缩冗余空白，减少 token 占用。
- `parseArgs` / 解析正则放宽：
  - 容忍参数块内嵌 `}`（栈式或匹配包裹 🪙 字符串后再切分）。
  - 容忍漏 🪙（裸字符串也能取值）。
  - 枚举值大小写不敏感匹配。
  - 解析失败回退原字符串值（保留现有 graceful 行为）。
- token 严格对齐官方示例：`<start_of_turn>developer` + system trigger + `<start_function_declaration>…<end_function_declaration>`；`<start_function_call>call:name{…}<end_function_call>`；`<start_function_response>response:name{…}<end_function_response>`；`<start_of_turn>model`。

### 5.3 `AiToolParam` — enum 支持

- record 增字段 `List<String> enumValues`（默认空列表）。
- 新增 `of(name, type, description, required, enumValues)` 重载；保留现有 `of(...)` 重载（enumValues 默认空）。
- `ToolSchemaBuilder.buildJsonSchema` 同步输出 `enum`（OpenAI/Anthropic 路径也受益）。

### 5.4 Excel 工具描述增强（P2）

- 6 个 Excel 工具：英文描述 + 语义关键词 + 1 行示例调用。
- `excel_configure.mode`、`excel_complex_config.action` 等参数标 enum。
- 示例：`excel_configure` 描述含 `mode ∈ {BY_SHEET, BY_COLUMN, COMPLEX}`，并附示例 `excel_configure{mode:BY_COLUMN, splitSheet:"Sheet1", splitColumn:"部门"}`。

### 5.5 离线 NL 归一化层（新组件，离线中文解法）

- 新类 `OfflineNlNormalizer`（`fan.summer.ai.tools`）：
  - 本地词典（`src/main/resources/ai/nl-normalizer.properties`）：CN→EN 动作动词（拆分→split、分析→analyze、合并→merge、转换→convert、查询→query、取消→cancel）+ 领域关键词（列→column、表→sheet、目录→directory）。
  - 标识符（文件路径、sheet 名、列名）原样透传。
  - 输出"动作部分英文、标识符原样"的规范化 NL。
  - 纯本地资源，零额外模型，离线。
- 在 `chatFunctionGemmaNative` 入口对**最新 user message** 归一化后再构建 prompt。
- **诚实边界**：覆盖=词典内；未覆盖词原样透传，命中率取决于模型基线。

### 5.6 ctx 提到 8192

- `loadModel` 中 `ModelParams.ctxLength(4096)` → **8192**（FunctionGemma 检测分支；模型支持 32K，给声明+多轮历史留余量）。

## 6. 错误处理

- **工具报错**（如 `"Call excel_analyze first"`）：作为 `function_response`（result 含错误文本）回喂，循环继续让模型自纠；连续失败计数超阈值（如 2 次）退出并 `onComplete` 回显最后错误。
- **解析失败**（无合法 call 且本轮本应有调用）：重试 1 次（提高 temperature 或追加「Output one function call.」提示）→ 仍失败则 `onComplete` 原文 + 「无法解析为工具调用」提示。
- **超过 MAX_TOOL_ROUNDS 仍未 execute**：`onComplete` 回显已完成步骤 + 提示用户确认下一步。

## 7. 测试

- **`FunctionGemmaAdapter`**（纯单测，无模型）：
  - 解析：含 🪙、含 enum、多调用（parallel）、缺省参数、参数值含 `,`/`}`。
  - `buildToolDeclarations`：enum 输出正确。
  - `buildPrompt`：developer/user/model/response 各 turn token 齐全。
- **`OfflineNlNormalizer`**（纯单测）：词典映射、标识符透传、未覆盖词回退。
- **`AiServiceImpl` 循环**（mock `NativeWorkerClient`）：analyze→configure→execute 三轮递归、并行执行、错误回喂自纠、MAX_TOOL_ROUNDS 上限、最终答案流式。
- **端到端**：真实 FunctionGemma GGUF（英文 prompt）跑 Excel 全流程。

## 8. 实现分阶段

- **Phase 1（跑通 Excel 英文全流程）**：5.1（多轮循环）+ 5.2（协议加固）+ 5.3（enum）+ 5.4（Excel 描述）+ 5.6（ctx）。
- **Phase 1b（离线中文）**：5.5（OfflineNlNormalizer）。
- **延后**：停止序列（native 侧）、微调（C 方案）。

## 9. 风险

- **模型基线准确率**：即便链路与循环修好，单调用 ~60% 意味着偶发选错工具/参数——属模型能力上限，非代码 bug。长期靠微调。
- **prompt 格式保真**：手写 token 需与官方示例精确对齐；偏差会进一步降低准确率。单测覆盖 token 序列。
- **离线归一化覆盖**：词典外中文词不被翻译，可能影响命中率。文档与 UI 注明。
