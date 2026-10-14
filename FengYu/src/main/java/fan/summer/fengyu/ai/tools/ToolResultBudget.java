package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.runtime.RuntimePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Layered budgets for tool-call results BEFORE they reach the model, the SSE stream, or the
 * persisted transcript (all three consumers share the callback's return string, so this is the
 * single choke point). Aligned with the numbers the industry converged on (Claude Code and
 * ZCode both cap shell-like output at ~30 KB; FengYu gives schema-validated plugin tools more
 * room than opaque third-party MCP tools):
 *
 * <ul>
 *   <li><b>Plugin tools</b> (manifest-validated, schema-checked): 256 KiB.</li>
 *   <li><b>MCP tools</b> (arbitrary third-party servers): 64 KiB.</li>
 * </ul>
 *
 * <p>Overflow is never silently dropped: the complete output is written to a process-lifetime
 * artifact under {@code <runtime-root>/tool-results/} and the truncated text carries an explicit
 * marker with the artifact's path (the desktop user can open it; the model at least knows the
 * result was cut and where the whole thing lives). Artifacts older than this JVM's start are
 * swept on the first write — previous runs' orphans, and (accepted) any still-running sibling
 * process's earlier artifacts, since this data is process-lifetime by contract. The directory
 * is capped at {@link #MAX_TOTAL_BYTES}, oldest-first, mirroring the default of the reserved
 * {@code fengyu.chat-resources.tool-artifacts-bytes} key (threading the configured value
 * through is a deliberate follow-up; the static cap is the guard).
 *
 * <p>MCP results that are themselves the official large-content channel pass through
 * untouched up to {@link #RESOURCE_PASSTHROUGH_CEILING_BYTES}: a JSON envelope whose shape is
 * STRUCTURALLY verified (top-level {@code resourceUri}, or a {@code content[]} element typed
 * {@code resource}/{@code resource_link}). Truncating such an envelope would corrupt the very
 * mechanism the protocol provides to avoid oversized inline content; the absolute ceiling
 * keeps even a hostile mislabeling bounded.
 */
public final class ToolResultBudget {

    /** Plugin-tool budget: 256 KiB (schema-validated, trusted surface). */
    static final int PLUGIN_BUDGET_BYTES = 256 * 1024;
    /** MCP-tool budget: 64 KiB (arbitrary third-party output). */
    static final int MCP_BUDGET_BYTES = 64 * 1024;
    /**
     * Absolute ceiling for the MCP resource-payload pass-through: even a legitimately-shaped
     * resource envelope may not carry more than this into the model/SSE/transcript in one
     * call — and a hostile server labeling arbitrary output as a "resource" gains at most
     * this much, not unbounded.
     */
    static final int RESOURCE_PASSTHROUGH_CEILING_BYTES = 32 * 1024 * 1024;
    /** Total artifact ceiling, oldest-first eviction beyond it. */
    static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
    /**
     * The truncation marker every budgeted result carries. Public constant by necessity:
     * AgentRunner's step validation matches it to fail with an actionable cause instead of a
     * confusing JSON error — rewording it must stay a compile-time-coordinated change.
     */
    public static final String TRUNCATION_MARKER = "[FengYu] Tool output truncated:";

    private static final AtomicBoolean SWEPT = new AtomicBoolean(false);
    private static final long JVM_START = System.currentTimeMillis();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            com.fasterxml.jackson.databind.json.JsonMapper.builder().build();

    private ToolResultBudget() {}

    /**
     * Applies the class budget to one tool result. Returns the text unchanged when it fits;
     * otherwise returns the head plus the truncation marker, after persisting the full text.
     *
     * @param toolName the tool's wire name (marker provenance only)
     * @param output   the serialized result text (may be null — passed through)
     * @param mcp      {@code true} for MCP-served tools, {@code false} for plugin tools
     */
    public static String budget(String toolName, String output, boolean mcp) {
        if (output == null) return null;
        int limit = mcp ? MCP_BUDGET_BYTES : PLUGIN_BUDGET_BYTES;
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= limit) return output;
        if (mcp && bytes.length <= RESOURCE_PASSTHROUGH_CEILING_BYTES
                && isResourcePayload(output)) {
            return output;
        }
        String shown = head(bytes, limit);
        Path artifact = writeArtifact(toolName, output);
        String provenance = artifact == null
                ? "The complete output could not be saved."
                : "The complete output was saved to: " + artifact + " (kept until the app restarts).";
        return shown + "\n\n" + TRUNCATION_MARKER + " showing the first "
                + readable(shown.getBytes(StandardCharsets.UTF_8).length) + " of "
                + readable(bytes.length) + ". " + provenance;
    }

    /**
     * The longest prefix fitting the byte budget, cut on the already-encoded bytes and walked
     * back over UTF-8 continuation bytes to a character boundary — O(1) after the single
     * encode, and never split so the marker starts mid-surrogate-pair.
     */
    private static String head(byte[] bytes, int byteLimit) {
        if (bytes.length <= byteLimit) return new String(bytes, StandardCharsets.UTF_8);
        int cut = byteLimit;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) cut--;
        return new String(bytes, 0, cut, StandardCharsets.UTF_8);
    }

    /**
     * The MCP official large-content shapes, verified STRUCTURALLY by parsing the envelope:
     * a top-level {@code resourceUri} string, or a {@code content[]} element that is itself
     * an object with {@code type} of {@code resource}/{@code resource_link}. Substring
     * matching is deliberately rejected — the server fully controls this JSON, and any
     * oversized payload could otherwise carry the marker text inside a string value to
     * defeat the budget.
     */
    private static boolean isResourcePayload(String output) {
        String trimmed = output.stripLeading();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false;
        try {
            com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(output);
            if (root.isObject() && root.path("resourceUri").isTextual()) return true;
            com.fasterxml.jackson.databind.JsonNode content = root.isObject()
                    ? root.path("content") : root;
            if (content.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode item : content) {
                    String type = item.path("type").asText("");
                    if ("resource".equals(type) || "resource_link".equals(type)) return true;
                }
            }
            return false;
        } catch (IOException malformed) {
            return false; // not parseable JSON → not a resource envelope → budget it
        }
    }

    private static Path writeArtifact(String toolName, String output) {
        try {
            Path dir = RuntimePaths.root().resolve("tool-results");
            sweepOnce(dir);
            Files.createDirectories(dir);
            Path file = dir.resolve(System.currentTimeMillis() + "-"
                    + sanitize(toolName) + "-" + UUID.randomUUID() + ".txt");
            Files.writeString(file, output);
            evictOldestBeyond(dir, MAX_TOTAL_BYTES);
            return file;
        } catch (IOException e) {
            return null;
        }
    }

    /** First-write-of-the-process sweep: entries written before this JVM started are orphans
     *  by contract (that includes a still-running sibling's earlier artifacts — process-lifetime
     *  data, accepted loss; the JVM-start boundary keeps a concurrent FIRST write in THIS
     *  process safe on ns-granularity filesystems). */
    private static void sweepOnce(Path dir) {
        if (!SWEPT.compareAndSet(false, true)) return;
        if (!Files.isDirectory(dir)) return;
        try (var entries = Files.list(dir)) {
            for (Path entry : entries.toList()) {
                try {
                    if (Files.getLastModifiedTime(entry).toMillis() >= JVM_START) continue;
                    deleteTree(entry);
                } catch (IOException ignored) {
                    // a locked or unreadable entry must never break the sweep
                }
            }
        } catch (IOException ignored) {
            // a vanishing directory mid-sweep is already swept
        }
    }

    /** Oldest-first eviction keeping the directory under {@code cap} bytes. */
    private static void evictOldestBeyond(Path dir, long cap) {
        try (var files = Files.list(dir)) {
            var candidates = files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong(ToolResultBudget::mtimeOf))
                    .toList();
            long total = candidates.stream().mapToLong(ToolResultBudget::sizeOf).sum();
            for (Path candidate : candidates) {
                if (total <= cap) break;
                long size = sizeOf(candidate);
                if (deleteQuietly(candidate)) total -= size;
            }
        } catch (IOException ignored) {
            // eviction is best-effort; the write itself already succeeded
        }
    }

    private static long mtimeOf(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); }
        catch (IOException e) { return Long.MAX_VALUE; }
    }

    private static long sizeOf(Path p) {
        try { return Files.size(p); }
        catch (IOException e) { return 0L; }
    }

    private static boolean deleteQuietly(Path p) {
        try { return Files.deleteIfExists(p); }
        catch (IOException e) { return false; }
    }

    private static void deleteTree(Path entry) throws IOException {
        try (var walk = Files.walk(entry)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /**
     * The MCP-class budget over a callback's result, resolving the tool name from its
     * definition — the shared form both callback layers (AiToolRegistry's audited wrapper
     * and McpRuntimeManager's reconnecting wrapper) use so the name handling cannot drift.
     */
    public static String budgetMcp(org.springframework.ai.tool.definition.ToolDefinition definition,
            String result) {
        String name = definition == null || definition.name() == null
                ? "mcp_tool" : definition.name();
        return budget(name, result, true);
    }

    private static String sanitize(String name) {
        String safe = name == null ? "tool" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.length() > 48 ? safe.substring(0, 48) : (safe.isEmpty() ? "tool" : safe);
    }

    private static String readable(long bytes) {
        if (bytes >= 1024 * 1024) return bytes / (1024 * 1024) + " MiB";
        if (bytes >= 1024) return bytes / 1024 + " KiB";
        return bytes + " bytes";
    }
}
