package fan.summer.fengyu.ai.workspace;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal unified-diff generator for the workspace file tools' results. Common prefix/suffix
 * lines are trimmed first (a typical edit differs in a small middle), and the remaining middle
 * is diffed with an LCS table; a middle too large for the table degrades to one whole-block
 * hunk instead of an unbounded allocation.
 *
 * <p>Output is GNU-patch-compatible enough for display: {@code --- a/<path>}, {@code +++ b/<path>},
 * and {@code @@ -l,s +l,s @@} hunks with 3 lines of context.
 */
public final class UnifiedDiff {

    private static final int CONTEXT_LINES = 3;
    private static final int MAX_DIFF_LINES = 500;
    /** LCS table ceiling: (a.length + 1) * (b.length + 1) beyond this falls back to one hunk. */
    private static final int MAX_LCS_CELLS = 4_000_000;

    private UnifiedDiff() {}

    /** Empty string when the contents are identical. */
    public static String diff(String displayPath, String before, String after) {
        List<String> a = lines(before);
        List<String> b = lines(after);

        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) prefix++;
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) suffix++;

        // Keep up to CONTEXT_LINES of the common head/tail INSIDE the op stream so hunks carry
        // real context (trimming everything would render context-free hunks).
        int headKeep = Math.min(CONTEXT_LINES, prefix);
        int tailKeep = Math.min(CONTEXT_LINES, suffix);
        int base = prefix - headKeep;

        List<String> aMiddle = a.subList(base, a.size() - suffix + tailKeep);
        List<String> bMiddle = b.subList(base, b.size() - suffix + tailKeep);

        List<char[]> ops = new ArrayList<>(); // ' ' context, '-' removed, '+' added
        List<String> opLines = new ArrayList<>();
        diffMiddle(aMiddle, bMiddle, ops, opLines);
        boolean changed = ops.stream().anyMatch(op -> op[0] != ' ');
        if (!changed) return "";

        StringBuilder out = new StringBuilder();
        out.append("--- a/").append(displayPath).append('\n');
        out.append("+++ b/").append(displayPath).append('\n');
        appendHunks(ops, opLines, base, out);
        return out.toString();
    }

    /** LCS over the trimmed middle; oversized middles become a single delete+add hunk. */
    private static void diffMiddle(List<String> a, List<String> b,
            List<char[]> ops, List<String> opLines) {
        if (a.isEmpty() && b.isEmpty()) return;
        if ((long) (a.size() + 1) * (b.size() + 1) > MAX_LCS_CELLS) {
            for (String line : a) {
                ops.add(new char[] {'-'});
                opLines.add(line);
            }
            for (String line : b) {
                ops.add(new char[] {'+'});
                opLines.add(line);
            }
            return;
        }
        int[][] table = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                table[i][j] = a.get(i).equals(b.get(j))
                        ? table[i + 1][j + 1] + 1
                        : Math.max(table[i + 1][j], table[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                ops.add(new char[] {' '});
                opLines.add(a.get(i));
                i++;
                j++;
            } else if (table[i + 1][j] >= table[i][j + 1]) {
                ops.add(new char[] {'-'});
                opLines.add(a.get(i));
                i++;
            } else {
                ops.add(new char[] {'+'});
                opLines.add(b.get(j));
                j++;
            }
        }
        while (i < a.size()) {
            ops.add(new char[] {'-'});
            opLines.add(a.get(i));
            i++;
        }
        while (j < b.size()) {
            ops.add(new char[] {'+'});
            opLines.add(b.get(j));
            j++;
        }
    }

    private static void appendHunks(List<char[]> ops, List<String> opLines,
            int prefix, StringBuilder out) {
        int index = 0;
        int emittedLines = 0;
        while (index < ops.size()) {
            while (index < ops.size() && ops.get(index)[0] == ' ') index++;
            if (index >= ops.size()) break;

            int changeEnd = index;
            while (changeEnd < ops.size()) {
                if (ops.get(changeEnd)[0] != ' ') {
                    changeEnd++;
                    continue;
                }
                // Stop once a gap of run of context lines exceeds twice the context window.
                int gap = 0;
                int probe = changeEnd;
                while (probe < ops.size() && ops.get(probe)[0] == ' ') {
                    gap++;
                    probe++;
                }
                if (gap > CONTEXT_LINES * 2 || probe >= ops.size()) break;
                changeEnd = probe;
            }

            int start = Math.max(0, index - CONTEXT_LINES);
            int end = Math.min(ops.size(), changeEnd + CONTEXT_LINES);

            int aStart = prefix + 1;
            int bStart = prefix + 1;
            for (int k = 0; k < start; k++) {
                if (ops.get(k)[0] != '+') aStart++;
                if (ops.get(k)[0] != '-') bStart++;
            }
            int aCount = 0;
            int bCount = 0;
            for (int k = start; k < end; k++) {
                if (ops.get(k)[0] != '+') aCount++;
                if (ops.get(k)[0] != '-') bCount++;
            }

            if (emittedLines >= MAX_DIFF_LINES) {
                out.append("…diff truncated…\n");
                return;
            }
            out.append("@@ -").append(aStart).append(',').append(aCount)
                    .append(" +").append(bStart).append(',').append(bCount).append(" @@\n");
            for (int k = start; k < end; k++) {
                if (emittedLines++ >= MAX_DIFF_LINES) {
                    out.append("…diff truncated…\n");
                    return;
                }
                out.append(ops.get(k)[0]).append(opLines.get(k)).append('\n');
            }
            index = end;
        }
    }

    /** JS-style split keeping trailing empties; the trailing artifact of a final newline is dropped. */
    private static List<String> lines(String value) {
        if (value == null || value.isEmpty()) return new ArrayList<>();
        String[] split = value.split("\n", -1);
        List<String> result = new ArrayList<>(split.length);
        for (int index = 0; index < split.length; index++) {
            if (index == split.length - 1 && split[index].isEmpty()) break;
            result.add(split[index].replace("\r", ""));
        }
        return result;
    }
}
