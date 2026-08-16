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
    void fullAccessStillReviewsUnverifiableCommandText() {
        ToolCallback command = audited(ToolEffect.COMMAND);
        assertTrue(ToolApprovalPolicy.requiresApproval(command, AiPermissionMode.FULL_ACCESS,
                "{\"command\":\"rm -rf ${HOME}\"}"));
        assertTrue(ToolApprovalPolicy.requiresApproval(command, AiPermissionMode.FULL_ACCESS,
                "{\"command\":\"ls $(pwd)\"}"));
        // Verifiable commands keep the FULL_ACCESS no-prompt behavior.
        assertFalse(ToolApprovalPolicy.requiresApproval(command, AiPermissionMode.FULL_ACCESS,
                "{\"command\":\"git status\"}"));
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
