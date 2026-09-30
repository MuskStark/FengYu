package fan.summer.fengyu.ai.codemode;

import java.util.List;

/**
 * Transport-neutral code-mode protocol records (the Java mirror of codex
 * {@code code-mode-protocol/src/runtime.rs} — the seam a future out-of-process host
 * would speak; V1 is in-process). No engine types leak here.
 */
public final class CodeModeProtocol {

    private CodeModeProtocol() {}

    /** One nested tool offered to a cell. */
    public record ToolDefinition(String name, String description, String inputSchema) {}

    /** A cell execution request: the source, the tools it may call, and its limits. */
    public record ExecuteRequest(String toolCallId, List<ToolDefinition> enabledTools,
            String source, Long yieldTimeMs, Long maxOutputTokens) {}

    /** A model-visible content item: text, or an image as a base64 data: URI. */
    public sealed interface ContentItem permits CodeModeProtocol.Text, CodeModeProtocol.Image {
    }

    public record Text(String text) implements ContentItem {}

    /** data: URIs only — remote URLs are rejected at the source (the runtime enforces). */
    public record Image(String dataUri, String detail) implements ContentItem {}

    /** What a cell reports back: partial output while running, or a terminal state. */
    public sealed interface RuntimeResponse
            permits CodeModeProtocol.Yielded, CodeModeProtocol.Terminated, CodeModeProtocol.Result {
    }

    /** The cell yielded (time window or explicit) with what it has produced so far. */
    public record Yielded(String cellId, List<ContentItem> contentItems) implements RuntimeResponse {}

    /** The cell was terminated (user cancel, timeout, or {@code terminate}). */
    public record Terminated(String cellId) implements RuntimeResponse {}

    /** The script finished; errorText non-null means it failed. */
    public record Result(String cellId, List<ContentItem> contentItems, String errorText)
            implements RuntimeResponse {}

    /** A nested tool call raised from inside a cell — the host resolves it. */
    public record NestedToolCall(String id, String toolName, String argumentsJson) {}

    /** One pending nested call, as the script's promise sees it. */
    public record NestedToolResult(String id, String output, boolean success) {}
}
