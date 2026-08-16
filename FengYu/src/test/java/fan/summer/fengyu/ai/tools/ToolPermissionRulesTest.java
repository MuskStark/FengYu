package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import fan.summer.fengyu.ai.tools.ToolPermissionRules.Decision;
import fan.summer.fengyu.ai.tools.ToolPermissionRules.Evaluation;
import fan.summer.fengyu.ai.tools.ToolPermissionRules.PermissionRule;
import fan.summer.fengyu.ai.tools.ToolPermissionRules.ToolAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule grammar and evaluation order mirror the semantics validated by terminal-agent
 * practice: order-independent {@code deny > ask > allow}, per-segment chain checks,
 * conjunctive command allows, and a dangerous-command floor allow rules cannot bypass.
 */
class ToolPermissionRulesTest {

    private static List<PermissionRule> rules(List<String> allow, List<String> ask, List<String> deny) {
        return ToolPermissionRules.parseAll(allow, ask, deny, new java.util.ArrayList<>());
    }

    private static Evaluation evaluate(List<PermissionRule> rules, ToolAccess access) {
        return ToolPermissionRules.evaluate(rules, access);
    }

    // ── parsing ────────────────────────────────────────────────────────────

    @Test
    void parsesCommandToolEffectMcpAndWebRules() {
        String[] error = new String[1];
        PermissionRule command = ToolPermissionRules.parse("Command(git status)", ToolPermissionRules.RuleAction.ALLOW, error);
        assertNotNull(command, error[0]);
        assertEquals(ToolPermissionRules.ToolFilter.COMMAND, command.tool());
        assertEquals("git status", command.pattern());

        PermissionRule bashAlias = ToolPermissionRules.parse("Bash(git:*)", ToolPermissionRules.RuleAction.ALLOW, error);
        assertNotNull(bashAlias, error[0]);
        assertEquals(ToolPermissionRules.ToolFilter.COMMAND, bashAlias.tool());
        assertEquals("git", bashAlias.pattern()); // `:*` prefix idiom stripped

        PermissionRule effect = ToolPermissionRules.parse("Effect(read)", ToolPermissionRules.RuleAction.ALLOW, error);
        assertEquals(ToolPermissionRules.ToolFilter.EFFECT, effect.tool());
        assertEquals("read", effect.pattern());

        PermissionRule tool = ToolPermissionRules.parse("Tool(excel_*)", ToolPermissionRules.RuleAction.DENY, error);
        assertEquals(ToolPermissionRules.ToolFilter.TOOL, tool.tool());

        PermissionRule mcp = ToolPermissionRules.parse("mcp__github", ToolPermissionRules.RuleAction.ALLOW, error);
        assertEquals(ToolPermissionRules.ToolFilter.MCP, mcp.tool());
        assertEquals("github__*", mcp.pattern());

        PermissionRule domain = ToolPermissionRules.parse("WebFetch(domain:example.com)", ToolPermissionRules.RuleAction.ALLOW, error);
        assertEquals(ToolPermissionRules.ToolFilter.WEB, domain.tool());
        assertEquals(ToolPermissionRules.PatternMode.DOMAIN, domain.mode());

        assertNull(ToolPermissionRules.parse("Nope(x)", ToolPermissionRules.RuleAction.ALLOW, error));
        assertTrue(error[0].contains("unknown rule prefix"));
        error[0] = null;
        assertNull(ToolPermissionRules.parse("Command(git status", ToolPermissionRules.RuleAction.ALLOW, error));
        assertTrue(error[0].contains("closing"));
    }

    // ── evaluation order ───────────────────────────────────────────────────

    @Test
    void denyBeatsAskAndAllowRegardlessOfDeclarationOrder() {
        List<PermissionRule> rules = rules(
                List.of("Tool(excel_*)"),           // allow
                List.of("Effect(external)"),          // ask
                List.of("Tool(excel_split)"));        // deny — narrower than the allow
        Evaluation evaluation = evaluate(rules, ToolAccess.of("excel_split", ToolEffect.EXTERNAL));
        assertNotNull(evaluation);
        assertEquals(Decision.DENY, evaluation.decision());
        assertTrue(evaluation.reason().contains("excel_split"));
    }

    @Test
    void askBeatsAllowAndEmptyFallsThrough() {
        List<PermissionRule> rules = rules(List.of("Effect(read)"), List.of("Tool(web_fetch)"), List.of());
        assertEquals(Decision.ASK, evaluate(rules, ToolAccess.of("web_fetch", ToolEffect.READ)).decision());
        assertNull(evaluate(rules, ToolAccess.of("browser_navigate", ToolEffect.EXTERNAL)));
    }

    // ── command chains ─────────────────────────────────────────────────────

