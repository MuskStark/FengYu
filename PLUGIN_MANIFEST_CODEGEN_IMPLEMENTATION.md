# 插件清单代码生成实施方案

> 状态：待实施<br>
> 日期：2026-08-21<br>
> 适用范围：FengYu 4.0.0、Plugin Toolchain 2、manifest schema v2<br>
> 目标：减少大型插件 `manifest.json` 的手写体积，同时保持安装包契约、权限审查与 Flow 行为可验证

## 1. 结论

采用“代码生成 RPC 契约、显式配置 Flow 语义、构建期合并、包内单清单”的混合方案：

```text
Java Contract/DTO ──注解处理──> RPC/AI Tool 中间模型 ─┐
                                                      │
manifest.base.json ────────────────────────────────────┼─> Manifest Compiler
                                                      │        │
manifest/flow-nodes.json ──────────────────────────────┤        ├─> 校验/预览
manifest/i18n/*.json ──────────────────────────────────┘        │
                                                               └─> .fyp/manifest.json
```

最终 `.fyp` 根目录仍然只有一个完整的 `manifest.json`。宿主、市场、安装器和运行时不支持
`include`、外部 `$ref` 或动态执行配置代码，因而现有安装边界不被削弱。

本方案明确保留两类无法从普通方法签名可靠推断的 Flow 能力：

1. **输入透传输出**：当节点调用某个方法后，下游仍需要本节点的原始输入时，由插件作者显式声明
   `outputs[].valueFrom.source = "input"`。未声明时绝不自动回显全部输入。
2. **编辑期上下文候选数据**：例如 Excel 节点先调用 `analyze` 分析工作簿，再把 sheet、列名作为
   当前节点配置项的待选数据。该能力继续使用显式的 `context`、`feeds` 和
   `optionsFromContext` 描述，不把分析调用隐式编译成工作流执行步骤。

## 2. 背景与当前基线

当前 manifest v2 已经解决了一部分重复问题：

- RPC 参数与返回值 Schema 只声明在 `rpc.methods`；
- `aiTools` 通过 `method` 引用 RPC，不再复制 Schema；
- CLI 根据 manifest 生成 TypeScript RPC 客户端和 Java Input/Output record；
- Flow 描述符已经支持 `context`、`feeds`、`optionsFromContext`；
- Excel 插件已经用 `analyze` 结果为复杂拆分节点提供 sheet/列候选数据。

但当前方向仍然是：

```text
manifest.json -> Java DTO + TypeScript Client
```

大型插件的 RPC 数量较多时，方法描述、Input/Output Schema、AI Tool 和 Flow 展示配置仍集中在一个
JSON 文件中。代码与契约的修改入口分离，也容易产生“处理器签名已变、manifest 尚未同步”的漂移。

代码优先模式将 RPC 部分反转为：

```text
Java Contract/DTO -> RPC Schema -> TypeScript Client
```

同一个插件不能同时手写和生成同一段 RPC 契约。代码优先模式下，Java Contract 是 RPC 的唯一事实来源；
`manifest.base.json` 是包身份与权限的唯一事实来源；Flow overlay 是画布交互语义的唯一事实来源。

## 3. 目标与非目标

### 3.1 目标

- 大幅减少大型插件手写 JSON 的长度和重复内容。
- 从 Java 方法、record 和注解生成 `rpc.methods`、Input/Output Schema 与可选 `aiTools`。
- 保持权限、资源上限、兼容范围等安全字段显式可审查。
- 保持 manifest v2 作为 `.fyp` 内唯一的运行时契约。
- 支持 Flow 显式输入透传，并让透传值成为真实、可持久化、可被下游引用的节点输出。
- 支持 Excel 风格的编辑期分析、平面候选集、按 sheet 分组的列候选集。
- 生成结果稳定、可复现，错误能够定位回 Java 源码或 overlay 文件。
- 旧插件无需迁移即可继续使用现有 manifest-first 模式。

### 3.2 非目标

