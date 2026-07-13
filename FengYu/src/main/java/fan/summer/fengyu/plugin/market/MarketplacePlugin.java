package fan.summer.fengyu.plugin.market;

import java.util.List;

/** Public marketplace view, combining catalog metadata with local installation state. */
public record MarketplacePlugin(
    String id,
    String name,
    String description,
    String version,
    String installedVersion,
    String author,
    String icon,
    String category,
    List<String> permissions,
    String homepage,
    String downloadUrl,
    boolean official,
    boolean installed,
    boolean enabled,
    boolean updateAvailable
) {}
