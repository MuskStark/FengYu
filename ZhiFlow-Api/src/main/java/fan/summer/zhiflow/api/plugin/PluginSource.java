package fan.summer.zhiflow.api.plugin;

/**
 * Declared origin of a plugin. Drives UI badges (Official / Third-party) and
 * optional trust checks. {@code labelKey} is resolved by the frontend via vue-i18n.
 */
public enum PluginSource {
    OFFICIAL("official", "source.official"),
    THIRD_PARTY("third_party", "source.third_party");

    private final String id;
    private final String labelKey;

    PluginSource(String id, String labelKey) {
        this.id = id;
        this.labelKey = labelKey;
    }

    public String getId() { return id; }
    public String getLabelKey() { return labelKey; }
}