- 不从 Worker 代码调用情况自动推断 `permissions`。
- 不启动 Worker 或执行插件业务代码来生成 manifest。
- 不在宿主安装或运行时解析多个清单分片。
- 不允许远程 `$ref`、远程 include 或任意构建命令数组。
- 不自动把所有节点输入复制到输出；输入透传必须逐字段显式声明。
- 不把编辑期 `analyze` 调用自动加入运行 DAG；运行时只使用用户最终保存的配置值。
- 第一阶段不为 Python、Go 提供代码优先生成器；它们继续使用 manifest-first，后续再共享同一中间模型。

## 4. 源码布局

代码优先插件采用以下约定目录：

```text
my-plugin/
├── manifest.base.json
├── manifest/
│   ├── flow-nodes.json
│   └── i18n/
│       ├── en.json
│       └── zh.json
├── src/main/java/.../contract/
│   ├── PluginContract.java
│   ├── AnalyzeInput.java
│   └── AnalyzeOutput.java
├── ui-src/
└── pom.xml
```

构建产物写入构建目录，不作为第二份手写来源：

```text
target/fengyu-contract/contract.json
target/fengyu-manifest/manifest.json
```

规则：

- 存在 `manifest.base.json` 时进入 code-first 模式；
- 只有 `manifest.json` 时保持现有 manifest-first 模式；
- 两者同时存在时 `fengyu check/build` 直接失败，避免来源不明确；
- `manifest.base.json` 不允许声明 `rpc`、`aiTools` 或 `flowNodes`；
- `manifest/flow-nodes.json` 只允许声明 Flow 展示与执行映射；
- `.fyp` staging 阶段只复制合并后的文件，并将其命名为根级 `manifest.json`。

`manifest.base.json` 示例：

```json
{
  "schemaVersion": 2,
  "id": "fan.summer.excel",
  "name": "Excel Splitter",
  "description": "Split Excel workbooks by sheet, column value, or complex rules",
  "version": "4.0.0",
  "author": "FengYu",
  "icon": "file-excel",
  "category": "file",
  "ui": { "entry": "ui/index.html" },
  "backend": {
    "runtime": "java",
    "protocolVersion": 1,
    "callTimeoutSeconds": 60
  },
  "permissions": ["files.read", "files.write"],
  "official": true
}
```

## 5. Java 代码优先契约

### 5.1 模块归属

- `toolchain/sdk-java`：提供 SOURCE retention 的契约注解，不引入新的运行时框架。
- `toolchain/devkit-java`：提供 JSR 269 注解处理器和构建期校验器。
- 官方插件和脚手架：在 Maven `generate-resources` 阶段以 `proc:only` 方式运行处理器。
- 处理器只读取编译期类型模型，不反射加载、不实例化 Contract、不执行 Worker。

把处理器放在 DevKit 而不是 Worker SDK 的运行路径中，可以避免把生成器实现打入 Worker shaded JAR。
SDK 与 DevKit 继续使用同一个独立的 Plugin Toolchain 版本线。

### 5.2 注解模型

建议新增以下注解：

| 注解 | 目标 | 用途 |
| --- | --- | --- |
| `@FengYuContract` | interface | 标识一个插件 RPC 契约入口 |
| `@FengYuRpc` | method | 方法名、描述、超时 |
| `@FengYuAiTool` | method | AI 工具名、描述、effect、幂等性、超时 |
| `@FengYuField` | record component | 字段描述、标题、必填、nullable、范围、枚举、Flow 提示 |
| `@FengYuSensitive` | record component | 标识不得记录或透传为 Flow 输出的敏感输入 |

示例：

```java
@FengYuContract
public interface ExcelContract {

    @FengYuRpc(
        name = "analyze",
        description = "Analyze a workbook and return sheets and columns.",
        timeoutSeconds = 30
    )
    AnalyzeOutput analyze(AnalyzeInput input, RpcContext context);

    @FengYuRpc(
        name = "excel_complex_config",
        description = "Add, list, or clear complex split rules.",
        timeoutSeconds = 60
    )
    @FengYuAiTool(
        name = "excel_complex_config",
        description = "Configure complex Excel split rules.",
        effect = ToolEffect.WRITE
    )
    ExcelComplexConfigOutput configure(
        ExcelComplexConfigInput input,
        RpcContext context
    );
}
```

