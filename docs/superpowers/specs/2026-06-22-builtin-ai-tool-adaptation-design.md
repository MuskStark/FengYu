# 内置 AI 工具适配现行 ChatBackend 架构设计

- **日期**:2026-06-22
- **状态**:已设计,待实施
- **分支**:v3.1.0
- **背景调研**:`docs/superpowers/specs/2026-06-22-qwen3-4b-toolcall-design.md`(Qwen3-4B 已落地为本地唯一 tool-calling 模型);`AiServiceProvider` 已暴露全局 `ChatBackend`;`AiToolParam` 已支持 `enumValues`(commit `13b149b`);现有 16 个内置 AI 工具未对齐这些新能力。

## 1. 目标与约束

### 目标
把现有 16 个内置 AI 工具从"由 `BuiltinAiToolRegistrar` 集中注册、用旧 schema 与描述风格"的形态,**重构为三个层次的统一**:

1. **归属层**:工具由其宿主插件通过 `SwissKitJPlugin.aiTools()` 自带 —— 删除 `BuiltinAiToolRegistrar`。
2. **能力层**:每个工具声明自己支持 cloud / local / 两者,local 模式下自动过滤隐藏,描述也走双轨。
3. **契约层**:所有工具的 schema(`enum`、`string[]`)、描述风格、返回 JSON 结构统一拉齐。

### 硬约束(已与用户确认)
1. **外部插件二进制兼容**:`SwissKitJPlugin` 与 `AiTool` 的所有新方法必须 `default`,MuskStark/SwissKiJ-Plugin 仓库的现有 JAR 不需要重新编译。
2. **Local 默认 Qwen3-4B**:工具集分桶必须考虑 4B 模型的限制 —— 隐藏能力过强、参数过杂、描述过长的工具。
3. **Commit 切分**:API 基础设施 / 工具迁移 / 契约拉齐必须分 3 个 commit,每个都能独立编译、独立运行。

### 非目标(YAGNI)
- **不引入"工具结果摘要字段"**(原 C 方案可选子项)。`AiToolResult` record 形状保持 `(boolean, String)`;约定在 output 字符串内部加 `summary` 字段已足够,不动 record。
- **不为外部插件仓库(MuskStark/SwissKiJ-Plugin)同步迁移**。能力就位,等其按需采纳。
- **不做模型调用 E2E 测试**。CI 不可行,验证走手动 smoke checklist(§6)。
- **不写迁移指南**。改动全部 default + 向后兼容,外部插件零成本。
- **不改 AiToolResult record 形状**。

## 2. 背景与现状

### 现行 AI 架构(2026-06-22 时点)
- `ChatBackend`(在 `SwissKitJ-Api`)是统一接口,两个实现:`LocalChatBackend`(Qwen3-4B,Qwen3 Hermes 工具调用 + thinking stream)、`CloudChatBackend`(LangChain4j,OpenAI/Anthropic 双系)。
- `AiServiceProvider` 全局单例,持有当前 `ChatBackend` + 全局工具注册表 `Map<String, AiTool>`。
- 工具消费链路:
  - Local 走 `ToolSchemaBuilder.buildPromptDefinitions(tools)` 把工具转 markdown 灌进 system prompt。
  - Cloud 走 `AiToolToToolSpecification.convert(tool)` 转 LC4j `ToolSpecification`。
- `AiToolParam` 已支持 `enumValues`(commit `13b149b`),JSON Schema 双路径都会渲染 `enum`。

### 现行 16 个内置 AI 工具的缺陷

