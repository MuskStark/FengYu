package fan.summer.fengyu.ai.codemode;

import java.util.List;
import java.util.Locale;

/**
 * The model-facing prompt surface of code mode — the FengYu adaptation of codex
 * {@code description.rs} templates (English canonical, per the SystemPrompts
 * convention; examples reference OUR tool names, no codex-specific terms). The nested
 * tools ride along as a TypeScript declaration built by {@link JsonSchemaToTs}.
 */
public final class CodeModeToolDescription {

    private CodeModeToolDescription() {}

    /** One nested tool the script may call. */
    public record NestedTool(String name, String description, String inputSchema) {}

    public static final long DEFAULT_YIELD_TIME_MS = 10_000;
    public static final long DEFAULT_MAX_OUTPUT_TOKENS = 10_000;

    /** The exec tool description with the nested-tool declaration appended. */
    public static String execDescription(List<NestedTool> tools, long defaultYieldTimeMs) {
        String template = """
                Run JavaScript code to orchestrate/compose tool calls
                - Evaluates the provided JavaScript code in a fresh sandboxed engine as an async module.
                - All nested tools are available on the global `tools` object, for example `await tools.workspace_exec(...)`. Tool names are exposed as normalized JavaScript identifiers.
                - Nested tool methods take either a string or an object as their input argument.
                - Nested tools return either an object or a string, based on the description.
                - Runs raw JavaScript -- no Node, no file system, no network access, no console.
                - Accepts raw JavaScript source text, not JSON, quoted strings, or markdown code fences.
                - You may optionally start the tool input with a first-line pragma like `// @exec: {"yield_time_ms": 10000, "max_output_tokens": 1000}`.
                - `yield_time_ms` asks `exec` to yield early if the script is still running. Defaults to %d ms.
                - `max_output_tokens` sets the token budget for direct `exec` results. Defaults to %d tokens.
                - When the JS code is fully evaluated, the engine's lifetime ends and unawaited promises are silently discarded.

                - Global helpers:
                - `exit()`: Immediately ends the current script successfully (like an early return from the top level).
                - `text(value: string | number | boolean | undefined | null)`: Appends a text item. Non-string values are stringified with `JSON.stringify(...)` when possible.
                - `image(dataUri: string)`: Appends an image item. Accepts base64 `data:` URIs only; remote URLs are rejected.
                - `store(key: string, value: any)`: stores a serializable value under a string key for later `exec` calls in the same session.
                - `load(key: string)`: returns the stored value for a string key, or `undefined` if it is missing.
                - `notify(value: string | number | boolean | undefined | null)`: immediately injects an extra progress output for the current `exec` call. Values are stringified like `text(...)`.
                - `setTimeout(callback: () => void, delayMs?: number)`: schedules a callback to run later and returns a timeout id. Pending timeouts do not keep `exec` alive by themselves; await an explicit promise if you need to wait for one.
                - `clearTimeout(timeoutId?: number)`: cancels a timeout created by `setTimeout`.
                - `ALL_TOOLS`: metadata for the enabled nested tools as `{ name, description }` entries.
                - `yield_control()`: yields the accumulated output to the model immediately while the script keeps running.
                """.formatted(defaultYieldTimeMs, DEFAULT_MAX_OUTPUT_TOKENS);

        return template + "\n" + toolsDeclaration(tools);
    }

    /** The wait tool description. */
    public static String waitDescription() {
        return """
                - Use `wait` only after `exec` returns `Script running with cell ID ...`.
                - `cell_id` identifies the running `exec` cell to resume.
                - `yield_time_ms` controls how long to wait for more output before yielding again. Defaults to 10000 ms.
                - `max_tokens` limits how much new output this wait call returns. Defaults to 10000 tokens.
                - `terminate: true` stops the running cell; false or omitted waits for output.
                - `wait` returns only the new output since the last yield, or the final completion or termination result for that cell.
                - If the cell is still running, `wait` may yield again with the same `cell_id`.
                - If the cell has already finished, `wait` returns the completed result and closes the cell.
                """;
    }

    /**
     * The TypeScript declaration of the {@code tools} object: normalized identifiers,
     * per-tool argument types rendered from the JSON Schema, string returns.
     */
    static String toolsDeclaration(List<NestedTool> tools) {
        StringBuilder out = new StringBuilder("declare const tools: {\n");
        java.util.Map<String, String> identifiers = identifiers(
                tools.stream().map(NestedTool::name).toList());
        for (NestedTool tool : tools) {
            String args = tool.inputSchema() == null || tool.inputSchema().isBlank()
                    ? "unknown" : JsonSchemaToTs.render(tool.inputSchema());
            if (args.length() > JsonSchemaToTs.MAX_SCHEMA_BYTES) {
                args = args.substring(0, JsonSchemaToTs.MAX_SCHEMA_BYTES)
                        + "\n…[schema truncated]";
            }
            String summary = tool.description() == null ? "" : tool.description()
                    .strip().replaceAll("\\s+", " ");
            if (summary.length() > 160) summary = summary.substring(0, 159) + "…";
            out.append("  /** ").append(summary).append(" */\n");
            out.append("  ").append(identifiers.get(tool.name()))
                    .append("(args: ").append(indentType(args)).append("): Promise<string>;\n");
        }
        return out.append("};").toString();
    }

    /** Normalized JS identifier: non-[A-Za-z0-9_] runs collapse to underscores. */
    static String identifier(String toolName) {
        String normalized = toolName == null ? "" : toolName
                .replaceAll("[^A-Za-z0-9_]", "_");
        if (normalized.isEmpty() || Character.isDigit(normalized.charAt(0))) {
            normalized = "_" + normalized;
        }
        return normalized;
    }

    /**
     * Collision-safe identifiers for a whole tool list: {@code read-file} and
     * {@code read_file} normalize to the same JS key, and a plain per-name mapping let the
     * second declaration silently overwrite the first in both the TypeScript declaration
     * and the runtime {@code tools} object. Collisions get a numeric suffix
     * ({@code read_file_2}) so every tool stays callable.
     *
     * @return name → unique identifier, in input order
     */
    static java.util.Map<String, String> identifiers(java.util.List<String> toolNames) {
        java.util.Map<String, String> byName = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> taken = new java.util.HashMap<>();
        java.util.Set<String> emitted = new java.util.HashSet<>();
        for (String name : toolNames) {
            String base = identifier(name);
            int count = taken.merge(base, 1, Integer::sum);
            // Suffix-collisions must be checked against EVERY emitted id, not just the base's
            // own count: a tool literally named {@code read_file_2} would otherwise silently
            // overwrite an earlier {@code read_file_2} suffix.
            String candidate = count == 1 ? base : base + "_" + count;
            while (emitted.contains(candidate)) {
                candidate = base + "_" + (++count);
            }
            taken.put(base, count);
            emitted.add(candidate);
            byName.put(name, candidate);
        }
        return byName;
    }

    /** Keeps multi-line object types readable inside the declaration. */
    private static String indentType(String type) {
        if (!type.contains("\n")) return type;
        return type.replace("\n", "\n    ");
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
