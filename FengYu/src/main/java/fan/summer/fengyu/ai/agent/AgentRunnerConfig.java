package fan.summer.fengyu.ai.agent;

import fan.summer.fengyu.ai.config.AiToolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link AgentRunner} as a Spring bean from its fully-injected constructor.
 *
 * <p>{@link AgentRunner} is deliberately not a {@code @Component} (it is constructed by both
 * tests and production wiring via the same 3-argument constructor). This configuration gives
 * Spring the production wiring:
 * <ul>
 *   <li>{@code tools} — a fresh {@link AiToolRegistry} snapshot for every run.</li>
 *   <li>{@code planGenerator} — {@link ChatBackendPlanGenerator}, which asks the active
 *       backend for a validated structured workflow without enabling tools during planning.</li>
 *   <li>{@code stepExecutor} — {@link AgentRunner#toolResolvingExecutor()} (the Spring AI-native
 *       path: resolve the tool by name and invoke its callback).</li>
 * </ul>
 */
@Configuration
public class AgentRunnerConfig {

    @Bean
    public AgentRunner agentRunner(AiToolRegistry toolRegistry, ChatBackendPlanGenerator planGenerator) {
        return new AgentRunner(toolRegistry::callbacks, planGenerator, AgentRunner.toolResolvingExecutor());
    }
}
