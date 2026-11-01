package fan.summer.fengyu.ai.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The memory FILE protocol (v4 architecture): one Markdown file per durable fact under
 * {@code ~/.fengyu/memory/}, plus a single {@code MEMORY.md} index that is the ONLY thing
 * injected into prompts — one line per fact, never a fact body. The index is the whole
 * retrieval surface for prompt context: bounded by construction, readable by the user in
 * a text editor, and diffable in git if the directory is ever symlinked into one.
 *
 * <p>Transitional placement (v4 cut): the DB remains the retrieval backend for
 * {@code memory_search} while this store mirrors every write, so the prompt index and the
 * search results can never disagree for long. The F2-1 finish line drops the DB half and
 * makes this store the single source; the file layout below is already the target shape.</p>
 *
 * <p>Format per fact file ({@code <id>.md}):</p>
 * <pre>
 * ---
 * name: short-kebab-slug
 * description: one-line summary used as the index hook
 * type: user | project | reference
 * source: manual | extraction
 * createdAt: 2026-10-31T12:00:00
 * ---
 * fact body, plain Markdown, [[other-fact]] links welcome
 * </pre>
 */
final class AiMemoryFiles {

    /** Index line budget: the injected surface stays small however many facts exist. */
    static final int MAX_INDEX_CHARS = 6_000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final Path root;
    /** id → index-line source of truth, rebuilt on load, appended on write. */
    private final Map<String, Fact> facts = new ConcurrentHashMap<>();

    /** One fact's file-plus-index identity. */
    record Fact(String id, String description, String type, String source,
            LocalDateTime createdAt) { }

    AiMemoryFiles(Path root) {
        this.root = root;
        loadAll();
    }

    /** Reads every fact file's frontmatter into the index map; a missing dir starts empty. */
    private void loadAll() {
        if (!Files.isDirectory(root)) return;
        try (var files = Files.list(root)) {
            files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(path -> !path.getFileName().toString().equals("MEMORY.md"))
                    .forEach(this::loadOne);
        } catch (IOException e) {
            // an unreadable memory dir degrades to empty-index, never to a crash
        }
    }

    private void loadOne(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.getFirst().equals("---")) return;
            String id = null, description = "", type = "reference", source = "manual";
            LocalDateTime createdAt = LocalDateTime.now();
            for (int i = 1; i < lines.size() && !lines.get(i).equals("---"); i++) {
                String line = lines.get(i);
                if (line.startsWith("name:")) id = value(line);
                else if (line.startsWith("description:")) description = value(line);
                else if (line.startsWith("type:")) type = value(line);
                else if (line.startsWith("source:")) source = value(line);
                else if (line.startsWith("createdAt:")) {
                    try { createdAt = LocalDateTime.parse(value(line), TS); }
                    catch (RuntimeException malformed) { /* keep fallback */ }
                }
            }
            if (id != null) facts.put(id, new Fact(id, description, type, source, createdAt));
        } catch (IOException | RuntimeException ignored) {
            // one malformed fact file must not take the memory subsystem down
        }
    }

    private static String value(String frontmatterLine) {
        return frontmatterLine.substring(frontmatterLine.indexOf(':') + 1).strip();
    }

    /**
     * Writes one fact file (atomically — temp file + move) and refreshes the index map.
     * The description doubles as the index hook; empty falls back to a content prefix.
     */
    synchronized void write(String id, String description, String type, String source,
            String body, List<String> topics) {
        try {
            Files.createDirectories(root);
            String desc = description == null || description.isBlank()
                    ? firstLine(body, 60) : description;
            StringBuilder file = new StringBuilder();
            file.append("---\n");
            file.append("name: ").append(safe(id)).append('\n');
            file.append("description: ").append(oneLine(desc)).append('\n');
            file.append("type: ").append(safe(type == null ? "reference" : type)).append('\n');
            file.append("source: ").append(safe(source == null ? "manual" : source)).append('\n');
            if (topics != null && !topics.isEmpty()) {
                file.append("topics: ").append(oneLine(String.join(", ", topics))).append('\n');
            }
            file.append("createdAt: ").append(TS.format(LocalDateTime.now())).append('\n');
            file.append("---\n\n").append(body.strip()).append('\n');
            Path target = root.resolve(safe(id) + ".md");
            Path temp = root.resolve(safe(id) + ".md.tmp");
            Files.writeString(temp, file.toString(), StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            facts.put(id, new Fact(id, desc, type == null ? "reference" : type,
                    source == null ? "manual" : source, LocalDateTime.now()));
        } catch (IOException e) {
            // file mirroring is best-effort; the DB write already succeeded
        }
    }

    /** Removes one fact file and its index entry; false when it was never mirrored. */
    synchronized boolean remove(String id) {
        facts.remove(id);
        try {
            return Files.deleteIfExists(root.resolve(safe(id) + ".md"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The prompt-injection surface: the MEMORY.md index — newest first, one line per
     * fact, hard-capped so a hundred facts still cost one small prompt section.
     */
    synchronized String indexMarkdown() {
        List<Fact> ordered = new ArrayList<>(facts.values());
        ordered.sort(Comparator.comparing(Fact::createdAt).reversed());
        StringBuilder index = new StringBuilder("# Memory index\n\n");
        for (Fact fact : ordered) {
            String line = "- " + fact.description() + " (`" + fact.id() + "`)\n";
            if (index.length() + line.length() > MAX_INDEX_CHARS) {
                index.append("- …older facts omitted\n");
                break;
            }
            index.append(line);
        }
        return facts.isEmpty() ? "" : index.toString();
    }

    int size() {
        return facts.size();
    }

    private static String safe(String id) {
        return id == null ? "fact" : id.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static String oneLine(String text) {
        return text.replaceAll("[\\r\\n]+", " ").strip();
    }

    private static String firstLine(String body, int max) {
        String line = oneLine(body == null ? "" : body);
        return line.length() > max ? line.substring(0, max) : line;
    }
}
