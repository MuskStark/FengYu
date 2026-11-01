package fan.summer.fengyu.ai.sandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The argv/flag-level readonly classifier — the finer layer under {@link ExecPolicy}'s
 * token-prefix rules. The rule engine decides at COMMAND granularity: a {@code git status}
 * that matches an allow rule never prompts, but everything the rules cannot see
 * ({@code gh pr list}, {@code git branch --list}, {@code git config --get user.email},
 * {@code whoami}) falls to the unmatched PROMPT floor and raises an approval card — even
 * when a human can see at a glance that the invocation only reads. This analyzer closes
 * that gap: given one shell command line it decides, purely statically (lexing, never
 * executing), whether EVERY chain segment is a known read-only invocation, so the floor
 * can upgrade a provably readonly command to ALLOW instead of prompting.
 *
 * <p>Conservative by construction — the fail direction is always "one more approval
 * card", never "one silent mutation":</p>
 * <ul>
 *   <li>chains split on {@code &&}/{@code ||}/{@code |}/{@code ;} and EVERY segment must
 *       independently classify readonly; one unknown segment sinks the whole line;</li>
 *   <li>any redirection ({@code > >> 2> &> <>} — plus input {@code <}, conservatively),
 *       command substitution ({@code $(…)} or a backtick), any {@code $} expansion
 *       (even an inert single-quoted one, matching {@code WorkspaceExecTool}'s
 *       quote-blind {@code UNSAFE_INVOCATION} screen), a lone {@code &}, unquoted
 *       parentheses, or a newline makes the line non-readonly;</li>
 *   <li>{@code sudo} and {@code env} prefixes are refused — their executable position
 *       can never sit on a readonly whitelist because they prefix arbitrary commands —
 *       while {@code timeout [--flags] N <readonly>} passes through to the wrapped
 *       command (it only bounds it);</li>
 *   <li>unknown executables and unknown {@code git}/{@code gh} subcommands are
 *       non-readonly: the analyzer never guesses.</li>
 * </ul>
 *
 * <p>Layering (the order is deliberate, and the analyzer sits at its safest end):
 * catastrophic-command hard floor → PreToolUse hooks → deny/ask/allow permission rules →
 * ExecPolicy rules → this analyzer, only at ExecPolicy's UNMATCHED floor. An explicit
 * rule (allow, prompt, or forbidden — builtin or user-configured) always wins, so
 * nothing the user configured can be upgraded by the analyzer, and a hook veto or deny
 * rule fires strictly earlier in the {@code ToolGuardService} pipeline. After an upgrade
 * the caller's own structural verifier still runs ({@code WorkspaceExecTool}'s flag and
 * operand jail) — this analyzer widens WHICH executables/subcommands may auto-run, never
 * WHAT arguments escape the jail. Read-only-ness is all it judges: path containment
 * (absolute, {@code ~}, {@code ..}, symlink escapes) remains the caller's jail.</p>
 *
 * <p>Stateless by design — contrast {@link ExecPolicy}'s instance form, which exists to
 * carry loaded user rules; everything here is compiled-in knowledge, so it is a static
 * utility class in the {@code ToolApprovalPolicy} style.</p>
 */
public final class ReadonlyCommandAnalyzer {

    private ReadonlyCommandAnalyzer() {}

