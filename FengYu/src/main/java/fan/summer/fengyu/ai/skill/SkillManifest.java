package fan.summer.fengyu.ai.skill;

/**
 * The manifest stored at the root of every {@code .fys} skill package. Mirrors
 * {@code PluginManifest} in shape but is deliberately leaner: a skill is pure guidance text, so
 * it carries no UI entry, no backend worker, no permissions, and no AI tools — those are plugin
 * concerns. Skills and plugins are peer extension surfaces; a skill never decays into a plugin.
 *
 * <p>Serialized as {@code manifest.json} at the zip root of a {@code .fys} archive, and read back
 * by {@link SkillPackageService} on install. After install it lives at
 * {@code <programWorkingDirectory>/.fengyu/skills/<id>/manifest.json}, a filesystem peer of
 * {@code <programWorkingDirectory>/.fengyu/plugins/<id>/manifest.json}.
 *
 * @param schemaVersion manifest format version (currently {@code 1}); bumped only on a breaking
 *                      change to this record's on-disk shape
 * @param id            stable lowercase identifier (reverse-domain, matches the install directory);
 *                      validated against {@code [a-z0-9]+(?:[.-][a-z0-9]+)+}
 * @param name          human-readable display name
 * @param description   one-line trigger description shown in the system-prompt catalog and the
 *                      marketplace card; should name concrete tokens that activate the skill
 * @param version       semantic version ({@code x.y.z[-pre]}); drives the marketplace's
 *                      "update available" comparison
 * @param author        optional author attribution
 * @param icon          optional Material Design Icon id (without the {@code mdi-} prefix)
 * @param homepage      optional project URL
 * @param official      {@code true} only for skills shipped by the FengYu team (requires the id
 *                      to start with {@code fan.summer.}, enforced in {@link SkillPackageService})
 */
public record SkillManifest(int schemaVersion, String id, String name, String description,
                            String version, String author, String icon, String homepage,
                            boolean official) {
}