| 类别 | 工具 | 缺陷 |
|---|---|---|
| **schema 缺失枚举**(走运行时校验) | `base64.mode`、`hash_calculate.algorithm`、`color_convert.from`、`color_convert.to` | 应改 enum;Qwen3 可能传 `"sha-256"` 这类不规范值,目前靠运行时 `toUpperCase().trim()` 兜底 |
| **数组类型声明错误** | `pdf_merge.filePaths` 声明为 `"array"`(应为 `"string[]"`) | `ToolSchemaBuilder` 与 `AiToolToToolSpecification` 都用 `endsWith("[]")` 判定数组,`"array"` 落到 string schema —— **bug** |
| **返回非 JSON** | `pdf_split`、`pdf_merge`、`pdf_to_docx` | 当前是纯文本,与 JSON 返回的工具不一致;Qwen3-4B 读 JSON 更可靠 |
| **返回缺 `summary`** | 大部分工具 | 模型读复杂 JSON 易抓错字段;无 `summary` 让 4B 模型只能看全部 payload |
| **返回缺 `success`** | `excel_cancel` | `{"cancelled":true,"summary":...}` 与其他工具的 `{success:true,...}` 不一致 |
| **描述风格混乱** | 全部 16 个 | 三段式 / Args 自由文本 / Example 多寡不一,对 Qwen3 不友好 |
| **没有 cloud/local 区分** | 全部 | `email_archive_fetch`、`browser_automate` 这种需要强模型决策的工具,4B 模型调用易出错,但仍暴露给 local |
| **工具-插件耦合错误** | `BuiltinAiToolRegistrar` 反向 `findPlugin()` 查找 `ExcelSplitterPlugin` / `EmailArchivePlugin` 后构造工具 | 应由插件自带 |
| **孤儿工具** | `BrowserAutomateTool` | 无对应 UI 插件,直接 `registerTool(new BrowserAutomateTool())` |

## 3. 设计概览(三层)

```
┌─ 归属层 ──────────────────────────────────────────────┐
│ SwissKitJPlugin.aiTools() 默认方法                    │
│ PluginRegistry 在 add/remove 时自动注册/注销工具      │
│ BuiltinAiToolRegistrar 整个删除                       │
└───────────────────────────────────────────────────────┘
                          ↓
┌─ 能力层 ──────────────────────────────────────────────┐
│ AiTool.supportsLocal() / supportsCloud() (默认 true)  │
│ AiTool.getLocalDescription() / getLocalParameters()   │
│ AiServiceProvider.getTools() 按当前 mode 过滤         │
└───────────────────────────────────────────────────────┘
                          ↓
┌─ 契约层 ──────────────────────────────────────────────┐
│ 16 个工具的 schema 修复、描述双轨重写、返回 JSON 化   │
│ ToolExecutor catch 兜底也产 JSON                      │
└───────────────────────────────────────────────────────┘
```

## 4. 第 1 层:归属 — 工具归插件

### 4.1 `SwissKitJPlugin.aiTools()` 默认方法

```java
// SwissKitJ-Api: fan.summer.zhiflow.api.SwissKitJPlugin
public interface SwissKitJPlugin {
    // ... 所有现有方法不变 ...

    /**
     * AI 工具列表,由 PluginRegistry 在插件注册时自动登记到 AiServiceProvider。
     * 默认返回空列表 —— 不需要 AI 集成的插件无需 override。
     *
     * <p>调用时机:插件被加入 PluginRegistry 时调用一次,结果由 registry 缓存。
     * 插件被移除(JAR 卸载)时,这些工具会按 name 从 AiServiceProvider 注销。</p>
     *
     * <p>线程:在 JavaFX Application Thread 上调用(与 addPlugins 同线程)。
     * 实现方应返回确定的、幂等的列表 —— 后续调用必须返回相同的工具名集合,
     * 以保证注销时能正确匹配。</p>
     *
     * @return 该插件暴露的 AI 工具;空列表表示无 AI 工具
     */
    default List<AiTool> aiTools() { return List.of(); }
}
```

**关键决策:**
- **`default` 方法 → 外部插件二进制兼容**。已编译的 JAR 不受影响,源码也不用改;新插件想用就 override。
- **注册时机 = 插件 ADD,不是 ACTIVATE**。AI 工具的可用性反映"插件已加载",与 UI 是否在前台无关。否则用户必须先点开 Base64Plugin 才能让 AI 调 `base64`,反直觉。
- **结果缓存(幂等)**。PluginRegistry 调用一次后记住工具名,注销时按名字移除。`aiTools()` 必须幂等。

### 4.2 `PluginRegistry` 接管工具生命周期

