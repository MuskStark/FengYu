package fan.summer.Registrar;

import fan.summer.api.SwissKitJPlugin;
import fan.summer.buildintool.dev.Base64Plugin;
import fan.summer.buildintool.dev.HashCalculatorPlugin;
import fan.summer.buildintool.dev.JsonFormatterPlugin;
import fan.summer.buildintool.email.EmailPlugin;
import fan.summer.buildintool.excelsplitter.ExcelSplitterPlugin;
import fan.summer.buildintool.image.ColorConverterPlugin;
import fan.summer.buildintool.text.MarkdownEditorPlugin;
import fan.summer.plugin.PluginLoader;
import fan.summer.plugin.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Registers all built-in tools directly into PluginRegistry, bypassing the JAR plugin loader.
 */
public class BuiltinToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(BuiltinToolRegistrar.class);

    public static void register(PluginLoader loader, PluginRegistry registry) {
        List<SwissKitJPlugin> builtins = List.of(
            new JsonFormatterPlugin(),
            new Base64Plugin(),
            new HashCalculatorPlugin(),
            new ExcelSplitterPlugin(),
            new ColorConverterPlugin(),
            new MarkdownEditorPlugin(),
            new EmailPlugin()
        );
        registry.getPlugins().addAll(builtins);
        for (SwissKitJPlugin p : builtins) {
            log.debug("Registered built-in tool: id={}, name={}, version={}",
                    p.getId(), p.getName(), p.getVersion());
        }
        log.info("Built-in tool registration complete, total={}", builtins.size());
    }
}
