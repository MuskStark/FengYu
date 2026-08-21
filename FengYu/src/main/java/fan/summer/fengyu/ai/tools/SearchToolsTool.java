package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.FengYuTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The dynamic-tool-loading loader (pi's {@code search_tools}). Searches the deferred catalog for
 * a keyword, activates the matches for the rest of the conversation, and returns name +
 * description + input schema so the model can prepare correct arguments. Activation is
 * additive-only and capped; already-active tools are reported as active instead of being
 * re-added, and the machine-readable marker line at the end is what re-seeds the activation set
 * on the next user turn.
 */
@Component
public class SearchToolsTool implements FengYuTool, ToolEffectProvider {

    static final String TOOL_NAME = "search_tools";
    private static final int MAX_RESULTS = 10;

    @Override
    public ToolEffect effectFor(String toolName) {
        return ToolEffect.READ;
    }

    /**
     * @param query short keywords to match deferred tool names and descriptions
     * @return the activated tools with their definitions, or guidance when nothing matches
     */
    @Tool(name = TOOL_NAME,
          description = "Search tools that exist but are not yet active, by keyword. Returns the "
                  + "name, description, and input schema of matches; matched tools are activated "
                  + "and become callable from your next message. Prefer one focused query and "
                  + "broaden it only if the result misses. Do not call this for tools that are "
                  + "already visible and active in the conversation.")
    public String search(String query) {
        ToolActivationContext.Activation context = ToolActivationContext.current();
        if (context == null || context.state() == null) {
            // Only reachable if the tool is attached outside a dynamic-loading chat; answer
            // honestly instead of failing the round.
            return "Tool loading is not active for this conversation; all available tools are "
                    + "already attached.";
        }
        String keywords = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (keywords.isEmpty()) {
            return "Provide one or more keywords, e.g. search_tools(query=\"excel table\").";
        }

        List<ToolDefinition> matches = new ArrayList<>();
        List<ToolDefinition> descriptionMatches = new ArrayList<>();
        for (ToolCallback callback : context.deferred()) {
            ToolDefinition definition = callback.getToolDefinition();
            if (definition == null || definition.name() == null) continue;
            String name = definition.name().toLowerCase(Locale.ROOT);
            String description = definition.description() == null
                    ? "" : definition.description().toLowerCase(Locale.ROOT);
            if (name.contains(keywords)) matches.add(definition);
            else if (description.contains(keywords)) descriptionMatches.add(definition);
        }
        matches.addAll(descriptionMatches);
        if (matches.isEmpty()) {
            return "No inactive tool matched '" + query.trim() + "'. Broaden the query, or check "
                    + "the 'Available tools (on-demand activation)' catalog in the system prompt; "
                    + "do not invent tool names.";
        }

        Set<String> activatedNow = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder();
        boolean capReached = false;
        for (int i = 0; i < matches.size() && i < MAX_RESULTS; i++) {
            ToolDefinition definition = matches.get(i);
            if (context.state().isActive(definition.name())) continue;
            if (!context.state().activate(definition.name())) {
                capReached = true;
                continue;
            }
            activatedNow.add(definition.name());
            out.append("- ").append(definition.name()).append(": ")
                    .append(oneLine(definition.description())).append('\n');
            String schema = definition.inputSchema();
            if (schema != null && !schema.isBlank()) {
                out.append("  input schema: ").append(schema.strip()).append('\n');
            }
        }
        if (activatedNow.isEmpty()) {
            if (capReached) {
                return "The activation limit (" + ToolActivationState.MAX_ACTIVATED
                        + " tools) is already reached. Finish the current task with the active "
                        + "tools; a later conversation can activate others.";
            }
            return "All matching tools are already active; call them directly.";
        }
        out.append('\n').append("Activated ").append(activatedNow.size())
                .append(" tool(s); they become callable from your next message:")
                .append('\n').append(ToolActivationState.markerFor(activatedNow));
        if (matches.size() > MAX_RESULTS || capReached) {
            out.append("\n(More matches exist; refine the query to list others.)");
        }
        return out.toString();
    }

    private static String oneLine(String description) {
        if (description == null || description.isBlank()) return "";
        return description.strip().replaceAll("\\s+", " ");
    }
}