```java
// SwissKit: fan.summer.zhiflow.plugin.PluginRegistry

private final Map<SwissKitJPlugin, List<String>> toolsByPlugin = new HashMap<>();

public void addPlugins(List<SwissKitJPlugin> toAdd) {   // 改 public
    plugins.addAll(toAdd);
    for (SwissKitJPlugin p : toAdd) registerPluginTools(p);
}

void removePlugin(SwissKitJPlugin plugin) {
    unregisterPluginTools(plugin);
    // ... 现有 deactivate / remove 逻辑不变 ...
}

private void registerPluginTools(SwissKitJPlugin plugin) {
    List<AiTool> tools;
    try {
        tools = PluginContext.runWith(plugin, plugin::aiTools);
    } catch (Exception e) {
        log.warn("Plugin {} threw on aiTools(): {}", plugin.getId(), e.getMessage(), e);
        return;
    }
    if (tools == null || tools.isEmpty()) return;

    List<String> names = new ArrayList<>();
    for (AiTool t : tools) {
        AiServiceProvider.registerTool(t);   // Map.put 语义:后注册覆盖
        names.add(t.getName());
    }
    toolsByPlugin.put(plugin, names);
    log.info("Registered {} AI tool(s) from plugin {}", names.size(), plugin.getId());
}

private void unregisterPluginTools(SwissKitJPlugin plugin) {
    List<String> names = toolsByPlugin.remove(plugin);
    if (names == null) return;
    for (String name : names) AiServiceProvider.unregisterTool(name);
    log.info("Unregistered {} AI tool(s) from plugin {}", names.size(), plugin.getId());
}
```

**关键决策:**
- **`addPlugins` 改 public**(原本 package-private)。`BuiltinToolRegistrar`(在 `fan.summer.zhiflow.registrar`)需要跨包调用。
- **工具名冲突策略**:`AiServiceProvider.registerTool` 已是 `Map.put` 语义,保持现状。但 `registerPluginTools` 在冲突时记一条 `WARN`。
- **热重载友好**:JAR 卸载 → `removePlugin` → `unregisterPluginTools`;JAR 重载 → `addPlugins` → `registerPluginTools`。`PluginLoader` 不动。

### 4.3 `BuiltinToolRegistrar` 改走单一入口

```java
public static void register(PluginLoader loader, PluginRegistry registry) {
    List<SwissKitJPlugin> builtins = List.of(
        new AiChatPlugin(),
        new JsonFormatterPlugin(),
        new Base64Plugin(),
        new HashCalculatorPlugin(),
        new ExcelSplitterPlugin(),
        new ColorConverterPlugin(),
        new MarkdownEditorPlugin(),
        new EmailPlugin(),
        new EmailArchivePlugin(),
        new PdfToolPlugin(),
        new BrowserAutomatePlugin()    // ← 新增
    );
    registry.addPlugins(builtins);     // ← 改走 addPlugins(原为 getPlugins().addAll)
    // ... 日志不变 ...
}
```

### 4.4 删除 `BuiltinAiToolRegistrar`

整个类删除。`SwissKitJApp.start()` 中调用 `BuiltinAiToolRegistrar.register()` 的行也删掉。

### 4.5 工具迁移映射

| 宿主插件 | 接管的 AI 工具 | 备注 |
|---|---|---|
| `Base64Plugin` | `BuiltinBase64Tool` | 工具类物理位置不变(`fan.summer.zhiflow.ai.tools`) |
| `HashCalculatorPlugin` | `BuiltinHashTool` | 同上 |
| `JsonFormatterPlugin` | `BuiltinJsonFormatTool` | 同上 |
| `ColorConverterPlugin` | `BuiltinColorConvertTool` | 同上 |
| `ExcelSplitterPlugin` | `ExcelAnalyzeTool`、`ExcelConfigureTool`、`ExcelComplexConfigTool`、`ExcelExecuteTool`、`ExcelQueryTool`、`ExcelCancelTool` | 工具仍持有插件引用,由 `aiTools()` 闭包传入 |
| `EmailArchivePlugin` | `EmailArchiveFetchTool`、`EmailArchiveQueryTool` | 同上 |
| `PdfToolPlugin` | `PdfSplitAiTool`、`PdfMergeAiTool`、`PdfToDocxAiTool` | 工具无状态,宿主仅为注册 |
| **新增** `BrowserAutomatePlugin` | `BrowserAutomateTool` | UI-less 宿主,见 §4.6 |
| `MarkdownEditorPlugin`、`EmailPlugin`、`AiChatPlugin` | (无) | 不需要 AI 工具 |

### 4.6 新增 `BrowserAutomatePlugin`(UI-less 宿主)

`BrowserAutomateTool` 之前是"孤儿" —— 无对应 UI 插件。给它一个宿主:

- 位置:`fan.summer.zhiflow.buildintool.browser.BrowserAutomatePlugin`
- `getCategory()` = `ToolCategory.DEV`
- `getMdiIcon()` = `"web"`
- `createView()` 返回一个说明页(标签 + 简短文字):"此插件为 AI 提供浏览器自动化能力,无独立界面。在 AI 聊天里说'打开 github.com 搜索 X'即可触发。"
- `aiTools()` 返回 `List.of(new BrowserAutomateTool())`

