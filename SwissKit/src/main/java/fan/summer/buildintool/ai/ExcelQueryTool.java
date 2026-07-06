package fan.summer.buildintool.ai;

import fan.summer.zhiflow.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.buildintool.excelsplitter.*;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;

import java.util.*;

/**
 * AI tool that queries the current Excel split configuration state.
 *
 * <p>Returns the live state of the shared {@link SplitConfig} without modifying it.
 * This can be used at any point in the workflow to check what file has been analyzed,
 * which mode is configured, and what output directory has been set.</p>
 *
 * <p>No arguments are required.</p>
 *
 * @see ExcelAnalyzeTool
 * @see ExcelConfigureTool
 * @see ExcelExecuteTool
 */

public class ExcelQueryTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(ExcelQueryTool.class);
    private final ExcelSplitterPlugin plugin;

    public ExcelQueryTool(ExcelSplitterPlugin plugin) { this.plugin = plugin; }

    @Override public String getName() { return "excel_query"; }

    @Override public String getDescription() {
        return "Query the current Excel split configuration state: source file, mode, selected sheets/columns, output directory.\n"
             + "Call to inspect progress before or between operations. No arguments.\n"
             + "Example: excel_query{}.";
    }

    @Override public String getLocalDescription() {
        return "Query current Excel split state. No args.\n"
             + "Example: excel_query{}.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of();
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        SplitConfig config = plugin.getSharedSplitConfig();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("summary", "Current state: mode=" + (config.mode != null ? config.mode.name() : "unset"));
        result.put("sourceFile", config.sourceFile != null ? config.sourceFile.toString() : null);
        result.put("mode", config.mode != null ? config.mode.name() : null);
        result.put("selectedSheets", config.selectedSheets);
        result.put("splitSheet", config.splitSheet);
        result.put("splitColumn", config.splitColumn);
        result.put("splitColumnIndex", config.splitColumnIndex);
        result.put("complexTaskId", config.complexTaskId);
        result.put("outputDir", config.outputDir != null ? config.outputDir.toString() : null);

        log.debug("excel_query returned state");
        return AiToolResult.success(JsonHelper.toJson(result));
    }
}