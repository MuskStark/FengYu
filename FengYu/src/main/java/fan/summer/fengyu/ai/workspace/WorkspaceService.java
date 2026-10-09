package fan.summer.fengyu.ai.workspace;

import fan.summer.fengyu.database.entity.ai.ConversationEntity;
import fan.summer.fengyu.database.repository.ai.ConversationRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Persists the per-conversation workspace root (the {@code ai_conversation.workspace_root}
 * column) and validates it at every transition. The root is user-chosen and server-verified:
 * it must be an existing, readable, real directory; the canonical form ({@code toRealPath}) is
 * stored so the path jail's containment checks operate on the same identity the user saw when
 * picking the folder.
 */
@Service
public class WorkspaceService {

    private static final int MAX_ROOT_LENGTH = 1024;

    private final ConversationRepository conversations;
    private final SecurityContext securityContext;
    private final WorkspaceReadState readState;

    public WorkspaceService(ConversationRepository conversations, SecurityContext securityContext,
            WorkspaceReadState readState) {
        this.conversations = conversations;
        this.securityContext = securityContext;
        this.readState = readState;
    }

    /**
     * Attach {@code rawPath} as the workspace of {@code conversationId}. The path must name an
     * existing readable directory; returns the canonical stored root.
     */
    @Transactional
    public Path setWorkspace(Long conversationId, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Workspace path must not be blank");
        }
        ConversationEntity conversation = owned(conversationId);
        Path canonical;
        try {
            Path supplied = Path.of(expandHome(rawPath.strip()));
            if (!Files.isDirectory(supplied)) {
                throw new IllegalArgumentException("Workspace path is not a directory: " + rawPath);
            }
            if (!Files.isReadable(supplied)) {
                throw new IllegalArgumentException("Workspace directory is not readable: " + rawPath);
            }
            canonical = supplied.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve workspace path: " + e.getMessage());
        } catch (java.nio.file.InvalidPathException e) {
            // NUL bytes / platform-illegal characters: a client error, mapped to 400 by the
            // GlobalExceptionHandler like every other malformed attach request.
            throw new IllegalArgumentException("Workspace path is not valid: " + e.getMessage());
        }
        if (canonical.toString().length() > MAX_ROOT_LENGTH) {
            throw new IllegalArgumentException("Workspace path is too long");
        }
        conversation.setWorkspaceRoot(canonical.toString());
        conversation.setUpdatedAt(java.time.LocalDateTime.now());
        conversations.save(conversation);
        readState.clearConversation(conversationId);
        return canonical;
    }

    /** Detach the workspace root; coding tools disappear from the conversation's next turn. */
    @Transactional
    public void clearWorkspace(Long conversationId) {
        ConversationEntity conversation = owned(conversationId);
        conversation.setWorkspaceRoot(null);
        conversation.setUpdatedAt(java.time.LocalDateTime.now());
        conversations.save(conversation);
        readState.clearConversation(conversationId);
    }

    /**
     * The binding a chat turn should carry, or null. A stored root that has vanished from disk
     * yields null (and logs) instead of poisoning the turn — the user re-attaches a valid folder.
     */
    public WorkspaceContext.Binding bindingFor(Long conversationId) {
        if (conversationId == null) return null;
        return conversations.findByIdAndUserId(conversationId, userId())
                .map(conversation -> conversation.getWorkspaceRoot())
                .filter(root -> root != null && !root.isBlank())
                .map(root -> {
                    Path path = Path.of(root);
                    if (!Files.isDirectory(path)) return null;
                    return new WorkspaceContext.Binding(path, conversationId);
                })
                .orElse(null);
    }

    /**
     * Short VCS label for a workspace root — the checked-out git branch (or the first 7 hex of a
     * detached HEAD), read straight from the on-disk refs so linked worktrees resolve too. Null
     * when the folder is not a git checkout or its refs are unreadable: a workspace need not be
     * one, and the UI simply falls back to the bare folder name.
     */
    public static String branchLabel(Path root) {
        try {
            Path gitDir = resolveGitDir(root);
            if (gitDir == null) return null;
            String head = Files.readString(gitDir.resolve("HEAD"), StandardCharsets.UTF_8).strip();
            if (head.startsWith("ref: refs/heads/")) {
                String branch = head.substring("ref: refs/heads/".length()).strip();
                return branch.isEmpty() ? null : branch;
            }
            return head.matches("[0-9a-f]{40}") ? head.substring(0, 7) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** One local branch of the workspace's repository, with the checked-out one flagged. */
    public record BranchInfo(String name, boolean current) {}

    /**
     * Local branches of the workspace root (loose refs plus packed-refs, shared metadata dir for
     * linked worktrees). Null when the root is not a git checkout; an empty list is a repo whose
     * HEAD is unborn (fresh {@code git init}).
     */
    public static List<BranchInfo> listBranches(Path root) {
        Path gitDir = resolveGitDir(root);
        if (gitDir == null) return null;
        String current = headBranch(gitDir);
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        Path heads = commonDir(gitDir).resolve("refs/heads");
        try (java.util.stream.Stream<Path> walk = Files.walk(heads)) {
            walk.filter(Files::isRegularFile)
                    .forEach(file -> names.add(heads.relativize(file).toString()
                            .replace('\\', '/')));
        } catch (IOException ignored) {
            // No loose refs (or unreadable tree) — packed-refs below still carries the list.
        }
        Path packed = commonDir(gitDir).resolve("packed-refs");
        if (Files.isRegularFile(packed)) {
            try {
                for (String line : Files.readAllLines(packed, StandardCharsets.UTF_8)) {
                    if (line.startsWith("#") || line.startsWith("^")) continue;
                    int space = line.indexOf(' ');
                    if (space <= 0) continue;
                    String ref = line.substring(space + 1).strip();
                    if (ref.startsWith("refs/heads/")) names.add(ref.substring("refs/heads/".length()));
                }
            } catch (IOException ignored) {
                // Unparseable packed-refs: the loose refs above are still authoritative.
            }
        }
        return names.stream().map(name -> new BranchInfo(name, name.equals(current))).toList();
    }

    /** Git ref-name shape we are willing to hand to {@code git switch}; {@code ..}, {@code //},
     *  leading dashes and friends are all rejected before a process is ever spawned. */
    private static final java.util.regex.Pattern BRANCH_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}");
    private static final long SWITCH_TIMEOUT_SECONDS = 60;
    /** In-flight merge/rebase/… markers, probed directly in the worktree's git dir (upstream
     *  resolves them via {@code git rev-parse --git-path}; these markers are per-worktree, so
     *  the git dir is exactly where that lands). */
    private static final List<String> OPERATION_MARKERS = List.of(
            "MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "REBASE_HEAD",
            "rebase-merge", "rebase-apply", "BISECT_LOG");

    /** One classified blocker of a branch mutation; codes and messages mirror the upstream
     *  agent's wire contract so the UI can map each to an i18n string. */
    public record BranchIssue(String code, String message, java.util.List<String> paths, String detail) {}

    /** Result of a switch/create-and-switch attempt (never thrown for git-level refusals —
     *  those are data, surfaced as issues for the picker to render). */
    public record BranchMutation(boolean ok, String action, String branchName, boolean didChange,
            boolean created, String headRefType, String branchLabel, java.util.List<BranchIssue> issues) {

        static BranchMutation failure(String action, String branchName, String headRefType,
                String branchLabel, java.util.List<BranchIssue> issues) {
            return new BranchMutation(false, action, branchName, false, false, headRefType, branchLabel, issues);
        }
    }

    /**
     * Switch the workspace root to a local branch — or with {@code create}, {@code git switch -c}
     * a new branch off HEAD and move to it (argv array only, strict ref-name shape, and for
     * plain switches the branch must be one {@link #listBranches} knows: a name can never
     * smuggle options or a path). Git-level refusals (dirty-tree overwrite, branch held by
     * another worktree, …) come back as classified {@link BranchIssue}s, not exceptions; only
     * environment failures (no git binary, timeout) throw.
     */
    public static BranchMutation switchBranch(Path root, String targetBranch, boolean create) {
        Path gitDir = resolveGitDir(root);
        if (gitDir == null) {
            throw new IllegalArgumentException("Workspace is not a git repository");
        }
        String action = create ? "create-and-switch" : "switch";
        String normalized = targetBranch == null ? "" : targetBranch.strip();
        String rawHead = rawHead(gitDir);
        String headRefType = rawHead != null && rawHead.startsWith("ref:") ? "branch" : "detached";
        String branchName = headBranch(gitDir);
        String label = branchLabel(root);

        if (normalized.isEmpty() || !BRANCH_NAME.matcher(normalized).matches()
                || normalized.contains("..") || normalized.contains("//") || normalized.endsWith("/lock")) {
            return BranchMutation.failure(action, normalized, headRefType, label, List.of(
                    new BranchIssue("invalid-branch-name", "Branch name is invalid.", null, null)));
        }
        java.util.List<BranchInfo> branches = listBranches(root);
        if (create && branches != null
                && branches.stream().anyMatch(info -> info.name().equals(normalized))) {
            return BranchMutation.failure(action, normalized, headRefType, label, List.of(
                    new BranchIssue("branch-already-exists", "Branch already exists.", null, null)));
        }
        if (!create && "branch".equals(headRefType) && normalized.equals(branchName)) {
            // Already there: a success without didChange, so the UI skips a pointless refresh.
            return new BranchMutation(true, action, normalized, false, false, headRefType, label, List.of());
        }
        if (OPERATION_MARKERS.stream().anyMatch(marker -> Files.exists(gitDir.resolve(marker)))) {
            return BranchMutation.failure(action, normalized, headRefType, label, List.of(
                    new BranchIssue("operation-in-progress", "Another Git operation is still in progress.", null, null)));
        }

        java.util.List<String> command = new java.util.ArrayList<>(
                List.of("git", "-C", root.toString(), "switch", "--no-guess"));
        if (create) {
            command.add("-c");
        }
        command.add(normalized);
        GitRun run = runGit(root, command);

        if (run.timedOut()) {
            throw new IllegalArgumentException("Branch switch timed out");
        }
        if (run.exit() != 0) {
            return BranchMutation.failure(action, normalized, headRefType, label,
                    classifySwitchFailure(run.stdout(), run.stderr()));
        }
        String nextType = rawHead(gitDir) != null && rawHead(gitDir).startsWith("ref:") ? "branch" : "detached";
        return new BranchMutation(true, action, normalized, true, create, nextType,
                branchLabel(root), List.of());
    }

    /** stdout/stderr of one finished (or timed-out) git invocation. */
    private record GitRun(int exit, String stdout, String stderr, boolean timedOut) {}

    private static GitRun runGit(Path root, java.util.List<String> command) {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(root.toFile());
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "git executable not found on this machine: " + e.getMessage());
        }
        // Both pipes must drain concurrently or a chatty git deadlocks on a full pipe.
        java.util.concurrent.CompletableFuture<String> stdout = java.util.concurrent.CompletableFuture
                .supplyAsync(() -> readQuietly(process.getInputStream()));
        java.util.concurrent.CompletableFuture<String> stderr = java.util.concurrent.CompletableFuture
                .supplyAsync(() -> readQuietly(process.getErrorStream()));
        boolean finished;
        try {
            finished = process.waitFor(SWITCH_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Branch switch was interrupted");
        }
        if (!finished) {
            process.destroyForcibly();
            return new GitRun(-1, join(stdout), join(stderr), true);
        }
        return new GitRun(process.exitValue(), join(stdout), join(stderr), false);
    }

    private static String readQuietly(java.io.InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String join(java.util.concurrent.CompletableFuture<String> future) {
        try {
            return future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Turns a failed {@code git switch} into stable issue codes. Ported verbatim from the
     * upstream agent's classifier: the indented path list under the two overwrite headers is
     * extracted so the UI can name the blocked files; everything else matches on stable
     * stderr phrases rather than git's chatty full output.
     */
    private static java.util.List<BranchIssue> classifySwitchFailure(String stdout, String stderr) {
        String stderrTrimmed = stderr == null ? "" : stderr.strip();
        String stdoutTrimmed = stdout == null ? "" : stdout.strip();
        String detail = !stderrTrimmed.isEmpty() ? stderrTrimmed
                : (!stdoutTrimmed.isEmpty() ? stdoutTrimmed : null);
        String[] lines = detail == null ? new String[0]
                : detail.replace("\r\n", "\n").split("\n", -1);
        String lower = detail == null ? "" : detail.toLowerCase(java.util.Locale.ROOT);

        java.util.List<String> tracked = extractIndentedPaths(lines,
                "your local changes to the following files would be overwritten by (checkout|switch)");
        if (!tracked.isEmpty()) {
            return List.of(new BranchIssue("tracked-changes-would-be-overwritten",
                    "Tracked changes would be overwritten by switching branches.", tracked, detail));
        }
        java.util.List<String> untracked = extractIndentedPaths(lines,
                "the following untracked working tree files would be overwritten by (checkout|switch)");
        if (!untracked.isEmpty()) {
            return List.of(new BranchIssue("untracked-changes-would-be-overwritten",
                    "Untracked files would be overwritten by switching branches.", untracked, detail));
        }
        if (lower.contains("already exists")) {
            return List.of(new BranchIssue("branch-already-exists", "Branch already exists.", null, detail));
        }
        if (lower.contains("invalid reference:")) {
            return List.of(new BranchIssue("target-branch-not-found", "Target branch was not found.", null, detail));
        }
        if (lower.contains("is already used by worktree at")) {
            return List.of(new BranchIssue("branch-in-other-worktree",
                    "Branch is already checked out in another worktree.", null, detail));
        }
        if (lower.contains("resolve your current index first")) {
            return List.of(new BranchIssue("conflicts-present",
                    "Repository still has unresolved conflicts.", null, detail));
        }
        if (java.util.regex.Pattern
                .compile("cannot switch branch while (merging|rebasing|cherry-picking|reverting|bisecting)")
                .matcher(lower).find()
                || lower.contains("you have not concluded your merge")
                || lower.contains("rebase in progress")) {
            return List.of(new BranchIssue("operation-in-progress",
                    "Another Git operation is still in progress.", null, detail));
        }
        return List.of(new BranchIssue("unknown", "Git could not complete the branch operation.", null, detail));
    }

    /** The indented file list beneath a header line (git prints blocked paths one-per-line,
     *  tab-indented; the list ends at the first non-indented non-empty line). */
    private static java.util.List<String> extractIndentedPaths(String[] lines, String headerRegex) {
        java.util.regex.Pattern header = java.util.regex.Pattern.compile(headerRegex);
        int headerIndex = -1;
        for (int index = 0; index < lines.length; index++) {
            if (header.matcher(lines[index].toLowerCase(java.util.Locale.ROOT)).find()) {
                headerIndex = index;
                break;
            }
        }
        if (headerIndex < 0) return List.of();
        java.util.List<String> paths = new java.util.ArrayList<>();
        for (int index = headerIndex + 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.isEmpty()) continue;
            if (!line.matches("\\s+.*")) break;
            String value = line.strip();
            if (!value.isEmpty()) paths.add(value);
        }
        return paths;
    }

    /** HEAD's raw first line ("ref: refs/heads/x" or a sha); null when unreadable. */
    private static String rawHead(Path gitDir) {
        try {
            return Files.readString(gitDir.resolve("HEAD"), StandardCharsets.UTF_8).strip();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** "branch" or "detached" for a git workspace root; null when not a git checkout. */
    public static String headRefType(Path root) {
        Path gitDir = resolveGitDir(root);
        if (gitDir == null) return null;
        String head = rawHead(gitDir);
        return head != null && head.startsWith("ref:") ? "branch" : "detached";
    }

    /** The metadata dir a root's refs live under — itself for plain repos; for a linked worktree
     *  (gitdir {@code <main>/.git/worktrees/<n>}) the shared {@code <main>/.git}. */
    private static Path commonDir(Path gitDir) {
        Path parent = gitDir.getParent();
        if (parent != null && "worktrees".equals(String.valueOf(parent.getFileName()))) {
            Path mainGit = parent.getParent();
            if (mainGit != null) return mainGit;
        }
        return gitDir;
    }

    /** {@code .git} dir for a plain repo, or the pointer target for a linked worktree; null when
     *  the root is not a git checkout (or the pointer is unreadable garbage). */
    private static Path resolveGitDir(Path root) {
        try {
            Path dotGit = root.resolve(".git");
            if (Files.isRegularFile(dotGit)) {
                // Linked worktree / submodule: ".git" carries "gitdir: <path>". Git writes an
                // absolute path; resolving against the root also covers the relative form.
                String pointer = Files.readString(dotGit, StandardCharsets.UTF_8).strip();
                if (!pointer.startsWith("gitdir:")) return null;
                return root.resolve(pointer.substring("gitdir:".length()).strip());
            }
            return Files.isDirectory(dotGit) ? dotGit : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** HEAD's branch name, or null when detached / unborn / unreadable. */
    private static String headBranch(Path gitDir) {
        try {
            String head = Files.readString(gitDir.resolve("HEAD"), StandardCharsets.UTF_8).strip();
            return head.startsWith("ref: refs/heads/")
                    ? head.substring("ref: refs/heads/".length()).strip()
                    : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private ConversationEntity owned(Long conversationId) {
        if (conversationId == null) throw new IllegalArgumentException("conversationId is required");
        return conversations.findByIdAndUserId(conversationId, userId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown conversation: " + conversationId));
    }

    private long userId() {
        Long id = securityContext.currentUserId();
        if (id == null) throw new IllegalStateException("No authenticated user");
        return id;
    }

    private static String expandHome(String path) {
        if (path.equals("~") || path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
