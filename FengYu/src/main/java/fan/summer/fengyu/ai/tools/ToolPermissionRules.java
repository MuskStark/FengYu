package fan.summer.fengyu.ai.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * User-configurable permission rules for AI tool calls, evaluated before the coarse
 * permission-mode default. The rule grammar and evaluation order follow the model
 * validated in terminal-agent practice (grok-build's permission rules):
 *
 * <ul>
 *   <li><b>Order-independent precedence {@code deny > ask > allow}</b> — a deny always
 *       wins over any allow, regardless of declaration order.</li>
 *   <li><b>Command chains are checked per segment</b> — deny/ask rules match ANY segment
 *       of a {@code &&}/{@code ||}/{@code ;}/{@code |} chain, while an allow rule only
 *       grants when EVERY segment independently matches (so {@code Command(git status)}
 *       cannot authorize {@code git status && rm -rf /}).</li>
 *   <li><b>A dangerous-command floor</b> — commands like {@code rm}, {@code sudo} or
 *       {@code git push} are never auto-approved by an allow rule; they still ask.</li>
 * </ul>
 *
 * <h2>Rule grammar</h2>
 * <pre>
 *   allow = ["Command(git status)", "Tool(excel_*)", "Effect(read)", "Mcp(github__*)"]
 *   ask   = ["Command(git push*)"]
 *   deny  = ["Tool(computer_*)", "WebFetch(domain:internal.example.com)"]
 * </pre>
 * <ul>
 *   <li>{@code Command(pattern)} — matches the {@code execute_command} tool. A trailing
 *       {@code :*} is the prefix idiom ({@code Command(git:*)} → prefix {@code git});
 *       otherwise the pattern matches by word-boundary prefix or glob.</li>
 *   <li>{@code Tool(name-glob)} — matches a tool by (glob) name, e.g. {@code Tool(excel_*)},
 *       {@code Tool(browser_navigate)}. {@code Bash(...)} is accepted as an alias of
 *       {@code Command(...)}.</li>
 *   <li>{@code Effect(read|write|command|external)} — matches every tool declaring that
 *       effect (FengYu's native tool-effect dimension).</li>
 *   <li>{@code Mcp(server__tool)} / {@code Mcp(server__*)}/{@code mcp__server} — matches
 *       MCP tools by qualified name.</li>
 *   <li>{@code WebFetch(domain:host)} — matches {@code web_fetch}/{@code web_search} on a
 *       host or subdomain; {@code WebFetch(glob)} otherwise.</li>
 *   <li>Bare names ({@code "Tool"}, {@code "Command"}) are tool-wide rules.</li>
 * </ul>
 *
 * Pure static code — no Spring, no I/O — so the whole grammar and evaluation order is
 * unit-testable in isolation.
 */
public final class ToolPermissionRules {

    private ToolPermissionRules() {}

    public enum RuleAction { ALLOW, ASK, DENY }

    /** How a rule's pattern is interpreted. */
    public enum PatternMode { GLOB, DOMAIN }

    /** The tool dimension a rule addresses. */
    public enum ToolFilter { ANY, COMMAND, TOOL, EFFECT, MCP, WEB }

    /** One parsed permission rule. */
    public record PermissionRule(RuleAction action, ToolFilter tool, String pattern, PatternMode mode) {}

    /** What a tool call is (the dimensions rules can match against). */
    public record ToolAccess(String toolName, ToolEffect effect, boolean mcpTool,
                             String command, String url) {

        public static ToolAccess of(String toolName, ToolEffect effect) {
            return new ToolAccess(toolName, effect, false, null, null);
        }

        public static ToolAccess command(String command) {
            return new ToolAccess("execute_command", ToolEffect.COMMAND, false, command, null);
        }
    }

    /** Outcome of evaluating the rule set for one tool call. */
    public enum Decision { ALLOW, ASK, DENY }

    public record Evaluation(Decision decision, String reason) {}

    // ── Parsing ────────────────────────────────────────────────────────────

    /** Parses one rule string; returns null with a message in {@code error[0]} when malformed. */
    public static PermissionRule parse(String rule, RuleAction action, String[] error) {
        if (rule == null || rule.isBlank()) {
            error[0] = "empty rule";
            return null;
        }
        String text = rule.trim();
        int open = text.indexOf('(');
        if (open >= 0) {
            String prefix = text.substring(0, open).trim();
            int close = text.lastIndexOf(')');
            if (close <= open) {
                error[0] = "missing closing parenthesis in '" + rule + "'";
                return null;
            }
            String raw = text.substring(open + 1, close).trim();
            ToolFilter filter = filterForPrefix(prefix);
            if (filter == null) {
                error[0] = "unknown rule prefix '" + prefix + "' (expected Command, Tool, Effect, Mcp, WebFetch)";
                return null;
            }
            String pattern = raw.isEmpty() || raw.equals("*") ? null : raw;
            if (filter == ToolFilter.COMMAND && pattern != null && pattern.endsWith(":*")) {
                // `Command(cmd:*)` prefix idiom — a bare prefix for the matcher.
                pattern = pattern.substring(0, pattern.length() - 2);
            }
            if (filter == ToolFilter.EFFECT && pattern != null) {
                String normalized = pattern.toLowerCase(Locale.ROOT);
                if (!Set.of("read", "write", "command", "external").contains(normalized)) {
                    error[0] = "Effect(...) expects read|write|command|external, got '" + pattern + "'";
                    return null;
                }
                pattern = normalized;
            }
            PatternMode mode = PatternMode.GLOB;
            if (filter == ToolFilter.WEB && pattern != null && pattern.startsWith("domain:")) {
                pattern = pattern.substring("domain:".length());
                mode = PatternMode.DOMAIN;
            }
            return new PermissionRule(action, filter, pattern, mode);
        }
        // Bare-prefix forms.
        ToolFilter filter = filterForPrefix(text);
        if (filter != null) return new PermissionRule(action, filter, null, PatternMode.GLOB);
        // `mcp__server[_tool]` spelling.
        if (text.startsWith("mcp__") && text.length() > 5) {
            String rest = text.substring(5);
            String pattern = rest.equals("*") ? null
                    : rest.contains("__") ? rest : rest + "__*";
            return new PermissionRule(action, ToolFilter.MCP, pattern, PatternMode.GLOB);
        }
        // A bare tool name.
        return new PermissionRule(action, ToolFilter.TOOL, text, PatternMode.GLOB);
    }

    private static ToolFilter filterForPrefix(String prefix) {
        return switch (prefix == null ? "" : prefix) {
            case "Command", "Bash", "bash" -> ToolFilter.COMMAND;
            case "Tool" -> ToolFilter.TOOL;
            case "Effect" -> ToolFilter.EFFECT;
            case "Mcp", "MCPTool" -> ToolFilter.MCP;
            case "WebFetch", "WebSearch", "Web" -> ToolFilter.WEB;
            case "*" -> ToolFilter.ANY;
            default -> null;
        };
    }

    /** Parses the three configured lists; invalid entries are skipped into {@code invalid}. */
    public static List<PermissionRule> parseAll(List<String> allow, List<String> ask,
                                                List<String> deny, List<String> invalid) {
        List<PermissionRule> rules = new ArrayList<>();
        collect(deny, RuleAction.DENY, rules, invalid);
        collect(ask, RuleAction.ASK, rules, invalid);
        collect(allow, RuleAction.ALLOW, rules, invalid);
        return rules;
    }

    private static void collect(List<String> entries, RuleAction action,
                                List<PermissionRule> into, List<String> invalid) {
        if (entries == null) return;
        for (String entry : entries) {
            String[] error = new String[1];
            PermissionRule rule = parse(entry, action, error);
            if (rule == null) invalid.add(action.name().toLowerCase(Locale.ROOT) + ": " + error[0]);
            else into.add(rule);
        }
    }

    // ── Evaluation ─────────────────────────────────────────────────────────

    /**
     * Evaluates the rule set against one tool call with {@code deny > ask > allow}
     * precedence. Command chains: deny/ask match any segment; allow must cover every
     * segment and is voided by the dangerous-command floor. Returns null when no rule
     * matches (the caller falls through to the permission-mode default).
     */
    public static Evaluation evaluate(List<PermissionRule> rules, ToolAccess access) {
        if (rules == null || rules.isEmpty() || access == null) return null;
        boolean asked = false;
        boolean allowed = false;
        List<String> segments = access.command() == null ? null : splitChain(access.command());
        for (PermissionRule rule : rules) {
            if (rule.action() == RuleAction.DENY && matches(rule, access, segments)) {
                return new Evaluation(Decision.DENY, denyReason(rule));
            }
        }
        for (PermissionRule rule : rules) {
            if (rule.action() != RuleAction.ASK) continue;
            if (matches(rule, access, segments)) asked = true;
        }
        for (PermissionRule rule : rules) {
            if (rule.action() != RuleAction.ALLOW) continue;
            if (matches(rule, access, segments)) allowed = true;
        }
        if (asked) return new Evaluation(Decision.ASK, null);
        if (allowed) {
            if (segments != null) {
                // Bash allow is conjunctive: every chain segment must be independently
                // covered by an allow rule, and the dangerous-command floor always asks.
                if (!allowCoversChain(rules, segments)) return new Evaluation(Decision.ASK, null);
                if (segments.stream().anyMatch(ToolPermissionRules::isDangerousCommand)) {
                    return new Evaluation(Decision.ASK, null);
                }
            }
            return new Evaluation(Decision.ALLOW, null);
        }
        return null;
    }

    private static String denyReason(PermissionRule rule) {
        String dimension = switch (rule.tool()) {
            case ANY -> "any tool";
            case COMMAND -> "command";
            case TOOL -> "tool";
            case EFFECT -> "effect";
            case MCP -> "mcp";
            case WEB -> "web";
        };
        return rule.pattern() == null
                ? "Denied by permission policy: deny rule on " + dimension
                : "Denied by permission policy: deny rule on " + dimension
                        + " matching \"" + rule.pattern() + "\"";
    }

    private static boolean matches(PermissionRule rule, ToolAccess access, List<String> chainSegments) {
        if (!filterMatches(rule, access)) return false;
        String pattern = rule.pattern();
        if (pattern == null || pattern.equals("*")) return true;
        // The EFFECT filter's pattern IS the effect discriminator — nothing further to match.
        if (rule.tool() == ToolFilter.EFFECT) return true;
        if (rule.tool() == ToolFilter.COMMAND) {
            if (access.command() == null || chainSegments == null) return false;
            // Deny/ask: any matching segment — against BOTH the raw text and the
            // normalized form (wrappers peeled, executable basenamed), so a deny on
            // `Command(rm)` still catches `env rm …` / `/bin/rm …`.
            return chainSegments.stream().anyMatch(segment ->
                    commandMatches(pattern, segment) || commandMatchesNormalized(pattern, segment));
        }
        if (rule.tool() == ToolFilter.WEB) {
            if (access.url() == null && access.toolName() != null
                    && !access.toolName().startsWith("web_")) return false;
            if (rule.mode() == PatternMode.DOMAIN) {
                return access.url() != null && domainMatches(pattern, access.url());
            }
            return globMatches(pattern, access.url() != null ? access.url() : access.toolName());
        }
        if (rule.tool() == ToolFilter.MCP) {
            return access.mcpTool() && globMatches(pattern, access.toolName());
        }
        return globMatches(pattern, access.toolName());
    }

    /** Allow for commands is conjunctive: every chain segment must match the rule set. */
    private static boolean allowCoversChain(List<PermissionRule> rules, List<String> segments) {
        for (String segment : segments) {
            if (isDangerousCommand(segment)) return false;
            boolean covered = false;
            for (PermissionRule rule : rules) {
                if (rule.action() != RuleAction.ALLOW) continue;
                String pattern = rule.pattern();
                covered = switch (rule.tool()) {
                    // Command rules: null pattern covers any command segment.
                    case COMMAND -> pattern == null
                            || commandMatches(pattern, segment)
                            || commandMatchesNormalized(pattern, segment);
                    // A tool-wide or any-tool allow also covers the command tool.
                    case ANY, TOOL -> pattern == null
                            || globMatches(pattern, "execute_command");
                    default -> false;
                };
                if (covered) break;
            }
            if (!covered) return false;
        }
        return true;
    }

    private static boolean filterMatches(PermissionRule rule, ToolAccess access) {
        return switch (rule.tool()) {
            case ANY -> true;
            case COMMAND -> "execute_command".equals(access.toolName());
            case TOOL -> true;
            case EFFECT -> rule.pattern() == null || effectName(access.effect()).equals(rule.pattern());
            case MCP -> access.mcpTool();
            // The WEB filter also carries the URL-bearing browser tools (their targets are
            // extracted by ToolGuardService.accessFor), so WebFetch(domain:...) rules can gate
            // browser navigation to the same hosts (M-8).
            case WEB -> access.toolName() != null && (access.toolName().startsWith("web_")
                    || access.toolName().equals("browser_navigate")
                    || access.toolName().equals("browser_new_tab"));
        };
    }

    private static String effectName(ToolEffect effect) {
        if (effect == null) return "external";
        return effect.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Command pattern semantics (adapted from the word-boundary prefix + freeform glob
     * regime): a pattern without wildcards matches when the command starts with it at a
     * word boundary ({@code git push} matches {@code git push origin} but {@code git}
     * does not match {@code ghostscript}); a pattern with wildcards is a freeform glob.
     */
    /** Pattern match against the normalized segment (wrappers peeled, basename). */
    static boolean commandMatchesNormalized(String pattern, String segment) {
        List<String> words = normalizeSegment(segment);
        if (words == null) return false;
        return commandMatches(pattern, String.join(" ", words));
    }

    static boolean commandMatches(String pattern, String command) {
        String cmd = command.trim();
        String pat = pattern.trim();
        if (cmd.isEmpty() || pat.isEmpty()) return false;
        if (pat.indexOf('*') >= 0 || pat.indexOf('?') >= 0) {
            return globMatches(pat, cmd);
        }
        if (!cmd.startsWith(pat)) return false;
        // Word boundary: the pattern ends at end-of-string, whitespace, or a shell operator.
        return cmd.length() == pat.length()
                || Character.isWhitespace(cmd.charAt(pat.length()))
                || "<>|;&".indexOf(cmd.charAt(pat.length())) >= 0;
    }

    /**
     * Splits a shell chain into its command segments on {@code &&}, {@code ||}, {@code ;}
     * and {@code |}. When a segment cannot be safely decomposed the caller treats the
     * whole command as a single segment (fail-safe: rules then evaluate against the full
     * text, which deny/ask can still match).
     */
    static List<String> splitChain(String command) {
        List<String> parts = new ArrayList<>();
        for (String segment : command.split("&&|\\|\\||[;|]")) {
            if (!segment.isBlank()) parts.add(segment.trim());
        }
        return parts.isEmpty() ? List.of(command.trim()) : parts;
    }

    /**
     * The dangerous-command floor: verbs that an allow rule must not auto-approve.
     * The segment is first normalized — leading {@code VAR=value} assignments and common
     * wrappers ({@code env}, {@code nice}, {@code nohup}, {@code timeout}, …) are peeled,
     * the executable reduces to its basename, and {@code sh|bash -c 'script'} recurses
     * into the script's segments. Anything we cannot parse reliably (command
     * substitution, backticks, subshells) trips the floor — fail-closed to ASK —
     * because an allow rule must never green-light text it does not understand.
     */
    static boolean isDangerousCommand(String segment) {
        List<String> words = normalizeSegment(segment);
        if (words == null) return true; // unparseable → the floor trips
        if (words.isEmpty()) return false;

        String executable = words.get(0);
        if (SHELL_EXECUTABLES.contains(executable)) {
            String script = inlineShellScript(words);
            if (script == null) return true; // sh -c with non-literal script → fail closed
            for (String inner : splitChain(script)) {
                if (isDangerousCommand(inner)) return true;
            }
            return false;
        }
        for (String verb : DANGEROUS_VERBS) {
            if (startsWithWord(String.join(" ", words), verb)) return true;
        }
        return false;
    }

    /**
     * The catastrophic-command HARD floor: unlike {@link #isDangerousCommand} (which only
     * demotes allow rules to ASK), these patterns are denied outright, BEFORE hooks, rules,
     * and the permission-mode default — so no allow rule, no hook {@code allow} decision,
     * and no FULL_ACCESS mode can ever green-light them. Deliberately minimal and
     * false-positive-averse: only commands that destroy a filesystem or raw device with no
     * plausible legitimate use in an AI tool call.
     * <ul>
     *   <li>{@code rm} recursive targeting a filesystem root ({@code /}, {@code /*},
     *       {@code ~}, {@code $HOME}/{@code ${HOME}}, Windows drive roots, …) — options and
     *       operands are collected across the whole invocation, because {@code rm / -rf}
     *       is valid GNU syntax</li>
     *   <li>{@code mkfs} / {@code mkfs.*} — any invocation</li>
     *   <li>{@code dd} with {@code of=/dev/…} — raw-device overwrite</li>
     *   <li>newline chains — a newline separates commands just like {@code ;}, so every
     *       line is scanned ({@code echo safe\nrm -rf /})</li>
     *   <li>unparseable text ({@code ${…}}, {@code $(…)}, backticks, subshells, unbalanced
     *       quotes) is checked against the same destruction signatures on raw tokens; when
     *       it carries none, {@link #isUnverifiableCommand} still blocks auto-approval</li>
     * </ul>
     */
    public static boolean isCatastrophicCommand(String command) {
        if (command == null || command.isBlank()) return false;
        // A newline is a command separator to the shell just like `;` — without the
        // pre-split, `echo safe\nrm -rf /` hides its destructive half inside one
        // unparseable segment (CQ-01).
        for (String line : command.split("[\\r\\n]+")) {
            for (String segment : splitChain(line)) {
                if (segmentIsCatastrophic(segment)) return true;
            }
        }
        return false;
    }

    /**
     * True when the command text cannot be structurally decomposed — newlines, command
     * substitution, variable expansion, subshells, or unbalanced quotes. The catastrophic
     * floor's raw-signature scan still denies recognizable destruction, but any OTHER
     * unverifiable text must never be auto-approved: callers treat this as a mandatory
     * human decision that not even FULL_ACCESS may skip (CQ-01).
     */
    public static boolean isUnverifiableCommand(String command) {
        if (command == null || command.isBlank()) return false;
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) return true;
        for (String segment : splitChain(command)) {
            if (normalizeSegment(segment) == null) return true;
        }
        return false;
    }

    private static boolean segmentIsCatastrophic(String segment) {
        List<String> words = normalizeSegment(segment);
        if (words == null) {
            // Unparseable text cannot be cleared by the structural checks — fail closed on
            // a destruction signature in the raw tokens (`rm -rf ${HOME}`), and leave the
            // signature-free remainder to isUnverifiableCommand's human gate.
            return unparseableSignatureIsCatastrophic(segment);
        }
        if (words.isEmpty()) return false;
        words = stripPrivilegeEscalation(words);
        if (words.isEmpty()) return false;
        String executable = basename(words.get(0));
        if (SHELL_EXECUTABLES.contains(executable)) {
            String script = inlineShellScript(words);
            return script != null && isCatastrophicCommand(script);
        }
        if (executable.equals("rm")) {
            // Options may legitimately follow operands (`rm / -rf` is valid GNU rm), so
            // collect the recursive flag and the root targets across the whole invocation
            // instead of judging in a single left-to-right pass (CQ-01).
            boolean recursive = false;
            boolean rootTarget = false;
            for (int i = 1; i < words.size(); i++) {
                String word = words.get(i);
                if (word.startsWith("-") && word.length() > 1) {
                    if (isRecursiveFlag(word)) recursive = true;
                } else if (isRootTarget(word)) {
                    rootTarget = true;
                }
            }
            return recursive && rootTarget;
        }
        if (executable.equals("mkfs") || executable.startsWith("mkfs.")) return true;
        if (executable.equals("dd")) {
            for (int i = 1; i < words.size(); i++) {
                String operand = words.get(i);
                if (operand.startsWith("--")) operand = operand.substring(2);
                if (operand.startsWith("of=/dev/")) return true;
            }
            return false;
        }
        return false;
    }

    /**
     * Fallback for segments {@link #normalizeSegment} cannot decompose: an order-free scan
     * of the raw whitespace tokens for a destruction signature — {@code rm} combining a
     * recursive flag with a root target, an {@code mkfs} invocation, or {@code dd} aimed
     * at a raw device. Only recognizable destruction trips; signature-free unparseable
     * text stays governed by {@link #isUnverifiableCommand} instead.
     */
    private static boolean unparseableSignatureIsCatastrophic(String segment) {
        boolean sawRm = false, sawRecursive = false, sawRoot = false;
        boolean sawMkfs = false, sawDd = false, sawRawDeviceOutput = false;
        for (String raw : segment.split("\\s+")) {
            String token = stripDelimiters(raw);
            String base = basename(token);
            if (base.equals("rm")) sawRm = true;
            else if (base.equals("mkfs") || base.startsWith("mkfs.")) sawMkfs = true;
            else if (base.equals("dd")) sawDd = true;
            if (isRecursiveFlag(token)) sawRecursive = true;
            if (isRootTarget(token)) sawRoot = true;
            if (token.startsWith("of=/dev/")) sawRawDeviceOutput = true;
        }
        return sawMkfs || (sawDd && sawRawDeviceOutput) || (sawRm && sawRecursive && sawRoot);
    }

    /** A recursive {@code rm} flag — {@code --recursive} or a clustered short flag holding r/R. */
    private static boolean isRecursiveFlag(String token) {
        if ("--recursive".equals(token)) return true;
        return token.length() > 1 && token.charAt(0) == '-' && token.charAt(1) != '-'
                && (token.indexOf('r') >= 0 || token.indexOf('R') >= 0);
    }

    /** Strips shell grouping characters so {@code (rm}, {@code 'rm} and {@code "rm} all read as {@code rm}. */
    private static String stripDelimiters(String token) {
        final String delimiters = "()'\"`";
        int start = 0, end = token.length();
        while (start < end && delimiters.indexOf(token.charAt(start)) >= 0) start++;
        while (end > start && delimiters.indexOf(token.charAt(end - 1)) >= 0) end--;
        return token.substring(start, end);
    }

    /** Drops leading {@code sudo}/{@code doas} (with simple flags) so the real command is examined. */
    private static List<String> stripPrivilegeEscalation(List<String> words) {
        int i = 0;
        while (i < words.size()
                && (basename(words.get(i)).equals("sudo") || basename(words.get(i)).equals("doas"))) {
            i++;
            while (i < words.size() && words.get(i).startsWith("-")) {
                String flag = words.get(i);
                i++;
                if (!flag.startsWith("--") && !flag.contains("=")
                        && i < words.size() && !words.get(i).startsWith("-")) {
                    i++; // the flag's value operand (e.g. `sudo -u root …`)
                }
            }
        }
        return words.subList(i, words.size());
    }

    /** True for operands that erase a filesystem root or drive. */
    private static boolean isRootTarget(String arg) {
        String t = arg.length() > 1 && arg.endsWith("/") ? arg.substring(0, arg.length() - 1) : arg;
        if (t.equals("/") || t.equals("/*") || t.equals("/.") || t.equals("/..")
                || t.equals("~") || t.equals("~/*") || t.equals("$HOME") || t.equals("$HOME/*")
                || t.equals("${HOME}") || t.equals("${HOME}/*")) {
            return true;
        }
        // Windows drive roots: C:\ , C:/ , C: , C:\* , C:/*
        return t.matches("^[A-Za-z]:[\\\\/]?\\*?$");
    }

    private static final List<String> DANGEROUS_VERBS = List.of(
            "rm", "chmod", "chown", "chgrp", "chattr", "sudo", "su", "kill", "killall", "pkill",
            "shutdown", "reboot", "mkfs", "dd", "git push", "git reset", "git clean",
            "curl", "wget", "ssh", "scp", "osascript", "powershell", "cmd", "cmd.exe", "xcopy", "del");

    /** Wrappers peeled before the real executable is examined. */
    private static final Set<String> PEELABLE_WRAPPERS = Set.of(
            "env", "nice", "nohup", "command", "builtin", "exec", "time", "timeout",
            "stdbuf", "setsid", "arch", "watch", "xargs");

    private static final Set<String> SHELL_EXECUTABLES = Set.of(
            "sh", "bash", "zsh", "dash", "ksh", "ash");

    /**
     * Splits a segment into shell words (quote-aware enough for {@code -c 'script'}),
     * peels {@code VAR=value} prefixes and wrapper commands, and basenames the
     * executable. Returns null when the text contains constructs we do not parse
     * (command substitution, backticks, subshells) — callers must treat that as
     * unparseable and fail closed.
     */
    static List<String> normalizeSegment(String segment) {
        if (segment == null) return null;
        String text = segment.trim();
        if (text.isEmpty()) return List.of();
        if (text.contains("`") || text.contains("$(") || text.contains("${")
                || text.contains("(") || text.contains(")") || text.contains("\n")) {
            return null;
        }
        List<String> words = shellWords(text);
        if (words == null) return null; // unbalanced quotes
        int i = 0;
        // Peel leading VAR=value assignments.
        while (i < words.size() && words.get(i).matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
            i++;
        }
        // Peel wrapper commands, consuming their flags and (for env/timeout) value args.
        while (i < words.size()) {
            String word = words.get(i);
            String base = basename(word);
            if (!PEELABLE_WRAPPERS.contains(base)) break;
            i++;
            while (i < words.size() && words.get(i).startsWith("-")) {
                String flag = words.get(i);
                i++;
                // Short flags like `nice -n` or `timeout -k` carry the next token as
                // their value; long `--flag=value` forms embed it already.
                if (!flag.startsWith("--") && !flag.contains("=")
                        && i < words.size() && !words.get(i).startsWith("-")) {
                    i++;
                }
            }
            if ("env".equals(base) || "timeout".equals(base)) {
                // env: skip VAR= assignments; timeout: skip the duration operand.
                while (i < words.size()
                        && ("env".equals(base) && words.get(i).matches("[A-Za-z_][A-Za-z0-9_]*=.*"))) {
                    i++;
                }
                if ("timeout".equals(base) && i < words.size()
                        && words.get(i).matches("[0-9]+[smh]?(\\.[0-9]+)?[smh]?")) {
                    i++;
                }
            }
        }
        if (i >= words.size()) return null; // only wrappers/assignments — nothing to run?
        List<String> normalized = new ArrayList<>(words.subList(i, words.size()));
        normalized.set(0, basename(normalized.get(0)));
        return normalized;
    }

    /** The literal script operand of {@code sh -c '…'}, or null when it is not a literal. */
    private static String inlineShellScript(List<String> normalizedWords) {
        for (int i = 1; i < normalizedWords.size(); i++) {
            if ("-c".equals(normalizedWords.get(i)) && i + 1 < normalizedWords.size()) {
                return normalizedWords.get(i + 1);
            }
        }
        return null;
    }

    /** Basename of a path-like token ({@code /bin/rm} → {@code rm}). */
    private static String basename(String word) {
        int slash = Math.max(word.lastIndexOf('/'), word.lastIndexOf('\\'));
        return slash >= 0 ? word.substring(slash + 1) : word;
    }

    /**
     * Minimal quote-aware word splitter: single/double quotes group words; an unbalanced
     * quote yields null (unparseable). Splits on whitespace outside quotes.
     */
    private static List<String> shellWords(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false, inDouble = false, haveWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && !inDouble) { inSingle = !inSingle; haveWord = true; continue; }
            if (c == '"' && !inSingle) { inDouble = !inDouble; haveWord = true; continue; }
            if (!inSingle && !inDouble && Character.isWhitespace(c)) {
                if (haveWord || current.length() > 0) {
                    words.add(current.toString());
                    current.setLength(0);
                    haveWord = false;
                }
                continue;
            }
            current.append(c);
            haveWord = true;
        }
        if (inSingle || inDouble) return null; // unbalanced quotes
        if (haveWord || current.length() > 0) words.add(current.toString());
        return words;
    }

    private static boolean startsWithWord(String command, String prefix) {
        if (!command.startsWith(prefix)) return false;
        if (command.length() == prefix.length()) return true;
        char next = command.charAt(prefix.length());
        return Character.isWhitespace(next);
    }

    static boolean domainMatches(String domainPattern, String url) {
        String host = hostOf(url);
        if (host == null || host.isEmpty()) return false;
        String pattern = domainPattern.toLowerCase(Locale.ROOT);
        String target = host.toLowerCase(Locale.ROOT);
        return target.equals(pattern) || target.endsWith("." + pattern);
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URI(url.trim()).getHost();
        } catch (Exception malformed) {
            return null;
        }
    }

    // ── Minimal glob (no external deps): '*' any run, '?' single char ───────

    /**
     * Compiled-pattern cache shared by chat, agent runs, and background tasks — a
     * plain LinkedHashMap here was a concurrency bug (6.2). ConcurrentHashMap is
     * lock-free on reads; unbounded growth is bounded in practice by the finite set
     * of rule patterns a user configures.
     */
    private static final Map<String, Pattern> GLOB_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean globMatches(String pattern, String text) {
        if (text == null) return false;
        Pattern compiled = GLOB_CACHE.computeIfAbsent(pattern, ToolPermissionRules::compileGlob);
        return compiled.matcher(text).matches();
    }

    private static Pattern compileGlob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> {
                    if ("\\.[]{}()+^$|".indexOf(c) >= 0) regex.append('\\');
                    regex.append(c);
                }
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    /** Extracts the {@code command} argument from a tool-call JSON args string. Uses the
     *  executor's parser (Jackson) so the guarded value is exactly the bound value. */
    public static String commandFromArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) return null;
        try {
            Map<String, Object> parsed = fan.summer.fengyu.ai.util.JsonHelper.parseObjectStrict(arguments);
            if (parsed != null && parsed.get("command") instanceof String command) {
                return command;
            }
        } catch (Exception ignored) {
            // Not JSON or no command field — rules fall back to whole-string matching.
        }
        return arguments;
    }
}
