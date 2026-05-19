package fan.summer.api;

/**
 * Categories used by sidebar navigation and tool grouping.
 */
public enum ToolCategory {
    DEV("dev", "developer.tools"),
    TEXT("text", "text.processing"),
    IMAGE("image", "image.processing"),
    NET("net", "network.tools"),
    OTHER("other", "other.tools");

    private final String id;
    private final String i18nKey;

    ToolCategory(String id, String i18nKey) {
        this.id = id;
        this.i18nKey = i18nKey;
    }

    /** Lowercase identifier matching the legacy string (e.g. "dev"). */
    public String getId() { return id; }

    /** Key for i18n resource bundle lookups (e.g. "developer.tools"). */
    public String getI18nKey() { return i18nKey; }

    /** Convert from a legacy string. Returns {@link #OTHER} for unrecognized input. */
    public static ToolCategory fromId(String id) {
        if (id == null) return OTHER;
        for (ToolCategory c : values()) {
            if (c.id.equalsIgnoreCase(id)) return c;
        }
        return OTHER;
    }
}
