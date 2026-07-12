package fan.summer.fengyu.plugin.excel.ai;

import fan.summer.fengyu.api.ai.FengYuTool;
import fan.summer.fengyu.plugin.excel.ExcelSessionStore;
import fan.summer.fengyu.plugin.excel.SplitConfig;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ExcelConfigureTool implements FengYuTool {
    private final ExcelSessionStore sessions;
    public ExcelConfigureTool(ExcelSessionStore sessions) { this.sessions = sessions; }

    @Tool(name = "excel_configure",
          description = "Configure the split mode. mode is one of BY_SHEET|BY_COLUMN|COMPLEX. "
                      + "BY_SHEET: optional sheets list (empty=all). BY_COLUMN: splitSheet + splitColumn (header name). "
                      + "COMPLEX: configure entries via excel_complex_config first.")
    public String configure(String mode, List<String> sheets, String splitSheet, String splitColumn) {
        SplitConfig c = sessions.active().orElse(null);
        if (c == null || c.analysisResult == null) return ToolJson.err("Call excel_analyze first.");
        SplitConfig.SplitMode m;
        try { m = SplitConfig.SplitMode.valueOf(mode); }
        catch (Exception e) { return ToolJson.err("Invalid mode: " + mode); }
        c.mode = m;
        switch (m) {
            case BY_SHEET -> c.selectedSheets = (sheets != null && !sheets.isEmpty())
                    ? new ArrayList<>(sheets) : new ArrayList<>(c.analysisResult.keySet());
            case BY_COLUMN -> {
                if (splitSheet == null || splitColumn == null)
                    return ToolJson.err("splitSheet and splitColumn required for BY_COLUMN");
                Map<Integer, String> headers = c.analysisResult.get(splitSheet);
                if (headers == null) return ToolJson.err("Unknown sheet: " + splitSheet);
                Integer idx = null;
                for (var e : headers.entrySet()) if (splitColumn.equals(e.getValue())) { idx = e.getKey(); break; }
                if (idx == null) return ToolJson.err("Unknown column: " + splitColumn);
                c.splitSheet = splitSheet; c.splitColumn = splitColumn; c.splitColumnIndex = idx;
            }
            case COMPLEX -> {
                if (c.complexEntries.isEmpty()) return ToolJson.err("Add entries via excel_complex_config first");
            }
        }
        return ToolJson.ok("configured mode=" + m, null);
    }
}
