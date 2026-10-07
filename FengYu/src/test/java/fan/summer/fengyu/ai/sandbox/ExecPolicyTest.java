package fan.summer.fengyu.ai.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exec-policy rule engine — a semantic subset mirror of codex
 * {@code execpolicy/tests/basic.rs}: token alternatives, prefix matching, strictest-wins,
 * unmatched-falls-to-prompt, sorted file merging, idempotent amend with banned-prefix
 * refusal, and the builtin whitelist migration staying behavior-equivalent.
 */
class ExecPolicyTest {

    @Test
    void tokenAlternativesMatchAnyOfAtThePosition() throws Exception {
        ExecPolicy policy = new ExecPolicy(ExecPolicy.parseRules(
                "[{\"pattern\":[\"git\",[\"status\",\"diff\"]],\"decision\":\"allow\"}]"));
        assertEquals(ExecPolicy.Decision.ALLOW, policy.decide(List.of("git", "status")));
        assertEquals(ExecPolicy.Decision.ALLOW, policy.decide(List.of("git", "diff")));
        assertEquals(ExecPolicy.Decision.ALLOW, policy.decide(List.of("git", "diff", "--stat")));
        assertEquals(ExecPolicy.Decision.PROMPT, policy.decide(List.of("git", "push")));
        assertEquals(ExecPolicy.Decision.PROMPT, policy.decide(List.of("git")));
    }

    @Test
    void prefixRulesMatchLongerCommandsAndStrictestWins() throws Exception {
        List<ExecPolicy.Rule> rules = ExecPolicy.parseRules("""
                [
                  {"pattern":["mvn"],"decision":"allow"},
                  {"pattern":["mvn","deploy"],"decision":"forbidden"},
                  {"pattern":["mvn","test"],"decision":"allow"}
                ]""");
        ExecPolicy policy = new ExecPolicy(rules);
        assertEquals(ExecPolicy.Decision.ALLOW, policy.decide(List.of("mvn", "verify")));
        assertEquals(ExecPolicy.Decision.FORBIDDEN, policy.decide(List.of("mvn", "deploy", "-Dx")));
        assertEquals(ExecPolicy.Decision.ALLOW, policy.decide(List.of("mvn", "test", "-q")));
    }

    @Test
    void unmatchedFallsToPromptAndEmptyIsPrompt() {
        ExecPolicy builtin = ExecPolicy.builtin();
        assertEquals(ExecPolicy.Decision.PROMPT, builtin.decide(List.of("rm", "-rf", "/")));
        assertEquals(ExecPolicy.Decision.PROMPT, builtin.decide(List.of()));
        assertEquals(ExecPolicy.Decision.PROMPT, builtin.decide(null));
    }

    @Test
    void builtinWhitelistMigrationCoversTheFormerHardcodedSets() {
        ExecPolicy builtin = ExecPolicy.builtin();
        for (String exe : List.of("ls", "cat", "grep", "rg", "jq", "find", "diff", "tree")) {
            assertEquals(ExecPolicy.Decision.ALLOW, builtin.decide(List.of(exe, "-la")),
                    exe + " stays whitelisted");
        }
        assertEquals(ExecPolicy.Decision.ALLOW, builtin.decide(List.of("git", "status", "--short")));
        assertEquals(ExecPolicy.Decision.PROMPT, builtin.decide(List.of("git", "commit", "-m", "x")));
        assertEquals(ExecPolicy.Decision.PROMPT, builtin.decide(List.of("mvn", "test")));
        assertEquals(ExecPolicy.Decision.PROMPT, builtin.decide(List.of("sed", "-i", "s/x/y/", "f")));
    }

