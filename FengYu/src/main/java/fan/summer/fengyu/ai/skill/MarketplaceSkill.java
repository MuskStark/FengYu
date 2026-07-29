package fan.summer.fengyu.ai.skill;

/**
 * Public skill marketplace view, combining remote catalog metadata with local installation state.
 * The lifecycle twin of {@code MarketplacePlugin}: every field here has a direct counterpart on
 * the plugin view, minus the plugin-only {@code category}/{@code permissions} (skills carry no
 * permissions and are not categorized).
 *
 * @param id               stable skill id
 * @param name             display name (catalog or local)
 * @param description      one-line trigger description
 * @param version          available version (catalog or local when catalog is absent)
 * @param installedVersion local version when installed, else {@code null}
 * @param author           attribution (catalog or local)
 * @param icon             Material Design Icon id without the {@code mdi-} prefix
 * @param homepage         project URL
 * @param downloadUrl      catalog download URL ({@code null} for local-only entries → no Update)
 * @param official         shipped by the FengYu team
 * @param installed        present in the local {@code <programWorkingDirectory>/skills/} directory
 * @param enabled          installed and not marked {@code .disabled}
 * @param updateAvailable  installed AND a newer catalog version exists
 */
public record MarketplaceSkill(
    String id,
    String name,
    String description,
    String version,
    String installedVersion,
    String author,
    String icon,
    String homepage,
    String downloadUrl,
    boolean official,
    boolean installed,
    boolean enabled,
    boolean updateAvailable
) {}
