package fan.summer.Registrar;

import fan.summer.api.SwissKitJPlugin;
import fan.summer.buildintool.ai.AiChatPlugin;
import fan.summer.buildintool.dev.Base64Plugin;
import fan.summer.buildintool.dev.HashCalculatorPlugin;
import fan.summer.buildintool.dev.JsonFormatterPlugin;
import fan.summer.buildintool.email.EmailPlugin;
import fan.summer.buildintool.emailarchive.EmailArchivePlugin;
import fan.summer.buildintool.excelsplitter.ExcelSplitterPlugin;
import fan.summer.buildintool.pdftool.PdfToolPlugin;
import fan.summer.buildintool.image.ColorConverterPlugin;
import fan.summer.buildintool.text.MarkdownEditorPlugin;
import fan.summer.plugin.PluginLoader;
import fan.summer.plugin.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Registers all built-in tools directly into the {@link PluginRegistry}, bypassing
 * the external JAR plugin loading mechanism.
 *
 * <p>This class is responsible for populating the application with the set of tools
 * that are compiled into the main application JAR rather than loaded at runtime from
 * the {@code plugins/} directory. Each built-in tool is instantiated directly and
 * added to the registry's plugin list during application startup, before
 * {@link PluginLoader#start()} is called.</p>
 *
 * <p>Built-in tools do not use the Java ServiceLoader mechanism; they implement
 * {@link SwissKitJPlugin} and are registered programmatically via
 * {@link #register(PluginLoader, PluginRegistry)}. This allows them to be activated
 * and deactivated through the same {@link PluginRegistry} API as external plugins.</p>
 *
 * @see PluginRegistry
 * @see PluginLoader
 * @see SwissKitJPlugin
 * @since 1.0
 */
public class BuiltinToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(BuiltinToolRegistrar.class);

    /**
     * Instantiates and registers all built-in tools into the given registry.
     *
     * <p>This method creates a new instance of each built-in plugin, adds them all to
     * the registry's observable plugin list, and logs the outcome at INFO level.
     * It does not interact with {@link PluginLoader} beyond the mandatory wiring in
     * {@link PluginRegistry} constructor.</p>
     *
     * <p>This method should be called exactly once during application startup,
     * after the {@link PluginRegistry} is constructed but before
     * {@link PluginLoader#start()} is invoked.</p>
     *
     * @param loader  the PluginLoader instance (used only to pass to
     *                {@link PluginRegistry#PluginRegistry(PluginLoader)} wiring;
     *                may be {@code null} if already wired)
     * @param registry the PluginRegistry to register built-in tools into; must not be {@code null}
     * @since 1.0
     */
    public static void register(PluginLoader loader, PluginRegistry registry) {
        List<SwissKitJPlugin> builtins = List.of(
            new AiChatPlugin(),
            new JsonFormatterPlugin(),
            new Base64Plugin(),
            new HashCalculatorPlugin(),
            new ExcelSplitterPlugin(),
            new ColorConverterPlugin(),
            new MarkdownEditorPlugin(),
            new EmailPlugin(),
            new EmailArchivePlugin(),
            new PdfToolPlugin()
        );
        registry.getPlugins().addAll(builtins);
        for (SwissKitJPlugin p : builtins) {
            log.debug("Registered built-in tool: id={}, name={}, version={}",
                    p.getId(), p.getName(), p.getVersion());
        }
        log.info("Built-in tool registration complete, total={}", builtins.size());
    }
}
