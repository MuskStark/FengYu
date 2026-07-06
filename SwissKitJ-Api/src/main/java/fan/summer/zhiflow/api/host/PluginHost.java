package fan.summer.zhiflow.api.host;

import fan.summer.zhiflow.api.log.PluginLogger;

/**
 * Per-plugin facade giving a plugin access to every host capability through a
 * single injected object — logging, namespaced settings, TCCL-safe background
 * tasks, i18n, theming, and notifications.
 *
 * <p>Injected via {@link fan.summer.zhiflow.api.SwissKitJPlugin#init(PluginHost)} exactly
 * once, on the JavaFX Application Thread, before the plugin becomes visible in the
 * registry. Store the reference; it stays valid for the plugin's whole lifetime.</p>
 *
 * @since 3.2.0
 */
public interface PluginHost {

    /** @return the owning plugin's ID (same value as {@code SwissKitJPlugin.getId()}) */
    String pluginId();

    /**
     * @param cls the class requesting the logger
     * @return a logger routed into the host logging backbone
     */
    PluginLogger logger(Class<?> cls);

    /** @return key-value settings persisted by the host, namespaced by {@link #pluginId()} */
    PluginSettings settings();

    /**
     * @return TCCL-safe background task runner; its running count feeds the host's
     *         background-keepalive decision alongside {@code hasRunningTasks()}
     */
    TaskRunner tasks();

    /** @return i18n facade bound to this plugin's ClassLoader */
    I18nFacade i18n();

    /** @return theme facade (current theme, change listener, stylesheet application) */
    ThemeFacade theme();

    /**
     * Named {@code notifications()} rather than {@code notify()} — a zero-arg
     * {@code notify()} would clash with the final {@link Object#notify()}.
     *
     * @return notification facade delegating to {@code SkNotification}
     */
    NotificationFacade notifications();
}
