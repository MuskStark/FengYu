package fan.summer.fengyu.ai.workspace;

/**
 * Appends the coding-workspace section to the chat system prompt when the turn carries a
 * workspace binding. Same request-time pattern as {@code ActiveFilesPromptAppender}: the base
 * persona stays workspace-free and this runs only when the user has actually attached a folder.
 */
public final class WorkspacePromptAppender {

    private WorkspacePromptAppender() {}

    public static String append(String systemPrompt) {
        WorkspaceContext.Binding binding = WorkspaceContext.current();
        if (binding == null) return systemPrompt;
        String base = systemPrompt == null ? "" : systemPrompt;
        return base + """

                ## Workspace
                The conversation has an attached workspace root: %s. The read_file, write_file,
                edit_file, grep, and glob tools operate inside it (workspace-relative paths).
                - Explore with glob/grep first; read a file before editing it — write_file and
                  edit_file reject files that were not read or that changed since the last read.
                - Prefer edit_file with the exact text copied from read_file output over
                  rewriting whole files; keep edits minimal and focused.
                - Build/dependency directories (.git, node_modules, target, build, dist) are
                  excluded from search and should not be edited.
                - Verify your own work (re-read, run checks via execute_command when useful) and
                  report honestly what you changed and what remains.
                - Stay inside the workspace; paths outside it are rejected by the host.
                """.formatted(binding.root()).stripTrailing();
    }
}
