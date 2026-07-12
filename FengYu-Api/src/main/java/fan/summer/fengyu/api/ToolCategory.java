package fan.summer.fengyu.api;

/**
 * Categories used to group tools in the sidebar navigation and across the tool registry.
 *
 * <p>Each value carries a lowercase {@code id} (used for serialisation and legacy
 * compatibility) and an i18n key for localised display names shown in the UI.</p>
 *
 * @see FengYuPlugin#getCategory()
 */
public enum ToolCategory {
    /** Development tools such as code formatters, build helpers, and CLI utilities. */
    DEV("dev", "category.dev"),

    /** Text processing tools such as encoders, formatters, and generators. */
    TEXT("text", "category.text"),

    /** Image manipulation and conversion tools. */
    IMAGE("image", "category.image"),

    /** Network-related tools such as API testers, fetch utilities, and connection checkers. */
    NET("net", "category.net"),

    /** AI / agent / prompt tools such as prompt builders and LLM helpers. */
    AI("ai", "category.ai"),

    /** Catch-all category for tools that do not fit any of the above groups. */
    OTHER("other", "category.other");

    private final String id;
    private final String labelKey;

    ToolCategory(String id, String labelKey) {
        this.id = id;
        this.labelKey = labelKey;
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
     * @return the label key, e.g. {@code "category.dev"}
     */
    public String getLabelKey() { return labelKey; }

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
