package fan.summer.fengyu.plugin.market;

import java.util.List;

/** One entry in the remotely hosted marketplace catalog JSON. */
public record MarketplaceCatalogEntry(
    String id,
    String name,
    String description,
    String version,
    String author,
    String icon,
    String category,
    List<String> permissions,
    String homepage,
    String downloadUrl,
    boolean official,
    /** Optional SHA-256 of the {@code .fyp} at {@code downloadUrl}. When present the host
     *  verifies the download against it — the only way a plain-http download URL or an
     *  install under checksum enforcement is accepted. */
    String sha256
) {}
