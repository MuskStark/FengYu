package fan.summer.buildintool.pdftool.ai;

import fan.summer.api.ai.AiTool;
import fan.summer.api.ai.AiToolParam;
import fan.summer.api.ai.AiToolResult;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import fan.summer.buildintool.pdftool.worker.PdfMergeWorker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class PdfMergeAiTool implements AiTool {

    private static final PluginLogger log = LoggerFactory.getLogger(PdfMergeAiTool.class);

    @Override public String getName() { return "pdf_merge"; }

    @Override public String getDescription() {
        return "Merge multiple PDF files into one.\n"
             + "Args: filePaths (string[], required) — ordered list of PDF file paths;\n"
             + "      outputPath (string, required) — output file path for merged PDF.\n"
             + "Example: pdf_merge{\"filePaths\":[\"/a.pdf\",\"/b.pdf\"],\"outputPath\":\"/out.pdf\"}.";
    }

    @Override public String getLocalDescription() {
        return "Merge PDFs. Args: filePaths (string[]), outputPath (string).\n"
             + "Example: pdf_merge{\"filePaths\":[\"/a.pdf\",\"/b.pdf\"],\"outputPath\":\"/o.pdf\"}.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("filePaths", "string[]", "Ordered list of PDF file paths to merge", true),
            AiToolParam.of("outputPath", "string", "Output file path for merged PDF", true)
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public AiToolResult execute(Map<String, Object> args) {
        Object rawPaths = args.get("filePaths");
        String outputPath = (String) args.get("outputPath");

        if (rawPaths == null) return AiToolResult.error("filePaths is required");
        if (outputPath == null || outputPath.isBlank()) return AiToolResult.error("outputPath is required");

        List<String> pathStrings;
        if (rawPaths instanceof List<?> list) {
            pathStrings = list.stream().map(Object::toString).toList();
        } else {
            return AiToolResult.error("filePaths must be an array of strings");
        }

        if (pathStrings.size() < 2) return AiToolResult.error("At least 2 files required for merge");

        List<Path> paths = new ArrayList<>();
        for (String p : pathStrings) {
            Path path = Path.of(p);
            if (!Files.exists(path)) return AiToolResult.error("File not found: " + p);
            paths.add(path);
        }

        try {
            PdfMergeWorker worker = new PdfMergeWorker(paths, Path.of(outputPath));
            Path result = CompletableFuture.supplyAsync(() -> {
                try {
                    return worker.call();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).join();

            log.info("AI pdf_merge success: {} files -> {}", paths.size(), result.getFileName());
            return AiToolResult.success("Merge complete: " + result.getFileName());
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("AI pdf_merge failed: {}", cause.getMessage());
            return AiToolResult.error("PDF merge failed: " + cause.getMessage());
        }
    }
}
