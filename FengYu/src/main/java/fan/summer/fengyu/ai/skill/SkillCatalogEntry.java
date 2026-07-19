package fan.summer.fengyu.ai.skill;

/**
 * One entry in the remotely hosted skill marketplace catalog JSON. The lifecycle twin of
 * {@code MarketplaceCatalogEntry} minus the plugin-only {@code category}/{@code permissions}
 * fields. A remote catalog is a plain JSON array of these — no envelope/wrapper — matching the
 * plugin catalog shape.
 *
 * @param id          stable skill id
 * @param name        display name
 * @param description one-line trigger description
 * @param version     semantic version
 * @param author      attribution
 * @param icon        Material Design Icon id without the {@code mdi-} prefix
 * @param homepage    project URL
 * @param downloadUrl URL of the {@code .fys} package to install
 * @param official    shipped by the FengYu team
 */
public record SkillCatalogEntry(
    String id,
    String name,
    String description,
    String version,
    String author,
    String icon,
    String homepage,
    String downloadUrl,
    boolean official
) {}
