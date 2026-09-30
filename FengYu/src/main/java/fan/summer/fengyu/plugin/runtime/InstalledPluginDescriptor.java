package fan.summer.fengyu.plugin.runtime;

import java.util.List;

/** Host-facing descriptor generated exclusively from an installed package manifest. */
public record InstalledPluginDescriptor(
    String id, String name, String description, String category, String icon, String version,
    String uiEntry, String author, List<String> permissions, boolean enabled,
    String iconStyle, boolean supportsAi, String source
) {}
