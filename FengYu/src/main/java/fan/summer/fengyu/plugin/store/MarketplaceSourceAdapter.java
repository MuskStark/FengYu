package fan.summer.fengyu.plugin.store;

import java.util.List;

/**
 * Translates one marketplace ecosystem's catalog format into unified entries.
 * One implementation per {@link StoreSourceType}.
 *
 * @since 4.0.0
 */
public interface MarketplaceSourceAdapter {

    /** Which ecosystem this adapter handles. */
    StoreSourceType type();

    /**
     * Fetch the catalog at {@code src.catalogUrl()} and translate it to unified entries.
     * Entries that cannot be resolved remotely (e.g. Claude local-path sources) are skipped.
     * Implementations should throw on HTTP/parse failure so the registry can record last_error.
     */
    List<UnifiedCatalogEntry> fetchCatalog(StoreSource src);
}
