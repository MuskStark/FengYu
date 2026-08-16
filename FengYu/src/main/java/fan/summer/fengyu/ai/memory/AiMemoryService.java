package fan.summer.fengyu.ai.memory;

import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.database.entity.ai.AiMemoryEntity;
import fan.summer.fengyu.database.repository.ai.AiMemoryRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cross-session memory for the AI surfaces (chat and agent runs) — experimental and
 * <b>off by default</b>. The design follows the model proven by terminal agents
 * (grok-build's memory): short durable statements, hybrid retrieval with recency decay,
 * and first-use injection into the planning context.
 *
 * <p>Retrieval scores each entry by keyword overlap over its content and topics (a
 * portable stand-in for the FTS5 half of a hybrid index — FengYu runs on H2, MySQL, or
 * PostgreSQL, none of which share an FTS dialect) multiplied by a recency factor with a
 * 7-day half-life, mirroring the session-memory decay curve. Semantic vector search is
 * a deliberate omission until an embedding backend is guaranteed present.
 */
@Service
public class AiMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AiMemoryService.class);
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final double RECENCY_HALF_LIFE_DAYS = 7.0;
    private static final int MAX_CONTENT_CHARS = 8_000;

    private final AiMemoryRepository memories;
    private final SecurityContext securityContext;

    public AiMemoryService(AiMemoryRepository memories, SecurityContext securityContext) {
        this.memories = memories;
        this.securityContext = securityContext;
    }

    public boolean enabled() {
        // Static bridge keeps reads cheap and unit-testable; default off (experimental).
        return Boolean.parseBoolean(fan.summer.fengyu.ai.service.AiConfigServiceHeadless
                .getSetting("ai.memory.enabled", "false"));
    }

    /** Stores one durable statement; returns the created entry's summary map. */
    @Transactional
    public Map<String, Object> remember(String content, List<String> topics) {
        if (!enabled()) {
            throw new IllegalStateException("Memory is disabled; enable it in Settings (experimental)");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Memory content is required");
        }
        AiMemoryEntity entity = new AiMemoryEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(currentUserId());
        entity.setContent(content.strip().length() > MAX_CONTENT_CHARS
                ? content.strip().substring(0, MAX_CONTENT_CHARS) : content.strip());
        entity.setTopicsJson(writeTopics(topics));
        entity.setSource("manual");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setLastAccessedAt(LocalDateTime.now());
        AiMemoryEntity saved = memories.save(entity);
        log.info("memory stored: {} ({} char(s))", saved.getId(), saved.getContent().length());
        return summary(saved);
    }

    /** Best-effort removal; returns whether an entry was deleted. */
    @Transactional
    public boolean forget(String id) {
        if (!enabled()) {
            throw new IllegalStateException("Memory is disabled; enable it in Settings (experimental)");
        }
        return memories.findByIdAndUserId(id, currentUserId())
                .map(entity -> {
                    memories.delete(entity);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Retrieves up to {@code limit} entries ranked by keyword overlap × recency decay.
     * Matching updates {@code lastAccessedAt} (the reuse signal a future consolidation
     * pass can build on).
     */
    @Transactional
    public List<Map<String, Object>> search(String query, int limit) {
        if (!enabled()) return List.of();
        if (query == null || query.isBlank()) return List.of();
        Set<String> needles = tokenize(query);
        if (needles.isEmpty()) return List.of();

        record Scored(AiMemoryEntity entity, double score) {}
        List<Scored> scored = new ArrayList<>();
        for (AiMemoryEntity entity : memories.findByUserIdOrderByCreatedAtDesc(currentUserId())) {
            Set<String> haystack = tokenize(entity.getContent() + " " + readTopics(entity));
            long hits = needles.stream().filter(haystack::contains).count();
            if (hits == 0) continue;
            double keywordScore = (double) hits / needles.size();
            double ageDays = java.time.Duration.between(
                    entity.getCreatedAt(), LocalDateTime.now()).toHours() / 24.0;
            double recency = Math.pow(0.5, ageDays / RECENCY_HALF_LIFE_DAYS);
            scored.add(new Scored(entity, keywordScore * 0.7 + recency * 0.3));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Scored entry : scored.stream().limit(Math.max(1, limit)).toList()) {
            entry.entity().setLastAccessedAt(LocalDateTime.now());
            memories.save(entry.entity());
            Map<String, Object> summary = summary(entry.entity());
            summary.put("score", Math.round(entry.score() * 1000.0) / 1000.0);
            out.add(summary);
        }
        return out;
    }

    /** The planning-context injection: top memories relevant to a goal, as prompt text. */
    public String injectionFor(String goal, int limit) {
        if (!enabled() || goal == null || goal.isBlank()) return "";
        List<Map<String, Object>> found = search(goal, limit);
        if (found.isEmpty()) return "";
        StringBuilder context = new StringBuilder("Relevant long-term memories:\n");
        for (Map<String, Object> memory : found) {
            context.append("- ").append(memory.get("content")).append('\n');
        }
        context.append("Use these only when they bear on the goal; ignore stale ones.\n");
        return context.toString();
    }

    public List<Map<String, Object>> list(int limit) {
        if (!enabled()) return List.of();
        return memories.findByUserIdOrderByCreatedAtDesc(currentUserId()).stream()
                .limit(Math.max(1, limit))
                .map(AiMemoryService::summary)
                .toList();
    }

    private static Map<String, Object> summary(AiMemoryEntity entity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entity.getId());
        out.put("content", entity.getContent());
        out.put("topics", readTopics(entity));
        out.put("source", entity.getSource());
        out.put("createdAt", entity.getCreatedAt().toString());
        return out;
    }

    private static Set<String> tokenize(String text) {
        Set<String> tokens = new java.util.HashSet<>();
        for (String word : (text == null ? "" : text).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 2) tokens.add(word);
        }
        return tokens;
    }

    private static String writeTopics(List<String> topics) {
        try {
            return JSON.writeValueAsString(topics == null ? List.of() : topics);
        } catch (Exception malformed) {
            return "[]";
        }
    }

    private static List<String> readTopics(AiMemoryEntity entity) {
        try {
            return JSON.readValue(entity.getTopicsJson() == null ? "[]" : entity.getTopicsJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception malformed) {
            return List.of();
        }
    }

    private long currentUserId() {
        Long id = securityContext.currentUserId();
        if (id == null) throw new IllegalStateException("No authenticated user");
        return id;
    }
}
