package fan.summer.fengyu.ai;

/**
 * Marker interface for Spring AI tool beans aggregated by the app's
 * {@code AiToolDiscoveryConfig} (in {@code FengYu} — the app module, not visible from here).
 *
 * <p>Any Spring {@code @Component} that carries one or more {@code @Tool}-annotated
 * methods should {@code implements FengYuTool}. Spring then collects every such bean
 * into a {@code List<FengYuTool>}, which the app's discovery config flattens into a
 * single {@code ToolCallback}[] via {@code ToolCallbacks.from(Object...)}.
 *
 * <p><b>Why a marker and not positional constructor params:</b> previously the discovery
 * config listed each tool bean positionally in its method signature, forcing a config edit
 * for every new tool. With the marker, adding a tool is a one-line change on the tool class
 * itself ({@code implements FengYuTool}) and requires zero edits to the discovery config —
 * Spring's collection injection handles it. This is explicit, type-safe, and keeps the
 * aggregation point closed for modification.
 *
 * <p>The interface carries no methods; implementors are discovered purely by type.
 * Tools that do not implement this marker are simply not aggregated (and therefore not
 * offered to the model / agent orchestrator).
 *
 * <p>Lives in the app module because tools are host-side beans.
 */
public interface FengYuTool {
}
