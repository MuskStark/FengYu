package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
import fan.summer.buildintool.excelsplitter.*;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

/**
 * AI tool that cancels a currently running Excel split operation.
 *
 * <p>Sends a cancellation signal to the shared {@link ExcelSplitterPlugin} execution
 * context. It is safe to call even if no operation is currently in progress.</p>
 *
 * <p>No arguments are required.</p>
 *
 * @see ExcelExecuteTool
 */

public class ExcelCancelTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(ExcelCancelTool.class);

    @Override public String getName() { return "excel_cancel"; }

    @Override public String getDescription() {
        return "Cancel the Excel split operation currently running. Safe to call even when nothing is running. "
               + "No arguments. Example: excel_cancel{}.";
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