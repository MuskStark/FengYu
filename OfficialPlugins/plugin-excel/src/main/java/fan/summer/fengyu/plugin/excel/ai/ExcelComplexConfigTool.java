package fan.summer.fengyu.plugin.excel.ai;

import fan.summer.fengyu.api.ai.FengYuTool;
import fan.summer.fengyu.plugin.excel.ComplexSplitEntry;
import fan.summer.fengyu.plugin.excel.ExcelSessionStore;
import fan.summer.fengyu.plugin.excel.SplitConfig;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ExcelComplexConfigTool implements FengYuTool {
    private final ExcelSessionStore sessions;
    public ExcelComplexConfigTool(ExcelSessionStore sessions) { this.sessions = sessions; }

    @Tool(name = "excel_complex_config",
          description = "Manage COMPLEX split entries. action is one of add|list|clear. "
                      + "For add: sheetName, headerIndex (1-based; -1 with columnIndex -1 = copy entire sheet), "
                      + "columnIndex (1-based column to split by).")
    public String complexConfig(String action, String sheetName, int headerIndex, int columnIndex) {
        SplitConfig c = sessions.active().orElse(null);
        if (c == null) return ToolJson.err("Call excel_analyze first.");
        String a = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        switch (a) {
            case "add" -> {
                if (sheetName == null || sheetName.isBlank()) return ToolJson.err("sheetName required for add");
                String field = c.sourceFile != null ? c.sourceFile.getFileName().toString() : "";
                c.complexEntries.add(new ComplexSplitEntry(field, sheetName, headerIndex, columnIndex));
                return ToolJson.ok("added entry; total=" + c.complexEntries.size(), null);
            }
            case "list" -> {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("entries", c.complexEntries.stream().map(e -> Map.of(
                        "sheetName", e.sheetName(), "headerIndex", e.headerIndex(), "columnIndex", e.columnIndex())).toList());
                return ToolJson.ok(c.complexEntries.size() + " entr(ies)", extra);
            }
            case "clear" -> { c.complexEntries.clear(); return ToolJson.ok("cleared", null); }
            default -> { return ToolJson.err("Invalid action: " + action); }
        }
    }
}