**选 UI-less 宿主而不是并入 `AiChatPlugin` 的理由**:`AiChatPlugin` 是 AI 入口本身,给它加浏览器能力混淆职责。UI-less 宿主让用户从侧边栏看到"哦,这有个浏览器自动化能力",未来若加独立 UI 直接在 `createView()` 扩展即可。

### 4.7 ExcelSplitterPlugin.aiTools() 样例

```java
@Override
public List<AiTool> aiTools() {
    return List.of(
        new ExcelAnalyzeTool(this),
        new ExcelConfigureTool(this),
        new ExcelComplexConfigTool(this),
        new ExcelExecuteTool(this),
        new ExcelQueryTool(this),
        new ExcelCancelTool()
    );
}
```

工具类的构造函数和 `execute` 都**不变**。`BuiltinAiToolRegistrar.registerExcelTools()` 里那一坨 `Optional<ExcelSplitterPlugin>` 查找 + 实例化代码整个消失。

## 5. 第 2 层:能力 — cloud / local 双轨

### 5.1 `AiTool` 接口扩展(4 个 default 方法)

```java
public interface AiTool {
    String getName();
    String getDescription();          // cloud 描述
    List<AiToolParam> getParameters();
    AiToolResult execute(Map<String, Object> arguments);

    /** Local 描述(短、关键词密集,针对 Qwen3-4B)。默认 fallback 到 getDescription()。 */
    default String getLocalDescription() { return getDescription(); }

    /** Local 参数表(可简化 schema)。默认 fallback 到 getParameters()。 */
    default List<AiToolParam> getLocalParameters() { return getParameters(); }

    /** 是否在 local 模式下可见。默认 true。 */
    default boolean supportsLocal() { return true; }

    /** 是否在 cloud 模式下可见。默认 true。 */
    default boolean supportsCloud() { return true; }
}
```

**为什么 4 个 default 而不是 1 个枚举:**
- `supportsLocal/supportsCloud` 决定**可见性**(model 能不能看到)。
- `getLocalDescription/getLocalParameters` 决定**形态**(model 看到它时长什么样)。
- 两个正交维度,4 个独立 default 比合并枚举(`Mode.CLOUD_ONLY / LOCAL_ONLY / BOTH`)更细,实现成本极低。

### 5.2 过滤在 `AiServiceProvider.getTools()`

```java
public static List<AiTool> getTools() {
    boolean isLocal = "local".equals(currentMode);
    String filter = constrainedTool;

    return tools.values().stream()
        .filter(t -> isLocal ? t.supportsLocal() : t.supportsCloud())
        .filter(t -> filter == null || filter.equals(t.getName()))
        .toList();
}
```

**关键决策:**
- **注册不变,查询过滤**。所有工具在插件加载时一次性注册到 `AiServiceProvider.tools`。过滤发生在每次 `getTools()` 调用。切换 mode 不需要重新注册工具。
- **`constrainedTool`(slash 命令约束)保持后置过滤**。若约束了 `supportsLocal=false` 的工具在 local 模式下,返回空 —— 由 `SlashCommandHandler` 在约束前检查并给用户友好错误。

### 5.3 描述选择 — `AiToolDescriptions` 工具类

```java
// SwissKit: fan.summer.zhiflow.ai.tools.AiToolDescriptions
public final class AiToolDescriptions {
    private AiToolDescriptions() {}

    public static String pickDescription(AiTool tool) {
        return "local".equals(AiServiceProvider.getCurrentMode())
            ? tool.getLocalDescription() : tool.getDescription();
    }

    public static List<AiToolParam> pickParameters(AiTool tool) {
        return "local".equals(AiServiceProvider.getCurrentMode())
            ? tool.getLocalParameters() : tool.getParameters();
    }
}
```

两处消费者改用它:
- `ToolSchemaBuilder.buildPromptDefinitions`(local 走的路径)
- `AiToolToToolSpecification.convert`(cloud 走的路径)

`ToolExecutor.execute` **不改** —— 按 name 查表执行,不关心描述。

### 5.4 16 个工具的能力分类

