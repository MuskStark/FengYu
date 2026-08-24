package fan.summer.fengyu.ai.tools;

import org.springframework.ai.tool.ToolCallback;

/** Tool callback carrying an explicit effect classification for approval and UI audit events. */
public interface AuditedToolCallback extends ToolCallback {
    ToolEffect effect();

    /**
     * Optional result contract used by Flow to validate pinned values and referenced output paths.
     * Built-in and remote tools may leave it unknown; plugin callbacks expose their generated RPC
     * output schema here so the host can fail before a malformed value contaminates downstream
     * steps.
     */
    default String outputSchema() {
        return null;
    }

    /**
     * Whether an identical invocation may be attempted again after a failure without creating
     * duplicate side effects. Read-only tools are safe by default; mutating/external tools must
     * opt in explicitly at their capability boundary.
     */
    default boolean retrySafe() {
        return effect() == ToolEffect.READ;
    }
}
