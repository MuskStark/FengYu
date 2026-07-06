package fan.summer.buildintool.pdftool.ai;

import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolParam;
import fan.summer.zhiflow.api.ai.AiToolResult;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import fan.summer.buildintool.pdftool.worker.PdfConvertWorker;
import fan.summer.ai.util.JsonHelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class PdfToDocxAiTool implements AiTool {

    private static final PluginLogger log = LoggerFactory.getLogger(PdfToDocxAiTool.class);

    @Override public String getName() { return "pdf_to_docx"; }

    @Override public String getDescription() {
        return "Convert a PDF file to DOCX format.\n"
             + "Args: filePath (string, required) — absolute path to PDF;\n"
             + "      outputDir (string, required) — output directory for the DOCX file.\n"
             + "Example: pdf_to_docx{\"filePath\":\"/a.pdf\",\"outputDir\":\"/out\"}.";
    }

    @Override public String getLocalDescription() {
        return "PDF to DOCX. Args: filePath (string), outputDir (string).\n"
             + "Example: pdf_to_docx{\"filePath\":\"/a.pdf\",\"outputDir\":\"/out\"}.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("filePath", "string", "Absolute path to the PDF file", true),
            AiToolParam.of("outputDir", "string", "Output directory for the DOCX file", true)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String filePath = (String) args.get("filePath");
        String outputDir = (String) args.get("outputDir");

        if (filePath == null || filePath.isBlank())
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "filePath is required")));
        if (outputDir == null || outputDir.isBlank())
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "outputDir is required")));

        Path pdf = Path.of(filePath);
        if (!Files.exists(pdf))
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "File not found: " + filePath)));

        try {
            PdfConvertWorker worker = new PdfConvertWorker(List.of(pdf), Path.of(outputDir));
            List<Path> outputs = CompletableFuture.supplyAsync(() -> {
                try {
                    return worker.call();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).join();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("summary", "Converted to " + outputs.get(0).getFileName());
            out.put("outputPath", outputs.get(0).toString());
            log.info("AI pdf_to_docx success: {} -> {}", filePath, outputs.get(0).getFileName());
            return AiToolResult.success(JsonHelper.toJson(out));
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("AI pdf_to_docx failed: {}", cause.getMessage());
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "PDF to DOCX failed: " + cause.getMessage())));
        }
    }
}
