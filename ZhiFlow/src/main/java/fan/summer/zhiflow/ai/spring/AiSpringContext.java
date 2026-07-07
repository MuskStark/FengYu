package fan.summer.zhiflow.ai.spring;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bootstraps and holds the embedded Spring Boot context used for AI inference.
 *
 * <p>Lifecycle: {@link #start()} is called from {@code ZhiFlowApp.start()} after
 * the H2 database is initialised (so {@code AiConfigService} can read settings);
 * {@link #close()} is called from {@code ZhiFlowApp.stop()}.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code WebApplicationType.NONE} — no HTTP server, no web context. This is
 *       a desktop app; Spring is here purely for DI + the {@code ChatModel} beans.</li>
 *   <li>{@code headless(false)} — required by JavaFX (AWT/Java2D insists on
 *       {@code java.awt.headless=false} when a {@code Stage} is shown).</li>
 *   <li>No {@code spring-boot-starter-web} on the classpath, so the context is
 *       guaranteed non-web regardless of auto-detection.</li>
 * </ul>
 *
 * <p>Non-Spring code (JavaFX controllers, the {@code ChatBackend} impls constructed
 * by the settings UI) resolves beans imperatively via {@link #getBean(Class)} or
 * {@link #getBean(String, Class)}. This keeps the strangler migration honest:
 * legacy code does not have to become Spring-managed to use the new {@code ChatModel}s.
 */
public final class AiSpringContext {

    private static final Logger log = LoggerFactory.getLogger(AiSpringContext.class);

    private static volatile ConfigurableApplicationContext context;

    private AiSpringContext() {}

    /** Bootstraps the context. Idempotent — a second call is a no-op. */
    public static synchronized void start() {
        if (context != null) {
            log.debug("AI Spring context already started");
            return;
        }
        log.info("Starting embedded AI Spring context (non-web, headless=false)");
        context = new SpringApplicationBuilder(AiApplication.class)
            .web(WebApplicationType.NONE)
            .headless(false)
            .registerShutdownHook(false)   // we close() manually in ZhiFlowApp.stop()
            .logStartupInfo(false)         // keep the FX launch console clean
            .run();
        log.info("AI Spring context ready");
    }

    /** @return the live context, or throws if {@link #start()} was not called. */
    public static ConfigurableApplicationContext getContext() {
        ConfigurableApplicationContext ctx = context;
        if (ctx == null) {
            throw new IllegalStateException("AI Spring context not started; call AiSpringContext.start() first");
        }
        return ctx;
    }

    /** Look up a bean by type. Convenience for non-Spring callers. */
    public static <T> T getBean(Class<T> type) {
        return getContext().getBean(type);
    }

    /**
     * Look up a bean by name and type. Used by the {@code ChatBackend} impls to pick
     * a specific {@code ChatModel} bean ({@code openAiChatModel} / {@code anthropicChatModel}
     * / {@code ollamaChatModel}) at mode-switch time.
     */
    public static <T> T getBean(String name, Class<T> type) {
        return getContext().getBean(name, type);
    }

    /** Close the context, releasing beans. Safe to call from {@code ZhiFlowApp.stop()}. */
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
