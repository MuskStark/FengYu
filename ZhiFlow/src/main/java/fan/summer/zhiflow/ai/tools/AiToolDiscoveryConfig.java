package fan.summer.zhiflow.ai.tools;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 * the explicit aggregation point: it runs every {@link ZhiFlowTool} bean through
 * {@link ToolCallbacks#from(Object...)} and exposes the flattened callbacks as a single bean.
 *
 * <p><b>Scalable discovery via the {@link ZhiFlowTool} marker:</b> rather than listing each tool bean
 * positionally in this config's signature (which forced a config edit for every new tool), discovery
 * is now driven by the marker interface. Any {@code @Component} that {@code implements ZhiFlowTool}
 * is auto-collected by Spring into the injected {@code List<ZhiFlowTool>}. Adding a new tool is a
 * one-line change on the tool class itself ({@code implements ZhiFlowTool}) and requires <em>no</em>
 * edit here. When a plugin-injection path lands, plugin {@code @Tool} beans that implement the marker
 * are aggregated the same way automatically.
 *
 * <p><b>Single source of truth:</b> Tasks 15 ({@code AgentRunner}) and 16
 * ({@code AgentController} {@code /api/agent/tools}) inject this {@code ToolCallback[]} to resolve
 * tools by name and to list orchestrable tools for the agent UI. The chat backends
 * ({@code SpringAiCloudBackend} via {@code AiBackendInitializer}; {@code OllamaLocalBackend} via its
 * own {@code loadModel}) are handed the same array via {@code setToolCallbacks(List.of(...))} so chat
 * regains tool-calling.
 *
 * <p>No mode/visibility filtering yet (spec §3.2.4 defaults to "no filtering"); when needed, filter
 * the callbacks here before returning.
 */
@Configuration
public class AiToolDiscoveryConfig {

    /**
     * The discovered {@link ToolCallback}s, in Spring bean-resolution order. This is the bean Tasks
     * 15/16 and the chat backends inject to resolve tools by name / offer them to the model.
     *
     * @param tools every {@link ZhiFlowTool} bean in the context (collected by type — add a tool by
     *              having it {@code implements ZhiFlowTool}; no edit to this config needed)
     */
    @Bean
    public ToolCallback[] aiToolCallbacks(List<ZhiFlowTool> tools) {
        return ToolCallbacks.from(tools.toArray());
    }
}