| 工具 | supportsLocal | supportsCloud | 理由 |
|---|---|---|---|
| `base64`、`hash_calculate`、`json_format`、`color_convert` | ✓ | ✓ | 单步纯函数,任何模型都能调 |
| `excel_analyze`、`excel_query`、`excel_cancel` | ✓ | ✓ | 读结构 / 无参 / 单步 |
| `excel_configure`、`excel_complex_config`、`excel_execute` | ✓ | ✓ | 模式枚举清晰,Qwen3 可处理 |
| `email_archive_query` | ✓ | ✓ | 数据库只读查询 |
| `email_archive_fetch` | ✗ | ✓ | 涉及 IMAP 凭据 / 网络故障恢复,Qwen3-4B 决策不可靠,让强模型来 |
| `pdf_split`、`pdf_merge`、`pdf_to_docx` | ✓ | ✓ | 文件操作,参数简单 |
| `browser_automate` | ✗ | ✓ | 自己驱动 think-act 循环,必须有强模型当 planner |

**结果:**
- Local 模式(Qwen3-4B)下可见 **13 个工具**(隐藏 `email_archive_fetch` 和 `browser_automate`)。
- Cloud 模式下可见全部 **16 个工具**。

### 5.5 切换 mode 时的刷新

用户在设置里切换 local → openai 时,`AiServiceProvider.switchMode()` 触发 `notifyStateChanged()`。**不需要额外的工具刷新事件** —— 过滤发生在每次 `getTools()` 调用,下次聊天发起时自动看到新工具集。

唯一例外:当前聊天 session 进行中切换 mode(罕见、且本来就有破坏性),不在本 spec 范围处理。

## 6. 第 3 层:契约 — schema / 描述 / 返回结构拉齐

### 6.1 Schema 修复

| 工具 | 字段 | 当前 | 修复后 |
|---|---|---|---|
| `base64` | `mode` | `"string"` + 运行时校验 | `"string"` + `enumValues: ["encode","decode"]` |
| `hash_calculate` | `algorithm` | `"string"` + 大小写归一 | `"string"` + `enumValues: ["MD5","SHA-1","SHA-256","SHA-512"]` |
| `color_convert` | `from` | `"string"` | `"string"` + `enumValues: ["HEX","RGB","HSL"]` |
| `color_convert` | `to` | `"string"` | `"string"` + `enumValues: ["HEX","RGB","HSL"]` |
| `pdf_merge` | `filePaths` | `"array"` ❌ | `"string[]"` |

**运行时校验保留**:模型(特别是 Qwen3)仍可能传 `"sha-256"`、`"Md5"`,运行时继续 `toUpperCase().trim()`。不合格的错误信息改为 JSON 形式(见 §6.3)。

### 6.2 描述模板

#### Cloud 描述模板(`getDescription()` 返回)

```
<一句话功能说明,20-30 词>.
Args: <name> (<type>[, required|optional][, enum: a|b|c]) — <说明>;
      <name> ... .
Example: <tool_name>{"<param>": "<value>"}.
```

**规则:**
- 第一句自包含(功能 + 关键词)。
- Args 段每参数 ≤ 25 词。
- Example 给 1 个典型例子(最多 2 个,差异显著时)。
- 不写流程性话术("This is the FIRST step" 等)—— 流程依赖通过 `excel_query` 查状态解决。

#### Local 描述模板(`getLocalDescription()` 返回)

```
<一句话功能,15-20 词>.
Args: <name> (<type>) — <说明>; <name> ... .
Example: <tool_name>{"<param>": "<value>"}.
```

**规则:**
- 总长度 ≤ 80 字符(不含 Example 行)。
- Args 段压成一行,分号分隔;描述 ≤ 8 词。
- 不写 `required` / `optional`(local schema 里已有,Qwen3 读 schema 更可靠)。
- Example 必给,参数值用占位符(`/tmp/a.xlsx`、`hello` 等)。

#### 双版本对照(base64)

```
Cloud:
Encode text to Base64 or decode Base64 back to text.
Args: text (string, required) — input text to transform;
      mode (string, required, enum: encode|decode) — direction of the conversion.
Example: base64{"text":"hello","mode":"encode"}.

Local:
Base64 encode or decode. Args: text (string), mode (encode|decode).
Example: base64{"text":"hello","mode":"encode"}.
```

#### Local 描述要点速查