DTO 示例：

```java
public record AnalyzeInput(
    @FengYuField(
        description = "Resolved path of the granted workbook.",
        required = true
    )
    String sourceFile,

    @FengYuField(
        description = "Canvas-isolated analysis session.",
        nullable = true
    )
    String session
) {}

public record AnalyzeOutput(
    @FengYuField(required = true)
    boolean success,

    @FengYuField(required = true)
    String summary,

    List<SheetInfo> sheets
) {}
```

代码优先模式下 Input/Output record 由插件源码持有，不再由 manifest 反向生成 Java DTO。处理器仍生成：

- `target/fengyu-contract/contract.json`；
- `PluginMethods.java` 方法名常量；
- 必要的契约索引。

CLI 在合并出最终 manifest 后继续生成 TypeScript RPC 客户端。这样不存在
“manifest 生成 Java DTO，Java DTO 又生成 manifest”的循环。

### 5.3 Java 类型到 JSON Schema 的映射

| Java 类型 | JSON Schema |
| --- | --- |
| `String`、enum | `string`、可选 `enum` |
| `byte/short/int/long` 及包装类 | `integer` |
| `float/double/BigDecimal` 及包装类 | `number` |
| `boolean/Boolean` | `boolean` |
| `List<T>` | `array` + `items` |
| record | `object` + `properties` |

约束：

- 不支持裸 `Map<String, Object>`、未界定泛型、递归 DTO 和不封闭多态类型；遇到时直接失败。
- 不允许静默退化为无约束 `object`。
- primitive 默认必填；引用类型是否必填由 `@FengYuField(required = true)` 明确决定。
- `nullable` 与“可省略”分开建模。
- enum 的 wire value 必须稳定；不以显示文案充当 wire value。
- 生成的 Schema 只能使用当前 manifest v2 支持的 JSON-Schema 子集。

## 6. Manifest Compiler 与合并规则

### 6.1 中间模型

所有语言生成器最终都必须输出同一个语言无关 IR：

```json
{
  "formatVersion": 1,
  "pluginId": "fan.summer.excel",
  "rpc": { "methods": {} },
  "aiTools": [],
  "origins": {
    "rpc.methods.analyze": "ExcelContract.java:12"
  }
}
```

`origins` 只用于错误定位，不进入最终 `.fyp/manifest.json`。

### 6.2 合并顺序

```text
manifest.base.json
  + generated contract.json
  + manifest/flow-nodes.json
  + manifest/i18n/*.json
  = final manifest.json
```

各来源拥有互不重叠的字段：

- Base：身份、版本、UI、Backend、权限、资源、兼容范围、主页、official。
- Generated contract：`rpc`、`aiTools`。
- Flow overlay：`flowNodes`。
- Locale files：`i18n`。

禁止跨来源覆盖。任何越界字段或重复 key 都直接报错，不采用“后者覆盖前者”的隐式规则。

### 6.3 确定性输出

- 顶层字段使用固定顺序。
- RPC 方法、Schema properties、AI Tools、Flow Nodes 按稳定键排序。
- JSON 使用 UTF-8、两空格缩进和结尾换行。
- 相同源码连续生成必须字节级一致。
- `fengyu check` 比较生成结果和上一次预览结果时报告 drift，但不改插件源码。

## 7. Flow 输入透传输出

### 7.1 使用场景

一些方法只返回执行状态或摘要，但下游节点仍需要本次调用的某个输入。例如：

- 节点 A 接收 `filePath`，执行登记或分析；
- 节点 A 的 Worker 返回 `{success, summary}`；
- 节点 B 仍需使用节点 A 实际解析后的 `filePath`。

不能要求每个 Worker 为画布手工回显输入，也不能默认回显所有参数，因为这会：

- 扩大运行历史与日志中的数据面；
- 意外泄露密码、token、邮件正文等敏感输入；
- 让方法的业务返回模型被 Flow UI 需求污染。

因此新增显式输出来源 `FlowNodeOutput.valueFrom`。

### 7.2 Manifest 形态

