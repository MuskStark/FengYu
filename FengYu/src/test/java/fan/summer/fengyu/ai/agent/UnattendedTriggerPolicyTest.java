package fan.summer.fengyu.ai.agent;

import fan.summer.fengyu.ai.hooks.HookDispatcher;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import fan.summer.fengyu.ai.tools.AuditedToolCallback;
import fan.summer.fengyu.ai.tools.ToolEffect;
import fan.summer.fengyu.ai.tools.ToolGuardService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The creation-time screen for unattended triggers: under the ask-for-approval default a
 * schedule/webhook workflow containing an uncovered non-read step could never get past its
 * approval gate, so it must be rejected when the trigger is created — not discovered as a
 * queue-starving hang on every fire.
 */
class UnattendedTriggerPolicyTest {

    /** An audited callback with a fixed effect — the dimension the policy screens on. */
    static final class EffectTool implements ToolCallback, AuditedToolCallback {
        private final ToolEffect effect;
        EffectTool(ToolEffect effect) { this.effect = effect; }
        @Override public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name(effect == ToolEffect.READ ? "peek" : "mutate")
                    .description("effect probe").inputSchema("{}").build();
        }
        @Override public String call(String toolInput) { return "ok"; }
        @Override public ToolEffect effect() { return effect; }
    }

    private static final ToolGuardService NO_RULES =
            new ToolGuardService(new HookDispatcher(), "{}", "[]");
    private static final ToolGuardService ALLOW_MUTATE =
            new ToolGuardService(new HookDispatcher(), "{\"allow\":[\"Tool(mutate)\"]}", "[]");

    private static UnattendedTriggerPolicy policy(ToolGuardService guard, ToolCallback... tools) {
        return new UnattendedTriggerPolicy(guard, () -> List.of(tools));
    }

    private static AgentPlan plan(String tool) {
        return new AgentPlan("g", List.of(
                new AgentStep(0, tool, Map.of(), "step 0", false)), "");
    }

    @Test
    void rejectsAskModePlanWithUncoveredNonReadSteps() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> policy(NO_RULES, new EffectTool(ToolEffect.WRITE))
                        .requireExecutable(plan("mutate"), AiPermissionMode.ASK_FOR_APPROVAL));
        assertTrue(rejected.getMessage().contains("ask-for-approval"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("step 0"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("mutate"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("permission mode"), rejected.getMessage());
    }

    @Test
    void acceptsReadOnlyPlansExplicitModesAndAllowRuleCoveredSteps() {
        // Read-effect steps never gate.
        assertDoesNotThrow(() -> policy(NO_RULES, new EffectTool(ToolEffect.READ))
                .requireExecutable(plan("peek"), AiPermissionMode.ASK_FOR_APPROVAL));
        // An explicit non-ask mode answers its own gates.
        for (AiPermissionMode mode : List.of(AiPermissionMode.APPROVE_FOR_ME,
                AiPermissionMode.FULL_ACCESS)) {
            assertDoesNotThrow(() -> policy(NO_RULES, new EffectTool(ToolEffect.WRITE))
                    .requireExecutable(plan("mutate"), mode));
        }
        // An allow rule covering the tool suppresses the approval prompt.
        assertDoesNotThrow(() -> policy(ALLOW_MUTATE, new EffectTool(ToolEffect.WRITE))
                .requireExecutable(plan("mutate"), AiPermissionMode.ASK_FOR_APPROVAL));
    }

    @Test
    void askRulesAndDenyRulesDoNotCoverTheGate() {
        // Only ALLOW suppresses the gate; an ask rule forces it, a deny rule fails the fire.
        ToolGuardService askMutate =
                new ToolGuardService(new HookDispatcher(), "{\"ask\":[\"Tool(mutate)\"]}", "[]");
        assertThrows(IllegalArgumentException.class, () -> policy(askMutate,
                new EffectTool(ToolEffect.WRITE))
                .requireExecutable(plan("mutate"), AiPermissionMode.ASK_FOR_APPROVAL));
    }

    @Test
    void unauditedToolsFollowTheLegacyPolicyAndStayAccepted() {
        // ToolApprovalPolicy only gates AuditedToolCallback instances; an unaudited tool
        // never asks, so an unattended trigger may reference it.
        ToolCallback plain = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name("plain").description("no effect declared").inputSchema("{}").build();
            }
            @Override public String call(String toolInput) { return "ok"; }
        };
        assertDoesNotThrow(() -> policy(NO_RULES, plain)
                .requireExecutable(plan("plain"), AiPermissionMode.ASK_FOR_APPROVAL));
    }

    @Test
    void nullPlanAndNullModeAreTolerated() {
        UnattendedTriggerPolicy policy = policy(NO_RULES, new EffectTool(ToolEffect.WRITE));
        assertDoesNotThrow(() -> policy.requireExecutable(null, AiPermissionMode.ASK_FOR_APPROVAL));
        assertDoesNotThrow(() -> policy.requireExecutable(
                new AgentPlan("g", null, ""), AiPermissionMode.ASK_FOR_APPROVAL));
    }
}