| 工具 | Local 描述要点 |
|---|---|
| `base64` | "Base64 encode or decode. Args: text (string), mode (encode\|decode)." |
| `hash_calculate` | "Hash digest. Args: text (string), algorithm (MD5\|SHA-1\|SHA-256\|SHA-512)." |
| `json_format` | "Format or minify JSON. Args: json (string), minify (boolean, default false)." |
| `color_convert` | "Convert color. Args: color (string), from (HEX\|RGB\|HSL), to (HEX\|RGB\|HSL)." |
| `excel_analyze` | "Read Excel structure (sheets, headers). Args: filePath (string)." |
| `excel_query` | "Query current Excel split state. No args." |
| `excel_configure` | "Set split mode. Args: mode (BY_SHEET\|BY_COLUMN\|COMPLEX), plus mode-specific." |
| `excel_complex_config` | "Manage complex split configs. Args: action (add\|list\|clear), plus action-specific." |
| `excel_execute` | "Run configured split. Args: outputDir (string), filePrefix (string, optional)." |
| `excel_cancel` | "Cancel running split. No args." |
| `email_archive_query` | "Search archived emails. Args: subject (string), fromAddress (string), startDate (ISO), endDate (ISO), limit (integer)." |
| `pdf_split` | "Split PDF by page ranges. Args: filePath (string), ranges (e.g. '1-3,5,8-10'), outputDir (string)." |
| `pdf_merge` | "Merge PDFs. Args: filePaths (string[]), outputPath (string)." |
| `pdf_to_docx` | "PDF to DOCX. Args: filePath (string), outputDir (string)." |

Local 隐藏的两个工具(`email_archive_fetch`、`browser_automate`)无需写 local 描述 —— `getLocalDescription()` fallback 到 cloud 描述即可。

### 6.3 返回结构统一

**所有工具 `execute` 返回的 `output` 字符串必须是 JSON:**

```json
// 成功
{
  "success": true,
  "summary": "<给模型看的 1 行人类可读总结>",
  ...payload
}

// 失败
{
  "success": false,
  "error": "<错误描述>"
}
```

**当前需要改造的工具:**

| 工具 | 当前 output | 改造后 |
|---|---|---|
| `pdf_split` | 纯文本 `"Split complete. Output files:\n- ..."` | `{"success":true,"summary":"Split into 3 files","outputFiles":[...]}` |
| `pdf_merge` | 纯文本 `"Merge complete: ..."` | `{"success":true,"summary":"Merged 3 PDFs into result.pdf","outputPath":"..."}` |
| `pdf_to_docx` | 纯文本 `"Conversion complete: ..."` | `{"success":true,"summary":"Converted to result.docx","outputPath":"..."}` |
| `excel_cancel` | `{"cancelled":true,"summary":"..."}` ❌(无 `success`) | `{"success":true,"summary":"Split cancelled"}` |
| 其他工具 | 已是 JSON 但缺 `summary` | 补 `summary` 字段,保留原有 payload |

**框架层兜底也改**(`ToolExecutor.execute` 的 catch 分支):

```java
catch (Exception e) {
    log.error("Tool execution error: tool={}, error={}", toolName, e.getMessage());
    String json = JsonHelper.toJson(Map.of(
        "success", false,
        "error",   "Tool execution error: " + e.getMessage()
    ));
    return AiToolResult.error(json);
}
```

**不改 `AiToolResult` record**:保持 `(boolean success, String output)`。约定在 output 字符串内部。

**为什么 `summary` 对 Qwen3 关键**:4B 模型读复杂 JSON 易抓错字段。给一行 `summary` 让它"一眼懂",payload 留给"必要时按需读"。例如 `email_archive_query` 当前会把 20 封邮件的完整 metadata 塞进 output,Qwen3 经常据此乱编回复;加 `summary` 后,模型主要看 `summary`,需要细节再 `email_archive_query` 缩小范围。

### 6.4 参数命名一致性

| 字段 | 统一规则 |
|---|---|
| 输入文件路径(单文件) | `filePath`(string) |
| 输入文件路径(多文件) | `filePaths`(string[]) |
| 输出目录 | `outputDir`(string) |
| 输出文件(单个) | `outputPath`(string) |
| 文件名前缀 | `filePrefix`(string) |
| 模式选择 | `mode`(功能模式)/ `action`(操作动作)— 语义不同,不强求合并 |

**当前命名已基本符合,主要变化只有 `pdf_merge.filePaths` 类型从 `"array"` 改 `"string[]"`(已在 §6.1 处理)。**

## 7. 实施分三个 commit