在 `toolchain/spec/manifest.schema.json` 的 `flowNodeOutput` 中新增：

```json
{
  "name": "sourceFile",
  "title": "源工作簿",
  "type": "string",
  "valueFrom": {
    "source": "input",
    "path": "filePath"
  },
  "help": "沿用本节点实际接收的工作簿路径。"
}
```

定义：

```text
valueFrom.source = input | result
valueFrom.path   = dotted path，支持 properties 与 [N] 数组索引
```

约定：

- `valueFrom` 省略时，保持现状，输出值来自 Worker result 中与 `name` 同名的字段。
- `source = input` 时，从完成模板解析后的实际调用参数中取值，而不是从保存时的原始模板取值。
- `source = result` 用于把 Worker 的嵌套结果投影为一个顶层命名输出；第一阶段可以一并实现，
  但输入透传是必须交付项。
- `name` 是下游稳定引用名，不必与输入字段名相同。

Flow overlay 示例：

```json
{
  "flowNodes": [
    {
      "tool": "excel_complex_config",
      "outputs": [
        {
          "name": "sourceFile",
          "title": "源工作簿",
          "type": "string",
          "valueFrom": {
            "source": "input",
            "path": "filePath"
          }
        },
        {
          "name": "result",
          "title": "配置结果",
          "type": "object"
        }
      ]
    }
  ]
}
```

下游仍使用统一引用语法：

```text
编辑态：{{node.configure.result.sourceFile}}
持久态：{{steps.0.result.sourceFile}}
```

### 7.3 编译与运行模型

Flow Builder 保存流程时，把 `valueFrom` 编译进对应 `AgentStep` 的不可变快照：

```json
{
  "outputBindings": [
    {
      "name": "sourceFile",
      "source": "input",
      "path": "filePath"
    }
  ]
}
```

将映射快照存入 Plan，而不是每次运行重新读取当前插件 manifest，原因是：

- 已保存流程不会因插件展示配置的小版本变化而静默改变数据语义；
- 单步运行、历史恢复、发布版本执行使用同一份映射；
- 后端可以在执行前验证完整映射，而不是依赖前端行为。

`AgentRunner` 的执行顺序调整为：

```text
解析上游引用 -> effective args
              -> Guard/审批
              -> 调用工具或读取 pinned raw result
              -> 解析 raw result object
              -> 按 outputBindings 物化派生输出
              -> 保存/广播 effective result
              -> 下游从 effective result 取值
```

物化规则：

1. Worker 返回值必须符合现有顶层 object outputSchema；非 JSON object 视为契约错误。
2. 复制原始结果对象，再加入显式映射字段，不修改 Worker 原始响应实例。
3. 映射目标与 Worker 实际返回字段冲突时失败，不允许静默覆盖。
4. `pinnedResult` 代表模拟的 Worker 原始结果；仍执行相同的输出物化，保证真实运行与单步调试一致。
5. 物化后的结果进入 `StepExecution.result`、SSE、运行历史和下游引用。
6. 恢复旧运行时，已经持久化的 effective result 直接使用，不再次物化。

### 7.4 安全与校验

构建期必须检查：

- `valueFrom.source=input` 的 path 存在于对应 RPC `inputSchema`。
- `valueFrom.source=result` 的 path 存在于对应 RPC `outputSchema`。
- 源类型与声明输出类型兼容。
- 映射目标名不与 outputSchema 顶层字段冲突。
- 同一 Flow Node 中输出名唯一。
- 标记 `@FengYuSensitive` / `x-fengyu-sensitive: true` 的字段禁止作为透传源。
- 对 `password`、`secret`、`token`、`credential` 等明显敏感命名保留兜底 lint；lint 不能替代显式标记。

安装期宿主对最终 manifest 重复以上结构和引用校验，不能只信任 CLI。

## 8. Excel 风格的编辑期分析候选数据

### 8.1 语义

编辑期上下文数据源解决的是“配置当前节点需要先理解一个输入资源”的问题。例如：

```text
filePath
  -> 点击分析
  -> 调用 analyze(sourceFile=filePath, session=canvas-<nodeId>)
  -> sheets: [华东, 华南]
  -> columns: {华东: [城市, 金额], 华南: [城市, 数量]}
  -> sheetName/columnName 输入框获得候选项
```

