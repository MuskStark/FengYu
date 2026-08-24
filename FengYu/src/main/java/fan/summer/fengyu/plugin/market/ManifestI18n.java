package fan.summer.fengyu.plugin.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;
import java.util.List;
import java.util.Map;

/**
 * Resolves a manifest's display strings for the current request locale. The manifest's top-level
 * {@code name}/{@code description} are the default (English) values; the optional {@code i18n}
 * block overrides them per locale. Lookup falls back through the language family and finally to the
 * top-level default, so a plugin that omits a locale — or translates only some strings — never
 * surfaces a missing value.
 *
 * <p>Resolution order for {@code locale = "zh-CN"}:
 * <ol>
 *   <li>{@code i18n["zh-CN"]}</li>
 *   <li>{@code i18n["zh"]} (language family)</li>
 *   <li>top-level default</li>
 * </ol>
 *
 * <p>The current locale is read from Spring's {@link LocaleContextHolder}, populated by the
 * {@code AcceptHeaderLocaleResolver}. AI-tool descriptions returned here are for <em>frontend
 * display only</em>; the strings sent to the LLM are always the top-level English originals.
 */
public final class ManifestI18n {
    /** Default locale used when no Accept-Language header is present or the header is unparseable. */
    public static final String DEFAULT_LOCALE = "en";

    private ManifestI18n() {}

    /** Resolve the request locale to a short lowercased language tag (e.g. {@code "zh"}, {@code "en"}). */
    public static String currentLocale() {
        return shortTag(LocaleContextHolder.getLocale());
    }