### Commit 1: API + 过滤基础设施
- `SwissKitJPlugin.aiTools()` 默认方法
- `AiTool` 4 个新 default 方法
- `AiServiceProvider.getTools()` 加 mode 过滤
- `AiToolDescriptions` 工具类(新)
- `ToolSchemaBuilder` / `AiToolToToolSpecification` 改用 `AiToolDescriptions`
- `ToolExecutor` catch 兜底改 JSON
- `PluginRegistry` 加 `toolsByPlugin` 映射 + `registerPluginTools` / `unregisterPluginTools`
- `PluginRegistry.addPlugins()` 改 public
- 新增单测(§8.1 前 6 项)
- **此 commit 后系统仍能正常启动**:`BuiltinAiToolRegistrar` 仍在按老路径注册全部 16 个工具;新加的 `PluginRegistry.registerPluginTools()` 也会被调用,但所有插件都用默认 `aiTools()` 返回空列表 —— 两条路径不冲突。Commit 1 内**不**让任何插件 override `aiTools()`,工具仍完全由 `BuiltinAiToolRegistrar` 提供。安全中间态。

### Commit 2: 工具迁移到插件
- 10 个插件各自 override `aiTools()` 返回对应工具
- 新建 `BrowserAutomatePlugin`(UI-less 宿主,DEV 分类,icon `web`)
- `BuiltinToolRegistrar` 的 builtins 列表追加 `BrowserAutomatePlugin`
- `BuiltinToolRegistrar.register()` 改走 `registry.addPlugins(builtins)`
- 删除 `BuiltinAiToolRegistrar` 类
- `SwissKitJApp.start()` 移除 `BuiltinAiToolRegistrar.register()` 调用
- `PluginRegistryAiToolsTest`(Commit 1 写好)验证迁移正确

### Commit 3: Schema / 描述 / 返回结构拉齐
- 16 个工具的 schema 修复(§6.1)
- 16 个工具的 cloud / local 描述重写(§6.2)
- 工具返回结构统一为 `{success, summary, ...}`(§6.3)
- `pdf_split` / `pdf_merge` / `pdf_to_docx` / `excel_cancel` 返回值改 JSON
- 参数命名核对(§6.4)
- 补完 §8.1 后 2 项单测

**为什么分三个 commit:**
- Commit 1 是 API + 基础设施,不动任何业务工具。出问题影响面只是新方法没人用。
- Commit 2 是机械迁移,不改任何 execute 逻辑。
- Commit 3 是 schema/描述/返回结构变更,对模型行为有可观察影响,需单独验证。
- 三个 commit 都能独立编译、独立运行;任何一个 revert 都不影响前面。

## 8. 测试

### 8.1 新增 / 改造的测试

| 测试 | 目的 | 覆盖点 |
|---|---|---|
| `PluginRegistryAiToolsTest`(新) | add/remove 自动注册/注销工具 | `addPlugins` 后 `AiServiceProvider.getTools()` 能看到;`removePlugin` 后看不到;冲突时记 WARN |
| `SwissKitJPluginDefaultAiToolsTest`(新) | 默认实现 | 不 override `aiTools()` 的插件返回空列表,不抛异常 |
| `AiServiceProviderModeFilterTest`(新) | local/cloud 过滤 | local 模式隐藏 `supportsLocal=false` 的工具;cloud 模式反之;切换 mode 后立即生效 |
| `AiToolDescriptionsTest`(新) | 描述选择 | local mode 用 `getLocalDescription`;cloud 用 `getDescription`;fallback 正确 |
| `ToolExecutorErrorJsonTest`(新) | catch 兜底 JSON 形态 | 抛异常的工具返回 `{"success":false,"error":"..."}` |
| `ToolSchemaBuilderLocalTest`(新) | prompt-definitions 用 local 描述 | 进入 local 模式后 markdown 里是 local 版 |
| `AiToolToToolSpecificationLocalTest`(新) | LC4j 转换用 local 描述 + 参数 | local 参数表被消费 |
| 现有 `AiToolToToolSpecificationTest` | 补一组 local-mode 断言 | 同一工具 cloud vs local schema 不同 |

**关键决策:**
- **不写"模型调用 E2E"测试**。需要起真实模型,CI 不可行。功能正确性靠单测覆盖;模型行为靠 §8.2 smoke checklist。
- **过滤逻辑测两遍**:`AiServiceProviderModeFilterTest` 测直接调用;`AiToolDescriptionsTest` 测消费者调用。
- **现有 Excel AI 工具的 `Optional<ExcelSplitterPlugin>` 查找相关测试**(如果有)会过时 —— 新流程下插件自带工具。扫一遍 `SwissKit/src/test/java/fan/summer/ai/` 和 `fan/summer/buildintool/`,把不再适用的旧测试删掉。

