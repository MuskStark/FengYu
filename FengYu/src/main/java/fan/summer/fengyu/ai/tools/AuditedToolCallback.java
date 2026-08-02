package fan.summer.fengyu.ai.tools;

import org.springframework.ai.tool.ToolCallback;

/** Tool callback carrying an explicit effect classification for approval and UI audit events. */
public interface AuditedToolCallback extends ToolCallback {
    ToolEffect effect();
}