    /**
     * True when the whole command line is statically provable read-only: every chain
     * segment lexes cleanly, carries no shell behavior the classifier cannot see
     * through (redirection, substitution, expansion, backgrounding), and resolves to a
     * known readonly invocation of the file-read, search, git, or gh families. Anything
     * else — including anything merely unusual — is non-readonly.
     */
    public static boolean isReadonly(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) return false;
        List<String> segments = splitChain(commandLine);
        if (segments == null) return false;
        for (String segment : segments) {
            if (!segmentIsReadonly(segment)) return false;
        }
        return true;
    }

    // ── lexing ─────────────────────────────────────────────────────────────────────────

    /**
     * Quote-aware chain split on {@code &&}/{@code ||}/{@code |}/{@code ;} — quoted
     * separators stay inside their segment ({@code echo "a;b"} is one echo, not a
     * chain). Returns null — the hard reject — when the line carries anything refused
     * wholesale: a redirection character ({@code > >> 2> &> <> <} each contain
     * {@code <} or {@code >}), a lone {@code &} (backgrounding, or the {@code &>}
     * redirect), unquoted parentheses (subshells, process substitution, or plain syntax
     * errors), {@code $} or a backtick (substitution/expansion — opaque even
     * single-quoted, per the {@code UNSAFE_INVOCATION} precedent), or a newline
     * (multi-line scripts are not one analyzable command). Quote characters are kept in
     * the segment text so {@link #tokenize} does the unquoting — the split must not
     * lose the grouping a quoted space depends on.
     */
    static List<String> splitChain(String line) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        int length = line.length();
        int i = 0;
        while (i < length) {
            char c = line.charAt(i);
            if (quote == '\'') {
                // Single quotes: literal until the closing quote — no escapes exist inside.
                if (c == '\'') quote = 0;
                else if (c == '$' || c == '`') return null;
                current.append(c);
                i++;
            } else if (quote == '"') {
                // Inside double quotes only \" and \\ are escapes; \$ and \` are left to
                // the $/backtick rejection below (conservative — they are the shapes the
                // screen exists for).
                if (c == '\\' && i + 1 < length
                        && (line.charAt(i + 1) == '"' || line.charAt(i + 1) == '\\')) {
                    current.append(c).append(line.charAt(i + 1));
                    i += 2;
                } else if (c == '"') {
                    quote = 0;
                    current.append(c);
                    i++;
                } else if (c == '$' || c == '`') {
                    return null;
                } else {
                    current.append(c);
                    i++;
                }
            } else {
                switch (c) {
                    case '\'', '"' -> {
                        quote = c;
                        current.append(c);
                        i++;
                    }
                    case '\\' -> {
                        if (i + 1 >= length) return null; // dangling escape — unreadable
                        current.append(c).append(line.charAt(i + 1));
                        i += 2;
                    }
                    case '&', '|' -> {
                        boolean doubled = i + 1 < length && line.charAt(i + 1) == c;
                        if (c == '&' && !doubled) return null;
                        segments.add(current.toString());
                        current.setLength(0);
                        i += doubled ? 2 : 1;
                    }
                    case ';' -> {
                        segments.add(current.toString());
                        current.setLength(0);
                        i++;
                    }
                    case '<', '>', '(', ')', '$', '`', '\n', '\r' -> {
                        return null;
                    }
                    default -> {
                        current.append(c);
                        i++;
                    }
                }
            }
        }
        if (quote != 0) return null; // unterminated quote — unreadable, refuse
        segments.add(current.toString());
        return segments;
    }

    /**
     * Shell-style word splitting of one chain segment: single/double quotes group (and
     * are dropped), a backslash outside quotes escapes the next character, unquoted
     * whitespace separates. {@link #splitChain} already rejected every metacharacter
     * that could appear here, so this only rebuilds the argv the shell would hand the
     * executable.
     */
    static List<String> tokenize(String segment) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean hasToken = false;
        char quote = 0;
        int length = segment.length();
        int i = 0;
        while (i < length) {
            char c = segment.charAt(i);
            if (quote == '\'') {
                if (c == '\'') quote = 0;
                else token.append(c);
                i++;
            } else if (quote == '"') {
                if (c == '\\' && i + 1 < length
                        && (segment.charAt(i + 1) == '"' || segment.charAt(i + 1) == '\\')) {
                    token.append(segment.charAt(i + 1));
                    i += 2;
                } else if (c == '"') {
                    quote = 0;
                    i++;
                } else {
                    token.append(c);
                    i++;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                hasToken = true; // '' is an empty argument, not no argument
            } else if (c == '\\') {
                if (i + 1 >= length) break; // unreachable post-scanner; defensive
                token.append(segment.charAt(++i));
                hasToken = true;
                i++;
            } else if (c == ' ' || c == '\t') {
                if (hasToken) {
                    tokens.add(token.toString());
                    token.setLength(0);
                    hasToken = false;
                }
                i++;
            } else {
                token.append(c);
                hasToken = true;
                i++;
            }
        }
        if (hasToken) tokens.add(token.toString());
        return tokens;
    }

    // ── segment classification ─────────────────────────────────────────────────────────

    private static boolean segmentIsReadonly(String segment) {
        List<String> tokens = tokenize(segment.trim());
        if (tokens.isEmpty()) return false; // empty segment (trailing ; , && &&) — refuse
        int start = skipTimeoutWrapper(tokens);
        if (start >= tokens.size()) return false; // a wrapper with no command inside
        String executable = basename(tokens.get(start).toLowerCase(Locale.ROOT));
        List<String> args = tokens.subList(start + 1, tokens.size());
        if (!argsPassGlobalChecks(executable, args)) return false;
        return switch (executable) {
            case "git" -> gitReadonly(args);
            case "gh" -> ghReadonly(args);
            default -> plainReadonly(executable, args);
        };
    }

    /**
     * Skips {@code timeout [--flags] [duration]} wrappers — timeout only bounds the
     * wrapped command, so the readonly verdict is the wrapped command's. Wrappers are
     * consumed in a loop ({@code timeout 5 timeout 5 ls} is odd but harmless); every
     * other prefix command ({@code sudo}, {@code env}, {@code nohup} …) gets no
     * pass-through and falls to the unknown-executable refusal below.
     */
    private static int skipTimeoutWrapper(List<String> tokens) {
        int start = 0;
        while (start < tokens.size()
                && "timeout".equals(basename(tokens.get(start).toLowerCase(Locale.ROOT)))) {
            int rest = start + 1;
            while (rest < tokens.size() && (tokens.get(rest).startsWith("-")
                    || TIMEOUT_DURATION.matcher(tokens.get(rest)).matches())) {
                rest++;
            }
            start = rest;
        }
        return start;
    }

    /** A timeout duration operand: {@code 5}, {@code 5s}, {@code 2m}, {@code 1h}. */
    private static final Pattern TIMEOUT_DURATION = Pattern.compile("\\d++[smhd]?");

    /**
     * Shape checks that apply to every family, mirroring the structural verifier up in
     * {@code WorkspaceExecTool} so a standalone verdict never disagrees with the tool's:
     * a {@code flag=value} spelling is opaque (the value side can name an output path —
     * {@code git diff --output=/any/x}), and the output-file flags of the
     * output-capable executables write files ({@code grep -o} stays a read-only matcher
     * — grep is not in the output-capable set).
     */
    private static boolean argsPassGlobalChecks(String executable, List<String> args) {
        boolean outputCapable = OUTPUT_FLAG_EXECUTABLES.contains(executable);
        for (String arg : args) {
            if (arg.startsWith("-") && arg.indexOf('=') >= 0) return false;
            if (outputCapable && OUTPUT_FLAGS.contains(arg)) return false;
        }
        return true;
    }

    /** Scoped per executable — a deliberate mirror of {@code WorkspaceExecTool}'s private set. */
    private static final Set<String> OUTPUT_FLAG_EXECUTABLES = Set.of("git", "diff", "tree", "sort");
    private static final Set<String> OUTPUT_FLAGS = Set.of(
            "--output", "--output-indicator-context", "--output-indicator-new",
            "--output-indicator-old", "-o");

    // ── plain executables ──────────────────────────────────────────────────────────────

    /**
     * The always-readonly executables: the file-read family from the task contract
     * ({@code less more type whoami id groups hostname uname true false printf …}) union
     * the {@link ExecPolicy} builtin whitelist ({@code ls cat grep rg find jq …}) —
     * their flags are vetted by the global checks plus the per-executable vetoes below.
     */
    private static final Set<String> READONLY_EXECUTABLES = Set.of(
            "ls", "pwd", "echo", "printf", "cat", "head", "tail", "less", "more", "wc",
            "find", "grep", "egrep", "fgrep", "rg", "sort", "diff", "file", "stat", "du",
            "df", "tree", "which", "type", "whoami", "id", "groups", "hostname", "uname",
            "date", "true", "false", "basename", "dirname", "realpath", "md5sum", "sha1sum",
            "sha256sum", "cut", "tr", "column", "jq");

    private static boolean plainReadonly(String executable, List<String> args) {
        if (!READONLY_EXECUTABLES.contains(executable)) return false;
        // date -s/--set writes the system clock; every other date form just prints.
        if ("date".equals(executable) && (args.contains("-s") || args.contains("--set"))) {
            return false;
        }
        // find's mutating flags — an otherwise-readonly find must not carry any of these
        // (the same veto the tool-level structural verifier applies).
        if ("find".equals(executable)) {
            for (String arg : args) {
                if (FIND_MUTATING_FLAGS.contains(arg)) return false;
            }
        }
        // rg --pre <cmd> executes the given command per searched file — arbitrary code
        // execution dressed as a search flag.
        if ("rg".equals(executable)) {
            for (String arg : args) {
                if (arg.startsWith("--pre")) return false;
            }
        }
        return true;
    }

    /** A deliberate mirror of {@code WorkspaceExecTool.FIND_MUTATING_FLAGS}. */
    private static final Set<String> FIND_MUTATING_FLAGS = Set.of(
            "-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0",
            "-fprintf", "-fls");

    // ── git ────────────────────────────────────────────────────────────────────────────

    /** Read-only in every shape — inspection output only, flags vetted by the global checks. */
    private static final Set<String> GIT_READONLY_SUBCOMMANDS = Set.of(
            "status", "diff", "log", "show", "blame", "shortlog", "rev-parse", "describe",
            "ls-files", "ls-tree", "show-ref");

    private static boolean gitReadonly(List<String> args) {
        int i = skipGitGlobalFlags(args);
        if (i >= args.size()) return false; // bare `git` — the tool layer has its own bare-git rule; standalone stays conservative
        String subcommand = args.get(i).toLowerCase(Locale.ROOT);
        List<String> rest = args.subList(i + 1, args.size());
        if (GIT_READONLY_SUBCOMMANDS.contains(subcommand)) return true;
        return switch (subcommand) {
            case "branch" -> gitBranchListOnly(rest);
            case "tag" -> gitTagListOnly(rest);
            case "remote" -> gitRemoteListOnly(rest);
            case "config" -> gitConfigGetOnly(rest);
            // `git reflog` shows the log; delete/expire rewrite it — show-shaped only.
            case "reflog" -> rest.isEmpty() || "show".equals(first(rest));
            // `git stash` STASHES — only the list subcommand reads.
            case "stash" -> "list".equals(first(rest));
            case "worktree" -> "list".equals(first(rest));
            // Unknown or write subcommand (push, commit, checkout, add, …) — never guess.
            default -> false;
        };
    }

    /**
     * Skips git's global pre-subcommand flags: valueless ones ({@code --no-pager},
     * {@code -p}, {@code --literal-pathspecs} …) and the two valued ones ({@code -c
     * <cfg>} config override, {@code -C <dir>} working directory). A {@code -C} path
     * that escapes the workspace is the caller's jail to catch (the tool layer's
     * operand checks reject {@code ..} operands); flag=value spellings were already
     * refused by the global checks.
     */
    private static int skipGitGlobalFlags(List<String> args) {
        int i = 0;
        while (i < args.size()) {
            String arg = args.get(i);
            if ("-c".equals(arg) || "-C".equals(arg)) {
                i += 2;
            } else if (arg.startsWith("-")) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    /** {@code git branch} list-modifier flags — the shapes that only print refs. */
    private static final Set<String> GIT_BRANCH_LIST_FLAGS = Set.of(
            "-l", "--list", "-r", "--remotes", "-a", "--all", "-v", "-vv", "--verbose",
            "-q", "--quiet", "--show-current", "--no-color", "-i", "--ignore-case");

    /**
     * {@code git branch} readonly shapes ONLY: bare (list), {@code --list [pattern]},
     * or list-modifier flags ({@code git branch -r}). A free operand without
     * {@code --list} CREATES a ref ({@code git branch feature}); {@code -d/-D/-m}
     * mutate — the closed flag set refuses both.
     */
    private static boolean gitBranchListOnly(List<String> args) {
        if (args.isEmpty()) return true;
        String first = args.get(0);
        if (first.equals("--list") || first.equals("-l")) return true; // a pattern may follow
        for (String arg : args) {
            if (!GIT_BRANCH_LIST_FLAGS.contains(arg)) return false;
        }
        return true;
    }

    /** {@code git tag} readonly shapes ONLY: bare, or {@code -l/--list [pattern]}. */
    private static boolean gitTagListOnly(List<String> args) {
        return args.isEmpty() || args.get(0).equals("-l") || args.get(0).equals("--list");
    }

    /**
     * {@code git remote} readonly shapes ONLY: bare, {@code -v/--verbose}, or
     * {@code get-url <name>} — {@code add/remove/set-url} mutate the configuration.
     */
    private static boolean gitRemoteListOnly(List<String> args) {
        if (args.isEmpty()) return true;
        String first = args.get(0);
        return first.equals("-v") || first.equals("--verbose") || first.equals("get-url");
    }

    /** Display-only modifiers allowed after {@code git config --list}. */
    private static final Set<String> GIT_CONFIG_LIST_SCOPES = Set.of(
            "--local", "--global", "--system", "--show-origin", "--show-scope", "--name-only");

    /**
     * {@code git config} readonly shapes ONLY: {@code --list [scopes]} and
     * {@code --get/--get-all/--get-regexp <name>} with exactly one name operand. The
     * implicit-get spelling ({@code git config user.name}) and every writing flag
     * ({@code --unset}, {@code --edit}, {@code --add}, …) stay per-call approvals —
     * {@code --file <path>} is refused too (it can point the read anywhere).
     */
    private static boolean gitConfigGetOnly(List<String> args) {
        if (args.isEmpty()) return false;
        String first = args.get(0);
        if (first.equals("-l") || first.equals("--list")) {
            return args.subList(1, args.size()).stream()
                    .allMatch(GIT_CONFIG_LIST_SCOPES::contains);
        }
        if (first.equals("--get") || first.equals("--get-all")
                || first.equals("--get-regexp")) {
            return args.size() == 2;
        }
        return false;
    }

    // ── gh ─────────────────────────────────────────────────────────────────────────────

    private static boolean ghReadonly(List<String> args) {
        int i = 0;
        // Global flags before the subcommand; a VALUED one (-R owner/repo) leaves its
        // value in the subcommand position → unknown → prompt (conservative).
        while (i < args.size() && args.get(i).startsWith("-")) i++;
        if (i >= args.size()) return false;
        String subcommand = args.get(i).toLowerCase(Locale.ROOT);
        List<String> rest = args.subList(i + 1, args.size());
        String subject = first(rest).toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "status" -> true;
            case "pr" -> Set.of("list", "view", "diff").contains(subject);
            case "issue" -> Set.of("list", "view").contains(subject);
            case "run" -> Set.of("list", "view", "watch").contains(subject);
            case "release" -> Set.of("list", "view").contains(subject);
            case "repo" -> "view".equals(subject);
            case "label" -> "list".equals(subject);
            case "milestone" -> "list".equals(subject);
            case "search" -> Set.of("repos", "issues", "prs", "code", "commits")
                    .contains(subject);
            // gh api defaults to GET but can mutate on any other verb — only an
            // EXPLICIT GET is trusted (the --method=GET spelling is refused by the
            // global flag=value check).
            case "api" -> ghApiExplicitGet(rest);
            // Unknown subcommand, or a write form (pr create, repo clone, run rerun,
            // release create …) — never guess.
            default -> false;
        };
    }

    /** True only when {@code --method GET} / {@code -X GET} appears explicitly. */
    private static boolean ghApiExplicitGet(List<String> rest) {
        for (int i = 0; i + 1 < rest.size(); i++) {
            if (("--method".equals(rest.get(i)) || "-X".equals(rest.get(i)))
                    && "GET".equalsIgnoreCase(rest.get(i + 1))) {
                return true;
            }
        }
        return false;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────

    private static String first(List<String> args) {
        return args.isEmpty() ? "" : args.get(0);
    }

    /** Executable → basename (POSIX or Windows separators), mirroring ExecPolicy's normalization. */
    private static String basename(String word) {
        int cut = Math.max(word.lastIndexOf('/'), word.lastIndexOf('\\'));
        return cut >= 0 && cut + 1 < word.length() ? word.substring(cut + 1) : word;
    }
}
