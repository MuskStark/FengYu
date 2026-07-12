package fan.summer.fengyu.plugin.excel.ai;

import fan.summer.fengyu.api.ai.FengYuTool;
import fan.summer.fengyu.plugin.excel.ExcelSessionStore;
import fan.summer.fengyu.plugin.excel.ExcelSplitter;
import fan.summer.fengyu.plugin.excel.SplitConfig;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ExcelExecuteTool implements FengYuTool {
    private final ExcelSessionStore sessions;
    public ExcelExecuteTool(ExcelSessionStore sessions) { this.sessions = sessions; }

    @Tool(name = "excel_execute",
          description = "Execute the configured Excel split. Args: outputDir (absolute path), "
                      + "filePrefix (optional). Run excel_analyze + excel_configure first.")
    public String execute(String outputDir, String filePrefix) {
        SplitConfig c = sessions.active().orElse(null);
        if (c == null || c.analysisResult == null) return ToolJson.err("Call excel_analyze first.");
        if (c.mode == null) return ToolJson.err("Call excel_configure first.");
        if (outputDir == null || outputDir.isBlank()) return ToolJson.err("outputDir is required");
        c.outputDir = Paths.get(outputDir.trim());
        c.filePrefix = filePrefix != null ? filePrefix.trim() : "";
        try { Files.createDirectories(c.outputDir); } catch (Exception ignored) {}
        ExcelSplitter.SplitResult res;
        try { res = new ExcelSplitter(c, null).split(); }
        catch (Exception e) { return ToolJson.err("Split failed: " + e.getMessage()); }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("fileCount", res.fileCount());
        extra.put("files", res.outputFiles().stream().map(p -> p.getFileName().toString()).toList());
        return ToolJson.ok("wrote " + res.fileCount() + " file(s)", extra);
    }
}
