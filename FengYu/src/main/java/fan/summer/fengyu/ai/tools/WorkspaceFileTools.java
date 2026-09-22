package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.ai.workspace.UnifiedDiff;
import fan.summer.fengyu.ai.workspace.WorkspaceContext;
import fan.summer.fengyu.ai.workspace.WorkspacePathPolicy;
import fan.summer.fengyu.ai.workspace.WorkspaceReadState;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Host-side coding tools over the conversation's attached workspace root: {@code read_file},
 * {@code write_file}, {@code edit_file}, {@code grep}, {@code glob}. Visible to the model only
 * while {@link WorkspaceContext} is bound; every path is jailed to the root, and every write
 * honors the read-before-edit freshness contract through {@link WorkspaceReadState}.
 *
 * <p>Tool-surface design follows terminal coding-agent practice (ZCode / Claude Code):
 * line-numbered reads that pair with {@code edit_file}'s prefix-stripping matcher, an exact
 * string-replacement edit with a progressive match waterfall
 * ({@link EditMatchers}), and capped, directory-excluding search primitives.
 */
@Component
public class WorkspaceFileTools implements FengYuTool, ToolEffectProvider {

    static final int MAX_READ_LINES = 2000;
    static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    static final int GREP_DEFAULT_LIMIT = 200;
    static final int GREP_MAX_LIMIT = 500;
    static final int GREP_MAX_OUTPUT_CHARS = 64 * 1024;
    static final int GLOB_DEFAULT_LIMIT = 500;
    static final int GLOB_MAX_LIMIT = 2000;
    static final int MAX_WALK_ENTRIES = 20_000;
    static final int BINARY_SNIFF_BYTES = 8192;

    /** Never searched or listed — build output, VCS internals, dependency trees. */
    private static final Pattern EXCLUDED_DIRS = Pattern.compile(
            "\\.(git|idea|vscode|venv|gradle|fengyu)|^(node_modules|target|build|dist|out|__pycache__|venv)$");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WorkspaceReadState readState;

    public WorkspaceFileTools(WorkspaceReadState readState) {
        this.readState = readState;
    }

    @Override
    public ToolEffect effectFor(String toolName) {
        return switch (toolName) {
            case "read_file", "grep", "glob" -> ToolEffect.READ;
            case "write_file", "edit_file" -> ToolEffect.WRITE;
            default -> null;
        };
    }

    // ── read_file ────────────────────────────────────────────────────────────────────────

