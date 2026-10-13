package fan.summer.fengyu.ai.mention;

import fan.summer.fengyu.ai.skill.ExplicitSkillInvocationParser;
import fan.summer.fengyu.ai.skill.Skill;
import fan.summer.fengyu.ai.skill.SkillRegistry;
import fan.summer.fengyu.ai.config.AiToolRegistry;
import fan.summer.fengyu.ai.workflow.WorkflowDefinition;
import fan.summer.fengyu.ai.workflow.WorkflowService;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes {@code [@label](scheme://id)} mention links in the last user message — the
 * unified {@code @} invocation surface. One grammar, three deterministic host-side effects:
 *
 * <ul>
 *   <li>{@code skill://id} — the skill's body is injected into this turn's system prompt
 *       (the same machinery the leading {@code /<skill-id>} grammar feeds);</li>
 *   <li>{@code plugin://id} — the plugin's tools are force-attached for the turn and a
 *       prompt section names it as explicitly invoked;</li>
 *   <li>{@code flow://id} — the workflow's run tool is force-attached and a prompt section
 *       tells the model to execute it for the user's request.</li>
 * </ul>
 *
 * <p>The links are what the composer's mention chips serialize to (appendix block after the
 * message text), so a link anywhere in the message counts — unlike the {@code /id} grammar,
 * which is anchored to message start. Links that predate this feature ({@code plugin://}
 * chips existed since 4.0) keep their old "decorative text" behavior exactly when they name
 * nothing installed; resolution verdicts (disabled / not installed / not published) become
 * short notices so the model can tell the user instead of silently ignoring an explicit
 * request.
 *
 * <p>Same containment contract as {@link ExplicitSkillInvocationParser}: every registry
 * lookup that throws degrades that single mention to its "unavailable" verdict (or to
 * plain text for skills) — a misbehaving subsystem must never break the turn.
 *
 * @since 4.1.0
 */
public final class ExplicitMentionParser {

    /**
     * The chip markdown the composer emits: {@code [@escaped-label](scheme://escaped-id)}.
     * The label is free text (escaped by the composer); the destination carries the
     * invocation identity. Only the three host-known schemes are captured — every other
     * link shape stays ordinary text.
     */
    private static final Pattern MENTION_LINK =
            Pattern.compile("\\[@[^\\]]*\\]\\((skill|plugin|flow)://([^)\\s]*)\\)");

    private ExplicitMentionParser() {}

    /** One raw {@code scheme://id} pair, order of first appearance, deduplicated. */
    public record Raw(Scheme scheme, String id) {}

    public enum Scheme { SKILL, PLUGIN, FLOW }

    /**
     * The resolved verdict for one message. Lists preserve first-appearance order and are
     * empty (never null) when nothing matches — the plain-text, pre-feature behavior.
     *
     * @param skills  per-mentioned-skill verdicts reusing the {@code /id} grammar's
     *                {@link ExplicitSkillInvocationParser.Result} shapes (unknown ids drop
     *                out entirely, matching that grammar's backward compatibility)
     * @param plugins installed-plugin verdicts with the plugin's catalog tool names
     * @param flows   workflow verdicts with the deterministic run-tool name
     */
    public record Resolved(List<ExplicitSkillInvocationParser.Result> skills,
                           List<PluginMention> plugins,
                           List<FlowMention> flows) {

        public static final Resolved EMPTY = new Resolved(List.of(), List.of(), List.of());

        /** Tool names this turn must force-attach (enabled plugins' tools, published flows' run tool). */
        public List<String> forcedToolNames() {
            List<String> names = new ArrayList<>();
            for (PluginMention plugin : plugins) {
                if (plugin.installed() && plugin.enabled()) names.addAll(plugin.toolNames());
            }
            for (FlowMention flow : flows) {
                if (flow.known() && flow.published()) names.add(flow.toolName());
            }
            return names;
        }
    }

    /**
     * A mentioned plugin's verdict. {@code installed=false} names nothing on this host (the
     * notice says so); an installed-but-disabled plugin lists its tools anyway so the notice
     * can name what the user is missing, but they stay unattached.
     */
    public record PluginMention(String id, String name, boolean installed, boolean enabled,
                                List<String> toolNames) {}

    /** A mentioned workflow's verdict ({@code known=false} = no such workflow on this host). */
    public record FlowMention(String id, String name, boolean known, boolean published,
                              String toolName) {}

    /** Pure regex pass: raw {@code scheme://id} pairs, first-appearance order, deduplicated. */
    public static List<Raw> parse(String content) {
        List<Raw> raw = new ArrayList<>();
        if (content == null || content.isEmpty()) return raw;
        Map<String, Boolean> seen = new LinkedHashMap<>();
        Matcher matcher = MENTION_LINK.matcher(content);
        while (matcher.find()) {
            Scheme scheme = switch (matcher.group(1)) {
                case "skill" -> Scheme.SKILL;
                case "plugin" -> Scheme.PLUGIN;
                default -> Scheme.FLOW;
            };
            String id = unescape(matcher.group(2));
            if (id.isEmpty()) continue;
            if (scheme == Scheme.SKILL) id = id.toLowerCase(Locale.ROOT);
            if (seen.putIfAbsent(scheme + ":" + id, true) == null) raw.add(new Raw(scheme, id));
        }
        return raw;
    }

    /**
     * Parse + resolve one message against the live registries. Any {@code null} service
     * (transport not wired) skips that scheme's resolution — the links stay plain text,
     * exactly the pre-feature behavior.
     */
    public static Resolved resolve(String lastUserContent, SkillRegistry skills,
                                   PluginPackageService packages, WorkflowService workflows) {
        List<Raw> raw = parse(lastUserContent);
        if (raw.isEmpty()) return Resolved.EMPTY;

        List<ExplicitSkillInvocationParser.Result> skillResults = new ArrayList<>();
        List<PluginMention> plugins = new ArrayList<>();
        List<FlowMention> flows = new ArrayList<>();
        for (Raw mention : raw) {
            switch (mention.scheme()) {
                case SKILL -> resolveSkill(mention.id(), skills).ifPresent(skillResults::add);
                case PLUGIN -> resolvePlugin(mention.id(), packages).ifPresent(plugins::add);
                case FLOW -> resolveFlow(mention.id(), workflows).ifPresent(flows::add);
            }
        }
        if (skillResults.isEmpty() && plugins.isEmpty() && flows.isEmpty()) return Resolved.EMPTY;
        return new Resolved(List.copyOf(skillResults), List.copyOf(plugins), List.copyOf(flows));
    }

    private static Optional<ExplicitSkillInvocationParser.Result> resolveSkill(
            String id, SkillRegistry registry) {
        if (registry == null) return Optional.empty();
        try {
            Optional<Skill> found = registry.find(id);
            if (found.isEmpty()) return Optional.empty();
            Skill skill = found.get();
            if (!registry.isEnabled(skill)) {
                return Optional.of(new ExplicitSkillInvocationParser.Result.Disabled(skill.id()));
            }
            // Mention links carry no separate arguments channel — the message itself is the
            // request, which the section's wording already reflects.
            return Optional.of(new ExplicitSkillInvocationParser.Result.Invoked(skill, ""));
        } catch (RuntimeException registryFailure) {
            return Optional.empty();
        }
    }

    private static Optional<PluginMention> resolvePlugin(String id, PluginPackageService packages) {
        if (packages == null) return Optional.empty();
        try {
            Optional<PluginManifest> found = packages.installed().stream()
                    .filter(manifest -> id.equals(manifest.id())).findFirst();
            if (found.isEmpty()) return Optional.of(new PluginMention(id, id, false, false, List.of()));
            PluginManifest manifest = found.get();
            List<String> toolNames = new ArrayList<>();
            if (manifest.aiTools() != null) {
                for (PluginManifest.AiTool tool : manifest.aiTools()) {
                    if (tool != null && tool.name() != null && !tool.name().isBlank()) {
                        toolNames.add(tool.name());
                    }
                }
            }
            boolean enabled;
            try {
                enabled = packages.isEnabled(manifest.id());
            } catch (RuntimeException enabledCheckFailure) {
                enabled = false;
            }
            return Optional.of(new PluginMention(manifest.id(), manifest.name(), true, enabled,
                    List.copyOf(toolNames)));
        } catch (RuntimeException registryFailure) {
            return Optional.empty();
        }
    }

    private static Optional<FlowMention> resolveFlow(String id, WorkflowService workflows) {
        if (workflows == null) return Optional.empty();
        try {
            Optional<WorkflowDefinition> found = workflows.list().stream()
                    .filter(definition -> id.equals(definition.id())).findFirst();
            if (found.isEmpty()) {
                return Optional.of(new FlowMention(id, id, false, false, ""));
            }
            WorkflowDefinition definition = found.get();
            return Optional.of(new FlowMention(definition.id(), definition.name(), true,
                    definition.published(), AiToolRegistry.workflowToolName(definition.id())));
        } catch (RuntimeException registryFailure) {
            return Optional.empty();
        }
    }

    /** The composer escapes {@code [\]<>\\} inside link destinations; strip the escapes. */
    private static String unescape(String destination) {
        return destination.replace("\\", "");
    }
}
