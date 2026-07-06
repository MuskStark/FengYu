package fan.summer.api.host;

import fan.summer.api.PluginContext;
import fan.summer.api.ZhiFlowPlugin;
import fan.summer.api.component.SkNotification;
import fan.summer.api.i18n.I18n;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import fan.summer.api.theme.ThemeService;
import fan.summer.api.theme.Themes;
import javafx.beans.property.StringProperty;
import javafx.scene.Node;
import javafx.scene.Scene;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Skeleton {@link PluginHost}: implements every facade except {@link #settings()},
 * which differs between the real host (H2-backed) and the preview window
 * (properties-file-backed).
 *
 * @since 3.2.0
 */
public abstract class BasePluginHost implements PluginHost {

    protected final ZhiFlowPlugin plugin;
    private final TaskRunner tasks;

    private final I18nFacade i18n = new I18nFacade() {
        @Override public String get(String key, Object... args) {
            return (args == null || args.length == 0) ? I18n.get(key) : I18n.get(key, args);
        }
        @Override public void bind(StringProperty property, String key) {
            I18n.bind(property, key);
        }
        @Override public void registerBundle(String baseName) {
            I18n.registerPluginBundle(baseName, PluginContext.getClassLoader(plugin));
        }
        @Override public void addListener(Runnable onLocaleChanged) {
            I18n.addListener(onLocaleChanged);
        }
    };

    private final ThemeFacade theme = new ThemeFacade() {
        @Override public ThemeService.Theme current() { return ThemeService.current(); }
        @Override public void onChange(Consumer<ThemeService.Theme> listener) { ThemeService.onChange(listener); }
        @Override public void applyTo(Scene scene) { Themes.applyTo(scene); }
    };

    private final NotificationFacade notifications = new NotificationFacade() {
        @Override public void toast(Node context, SkNotification.Type type, String message) {
            SkNotification.toast(context, type, message);
        }
        @Override public void notify(Node context, SkNotification.Type type, String message) {
            SkNotification.notify(context, type, message);
        }
        @Override public boolean confirm(Node context, String title, String message) {
            return SkNotification.confirm(context, title, message);
        }
    };

    /**
     * @param plugin the plugin this host is bound to; must not be null
     */
    protected BasePluginHost(ZhiFlowPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.tasks = new SimpleTaskRunner(plugin);
    }

    @Override public String pluginId() { return plugin.getId(); }
    @Override public PluginLogger logger(Class<?> cls) { return LoggerFactory.getLogger(cls); }
    @Override public TaskRunner tasks() { return tasks; }
    @Override public I18nFacade i18n() { return i18n; }
    @Override public ThemeFacade theme() { return theme; }
    @Override public NotificationFacade notifications() { return notifications; }
}