    /**
     * Best-effort parse of a raw {@code Accept-Language} header (or any locale string) into a short
     * lowercased language tag. Returns {@link #DEFAULT_LOCALE} for blank/garbled input. Keeps only
     * the language subtag so {@code zh-CN} collapses to {@code zh}, matching the {@code en/zh} keys
     * the manifest i18n block uses.
     */
    public static String resolveLocale(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) return DEFAULT_LOCALE;
        String raw = acceptLanguage.trim();
        // Accept-Language may carry q-values and multiple ranges: "zh-CN,zh;q=0.9,en;q=0.8".
        String first = raw.split(",")[0].trim();
        String parsed = Locale.forLanguageTag(first).getLanguage();
        return (parsed == null || parsed.isEmpty()) ? DEFAULT_LOCALE : parsed.toLowerCase(Locale.ROOT);
    }

    /** Localized plugin name: override for the locale (or its family) → top-level default. */
    public static String name(PluginManifest m) { return name(m, currentLocale()); }

    /** Localized plugin description: override for the locale (or its family) → top-level default. */
    public static String description(PluginManifest m) { return description(m, currentLocale()); }

    /**
     * Localized description for one AI tool (frontend display): override for the locale (or its
     * family) → the tool's top-level English description. Returns {@code null} only when the tool
     * itself has no description at any level.
     */
    public static String aiToolDescription(PluginManifest m, String aiToolName) {
        return aiToolDescription(m, aiToolName, currentLocale());
    }

    public static String name(PluginManifest m, String locale) {
        if (m == null) return null;
        String override = pickOverride(m, locale, o -> o.name());
        return override != null ? override : m.name();
    }

    public static String description(PluginManifest m, String locale) {
        if (m == null) return null;
        String override = pickOverride(m, locale, o -> o.description());
        return override != null ? override : m.description();
    }

    public static String aiToolDescription(PluginManifest m, String aiToolName, String locale) {
        if (m == null || aiToolName == null) return null;
        String override = pickAiToolOverride(m, aiToolName, locale);
        if (override != null) return override;
        if (m.aiTools() != null) {
            for (var tool : m.aiTools()) {
                if (aiToolName.equals(tool.name())) return tool.description();
            }
        }
        return null;
    }

    /** Localized flow-node descriptor, falling back to the canonical English overlay. */
    public static JsonNode flowNode(PluginManifest m, String aiToolName, String locale) {
        if (m == null || aiToolName == null) return null;
        JsonNode canonical = m.flowNodeFor(aiToolName);
        if (canonical == null) return null;
        JsonNode override = pickFlowNodeOverride(m, aiToolName, locale);
        if (override == null || !override.isObject()) return canonical;
        return localizeFlowNode(canonical, override);
    }

    /** Apply one display-only locale delta to a canonical flow-node overlay. */
    public static JsonNode localizeFlowNode(JsonNode canonical, JsonNode override) {
        if (canonical == null || !canonical.isObject()) return canonical;
        if (override == null || !override.isObject()) return canonical;
        ObjectNode localized = canonical.deepCopy();
        mergeNodeDisplay(localized, override);
        return localized;
    }

    private static String pickOverride(PluginManifest m, String locale,
            java.util.function.Function<PluginManifest.LocaleOverride, String> field) {
        Map<String, PluginManifest.LocaleOverride> table = m.i18n();
        if (table == null || table.isEmpty() || locale == null) return null;
        Locale requested = Locale.forLanguageTag(locale);
        // Candidate keys, most-specific first: full tag, then language family.
        for (String key : localeKeys(requested, locale)) {
            PluginManifest.LocaleOverride entry = lookupLocale(table, key);
            if (entry != null) {
                String value = field.apply(entry);
                if (value != null && !value.isBlank()) return value;
            }
        }
        return null;
    }

    private static String pickAiToolOverride(PluginManifest m, String aiToolName, String locale) {
        Map<String, PluginManifest.LocaleOverride> table = m.i18n();
        if (table == null || table.isEmpty() || locale == null) return null;
        Locale requested = Locale.forLanguageTag(locale);
        for (String key : localeKeys(requested, locale)) {
            PluginManifest.LocaleOverride entry = lookupLocale(table, key);
            if (entry == null || entry.aiTools() == null) continue;
            // Tool keys are author-chosen identifiers; match them case-insensitively too for the
            // same reason as locale tags — consistency over fragile exact-case equality.
            PluginManifest.AiToolOverride tool = lookupTool(entry.aiTools(), aiToolName);
            if (tool != null && tool.description() != null && !tool.description().isBlank()) {
                return tool.description();
            }
        }
        return null;
    }

    private static JsonNode pickFlowNodeOverride(
            PluginManifest m, String aiToolName, String locale) {
        Map<String, PluginManifest.LocaleOverride> table = m.i18n();
        if (table == null || table.isEmpty() || locale == null) return null;
        Locale requested = Locale.forLanguageTag(locale);
        for (String key : localeKeys(requested, locale)) {
            PluginManifest.LocaleOverride entry = lookupLocale(table, key);
            if (entry == null || entry.flowNodes() == null || !entry.flowNodes().isObject()) continue;
            JsonNode exact = entry.flowNodes().get(aiToolName);
            if (exact != null) return exact;
            var fields = entry.flowNodes().fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (aiToolName.equalsIgnoreCase(field.getKey())) return field.getValue();
            }
        }
        return null;
    }

    private static void mergeNodeDisplay(ObjectNode target, JsonNode override) {
        if (override.has("label")) target.set("label", override.get("label"));
        if (override.has("help")) target.set("help", override.get("help"));
        mergeNamedArray(target, "inputs", override.get("inputs"));
        mergeNamedArray(target, "outputs", override.get("outputs"));
    }

    private static void mergeNamedArray(ObjectNode target, String field, JsonNode overrides) {
        if (overrides == null || !overrides.isObject()) return;
        JsonNode array = target.get(field);
        if (array == null || !array.isArray()) return;
        for (JsonNode item : array) {
            if (!(item instanceof ObjectNode object)) continue;
            JsonNode patch = overrides.get(item.path("name").asText());
            if (patch != null && patch.isObject()) mergePortDisplay(object, patch);
        }
    }

    private static void mergePortDisplay(ObjectNode target, JsonNode override) {
        for (String field : List.of("title", "description", "placeholder", "help", "options", "examples")) {
            if (override.has(field)) target.set(field, override.get(field));
        }
        JsonNode fieldOverrides = override.get("fields");
        if (fieldOverrides != null && fieldOverrides.isObject()) {
            JsonNode fields = target.get("fields");
            if (fields != null && fields.isArray()) {
                for (JsonNode item : fields) {
                    if (item instanceof ObjectNode object) {
                        JsonNode patch = fieldOverrides.get(item.path("name").asText());
                        if (patch != null && patch.isObject()) mergePortDisplay(object, patch);
                    }
                }
            }
        }
        JsonNode propertyOverrides = override.get("properties");
        JsonNode properties = target.get("properties");
        if (propertyOverrides != null && propertyOverrides.isObject()
                && properties instanceof ObjectNode objectProperties) {
            propertyOverrides.fields().forEachRemaining(entry -> {
                JsonNode property = objectProperties.get(entry.getKey());
                if (property instanceof ObjectNode object && entry.getValue().isObject()) {
                    mergePortDisplay(object, entry.getValue());
                }
            });
        }
    }

    /**
     * Fetch a locale override by key, case-insensitively. BCP 47 tags are case-insensitive
     * ({@code "zh-CN"} and {@code "zh-cn"} denote the same locale), and manifest authors may write
     * either form, so an exact {@code Map.get} would miss on a casing mismatch.
     */
    private static PluginManifest.LocaleOverride lookupLocale(
            Map<String, PluginManifest.LocaleOverride> table, String key) {
        PluginManifest.LocaleOverride exact = table.get(key);
        if (exact != null) return exact;
        for (var e : table.entrySet()) {
            if (key.equalsIgnoreCase(e.getKey())) return e.getValue();
        }
        return null;
    }

    private static PluginManifest.AiToolOverride lookupTool(
            Map<String, PluginManifest.AiToolOverride> table, String toolName) {
        PluginManifest.AiToolOverride exact = table.get(toolName);
        if (exact != null) return exact;
        for (var e : table.entrySet()) {
            if (toolName.equalsIgnoreCase(e.getKey())) return e.getValue();
        }
        return null;
    }

    /**
     * Candidate i18n keys for a locale, most-specific first: the full lowercased tag, then the
     * language family, then — when the request is the default locale — nothing (the top-level
     * default already serves it, so an {@code en} override block would be redundant but harmless).
     */
    private static String[] localeKeys(Locale requested, String rawLocale) {
        String family = requested.getLanguage();
        if (family == null || family.isEmpty()) return new String[] { rawLocale };
        String familyLower = family.toLowerCase(Locale.ROOT);
        if (rawLocale.equalsIgnoreCase(familyLower)) return new String[] { familyLower };
        return new String[] { rawLocale.toLowerCase(Locale.ROOT), familyLower };
    }

    private static String shortTag(Locale locale) {
        if (locale == null) return DEFAULT_LOCALE;
        String lang = locale.getLanguage();
        return (lang == null || lang.isEmpty()) ? DEFAULT_LOCALE : lang.toLowerCase(Locale.ROOT);
    }
}
