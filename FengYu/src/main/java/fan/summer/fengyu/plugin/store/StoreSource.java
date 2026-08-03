package fan.summer.fengyu.plugin.store;

/**
 * In-memory view of a subscribed marketplace source (mirrors {@link StoreSourceEntity}).
 *
 * @param origin     stable unique identifier used as the uid prefix (e.g. "anthropics-claude")
 * @param sourceType which ecosystem's format the catalog uses
 * @param catalogUrl HTTPS URL of the marketplace.json / catalog.json
 * @param name       human-friendly name shown in the source manager UI
 */
public record StoreSource(String origin, StoreSourceType sourceType, String catalogUrl, String name) {
}
