package fan.summer.fengyu.plugin.excel.ai;

import fan.summer.fengyu.api.ai.FengYuTool;
import fan.summer.fengyu.plugin.excel.ExcelSessionStore;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class ExcelCancelTool implements FengYuTool {
    private final ExcelSessionStore sessions;
    public ExcelCancelTool(ExcelSessionStore sessions) { this.sessions = sessions; }

    @Tool(name = "excel_cancel", description = "Cancel/reset the current Excel split session.")
    public String cancel() {
        sessions.remove(ExcelAnalyzeTool.AI_SESSION);
        return ToolJson.ok("session reset", null);
    }
}
