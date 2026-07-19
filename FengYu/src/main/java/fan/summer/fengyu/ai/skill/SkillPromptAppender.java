package fan.summer.fengyu.ai.skill;

import java.util.List;

/**
 * Appends the enabled-skills catalog to the user's base system prompt (progressive
 * disclosure). Shared by {@code SpringAiCloudBackend} and {@code OllamaLocalBackend} so the
 * two backends — which already mirror each other's tool-loop logic — do not diverge on how
 * skills are surfaced.
 *
 * <p>The catalog is intentionally compact: only each enabled skill's id + description. Skill
 * <em>bodies</em> are never inlined; the model fetches them on demand via the {@code skill}
 * tool. This keeps the per-request token cost of N skills at roughly N lines, independent of
 * how long each skill body is.
 *
 * <p>When the enabled list is empty the base prompt is returned unchanged (zero behaviour
 * change for deployments with no skills), and a {@code null} registry is tolerated so this
 * helper can be called from contexts where Spring wiring is not yet complete (e.g. backend
 * construction) without crashing.
 *
 * @since 4.0.0
 */
public final class SkillPromptAppender {

    private SkillPromptAppender() {}

    /**
     * @param basePrompt the user-configured system prompt (may be {@code null}/blank)
     * @param registry   the live skill registry, or {@code null} if unavailable
     * @return the base prompt with the enabled-skills catalog appended, or the base prompt
     *         unchanged when there are no enabled skills or the registry is unavailable
     */
    public static String append(String basePrompt, SkillRegistry registry) {
        if (registry == null) return basePrompt;
        List<Skill> enabled;
        try {
            enabled = registry.enabled();
        } catch (Throwable t) {
            // A misbehaving registry must never break chat — fall back to the base prompt.
            return basePrompt;
        }
        if (enabled == null || enabled.isEmpty()) return basePrompt;

        StringBuilder sb = new StringBuilder();
        if (basePrompt != null && !basePrompt.isBlank()) {
            sb.append(basePrompt.stripTrailing()).append("\n\n");
        }
        sb.append("## Available Skills\n");
        sb.append("You have access to skills. Call the `skill` tool with a skill's id to load ");
        sb.append("its full guidance BEFORE acting on a request that matches it.\n");
        for (Skill s : enabled) {
            sb.append("- ").append(s.id()).append(": ");
            sb.append(s.description() == null ? "" : s.description().strip()).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
