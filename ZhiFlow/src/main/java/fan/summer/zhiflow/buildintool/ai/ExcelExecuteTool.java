package fan.summer.zhiflow.buildintool.ai;

import fan.summer.zhiflow.api.ai.*;
import fan.summer.zhiflow.ai.util.JsonHelper;
import fan.summer.zhiflow.buildintool.excelsplitter.*;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * AI tool that executes an Excel file split operation.
 *
 * <p>This tool performs the actual split based on the configuration previously set by
 * {@link ExcelConfigureTool}. It validates that analysis and configuration have been
 * performed, then runs the split operation asynchronously and reports the resulting
 * output files.</p>
 *
 * <p>Required execution order:</p>
 * <ol>
 *   <li>{@link ExcelAnalyzeTool} — analyze the source file</li>
 *   <li>{@link ExcelConfigureTool} — configure the split mode and parameters</li>
 *   <li>{@code ExcelExecuteTool} — execute the split</li>
 * </ol>
 *
 * @see ExcelAnalyzeTool
 * @see ExcelConfigureTool
 * @see ExcelQueryTool
 */

public class ExcelExecuteTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(ExcelExecuteTool.class);
    private final ExcelSplitterPlugin plugin;

    public ExcelExecuteTool(ExcelSplitterPlugin plugin) { this.plugin = plugin; }

    @Override public String getName() { return "excel_execute"; }

    @Override public String getDescription() {
        return "Execute the configured Excel split and write output files. Must be called after excel_analyze and excel_configure.\n"
             + "Args: outputDir (string, required) — absolute path to output directory;\n"
             + "      filePrefix (string, optional) — prefix for output filenames.\n"
             + "Example: excel_execute{\"outputDir\":\"/out\",\"filePrefix\":\"result_\"}.";
    }

    @Override public String getLocalDescription() {
        return "Run configured split. Args: outputDir (string), filePrefix (string, optional).\n"
             + "Example: excel_execute{\"outputDir\":\"/tmp/out\"}.";
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
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "No analysis result. Call excel_analyze first.")));
        }
        if (config.mode == null) {
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "Split mode not configured. Call excel_configure first.")));
        }

        String outputDirStr = (String) args.get("outputDir");
        if (outputDirStr == null || outputDirStr.isBlank()) {
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "outputDir is required")));
        }
        Path outputDir = Paths.get(outputDirStr.trim());
        if (!Files.exists(outputDir) || !Files.isDirectory(outputDir)) {
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "Output directory does not exist: " + outputDirStr)));
        }
        config.outputDir = outputDir;

        String filePrefix = (String) args.get("filePrefix");
        config.filePrefix = (filePrefix != null) ? filePrefix.trim() : "";

        try {
            ExcelSplitter splitter = new ExcelSplitter(config, (pct, msg) -> {
                log.debug("Split progress: {}% - {}", (int)(pct * 100), msg);
            });

            ExcelSplitter.SplitResult splitResult = CompletableFuture.supplyAsync(() -> {
                try {
                    return splitter.split();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).get();

            List<String> fileNames = new ArrayList<>();
            for (Path f : splitResult.outputFiles()) {
                fileNames.add(f.getFileName().toString());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("outputFiles", fileNames);
            result.put("fileCount", splitResult.fileCount());
            result.put("summary", "Created " + splitResult.fileCount() + " output file(s) in " + outputDirStr);

            log.info("excel_execute success: {} files created", splitResult.fileCount());
            return AiToolResult.success(JsonHelper.toJson(result));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("excel_execute failed: {}", cause.getMessage());
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "Split failed: " + cause.getMessage())));
        } catch (Exception e) {
            log.error("excel_execute error: {}", e.getMessage());
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "Unexpected error: " + e.getMessage())));
        }
    }
}