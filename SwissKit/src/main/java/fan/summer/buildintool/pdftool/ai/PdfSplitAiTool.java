package fan.summer.buildintool.pdftool.ai;

import fan.summer.api.ai.AiTool;
import fan.summer.api.ai.AiToolParam;
import fan.summer.api.ai.AiToolResult;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import fan.summer.buildintool.pdftool.worker.PdfSplitWorker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class PdfSplitAiTool implements AiTool {

    private static final PluginLogger log = LoggerFactory.getLogger(PdfSplitAiTool.class);

    @Override public String getName() { return "pdf_split"; }

    @Override public String getDescription() {
        return "Split a PDF file into multiple files by page ranges. " +
               "Args: filePath (string) — absolute path to PDF; " +
               "ranges (string) — page ranges like '1-3,5,8-10'; " +
               "outputDir (string) — output directory.";
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

        if (filePath == null || filePath.isBlank()) return AiToolResult.error("filePath is required");
        if (ranges == null || ranges.isBlank()) return AiToolResult.error("ranges is required");
        if (outputDir == null || outputDir.isBlank()) return AiToolResult.error("outputDir is required");

        Path pdf = Path.of(filePath);
        if (!Files.exists(pdf)) return AiToolResult.error("File not found: " + filePath);

        List<int[]> parsedRanges = parseRanges(ranges);
        if (parsedRanges == null || parsedRanges.isEmpty()) return AiToolResult.error("Invalid ranges: " + ranges);

        try {
            PdfSplitWorker worker = new PdfSplitWorker(pdf, parsedRanges, Path.of(outputDir));
            List<Path> outputs = CompletableFuture.supplyAsync(() -> {
                try {
                    return worker.call();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).join();

            StringBuilder sb = new StringBuilder("Split complete. Output files:\n");
            for (Path p : outputs) sb.append("- ").append(p.getFileName()).append("\n");
            log.info("AI pdf_split success: {} -> {} files", filePath, outputs.size());
            return AiToolResult.success(sb.toString());
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("AI pdf_split failed: {}", cause.getMessage());
            return AiToolResult.error("PDF split failed: " + cause.getMessage());
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
