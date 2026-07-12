package fan.summer.fengyu.ai.agent;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * Wires the {@link AgentRunner} as a Spring bean from its fully-injected constructor.
 *
 * <p>{@link AgentRunner} is deliberately not a {@code @Component} (it is constructed by both
 * tests and production wiring via the same 3-argument constructor). This configuration gives
 * Spring the production wiring:
 * <ul>
 *   <li>{@code tools} — the aggregated {@link ToolCallback} bean from
 *       {@code AiToolDiscoveryConfig#aiToolCallbacks(List)} (the single source of truth for
 *       "what can the agent orchestrate"); the array is defensively copied to a {@link List}.</li>
 *   <li>{@code planGenerator} — the {@link StubPlanGenerator} stub (see its javadoc for why a
 *       production {@code ChatClient}-backed planner is deferred).</li>
 *   <li>{@code stepExecutor} — {@link AgentRunner#toolResolvingExecutor()} (the Spring AI-native
 *       path: resolve the tool by name and call {@link ToolCallback#call(String)}).</li>
 * </ul>
 */
@Configuration
public class AgentRunnerConfig {

    @Bean
    public AgentRunner agentRunner(ToolCallback[] aiToolCallbacks, StubPlanGenerator planGenerator) {
        List<ToolCallback> tools = aiToolCallbacks == null ? List.of() : Arrays.asList(aiToolCallbacks);
        return new AgentRunner(tools, planGenerator, AgentRunner.toolResolvingExecutor());
    }
}
