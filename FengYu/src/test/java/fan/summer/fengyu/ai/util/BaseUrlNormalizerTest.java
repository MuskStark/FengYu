package fan.summer.fengyu.ai.util;

import org.junit.jupiter.api.Test;

import static fan.summer.fengyu.ai.util.BaseUrlNormalizer.Provider.ANTHROPIC;
import static fan.summer.fengyu.ai.util.BaseUrlNormalizer.Provider.OPENAI_COMPATIBLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins {@link BaseUrlNormalizer}'s per-provider base-URL contract. The two vendor SDKs
 * take opposite shapes (OpenAI-compatible needs {@code /v1}; Anthropic forbids it), and
 * this is the logic both {@code ChatModelConfig} and {@code ConnectionTester} rely on.
 */
class BaseUrlNormalizerTest {

    // ── OpenAI-compatible (OpenAI + DeepSeek): base URL must carry /v1 ──

    @Test
    void openaiBareRootGetsV1Appended() {
        assertEquals("https://api.openai.com/v1",
                BaseUrlNormalizer.normalizeForSdk("https://api.openai.com", OPENAI_COMPATIBLE));
    }

    @Test
    void openaiAlreadyHasV1IsUntouched() {
        assertEquals("https://api.openai.com/v1",
                BaseUrlNormalizer.normalizeForSdk("https://api.openai.com/v1", OPENAI_COMPATIBLE));
    }

    @Test
    void openaiTrailingSlashStrippedThenV1Appended() {
        assertEquals("https://api.openai.com/v1",
                BaseUrlNormalizer.normalizeForSdk("https://api.openai.com/", OPENAI_COMPATIBLE));
    }

    @Test
    void openaiCustomGatewayWithoutV1GetsV1() {
        assertEquals("https://gateway.example.com/v1",
                BaseUrlNormalizer.normalizeForSdk("https://gateway.example.com", OPENAI_COMPATIBLE));
    }

    @Test
    void openaiCustomGatewayWithV1Untouched() {
        assertEquals("https://gateway.example.com/v1",
                BaseUrlNormalizer.normalizeForSdk("https://gateway.example.com/v1", OPENAI_COMPATIBLE));
    }

    @Test
    void openaiNewerVersionSegmentUntouched() {
        // /v2 (or any /vN) already satisfies the "ends in a version segment" check.
        assertEquals("https://gateway.example.com/v2",
                BaseUrlNormalizer.normalizeForSdk("https://gateway.example.com/v2", OPENAI_COMPATIBLE));
    }

    @Test
    void deepseekBehavesAsOpenAiCompatible() {
        assertEquals("https://api.deepseek.com/v1",
                BaseUrlNormalizer.normalizeForSdk("https://api.deepseek.com", OPENAI_COMPATIBLE));
    }

    // ── Anthropic: base URL must NOT carry /v1 ──────────────────────────

    @Test
    void anthropicBareRootIsUntouched() {
        assertEquals("https://api.anthropic.com",
                BaseUrlNormalizer.normalizeForSdk("https://api.anthropic.com", ANTHROPIC));
    }

    @Test
    void anthropicTrailingV1Stripped() {
        assertEquals("https://api.anthropic.com",
                BaseUrlNormalizer.normalizeForSdk("https://api.anthropic.com/v1", ANTHROPIC));
    }

    @Test
    void anthropicTrailingSlashAndV1Stripped() {
        assertEquals("https://api.anthropic.com",
                BaseUrlNormalizer.normalizeForSdk("https://api.anthropic.com/v1/", ANTHROPIC));
    }

    @Test
    void anthropicGatewayWithoutV1Untouched() {
        assertEquals("https://gw.example.com",
                BaseUrlNormalizer.normalizeForSdk("https://gw.example.com", ANTHROPIC));
    }

    @Test
    void anthropicNewerVersionStripped() {
        // Any /vN tail is stripped for Anthropic, not just /v1.
        assertEquals("https://gw.example.com",
                BaseUrlNormalizer.normalizeForSdk("https://gw.example.com/v2", ANTHROPIC));
    }

    // ── Blank passthrough (let the SDK's own default apply downstream) ───

    @Test
    void blankInputReturnedAsIsNull() {
        assertNull(BaseUrlNormalizer.normalizeForSdk(null, OPENAI_COMPATIBLE));
    }

    @Test
    void blankInputReturnedAsIsEmpty() {
        assertEquals("", BaseUrlNormalizer.normalizeForSdk("", ANTHROPIC));
    }

    @Test
    void blankInputReturnedAsIsWhitespace() {
        assertEquals("   ", BaseUrlNormalizer.normalizeForSdk("   ", OPENAI_COMPATIBLE));
    }

    // ── describeFix ─────────────────────────────────────────────────────

    @Test
    void describeFixNullWhenUnchanged() {
        assertNull(BaseUrlNormalizer.describeFix(
                "https://api.openai.com/v1", "https://api.openai.com/v1"));
    }

    @Test
    void describeFixNullWhenOnlyTrailingSlash() {
        // A trailing slash alone is cosmetic and not surfaced as a meaningful fix.
        assertNull(BaseUrlNormalizer.describeFix(
                "https://api.openai.com/v1/", "https://api.openai.com/v1"));
    }

    @Test
    void describeFixNonNulWhenNormalized() {
        String note = BaseUrlNormalizer.describeFix(
                "https://api.openai.com", "https://api.openai.com/v1");
        assertNotNull(note);
        // Mentions both ends of the transformation so the user can update the setting.
        assertEquals(true, note.contains("https://api.openai.com"));
        assertEquals(true, note.contains("https://api.openai.com/v1"));
    }
}
