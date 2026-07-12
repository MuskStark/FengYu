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
public class ExcelAnalyzeTool implements FengYuTool {
    static final String AI_SESSION = "ai";
    private final ExcelSessionStore sessions;
    public ExcelAnalyzeTool(ExcelSessionStore sessions) { this.sessions = sessions; }

    @Tool(name = "excel_analyze",
          description = "Analyze an Excel .xlsx/.xls file: returns sheet names and column headers. "
                      + "Arg: filePath (absolute path to the Excel file).")
    public String analyze(String filePath) {
        if (filePath == null || filePath.isBlank()) return ToolJson.err("filePath is required");
        Path p = Paths.get(filePath.trim());
        if (!Files.exists(p) || !Files.isReadable(p)) return ToolJson.err("File not found: " + filePath);
        SplitConfig cfg = sessions.get(AI_SESSION);
        cfg.sourceFile = p;
        try { cfg.analysisResult = ExcelSplitter.analyze(p); }
        catch (Exception e) { return ToolJson.err("Analyze failed: " + e.getMessage()); }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("sheets", cfg.analysisResult.keySet());
        return ToolJson.ok("analyzed " + cfg.analysisResult.size() + " sheet(s)", extra);
    }
}
