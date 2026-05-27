package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
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

        StringBuilder json = new StringBuilder("{");
        json.append("\"sourceFile\":");
        if (config.sourceFile != null) {
            json.append("\"").append(jsonEscape(config.sourceFile.toString())).append("\"");
        } else {
            json.append("null");
        }
        json.append(",\"mode\":");
        if (config.mode != null) {
            json.append("\"").append(config.mode.name()).append("\"");
        } else {
            json.append("null");
        }
        json.append(",\"selectedSheets\":");
        if (config.selectedSheets != null) {
            json.append(listToJson(config.selectedSheets));
        } else {
            json.append("null");
        }
        json.append(",\"splitSheet\":");
        if (config.splitSheet != null) {
            json.append("\"").append(jsonEscape(config.splitSheet)).append("\"");
        } else {
            json.append("null");
        }
        json.append(",\"splitColumn\":");
        if (config.splitColumn != null) {
            json.append("\"").append(jsonEscape(config.splitColumn)).append("\"");
        } else {
            json.append("null");
        }
        json.append(",\"splitColumnIndex\":").append(config.splitColumnIndex);
        json.append(",\"complexTaskId\":");
        if (config.complexTaskId != null) {
            json.append("\"").append(jsonEscape(config.complexTaskId)).append("\"");
        } else {
            json.append("null");
        }
        json.append(",\"outputDir\":");
        if (config.outputDir != null) {
            json.append("\"").append(jsonEscape(config.outputDir.toString())).append("\"");
        } else {
            json.append("null");
        }
        json.append("}");

        log.debug("excel_query returned state");
        return AiToolResult.success(json.toString());
    }

    private static String listToJson(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(jsonEscape(list.get(i))).append("\"");
            }
        }
        return sb.append("]").toString();
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}