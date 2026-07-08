package fan.summer.zhiflow.ai.spring;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Publishes the running Spring context into {@link AiSpringContext} so non-Spring callers (the
 * {@code ChatBackend} impls) can resolve {@code ChatModel} beans imperatively. Runs during context
 * refresh, before any {@code ApplicationRunner}, so the holder is populated by the time the AI
 * backend initializes.
 */
@Component
public class AiContextBridge implements ApplicationContextAware {

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        if (applicationContext instanceof ConfigurableApplicationContext ctx) {
            AiSpringContext.adopt(ctx);
        }
    }
}
