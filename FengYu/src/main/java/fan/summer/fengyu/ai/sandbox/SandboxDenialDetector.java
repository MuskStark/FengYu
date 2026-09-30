package fan.summer.fengyu.ai.sandbox;

import java.util.List;
import java.util.Locale;

/**
 * Heuristic: did the OS sandbox (not the command itself) deny this execution? The port
 * of codex {@code sandboxing/src/denial.rs}: exit 0 is never a denial; the quick-reject
 * exit codes (2 misuse, 126 not-executable, 127 not-found) are the command's own
 * failure, not the fence's; anything else whose output carries one of the sandbox
 * denial keywords is treated as a fence denial. False positives are safe here — they
 * only route into the escape-approval path, never auto-escalate.
 */
public final class SandboxDenialDetector {

    private SandboxDenialDetector() {
    }

    /** Exit codes that mean the COMMAND failed, not the sandbox (codex denial.rs). */
    private static final List<Integer> QUICK_REJECT_EXIT_CODES = List.of(2, 126, 127);

    /** stderr/stdout markers of an OS-sandbox denial (codex SANDBOX_DENIED_KEYWORDS). */
    private static final List<String> DENIED_KEYWORDS = List.of(
            "operation not permitted",
            "permission denied",
            "read-only file system",
            "seccomp",
            "sandbox",
            "landlock",
            "failed to write file");

    /** True when the output looks like the sandbox fence denied the command. */
    public static boolean likelyDenied(int exitCode, String output) {
        if (exitCode == 0) return false;
        if (QUICK_REJECT_EXIT_CODES.contains(exitCode)) return false;
        if (output == null || output.isBlank()) return false;
        String lower = output.toLowerCase(Locale.ROOT);
        return DENIED_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /** A one-line excerpt of the denial for the approval card (first matching line). */
    public static String denialExcerpt(String output, int maxChars) {
        if (output == null || output.isBlank()) return "";
        for (String line : output.split("\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (DENIED_KEYWORDS.stream().anyMatch(lower::contains)) {
                String trimmed = line.strip();
                return trimmed.length() > maxChars ? trimmed.substring(0, maxChars) + "…" : trimmed;
            }
        }
        return "";
    }
}
