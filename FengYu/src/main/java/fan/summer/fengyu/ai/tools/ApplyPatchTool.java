package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.ai.workspace.WorkspaceCheckpointService;
import fan.summer.fengyu.ai.workspace.WorkspaceContext;
import fan.summer.fengyu.ai.workspace.WorkspacePathPolicy;
import fan.summer.fengyu.ai.workspace.WorkspaceReadState;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code apply_patch} — the multi-file patch tool over the attached workspace, following the
 * terminal coding-agent patch grammar (see {@link ApplyPatchParser}). One call can add,
 * update (diff-context hunks with optional {@code @@} named anchors), delete, and move
 * files; every path is jailed to the workspace root, every write is checkpointed for the
 * Changes pane, and updates honor the same read-before-edit freshness contract as
 * {@code edit_file}.
 *
 * <p>Application semantics mirror upstream: chunks are located sequentially (each chunk
 * searched after the previous one, after its anchor when given), matching degrades from
 * exact lines through end-trimmed to fully-trimmed lines, a failed chunk after successful
 * ones KEEPS the already-applied changes and reports both — so the model can retry only
 * the failed part instead of re-planning the whole edit.</p>
 */
@Component
public class ApplyPatchTool implements FengYuTool, ToolEffectProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WorkspaceReadState readState;
    private final WorkspaceCheckpointService checkpoints;

    public ApplyPatchTool(WorkspaceReadState readState) {
        this(readState, null);
    }

    /** Production constructor — Spring must prefer it when both are present. */
    @Autowired
    public ApplyPatchTool(WorkspaceReadState readState, WorkspaceCheckpointService checkpoints) {
        this.readState = readState;
        this.checkpoints = checkpoints;
    }

    @Override
    public ToolEffect effectFor(String toolName) {
        return "apply_patch".equals(toolName) ? ToolEffect.WRITE : null;
    }

    // ── apply_patch ──────────────────────────────────────────────────────────────────────

    @Tool(name = "apply_patch",
          description = "Apply a multi-file patch inside the workspace in ONE call — the "
                  + "preferred tool whenever an edit touches more than one place. Format:\n"
                  + "*** Begin Patch\n"
                  + "*** Update File: path/to/file.py\n"
                  + "@@ def example():   (optional unique anchor line that precedes the change)\n"
                  + " context line (leading space)\n-removed line\n+added line\n"
                  + "*** Add File: path/new.py\n+entire\n+file content\n"
                  + "*** Delete File: path/old.py\n"
                  + "*** Update File: path/a.py\n*** Move to: path/renamed.py\n…change lines…\n"
                  + "*** End Patch\n"
                  + "Update hunks must have been read first (context lines come from read_file "
                  + "output). A failed hunk is reported with the expected lines; successful "
                  + "hunks before it stay applied. Do not re-read files after success — the "
                  + "result reports what changed.")
    public String applyPatch(
            @ToolParam(description = "The full patch text, *** Begin Patch … *** End Patch.")
            String patch) {
        try {
            WorkspaceContext.Binding binding = WorkspaceContext.current();
            if (binding == null) {
                return error("This conversation has no workspace attached");
            }
            ApplyPatchParser.Patch parsed;
            try {
                parsed = ApplyPatchParser.parse(patch);
            } catch (ApplyPatchParser.ParseError e) {
                return error("apply_patch parse failed: " + e.getMessage());
            }

            // Verification pass before anything is applied: a patch whose hunks collide on
            // one path is rejected whole, never applied half of it (upstream semantics).
            java.util.Set<Path> targets = new java.util.HashSet<>();
            for (ApplyPatchParser.Hunk hunk : parsed.hunks()) {
                Path path = WorkspacePathPolicy.resolve(binding.root(), hunkPath(hunk));
                if (!targets.add(path)) {
                    return error("apply_patch verification failed: multiple operations target "
                            + hunkPath(hunk) + "; merge them into one hunk");
                }
            }

            List<String> applied = new ArrayList<>();
            List<Map<String, Object>> failures = new ArrayList<>();
            for (ApplyPatchParser.Hunk hunk : parsed.hunks()) {
                try {
                    applyHunk(binding, hunk, applied);
                } catch (RuntimeException | IOException e) {
                    failures.add(failure(hunkPath(hunk), e.getMessage()));
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", failures.isEmpty());
            result.put("files", applied);
            if (!applied.isEmpty()) {
                result.put("summary", "Updated the following files: " + String.join(", ", applied));
            }
            if (!failures.isEmpty()) {
                result.put("failures", failures);
                result.put("error", failures.size() + " of " + parsed.hunks().size()
                        + " hunk(s) failed; successful hunks before them were kept applied — "
                        + "re-send only the failed hunks after re-reading the file if needed");
            }
            return toJson(result);
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    private static String hunkPath(ApplyPatchParser.Hunk hunk) {
        if (hunk instanceof ApplyPatchParser.AddFile add) return add.path();
        if (hunk instanceof ApplyPatchParser.DeleteFile delete) return delete.path();
        return ((ApplyPatchParser.UpdateFile) hunk).path();
    }

    private void applyHunk(WorkspaceContext.Binding binding, ApplyPatchParser.Hunk hunk,
            List<String> applied) throws IOException {
        if (hunk instanceof ApplyPatchParser.AddFile add) {
            applyAdd(binding, add, applied);
        } else if (hunk instanceof ApplyPatchParser.DeleteFile delete) {
            applyDelete(binding, delete, applied);
        } else {
            applyUpdate(binding, (ApplyPatchParser.UpdateFile) hunk, applied);
        }
    }

    // ── Add / Delete ─────────────────────────────────────────────────────────────────────

    private void applyAdd(WorkspaceContext.Binding binding, ApplyPatchParser.AddFile add,
            List<String> applied) throws IOException {
        Path file = WorkspacePathPolicy.resolve(binding.root(), add.path());
        boolean existed = Files.exists(file);
        if (existed) {
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException("Not a regular file: " + add.path());
            }
            requireFreshRead(binding, file, "apply_patch (Add File over existing)");
        }
        Files.createDirectories(file.getParent());
        String content = String.join("\n", add.lines()) + "\n";
        snapshotBefore(binding, file);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        readState.recordRead(binding.conversationId(), file, mtimeOf(file));
        applied.add((existed ? "R " : "A ") + add.path());
    }

    private void applyDelete(WorkspaceContext.Binding binding, ApplyPatchParser.DeleteFile delete,
            List<String> applied) throws IOException {
        Path file = WorkspacePathPolicy.resolve(binding.root(), delete.path());
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Not a regular file: " + delete.path());
        }
        requireFreshRead(binding, file, "apply_patch (Delete File)");
        snapshotBefore(binding, file);
        Files.delete(file);
        applied.add("D " + delete.path());
    }

    // ── Update (the interesting one) ─────────────────────────────────────────────────────

    private void applyUpdate(WorkspaceContext.Binding binding, ApplyPatchParser.UpdateFile update,
            List<String> applied) throws IOException {
        Path file = WorkspacePathPolicy.resolve(binding.root(), update.path());
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Not a regular file: " + update.path());
        }
        requireFreshRead(binding, file, "apply_patch");
        // The move target is validated (and freshness-checked) BEFORE any chunk is applied so a
        // bad rename cannot leave a half-moved edit behind.
        Path target = null;
        if (update.moveTo() != null) {
            target = WorkspacePathPolicy.resolve(binding.root(), update.moveTo());
            if (Files.exists(target) && !Files.isRegularFile(target)) {
                throw new IllegalStateException("Move target is not a regular file: "
                        + update.moveTo());
            }
            if (Files.exists(target)) {
                requireFreshRead(binding, target, "apply_patch (Move over existing)");
            }
        }

        String original = Files.readString(file, StandardCharsets.UTF_8);
        String normalized = EditMatchers.normalizeLineEndings(original);
        // JS-style split: the final empty segment of "a\nb\n" is the trailing newline, not a line.
        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        boolean trailingNewline = !lines.isEmpty() && lines.get(lines.size() - 1).isEmpty();
        if (trailingNewline) lines.remove(lines.size() - 1);

        int cursor = 0;
        int appliedChunks = 0;
        for (ApplyPatchParser.Chunk chunk : update.chunks()) {
            if (chunk.anchor() != null && !chunk.anchor().isBlank()) {
                Integer anchorIndex = findLine(lines, chunk.anchor(), cursor);
                if (anchorIndex == null) {
                    throw new IllegalStateException("Failed to find context '"
                            + chunk.anchor() + "' in " + update.path());
                }
                cursor = anchorIndex + 1;
            }
            int start = findBlock(lines, chunk.oldLines(), cursor, chunk.endOfFile());
            if (start < 0) {
                if (appliedChunks > 0) {
                    writeLines(binding, file, lines, trailingNewline);
                }
                throw new IllegalStateException("Failed to find expected lines in "
                        + update.path() + ":\n" + String.join("\n", chunk.oldLines()));
            }
            lines.subList(start, start + chunk.oldLines().size()).clear();
            lines.addAll(start, chunk.newLines());
            cursor = start + chunk.newLines().size();
            appliedChunks++;
        }

        writeLines(binding, file, lines, trailingNewline);

        if (target != null) {
            snapshotBefore(binding, target);
            Files.createDirectories(target.getParent());
            Files.move(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            readState.recordRead(binding.conversationId(), target, mtimeOf(target));
            applied.add("M " + update.path() + " → " + update.moveTo());
            return;
        }
        applied.add("M " + update.path());
    }

    private void writeLines(WorkspaceContext.Binding binding, Path file, List<String> lines,
            boolean trailingNewline) throws IOException {
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) content.append('\n');
            content.append(lines.get(index));
        }
        if (trailingNewline && !lines.isEmpty()) content.append('\n');
        snapshotBefore(binding, file);
        Files.writeString(file, content.toString(), StandardCharsets.UTF_8);
        readState.recordRead(binding.conversationId(), file, mtimeOf(file));
    }

    /**
     * Sequential block search with the same degradation ladder as upstream patch appliers:
     * exact lines, then end-trimmed (trailing whitespace drift), then fully trimmed
     * (indentation drift) — strictest pass wins wherever it matches. First match at or after
     * {@code from} wins; chunk order plus the optional anchor, not global uniqueness,
     * disambiguate repeated blocks. An empty expected block matches as an insertion point.
     */
    private static int findBlock(List<String> lines, List<String> expected, int from,
            boolean endOfFile) {
        int lastStart = lines.size() - expected.size();
        if (lastStart < 0) return -1;
        if (endOfFile) {
            // EOF-anchored chunks try the tail position first, per upstream semantics.
            for (int pass = 0; pass < 3; pass++) {
                if (blockMatches(lines, expected, lastStart, pass)) return lastStart;
            }
        }
        int begin = Math.max(0, from);
        for (int pass = 0; pass < 3; pass++) {
            for (int start = begin; start <= lastStart; start++) {
                if (blockMatches(lines, expected, start, pass)) return start;
            }
        }
        return -1;
    }

    private static boolean blockMatches(List<String> lines, List<String> expected, int start,
            int pass) {
        for (int offset = 0; offset < expected.size(); offset++) {
            String actual = lines.get(start + offset);
            String wanted = expected.get(offset);
            switch (pass) {
                case 0 -> { if (!actual.equals(wanted)) return false; }
                case 1 -> { if (!actual.stripTrailing().equals(wanted.stripTrailing())) return false; }
                default -> { if (!actual.trim().equals(wanted.trim())) return false; }
            }
        }
        return true;
    }

    private static Integer findLine(List<String> lines, String wanted, int from) {
        for (int index = Math.max(0, from); index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.equals(wanted) || line.trim().equals(wanted.trim())) return index;
        }
        return null;
    }

    // ── shared plumbing (mirrors WorkspaceFileTools' contract) ────────────────────────────

    private void requireFreshRead(WorkspaceContext.Binding binding, Path file, String tool) {
        WorkspaceReadState.ReadEntry entry = readState.readEntry(binding.conversationId(), file);
        if (entry == null) {
            throw new IllegalStateException("Read the file with read_file before using " + tool
                    + " on it (a fresh copy of the exact current content is required)");
        }
        FileTime current = mtimeOf(file);
        if (entry.mtime() != null && current != null && !entry.mtime().equals(current)) {
            throw new IllegalStateException("The file changed on disk after it was read; read it "
                    + "again before editing (stale-view protection)");
        }
    }

    private void snapshotBefore(WorkspaceContext.Binding binding, Path file) {
        if (checkpoints != null) {
            checkpoints.snapshotBefore(binding.conversationId(), binding.root(), file);
        }
    }

    private static FileTime mtimeOf(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            return null;
        }
    }

    private static Map<String, Object> failure(String path, String message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("path", path);
        entry.put("error", message);
        return entry;
    }

    private static String error(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message == null ? "apply_patch failed" : message);
        return toJson(result);
    }

    private static String toJson(Map<String, Object> result) {
        try {
            return JSON.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"tool result serialization failed\"}";
        }
    }
}
