package fan.summer.fengyu.ai.mention;

import java.util.List;
import java.util.Set;

/**
 * Appends the explicit-invocation prompt sections for {@code @} mention links that named a
 * plugin or a workflow — the deterministic (model-independent) counterpart to just letting
 * the link text sit in the user message. Skills skip this class: their mention verdicts feed
 * the same {@link fan.summer.fengyu.ai.skill.SkillPromptAppender} section the
 * {@code /<skill-id>} grammar uses.
 *
 * <p>Wording mirrors the skill section's contract: the model is told WHAT the user invoked
 * and what is attached, notices for unavailable targets let the model answer honestly
 * instead of ignoring an explicit request, and every user-controlled string (names, ids,
 * tool names) is single-lined and capped so a hostile manifest cannot forge prompt
 * sections. Unavailable verdicts say what to do ("tell the user, then answer normally")
 * exactly like the skill {@code Disabled} notice.
 *
 * <p>{@link #append} returns the base prompt unchanged when the verdict carries no plugin
 * and no flow entry — zero behaviour change for every message without mention links.
 *
 * @since 4.1.0
 */
public final class MentionPromptAppender {

    private static final int MAX_FIELD_CHARS = 200;

    private MentionPromptAppender() {}

    /**
     * @param basePrompt          the prompt composed so far (base + skills sections), may be null
     * @param mentions            this turn's resolved mention verdict (never null)
     * @param availableToolNames  the names actually present on this turn's tool surface —
     *                            lets the section stay honest when a plugin's tools are
     *                            suppressed (desktop browser-plugin suppression, name
     *                            shadowing) despite the plugin being enabled
     * @return the prompt with the plugins/workflows sections appended
     */
    public static String append(String basePrompt, ExplicitMentionParser.Resolved mentions,
                                Set<String> availableToolNames) {
        if (mentions == null) return basePrompt;
        String pluginSection = pluginSection(mentions.plugins(), availableToolNames);
        String flowSection = flowSection(mentions.flows(), availableToolNames);
        if (pluginSection == null && flowSection == null) return basePrompt;

        StringBuilder sb = new StringBuilder();
        if (basePrompt != null && !basePrompt.isBlank()) {
            sb.append(basePrompt.stripTrailing()).append("\n\n");
        }
        if (pluginSection != null) sb.append(pluginSection).append("\n\n");
        if (flowSection != null) sb.append(flowSection);
        return sb.toString().stripTrailing();
    }

    private static String pluginSection(List<ExplicitMentionParser.PluginMention> plugins,
                                        Set<String> availableToolNames) {
        if (plugins == null || plugins.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("## Explicitly invoked plugins\n");
        sb.append("The user explicitly attached the plugins below to this turn via @mentions. ");
        sb.append("Prefer their tools for the request; the tool schemas ride the normal tool ");
        sb.append("surface, this section only names them.\n");
        for (ExplicitMentionParser.PluginMention plugin : plugins) {
            sb.append("- ");
            if (!plugin.installed()) {
                sb.append("`").append(field(plugin.id())).append("`: no plugin with this id is ");
                sb.append("installed on this host — briefly tell the user so, then answer ");
                sb.append("normally without it.\n");
                continue;
            }
            if (!plugin.enabled()) {
                sb.append("`").append(field(plugin.id())).append("` (")
                        .append(field(plugin.name())).append("): installed but currently ");
                sb.append("disabled — briefly tell the user so (it can be re-enabled in the ");
                sb.append("tools panel), then answer normally without it.\n");
                continue;
            }
            List<String> attached = plugin.toolNames().stream()
                    .filter(availableToolNames::contains).toList();
            if (attached.isEmpty()) {
                sb.append("`").append(field(plugin.id())).append("` (")
                        .append(field(plugin.name())).append("): enabled, but none of its ");
                sb.append("tools are available on this surface — briefly tell the user so, ");
                sb.append("then answer normally without it.\n");
                continue;
            }
            sb.append("`").append(field(plugin.id())).append("` (")
                    .append(field(plugin.name())).append("): explicitly invoked — its tools ");
            sb.append("are attached to this turn (");
            sb.append(String.join(", ", attached.stream().map(MentionPromptAppender::field).toList()));
            sb.append("). Use them where they fit the request.\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String flowSection(List<ExplicitMentionParser.FlowMention> flows,
                                      Set<String> availableToolNames) {
        if (flows == null || flows.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("## Explicitly invoked workflows\n");
        sb.append("The user explicitly invoked the workflows below via @mentions — execute ");
        sb.append("them for this request rather than describing them.\n");
        for (ExplicitMentionParser.FlowMention flow : flows) {
            sb.append("- ");
            if (!flow.known()) {
                sb.append("`").append(field(flow.id())).append("`: no workflow with this id ");
                sb.append("exists on this host — briefly tell the user so, then answer ");
                sb.append("normally.\n");
                continue;
            }
            if (!flow.published()) {
                sb.append("`").append(field(flow.id())).append("` (")
                        .append(field(flow.name())).append("): exists but is not published, ");
                sb.append("so it has no run tool — briefly tell the user to publish it ");
                sb.append("first, then answer normally.\n");
                continue;
            }
            if (!availableToolNames.contains(flow.toolName())) {
                sb.append("`").append(field(flow.id())).append("` (")
                        .append(field(flow.name())).append("): published, but its run tool ");
                sb.append("is not available on this surface — briefly tell the user so, ");
                sb.append("then answer normally.\n");
                continue;
            }
            sb.append("`").append(field(flow.id())).append("` (")
                    .append(field(flow.name())).append("): explicitly invoked — its run tool `")
                    .append(field(flow.toolName()))
                    .append("` is attached to this turn. Call it with inputs derived from ");
            sb.append("the user's request, then report the result.\n");
        }
        return sb.toString().stripTrailing();
    }

    /** Single-line + cap: manifest/workflow-controlled text must not forge prompt sections. */
    private static String field(String value) {
        if (value == null || value.isBlank()) return "";
        String oneLine = value.strip().replaceAll("\\s+", " ");
        if (oneLine.length() <= MAX_FIELD_CHARS) return oneLine;
        String capped = oneLine.substring(0, MAX_FIELD_CHARS - 1);
        if (!capped.isEmpty() && Character.isHighSurrogate(capped.charAt(capped.length() - 1))) {
            capped = capped.substring(0, capped.length() - 1);
        }
        return capped.stripTrailing() + "…";
    }
}
