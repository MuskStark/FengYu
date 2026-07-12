package fan.summer.fengyu.plugin.excel.ai;

import fan.summer.fengyu.api.ai.FengYuTool;
import fan.summer.fengyu.plugin.excel.ExcelSessionStore;
import fan.summer.fengyu.plugin.excel.SplitConfig;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ExcelQueryTool implements FengYuTool {
    private final ExcelSessionStore sessions;
    public ExcelQueryTool(ExcelSessionStore sessions) { this.sessions = sessions; }

    @Tool(name = "excel_query", description = "Query the current Excel split configuration state.")
    public String query() {
        SplitConfig c = sessions.active().orElse(null);
        if (c == null) return ToolJson.err("No active Excel session; call excel_analyze first.");
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("sourceFile", c.sourceFile != null ? c.sourceFile.toString() : null);
        extra.put("mode", c.mode != null ? c.mode.name() : null);
        extra.put("selectedSheets", c.selectedSheets);
        extra.put("splitSheet", c.splitSheet);
        extra.put("splitColumnIndex", c.splitColumnIndex);
        extra.put("complexEntries", c.complexEntries.size());
        extra.put("outputDir", c.outputDir != null ? c.outputDir.toString() : null);
        return ToolJson.ok("mode=" + (c.mode != null ? c.mode.name() : "unset"), extra);
    }
}
