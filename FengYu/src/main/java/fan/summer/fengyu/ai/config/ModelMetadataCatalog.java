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
            Integer maxOutputTokens, ThinkingSpec thinking, CostRates cost) {}

    /** Rough public list prices in USD per million tokens (catalog-grade approximations). */
    public record CostRates(double inputPerMillion, double outputPerMillion) {
        /** Output-side USD cost for a token count: rate ($/M) x tokens / 1e6. */
        public double outputCostUsd(long outputTokens) {
            return outputPerMillion * Math.max(0, outputTokens) / 1_000_000d;
        }
    }

    /**
     * Declarative thinking-control descriptor from the catalog: the STYLE selects a
     * hard request template (data, not code), the LEVELS are the model's supported
     * knobs, and {@code defaultLevel} applies when the configured level is not in
     * the list. Styles known today:
     * <ul>
     *   <li>{@code reasoning_effort} — OpenAI-style top-level {@code reasoning_effort}</li>
     *   <li>{@code thinking_type} — GLM/DeepSeek/MiMo {@code thinking:{type:enabled|disabled}}</li>
     *   <li>{@code enable_thinking} — Qwen/DashScope boolean switch</li>
     *   <li>{@code shotgun} — all OpenAI-compatible shapes at once (server picks)</li>
     *   <li>{@code anthropic_enabled} / {@code anthropic_adaptive} — Anthropic thinking configs</li>
     *   <li>{@code ollama_boolean} / {@code ollama_level} — Ollama {@code think} option</li>
     * </ul>
     */
    public record ThinkingSpec(String style, java.util.List<String> levels, String defaultLevel) {}

    private record FamilyRule(Pattern pattern, ModelInfo info) {}

    /** Parsed catalog: exact entries and the ordered family rules. Test-visible shape. */
    record Catalog(List<ModelInfo> exact, List<String> exactIds, List<FamilyRule> families) {}

    private static volatile Catalog catalog;

    /** Remote overlay (C1): consulted BEFORE the bundled baseline; remote wins ties. */
    private static volatile Catalog remoteOverlay;

    private ModelMetadataCatalog() {}

    /** Resolves one model id; empty when the catalog has no opinion. */
    public static Optional<ModelInfo> find(String modelId) {
        if (modelId == null || modelId.isBlank()) return Optional.empty();
        String id = modelId.trim();
        Catalog overlay = remoteOverlay;
        if (overlay != null) {
            for (int i = 0; i < overlay.exactIds().size(); i++) {
                if (overlay.exactIds().get(i).equalsIgnoreCase(id)) {
                    return Optional.of(overlay.exact().get(i));
                }
            }
        }
        Catalog data = catalog();
        for (int i = 0; i < data.exactIds().size(); i++) {
            if (data.exactIds().get(i).equalsIgnoreCase(id)) {
                return Optional.of(data.exact().get(i));
            }
        }
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        if (overlay != null) {
            for (FamilyRule rule : overlay.families()) {
                if (rule.pattern().matcher(lower).find()) return Optional.of(rule.info());
            }
        }
        for (FamilyRule rule : data.families()) {
            if (rule.pattern().matcher(lower).find()) return Optional.of(rule.info());
        }
        return Optional.empty();
    }

    /**
     * Installs a remote catalog overlay (C1). The overlay's exact entries and family
     * rules are consulted before the bundled baseline, so a remote entry with the
     * same id/pattern overrides it and a new one appends. Malformed JSON is ignored
     * (baseline stays); {@code null} clears the overlay.
     */
    public static void applyRemoteOverlay(String overlayJson) {
        if (overlayJson == null) {
            remoteOverlay = null;
            return;
        }
        try {
            java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(
                    overlayJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            remoteOverlay = loadFromStream(in);
        } catch (Exception ignored) {
            // A malformed overlay must never take lookups down — baseline stays.
        }
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

    /**
     * The provider's published OUTPUT cap for the id, or empty when unknown.
     */
    public static Optional<Integer> maxOutputTokens(String modelId) {
        return find(modelId).flatMap(info -> Optional.ofNullable(info.maxOutputTokens()));
    }

    /**
     * An EXACT-id image-input verdict, ignoring family regex heuristics. Only a
     * curated exact assertion may drive proactive media downgrades — a family guess
     * being wrong would strip images that used to work, so those stay permissive and
     * let the runtime media-rejection fallback stay authoritative.
     */
    public static Optional<Boolean> supportsImageExact(String modelId) {
        if (modelId == null || modelId.isBlank()) return Optional.empty();
        Catalog data = catalog();
        String id = modelId.trim();
        for (int i = 0; i < data.exactIds().size(); i++) {
            if (data.exactIds().get(i).equalsIgnoreCase(id)) {
                return Optional.ofNullable(data.exact().get(i).supportsImage());
            }
        }
        return Optional.empty();
    }

    /**
     * The thinking descriptor for the id (exact match first, then the ordered family
     * rules); empty when the catalog has no thinking opinion (no thinking fields sent).
     */
    public static Optional<ThinkingSpec> thinkingFor(String modelId) {
        return find(modelId).flatMap(info -> Optional.ofNullable(info.thinking()));
    }

    /** Parses one {@code thinking:{style,levels,defaultLevel}} node; null when absent/invalid. */
    private static ThinkingSpec thinking(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        String style = text(node, "style");
        JsonNode levelsNode = node.get("levels");
        if (style == null || style.isBlank() || levelsNode == null || !levelsNode.isArray()) return null;
        java.util.List<String> levels = new ArrayList<>();
        for (JsonNode l : levelsNode) if (l.isTextual()) levels.add(l.asText());
        if (levels.isEmpty()) return null;
        String defaultLevel = text(node, "defaultLevel");
        return new ThinkingSpec(style, List.copyOf(levels),
                defaultLevel != null && levels.contains(defaultLevel) ? defaultLevel : levels.get(0));
    }

    /** The cost rates for the id; empty when the catalog has no pricing opinion. */
    public static Optional<CostRates> costFor(String modelId) {
        return find(modelId).flatMap(info -> Optional.ofNullable(info.cost()));
    }

    /** Parses one {@code cost:{input,output}} node ($/M tokens); null when absent/invalid. */
    private static CostRates cost(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        JsonNode in = node.get("input");
        JsonNode out = node.get("output");
        if (in == null || !in.isNumber() || out == null || !out.isNumber()) return null;
        return new CostRates(in.asDouble(), out.asDouble());
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
            return loadFromStream(in);
        } catch (Exception ignored) {
            // A malformed bundled file must not take the app down — resolve as "no opinion".
        }
        return new Catalog(exact, exactIds, families);
    }

    /** Stream-level parse shared by the bundled resource and the remote overlay. */
    private static Catalog loadFromStream(InputStream in) throws Exception {
        List<ModelInfo> exact = new ArrayList<>();
        List<String> exactIds = new ArrayList<>();
        List<FamilyRule> families = new ArrayList<>();
        {
            if (in == null) return new Catalog(exact, exactIds, families);
            JsonNode root = new ObjectMapper().readTree(in);
            for (JsonNode node : root.path("models")) {
                String id = text(node, "id");
                // Entries without a usable id are skipped, not stored: find() matches ids
                // and a stored null would NPE the equalsIgnoreCase below.
                if (id == null || id.isBlank()) continue;
                exactIds.add(id);
                exact.add(new ModelInfo(positive(node, "contextWindowTokens"),
                        triState(node, "supportsImage"), positive(node, "maxOutputTokens"),
                        thinking(node.get("thinking")), cost(node.get("cost"))));
            }
            for (JsonNode node : root.path("families")) {
                String pattern = text(node, "pattern");
                if (pattern == null || pattern.isBlank()) continue;
                families.add(new FamilyRule(Pattern.compile(pattern),
                        new ModelInfo(positive(node, "contextWindowTokens"),
                                triState(node, "supportsImage"), positive(node, "maxOutputTokens"),
                                thinking(node.get("thinking")), cost(node.get("cost")))));
            }
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