分析结果只用于编辑器候选项，不作为执行结果，不自动写入 Plan，也不自动创建一条 DAG 边。用户最后
选择或填写的 sheet/列值才进入节点 args，并在正式运行时交给实际工具方法。

### 8.2 显式 Flow 配置

继续沿用并标准化当前 `context` 契约：

```json
{
  "flowNodes": [
    {
      "tool": "excel_complex_config",
      "label": "Excel 复杂拆分",
      "inputs": [
        {
          "name": "filePath",
          "widget": "text",
          "title": "文件路径",
          "type": "string",
          "required": true,
          "context": {
            "method": "analyze",
            "params": {
              "sourceFile": "{{value}}"
            },
            "sessionScope": "node",
            "feeds": {
              "sheets": {
                "list": "sheets",
                "item": "name"
              },
              "columns": {
                "list": "sheets",
                "key": "name",
                "items": "columns",
                "itemField": "header"
              }
            }
          }
        },
        {
          "name": "entries",
          "widget": "rows",
          "type": "array",
          "fields": [
            {
              "name": "sheetName",
              "widget": "text",
              "optionsFromContext": {
                "set": "sheets"
              }
            },
            {
              "name": "columnName",
              "widget": "text",
              "optionsFromContext": {
                "set": "columns",
                "keyedBy": "sheetName"
              }
            }
          ]
        }
      ]
    }
  ]
}
```

这部分不从 Java 方法签名自动推断。生成器只能知道 `analyze` 接收什么、返回什么，无法知道：

- 哪个输入变化时应该触发分析；
- 返回数组中的哪个字段是显示值；
- 列候选需要按哪个 sheet 分组；
- 哪些候选仅用于编辑期而不是正式执行。

因此它必须保留为显式、可审查的 Flow overlay。

### 8.3 构建期校验

在现有 `validateFlowNodes` 基础上新增：

- `context.method` 必须存在于同一插件的 `rpc.methods`，但不要求它是 `aiTools`。
- `context.params` 的 key 必须存在于 context 方法的 `inputSchema`。
- `{{value}}` 的来源类型必须与目标参数类型兼容。
- `sessionScope=node` 时，context 方法必须接受 `session`，或显式允许额外参数。
- 每个 feed 的 `list/item/key/items/itemField` 路径必须能在 context 方法的 `outputSchema` 中解析。
- `optionsFromContext.set` 必须引用当前节点某个 context 声明的 feed。
- `keyedBy` 必须引用同一 rows item 中的字段，或当前节点中明确允许的兄弟输入。
- 平面 feed 必须产出标量数组；keyed feed 必须产出 `Record<string, scalar[]>` 等价结构。
- context 调用不得引用另一个插件的方法。

### 8.4 编辑器状态与并发要求

前端实现必须满足：

1. context 缓存键至少包含 `nodeId + method + sourceValue`，不能跨节点复用会话数据。
2. sourceValue 改变后立即清空旧 feeds，并标记依赖候选值为“需要重新验证”。
3. 多次快速分析使用请求序号或 AbortController；旧响应不得覆盖新输入的结果。
4. 分析失败显示在触发输入附近，保留用户已填写值，但不继续展示过期候选项。
5. `sessionScope=node` 固定映射为 `canvas-<nodeId>`，编辑期分析不污染聊天或正式工作流 session。
6. 候选数据可以辅助输入，但不能强制把已有自定义值清空；是否限定 enum 由显式配置决定。
7. 分析调用继续通过宿主 Plugin Runtime RPC 通道，不能由 iframe 或 Flow UI 直接访问文件系统。

## 9. CLI 生命周期

### 9.1 新增命令

新增：

```bash
fengyu generate <plugin-path>
```

职责：

1. 检测 manifest-first 或 code-first 模式。
2. code-first Java 插件运行 Maven `generate-resources` 契约处理阶段。
3. 读取 IR、Base、Flow overlay、i18n。
4. 合并并执行完整 Schema 与语义校验。
5. 写入 `target/fengyu-manifest/manifest.json`。
6. 生成 TypeScript RPC 客户端和方法常量。
7. 输出生成摘要和来源映射，不修改手写源文件。