    @Test
    void commandDenyMatchesAnyChainSegment() {
        List<PermissionRule> rules = rules(List.of(), List.of(), List.of("Command(rm)"));
        Evaluation evaluation = evaluate(rules, ToolPermissionRules.ToolAccess.command("git status && rm -rf /tmp/x | cat"));
        assertNotNull(evaluation);
        assertEquals(Decision.DENY, evaluation.decision());
    }

    @Test
    void commandAllowIsConjunctiveAcrossTheChain() {
        // `Command(git status)` alone must NOT authorize `git status && rm -rf /`.
        List<PermissionRule> partial = rules(List.of("Command(git status)"), List.of(), List.of());
        assertEquals(Decision.ASK, evaluate(partial,
                ToolPermissionRules.ToolAccess.command("git status && rm -rf /tmp/x")).decision());

        // Covering every segment allows the chain.
        List<PermissionRule> full = rules(List.of("Command(git status)", "Command(ls)"), List.of(), List.of());
        assertEquals(Decision.ALLOW, evaluate(full,
                ToolPermissionRules.ToolAccess.command("git status && ls -la")).decision());
    }

    @Test
    void dangerousCommandsAreNeverAutoApprovedByAllowRules() {
        List<PermissionRule> rules = rules(List.of("Command(git push)"), List.of(), List.of());
        assertEquals(Decision.ASK, evaluate(rules,
                ToolPermissionRules.ToolAccess.command("git push origin main")).decision());
    }

    @Test
    void dangerousFloorSurvivesWrappersPathsAndInlineShells() {
        // A blanket allow must still ask for every dangerous form (P1-2 regression matrix).
        List<PermissionRule> blanket = rules(List.of("Command"), List.of(), List.of());
        for (String command : List.of(
                "env rm -rf /tmp/example",
                "/bin/rm -rf /tmp/example",
                "./bin/rm -rf /tmp/example",
                "command rm -rf /tmp/example",
                "nohup rm -rf /tmp/example",
                "nice -n 5 rm -rf /tmp/example",
                "timeout 5 rm -rf /tmp/example",
                "FOO=bar rm -rf /tmp/example",
                "sh -c 'rm -rf /tmp/example'",
                "bash -c 'git push --force'",
                "/bin/sh -c 'mkfs /dev/sda1'",
                "sudo reboot",
                "/usr/bin/sudo reboot",
                "git push --force",
                "echo $(rm -rf /)",
                "echo `rm -rf /`",
                "(rm -rf /)",
                "echo 'unbalanced")) {
            assertEquals(Decision.ASK, evaluate(blanket,
                    ToolPermissionRules.ToolAccess.command(command)).decision(),
                    "floor must trip for: " + command);
        }
        // Benign wrapped commands still allow under the blanket rule.
        for (String command : List.of(
                "env ls -la",
                "/bin/ls -la",
                "nice -n 5 git status",
                "timeout 30 pytest -q",
                "FOO=bar echo hello",
                "sh -c 'ls -la'")) {
            assertEquals(Decision.ALLOW, evaluate(blanket,
                    ToolPermissionRules.ToolAccess.command(command)).decision(),
                    "benign form must allow: " + command);
        }
        // A bare deny on rm still catches wrapper forms (deny matches any chain segment
        // through the raw text, and the floor independently voids blanket allows).
        List<PermissionRule> denyRm = rules(List.of("Command(ls)"), List.of(), List.of("Command(rm)"));
        assertEquals(Decision.DENY, evaluate(denyRm,
                ToolPermissionRules.ToolAccess.command("env rm -rf /x")).decision());
    }

    @Test
    void wordBoundaryPrefixPreventsFalseMatches() {
        List<PermissionRule> rules = rules(List.of("Command(git)"), List.of(), List.of());
        assertEquals(Decision.ALLOW, evaluate(rules,
                ToolPermissionRules.ToolAccess.command("git status")).decision());
        // `git` must not match `ghostscript -h` — no rule matched, so the caller falls
        // through to the permission-mode default.
        assertNull(evaluate(rules, ToolPermissionRules.ToolAccess.command("ghostscript -h")));
    }

    // ── other dimensions ───────────────────────────────────────────────────

    @Test
    void effectFilterMatchesToolEffect() {
        List<PermissionRule> rules = rules(List.of("Effect(read)"), List.of(), List.of());
        assertEquals(Decision.ALLOW, evaluate(rules, ToolAccess.of("web_fetch", ToolEffect.READ)).decision());
        assertNull(evaluate(rules, ToolAccess.of("excel_execute", ToolEffect.WRITE)));
    }

