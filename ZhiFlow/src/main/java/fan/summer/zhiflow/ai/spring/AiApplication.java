package fan.summer.zhiflow.ai.spring;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Minimal Spring Boot application for the embedded (non-web) AI context.
 *
 * <p>Scans only the {@code fan.summer.zhiflow.ai.spring} package so the context stays
 * small: just the {@code ChatModel} {@code @Bean}s and their config. It does
 * <strong>not</strong> scan the legacy {@code fan.summer.zhiflow.ai.service} / {@code tools}
 * code (those classes are not Spring-managed; they are constructed imperatively
 * by {@code ZhiFlowApp} and the settings UI, exactly as before).
 *
 * <p>{@code WebApplicationType.NONE} is forced by {@link AiSpringContext} — this
 * class never starts an HTTP server.
 */
@SpringBootApplication
@ComponentScan(basePackages = "fan.summer.zhiflow.ai.spring")
public class AiApplication {
}