### 8.2 手动 smoke checklist(每个 commit 后跑一遍)

```
启动 app → 进入 AI 聊天 →
  [local 模式]
  □ 工具集可见数 = 13(隐藏 email_archive_fetch、browser_automate)
  □ 输入 "base64 encode hello" → 触发 base64,返回 summary
  □ 输入 "1+1 的 MD5 是多少" → 触发 hash_calculate
  □ 输入 "分析 /tmp/test.xlsx" → 触发 excel_analyze,返回 summary
  □ 输入 "打开 github.com 搜索 abc" → browser_automate 不可见,模型拒绝/回答无能力

  [openai 模式,配 GPT-4o 或兼容端]
  □ 工具集可见数 = 16
  □ base64 / hash_calculate 正常
  □ email_archive_fetch 可用
  □ browser_automate 可用(需 Chrome/Edge)

切换 mode:
  □ local → openai:下次发消息工具集自动扩到 16
  □ openai → local:下次发消息工具集自动缩到 13

热重载:
  □ 把一个外部插件 JAR 丢进 .swisskit/plugin/ → 工具自动注册
  □ 删除 JAR → 工具自动注销

工具执行失败路径:
  □ 让 base64 拿到非法 mode(如 "encrypt") → 返回 JSON 形式错误
  □ 让 excel_analyze 拿到不存在的文件路径 → 返回 JSON 形式错误
```

## 9. 文档更新

| 文件 | 改动 |
|---|---|
| `CLAUDE.md` | 在"Plugin Development"段补 `aiTools()` 用法;在"AI tools"段补 `supportsLocal/supportsCloud` 能力声明 + cloud/local 描述双轨 |
| `plugin-dev` skill | 同步更新插件开发指引,给出 `aiTools()` + local/cloud 双描述的模板代码 |
| `CHANGELOG.md` | v3.1.0 / v3.2.0 下追加"插件可声明 AI 工具;工具可声明 cloud/local 能力" |

**不写**:独立的 migration guide。改动全部 default + 向后兼容,外部插件零成本。

## 10. 风险与回退

### 风险
1. **`supportsLocal/supportsCloud` 默认 true 的副作用**:外部插件仓库(MuskStark/SwissKiJ-Plugin)的现有工具继续在 local 模式下可见 —— 如果某个工具对 Qwen3-4B 不友好(参数过复杂、需要强模型),用户在 local 模式下仍会遇到调用失败。**缓解**:写文档建议外部插件作者按需 override;Qwen3 失败时模型会自己降级回答(已有行为)。
2. **工具名冲突**:`AiServiceProvider.registerTool` 是 `Map.put`,后注册覆盖。新流程下若两个插件声明同名工具,静默覆盖。**缓解**:`registerPluginTools` 记 WARN 日志。
3. **Commit 1 中间态**:`BuiltinAiToolRegistrar` 与(暂未启用的)`aiTools()` 默认方法并存,理论上一致但实际有重复注册风险。**缓解**:Commit 1 不让任何插件 override `aiTools()`,工具仍由 `BuiltinAiToolRegistrar` 注册;Commit 2 一次性切换。

### 回退
- Commit 3 revert → 回到 Commit 2 状态:工具已迁移但 schema/描述/返回结构未拉齐,系统可用,体验略旧。
- Commit 2 revert → 回到 Commit 1 状态:`aiTools()` API 就位但没人用,工具由旧的 `BuiltinAiToolRegistrar` 注册,系统可用。
- Commit 1 revert → 回到原始状态:无 API 扩展,系统原样可用。
- 任何 commit 都可独立 revert,不影响前面 commit。

## 11. 完成定义(Definition of Done)

- [ ] 三个 commit 全部合入 v3.1.0 分支
- [ ] §8.1 所有新单测在 IDEA Maven 下通过
- [ ] §8.2 smoke checklist 全绿(local + cloud + 切换 mode + 热重载 + 失败路径)
- [ ] `CLAUDE.md` / `plugin-dev` skill / `CHANGELOG.md` 更新
- [ ] 手动跑一个 MuskStark/SwissKiJ-Plugin 的 JAR,确认能加载且工具仍可见
- [ ] 没有破坏现有外部插件(跑至少 1 个第三方插件做兼容性确认)
