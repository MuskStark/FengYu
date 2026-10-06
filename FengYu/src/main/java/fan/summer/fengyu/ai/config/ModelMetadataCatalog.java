package fan.summer.fengyu.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Bundled per-model capability estimates: context-window size and image-input support.
 *
 * <p>Loaded once from {@code classpath:ai/model-metadata.json}. An exact (case-insensitive)
 * id always beats the family rules; families apply top-down and the first match wins —
 * the file's order IS its precedence. Every value is an approximation of the provider's
 * public listing: the catalog only has to be closer than the flat legacy default
 * ({@link fan.summer.fengyu.ai.AiConfigService#DEFAULT_CONTEXT_WINDOW_TOKENS}), never exact,
 * and an explicit user setting always overrides it (see
 * {@link fan.summer.fengyu.ai.AiConfigService#effectiveContextWindowTokens}).</p>
 *
 * <p>Unknown models resolve empty: callers keep their previous behavior (the setting or
 * the legacy default for the window; permissive image attachment, which the
 * strict-gateway media fallback already recovers on text-only endpoints).</p>
 */
public final class ModelMetadataCatalog {

    private static final String RESOURCE = "/ai/model-metadata.json";

    /** One resolved capability record; a field is null when the entry does not assert it. */
    public record ModelInfo(Integer contextWindowTokens, Boolean supportsImage,
            Integer maxOutputTokens) {}

    private record FamilyRule(Pattern pattern, ModelInfo info) {}

    /** Parsed catalog: exact entries and the ordered family rules. Test-visible shape. */
    record Catalog(List<ModelInfo> exact, List<String> exactIds, List<FamilyRule> families) {}

    private static volatile Catalog catalog;

    private ModelMetadataCatalog() {}

    /** Resolves one model id; empty when the catalog has no opinion. */
    public static Optional<ModelInfo> find(String modelId) {
        if (modelId == null || modelId.isBlank()) return Optional.empty();
        String id = modelId.trim();
        Catalog data = catalog();
        for (int i = 0; i < data.exactIds().size(); i++) {
            if (data.exactIds().get(i).equalsIgnoreCase(id)) {
                return Optional.of(data.exact().get(i));
            }
        }
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        for (FamilyRule rule : data.families()) {
            if (rule.pattern().matcher(lower).find()) return Optional.of(rule.info());
        }
        return Optional.empty();
    }

    /** The catalog's context window for the id, or empty when unknown. */
    public static Optional<Integer> contextWindow(String modelId) {
        return find(modelId).flatMap(info -> Optional.ofNullable(info.contextWindowTokens()));
    }

    /**
     * The catalog's image-input verdict for the id, or empty when unknown — callers treat
     * unknown as permissive and let the wire-level media fallback correct a wrong guess.
     */
    public static Optional<Boolean> supportsImage(String modelId) {
        return find(modelId).flatMap(info -> Optional.ofNullable(info.supportsImage()));
    }

    /** The provider's published OUTPUT cap for the id, or empty when unknown. */
    public static Optional<Integer> maxOutputTokens(String modelId) {
        return find(modelId).flatMap(info -> Optional.ofNullable(info.maxOutputTokens()));
    }

    private static Catalog catalog() {
        Catalog loaded = catalog;
        if (loaded == null) {
            synchronized (ModelMetadataCatalog.class) {
                if (catalog == null) catalog = load(RESOURCE);
                loaded = catalog;
            }
        }
        return loaded;
    }

    /** Parses catalog JSON; any structural problem yields an empty catalog, never a crash. */
    static Catalog load(String resource) {
        List<ModelInfo> exact = new ArrayList<>();
        List<String> exactIds = new ArrayList<>();
        List<FamilyRule> families = new ArrayList<>();
        try (InputStream in = ModelMetadataCatalog.class.getResourceAsStream(resource)) {
            if (in == null) return new Catalog(exact, exactIds, families);
            JsonNode root = new ObjectMapper().readTree(in);
            for (JsonNode node : root.path("models")) {
                String id = text(node, "id");
                // Entries without a usable id are skipped, not stored: find() matches ids
                // and a stored null would NPE the equalsIgnoreCase below.
                if (id == null || id.isBlank()) continue;
                exactIds.add(id);
                exact.add(new ModelInfo(positive(node, "contextWindowTokens"),
                        triState(node, "supportsImage"), positive(node, "maxOutputTokens")));
            }
            for (JsonNode node : root.path("families")) {
                String pattern = text(node, "pattern");
                if (pattern == null || pattern.isBlank()) continue;
                families.add(new FamilyRule(Pattern.compile(pattern),
                        new ModelInfo(positive(node, "contextWindowTokens"),
                                triState(node, "supportsImage"), positive(node, "maxOutputTokens"))));
            }
        } catch (Exception ignored) {
            // A malformed bundled file must not take the app down — resolve as "no opinion".
        }
        return new Catalog(List.copyOf(exact), List.copyOf(exactIds), List.copyOf(families));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static Integer positive(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToInt() && value.asInt() > 0 ? value.asInt() : null;
    }

    private static Boolean triState(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : null;
    }
}
