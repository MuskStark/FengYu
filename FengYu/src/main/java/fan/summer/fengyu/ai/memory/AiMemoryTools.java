package fan.summer.fengyu.ai.memory;

import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.ai.tools.ToolEffect;
import fan.summer.fengyu.ai.tools.ToolEffectProvider;
import fan.summer.fengyu.ai.util.JsonHelper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Memory tools for the model — the {@code /remember}/{@code memory_search} surface.
 * Every call is a read/write on the user's own memory store; the feature is
 * experimental and disabled until the user turns it on in Settings, in which case the
 * tools answer with an explicit disabled message instead of failing silently.
 */
@Component
public class AiMemoryTools implements ToolEffectProvider {

    private final AiMemoryService memory;

    public AiMemoryTools(AiMemoryService memory) {
        this.memory = memory;
    }

    @Override
    public ToolEffect effectFor(String toolName) {
        return switch (toolName) {
            case "memory_search", "memory_list" -> ToolEffect.READ;
            case "memory_forget" -> ToolEffect.WRITE;
            default -> ToolEffect.WRITE; // memory_remember writes a durable statement
        };
    }

    /**
     * Store a durable fact worth remembering across sessions.
     *
     * @param content the statement to remember (one clear sentence works best)
     * @param topicsJson optional JSON array of topic tags, e.g. ["excel","invoices"]
     * @return {"id","content","topics"}
     */
    @Tool(name = "memory_remember",
          description = "Store a durable fact that should be remembered across sessions, "
                  + "e.g. user preferences or recurring conventions.")
    public String remember(String content, String topicsJson) {
        try {
            List<String> topics = parseTopics(topicsJson);
            return JsonHelper.toJson(memory.remember(content, topics));
        } catch (IllegalStateException disabled) {
            return "{\"success\":false,\"error\":\"" + disabled.getMessage() + "\"}";
        } catch (Exception error) {
            return "{\"success\":false,\"error\":\"" + safe(error.getMessage()) + "\"}";
        }
    }

    /**
     * Search long-term memories by keyword (ranked by relevance and recency).
     *
     * @param query free-text query
     * @param limit maximum entries (1..10)
     * @return array of {"id","content","topics","score"}
     */
    @Tool(name = "memory_search",
          description = "Search the user's long-term memory by keyword; ranked by relevance "
                  + "and recency. Returns an empty array when nothing matches or memory is off.")
    public String search(String query, Integer limit) {
        try {
            List<Map<String, Object>> found = memory.search(query, limit == null ? 5 : limit);
            return JsonHelper.toJson(found);
        } catch (Exception error) {
            return "[]";
        }
    }

    /**
     * List recent memories regardless of query.
     *
     * @param limit maximum entries (1..50)
     * @return array of memory summaries
     */
    @Tool(name = "memory_list",
          description = "List the user's recent long-term memories, newest first.")
    public String list(Integer limit) {
        try {
            return JsonHelper.toJson(memory.list(limit == null ? 20 : limit));
        } catch (Exception error) {
            return "[]";
        }
    }

    /**
     * Delete one memory entry by id.
     *
     * @param id the memory id from memory_search/memory_list
     * @return {"ok":true|false}
     */
    @Tool(name = "memory_forget",
          description = "Delete one long-term memory entry by id.")
    public String forget(String id) {
        try {
            return "{\"ok\":" + memory.forget(id) + "}";
        } catch (IllegalStateException disabled) {
            return "{\"success\":false,\"error\":\"" + disabled.getMessage() + "\"}";
        } catch (Exception error) {
            return "{\"ok\":false,\"error\":\"" + safe(error.getMessage()) + "\"}";
        }
    }

    private static List<String> parseTopics(String topicsJson) throws Exception {
        if (topicsJson == null || topicsJson.isBlank()) return List.of();
        Object parsed = JsonHelper.parse(topicsJson);
        if (parsed instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static String safe(String message) {
        return message == null ? "unknown error" : message.replace("\"", "'");
    }
}
