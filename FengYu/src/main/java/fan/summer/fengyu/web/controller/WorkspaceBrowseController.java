package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.workspace.WorkspaceContext;
import fan.summer.fengyu.ai.workspace.WorkspacePathPolicy;
import fan.summer.fengyu.ai.workspace.WorkspaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Read-only browsing surface for a conversation's coding workspace — the file tree and single
 * text-file reads behind the chat view's workspace side panel ("which files did the AI touch").
 *
 * <p>Endpoints (both scoped to the conversation's stored, user-owned root via
 * {@link WorkspaceService#bindingFor}; no workspace attached → 404):
 * <ul>
 *   <li>{@code GET /api/ai/conversations/{id}/workspace/tree} — flat node list
 *       (workspace-relative path, directory flag, file size), traversal-excluded dirs skipped,
 *       bounded by depth and entry count</li>
 *   <li>{@code GET /api/ai/conversations/{id}/workspace/file?path=…} — one text file's content
 *       (same caps and binary sniff as the {@code read_file} model tool)</li>
 * </ul>
 *
 * <p>Deliberately read-only and separate from the model-facing {@code WorkspaceFileTools}: the UI
 * channel can never mint writes, and every path still goes through the {@link WorkspacePathPolicy}
 * jail (relative-to-root resolution, symlink collapsing, containment check).
 *
 * @since 4.1.0
 */
@RestController
@RequestMapping("/api/ai/conversations")
public class WorkspaceBrowseController {

    /** Node ceiling for one tree response; past it the walk stops and {@code truncated} flips. */
    static final int MAX_TREE_ENTRIES = 4000;
    static final int MAX_TREE_DEPTH = 16;
    /** Mirrors {@code WorkspaceFileTools.MAX_FILE_BYTES}: the readable ceiling for one preview. */
    static final long MAX_PREVIEW_BYTES = 2 * 1024 * 1024;
    /** Mirrors {@code WorkspaceFileTools.EXCLUDED_DIRS} so the panel sees the same tree as the model. */
    private static final Pattern EXCLUDED_DIRS = Pattern.compile(
            "\\.(git|idea|vscode|venv|gradle|fengyu)|^(node_modules|target|build|dist|out|__pycache__|venv)$");

    private final WorkspaceService workspaces;

    public WorkspaceBrowseController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @GetMapping("/{id}/workspace/tree")
    public ResponseEntity<Map<String, Object>> tree(@PathVariable Long id) {
        WorkspaceContext.Binding binding = workspaces.bindingFor(id);
        if (binding == null) return ResponseEntity.notFound().build();

        List<Map<String, Object>> nodes = new ArrayList<>();
        boolean truncated = walk(binding.root(), nodes);
        nodes.sort(Comparator.comparing(n -> String.valueOf(n.get("path"))));

        Map<String, Object> out = new HashMap<>();
        out.put("workspaceRoot", binding.root().toString());
        out.put("truncated", truncated);
        out.put("nodes", nodes);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{id}/workspace/file")
    public ResponseEntity<Map<String, Object>> file(@PathVariable Long id, @RequestParam String path) {
        WorkspaceContext.Binding binding = workspaces.bindingFor(id);
        if (binding == null) return ResponseEntity.notFound().build();

        Path file;
        try {
            file = WorkspacePathPolicy.resolve(binding.root(), path);
        } catch (WorkspacePathPolicy.EscapeException e) {
            // Same wire contract as the other workspace endpoints: bad input is a 400, not a 500.
            throw new IllegalArgumentException(e.getMessage());
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Not a regular file: " + path);
        }

        Map<String, Object> out = new HashMap<>();
        out.put("path", WorkspacePathPolicy.display(binding.root(), file));
        try {
            long size = Files.size(file);
            out.put("size", size);
            if (size > MAX_PREVIEW_BYTES) {
                out.put("tooLarge", true);
                return ResponseEntity.ok(out);
            }
            String content = fan.summer.fengyu.ai.tools.EditMatchers
                    .normalizeLineEndings(Files.readString(file, StandardCharsets.UTF_8));
            if (looksBinary(content)) {
                out.put("binary", true);
                return ResponseEntity.ok(out);
            }
            out.put("content", content);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read workspace file: " + e.getMessage(), e);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Depth-and-count-bounded walk collecting flat node records; returns true when the entry
     * ceiling stopped it early. Symlinked directories are skipped for the same reason the model
     * tools skip them — a link can loop or leave the tree.
     */
    private static boolean walk(Path root, List<Map<String, Object>> nodes) {
        boolean[] truncated = {false};
        try {
            Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    MAX_TREE_DEPTH, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (truncated[0]) return FileVisitResult.TERMINATE;
                            if (dir.equals(root)) return FileVisitResult.CONTINUE;
                            if (attrs.isSymbolicLink()) return FileVisitResult.SKIP_SUBTREE;
                            String name = String.valueOf(dir.getFileName());
                            if (EXCLUDED_DIRS.matcher(name).matches()) return FileVisitResult.SKIP_SUBTREE;
                            if (nodes.size() >= MAX_TREE_ENTRIES) {
                                truncated[0] = true;
                                return FileVisitResult.TERMINATE;
                            }
                            nodes.add(node(root, dir, true, attrs.size()));
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (truncated[0]) return FileVisitResult.TERMINATE;
                            if (attrs.isSymbolicLink()) return FileVisitResult.CONTINUE;
                            if (nodes.size() >= MAX_TREE_ENTRIES) {
                                truncated[0] = true;
                                return FileVisitResult.TERMINATE;
                            }
                            nodes.add(node(root, file, false, attrs.size()));
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot walk workspace: " + e.getMessage(), e);
        }
        return truncated[0];
    }

    private static Map<String, Object> node(Path root, Path item, boolean dir, long size) {
        Map<String, Object> n = new HashMap<>();
        n.put("path", WorkspacePathPolicy.display(root, item));
        n.put("name", String.valueOf(item.getFileName()));
        n.put("dir", dir);
        if (!dir) n.put("size", size);
        return n;
    }

    /** Mirrors {@code WorkspaceFileTools.looksBinary}: a NUL in the sniff window means binary. */
    private static boolean looksBinary(String content) {
        int sniff = Math.min(content.length(), 8192);
        for (int index = 0; index < sniff; index++) {
            if (content.charAt(index) == '\0') return true;
        }
        return false;
    }
}
