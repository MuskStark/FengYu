package fan.summer.ui.store;

import fan.summer.zhiflow.api.ToolCategory;

import java.util.Map;

/**
 * Pure, side-effect-free helpers for the online plugin store: version comparison,
 * install-state classification, and search/category filtering. Kept separate from
 * {@link OnlineStorePane} so the logic is unit-testable without a JavaFX runtime.
 *
 * @since 1.0
 */
public final class StorePluginLogic {

    private StorePluginLogic() {}

    /** Install status of a store plugin relative to the locally installed set. */
    public enum InstallState { NOT_INSTALLED, INSTALLED, UPDATABLE }

    /**
     * Compares two dotted version strings segment by segment. Numeric segments are
     * compared as integers; if either segment is non-numeric, that segment pair is
     * compared lexicographically. Missing trailing segments are treated as "0".
     *
     * @return &gt;0 if {@code a} is newer, 0 if equal, &lt;0 if {@code a} is older
     */
    public static int compareVersion(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;

        String[] as = a.split("\\.");
        String[] bs = b.split("\\.");
        int n = Math.max(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            String sa = i < as.length ? as[i] : "0";
            String sb = i < bs.length ? bs[i] : "0";
            if (sa.equals(sb)) continue;
            Integer ia = tryParse(sa);
            Integer ib = tryParse(sb);
            int cmp;
            if (ia != null && ib != null) {
                cmp = Integer.compare(ia, ib);
            } else {
                cmp = sa.compareTo(sb);
            }
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private static Integer tryParse(String s) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return null; }
    }

    /**
     * Classifies a store plugin's install state against the installed id→version map.
     *
     * @param id           the store plugin id
     * @param storeVersion the version offered by the store
     * @param installed    map of installed plugin id → version; may be null/empty
     */
    public static InstallState installState(String id, String storeVersion,
                                            Map<String, String> installed) {
        if (installed == null || !installed.containsKey(id)) {
            return InstallState.NOT_INSTALLED;
        }
        String localVersion = installed.get(id);
        return compareVersion(storeVersion, localVersion) > 0
                ? InstallState.UPDATABLE
                : InstallState.INSTALLED;
    }

    /**
     * Tests whether a plugin passes the current search query and category filter.
     *
     * @param p      the plugin
     * @param query  case-insensitive substring matched against name/description/id;
     *               null or blank matches everything
     * @param filter required category, or null to mean "all categories"
     */
    public static boolean matches(StorePlugin p, String query, ToolCategory filter) {
        if (filter != null && p.category != filter) return false;
        if (query == null || query.isBlank()) return true;
        String q = query.trim().toLowerCase();
        return contains(p.name, q) || contains(p.description, q) || contains(p.id, q);
    }

    private static boolean contains(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase().contains(lowerNeedle);
    }
}
