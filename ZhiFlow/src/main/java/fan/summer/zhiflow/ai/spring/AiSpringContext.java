package fan.summer.zhiflow.ai.spring;

import org.springframework.context.ConfigurableApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static holder for the running Spring context, so non-Spring callers can resolve beans
 * imperatively.
 *
 * <p>The context is populated by {@link AiContextBridge} (an {@code ApplicationContextAware}
 * bean) during context refresh — callers must not touch {@link #getBean} before the context has
 * started. The {@code ChatBackend} impls ({@code SpringAiCloudBackend} / {@code OllamaLocalBackend})
 * use {@link #getBean(String, Class)} to pick a specific {@code ChatModel} bean at mode-switch
 * time; this keeps the strangler migration honest (legacy code need not become Spring-managed to
 * use the new {@code ChatModel}s).
 */
public final class AiSpringContext {

    private static final Logger log = LoggerFactory.getLogger(AiSpringContext.class);

    private static volatile ConfigurableApplicationContext context;

    private AiSpringContext() {}

    /** Records the live context. Called once by {@link AiContextBridge} during refresh. */
    public static void adopt(ConfigurableApplicationContext ctx) {
        context = ctx;
        log.debug("AI Spring context adopted");
    }

    /** @return the live context, or throws if the Spring context has not started yet. */
    public static ConfigurableApplicationContext getContext() {
        ConfigurableApplicationContext ctx = context;
        if (ctx == null) {
            throw new IllegalStateException("AI Spring context not started yet");
        }
        return ctx;
    }

    /** Look up a bean by type. Convenience for non-Spring callers. */
    public static <T> T getBean(Class<T> type) {
        return getContext().getBean(type);
    }

    /**
     * Look up a bean by name and type. Used by the {@code ChatBackend} impls to pick a specific
     * {@code ChatModel} bean ({@code openAiChatModel} / {@code anthropicChatModel} /
     * {@code ollamaChatModel}).
     */
    public static <T> T getBean(String name, Class<T> type) {
        return getContext().getBean(name, type);
    }

    /** Close the context, releasing beans. Normally the Boot shutdown hook handles this. */
    public static synchronized void close() {
        if (context != null) {
            try {
                context.close();
            } catch (Exception e) {
                log.warn("Error closing AI Spring context: {}", e.getMessage());
            } finally {
                context = null;
            }
        }
    }
}
