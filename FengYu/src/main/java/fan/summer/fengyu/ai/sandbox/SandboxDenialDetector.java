package fan.summer.fengyu.ai.sandbox;

import java.util.List;
import java.util.Locale;

/**
 * Heuristic: did the OS sandbox (not the command itself) deny this execution? The port
 * of codex {@code sandboxing/src/denial.rs}, tightened to the fence backends FengYu
 * actually launches: exit 0 is never a denial; the quick-reject exit codes (2 misuse,
 * 126 not-executable, 127 not-found) are the command's own failure, not the fence's.
 *
 * <p>Matching is by the backends' REAL rejection fingerprints, not generic keywords —
 * a bare "permission denied" in the output is the command's own failure (a plain
 * unreadable file) as often as the fence's, and flagging it popped the escape card on
 * ordinary failures. The fingerprints:</p>
 * <ul>
 *   <li>macOS Seatbelt ({@code sandbox-exec}): a log line starting with
 *       {@code Sandbox:} that contains {@code deny} — the kernel's own denial record;</li>
 *   <li>Linux bubblewrap: any output line naming {@code bwrap} (the fence speaks in
 *       first person: {@code bwrap: Can't mount …}), OR one of the classic fence errno
 *       texts ({@code Read-only file system} / {@code Operation not permitted}) — the
 *       caller only consults this detector for runs it launched fenced, so an errno
 *       the fence provably imposed is attributed to the fence;</li>
 *   <li>when the exit code is unavailable the detector does not guess: exit 0 and the
 *       quick-reject codes answer "not a denial" without looking at the output.</li>
 * </ul>
 * False negatives here only mean no escape card — the failed result still reaches the
 * model. False positives pop an approval card for ordinary failures, so the detector
 * stays on the strict side.
 */
public final class SandboxDenialDetector {

    private SandboxDenialDetector() {
    }

    /** Exit codes that mean the COMMAND failed, not the sandbox (codex denial.rs). */
    private static final List<Integer> QUICK_REJECT_EXIT_CODES = List.of(2, 126, 127);

    /** errno texts the fence backends impose on the jailed process's own syscalls. */
    private static final List<String> FENCE_ERRNO_TEXTS = List.of(
            "read-only file system",
            "operation not permitted");

    /** True when the output looks like the sandbox fence denied the command. */
    public static boolean likelyDenied(int exitCode, String output) {
        if (exitCode == 0) return false;
        if (QUICK_REJECT_EXIT_CODES.contains(exitCode)) return false;
        if (output == null || output.isBlank()) return false;
        return denialLine(output) != null;
    }

    /** A one-line excerpt of the denial for the approval card (first matching line). */
    public static String denialExcerpt(String output, int maxChars) {
        String line = denialLine(output);
        if (line == null) return "";
        return line.length() > maxChars ? line.substring(0, maxChars) + "…" : line;
    }

    /**
     * The first output line carrying a fence fingerprint (stripped), or null. One
     * matcher serves both likelyDenied and denialExcerpt so the card always quotes the
     * exact line that tripped the detector.
     */
    private static String denialLine(String output) {
        if (output == null || output.isBlank()) return null;
        for (String line : output.split("\n")) {
            String stripped = line.strip();
            if (stripped.isEmpty()) continue;
            String lower = stripped.toLowerCase(Locale.ROOT);
            // Seatbelt: the kernel's denial record ("Sandbox: proc(pid) deny(…) …").
            if (lower.startsWith("sandbox:") && lower.contains("deny")) {
                return stripped;
            }
            // bubblewrap speaks in first person ("bwrap: Can't mount …: Read-only file
            // system"); the bare errno texts are only trusted because the caller asked
            // about a run it launched through the fence.
            if (lower.contains("bwrap")
                    || FENCE_ERRNO_TEXTS.stream().anyMatch(lower::contains)) {
                return stripped;
            }
        }
        return null;
    }
}
