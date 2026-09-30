package fan.summer.fengyu.ai.skill;

import fan.summer.fengyu.ai.FengYuTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * The progressive-disclosure loader for skills (the Codex model).
 *
 * <p>The system prompt only carries each enabled skill's id + description as a compact
 * catalog. When the model decides a request matches one of those entries it calls this
 * tool with the skill's id; the tool returns the full markdown {@link Skill#body()} so the
 * model can follow the guidance. This keeps large skill bodies out of the token budget
 * until they are actually relevant.
 *
 * <p>Because this class {@code implements FengYuTool}, the existing
 * {@code AiToolDiscoveryConfig.aiToolCallbacks(List<FengYuTool> ...)} aggregator picks it up
 * automatically — no edit to that config is needed (the marker pattern's payoff). The
 * {@code skill} tool is therefore offered to the model on both the cloud and Ollama
 * backends alongside every other tool.
 *
 * <p>Named {@code skill} (not {@code loadSkill}) so the model's tool-call reads naturally
 * as {@code skill("<id>")}. Spring AI would otherwise derive {@code load} from the method
 * name, which is too generic to read as a skill operation in a tool list.
 *
 * @since 4.0.0
 */
@Component
public class SkillTool implements FengYuTool {

    private final SkillRegistry registry;

    public SkillTool(SkillRegistry registry) {
        this.registry = registry;
    }

    /**
     * Load a skill's full guidance body by id.
     *
     * @param id the skill id as listed in the {@code Available Skills} catalog of the system
     *           prompt (e.g. {@code "fengyu-features"})
     * @return the skill's complete markdown body, or an {@code "Skill not found: ..."} message
     *         if no discovered skill matches (lets the model self-correct)
     */
    @Tool(name = "skill",
          description = "Load the full body of a skill by its id. Call this when the user's "
                  + "request matches a skill listed in the 'Available Skills' catalog of the "
                  + "system prompt, BEFORE acting on it. Returns the skill's complete guidance "
                  + "as markdown.")
    public String load(String id) {
        return registry.find(id)
                .filter(registry::isEnabled)
                .map(Skill::body)
                .orElse("Skill not found: " + id);
    }

    @Tool(name = "skill_resource",
          description = "Read a UTF-8 text file referenced by a loaded skill, relative to that "
                  + "skill's directory (for example references/api.md or scripts/check.sh). "
                  + "Paths cannot leave the skill directory and resources are limited to 1 MB.")
    public String resource(String id, String path) {
        return registry.readResource(id, path)
                .orElse("Skill resource not found: " + id + "/" + path);
    }
}
