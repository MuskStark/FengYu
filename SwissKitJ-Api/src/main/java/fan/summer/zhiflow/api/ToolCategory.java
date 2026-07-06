package fan.summer.zhiflow.api;

/**
 * Categories used to group tools in the sidebar navigation and across the tool registry.
 *
 * <p>Each value carries a lowercase {@code id} (used for serialisation and legacy
 * compatibility) and an i18n key for localised display names shown in the UI.</p>
 *
 * @see SwissKitJPlugin#getCategory()
 */
public enum ToolCategory {
    /** Development tools such as code formatters, build helpers, and CLI utilities. */
    DEV("dev", "developer.tools"),

    /** Text processing tools such as encoders, formatters, and generators. */
    TEXT("text", "text.processing"),

    /** Image manipulation and conversion tools. */
    IMAGE("image", "image.processing"),

    /** Network-related tools such as API testers, fetch utilities, and connection checkers. */
    NET("net", "network.tools"),

    /** Catch-all category for tools that do not fit any of the above groups. */
    OTHER("other", "other.tools");

    private final String id;
    private final String i18nKey;

    ToolCategory(String id, String i18nKey) {
        this.id = id;
        this.i18nKey = i18nKey;
    }

    /**
     * Returns the lowercase identifier used in persistence, serialisation, and
     * matching against legacy configuration.
     *
     * @return the category id, e.g. {@code "dev"}
     */
    public String getId() { return id; }

    /**
     * Returns the resource-bundle key used to look up the localised display name
     * shown in the sidebar and filter labels.
     *
     * @return the i18n key, e.g. {@code "developer.tools"}
     */
    public String getI18nKey() { return i18nKey; }

    /**
     * Converts a legacy string identifier to a {@link ToolCategory}.
     *
     * @param id the lowercase id string, e.g. {@code "dev"}
     * @return the matching {@code ToolCategory}, or {@link #OTHER} if {@code id} is
     *         {@code null} or not recognised
     */
    public static ToolCategory fromId(String id) {
        if (id == null) return OTHER;
        for (ToolCategory c : values()) {
            if (c.id.equalsIgnoreCase(id)) return c;
        }
        return OTHER;
    }
}
