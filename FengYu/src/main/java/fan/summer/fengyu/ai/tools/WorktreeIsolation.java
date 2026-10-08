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
    /** Output cap for one git invocation — huge porcelain listings truncate, never OOM. */
    static final int MAX_GIT_OUTPUT_BYTES = 2 * 1024 * 1024;

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
        return parseStatusLines(git(worktree, "status", "--porcelain"));
    }

    /**
     * Porcelain rows from the MERGED stdout+stderr stream {@link #git} returns: git writes
     * exit-0 warnings ("refname 'HEAD' is ambiguous", CRLF notices, trace output) to stderr,
     * and a warning line must never read as a phantom change — keep only rows shaped
     * {@code XY<space>path}.
     */
    static List<String> parseStatusLines(String mergedOut) {
        List<String> lines = new ArrayList<>();
        for (String line : mergedOut.split("\n")) {
            if (!line.isBlank() && isPorcelainRow(line)) lines.add(line.strip());
        }
        return lines;
    }

    /** Porcelain rows are exactly {@code XY<space>path} with X/Y status chars or blanks. */
    private static boolean isPorcelainRow(String line) {
        return line.length() >= 3 && line.charAt(2) == ' '
                && isStatusColumn(line.charAt(0)) && isStatusColumn(line.charAt(1));
    }

    private static boolean isStatusColumn(char c) {
        return c == ' ' || c == '?' || c == '!' || c == '#'
                || (c >= 'A' && c <= 'Z');
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
                // One merged stream, drained as it arrives: reading stdout and then stderr
                // SEQUENTIALLY deadlocks when the child fills the pipe we are not reading
                // (git progress goes to stderr) while stdout stays open.
                .redirectErrorStream(true);
        Process process = builder.start();
        process.getOutputStream().close();
        String output;
        try (var merged = process.getInputStream()) {
            output = readAtMost(merged);
        }
        if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("git " + args[0] + " timed out after " + GIT_TIMEOUT_SECONDS + "s");
        }
        if (process.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (exit "
                    + process.exitValue() + "): " + output.strip());
        }
        return output;
    }

    /** Drains up to {@link #MAX_GIT_OUTPUT_BYTES} bytes; anything beyond is dropped with a
     *  marker (keeps draining first, so the child never blocks on a full pipe). */
    static String readAtMost(java.io.InputStream input) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        boolean overflow = false;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total <= MAX_GIT_OUTPUT_BYTES) {
                bytes.write(buffer, 0, read);
            } else {
                overflow = true;
            }
        }
        String text = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        return overflow
                ? text + "\n…[git output truncated at " + MAX_GIT_OUTPUT_BYTES + " bytes]"
                : text;
    }
}
