package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.workspace.WorkspaceCheckpointService;
import fan.summer.fengyu.ai.workspace.WorkspaceContext;
import fan.summer.fengyu.ai.workspace.WorkspaceReadState;
import fan.summer.fengyu.ai.workspace.WorkspaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Git branch surface for a conversation's coding workspace — the listing behind the branch
 * picker and the {@code git switch} behind "switch branch" / "create and switch" (4.1.0,
 * upstream agent design port).
 *
 * <p>Switch refusals are data, not errors: git-level blockers (dirty-tree overwrite, branch
 * held by another worktree, in-flight rebase…) come back HTTP 200 with {@code ok:false} and
 * classified {@code issues} the UI maps to i18n strings — the wire contract mirrors the
 * upstream agent's branch-mutation result. Only bad input and environment failures (no git
 * binary, timeout) throw (→ 400 via GlobalExceptionHandler).
 *
 * <p>Scoped to the conversation's stored, user-owned root via {@link WorkspaceService#bindingFor};
 * the branch name can only ever be one that {@code listBranches} read off the repository's own
 * refs, so option injection and path arguments are structurally impossible, not just filtered.
 *
 * <p>A successful switch also drops the conversation's branch-scoped ledgers (the AI-edit read
 * state and the checkpoint/rollback history): a "changes" pane spanning a branch boundary is
 * fiction, and the workspace panel reloads against the new checkout.
 *
 * @since 4.1.0
 */
@RestController
@RequestMapping("/api/ai/conversations")
public class WorkspaceGitController {

    private final WorkspaceService workspaces;
    private final WorkspaceReadState readState;
    /** Nullable for legacy unit-test constructions, mirroring ConversationController. */
    private final WorkspaceCheckpointService checkpoints;

    public WorkspaceGitController(WorkspaceService workspaces, WorkspaceReadState readState,
            WorkspaceCheckpointService checkpoints) {
        this.workspaces = workspaces;
        this.readState = readState;
        this.checkpoints = checkpoints;
    }

    public record SwitchDto(String branch, Boolean create) {}

    /** Local branches with the checked-out one flagged; {@code current} is the chip's label
     *  (branch name, or the detached short sha — {@code headRefType} says which). */
    @GetMapping("/{id}/workspace/branches")
    public ResponseEntity<Map<String, Object>> branches(@PathVariable Long id) {
        WorkspaceContext.Binding binding = workspaces.bindingFor(id);
        if (binding == null) return ResponseEntity.notFound().build();
        List<WorkspaceService.BranchInfo> branches = WorkspaceService.listBranches(binding.root());
        if (branches == null) {
            // IllegalArgumentException → 400 via GlobalExceptionHandler, like every other
            // "this workspace cannot do that" case.
            throw new IllegalArgumentException("Workspace is not a git repository");
        }
        Map<String, Object> out = new HashMap<>();
        out.put("current", WorkspaceService.branchLabel(binding.root()));
        out.put("headRefType", WorkspaceService.headRefType(binding.root()));
        out.put("branches", branches);
        return ResponseEntity.ok(out);
    }

    /** Switch the workspace to another local branch (or create one off HEAD and switch). */
    @PutMapping("/{id}/workspace/checkout")
    public ResponseEntity<WorkspaceService.BranchMutation> checkout(
            @PathVariable Long id, @RequestBody SwitchDto body) {
        if (body == null || body.branch() == null || body.branch().isBlank()) {
            throw new IllegalArgumentException("Branch is required");
        }
        WorkspaceContext.Binding binding = workspaces.bindingFor(id);
        if (binding == null) return ResponseEntity.notFound().build();

        WorkspaceService.BranchMutation result = WorkspaceService.switchBranch(
                binding.root(), body.branch(), Boolean.TRUE.equals(body.create()));
        if (result.ok() && result.didChange()) {
            readState.clearConversation(id);
            if (checkpoints != null) checkpoints.clearConversation(id);
        }
        return ResponseEntity.ok(result);
    }
}
