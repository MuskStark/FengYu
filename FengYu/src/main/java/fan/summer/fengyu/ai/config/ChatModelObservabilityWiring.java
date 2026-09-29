package fan.summer.fengyu.ai.config;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Bridges the Spring-managed {@link ObservationRegistry} into {@link ChatModelConfig}'s
 * static factory at boot. Models are built directly from live config (not beans — see
 * {@code ChatModelConfig.buildOpenAiCompatible}), so the registry reaches them through a
 * holder this component sets once. With the actuator on the classpath, micrometer's
 * observation auto-configuration provides the registry; export (OTLP, tracing) follows
 * whatever the management settings configure.
 */
@Component
public class ChatModelObservabilityWiring {

    private static final Logger log = LoggerFactory.getLogger(ChatModelObservabilityWiring.class);

    public ChatModelObservabilityWiring(ObjectProvider<ObservationRegistry> registry) {
        registry.ifAvailable(r -> {
            ChatModelConfig.useObservationRegistry(r);
            log.info("AI observability wired: model/tool observations report to the managed registry");
        });
    }
}
