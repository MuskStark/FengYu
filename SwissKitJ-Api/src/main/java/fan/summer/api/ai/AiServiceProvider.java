package fan.summer.api.ai;

import java.util.Optional;

/**
 * Global access point for the AI service.
 * <p>
 * The host application installs the service instance during startup.
 * Plugins can then access it via {@link #getService()}.
 *
 * <pre>
 *   Optional&lt;AiService&gt; ai = AiServiceProvider.getService();
 *   if (ai.isPresent()) {
 *       ai.get().chat(messages, callback);
 *   }
 * </pre>
 */
public final class AiServiceProvider {

    private static volatile AiService instance;

    private AiServiceProvider() {}

    /** Install the service instance (called by the host application). */
    public static void setService(AiService service) {
        instance = service;
    }

    /** Obtain the installed AI service, or empty if unavailable. */
    public static Optional<AiService> getService() {
        return Optional.ofNullable(instance);
    }
}
