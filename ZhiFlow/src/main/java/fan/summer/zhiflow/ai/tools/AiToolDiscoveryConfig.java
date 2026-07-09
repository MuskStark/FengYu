package fan.summer.zhiflow.ai.tools;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates every Spring AI {@code @Tool}-annotated bean into a single {@link ToolCallback}[]
 * (spec §3.2.3 — "ZhiFlow provides a {@code @Configuration} to aggregate the available tools").
 *
 * <p><b>Why this exists:</b> ZhiFlow drives Spring AI through manual {@code @Bean} configuration
 * (the non-starter artifacts {@code spring-ai-openai/-anthropic/-ollama} + {@code spring-ai-client-chat}),
 * none of which ships a {@code spring.boot.autoconfigure.AutoConfiguration.imports} entry. Spring AI's
 * starter auto-config is what normally turns {@code @Tool}-annotated beans into {@link ToolCallback}
 * beans — without it, an {@code @Autowired Collection<ToolCallback>} finds zero beans. This class is
 * the explicit aggregation point: it runs each known {@code @Tool} bean through
 * {@link ToolCallbacks#from(Object...)} and exposes the flattened callbacks as a single bean.
 *
 * <p><b>Single source of truth:</b> Tasks 15 ({@code AgentRunner}) and 16
 * ({@code AgentController} {@code /api/agent/tools}) inject this {@code ToolCallback[]} to resolve
 * tools by name and to list orchestrable tools for the agent UI. {@code SpringAiCloudBackend} can be
 * handed the same array via {@code setToolCallbacks(List.of(...))}. Future tools (plugins) add their
 * bean to the {@code aiToolBeans} list here — or, when a plugin-injection path lands, this bean
 * iterates the discovered plugin {@code @Tool} beans the same way.
 *
 * <p>No mode/visibility filtering yet (spec §3.2.4 defaults to "no filtering"); when needed, filter
 * the callbacks here before returning.
 */
@Configuration
public class AiToolDiscoveryConfig {

    /**
     * All beans carrying {@code @Tool} methods. Spring injects every bean declared as a parameter;
     * add new {@code @Tool} beans here as they are created (plugins will extend this list).
     */
    @Bean
    public List<Object> aiToolBeans(JsonFormatTool jsonFormatTool) {
        List<Object> beans = new ArrayList<>();
        beans.add(jsonFormatTool);
        return beans;
    }

    /**
     * The discovered {@link ToolCallback}s, in bean declaration order. This is the bean Tasks 15/16
     * inject to resolve tools by name / list them for the agent UI.
     */
    @Bean
    public ToolCallback[] aiToolCallbacks(List<Object> aiToolBeans) {
        return ToolCallbacks.from(aiToolBeans.toArray());
    }
}
