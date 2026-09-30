package fan.summer.fengyu.ai.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes a user-supplied AI provider base URL to the contract its official
 * Java SDK expects. Spring AI 2.0 wires each cloud provider onto its vendor SDK
 * and passes the configured base URL through <em>verbatim</em>:
 * <ul>
 *   <li><strong>OpenAI-compatible</strong> (OpenAI &amp; DeepSeek) —
 *       {@code OpenAiSetup} defaults to {@code https://api.openai.com/v1} and
 *       appends {@code chat/completions}; the base URL <em>must</em> carry
 *       {@code /v1}. A bare root ({@code https://api.openai.com}) yields a 404.</li>
 *   <li><strong>Anthropic</strong> — {@code AnthropicSetup} defaults to
 *       {@code https://api.anthropic.com} (no version segment) and the SDK
 *       builds the {@code v1/messages} path itself; the base URL <em>must not</em>
 *       carry {@code /v1}. A trailing {@code /v1} produces {@code /v1/v1/messages}.</li>
 * </ul>
 * The two contracts are opposite, so this class applies per-provider rules and is
 * the single authority shared by {@code ChatModelConfig} (live chat) and
 * {@code ConnectionTester} (probe). Both paths then accept the same input.
 *
 * <p>Normalization is conservative: it strips a trailing slash and only ever
 * appends (OpenAI-compatible) or strips (Anthropic) a {@code /vN} tail. Any
 * deeper path, query, or fragment is left untouched.
 *
 * @see fan.summer.fengyu.ai.config.ChatModelConfig
 * @see fan.summer.fengyu.ai.service.ConnectionTester
 */
public final class BaseUrlNormalizer {

    private BaseUrlNormalizer() {}

    /** The provider family whose base-URL contract is being normalized. */
    public enum Provider {
        /** OpenAI and DeepSeek — base URL must carry {@code /v1}. */
        OPENAI_COMPATIBLE,
        /** Anthropic — base URL must NOT carry {@code /v1}. */
        ANTHROPIC
    }

    /** Matches a trailing {@code /v<N>} segment, e.g. {@code /v1}, {@code /v2}. */
    private static final Pattern TRAILING_VERSION = Pattern.compile("/v\\d+$");

    /**
     * Returns a base URL that satisfies the given provider's SDK contract.
     * Strips a single trailing {@code /} first, then appends or strips {@code /v1}
     * as the provider requires. A {@code null}/blank input is returned as-is so the
     * SDK's own default kicks in downstream.
     *
     * @param baseUrl the user-supplied base URL, or {@code null}/blank
     * @param provider the provider family to normalize for
     * @return the normalized base URL, or the original blank value
     */
    public static String normalizeForSdk(String baseUrl, Provider provider) {
        if (baseUrl == null || baseUrl.isBlank()) return baseUrl;
        String base = stripTrailingSlash(baseUrl);
        return switch (provider) {
            case OPENAI_COMPATIBLE -> ensureVersionSegment(base);
            case ANTHROPIC         -> stripVersionSegment(base);
        };
    }

    /**
     * Describes the correction applied by {@link #normalizeForSdk}, for surfacing
     * to the user as a non-blocking warning. Returns {@code null} when no change
     * was made (input already matched the contract).
     *
     * @param original   the raw user-supplied base URL
     * @param normalized the value returned by {@link #normalizeForSdk}
     * @return a short human-readable note, or {@code null} if unchanged
     */
    public static String describeFix(String original, String normalized) {
        if (original == null || normalized == null) return null;
        String trimmed = stripTrailingSlash(original);
        if (trimmed.equals(normalized)) return null;
        return "Endpoint auto-normalized: '" + trimmed + "' → '" + normalized
                + "'. Update the setting to match to silence this notice.";
    }

    private static String ensureVersionSegment(String base) {
        // The root already ends in a version segment (e.g. /v1) — leave it.
        Matcher m = TRAILING_VERSION.matcher(base);
        if (m.find()) return base;
        return base + "/v1";
    }

    private static String stripVersionSegment(String base) {
        Matcher m = TRAILING_VERSION.matcher(base);
        if (m.find()) return m.replaceFirst("");
        return base;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
