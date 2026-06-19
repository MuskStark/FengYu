package fan.summer.ai.tools;

import fan.summer.api.ai.AiChatMessage;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Offline CN→EN keyword normalizer for the English-trained FunctionGemma model.
 *
 * <p>Loads a local {@code /ai/nl-normalizer.properties} dictionary at class-load time
 * and replaces known Chinese action/domain keywords with their English equivalents
 * via plain substring replacement. File paths, sheet names and column names are
 * identifiers and are intentionally not in the dictionary, so they pass through.
 *
 * <p>This is a <b>best-effort heuristic</b>: coverage is limited to the dictionary,
 * and substring replacement can occasionally over-match inside larger words. It is
 * fully offline and adds no second model, matching the lightweight 270M deployment.
 */
public final class OfflineNlNormalizer {

    private static final PluginLogger log = LoggerFactory.getLogger(OfflineNlNormalizer.class);
    private static final Map<String, String> DICT = load();

    private OfflineNlNormalizer() {}

    private static Map<String, String> load() {
        Map<String, String> m = new LinkedHashMap<>();
        Properties p = new Properties();
        try (InputStream in = OfflineNlNormalizer.class.getResourceAsStream("/ai/nl-normalizer.properties")) {
            if (in != null) {
                try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    p.load(reader);
                }
                for (String k : p.stringPropertyNames()) {
                    m.put(k, p.getProperty(k));
                }
            } else {
                log.warn("nl-normalizer.properties not found on classpath; normalizer is a no-op");
            }
        } catch (Exception e) {
            log.warn("Failed to load nl-normalizer.properties: {}", e.getMessage());
        }
        return m;
    }

    /** Replace known Chinese keywords with their English equivalents; leave the rest verbatim. */
    public static String normalize(String text) {
        if (text == null || text.isBlank()) return text;
        String out = text;
        for (Map.Entry<String, String> e : DICT.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    /** Normalize the most recent USER message in-place (other turns untouched). */
    public static void normalizeLatestUser(List<AiChatMessage> history) {
        if (history == null || history.isEmpty()) return;
        for (int i = history.size() - 1; i >= 0; i--) {
            AiChatMessage m = history.get(i);
            if (m.role() == AiChatMessage.Role.USER) {
                String normalized = normalize(m.content());
                if (!normalized.equals(m.content())) {
                    history.set(i, AiChatMessage.user(normalized));
                }
                return;
            }
        }
    }
}
