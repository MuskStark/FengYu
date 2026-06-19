package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.buildintool.excelsplitter.*;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * AI tool that analyzes an Excel file and returns its structure.
 *
 * <p>Reads the source Excel file and produces a summary containing all sheet names,
 * the number of columns in each sheet (as determined by the header row), and the
 * header names themselves. The result is stored in the shared {@link SplitConfig}
 * for use by subsequent tools in the workflow.</p>
 *
 * <p>Required arguments:</p>
 * <ul>
 *   <li>{@code filePath} (string, required) — absolute path to the {@code .xlsx} or {@code .xls} file</li>
 * </ul>
 *
 * <p>Workflow order:</p>
 * <ol>
 *   <li>{@code ExcelAnalyzeTool} — analyze (first step)</li>
 *   <li>{@link ExcelConfigureTool} — configure split mode</li>
 *   <li>{@link ExcelExecuteTool} — execute split</li>
 * </ol>
 *
 * @see ExcelConfigureTool
 * @see ExcelExecuteTool
 * @see ExcelQueryTool
 */

public class ExcelAnalyzeTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(ExcelAnalyzeTool.class);
    private final ExcelSplitterPlugin plugin;

    public ExcelAnalyzeTool(ExcelSplitterPlugin plugin) { this.plugin = plugin; }

    @Override public String getName() { return "excel_analyze"; }

    @Override public String getDescription() {
        return "Analyze (read) an Excel .xlsx/.xls file and return every sheet name, row counts, and column headers. "
               + "This is the FIRST step before configuring or splitting a file. "
               + "Example: excel_analyze{filePath:\"/path/file.xlsx\"}.";
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
                Map<String, Object> sheet = new LinkedHashMap<>();
                sheet.put("name", e.getKey());
                sheet.put("headerCount", e.getValue().size());
                sheet.put("headers", new ArrayList<>(e.getValue().values()));
                sheets.add(sheet);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("sheets", sheets);
            result.put("totalSheets", analysisResult.size());
            result.put("sourceFile", filePath.getFileName().toString());

            log.info("excel_analyze success: {} sheets found", analysisResult.size());
            return AiToolResult.success(JsonHelper.toJson(result));
        } catch (Exception e) {
            log.error("excel_analyze failed: {}", e.getMessage());
            return AiToolResult.error("Analysis failed: " + e.getMessage());
        }
    }
}