    @Tool(name = "read_file",
          description = "Read a text file from the workspace, returned as 1-based line-numbered "
                  + "text (up to 2000 lines per call; use offset/limit for larger files). Paths "
                  + "are workspace-relative. Reading a file is required before editing it.")
    public String readFile(
            @ToolParam(description = "Workspace-relative (or absolute-inside-workspace) file path.") String path,
            @ToolParam(required = false, description = "First line to return, 1-based (default 1).")
            Integer offset,
            @ToolParam(required = false, description = "Maximum number of lines to return (default/max 2000).")
            Integer limit) {
        try {
            WorkspaceContext.Binding binding = requireBinding();
            Path file = WorkspacePathPolicy.resolve(binding.root(), path);
            if (!Files.isRegularFile(file)) return error("Not a regular file: " + path);
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                return error("File is " + size + " bytes; the readable maximum is " + MAX_FILE_BYTES);
            }
            String content = EditMatchers.normalizeLineEndings(Files.readString(file, StandardCharsets.UTF_8));
            if (looksBinary(content)) return error("File appears to be binary and cannot be read as text");
            String[] lines = content.split("\n", -1);
            int totalLines = lines.length > 0 && lines[lines.length - 1].isEmpty()
                    ? lines.length - 1 : lines.length;
            int from = Math.max(1, offset == null ? 1 : offset);
            int count = Math.min(offset == null || limit == null ? MAX_READ_LINES : Math.max(1, limit),
                    MAX_READ_LINES);
            int to = Math.min(totalLines == 0 ? 0 : totalLines, from + count - 1);

            StringBuilder body = new StringBuilder();
            for (int line = from; line <= to; line++) {
                body.append(String.format("%6d\t%s\n", line, lines[line - 1]));
            }
            readState.recordRead(binding.conversationId(), file, mtimeOf(file));

            Map<String, Object> result = okBase();
            result.put("path", WorkspacePathPolicy.display(binding.root(), file));
            result.put("totalLines", totalLines);
            result.put("offset", from);
            result.put("lines", Math.max(0, to - from + 1));
            result.put("truncated", to < totalLines);
            result.put("content", body.toString());
            return toJson(result);
        } catch (RuntimeException | IOException e) {
            return error(e.getMessage());
        }
    }

    // ── write_file ───────────────────────────────────────────────────────────────────────

    @Tool(name = "write_file",
          description = "Create or replace a text file in the workspace with the exact content. "
                  + "Overwriting an existing file requires reading it first (and it must be "
                  + "unchanged since). Returns a unified diff of the change.")
    public String writeFile(
            @ToolParam(description = "Workspace-relative file path.") String path,
            @ToolParam(description = "Full file content to write (UTF-8 text).") String content,
            @ToolParam(required = false,
                       description = "Create missing parent directories (default true).")
            Boolean createDirectories) {
        try {
            WorkspaceContext.Binding binding = requireBinding();
            Path file = WorkspacePathPolicy.resolve(binding.root(), path);
            String text = content == null ? "" : content;
            boolean existed = Files.exists(file);
            String before = "";
            if (existed) {
                if (!Files.isRegularFile(file)) return error("Not a regular file: " + path);
                requireFreshRead(binding, file, "write_file");
                before = EditMatchers.normalizeLineEndings(
                        Files.readString(file, StandardCharsets.UTF_8));
            }
            if (Boolean.TRUE.equals(createDirectories) || createDirectories == null) {
                Files.createDirectories(file.getParent());
            } else if (file.getParent() != null && !Files.isDirectory(file.getParent())) {
                return error("Parent directory does not exist: " + file.getParent());
            }
            Files.writeString(file, text, StandardCharsets.UTF_8);
            readState.recordRead(binding.conversationId(), file, mtimeOf(file));

            Map<String, Object> result = okBase();
            result.put("path", WorkspacePathPolicy.display(binding.root(), file));
            result.put("created", !existed);
            result.put("bytes", text.getBytes(StandardCharsets.UTF_8).length);
            String diff = existed ? UnifiedDiff.diff(
                    WorkspacePathPolicy.display(binding.root(), file), before, text) : "";
            if (!diff.isEmpty()) result.put("diff", diff);
            return toJson(result);
        } catch (RuntimeException | IOException e) {
            return error(e.getMessage());
        }
    }

    // ── edit_file ────────────────────────────────────────────────────────────────────────

    @Tool(name = "edit_file",
          description = "Replace one occurrence of old_string in a workspace file with "
                  + "new_string (all occurrences when replace_all). The file must have been read "
                  + "first and must be unchanged since. Matching falls back progressively through "
                  + "quote normalization, read line-number prefix stripping, escape normalization, "
                  + "whitespace-relaxed block matching; distinct multiple matches are rejected.")
    public String editFile(
            @ToolParam(description = "Workspace-relative file path.") String path,
            @ToolParam(description = "Exact text to find (copy it from read_file output).") String oldString,
            @ToolParam(description = "Replacement text.") String newString,
            @ToolParam(required = false, description = "Replace every occurrence (default false).")
            Boolean replaceAll) {
        if (oldString == null || oldString.isEmpty()) {
            return error("old_string must not be empty");
        }
        try {
            WorkspaceContext.Binding binding = requireBinding();
            Path file = WorkspacePathPolicy.resolve(binding.root(), path);
            if (!Files.isRegularFile(file)) return error("Not a regular file: " + path);
            requireFreshRead(binding, file, "edit_file");

            String original = Files.readString(file, StandardCharsets.UTF_8);
            String content = EditMatchers.normalizeLineEndings(original);
            boolean all = Boolean.TRUE.equals(replaceAll);

            EditMatchers.MatchResult match = EditMatchers.findEditMatch(content, oldString, all);
            if (match instanceof EditMatchers.NotFound) {
                return error("old_string was not found in " + path
                        + ". Re-read the file and copy the text exactly from the read output.");
            }
            if (match instanceof EditMatchers.Ambiguous ambiguous) {
                return error("old_string matched " + ambiguous.candidateCount()
                        + " distinct places via the " + ambiguous.strategy()
                        + " strategy. Include surrounding lines to make it unique.");
            }
            EditMatchers.Matched matched = (EditMatchers.Matched) match;

            String replacement = EditMatchers.normalizeReplacementForMatch(matched.strategy(), newString == null ? "" : newString);
            replacement = EditMatchers.preserveQuoteStyle(oldString, matched.actualString(), replacement);
            String updated;
            if (all) {
                updated = content.replace(matched.actualString(), replacement);
            } else {
                int index = content.indexOf(matched.actualString());
                updated = content.substring(0, index) + replacement
                        + content.substring(index + matched.actualString().length());
            }
            if (updated.equals(content)) {
                return error("The replacement is identical to the original text");
            }
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            readState.recordRead(binding.conversationId(), file, mtimeOf(file));

            Map<String, Object> result = okBase();
            result.put("path", WorkspacePathPolicy.display(binding.root(), file));
            result.put("strategy", matched.strategy().toString());
            result.put("replacements", all ? matched.candidateCount() : 1);
            result.put("diff", UnifiedDiff.diff(
                    WorkspacePathPolicy.display(binding.root(), file), content, updated));
            return toJson(result);
        } catch (RuntimeException | IOException e) {
            return error(e.getMessage());
        }
    }

    // ── grep ─────────────────────────────────────────────────────────────────────────────

    @Tool(name = "grep",
          description = "Search file CONTENTS under the workspace with a regular expression "
                  + "(java.util.regex syntax). Returns matching lines with file:line references. "
                  + "Skips build/dependency directories and binary files. Prefer this over "
                  + "execute_command for code search.")
    public String grep(
            @ToolParam(description = "Regular expression to match per line.") String pattern,
            @ToolParam(required = false,
                       description = "Optional glob narrowing which FILES to search, e.g. '*.java' or 'src/**/*.ts'.")
            String glob,
            @ToolParam(required = false,
                       description = "Workspace-relative subdirectory to search (default: whole workspace).")
            String path,
            @ToolParam(required = false,
                       description = "Maximum matches to return (default 200, max 500).")
            Integer maxResults) {
        try {
            WorkspaceContext.Binding binding = requireBinding();
            Pattern regex;
            try {
                regex = Pattern.compile(pattern == null ? "" : pattern);
            } catch (PatternSyntaxException e) {
                return error("Invalid regular expression: " + e.getDescription());
            }
            Path searchRoot = path == null || path.isBlank()
                    ? binding.root()
                    : WorkspacePathPolicy.resolve(binding.root(), path);
            if (!Files.isDirectory(searchRoot)) return error("Not a directory: " + path);
            java.nio.file.PathMatcher filter = globMatcher(binding.root(), glob);

            List<Map<String, Object>> matches = new ArrayList<>();
            int[] filesSearched = {0};
            boolean[] truncated = {false};
            int limit = bounded(maxResults, GREP_DEFAULT_LIMIT, 1, GREP_MAX_LIMIT);
            int[] outputChars = {0};

            walkFiles(searchRoot, file -> {
                if (!filter.matches(file)) return;
                filesSearched[0]++;
                if (filesSearched[0] > MAX_WALK_ENTRIES) {
                    truncated[0] = true;
                    return;
                }
                String text;
                try {
                    if (Files.size(file) > MAX_FILE_BYTES) return;
                    text = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException | RuntimeException unreadable) {
                    return; // unreadable or undecodable: not a search error
                }
                if (looksBinary(text)) return;
                String[] lines = text.split("\n", -1);
                for (int number = 0; number < lines.length; number++) {
                    if (matches.size() >= limit || outputChars[0] >= GREP_MAX_OUTPUT_CHARS) {
                        truncated[0] = true;
                        return;
                    }
                    if (regex.matcher(lines[number]).find()) {
                        matches.add(matchEntry(binding.root(), file, number + 1, lines[number]));
                        outputChars[0] += lines[number].length();
                    }
                }
            });

            Map<String, Object> result = okBase();
            result.put("matches", matches);
            result.put("count", matches.size());
            result.put("filesSearched", Math.min(filesSearched[0], MAX_WALK_ENTRIES));
            result.put("truncated", truncated[0]);
            return toJson(result);
        } catch (RuntimeException | IOException e) {
            return error(e.getMessage());
        }
    }

    // ── glob ─────────────────────────────────────────────────────────────────────────────

    @Tool(name = "glob",
          description = "Find files under the workspace by glob pattern against "
                  + "workspace-relative paths, e.g. 'src/**/*.ts', '*.md'. Skips "
                  + "build/dependency directories. Sorted lexicographically.")
    public String glob(
            @ToolParam(description = "Glob pattern (* any run, ** any depth, ? one char).") String pattern,
            @ToolParam(required = false,
                       description = "Workspace-relative subdirectory to search (default: whole workspace).")
            String path,
            @ToolParam(required = false, description = "Maximum results (default 500, max 2000).")
            Integer maxResults) {
        try {
            WorkspaceContext.Binding binding = requireBinding();
            if (pattern == null || pattern.isBlank()) return error("Pattern must not be blank");
            Path searchRoot = path == null || path.isBlank()
                    ? binding.root()
                    : WorkspacePathPolicy.resolve(binding.root(), path);
            if (!Files.isDirectory(searchRoot)) return error("Not a directory: " + path);
            java.nio.file.PathMatcher matcher = globMatcher(binding.root(), pattern);
            int limit = bounded(maxResults, GLOB_DEFAULT_LIMIT, 1, GLOB_MAX_LIMIT);

            List<String> found = new ArrayList<>();
            boolean[] truncated = {false};
            walkFiles(searchRoot, file -> {
                if (found.size() >= limit) {
                    truncated[0] = true;
                    return;
                }
                if (matcher.matches(file)) {
                    found.add(WorkspacePathPolicy.display(binding.root(), file));
                }
            });
            found.sort(String::compareTo);

            Map<String, Object> result = okBase();
            result.put("matches", found);
            result.put("count", found.size());
            result.put("truncated", truncated[0]);
            return toJson(result);
        } catch (RuntimeException | IOException e) {
            return error(e.getMessage());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private static WorkspaceContext.Binding requireBinding() {
        WorkspaceContext.Binding binding = WorkspaceContext.current();
        if (binding == null) {
            throw new IllegalStateException(
                    "This conversation has no workspace attached; the user can attach one in the chat UI");
        }
        return binding;
    }

    /** Read-before-edit contract: the file must have been read and not modified since. */
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

    private static FileTime mtimeOf(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Glob matching against the workspace-relative form (or the bare file name for patterns
     * without a slash — a bare {@code *.ts} matches at any depth). Hand-rolled instead of
     * {@code PathMatcher} because its double-star never matches zero directories, so
     * {@code src} + double-star + {@code .ts} patterns would miss direct children such as
     * {@code src/a.ts}.
     */
    private static java.nio.file.PathMatcher globMatcher(Path root, String glob) {
        if (glob == null || glob.isBlank()) return file -> true;
        boolean nameOnly = !glob.contains("/");
        Pattern pattern = Pattern.compile(globToRegex(glob));
        return file -> {
            String relative = root.relativize(file).toString().replace('\\', '/');
            String candidate = relative.substring(relative.lastIndexOf('/') + 1);
            return pattern.matcher(nameOnly ? candidate : relative).matches();
        };
    }

    private static String globToRegex(String glob) {
        String[] segments = glob.split("/", -1);
        StringBuilder regex = new StringBuilder();
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.equals("**")) {
                // Zero or more whole directory segments, including none.
                if (index == segments.length - 1) return regex.append(".*").toString();
                regex.append("(?:[^/]+/)*");
                continue;
            }
            int braceDepth = 0;
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                switch (c) {
                    case '*' -> regex.append("[^/]*");
                    case '?' -> regex.append("[^/]");
                    case '{' -> {
                        regex.append("(?:(?:");
                        braceDepth++;
                    }
                    case '}' -> {
                        regex.append("))");
                        braceDepth = Math.max(0, braceDepth - 1);
                    }
                    case ',' -> regex.append(braceDepth > 0 ? ")|(?:" : ",");
                    default -> {
                        if ("\\.[]()^-$+|".indexOf(c) >= 0) regex.append('\\');
                        regex.append(c);
                    }
                }
            }
            if (index < segments.length - 1) regex.append('/');
        }
        return regex.toString();
    }

    private static Map<String, Object> matchEntry(Path root, Path file, int line, String text) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("path", WorkspacePathPolicy.display(root, file));
        entry.put("line", line);
        entry.put("text", text.length() > 400 ? text.substring(0, 400) : text);
        return entry;
    }

    @FunctionalInterface
    private interface FileConsumer {
        void accept(Path file) throws IOException;
    }

    /** Depth-first walk that prunes excluded directories and ignores symlinked directories. */
    private static void walkFiles(Path root, FileConsumer consumer) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return EXCLUDED_DIRS.matcher(name).matches()
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    try {
                        consumer.accept(file);
                    } catch (IOException ignored) {
                        // one unreadable file must never abort the search
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean looksBinary(String content) {
        int sniff = Math.min(content.length(), BINARY_SNIFF_BYTES);
        for (int index = 0; index < sniff; index++) {
            if (content.charAt(index) == '\0') return true;
        }
        return false;
    }

    private static int bounded(Integer value, int fallback, int min, int max) {
        if (value == null) return fallback;
        return Math.max(min, Math.min(max, value));
    }

    private static Map<String, Object> okBase() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    private static String error(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message == null ? "workspace tool failed" : message);
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
