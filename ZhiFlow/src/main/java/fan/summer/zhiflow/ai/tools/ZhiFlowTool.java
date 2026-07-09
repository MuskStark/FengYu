package fan.summer.zhiflow.ai.tools;

/**
 * Marker interface for Spring AI tool beans aggregated by {@link AiToolDiscoveryConfig}.
 *
 * <p>Any Spring {@code @Component} that carries one or more {@code @Tool}-annotated
 * methods should {@code implements ZhiFlowTool}. Spring then collects every such bean
 * into a {@code List<ZhiFlowTool>}, which {@link AiToolDiscoveryConfig#aiToolCallbacks}
 * flattens into a single {@link org.springframework.ai.tool.ToolCallback}[] via
 * {@link org.springframework.ai.support.ToolCallbacks#from(Object...)}.
 *
 * <p><b>Why a marker and not positional constructor params:</b> previously
 * {@code AiToolDiscoveryConfig} listed each tool bean positionally in its
 * {@code aiToolBeans(...)} method signature, forcing a config edit for every new tool.
 * With the marker, adding a tool is a one-line change on the tool class itself
 * ({@code implements ZhiFlowTool}) and requires zero edits to the discovery config —
 * Spring's collection injection handles it. This is explicit, type-safe, and keeps the
 * aggregation point closed for modification.
 *
 * <p>The interface carries no methods; implementors are discovered purely by type.
 * Tools that do not implement this marker are simply not aggregated (and therefore not
 * offered to the model / agent orchestrator).
 *
 * @see AiToolDiscoveryConfig
 */
public interface ZhiFlowTool {
}
