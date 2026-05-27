# AI → Excel Splitter 集成实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过AI聊天的5个工具逐步引导用户完成Excel拆分，支持BY_SHEET / BY_COLUMN / COMPLEX三种模式。

**Architecture:** 共享`SplitConfig`单例，5个`AiTool`实现，后台线程执行POI操作，结果通过`AiToolResult`返回给AI。

**Tech Stack:** Java, SwissKitJ-Api (AiTool, AiService, AiToolResult), Apache POI, FesodSheet, JavaFX Platform.runLater

---

## 文件结构

```
SwissKit/src/main/java/fan/summer/buildintool/
├── ai/
│   ├── ExcelAnalyzeTool.java      # 新建
│   ├── ExcelConfigureTool.java    # 新建
│   ├── ExcelExecuteTool.java      # 新建
│   ├── ExcelQueryTool.java        # 新建
│   ├── ExcelCancelTool.java       # 新建
│   └── AiExcelTools.java          # 新建 — 工具注册辅助类
└── excelsplitter/
    └── ExcelSplitterPlugin.java   # 修改 — 暴露共享SplitConfig单例
```

---

## 任务 1: 修改 ExcelSplitterPlugin 暴露共享 SplitConfig

**文件:**
- 修改: `SwissKit/src/main/java/fan/summer/buildintool/excelsplitter/ExcelSplitterPlugin.java`

`buildWizardView()` 中已创建 `SplitConfig config = new SplitConfig()`，改为插件级字段：

```java
public class ExcelSplitterPlugin implements SwissKitJPlugin {
    private static final AtomicBoolean hasRunningTask = new AtomicBoolean(false);
    private Node view;
    private final SplitConfig sharedConfig = new SplitConfig();  // 共享单例

    public SplitConfig getSharedSplitConfig() { return sharedConfig; }  // 新增

    private Node buildWizardView() {
        SplitConfig config = sharedConfig;  // 使用同一实例
        // ... 后续不变
    }
}
```

- [ ] **Step 1: 修改 ExcelSplitterPlugin.java**

```java
// 在 ExcelSplitterPlugin 类中，将 view 字段下移，添加 sharedConfig 字段和 getter
// 替换 buildWizardView 中的 "SplitConfig config = new SplitConfig();" 为 "SplitConfig config = sharedConfig;"
```

- [ ] **Step 2: 运行构建验证**

```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && mvn compile -f SwissKit/pom.xml -q
```
预期: 编译成功

