package fan.summer.fengyu.ai;

import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;

import java.util.List;

/**
 * Appends the active file grants for a chat turn to the system prompt (route A fallback). When the
 * host could not transparently inject a FileRef (multiple file params or no
 * matching grant), the model picks from this list and passes the whole object as the argument.
 */
public final class ActiveFilesPromptAppender {

    private ActiveFilesPromptAppender() {}

    /**
     * @return {@code basePrompt} with an "available files" section appended, or {@code basePrompt}
     *         unchanged when no active files are present or the list is null.
     */
    public static String append(String basePrompt, List<ActiveFileRef> activeRefs) {
        if (activeRefs == null || activeRefs.isEmpty()) return basePrompt;
        StringBuilder sb = new StringBuilder();
        sb.append(basePrompt == null ? "" : basePrompt);
        if (sb.length() > 0) sb.append("\n\n");
        sb.append("## Files available for this conversation\n");
        sb.append("When a plugin tool needs a file/directory parameter, pick from this list and ");
        sb.append("pass the WHOLE object as the argument, exactly as shown:\n");
        sb.append("Use that plugin's purpose-built tools for supported file operations instead of ");
        sb.append("shell commands. For Excel workbooks, call excel_analyze before configuring or executing a split.\n");
        for (ActiveFileRef ref : activeRefs) {
            FileRef f = ref.ref();
            sb.append("- ").append(ref.pluginId()).append(": ");
            sb.append("{\"id\":\"").append(f.id()).append("\",");
            sb.append("\"name\":").append(jsonString(f.name())).append(',');
            sb.append("\"kind\":\"").append(f.kind()).append("\",");
            sb.append("\"access\":\"").append(f.access()).append("\",");
            sb.append("\"size\":").append(f.size()).append("}\n");
        }
        return sb.toString();
    }

    private static String jsonString(String s) {
        // minimal JSON string escaping for the name field
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; s != null && i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
