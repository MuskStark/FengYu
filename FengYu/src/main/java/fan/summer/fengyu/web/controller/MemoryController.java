package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.memory.AiMemoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Settings-facing read/manage surface over the (experimental) AI memory store — the
 * memory viewer lists and deletes remembered facts without going through model tools.
 *
 * @since 4.1.0
 */
@RestController
@RequestMapping("/api/ai/memory")
public class MemoryController {

    private final AiMemoryService memory;

    public MemoryController(AiMemoryService memory) {
        this.memory = memory;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) String query,
            @RequestParam(required = false, defaultValue = "200") int limit) {
        if (!memory.enabled()) {
            return ResponseEntity.ok(List.of());
        }
        int bounded = Math.max(1, Math.min(limit, 200));
        List<Map<String, Object>> rows = query == null || query.isBlank()
                ? memory.list(bounded)
                : memory.search(query, bounded);
        return ResponseEntity.ok(rows);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> forget(@PathVariable String id) {
        boolean removed = memory.enabled() && memory.forget(id);
        return removed
                ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.ok(Map.of("ok", false, "error", "Unknown memory entry"));
    }
}
