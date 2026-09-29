package fan.summer.fengyu.ai.workspace;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Appends the coding-workspace section to the chat system prompt when the turn carries a
 * workspace binding. Same request-time pattern as {@code ActiveFilesPromptAppender}: the base
 * persona stays workspace-free and this runs only when the user has actually attached a folder.
 *
 * <p>When the workspace root carries a project-instructions file ({@code AGENTS.md} or
 * {@code FENGYU.md}, first match wins), its content is appended under a dedicated heading so
 * the model obeys the project's own conventions — terminal coding-agent practice.
 * The file is capped; an unreadable or oversized file is skipped
 * silently rather than poisoning the turn.</p>
 */
public final class WorkspacePromptAppender {

    /** Project-instruction candidates at the workspace root, in priority order. */
    static final String[] PROJECT_INSTRUCTION_FILES = {"AGENTS.md", "FENGYU.md"};

    /** Project instructions beyond this are truncated with a marker (byte estimate). */
    static final int MAX_PROJECT_INSTRUCTIONS_CHARS = 16_000;

    private WorkspacePromptAppender() {}

    public static String append(String systemPrompt) {
        WorkspaceContext.Binding binding = WorkspaceContext.current();
        if (binding == null) return systemPrompt;
        String base = systemPrompt == null ? "" : systemPrompt;
        String prompt = base + """

                ## Workspace
                The conversation has an attached workspace root: %s. The read_file, grep, and
                glob tools inspect it; write_file, edit_file, apply_patch, workspace_exec, and
                write_stdin modify or execute inside it (workspace-relative paths).
                - Explore with glob/grep first. Read a file before editing it — the write tools
                  reject files that were not read or that changed since the last read.
                - Prefer apply_patch whenever an edit touches several places or several files in
                  one step; use edit_file for a single small replacement. Do NOT re-read a file
                  after a successful edit — the tool reports failures itself.
                - Delegate self-contained multi-step work (bulk renames, wide migrations,
                  long investigations) to the explore (read-only) or delegate_task (may write)
                  subagents so this conversation keeps only the conclusions; use
                  delegate_task's isolate=true when it must not touch uncommitted changes.
                - The workspace may be dirty with the user's own changes. NEVER revert changes
                  you did not make, never run destructive git commands (reset --hard, checkout --,
                  clean), and stop to ask the user if you notice unexpected changes you did not
                  cause.
                - Build/dependency directories (.git, node_modules, target, build, dist) are
                  excluded from search and should not be edited.
                - Verify your own work: run the narrowest check first (a single test, a compile),
                  widen only if it passes, and iterate on formatting at most 3 times. Long
                  checks can run as interactive sessions (workspace_exec interactive=true +
                  write_stdin). After substantial edits, run review over the diff so an
                  independent reviewer grades it before you declare the work done.
                - Report honestly what you changed and what remains, citing files as
                  path/to/file.java:123.
                - Stay inside the workspace; paths outside it are rejected by the host.
                """.formatted(binding.root()).stripTrailing();
        String instructions = readProjectInstructions(binding.root());
        if (instructions == null) return prompt;
        return prompt + "\n\n## Project instructions (AGENTS.md)\n"
                + "The workspace's own instruction file follows. Treat it as the project's\n"
                + "conventions for THIS workspace: follow it unless it conflicts with the user's\n"
                + "current request or these system rules.\n\n" + instructions;
    }

    /**
     * The workspace's project-instruction file content (truncated to bound the prompt), or
     * null when the root has none / it cannot be read. Package-private for tests.
     */
    static String readProjectInstructions(Path root) {
        if (root == null) return null;
        for (String name : PROJECT_INSTRUCTION_FILES) {
            Path file = root.resolve(name);
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) continue;
            try {
                String content = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
                if (content.isBlank()) return null;
                if (content.length() > MAX_PROJECT_INSTRUCTIONS_CHARS) {
                    int cut = MAX_PROJECT_INSTRUCTIONS_CHARS;
                    // Never split a surrogate pair (emoji, CJK extensions) mid-pair — an
                    // unpaired high surrogate at the cut would corrupt the tail.
                    if (Character.isHighSurrogate(content.charAt(cut - 1))) cut--;
                    return content.substring(0, cut)
                            + "\n…[project instructions truncated at " + cut
                            + " characters]";
                }
                return content.stripTrailing();
            } catch (Exception unreadable) {
                return null; // unreadable instructions must never fail the turn
            }
        }
        return null;
    }
}
