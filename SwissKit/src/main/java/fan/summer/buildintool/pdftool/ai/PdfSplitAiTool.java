package fan.summer.buildintool.pdftool.ai;

import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolParam;
import fan.summer.zhiflow.api.ai.AiToolResult;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import fan.summer.buildintool.pdftool.worker.PdfSplitWorker;
import fan.summer.ai.util.JsonHelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class PdfSplitAiTool implements AiTool {

    private static final PluginLogger log = LoggerFactory.getLogger(PdfSplitAiTool.class);

    @Override public String getName() { return "pdf_split"; }

    @Override public String getDescription() {
        return "Split a PDF file into multiple files by page ranges.\n"
             + "Args: filePath (string, required) — absolute path to PDF;\n"
             + "      ranges (string, required) — page ranges like '1-3,5,8-10';\n"
             + "      outputDir (string, required) — output directory.\n"
             + "Example: pdf_split{\"filePath\":\"/a.pdf\",\"ranges\":\"1-3,5\",\"outputDir\":\"/out\"}.";
    }

    @Override public String getLocalDescription() {
        return "Split PDF by page ranges. Args: filePath (string), ranges (e.g. '1-3,5,8-10'), outputDir (string).\n"
             + "Example: pdf_split{\"filePath\":\"/a.pdf\",\"ranges\":\"1-3\",\"outputDir\":\"/out\"}.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("filePath", "string", "Absolute path to the PDF file", true),
            AiToolParam.of("ranges", "string", "Page ranges, e.g. '1-3,5,8-10'", true),
            AiToolParam.of("outputDir", "string", "Output directory for split files", true)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String filePath = (String) args.get("filePath");
        String ranges = (String) args.get("ranges");
        String outputDir = (String) args.get("outputDir");

        if (filePath == null || filePath.isBlank())
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "filePath is required")));
        if (ranges == null || ranges.isBlank())
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "ranges is required")));
        if (outputDir == null || outputDir.isBlank())
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "outputDir is required")));

        Path pdf = Path.of(filePath);
        if (!Files.exists(pdf))
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "File not found: " + filePath)));

        List<int[]> parsedRanges = parseRanges(ranges);
        if (parsedRanges == null || parsedRanges.isEmpty())
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "Invalid ranges: " + ranges)));

        try {
            PdfSplitWorker worker = new PdfSplitWorker(pdf, parsedRanges, Path.of(outputDir));
            List<Path> outputs = CompletableFuture.supplyAsync(() -> {
                try {
                    return worker.call();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).join();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("summary", "Split into " + outputs.size() + " file(s)");
            result.put("outputFiles", outputs.stream().map(p -> p.getFileName().toString()).toList());
            log.info("AI pdf_split success: {} -> {} files", filePath, outputs.size());
            return AiToolResult.success(JsonHelper.toJson(result));
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("AI pdf_split failed: {}", cause.getMessage());
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "PDF split failed: " + cause.getMessage())));
        }
    }

    /**
     * Parses a range string like "1-3,5,8-10" into a list of [start, end] arrays.
     */
    private static List<int[]> parseRanges(String input) {
        if (input == null || input.isBlank()) return null;

        List<int[]> ranges = new ArrayList<>();
        String[] parts = input.split(",");

        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) return null;

            if (trimmed.contains("-")) {
                String[] bounds = trimmed.split("-", 2);
                if (bounds.length != 2) return null;
                try {
                    int start = Integer.parseInt(bounds[0].trim());
                    int end = Integer.parseInt(bounds[1].trim());
                    if (start < 1 || end < start) return null;
                    ranges.add(new int[]{start, end});
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                try {
                    int page = Integer.parseInt(trimmed);
                    if (page < 1) return null;
                    ranges.add(new int[]{page, page});
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }

        return ranges.isEmpty() ? null : ranges;
    }
}
