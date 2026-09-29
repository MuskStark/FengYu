package fan.summer.fengyu.ai.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the {@code apply_patch} freeform tool — the terminal coding-agent patch
 * grammar ({@code *** Begin Patch} / {@code *** Update File:} with diff-style context,
 * {@code *** Add File:}, {@code *** Delete File:}, {@code *** Move to:}, {@code @@} named
 * anchors, {@code *** End of File}).
 *
 * <p>Parsing is deliberately LENIENT, mirroring upstream practice: markers tolerate
 * surrounding whitespace, a markdown code fence around the whole patch is dropped, a
 * GPT-4.1-style {@code <<EOF … EOF} heredoc wrapper is unwrapped, a bare empty line inside
 * an update hunk reads as an empty context line, and everything after {@code *** End Patch}
 * is ignored. What is NOT forgiven: a line inside a hunk that carries no {@code / + / -}
 * prefix (prose bleeding into the patch) and update chunks without a single change — both
 * must fail loudly so the model regenerates the patch instead of silently mangling a file.</p>
 */
final class ApplyPatchParser {

    /** Patches beyond this are rejected outright — a patch is an edit, not a file transfer. */
    static final int MAX_PATCH_CHARS = 512_000;

    record Patch(List<Hunk> hunks) {}

    sealed interface Hunk permits AddFile, DeleteFile, UpdateFile {}

    record AddFile(String path, List<String> lines) implements Hunk {}

    record DeleteFile(String path) implements Hunk {}

    /** Update with optional rename: chunks apply to the source, then the file moves. */
    record UpdateFile(String path, String moveTo, List<Chunk> chunks) implements Hunk {}

    /** One {@code @@ anchor} chunk: {@code oldLines} are the context+removed lines in file
     *  order, {@code newLines} the context+added replacement; {@code endOfFile} marks the
     *  {@code *** End of File} anchor. */
    record Chunk(String anchor, List<String> oldLines, List<String> newLines, boolean endOfFile) {}

    static final class ParseError extends RuntimeException {
        ParseError(String message) {
            super(message);
        }
    }

    static Patch parse(String raw) {
        if (raw == null || raw.isBlank()) throw new ParseError("The patch is empty");
        if (raw.length() > MAX_PATCH_CHARS) {
            throw new ParseError("The patch is " + raw.length() + " characters; the maximum is "
                    + MAX_PATCH_CHARS + ". Split the work into several apply_patch calls.");
        }
        List<String> lines = stripWrappers(raw.split("\n", -1));

        int index = 0;
        while (index < lines.size() && lines.get(index).isBlank()) index++;
        if (index >= lines.size() || !lines.get(index).trim().equals("*** Begin Patch")) {
            throw new ParseError("The patch must start with '*** Begin Patch'");
        }
        index++;

        List<Hunk> hunks = new ArrayList<>();
        while (index < lines.size()) {
            String line = lines.get(index);
            String trimmed = line.trim();
            if (trimmed.equals("*** End Patch")) break;
            if (line.isBlank()) {
                index++;
                continue;
            }
            if (trimmed.startsWith("*** Add File: ")) {
                index = parseAdd(lines, index, trimmed.substring("*** Add File: ".length()), hunks);
            } else if (trimmed.startsWith("*** Delete File: ")) {
                hunks.add(new DeleteFile(requirePath(trimmed.substring("*** Delete File: ".length()))));
                index++;
            } else if (trimmed.startsWith("*** Update File: ")) {
                index = parseUpdate(lines, index, trimmed.substring("*** Update File: ".length()), hunks);
            } else {
                throw new ParseError("Expected a hunk marker ('*** Add File:'/'*** Update File:'/"
                        + "'*** Delete File:'/'*** End Patch') but found: " + preview(line));
            }
        }
        if (hunks.isEmpty()) throw new ParseError("The patch contains no file hunks");
        return new Patch(List.copyOf(hunks));
    }

    // ── Add File ─────────────────────────────────────────────────────────────────────────

    private static int parseAdd(List<String> lines, int index, String path, List<Hunk> hunks) {
        requirePath(path);
        List<String> content = new ArrayList<>();
        index++;
        while (index < lines.size()) {
            String line = lines.get(index);
            String trimmed = line.trim();
            if (isHunkBoundary(trimmed)) break;
            if (line.startsWith("+")) {
                content.add(line.substring(1));
            } else if (line.isEmpty()) {
                // A bare empty line inside an Add File body reads as an empty content line —
                // models frequently drop the leading '+' on blank lines.
                content.add("");
            } else {
                throw new ParseError("Add File body lines must start with '+' but found: "
                        + preview(line));
            }
            index++;
        }
        if (content.isEmpty()) {
            throw new ParseError("Add File hunk for " + path + " has no content lines");
        }
        hunks.add(new AddFile(path, List.copyOf(content)));
        return index;
    }

