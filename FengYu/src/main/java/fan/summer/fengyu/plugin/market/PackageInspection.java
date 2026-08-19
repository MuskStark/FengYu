package fan.summer.fengyu.plugin.market;

import java.util.Optional;

/**
 * Pre-install view of an incoming {@code .fyp} package: what an upload would do to this host.
 * Served by the {@code /api/plugin-market/inspect} endpoints so the UI can confirm a
 * local-package update — including warning on a downgrade or a same-version reinstall —
 * before the package swaps the installed copy (the upload itself stops the running Worker
 * and replaces the directory).
 */
public record PackageInspection(
    String id,
    String name,
    String version,
    boolean installed,
    String installedVersion,
    String comparison
) {
    /** {@link #comparison} values: the incoming version vs the installed one. */
    public static final String UPGRADE = "upgrade";
    public static final String DOWNGRADE = "downgrade";
    public static final String SAME = "same";

    /**
     * Build the inspection for an incoming manifest against the host's installed state.
     * Version ordering reuses the marketplace's semver comparator, so the pre-upload
     * confirmation and the catalog's {@code updateAvailable} badge can never disagree.
     */
    public static PackageInspection of(PluginManifest incoming, Optional<PluginManifest> installedManifest) {
        PluginManifest local = installedManifest == null ? null : installedManifest.orElse(null);
        String comparison = null;
        if (local != null) {
            int order = PluginMarketplaceService.compareVersions(incoming.version(), local.version());
            comparison = order > 0 ? UPGRADE : order < 0 ? DOWNGRADE : SAME;
        }
        return new PackageInspection(
            incoming.id(),
            incoming.name(),
            incoming.version(),
            local != null,
            local != null ? local.version() : null,
            comparison);
    }
}
