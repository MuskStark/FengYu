package fan.summer.fengyu.ai.skill;

/**
 * An immutable, prompt-injectable skill — a unit of domain guidance the model loads
 * on demand (Codex-style progressive disclosure).
 *
 * <p>The system prompt only carries each enabled skill's {@link #id} + {@link #description}
 * (a compact catalog). The model then calls the built-in {@code skill} tool to fetch the
 * full {@link #body} when a user request matches a skill — so bodies stay out of the token
 * budget until actually needed.
 *
 * <p><b>Lifecycle twin of a plugin:</b> a skill is managed exactly like a plugin. Installed
 * skills arrive as {@code .fys} packages (a zip of {@code manifest.json} + {@code SKILL.md}),
 * land under {@code <programWorkingDirectory>/skills/<id>/}, can be enabled/disabled via a
 * {@code .disabled} marker file, and can be uninstalled. They can also be browsed and installed
 * from a remote catalog through the skill marketplace. Builtin skills ship on the classpath and
 * cannot be uninstalled (only disabled).
 *
 * <p><b>Why this lives in the {@code FengYu} app module and not {@code FengYu-Api}:</b>
 * skills are a host-side runtime concept (discovered from the classpath and the user
 * directory, injected into the host's system prompt). They have no need to be implemented
 * by out-of-process plugin developers, so they do not belong in the {@code FengYu-Api}
 * contract module (which is slated for removal). This keeps the new surface decoupled
 * from the legacy contract package.
 *
 * <p>A skill is <em>not</em> a plugin extension — it is a peer extension surface. Plugins
 * contribute callable tools; skills contribute contextual guidance. The two are intentionally
 * independent: a skill never touches {@code plugin-spec/} or a plugin manifest.
 *
 * @param id          stable lowercase identifier (matches its directory name); also the
 *                    {@code skill} tool argument. For {@link Source#INSTALLED} skills this is
 *                    {@link SkillManifest#id()}; for {@link Source#BUILTIN} it is the classpath
 *                    directory name.
 * @param name        human-readable display name
 * @param description one-line trigger description shown in the system-prompt catalog
 *                    (should name concrete tokens that activate the skill)
 * @param body        the full markdown guidance returned by the {@code skill} tool
 * @param source      where the skill was discovered — {@link Source#BUILTIN} (classpath,
 *                    cannot be uninstalled) or {@link Source#INSTALLED} (a {@code .fys} package
 *                    under {@code <programWorkingDirectory>/skills/})
 */
public record Skill(String id, String name, String description, String body, Source source) {

    /**
     * Where a skill was discovered. {@link #BUILTIN} skills ship inside the app JAR and cannot
     * be uninstalled (they may still be disabled). {@link #INSTALLED} skills were installed from
     * a {@code .fys} package and have a full install/uninstall lifecycle.
     */
    public enum Source { BUILTIN, INSTALLED }
}