### 9.2 `fengyu check`

- manifest-first：保持现有行为和 drift 检查。
- code-first：在构建目录生成临时 IR，合并后校验，不写手写文件。
- 检查最终 manifest、RPC/AI Tool 引用、Flow overlay、context feeds、输入透传、生成代码 drift。
- 错误同时打印最终 JSON path 与源位置，例如：

```text
manifest.flowNodes[excel_complex_config].outputs[sourceFile].valueFrom.path
  -> unknown input path: filePath
  -> manifest/flow-nodes.json:42
  -> RPC source: ExcelContract.java:28
```

### 9.3 `fengyu build`

code-first 顺序调整为：

```text
contract generate
-> manifest merge/validate
-> TypeScript client generate
-> UI test/build
-> Worker test/build
-> staging
-> staged manifest validate
-> .fyp package + sha256
```

任何一步失败都不能留下部分 `.fyp`。最终归档再次使用宿主同构规则校验根级 `manifest.json`。

### 9.4 `fengyu dev`

- 启动前执行一次 generate。
- 监听 Contract/DTO、Base、Flow overlay、i18n 变化。
- 变更后重新生成 manifest 与 TypeScript client。
- Contract 生成失败时保持上一次可运行构建，但 Dev UI 必须显示错误横幅，不得悄悄使用旧契约作为成功证据。

## 10. 代码改动清单

### 10.1 Toolchain Spec

涉及：

- `toolchain/spec/manifest.schema.json`
- `toolchain/spec/flow-node.schema.json`
- 对应 fixtures 与 schema tests

新增：

- `flowNodeOutput.valueFrom`；
- JSON Schema 字段提示 `x-fengyu-sensitive`；
- 对 source/path 的结构约束。

语义路径校验仍由 CLI 和宿主代码完成，JSON Schema 只负责对象形状。

### 10.2 Java SDK / DevKit

涉及：

- `toolchain/sdk-java/src/main/java/fan/summer/fengyu/sdk/contract/`
- `toolchain/devkit-java/src/main/java/fan/summer/fengyu/devkit/contract/`

实现：

- 契约注解；
- Java 类型解析；
- Schema/IR 生成；
- 源位置索引；
- Maven proc-only 集成；
- 对不支持类型、重复方法、重复工具名的编译错误。

### 10.3 CLI

涉及：

- `toolchain/cli/src/args.mjs`
- `toolchain/cli/src/cli.mjs`
- `toolchain/cli/src/build.mjs`
- `toolchain/cli/src/generate.mjs`
- `toolchain/cli/src/manifest.mjs`
- `toolchain/cli/src/staging.mjs`
- 新增 `contract.mjs`、`manifest-source.mjs`、`manifest-compiler.mjs`

实现：

- 双模式发现；
- IR 读取与来源跟踪；
- 非覆盖式合并；
- 最终 manifest 确定性序列化；
- Flow `valueFrom` 与 context 的跨 Schema 校验；
- code-first 下关闭现有 Java DTO 反向生成，只保留 TS client 与方法常量生成。

### 10.4 Frontend Flow Builder

涉及：

- `frontend/src/api/types.ts`
- `frontend/src/components/agent/workflow.ts`
- `frontend/src/components/agent/FlowNodeInspector.vue`
- `frontend/src/components/agent/optionSource.ts`
- `frontend/src/views/FlowBuilder.vue`

实现：

- `FlowNodeOutputValueFrom` 类型；
- 变量树把输入透传输出显示为普通命名输出，并注明“来自本节点输入”；
- Flow 编译时生成 `AgentStep.outputBindings`；
- context 缓存失效、过期响应抑制、错误状态与依赖候选校验；
- 保存前校验 output binding 与节点工具契约一致。

### 10.5 Backend

涉及：

