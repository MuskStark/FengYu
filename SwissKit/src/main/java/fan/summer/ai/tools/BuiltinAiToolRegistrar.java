package fan.summer.ai.tools;

import fan.summer.api.ai.AiServiceProvider;
import fan.summer.buildintool.ai.*;
import fan.summer.buildintool.emailarchive.EmailArchivePlugin;
import fan.summer.buildintool.excelsplitter.ExcelSplitterPlugin;
import fan.summer.plugin.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Registers all built-in AI tools with {@link AiServiceProvider}.
 *
 * <p>This class wires together the built-in tools ({@link BuiltinBase64Tool},
 * {@link BuiltinHashTool}, {@link BuiltinJsonFormatTool}, {@link BuiltinColorConvertTool})
 * and the Excel-related tools obtained from a live {@link ExcelSplitterPlugin}
 * instance via the {@link PluginRegistry}.</p>
 *
 * <p>Call {@link #register()} once during application startup to make all
 * built-in tools available to the AI layer.</p>
 *
 * @see AiServiceProvider
 * @see PluginRegistry
 */
public class BuiltinAiToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(BuiltinAiToolRegistrar.class);

    /**
     * Registers all built-in tools with {@link AiServiceProvider}.
     * Idempotent — safe to call more than once, though tools are registered only once
     * because the underlying provider ignores duplicate registrations.
     *
     * @see AiServiceProvider#registerTool(AiTool)
     */
    public static void register() {
        AiServiceProvider.registerTool(new BuiltinBase64Tool());
        AiServiceProvider.registerTool(new BuiltinHashTool());
        AiServiceProvider.registerTool(new BuiltinJsonFormatTool());
        AiServiceProvider.registerTool(new BuiltinColorConvertTool());

        registerExcelTools();
        registerEmailArchiveTools();

        log.info("Built-in AI tools registered: base64, hash_calculate, json_format, color_convert, excel_*, email_archive_*");
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
        AiServiceProvider.registerTool(new ExcelComplexConfigTool(plugin));
        AiServiceProvider.registerTool(new ExcelCancelTool());
        log.info("Excel AI tools registered (6 tools)");
    }

    private static void registerEmailArchiveTools() {
        PluginRegistry registry = PluginRegistry.getInstance();
        if (registry == null) return;

        Optional<EmailArchivePlugin> opt = registry.findPlugin("fan.summer.buildin.email-archive")
                .map(p -> (EmailArchivePlugin) p);
        if (opt.isEmpty()) return;

        EmailArchivePlugin plugin = opt.get();
        AiServiceProvider.registerTool(new EmailArchiveFetchTool(plugin));
        AiServiceProvider.registerTool(new EmailArchiveQueryTool());
        log.info("Email archive AI tools registered (2 tools)");
    }
}
