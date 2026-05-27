package fan.summer.ai.tools;

import fan.summer.api.ai.AiServiceProvider;
import fan.summer.buildintool.ai.*;
import fan.summer.buildintool.excelsplitter.ExcelSplitterPlugin;
import fan.summer.plugin.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class BuiltinAiToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(BuiltinAiToolRegistrar.class);

    public static void register() {
        AiServiceProvider.registerTool(new BuiltinBase64Tool());
        AiServiceProvider.registerTool(new BuiltinHashTool());
        AiServiceProvider.registerTool(new BuiltinJsonFormatTool());
        AiServiceProvider.registerTool(new BuiltinColorConvertTool());

        registerExcelTools();

        log.info("Built-in AI tools registered: base64, hash_calculate, json_format, color_convert, excel_*");
    }

    private static void registerExcelTools() {
        PluginRegistry registry = PluginRegistry.getInstance();
        if (registry == null) return;

        Optional<ExcelSplitterPlugin> opt = registry.findPlugin("fan.summer.buildin.excelsplitter")
            .map(p -> (ExcelSplitterPlugin) p);
        if (opt.isEmpty()) return;

        ExcelSplitterPlugin plugin = opt.get();
        AiServiceProvider.registerTool(new ExcelAnalyzeTool(plugin));
        AiServiceProvider.registerTool(new ExcelConfigureTool(plugin));
        AiServiceProvider.registerTool(new ExcelExecuteTool(plugin));
        AiServiceProvider.registerTool(new ExcelQueryTool(plugin));
        AiServiceProvider.registerTool(new ExcelCancelTool());
        log.info("Excel AI tools registered (5 tools)");
    }
}
