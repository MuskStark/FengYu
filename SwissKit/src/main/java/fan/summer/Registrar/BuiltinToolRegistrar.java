package fan.summer.Registrar;

import fan.summer.buildintool.dev.Base64Plugin;
import fan.summer.buildintool.dev.HashCalculatorPlugin;
import fan.summer.buildintool.dev.JsonFormatterPlugin;
import fan.summer.buildintool.excelsplitter.ExcelSplitterPlugin;
import fan.summer.buildintool.image.ColorConverterPlugin;
import fan.summer.buildintool.text.MarkdownEditorPlugin;
import fan.summer.plugin.PluginLoader;
import fan.summer.plugin.PluginRegistry;

/**
 * Registers all built-in tools directly into PluginRegistry, bypassing the JAR plugin loader.
 */
public class BuiltinToolRegistrar {

    public static void register(PluginLoader loader, PluginRegistry registry) {
        registry.getPlugins().addAll(
            new JsonFormatterPlugin(),
            new Base64Plugin(),
            new HashCalculatorPlugin(),
            new ExcelSplitterPlugin(),
            new ColorConverterPlugin(),
            new MarkdownEditorPlugin()
        );
    }
}
