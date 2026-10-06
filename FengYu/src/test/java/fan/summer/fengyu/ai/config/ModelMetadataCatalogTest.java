package fan.summer.fengyu.ai.config;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ModelMetadataCatalog} resolution: exact-id precedence over families,
 * top-down family order, case-insensitivity, unknown ids resolving empty, and the
 * malformed-file degradation (an empty catalog, never an exception).
 */
class ModelMetadataCatalogTest {

    @Test
    void exactIdBeatsFamilyRules() {
        // "deepseek-chat" is both an exact entry and covered by the deepseek-.* family;
        // both say 128k here, so prove precedence with the bundled claude default:
        // claude-sonnet-4-20250514 (exact, 200k) must resolve identically to the family
        // rule, while a family-only id picks the family.
        assertEquals(Optional.of(200000),
                ModelMetadataCatalog.contextWindow("claude-sonnet-4-20250514"));
        assertEquals(Optional.of(200000), ModelMetadataCatalog.contextWindow("claude-3-5-sonnet-20241022"));
        // glm-4.6v (generation-specific vision rule, first) must not fall through to
        // glm-4.6.* (200k text) nor to the generic vision fallback.
        assertEquals(Optional.of(131072), ModelMetadataCatalog.contextWindow("glm-4.6v"));
        assertEquals(Optional.of(true), ModelMetadataCatalog.supportsImage("glm-4.6v"));
        assertEquals(Optional.of(32768), ModelMetadataCatalog.maxOutputTokens("glm-4.6v"));
        assertEquals(Optional.of(false), ModelMetadataCatalog.supportsImage("glm-4.6"));
    }

    @Test
    void matchingIsCaseInsensitiveAndWhitespaceTolerant() {
        assertEquals(Optional.of(128000), ModelMetadataCatalog.contextWindow("GPT-4O"));
        assertEquals(Optional.of(128000), ModelMetadataCatalog.contextWindow(" deepseek-chat "));
    }

    @Test
    void familyPatternsCoverCommonOllamaTags() {
        assertEquals(Optional.of(131072), ModelMetadataCatalog.contextWindow("qwen3:4b"));
        assertEquals(Optional.of(false), ModelMetadataCatalog.supportsImage("qwen3:4b"));
        assertEquals(Optional.of(true), ModelMetadataCatalog.supportsImage("qwen2.5-vl-7b"));
        // qwen2.5-vl's NATIVE window is 32k (HF card) — not the 131k of its text sibling.
        assertEquals(Optional.of(32768), ModelMetadataCatalog.contextWindow("qwen2.5-vl-7b"));
    }

    @Test
    void unknownModelsResolveEmpty() {
        assertTrue(ModelMetadataCatalog.find("totally-unknown-model").isEmpty());
        assertTrue(ModelMetadataCatalog.find(null).isEmpty());
        assertTrue(ModelMetadataCatalog.find("").isEmpty());
        assertTrue(ModelMetadataCatalog.find("   ").isEmpty());
    }

    @Test
    void outputCapsResolvePerModelAndFamily() {
        assertEquals(Optional.of(16384), ModelMetadataCatalog.maxOutputTokens("gpt-4o"));
        assertEquals(Optional.of(64000), ModelMetadataCatalog.maxOutputTokens("claude-sonnet-4-20250514"));
        assertEquals(Optional.of(131072), ModelMetadataCatalog.maxOutputTokens("glm-4.7-flash"));
        assertEquals(Optional.of(128000), ModelMetadataCatalog.maxOutputTokens("glm-5.3"));
        assertEquals(Optional.of(131072), ModelMetadataCatalog.maxOutputTokens("kimi-k3-256k"));
        assertEquals(Optional.of(98304), ModelMetadataCatalog.maxOutputTokens("kimi-k2.6"));
        assertEquals(Optional.of(131072), ModelMetadataCatalog.maxOutputTokens("minimax-m2"));
        assertEquals(Optional.of(32000), ModelMetadataCatalog.maxOutputTokens("minimax-m2.5"));
        assertEquals(Optional.of(384000), ModelMetadataCatalog.maxOutputTokens("deepseek-v4-pro"));
        // The V3-era default model keeps its hard 8192 ceiling.
        assertEquals(Optional.of(8192), ModelMetadataCatalog.maxOutputTokens("deepseek-chat"));
    }

    @Test
    void familiesMayAssertContextWithoutOutputCap() {
        // The GENERIC GLM vision fallback deliberately omits the output cap — a glm vision
        // id no generation-specific rule knows keeps the flat default there, while the
        // window still resolves.
        assertEquals(Optional.of(65536), ModelMetadataCatalog.contextWindow("glm-4.4v"));
        assertTrue(ModelMetadataCatalog.maxOutputTokens("glm-4.4v").isEmpty());
    }

    @Test
    void malformedResourceYieldsEmptyCatalogNotCrash() {
        ModelMetadataCatalog.Catalog catalog = ModelMetadataCatalog.load("/ai/does-not-exist.json");
        assertTrue(catalog.exactIds().isEmpty());
        assertTrue(catalog.families().isEmpty());
        // Malformed JSON content (not just a missing file) degrades the same way.
        ModelMetadataCatalog.Catalog broken = ModelMetadataCatalog.load("/ai/model-metadata-broken-test.json");
        assertTrue(broken.exactIds().isEmpty());
    }
}