    @Test
    void userRulesMergeFromSortedFiles(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("b-rules.json"),
                "[{\"pattern\":[\"docker\"],\"decision\":\"prompt\"}]");
        Files.writeString(dir.resolve("a-rules.json"),
                "[{\"pattern\":[\"mvn\",\"test\"],\"decision\":\"allow\"}]");
        ExecPolicy policy = ExecPolicy.load(dir);
        assertEquals(ExecPolicy.Decision.ALLOW, policy.decide(List.of("mvn", "test")));
        assertEquals(ExecPolicy.Decision.PROMPT, policy.decide(List.of("docker", "ps")));
        // builtin still applies underneath
        assertEquals(ExecPolicy.Decision.ALLOW, policy.decide(List.of("git", "diff")));
        // a forbidden user rule beats the builtin allow
        Files.writeString(dir.resolve("c-rules.json"),
                "[{\"pattern\":[\"git\",\"diff\"],\"decision\":\"forbidden\"}]");
        assertEquals(ExecPolicy.Decision.FORBIDDEN, ExecPolicy.load(dir).decide(List.of("git", "diff")));
    }

    @Test
    void amendIsIdempotentAndRefusesBannedPrefixes(@TempDir Path dir) throws Exception {
        assertTrue(ExecPolicy.amend(dir, List.of("mvn", "test")));
        Path file = dir.resolve("default.rules.json");
        String first = Files.readString(file);
        assertFalse(ExecPolicy.amend(dir, List.of("mvn", "test")), "an existing rule is not duplicated");
        assertEquals(first, Files.readString(file));
        // Shells/interpreters/sudo never become permanent grants.
        assertFalse(ExecPolicy.amend(dir, List.of("bash")));
        assertFalse(ExecPolicy.amend(dir, List.of("python3", "-c")));
        assertFalse(ExecPolicy.amend(dir, List.of("sudo", "apt", "install")));
        assertFalse(Files.readString(file).contains("bash"));
        // The amended rule is live after a reload.
        assertEquals(ExecPolicy.Decision.ALLOW, ExecPolicy.load(dir).decide(List.of("mvn", "test")));
    }

    @Test
    void bannedPrefixesNormalizeBeforeComparisonAndCoverTheCodexSet(
            @org.junit.jupiter.api.io.TempDir Path dir) {
        // The review's P1-5 exploits, all refused now (null dir = refusal before any IO).
        assertFalse(ExecPolicy.amend(null, List.of("rm")));                       // plain rm
        assertFalse(ExecPolicy.amend(null, List.of("rm", "-rf", "x")));
        assertFalse(ExecPolicy.amend(null, List.of("/usr/bin/python3", "-c")));   // absolute path
        assertFalse(ExecPolicy.amend(null, List.of("Bash")));                     // case variant
        assertFalse(ExecPolicy.amend(null, List.of("Git", "push")));
        assertFalse(ExecPolicy.amend(null, List.of("npm", "run", "x")));
        assertFalse(ExecPolicy.amend(null, List.of("git")));
        // A non-banned executable still amends (into the TEMP dir — never the home),
        // stored in its normalized form so it matches plain invocations.
        assertTrue(ExecPolicy.amend(dir, List.of("/usr/local/bin/docker", "ps")));
        assertEquals(ExecPolicy.Decision.ALLOW,
                ExecPolicy.load(dir).decide(List.of("docker", "ps")),
                "the normalized stored rule matches the plain invocation");
    }

    @Test
    void decideNormalizesTheExecutableToken() {
        ExecPolicy builtin = ExecPolicy.builtin();
        assertEquals(ExecPolicy.Decision.ALLOW, builtin.decide(List.of("/usr/bin/git", "status")),
                "an absolute-path git status is still the readonly subcommand");
        assertEquals(ExecPolicy.Decision.PROMPT, builtin.decide(List.of("/usr/bin/git", "push")));
        assertEquals(ExecPolicy.Decision.PROMPT, builtin.decide(List.of("/bin/rm", "-rf", "x")));
    }

    @Test
    void amendablePrefixIsExecutablePlusSubcommandNoFlags() {
        assertEquals(List.of("mvn", "test"), ExecPolicy.amendablePrefix(List.of("mvn", "test", "-q")));
        assertEquals(List.of("docker", "ps"), ExecPolicy.amendablePrefix(List.of("docker", "ps")));
        assertEquals(List.of(), ExecPolicy.amendablePrefix(List.of()));
    }

    /**
     * The P2 amend regression: flags are SKIPPED and the first non-flag operand pins the
     * rule ({@code curl -fsSL <url>} amends to {@code ["curl","<url>"]}) — the old
     * logic froze ["curl"] for a flag-led invocation, and a bare ["curl"] rule
     * blanket-approves every future curl, {@code | sh} pipes included.
     */
    @Test
    void flagLedCommandsAmendOntoTheFirstOperandNeverTheBareExecutable() {
        assertEquals(List.of("curl", "https://example.com/install.sh"),
                ExecPolicy.amendablePrefix(List.of("curl", "-fsSL", "https://example.com/install.sh")));
        assertEquals(List.of("git", "status"),
                ExecPolicy.amendablePrefix(List.of("git", "--no-pager", "status")));
        assertEquals(List.of("make", "build"),
                ExecPolicy.amendablePrefix(List.of("make", "-j", "8", "build")));
        assertEquals(List.of("grep", "pattern"),
                ExecPolicy.amendablePrefix(List.of("grep", "-i", "--color=auto", "pattern", "file")));
    }

    /**
     * A pure-flag invocation has nothing narrow to pin: {@code amendablePrefix} returns
     * empty and {@link ExecPolicy#amend} refuses an empty pattern, so {@code docker -v}
     * can never become a permanent whole-tool grant.
     */
    @Test
    void pureFlagCommandsAreRefusedPersistence(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), ExecPolicy.amendablePrefix(List.of("docker", "-v")));
        assertEquals(List.of(), ExecPolicy.amendablePrefix(List.of("ls", "-la")));
        assertFalse(ExecPolicy.amend(dir, ExecPolicy.amendablePrefix(List.of("docker", "-v"))),
                "an empty pattern never writes a rule");
        assertFalse(Files.exists(dir.resolve("default.rules.json")),
                "nothing was persisted at all");
    }

    /**
     * The AMENDED result re-passes the BANNED check: the prefix that survives flag
     * skipping is still refused when its executable is banned — {@code curl <url>} and
     * the DANGEROUS_VERBS readers must stay per-call approvals, never permanent rules.
     */
    @Test
    void amendRechecksTheBannedPrefixesOnTheAmendedResult(@TempDir Path dir) throws Exception {
        assertFalse(ExecPolicy.amend(dir,
                ExecPolicy.amendablePrefix(List.of("curl", "-fsSL", "https://example.com/x"))));
        assertFalse(ExecPolicy.amend(dir,
                ExecPolicy.amendablePrefix(List.of("cat", "-n", "secrets.env"))));
        assertFalse(ExecPolicy.amend(dir,
                ExecPolicy.amendablePrefix(List.of("npm", "install", "left-pad"))));
        assertFalse(ExecPolicy.amend(dir,
                ExecPolicy.amendablePrefix(List.of("irb", "script.rb"))));
        assertFalse(Files.exists(dir.resolve("default.rules.json")),
                "no banned-prefix rule ever lands in the file");
        // A genuinely narrow non-banned prefix still amends.
        assertTrue(ExecPolicy.amend(dir,
                ExecPolicy.amendablePrefix(List.of("docker", "--verbose", "ps"))));
    }

    /** The banned list covers the bare package managers, fetchers, and secret readers. */
    @Test
    void bannedPrefixesCoverTheBroadenedSet() {
        for (List<String> banned : List.of(
                List.of("npm"), List.of("npx"), List.of("yarn"), List.of("pnpm"),
                List.of("curl"), List.of("wget"), List.of("ssh"), List.of("scp"),
                List.of("tar"), List.of("cp"), List.of("mv"), List.of("mkdir"),
                List.of("chmod"), List.of("kill"), List.of("dd"),
                List.of("cat"), List.of("head"), List.of("tail"), List.of("base64"),
                List.of("java"), List.of("irb"))) {
            assertFalse(ExecPolicy.amend(null, banned), banned.toString());
        }
    }
}
