package fan.summer.fengyu.ai.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Git-worktree isolation for subagent delegation: a task that will WRITE code can run in
 * its own worktree (a cheap checkout of the current HEAD on a dedicated branch) so it can
 * never trample the user's uncommitted changes in the main workspace. When the subagent
 * finishes with changes, the worktree and branch are KEPT and reported — merging back is a
 * deliberate human/model decision; a worktree that ends clean is removed immediately.
 *
 * <p>All git access is one-shot {@code git -C} invocations with a bounded timeout; nothing
 * here trusts the workspace further than git itself does. Package-private: a seam behind
 * {@code delegate_task}, not a general git API.</p>
 */
final class WorktreeIsolation {

    private static final long GIT_TIMEOUT_SECONDS = 30;
    private static final String BRANCH_PREFIX = "fengyu/task-";

    record Worktree(Path repoRoot, Path root, String branch) {}

    private WorktreeIsolation() {}

    /** The enclosing git repository root, or null when {@code from} is not inside one. */
    static Path repoRoot(Path from) {
        Path dir = from.toAbsolutePath().normalize();
        while (dir != null) {
            if (Files.exists(dir.resolve(".git"))) return dir;
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * Creates a worktree of the repository at HEAD on a fresh
     * {@code fengyu/task-<timestamp>-<rand>} branch under the system temp directory.
     */
    static Worktree create(Path workspaceRoot) throws IOException, InterruptedException {
        Path repo = repoRoot(workspaceRoot);
        if (repo == null) {
            throw new IllegalArgumentException("workspace isolation needs a git repository: "
                    + workspaceRoot + " is not inside one");
        }
        String branch = BRANCH_PREFIX + System.currentTimeMillis() + "-"
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0xffff));
        Path parent = Files.createTempDirectory("fengyu-worktree");
        Path worktree = parent.resolve("wt");
        git(repo, "worktree", "add", "-b", branch, worktree.toString(), "HEAD");
        return new Worktree(repo, worktree, branch);
    }

    /** True when the worktree has any change (staged, unstaged, or untracked) vs HEAD. */
    static boolean hasChanges(Path worktree) throws IOException, InterruptedException {
        return !status(worktree).isEmpty();
    }

    /** {@code git status --porcelain} lines: status code + path, untracked included. */
    static List<String> status(Path worktree) throws IOException, InterruptedException {
        String out = git(worktree, "status", "--porcelain");
        List<String> lines = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (!line.isBlank()) lines.add(line.strip());
        }
        return lines;
    }

    /** One-line {@code git diff --stat HEAD} summary of tracked changes (untracked excluded). */
    static String diffStat(Path worktree) throws IOException, InterruptedException {
        String out = git(worktree, "diff", "--stat", "HEAD");
        String stripped = out.strip();
        return stripped.isEmpty() ? "(no tracked-file changes; see changedFiles for untracked)"
                : stripped;
    }

    /** Removes the worktree and its temp parent directory; prunes stale metadata. */
    static void remove(Worktree worktree) throws IOException, InterruptedException {
        try {
            git(worktree.repoRoot(), "worktree", "remove", "--force", worktree.root().toString());
        } finally {
            git(worktree.repoRoot(), "worktree", "prune");
            Path parent = worktree.root().getParent();
            if (parent != null && Files.isDirectory(parent)) {
                try (var entries = Files.list(parent)) {
                    if (entries.findAny().isEmpty()) Files.deleteIfExists(parent);
                }
            }
        }
    }

    private static String git(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(false);
        Process process = builder.start();
        process.getOutputStream().close();
        String stdout;
        try (var out = process.getInputStream()) {
            stdout = new String(out.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        String stderr;
        try (var err = process.getErrorStream()) {
            stderr = new String(err.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("git " + args[0] + " timed out after " + GIT_TIMEOUT_SECONDS + "s");
        }
        if (process.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (exit "
                    + process.exitValue() + "): " + (stderr.isBlank() ? stdout : stderr).strip());
        }
        return stdout;
    }
}
