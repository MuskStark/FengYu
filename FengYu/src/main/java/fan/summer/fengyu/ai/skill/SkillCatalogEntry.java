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
 * @param official    shipped by the FengYu team — displayed only when the entry's
 *                    signature verifies against a trusted store key (review M-6:
 *                    a remote catalog cannot vouch for official identity on its own)
 * @param sha256      attested digest of the exact {@code .fys} bytes (mandatory)
 * @param signature   base64 Ed25519 signature over the exact {@code .fys} bytes
 * @param keyId       id of the trusted store key that produced {@code signature}
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
    boolean official,
    String sha256,
    String signature,
    String keyId
) {}
