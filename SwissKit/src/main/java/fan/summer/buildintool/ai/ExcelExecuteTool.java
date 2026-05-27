package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
import fan.summer.ai.util.JsonHelper;
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
            return AiToolResult.error("Split failed: " + cause.getMessage());
        } catch (Exception e) {
            log.error("excel_execute error: {}", e.getMessage());
            return AiToolResult.error("Unexpected error: " + e.getMessage());
        }
    }
}