    @Test
    void domainRulesMatchHostAndSubdomainsOnly() {
        List<PermissionRule> rules = rules(List.of("WebFetch(domain:example.com)"), List.of(), List.of());
        assertEquals(Decision.ALLOW, evaluate(rules,
                new ToolAccess("web_fetch", ToolEffect.READ, false, null, "https://api.example.com/x")).decision());
        assertEquals(Decision.ALLOW, evaluate(rules,
                new ToolAccess("web_fetch", ToolEffect.READ, false, null, "https://example.com/")).decision());
        // A non-matching host hits no rule → falls through to the permission-mode default.
        assertNull(evaluate(rules,
                new ToolAccess("web_fetch", ToolEffect.READ, false, null, "https://notexample.com/")));
    }

    @Test
    void mcpRulesRequireQualifiedMcpTools() {
        List<PermissionRule> rules = rules(List.of("Mcp(github__*)"), List.of(), List.of());
        assertEquals(Decision.ALLOW, evaluate(rules,
                new ToolAccess("github__create_issue", ToolEffect.EXTERNAL, true, null, null)).decision());
        // A built-in tool with a similar name is not an MCP tool → no rule matched.
        assertNull(evaluate(rules,
                new ToolAccess("github__create_issue", ToolEffect.EXTERNAL, false, null, null)));
    }

    @Test
    void catastrophicFloorMatchesOnlyDestruction() {
        for (String command : List.of(
                "rm -rf /",
                "rm -fr /",
                "rm -rf //",
                "rm --recursive --force /",
                "rm -rf /*",
                "rm -rf ~",
                "rm -rf $HOME",
                "rm -rf C:\\",
                "sudo rm -rf /",
                "sudo -u root rm -rf /",
                "doas rm -rf /",
                "/bin/rm -rf /",
                "mkfs /dev/sda1",
                "mkfs.ext4 /dev/sda1",
                "dd if=/dev/zero of=/dev/sda",
                "dd of=/dev/disk2 bs=1m",
                "sh -c 'rm -rf /'",
                "bash -c 'mkfs.vfat /dev/sdb1'",
                "echo hi && rm -rf / && echo bye",
                "env rm -rf /",
                "sudo mkfs.ext4 /dev/sda1",
                "sudo dd if=x of=/dev/sda",
                // CQ-01 bypass matrix: operands before options (valid GNU rm syntax),
                // expansion-wrapped roots, newline chains, subshells, wrappers.
                "rm / -rf",
                "rm / --recursive",
                "sudo rm / -rf",
                "sudo rm --recursive /",
                "env rm / --recursive",
                "rm -rf ${HOME}",
                "rm ${HOME} --recursive",
                "sudo rm -rf ${HOME}",
                "sh -c 'rm -rf ${HOME}'",
                "echo safe\nrm -rf /",
                "echo safe\r\nrm -rf /",
                "(rm -rf /)",
                "rm -rf C:/",
                "rm C:\\ -rf",
                "sudo mkfs.ext4 ${DISK}",
                "dd if=/dev/zero of=/dev/${DISK}")) {
            assertTrue(ToolPermissionRules.isCatastrophicCommand(command), "must trip: " + command);
        }
        for (String command : List.of(
                "rm -rf ./build",
                "rm -rf /tmp/scratch",
                "rm -rf /tmp/*",
                "rm file.txt",
                "rm -f /tmp/x",
                "rm -r ../build",
                "dd if=/dev/zero of=disk.img bs=1M",
                "dd of=output.bin",
                "sudo apt update",
                "echo rm -rf /",
                "ls -la /",
                // Unparseable but destruction-free text must NOT hard-deny — it is routed
                // to the human gate via isUnverifiableCommand instead.
                "git commit -m \"(see rm notes)\"",
                "ls $(pwd)",
                "echo ${HOME}",
                "rm -rf ${HOME}/.cache",
                "echo \"unbalanced",
                "",
                "   ")) {
            assertFalse(ToolPermissionRules.isCatastrophicCommand(command), "must NOT trip: " + command);
        }
    }

    @Test
    void unverifiableCommandsAreNeverAutoApprovable() {
        for (String command : List.of(
                "echo safe\nrm -rf /tmp/x",
                "ls $(pwd)",
                "echo `date`",
                "rm -rf ${HOME}/.cache",
                "(git status)",
                "echo \"unbalanced")) {
            assertTrue(ToolPermissionRules.isUnverifiableCommand(command), "unverifiable: " + command);
        }
        for (String command : List.of(
                "git status",
                "rm -rf ./build",
                "echo rm -rf /",
                "sh -c 'ls -la'",
                "   ")) {
            assertFalse(ToolPermissionRules.isUnverifiableCommand(command), "verifiable: " + command);
        }
        assertFalse(ToolPermissionRules.isUnverifiableCommand(null));
    }
}
