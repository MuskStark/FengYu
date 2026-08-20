package fan.summer.fengyu.ai.tools;

import org.springframework.ai.tool.ToolCallback;

/** Tool callback carrying an explicit effect classification for approval and UI audit events. */
public interface AuditedToolCallback extends ToolCallback {
    ToolEffect effect();

    /**
     * Whether an identical invocation may be attempted again after a failure without creating
     * duplicate side effects. Read-only tools are safe by default; mutating/external tools must
     * opt in explicitly at their capability boundary.
     */
    default boolean retrySafe() {
        return effect() == ToolEffect.READ;
    }
}
