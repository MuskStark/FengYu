package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolApprovalPolicyTest {

    @Test
    void externalToolsAreReviewedExceptInFullAccess() {
        ToolCallback tool = audited(ToolEffect.EXTERNAL);
        assertTrue(ToolApprovalPolicy.requiresApproval(
                tool, AiPermissionMode.APPROVE_FOR_ME, "{}"));
        assertFalse(ToolApprovalPolicy.requiresApproval(
                tool, AiPermissionMode.FULL_ACCESS, "{}"));
    }

    @Test
    void askModeAllowsReadsButReviewsWrites() {
        assertFalse(ToolApprovalPolicy.requiresApproval(
                audited(ToolEffect.READ), AiPermissionMode.ASK_FOR_APPROVAL, "{}"));
        assertTrue(ToolApprovalPolicy.requiresApproval(
                audited(ToolEffect.WRITE), AiPermissionMode.ASK_FOR_APPROVAL, "{}"));
    }

    @Test
    void fullAccessAutoRunsUnverifiableCommandText() {
        ToolCallback command = audited(ToolEffect.COMMAND);
        // Codex `AskForApproval::Never` contract: FULL_ACCESS never hands a decision back
        // to the user — unverifiable text (substitution, heredocs) auto-runs too. The
        // catastrophic-command hard floor in ToolGuardService still DENIES destruction
        // outright BEFORE this policy is consulted; asking here is what made "full
        // control" prompt on every heredoc/$(…).
        assertFalse(ToolApprovalPolicy.requiresApproval(command, AiPermissionMode.FULL_ACCESS,
                "{\"command\":\"ls $(pwd)\"}"));
        assertFalse(ToolApprovalPolicy.requiresApproval(command, AiPermissionMode.FULL_ACCESS,
                "{\"command\":\"cat > f <<'EOF'\\nline1\\nEOF\"}"));
        assertFalse(ToolApprovalPolicy.requiresApproval(command, AiPermissionMode.FULL_ACCESS,
                "{\"command\":\"git status\"}"));
        // Every other mode keeps the mandatory human gate on text it cannot verify.
        assertTrue(ToolApprovalPolicy.requiresApproval(command, AiPermissionMode.ASK_FOR_APPROVAL,
                "{\"command\":\"ls $(pwd)\"}"));
        assertTrue(ToolApprovalPolicy.requiresApproval(command, AiPermissionMode.APPROVE_FOR_ME,
                "{\"command\":\"ls $(pwd)\"}"));
    }

    private static AuditedToolCallback audited(ToolEffect effect) {
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name("test_" + effect.name().toLowerCase())
                .description("test")
                .inputSchema("{\"type\":\"object\"}")
                .build();
        return new AuditedToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) { return input; }
            @Override public ToolEffect effect() { return effect; }
        };
    }
}
