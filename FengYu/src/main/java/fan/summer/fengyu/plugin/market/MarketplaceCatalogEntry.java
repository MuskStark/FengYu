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
    boolean official
) {}