- `FengYu/src/main/java/fan/summer/fengyu/ai/agent/AgentStep.java`
- `FengYu/src/main/java/fan/summer/fengyu/ai/agent/AgentRunner.java`
- `FengYu/src/main/java/fan/summer/fengyu/ai/workflow/WorkflowService.java`
- `FengYu/src/main/java/fan/summer/fengyu/plugin/market/PluginPackageService.java`
- `FengYu/src/main/java/fan/summer/fengyu/plugin/market/PluginManifest.java`

实现：

- `AgentStep.OutputBinding` 与向后兼容构造器；
- Plan 保存/发布/恢复时保留绑定快照；
- 工具调用后物化 effective result；
- pinned、重试、并行 DAG、单步执行和恢复运行使用同一物化函数；
- 安装期复核 Flow source/path、敏感字段和 context feed 路径。

### 10.6 脚手架与文档

涉及：

- Java 插件模板新增 code-first Contract、DTO 与 Maven proc-only 配置；
- Python/Go 模板暂时保持 manifest-first；
- `docs/en/plugins/` 与 `docs/zh/plugins/` 同步更新 manifest、CLI、Flow nodes 文档；
- `fengyu-plugin-dev` Skill 在真正实现后更新验证流程。

公开文档必须在代码落地时英中结构对齐；本实施方案本身不代表功能已经可用。

## 11. 兼容性与迁移

### 11.1 双模式共存

- schemaVersion 保持 `2`，因为最终安装契约没有被拆分；新增字段均为可选。
- 旧 manifest-first 插件完全不迁移也可继续构建。
- 宿主不认识 `valueFrom` 的旧版本会因 `engines.fengyu` 约束被阻止安装使用该能力的新插件。
- code-first 是项目源布局变化，不是 `.fyp` 格式变化。

### 11.2 官方插件迁移顺序

1. `plugin-markdown`：验证最小 code-first RPC、TS client 与打包链路。
2. `plugin-excel`：验证嵌套 DTO、context feeds、keyed columns 和输入透传。
3. `plugin-offlinepython`：验证大量方法、Job Schema 与复杂结果。
4. `plugin-email`：最后迁移，验证最大 manifest、数据库权限和敏感字段约束。

每个插件单独迁移，禁止一次性机械重写四个插件。迁移时比较旧/新最终 manifest 的语义差异；除预期排序、
来源字段和明确新增的 Flow 能力外，其余差异均视为阻断问题。

### 11.3 迁移工具

后续可提供一次性辅助命令：

```bash
fengyu migrate manifest-codegen <plugin-path>
```

它只负责：

- 从现有 manifest 提取 `manifest.base.json`、Flow overlay、i18n；
- 为 RPC 生成 Contract/DTO 草稿；
- 输出迁移报告。

它不得删除原 manifest。插件作者确认生成结果、完成代码迁移后，再手动切换模式。

## 12. 测试计划

### 12.1 Java 处理器

- primitive、包装类、record、嵌套 record、List、enum 映射。
- required 与 nullable 分离。
- 重复 RPC/AI Tool 名失败。
- Map、未界定泛型、递归 DTO、多态 DTO 失败。
- 同一源码连续生成字节一致。
- 源行号能够映射到错误。

### 12.2 CLI

- manifest-first 行为不回归。
- Base 与 manifest 同时存在时失败。
- 越界字段、重复字段和跨来源覆盖失败。
- 生成 manifest 通过 schema v2。
- `valueFrom` 正常、未知 path、类型不兼容、目标冲突、敏感输入分别覆盖。
- context method、params、feeds、keyedBy 的成功和失败用例。
- staging 内只有一个根级 manifest。
- 两次 build 的 manifest 字节一致。

### 12.3 Frontend

- 输入透传输出出现在变量树并可绑定下游。
- 编辑态引用正确编译为 `steps.N.result.*`。
- filePath 改变后清空旧 Excel feeds。
- 慢的旧 analyze 响应不会覆盖新的结果。
- sheet 候选为平面集合；column 候选按 sheet 过滤；未选 sheet 时可展示去重并集。
- 分析失败保留输入、清除旧候选并显示可重试错误。
- 保存/重新打开流程后 outputBindings 不丢失。

### 12.4 Backend

