package fan.summer.i18n;

/**
 * Supported application languages.
 *
 * @author summer
 */
public enum Language {
    ENGLISH("en"),
    CHINESE("zh");

    private final String code;

    Language(String code) {
        this.code = code;
    }

    /**
     * Returns the language code (e.g., "en", "zh").
     *
     * @return language code
     */
    public String getCode() {
        return code;
    }

    /**
     * Finds Language by code string.
     *
     * @param code language code (e.g., "en", "zh")
     * @return matching Language or ENGLISH as default
     */
    public static Language fromCode(String code) {
        for (Language lang : values()) {
            if (lang.code.equals(code)) {
                return lang;
            }
        }
        return ENGLISH;
    }

    /**
     * Returns the java.util.Locale for this language.
     *
     * @return locale
     */
    public java.util.Locale toLocale() {
        return new java.util.Locale(code);
    }
}