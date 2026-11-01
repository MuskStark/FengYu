package fan.summer.fengyu.ai.sandbox;

import fan.summer.fengyu.ai.hooks.HookDispatcher;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import fan.summer.fengyu.ai.tools.AuditedToolCallback;
import fan.summer.fengyu.ai.tools.ToolEffect;
import fan.summer.fengyu.ai.tools.ToolGuardService;
import fan.summer.fengyu.ai.tools.WorkspaceExecTool;
import fan.summer.fengyu.ai.workspace.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ordering contract of the argv-level readonly upgrade (the seam is
 * {@link ExecPolicy#decide}'s UNMATCHED floor calling
 * {@link ReadonlyCommandAnalyzer}): the floor upgrades a would-be prompt to allow only
 * for statically provable read-only commands, an explicit rule decision (prompt or
 * forbidden) is never upgraded, and everything that fires EARLIER in the guard
 * pipeline — the catastrophic-command hard floor, a PreToolUse hook veto, a deny rule —
 * still denies, upgrade or not. The end-to-end row proves the upgrade reaches the tool
 * envelope's {@code readonly} audit mark.
 */
class ExecPolicyReadonlyUpgradeTest {

    // ── ExecPolicy level ───────────────────────────────────────────────────────────────

    @Test
    void unmatchedFloorUpgradesProvablyReadonlyCommandsToAllow() {
        ExecPolicy builtin = ExecPolicy.builtin();
        // Commands the token-prefix rules cannot see (no builtin rule matches) but the
        // argv analyzer proves read-only.
        assertEquals(ExecPolicy.Decision.ALLOW, builtin.decide(List.of("gh", "pr", "list")));
        assertEquals(ExecPolicy.Decision.ALLOW, builtin.decide(List.of("git", "branch", "--list")));
        assertEquals(ExecPolicy.Decision.ALLOW, builtin.decide(List.of("git", "config", "--get", "user.email")));
        assertEquals(ExecPolicy.Decision.ALLOW, builtin.decide(List.of("whoami")));
        assertEquals(ExecPolicy.Decision.ALLOW,
                builtin.decide(List.of("/usr/bin/gh", "run", "view", "42")),
                "the executable token normalizes before the floor");
        // Not read-only → the floor keeps its prompt.
        assertEquals(ExecPolicy.Decision.PROMPT, builtin.decide(List.of("git", "branch", "feature")));
        assertEquals(ExecPolicy.Decision.PROMPT, builtin.decide(List.of("gh", "pr", "create")));
        assertEquals(ExecPolicy.Decision.PROMPT, builtin.decide(List.of("npm", "install")));
    }

    @Test
    void explicitRuleDecisionsAreNeverUpgraded(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("prompt-gh.json"),
                "[{\"pattern\":[\"gh\"],\"decision\":\"prompt\"}]");
        ExecPolicy prompted = ExecPolicy.load(dir);
        assertEquals(ExecPolicy.Decision.PROMPT, prompted.decide(List.of("gh", "pr", "list")),
                "a user's explicit prompt rule is authoritative — no analyzer override");
        Files.writeString(dir.resolve("forbidden-gh.json"),
                "[{\"pattern\":[\"gh\"],\"decision\":\"forbidden\"}]");
        ExecPolicy forbidden = ExecPolicy.load(dir);
        assertEquals(ExecPolicy.Decision.FORBIDDEN, forbidden.decide(List.of("gh", "pr", "list")),
                "a forbidden decision is never upgraded to allow");
        // The floor still refines commands OUTSIDE the explicit rules' reach.
        assertEquals(ExecPolicy.Decision.ALLOW, forbidden.decide(List.of("git", "remote", "-v")));
    }

    // ── guard pipeline level (ordering) ────────────────────────────────────────────────

    @Test
    void readonlyUpgradeRunsOnlyAtThePipelineTail() {
        ToolGuardService service = new ToolGuardService(new HookDispatcher(), "{}", "[]");
        // Would-be prompt + analyzer-readonly → allow (the upgrade, through the mode default).
        assertEquals(ToolGuardService.Verdict.ALLOW, service.decide("workspace_exec",
                audited(ToolEffect.COMMAND), "{\"command\":\"gh pr list\"}",
                AiPermissionMode.ASK_FOR_APPROVAL, null).verdict());
        assertEquals(ToolGuardService.Verdict.ALLOW, service.decide("workspace_exec",
                audited(ToolEffect.COMMAND), "{\"command\":\"git branch --list\"}",
                AiPermissionMode.ASK_FOR_APPROVAL, null).verdict());
        // Would-be prompt + NOT readonly → still asks.
        assertEquals(ToolGuardService.Verdict.ASK, service.decide("workspace_exec",
                audited(ToolEffect.COMMAND), "{\"command\":\"npm install\"}",
                AiPermissionMode.ASK_FOR_APPROVAL, null).verdict());
    }

    @Test
    void denyRulesBeatTheReadonlyUpgrade() {
        ToolGuardService denying = new ToolGuardService(new HookDispatcher(),
                "{\"deny\":[\"Command(gh)\"]}", "[]");
        assertEquals(ToolGuardService.Verdict.DENY, denying.decide("workspace_exec",
                audited(ToolEffect.COMMAND), "{\"command\":\"gh pr list\"}",
                AiPermissionMode.ASK_FOR_APPROVAL, null).verdict(),
                "a configured deny rule fires before the mode-default readonly floor");
    }

    @Test
    void hookDenyBeatsTheReadonlyUpgrade(@TempDir Path tmp) throws Exception {
        Path script = tmp.resolve("deny-readonly.sh");
        Files.writeString(script, "#!/bin/sh\necho 'hook veto' >&2; exit 2\n");
        script.toFile().setExecutable(true);
        ToolGuardService hooked = new ToolGuardService(new HookDispatcher(), "{}",
                "[{\"name\":\"veto\",\"event\":\"pre_tool_use\",\"type\":\"command\","
                        + "\"matcher\":\"workspace_exec\",\"command\":\"" + script + "\","
                        + "\"timeoutSeconds\":5}]");
        ToolGuardService.GuardDecision decision = hooked.decide("workspace_exec",
                audited(ToolEffect.COMMAND), "{\"command\":\"gh pr list\"}",
                AiPermissionMode.ASK_FOR_APPROVAL, null);
        assertEquals(ToolGuardService.Verdict.DENY, decision.verdict(),
                "a PreToolUse hook veto fires before the readonly upgrade can allow");
        assertEquals("hook veto", decision.reason());
    }

    @Test
    void catastrophicFloorBeatsTheReadonlyUpgrade() {
        ToolGuardService service = new ToolGuardService(new HookDispatcher(), "{}", "[]");
        assertEquals(ToolGuardService.Verdict.DENY, service.decide("workspace_exec",
                audited(ToolEffect.COMMAND), "{\"command\":\"rm -rf / --no-preserve-root\"}",
                AiPermissionMode.FULL_ACCESS, null).verdict(),
                "the hard floor denies destruction before any readonly consideration");
    }

    // ── end to end: the audit mark ─────────────────────────────────────────────────────

    /**
     * The upgrade reaches the tool envelope: a floor-upgraded command auto-runs and its
     * result carries {@code readonly: true} (the audit mark — ExecPolicy's decision
     * shape is unchanged, so the envelope is where the verdict is reported).
     * {@code whoami} has NO builtin rule, so its auto-run IS the analyzer's doing.
     */
    @Test
    void upgradedCommandCarriesTheReadonlyAuditMark(@TempDir Path root) throws Exception {
        WorkspaceContext.set(new WorkspaceContext.Binding(root, null));
        try {
            com.fasterxml.jackson.databind.JsonNode result =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                            new WorkspaceExecTool().workspaceExec("whoami", null, 30, null));
            assertTrue(result.path("success").asBoolean(), () -> "whoami must run: " + result);
            assertTrue(result.path("readonly").asBoolean(),
                    "the analyzer-upgraded invocation reports readonly");
        } finally {
            WorkspaceContext.clear();
        }
    }

    /** A minimal audited callback with a fixed effect (the shape the guard consults). */
    private static ToolCallback audited(ToolEffect effect) {
        return new AuditedToolCallback() {
            private final org.springframework.ai.tool.definition.ToolDefinition definition =
                    org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                            .name("workspace_exec").description("workspace_exec")
                            .inputSchema("{\"type\":\"object\"}").build();
            @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return definition;
            }
            @Override public ToolEffect effect() { return effect; }
            @Override public String call(String input) { return input; }
        };
    }
}
