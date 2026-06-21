package fan.summer.ai.adapter;

/**
 * Marker interface for {@link fan.summer.api.ai.AiService} implementations that
 * expose raw cloud-API config (endpoint, API key, model name) for consumers that
 * must bypass the standard {@code chat()} flow.
 *
 * <p>Currently used by the browser-automation planner
 * (see {@code SynchronousChatHelper}), which makes its own direct HTTP call to
 * avoid recursive tool invocation.
 */
public interface CloudAiConfigProvider {

    /** Base URL of the cloud API (no trailing slash). May be empty if unconfigured. */
    String getEndpoint();

    /** API key for authentication. May be empty if unconfigured. */
    String getApiKey();

    /** Model identifier (e.g. {@code "gpt-4o"}). May be empty if unconfigured. */
    String getModelNameInternal();
}
