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

            // 手动构建JSON（无Jackson）
            StringBuilder sheetsJson = new StringBuilder("[");
            int idx = 0;
            for (Map.Entry<String, Map<Integer, String>> e : analysisResult.entrySet()) {
                if (idx > 0) sheetsJson.append(",");
                sheetsJson.append("{\"name\":\"").append(jsonEscape(e.getKey()))
                    .append("\",\"headerCount\":").append(e.getValue().size())
                    .append(",\"headers\":[");
                int hIdx = 0;
                for (String header : e.getValue().values()) {
                    if (hIdx > 0) sheetsJson.append(",");
                    sheetsJson.append("\"").append(jsonEscape(header)).append("\"");
                    hIdx++;
                }
                sheetsJson.append("]}");
                idx++;
            }
            sheetsJson.append("]");

            String json = "{\"success\":true,\"sheets\":" + sheetsJson +
                    ",\"totalSheets\":" + sheets.size() +
                    ",\"sourceFile\":\"" + jsonEscape(filePath.getFileName().toString()) + "\"}";
            log.info("excel_analyze success: {} sheets found", sheets.size());
            return AiToolResult.success(json);
        } catch (Exception e) {
            log.error("excel_analyze failed: {}", e.getMessage());
            return AiToolResult.error("Analysis failed: " + e.getMessage());
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}