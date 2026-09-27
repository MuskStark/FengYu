package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.workspace.WorkspaceCheckpointService;
import fan.summer.fengyu.ai.workspace.WorkspaceContext;
import fan.summer.fengyu.ai.workspace.WorkspaceReadState;
import fan.summer.fengyu.ai.workspace.WorkspaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Changes pane over the workspace coding tools' checkpoints
 * ({@code .fengyu/workspace-checkpoints}): the cumulative per-file diff of everything this
 * conversation wrote, plus per-file and whole-conversation rollback.
 *
 * <ul>
 *   <li>{@code GET  /api/ai/conversations/{id}/workspace/changes} — per-file change rows</li>
 *   <li>{@code POST /api/ai/conversations/{id}/workspace/changes/rollback} — one file</li>
 *   <li>{@code POST /api/ai/conversations/{id}/workspace/changes/rollback-all} — everything</li>
 * </ul>
 *
 * @since 4.1.0
 */
@RestController
@RequestMapping("/api/ai/conversations/{id}/workspace/changes")
public class WorkspaceChangesController {

    private final WorkspaceService workspaces;
    private final WorkspaceCheckpointService checkpoints;
    private final WorkspaceReadState readState;

    public WorkspaceChangesController(WorkspaceService workspaces,
            WorkspaceCheckpointService checkpoints, WorkspaceReadState readState) {
        this.workspaces = workspaces;
        this.checkpoints = checkpoints;
        this.readState = readState;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> changes(@PathVariable Long id) {
        WorkspaceContext.Binding binding = workspaces.bindingFor(id);
        if (binding == null) return ResponseEntity.notFound().build();
        List<Map<String, Object>> rows = checkpoints.changes(id, binding.root()).stream()
                .map(change -> Map.<String, Object>of(
                        "path", change.path(),
                        "created", change.created(),
                        "reverted", change.reverted(),
                        "gone", change.gone(),
                        "snapshots", change.snapshots(),
                        "firstTouchedAt", change.firstTouchedAt().toString(),
                        "diff", change.diff()))
                .toList();
        return ResponseEntity.ok(rows);
    }

    public record RollbackRequest(String path) {}

    @PostMapping("/rollback")
    public ResponseEntity<Map<String, Object>> rollback(@PathVariable Long id,
            @RequestBody RollbackRequest request) {
        WorkspaceContext.Binding binding = workspaces.bindingFor(id);
        if (binding == null) return ResponseEntity.notFound().build();
        if (request.path() == null || request.path().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "path is required"));
        }
        String result = checkpoints.rollbackFile(id, binding.root(), request.path().trim());
        // The restored content invalidates the model's read-state view of the file — resolve
        // through the same jail so a/../b invalidates the key the rollback actually restored.
        try {
            readState.invalidate(id,
                    fan.summer.fengyu.ai.workspace.WorkspacePathPolicy.resolve(
                            binding.root(), request.path().trim()));
        } catch (RuntimeException ignored) {
            // an unresolvable path cannot have a read-state entry either
        }
        return ResponseEntity.ok(Map.of("result", result));
    }

    @PostMapping("/rollback-all")
    public ResponseEntity<Map<String, Object>> rollbackAll(@PathVariable Long id) {
        WorkspaceContext.Binding binding = workspaces.bindingFor(id);
        if (binding == null) return ResponseEntity.notFound().build();
        String result = checkpoints.rollbackAll(id, binding.root());
        readState.clearConversation(id);
        return ResponseEntity.ok(Map.of("result", result));
    }
}