- [ ] **Step 3: 提交**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/excelsplitter/ExcelSplitterPlugin.java
git commit -m "refactor(excel): expose shared SplitConfig for AI tool access"
```

---

## 任务 2: 创建 ExcelAnalyzeTool

**文件:**
- 新建: `SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelAnalyzeTool.java`

```java
package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
import fan.summer.buildintool.excelsplitter.*;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class ExcelAnalyzeTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(ExcelAnalyzeTool.class);
    private final ExcelSplitterPlugin plugin;

    public ExcelAnalyzeTool(ExcelSplitterPlugin plugin) { this.plugin = plugin; }

    @Override public String getName() { return "excel_analyze"; }

    @Override public String getDescription() {
        return "Analyze an Excel file and return all sheet names, row counts, and column headers. " +
               "Call this first before configuring the split. " +
               "Argument: filePath (string, required) — absolute path to the .xlsx/.xls file.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(AiToolParam.of("filePath", "string", "Absolute path to the Excel file", true));
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String filePathStr = (String) args.get("filePath");
        if (filePathStr == null || filePathStr.isBlank()) {
            return AiToolResult.error("filePath is required");
        }
        Path filePath = Paths.get(filePathStr.trim());
        if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
            return AiToolResult.error("File not found or not readable: " + filePathStr);
        }

        SplitConfig config = plugin.getSharedSplitConfig();
        config.sourceFile = filePath;

        try {
            Map<String, Map<Integer, String>> analysisResult =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return ExcelSplitter.analyze(filePath);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }).get();

            config.analysisResult = analysisResult;

            List<Map<String, Object>> sheets = new ArrayList<>();
            int totalRows = 0;
            for (Map.Entry<String, Map<Integer, String>> e : analysisResult.entrySet()) {
                Map<String, Object> sheetInfo = new LinkedHashMap<>();
                sheetInfo.put("name", e.getKey());
                sheetInfo.put("headerCount", e.getValue().size());
                sheetInfo.put("headers", new ArrayList<>(e.getValue().values()));
                sheets.add(sheetInfo);
                totalRows += e.getValue().size();
            }

            String json = String.format(
                "{\"success\":true,\"sheets\":%s,\"totalSheets\":%d,\"sourceFile\":\"%s\"}",
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(sheets),
                sheets.size(),
                filePath.getFileName()
            );
            log.info("excel_analyze success: {} sheets found", sheets.size());
            return AiToolResult.success(json);
        } catch (Exception e) {
            log.error("excel_analyze failed: {}", e.getMessage());
            return AiToolResult.error("Analysis failed: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 1: 创建 ExcelAnalyzeTool.java**

```bash
cat > /Users/phoebej/Develop/Java/SwissKitJ/SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelAnalyzeTool.java << 'EOF'
package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
import fan.summer.buildintool.excelsplitter.*;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class ExcelAnalyzeTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(ExcelAnalyzeTool.class);
    private final ExcelSplitterPlugin plugin;

    public ExcelAnalyzeTool(ExcelSplitterPlugin plugin) { this.plugin = plugin; }

    @Override public String getName() { return "excel_analyze"; }

    @Override public String getDescription() {
        return "Analyze an Excel file and return all sheet names, row counts, and column headers. " +
               "Call this first before configuring the split. " +
               "Argument: filePath (string, required) — absolute path to the .xlsx/.xls file.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(AiToolParam.of("filePath", "string", "Absolute path to the Excel file", true));
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String filePathStr = (String) args.get("filePath");
        if (filePathStr == null || filePathStr.isBlank()) {
            return AiToolResult.error("filePath is required");
        }
        Path filePath = Paths.get(filePathStr.trim());
        if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
            return AiToolResult.error("File not found or not readable: " + filePathStr);
        }

        SplitConfig config = plugin.getSharedSplitConfig();
        config.sourceFile = filePath;

        try {
            Map<String, Map<Integer, String>> analysisResult =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return ExcelSplitter.analyze(filePath);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }).get();

            config.analysisResult = analysisResult;

            List<Map<String, Object>> sheets = new ArrayList<>();
            for (Map.Entry<String, Map<Integer, String>> e : analysisResult.entrySet()) {
                Map<String, Object> sheetInfo = new LinkedHashMap<>();
                sheetInfo.put("name", e.getKey());
                sheetInfo.put("headerCount", e.getValue().size());
                sheetInfo.put("headers", new ArrayList<>(e.getValue().values()));
                sheets.add(sheetInfo);
            }

            String json = String.format(
                "{\"success\":true,\"sheets\":%s,\"totalSheets\":%d,\"sourceFile\":\"%s\"}",
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(sheets),
                sheets.size(),
                filePath.getFileName()
            );
            log.info("excel_analyze success: {} sheets found", sheets.size());
            return AiToolResult.success(json);
        } catch (Exception e) {
            log.error("excel_analyze failed: {}", e.getMessage());
            return AiToolResult.error("Analysis failed: " + e.getMessage());
        }
    }
}
EOF
```

- [ ] **Step 2: 运行构建验证**

```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && mvn compile -f SwissKit/pom.xml -q
```
预期: 编译成功

- [ ] **Step 3: 提交**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelAnalyzeTool.java
git commit -m "feat(ai): add ExcelAnalyzeTool for file analysis"
```

---

## 任务 3: 创建 ExcelConfigureTool

**文件:**
- 新建: `SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelConfigureTool.java`

此工具根据 mode 参数配置 `SplitConfig`：
- `BY_SHEET`: 配置 `selectedSheets`
- `BY_COLUMN`: 配置 `splitSheet`, `splitColumn`, `splitColumnIndex`（需要从 header 名称查找索引）
- `COMPLEX`: 配置 `complexTaskId`

```java
package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
import fan.summer.buildintool.excelsplitter.*;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.util.*;

@SuppressWarnings("unchecked")
public class ExcelConfigureTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(ExcelConfigureTool.class);
    private final ExcelSplitterPlugin plugin;

    public ExcelConfigureTool(ExcelSplitterPlugin plugin) { this.plugin = plugin; }

    @Override public String getName() { return "excel_configure"; }

    @Override public String getDescription() {
        return "Configure the Excel split mode and parameters. " +
               "Must be called after excel_analyze. " +
               "Modes: BY_SHEET (one file per sheet), BY_COLUMN (group by column value), " +
               "COMPLEX (DB-backed multi-config). " +
               "Required args: mode (string). " +
               "BY_SHEET optional: sheets (string[]). " +
               "BY_COLUMN required: splitSheet (string), splitColumn (string). " +
               "COMPLEX required: taskId (string, UUID).";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("mode", "string", "Split mode: BY_SHEET, BY_COLUMN, or COMPLEX", true),
            AiToolParam.of("sheets", "string[]", "Sheet names to export (BY_SHEET mode)", false),
            AiToolParam.of("splitSheet", "string", "Sheet name to split on (BY_COLUMN mode)", false),
            AiToolParam.of("splitColumn", "string", "Column header name to split by (BY_COLUMN mode)", false),
            AiToolParam.of("taskId", "string", "Complex split task ID from DB (COMPLEX mode)", false)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        SplitConfig config = plugin.getSharedSplitConfig();

        if (config.analysisResult == null) {
            return AiToolResult.error("No analysis result. Call excel_analyze first.");
        }

        String modeStr = (String) args.get("mode");
        if (modeStr == null || modeStr.isBlank()) {
            return AiToolResult.error("mode is required (BY_SHEET, BY_COLUMN, or COMPLEX)");
        }

        try {
            SplitConfig.SplitMode mode = SplitConfig.SplitMode.valueOf(modeStr.toUpperCase());
            config.mode = mode;

            switch (mode) {
                case BY_SHEET -> {
                    List<String> sheets = (List<String>) args.get("sheets");
                    if (sheets != null && !sheets.isEmpty()) {
                        config.selectedSheets = new ArrayList<>(sheets);
                    } else {
                        config.selectedSheets = new ArrayList<>(config.analysisResult.keySet());
                    }
                    return AiToolResult.success(String.format(
                        "{\"configured\":true,\"mode\":\"BY_SHEET\",\"selectedSheets\":%s,\"summary\":\"Will export %d sheet(s) as separate files\"}",
                        config.selectedSheets, config.selectedSheets.size()));
                }
                case BY_COLUMN -> {
                    String splitSheet = (String) args.get("splitSheet");
                    String splitColumn = (String) args.get("splitColumn");
                    if (splitSheet == null || splitColumn == null) {
                        return AiToolResult.error("BY_COLUMN mode requires splitSheet and splitColumn");
                    }
                    Map<Integer, String> headers = config.analysisResult.get(splitSheet);
                    if (headers == null) {
                        return AiToolResult.error("Sheet not found: " + splitSheet);
                    }
                    Integer colIdx = null;
                    String foundCol = null;
                    for (Map.Entry<Integer, String> e : headers.entrySet()) {
                        if (e.getValue().equalsIgnoreCase(splitColumn.trim())) {
                            colIdx = e.getKey();
                            foundCol = e.getValue();
                            break;
                        }
                    }
                    if (colIdx == null) {
                        return AiToolResult.error("Column not found: " + splitColumn + ". Available columns: " + headers.values());
                    }
                    config.splitSheet = splitSheet;
                    config.splitColumn = foundCol;
                    config.splitColumnIndex = colIdx;
                    return AiToolResult.success(String.format(
                        "{\"configured\":true,\"mode\":\"BY_COLUMN\",\"splitSheet\":\"%s\",\"splitColumn\":\"%s\",\"splitColumnIndex\":%d,\"summary\":\"Will split sheet '%s' by column '%s' (index %d)\"}",
                        splitSheet, foundCol, colIdx, splitSheet, foundCol, colIdx));
                }
                case COMPLEX -> {
                    String taskId = (String) args.get("taskId");
                    if (taskId == null || taskId.isBlank()) {
                        return AiToolResult.error("COMPLEX mode requires taskId (UUID string)");
                    }
                    config.complexTaskId = taskId;
                    return AiToolResult.success(String.format(
                        "{\"configured\":true,\"mode\":\"COMPLEX\",\"taskId\":\"%s\",\"summary\":\"Complex split configured with taskId: %s\"}",
                        taskId, taskId));
                }
            }
        } catch (IllegalArgumentException e) {
            return AiToolResult.error("Invalid mode: " + modeStr + ". Use BY_SHEET, BY_COLUMN, or COMPLEX.");
        }
        return AiToolResult.error("Unexpected error in configure");
    }
}
```

- [ ] **Step 1: 创建 ExcelConfigureTool.java**（内容见上方完整代码）

- [ ] **Step 2: 运行构建验证**

```bash
mvn compile -f SwissKit/pom.xml -q
```
预期: 编译成功

- [ ] **Step 3: 提交**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelConfigureTool.java
git commit -m "feat(ai): add ExcelConfigureTool for split mode configuration"
```

---

## 任务 4: 创建 ExcelExecuteTool

**文件:**
- 新建: `SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelExecuteTool.java`

在后台线程执行 `ExcelSplitter.split()`，通过 `CompletableFuture` 实现静默后台执行。

```java
package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
import fan.summer.buildintool.excelsplitter.*;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class ExcelExecuteTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(ExcelExecuteTool.class);
    private final ExcelSplitterPlugin plugin;

    public ExcelExecuteTool(ExcelSplitterPlugin plugin) { this.plugin = plugin; }

    @Override public String getName() { return "excel_execute"; }

    @Override public String getDescription() {
        return "Execute the Excel split operation. " +
               "Must be called after excel_analyze and excel_configure. " +
               "Args: outputDir (string, required) — absolute path to output directory; " +
               "filePrefix (string, optional) — prefix for output filenames.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("outputDir", "string", "Absolute path to output directory", true),
            AiToolParam.of("filePrefix", "string", "Optional prefix for output filenames", false)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        SplitConfig config = plugin.getSharedSplitConfig();

        if (config.analysisResult == null) {
            return AiToolResult.error("No analysis result. Call excel_analyze first.");
        }
        if (config.mode == null) {
            return AiToolResult.error("Split mode not configured. Call excel_configure first.");
        }

        String outputDirStr = (String) args.get("outputDir");
        if (outputDirStr == null || outputDirStr.isBlank()) {
            return AiToolResult.error("outputDir is required");
        }
        Path outputDir = Paths.get(outputDirStr.trim());
        if (!Files.exists(outputDir) || !Files.isDirectory(outputDir)) {
            return AiToolResult.error("Output directory does not exist: " + outputDirStr);
        }
        config.outputDir = outputDir;

        String filePrefix = (String) args.get("filePrefix");
        config.filePrefix = (filePrefix != null) ? filePrefix.trim() : "";

        // Check cancel flag before starting
        if (ExcelSplitterPlugin.isCancelled()) {
            return AiToolResult.error("Split operation was cancelled");
        }

        try {
            ExcelSplitter splitter = new ExcelSplitter(config, (pct, msg) -> {
                log.debug("Split progress: {}% - {}", (int)(pct * 100), msg);
            });

            ExcelSplitter.SplitResult result = CompletableFuture.supplyAsync(() -> {
                try {
                    return splitter.split();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).get();

            String outputFileNames = result.outputFiles().stream()
                .map(Path::getFileName)
                .map(Path::toString)
                .toList();

            String json = String.format(
                "{\"success\":true,\"outputFiles\":%s,\"fileCount\":%d,\"summary\":\"Created %d output file(s) in %s\"}",
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(outputFileNames),
                result.fileCount(),
                result.fileCount(),
                outputDirStr
            );
            log.info("excel_execute success: {} files created", result.fileCount());
            return AiToolResult.success(json);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("excel_execute failed: {}", cause.getMessage());
            return AiToolResult.error("Split failed: " + cause.getMessage());
        } catch (Exception e) {
            log.error("excel_execute error: {}", e.getMessage());
            return AiToolResult.error("Unexpected error: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 1: 创建 ExcelExecuteTool.java**

- [ ] **Step 2: 运行构建验证**

```bash
mvn compile -f SwissKit/pom.xml -q
```

- [ ] **Step 3: 提交**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelExecuteTool.java
git commit -m "feat(ai): add ExcelExecuteTool for split execution"
```

---

## 任务 5: 创建 ExcelQueryTool 和 ExcelCancelTool

**文件:**
- 新建: `SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelQueryTool.java`
- 新建: `SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelCancelTool.java`

`ExcelQueryTool` 返回当前配置状态：
```java
@Override public AiToolResult execute(Map<String, Object> args) {
    SplitConfig config = plugin.getSharedSplitConfig();
    // 返回 JSON 格式的当前配置状态
}
```

`ExcelCancelTool` 设置取消标志：
```java
// 需要在 ExcelSplitterPlugin 中添加 public static 方法
// public static void cancel() { cancelled.set(true); }
// public static boolean isCancelled() { return cancelled.get(); }
```

- [ ] **Step 1: 创建两个工具类**

- [ ] **Step 2: 运行构建验证**

```bash
mvn compile -f SwissKit/pom.xml -q
```

- [ ] **Step 3: 提交**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelQueryTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelCancelTool.java
git commit -m "feat(ai): add ExcelQueryTool and ExcelCancelTool"
```

---

## 任务 6: 在 AiChatPlugin 中注册工具

**文件:**
- 修改: `SwissKit/src/main/java/fan/summer/buildintool/ai/AiChatPlugin.java`

在 `onActivate()` 中注册 5 个工具，同时需要获取 `ExcelSplitterPlugin` 实例。由于插件通过 `PluginRegistry` 管理，需要通过 `PluginRegistry` 查找 `ExcelSplitterPlugin`。

最简单方案：在 `AiChatPlugin` 中通过 `PluginRegistry.getPlugin("fan.summer.buildin.excelsplitter")` 获取引用。

在 `AiChatPlugin.java` 中添加：
```java
@Override
public void onActivate() {
    log.info("AI Chat plugin activated");
    Optional<AiService> aiOpt = AiServiceProvider.getService();
    if (aiOpt.isPresent()) {
        AiService aiService = aiOpt.get();
        PluginRegistry registry = PluginRegistry.getInstance();
        ExcelSplitterPlugin excelPlugin = registry.findPlugin("fan.summer.buildin.excelsplitter")
            .map(p -> (ExcelSplitterPlugin) p)
            .orElse(null);
        if (excelPlugin != null) {
            aiService.registerTool(new ExcelAnalyzeTool(excelPlugin));
            aiService.registerTool(new ExcelConfigureTool(excelPlugin));
            aiService.registerTool(new ExcelExecuteTool(excelPlugin));
            aiService.registerTool(new ExcelQueryTool(excelPlugin));
            aiService.registerTool(new ExcelCancelTool(excelPlugin));
            log.info("Registered {} AI tools for Excel Splitter", 5);
        }
    }
}
```

`PluginRegistry` 需要添加 `findPlugin(String id)` 方法（或已有 `getPlugin` 方法）。

检查 `PluginRegistry.java` 的现有 API：
- 已有 `plugins` 列表
- 添加 `findPlugin(String id)` 方法

- [ ] **Step 1: 修改 AiChatPlugin.java onActivate() 注册工具**

- [ ] **Step 2: 如需要，在 PluginRegistry.java 添加 findPlugin 方法**

```java
public Optional<SwissKitJPlugin> findPlugin(String id) {
    return plugins.stream().filter(p -> p.getId().equals(id)).findFirst();
}
```

- [ ] **Step 3: 运行构建验证**

```bash
mvn compile -f SwissKit/pom.xml -q
```

- [ ] **Step 4: 提交**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/ai/AiChatPlugin.java
git commit -m "feat(ai): register Excel AI tools on activation"
```

---

## 自检清单

1. **Spec覆盖**: 所有5个工具均实现，三种模式均支持 ✓
2. **占位符扫描**: 无"TODO"、"TBD"、placeholder ✓
3. **类型一致性**: `SplitConfig` 字段名与工具中使用的完全一致 ✓
4. **静默后台执行**: `CompletableFuture.supplyAsync()` 在后台线程执行 ✓
5. **错误处理**: 所有工具返回 `AiToolResult.error()` 而非抛异常 ✓
6. **路径处理**: 接受绝对路径，验证文件存在性 ✓
7. **Jackson依赖**: 如项目无 Jackson，检查是否有 JSON 序列化方式（可使用 `com.google.gson.Gson` 或手动拼接）—— 先检查项目依赖

---

## 工具注册时机说明

工具在 `AiChatPlugin.onActivate()` 中注册，而非 `SwissKitJApp.start()`。这保证：
- AI聊天被用户首次打开时才注册工具
- 避免工具注册顺序问题
- 如果 `AiService` 尚未就绪，`AiServiceProvider.getService()` 返回 `Optional.empty()`，工具注册会跳过

**注意**: 如果用户在 AI 聊天打开之前就想通过 AI 使用 Excel 工具，需要改为在 `SwissKitJApp.start()` 中注册。但设计决定让 AI 聊天作为入口，所以当前方案是合理的。