- resolved args 而不是模板字符串被透传。
- 普通工具结果与透传字段正确合并。
- 运行时未声明字段冲突失败且不覆盖。
- 敏感字段防御性拒绝。
- pinned result 仍执行透传物化。
- retry 每次使用相同 effective args，最终只物化一次。
- 并行步骤的 args/result 不串线。
- 历史恢复不重复物化。
- 旧 Plan 缺少 outputBindings 时保持原行为。

### 12.5 端到端

至少覆盖两条真实链路：

```text
Excel 文件 -> analyze 候选 -> complex config -> 透传 sourceFile -> 下游节点
Excel 文件 -> complex config -> execute -> outputDir/files -> Email 批量发送
```

验证源码、生成 manifest、`.fyp` 内 manifest、宿主安装后的工具描述符完全一致。

## 13. 分阶段实施

### 阶段 0：契约冻结

- 为当前 manifest 生成、Flow context 和 AgentRunner 结果解析补充基线测试。
- 固定 IR formatVersion 1、`valueFrom` 和 `outputBindings` 字段名。
- 不改现有插件。

### 阶段 1：Manifest Compiler

- 实现 Base/IR/Flow/i18n 的非覆盖式合并。
- 实现确定性输出和 source map。
- CLI 双模式发现，但暂不迁移官方插件。

### 阶段 2：Java 注解处理器

- 在 SDK/DevKit 落地注解和处理器。
- CLI 增加 `generate`。
- 迁移 `plugin-markdown` 验证最短链路。

### 阶段 3：Flow 输入透传

- 扩展 schema、前端类型、AgentStep 与 AgentRunner。
- 完成安全、冲突、pinned、恢复测试。
- 用 Excel 配置节点验证真实下游引用。

### 阶段 4：Context 候选强化

- 把当前 Excel context 能力纳入 code-first 合并与强校验。
- 补齐缓存失效、并发响应和 keyed feed 校验。
- 完成 Excel 端到端测试。

### 阶段 5：官方插件渐进迁移

- 按 Markdown -> Excel -> Offline Python -> Email 顺序迁移。
- 每个插件分别构建、检查 `.fyp` 与 sha256。
- 更新英中文档、CLI 模板和 Skill。

## 14. 验收标准

全部满足后才视为完成：

- `.fyp` 仍只包含一个根级、完整、可独立校验的 `manifest.json`。
- 权限、资源限制、插件身份没有任何自动推断。
- Java Contract 是 RPC 唯一来源，不存在双向生成环。
- 复杂插件不再手写 `rpc.methods` 的大段 JSON。
- 输入只在 Flow overlay 显式声明后才会成为输出。
- 被透传的是运行时解析后的 effective input，可由下游稳定引用。
- 敏感输入不能被透传或进入运行历史。
- Excel analyze 能同时提供 sheet 平面候选和按 sheet 分组的列候选。
- 编辑期分析不会成为运行步骤，也不会污染正式运行 session。
- manifest-first 旧插件与旧工作流保持兼容。
- `fengyu check`、宿主安装校验和运行时防御形成三层一致校验。
- 同一源码连续构建产生字节级一致的 manifest。

## 15. 风险与控制

| 风险 | 控制措施 |
| --- | --- |
| 双向生成导致来源冲突 | code-first 禁止手写 rpc/aiTools；manifest-first 保持旧模式 |
| 注解无法表达复杂 Flow UX | Flow 使用独立显式 overlay，不强行从方法签名推断 |
| 输入透传泄露秘密 | 显式逐字段开启、敏感标记、名称 lint、CLI/宿主双重拒绝 |
| 插件升级改变已保存流程语义 | `outputBindings` 编译进 AgentStep 快照 |
| analyze 结果过期或串节点 | node session、缓存键、请求序号、源值变化即失效 |
| 处理器偷偷执行业务代码 | 仅使用 JSR 269 类型模型，不反射、不实例化 Contract |
| 多语言生成器行为漂移 | 所有生成器只输出统一 IR，最终规则集中在 Manifest Compiler |
| 生成成功但归档内容错误 | staging 后再次校验最终 manifest，再原子写入 `.fyp` |