    // ── Update File ──────────────────────────────────────────────────────────────────────

    private static int parseUpdate(List<String> lines, int index, String path, List<Hunk> hunks) {
        requirePath(path);
        index++;
        String moveTo = null;
        if (index < lines.size() && lines.get(index).trim().startsWith("*** Move to: ")) {
            moveTo = requirePath(lines.get(index).trim().substring("*** Move to: ".length()));
            index++;
        }

        List<Chunk> chunks = new ArrayList<>();
        String anchor = null;
        List<String> oldLines = new ArrayList<>();
        List<String> newLines = new ArrayList<>();
        boolean endOfFile = false;

        while (index < lines.size()) {
            String line = lines.get(index);
            String trimmed = line.trim();
            if (isHunkBoundary(trimmed)) break;
            if (trimmed.startsWith("*** Move to: ")) {
                throw new ParseError("'*** Move to:' must come directly after the Update File "
                        + "marker, not after change lines");
            }
            if (trimmed.equals("*** End of File")) {
                endOfFile = true;
                index++;
                continue;
            }
            if (trimmed.startsWith("@@")) {
                flushChunk(chunks, anchor, oldLines, newLines, endOfFile, path);
                anchor = trimmed.length() > 2 ? trimmed.substring(2).trim() : null;
                endOfFile = false;
                index++;
                continue;
            }
            if (trimmed.startsWith("***")) {
                throw new ParseError("Unknown marker inside Update File hunk: " + preview(line));
            }
            if (line.startsWith("+")) {
                newLines.add(line.substring(1));
            } else if (line.startsWith("-")) {
                oldLines.add(line.substring(1));
            } else if (line.startsWith(" ")) {
                oldLines.add(line.substring(1));
                newLines.add(line.substring(1));
            } else if (line.isEmpty()) {
                // Blank context lines are often emitted without their leading space.
                oldLines.add("");
                newLines.add("");
            } else {
                throw new ParseError("Update lines must start with ' ' (context), '-' (remove), "
                        + "or '+' (add) but found: " + preview(line));
            }
            index++;
        }
        flushChunk(chunks, anchor, oldLines, newLines, endOfFile, path);

        if (chunks.isEmpty()) {
            throw new ParseError("Update File hunk for " + path + " has no change lines "
                    + "(every chunk needs at least one '-' or '+' line)");
        }
        hunks.add(new UpdateFile(path, moveTo, List.copyOf(chunks)));
        return index;
    }

    private static void flushChunk(List<Chunk> chunks, String anchor, List<String> oldLines,
            List<String> newLines, boolean endOfFile, String path) {
        if (oldLines.isEmpty() && newLines.isEmpty()) return; // bare '@@' or lead-in: no chunk yet
        boolean hasChange = oldLines.size() != newLines.size()
                || !oldLines.equals(newLines);
        if (!hasChange) {
            throw new ParseError("Update chunk in " + path
                    + " is a no-op — every chunk needs at least one '-' or '+' line that "
                    + "actually changes the content");
        }
        chunks.add(new Chunk(anchor, List.copyOf(oldLines), List.copyOf(newLines), endOfFile));
        oldLines.clear();
        newLines.clear();
    }

    // ── leniency helpers ─────────────────────────────────────────────────────────────────

    private static boolean isHunkBoundary(String trimmed) {
        return trimmed.equals("*** End Patch") || trimmed.startsWith("*** Add File: ")
                || trimmed.startsWith("*** Update File: ") || trimmed.startsWith("*** Delete File: ");
    }

    /** Drops a markdown fence and/or heredoc wrapper around the whole patch, if present. */
    private static List<String> stripWrappers(String[] raw) {
        int from = 0;
        int to = raw.length; // exclusive
        while (from < to && raw[from].isBlank()) from++;
        while (to > from && raw[to - 1].isBlank()) to--;
        if (from < to && raw[from].trim().startsWith("```")) from++;
        if (to > from && raw[to - 1].trim().startsWith("```")) to--;
        while (from < to && raw[from].isBlank()) from++;
        while (to > from && raw[to - 1].isBlank()) to--;
        if (from < to && raw[from].trim().matches("<<(['\"]?)(EOF|PATCH|eof)\\1?")) from++;
        if (to > from && raw[to - 1].trim().matches("(EOF|PATCH|eof)")) to--;
        String[] slice = new String[to - from];
        System.arraycopy(raw, from, slice, 0, slice.length);
        return List.of(slice);
    }

    private static String requirePath(String path) {
        String trimmed = path == null ? "" : path.trim();
        if (trimmed.isEmpty()) throw new ParseError("A hunk marker is missing its file path");
        return trimmed;
    }

    private static String preview(String line) {
        String trimmed = line.trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) + "…" : trimmed;
    }
}
