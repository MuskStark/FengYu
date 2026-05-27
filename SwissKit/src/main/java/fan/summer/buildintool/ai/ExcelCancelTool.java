package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
import fan.summer.buildintool.excelsplitter.*;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

public class ExcelCancelTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(ExcelCancelTool.class);

    @Override public String getName() { return "excel_cancel"; }

    @Override public String getDescription() {
        return "Cancel the currently running Excel split operation. " +
               "No arguments required. Returns success even if no operation was running.";
    }

    @Override public java.util.List<AiToolParam> getParameters() {
        return java.util.List.of();
    }

    @Override public AiToolResult execute(java.util.Map<String, Object> args) {
        ExcelSplitterPlugin.cancel();
        log.info("excel_cancel: split operation cancelled");
        return AiToolResult.success("{\"cancelled\":true,\"summary\":\"Split operation has been cancelled\"}");
    }
}