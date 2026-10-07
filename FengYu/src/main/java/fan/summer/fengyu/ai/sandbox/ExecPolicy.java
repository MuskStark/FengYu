package fan.summer.fengyu.ai.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The exec policy rule engine — the JSON-shaped port of codex {@code execpolicy/}
 * (decision D4: no Starlark; token-sequence semantics are equivalent, the language is
 * just the shell). A rule is
 * {@code {"pattern": ["git", ["status","diff"]], "decision": "allow"}}: the pattern is a
 * command-token prefix, an inner list is an any-of alternative at that position, and the
 * decision is {@code allow} / {@code prompt} / {@code forbidden}. When several rules
 * match, the STRICTEST wins ({@code forbidden} &gt; {@code prompt} &gt; {@code allow});
 * an unmatched command falls back to {@code prompt} — the heuristics floor.
 *
 * <p>Sources merge in order: the built-in default rules (migrated from the former
 * hardcoded readonly whitelist — same semantics, now data) plus every
 * {@code *.json} under {@code ~/.fengyu/ai/rules} sorted by file name. {@link #amend}
 * appends a rule from an approval card's "always allow" and refuses the BANNED prefix
 * suggestions (shells, interpreters, sudo — codex {@code exec_policy.rs:57-146}): those
 * must never become permanent grants.</p>
 */
public final class ExecPolicy {

    private static final Logger log = LoggerFactory.getLogger(ExecPolicy.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final Path DEFAULT_RULES_DIR =
            Path.of(System.getProperty("user.home"), ".fengyu", "ai", "rules");

    public enum Decision { ALLOW, PROMPT, FORBIDDEN }

    /** One pattern position: a literal token, or an any-of alternative set. */
    public sealed interface PatternToken permits Literal, Alternatives {}

    public record Literal(String token) implements PatternToken {}

    /** Any-of at one position: {@code ["status","diff"]} matches either. */
    public record Alternatives(List<String> tokens) implements PatternToken {
        public Alternatives {
            tokens = List.copyOf(tokens);
        }
    }

    public record Rule(List<PatternToken> pattern, Decision decision) {
        public Rule {
            pattern = List.copyOf(pattern);
        }
    }

    private final List<Rule> rules;

    ExecPolicy(List<Rule> rules) {
        this.rules = rules;
    }

    /**
     * The strictest matching decision, or PROMPT when nothing matches (the floor). The
     * executable token is normalized (basename + lowercase) before matching —
     * {@code /usr/bin/git status} and {@code Git} match the same rule as {@code git},
     * so neither spelling escapes the policy (the P1-5 hardening).
     */
    public Decision decide(List<String> commandTokens) {
        if (commandTokens == null || commandTokens.isEmpty()) return Decision.PROMPT;
        List<String> normalized = normalizeTokens(commandTokens);
        Decision strictest = null;
        for (Rule rule : rules) {
            if (!matches(rule.pattern(), normalized)) continue;
            if (strictest == null || rule.decision().ordinal() > strictest.ordinal()) {
                strictest = rule.decision();
            }
        }
        return strictest == null ? Decision.PROMPT : strictest;
    }

    /** Executable → basename + lowercase; subcommands lowercased; flags untouched. */
    static List<String> normalizeTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return List.of();
        List<String> normalized = new ArrayList<>(tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (i == 0) {
                String stripped = token.lastIndexOf('/') >= 0
                        || token.lastIndexOf('\\') >= 0
                        ? substringAfterLastSeparator(token)
                        : token;
                normalized.add(stripped.toLowerCase(Locale.ROOT));
            } else {
                normalized.add(token.startsWith("-") ? token : token.toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    private static String substringAfterLastSeparator(String path) {
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return cut >= 0 && cut + 1 < path.length() ? path.substring(cut + 1) : path;
    }

    /** Prefix semantics: every pattern position matches the command token at that index. */
    static boolean matches(List<PatternToken> pattern, List<String> tokens) {
        if (pattern.size() > tokens.size()) return false;
        for (int i = 0; i < pattern.size(); i++) {
            String token = tokens.get(i);
            if (pattern.get(i) instanceof Literal literal) {
                if (!literal.token().equals(token)) return false;
            } else if (pattern.get(i) instanceof Alternatives alternatives) {
                if (!alternatives.tokens().contains(token)) return false;
            }
        }
        return true;
    }

    // ── loading ─────────────────────────────────────────────────────────────────────────

    /** Builtin defaults + user rules ({@code *.json} sorted by file name). */
    public static ExecPolicy load(Path rulesDir) {
        List<Rule> merged = new ArrayList<>(builtinRules());
        Path dir = rulesDir == null ? DEFAULT_RULES_DIR : rulesDir;
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                    try {
                        merged.addAll(parseRules(Files.readString(file)));
                    } catch (Exception e) {
                        log.warn("skipping unparsable exec-policy rule file {}: {}",
                                file.getFileName(), e.toString());
                    }
                }
            } catch (IOException e) {
                log.debug("exec-policy rules dir unreadable: {}", e.toString());
            }
        }
        return new ExecPolicy(List.copyOf(merged));
    }

    /** The engine with builtin defaults only (no filesystem). */
    public static ExecPolicy builtin() {
        return new ExecPolicy(builtinRules());
    }

    /**
     * The migrated readonly whitelist (behavior-equivalent to the former hardcoded
     * sets in {@code WorkspaceExecTool}): the plain inspection executables, the
     * read-only git subcommands, and {@code find} (whose mutating flags stay vetoed by
     * the structural verifier up in the tool).
     */
    static List<Rule> builtinRules() {
        List<Rule> rules = new ArrayList<>();
        for (String exe : List.of("ls", "pwd", "echo", "cat", "head", "tail", "wc", "find",
                "grep", "rg", "sort", "diff", "file", "stat", "du", "df", "tree", "which",
                "date", "basename", "dirname", "realpath", "md5sum", "sha1sum", "sha256sum",
                "cut", "tr", "column", "jq")) {
            rules.add(new Rule(List.of(new Literal(exe)), Decision.ALLOW));
        }
        rules.add(new Rule(List.of(new Literal("git"), new Alternatives(List.of(
                "status", "diff", "log", "show", "blame", "rev-parse", "ls-files", "ls-tree",
                "shortlog", "describe", "name-rev", "count-objects"))), Decision.ALLOW));
        return rules;
    }

    /** Parses {@code [{"pattern": [...], "decision": "..."}]} with literal/list positions. */
    static List<Rule> parseRules(String json) throws IOException {
        List<?> raw = JSON.readValue(json, List.class);
        List<Rule> rules = new ArrayList<>();
        for (Object entry : raw) {
            if (!(entry instanceof Map<?, ?> ruleMap)) continue;
            Object pattern = ruleMap.get("pattern");
            if (!(pattern instanceof List<?> positions) || positions.isEmpty()) continue;
            List<PatternToken> tokens = new ArrayList<>();
            for (Object position : positions) {
                if (position instanceof List<?> alternatives) {
                    List<String> options = new ArrayList<>();
                    for (Object alternative : alternatives) {
                        if (alternative != null) options.add(String.valueOf(alternative));
                    }
                    tokens.add(new Alternatives(options));
                } else if (position != null) {
                    tokens.add(new Literal(String.valueOf(position)));
                }
            }
            Decision decision = switch (String.valueOf(ruleMap.get("decision"))) {
                case "allow" -> Decision.ALLOW;
                case "forbidden" -> Decision.FORBIDDEN;
                default -> Decision.PROMPT;
            };
            rules.add(new Rule(tokens, decision));
        }
        return rules;
    }

    // ── amend ───────────────────────────────────────────────────────────────────────────

    /**
     * Prefixes that never become permanent grants via the approval card's "always
     * allow" (codex BANNED_PREFIX_SUGGESTIONS: shells, interpreters, package runners,
     * sudo) — amending them would allow arbitrary code by another name.
     *
     * <p>Rule-token matching is a command-token PREFIX: {@code ["curl","<url>"]} would
     * still match {@code curl <url> | sh} (the suffix rides along), so a prefix rule can
     * never be narrow enough for a tool whose own arguments are arbitrary code or
     * arbitrary destinations. Everything {@code ToolPermissionRules.DANGEROUS_VERBS}
     * refuses to auto-approve is therefore banned from PERSISTENCE here too — a one-click
     * approve still works per-call; only the permanent rule is refused.</p>
     */
    static final List<List<String>> BANNED_PREFIXES = List.of(
            List.of("bash"), List.of("sh"), List.of("zsh"), List.of("dash"), List.of("fish"),
            List.of("ksh"), List.of("env"),
            List.of("sudo"), List.of("su"),
            // rm: "always allow rm" is a permanent deletion grant, however harmless the
            // single approved invocation looked (review P1-5).
            List.of("rm"),
            // git as a WHOLE-tool grant is arbitrary-branch surgery (codex bans ["git"]).
            List.of("git"),
            List.of("node"), List.of("nodejs"), List.of("bun"), List.of("deno"),
            // npm/yarn/pnpm run execute arbitrary package scripts; the BARE package
            // managers install arbitrary code — both are permanent arbitrary-code grants.
            List.of("npm"), List.of("npx"), List.of("yarn"), List.of("pnpm"),
            List.of("python"), List.of("python3"), List.of("pythonw"), List.of("py"),
            List.of("pyw"), List.of("pypy"), List.of("pypy3"),
            List.of("perl"), List.of("php"), List.of("ruby"), List.of("irb"),
            List.of("lua"), List.of("julia"), List.of("Rscript"),
            List.of("osascript"), List.of("powershell"), List.of("pwsh"), List.of("cmd"),
            List.of("cmd.exe"), List.of("powershell.exe"), List.of("pwsh.exe"),
            List.of("java"), List.of("javaw"), List.of("dotnet"),
            // Network fetchers: their own arguments pick up and pipe arbitrary payloads
            // (`curl -fsSL url | sh` rides any curl prefix), so they stay per-call.
            List.of("curl"), List.of("wget"), List.of("ssh"), List.of("scp"),
            // Archivers/copy tools reshape the filesystem far beyond one approved
            // invocation's shape (tar overwrite, cp/mv anywhere, mkdir anywhere).
            List.of("tar"), List.of("cp"), List.of("mv"), List.of("mkdir"),
            List.of("xcopy"), List.of("del"),
            // Privilege/permission mutation and process control.
            List.of("chmod"), List.of("chown"), List.of("chgrp"), List.of("chattr"),
            List.of("kill"), List.of("killall"), List.of("pkill"),
            List.of("shutdown"), List.of("reboot"), List.of("mkfs"), List.of("dd"),
            // Local-secret readers (DANGEROUS_VERBS): a permanent grant would silently
            // authorize credential/database/key reading into model output.
            List.of("cat"), List.of("less"), List.of("more"), List.of("head"), List.of("tail"),
            List.of("strings"), List.of("xxd"), List.of("hexdump"), List.of("base64"));

    /**
     * Appends an "always allow" prefix rule to {@code <rulesDir>/default.rules.json}.
     * Idempotent (an equivalent rule is skipped) and refuses BANNED prefixes.
     *
     * <p>Synchronized: two concurrent approvals both read the rule file, each appends its
     * own rule, and the second write silently swallows the first's — the read-modify-write
     * must be one critical section.</p>
     *
     * @return true when the rule file changed.
     */
    public static synchronized boolean amend(Path rulesDir, List<String> pattern) {
        if (pattern == null || pattern.isEmpty()) return false;
        List<String> normalized = normalizeTokens(pattern);
        for (List<String> banned : BANNED_PREFIXES) {
            if (normalized.size() >= banned.size()
                    && normalized.subList(0, banned.size()).equals(banned)) {
                log.info("refusing to amend exec policy with banned prefix {}", banned);
                return false;
            }
        }
        pattern = normalized;
        try {
            Path dir = rulesDir == null ? DEFAULT_RULES_DIR : rulesDir;
            Files.createDirectories(dir);
            Path file = dir.resolve("default.rules.json");
            List<Map<String, Object>> existing = Files.isRegularFile(file)
                    ? new ArrayList<>(JSON.readValue(Files.readString(file),
                            new com.fasterxml.jackson.core.type.TypeReference<
                                    List<Map<String, Object>>>() {}))
                    : new ArrayList<>();
            for (Map<String, Object> rule : existing) {
                if ("allow".equals(rule.get("decision"))
                        && rule.get("pattern") instanceof List<?> positions
                        && positions.equals(pattern)) {
                    return false; // already granted — amend is idempotent
                }
            }
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("pattern", pattern);
            rule.put("decision", "allow");
            existing.add(rule);
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), existing);
            return true;
        } catch (IOException e) {
            log.warn("exec-policy amend failed: {}", e.toString());
            return false;
        }
    }

    /**
     * The command's amendable prefix: the executable plus the FIRST NON-FLAG operand
     * after it — flags are skipped, not frozen into the rule ({@code curl -fsSL <url>}
     * amends to {@code ["curl","<url>"]}, never bare {@code ["curl"]}, whose rule would
     * blanket-approve every future curl invocation including {@code | sh} pipes). A
     * command with no operand after the flags (a pure-flag invocation) has nothing
     * narrow to pin and returns empty — {@link #amend} refuses an empty pattern, so it
     * never becomes a persistent grant.
     */
    public static List<String> amendablePrefix(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return List.of();
        List<String> normalized = normalizeTokens(tokens);
        String executable = normalized.get(0);
        String previous = null;
        for (int i = 1; i < normalized.size(); i++) {
            String token = normalized.get(i);
            // Flags (short, long, and --key=value shapes) are skipped and the search
            // continues — the operand may sit behind any number of them. A purely
            // numeric token right after a flag is that flag's VALUE (make -j 8), not
            // the noun a persistent grant should pin.
            boolean flagValue = previous != null && previous.startsWith("-") && token.matches("\\d+");
            if (token.startsWith("-") || flagValue) {
                previous = token;
                continue;
            }
            return List.of(executable, token);
        }
        return List.of();
    }

    static String lower(String token) {
        return token == null ? "" : token.toLowerCase(Locale.ROOT);
    }
}
