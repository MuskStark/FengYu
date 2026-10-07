package fan.summer.fengyu.ai.provider;

import java.util.Map;

/**
 * One provider instance in the {@link ProviderRegistryService} registry — pure data.
 * Adding a vendor is a registry row (any OpenAI/Anthropic-compatible endpoint),
 * never a Java change; the four built-ins are seeded by the legacy-settings migration.
 *
 * @param id            stable registry id; the built-ins are {@code openai} /
 *                      {@code anthropic} / {@code deepseek} / {@code ollama},
 *                      user-created ids are validated slugs that must not collide
 * @param displayName   UI label
 * @param protocol      the wire protocol (dispatch point; see {@link Protocol})
 * @param baseUrl       endpoint root (normalized per protocol at build time)
 * @param model         the active model id on this provider
 * @param apiKeyCipher  the API key inside {@code CryptoUtil}'s machine-bound
 *                      {@code ENC(...)} envelope, or {@code ""} when unset; decrypted
 *                      only inside the registry service — never serialized masked
 * @param headers       extra request headers (nullable)
 * @param builtin       true for the four seeded instances (updatable, not deletable)
 * @param sort          UI ordering hint
 */
public record ProviderDefinition(
        String id,
        String displayName,
        Protocol protocol,
        String baseUrl,
        String model,
        String apiKeyCipher,
        Map<String, String> headers,
        boolean builtin,
        int sort) {

    public ProviderDefinition {
        if (headers == null) headers = Map.of();
        else headers = Map.copyOf(headers);
    }

    /** Copy with the credential replaced (already encrypted by the caller). */
    public ProviderDefinition withApiKeyCipher(String cipher) {
        return new ProviderDefinition(id, displayName, protocol, baseUrl, model,
                cipher, headers, builtin, sort);
    }

    /** True when a usable key is stored (empty or blank cipher means unset). */
    public boolean hasApiKey() {
        return apiKeyCipher != null && !apiKeyCipher.isBlank();
    }
}
