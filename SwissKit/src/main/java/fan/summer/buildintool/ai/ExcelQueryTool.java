package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.buildintool.excelsplitter.*;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.util.*;

public class ExcelQueryTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(ExcelQueryTool.class);
    private final ExcelSplitterPlugin plugin;

    public ExcelQueryTool(ExcelSplitterPlugin plugin) { this.plugin = plugin; }

    @Override public String getName() { return "excel_query"; }

    @Override public String getDescription() {
        return "Query the current Excel split configuration state. " +
               "Returns source file, mode, configured sheets/columns, and output directory. " +
               "No arguments required.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of();
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        SplitConfig config = plugin.getSharedSplitConfig();

        Map<String, Object> result = new LinkedHashMap<>();
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