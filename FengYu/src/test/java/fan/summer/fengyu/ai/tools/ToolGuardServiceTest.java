package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.hooks.HookDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The layered pipeline order: PreToolUse hooks → deny/ask/allow rules → mode default. */
class ToolGuardServiceTest {

    @TempDir
    Path tmp;

    private ToolGuardService guard(String rulesJson, HookDispatcher.HookDefinition... hooks) {
        StringBuilder hooksJson = new StringBuilder("[");
        if (hooks != null) {
            for (HookDispatcher.HookDefinition hook : hooks) {
                if (hooksJson.length() > 1) hooksJson.append(",");
                hooksJson.append("{\"name\":\"").append(hook.name())
                        .append("\",\"event\":\"").append(hook.event().wireName())
                        .append("\",\"type\":\"command\",\"command\":\"").append(hook.command())
                        .append("\",\"timeoutSeconds\":5}");
            }
        }
        hooksJson.append("]");
        return new ToolGuardService(new HookDispatcher(), rulesJson, hooksJson.toString());
    }

    /** A minimal audited callback with a fixed effect. */
    private static ToolCallback tool(String name, ToolEffect effect) {
        return new AuditedToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }
            @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }
            @Override public String call(String input) { return "{\"success\":true}"; }
            @Override public ToolEffect effect() { return effect; }
        };
    }

    private String hookScript(String body) throws Exception {
        Path file = tmp.resolve("g-" + System.nanoTime() + ".sh");
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        file.toFile().setExecutable(true);
        return file.toString();
    }

    @Test
    void hookVetoBeatsAllowRuleAndModeDefault() throws Exception {
        ToolGuardService service = guard(
                "{\"allow\":[\"Tool(excel_*)\"]}",
                HookDispatcher.HookDefinition.command("veto", HookDispatcher.HookEvent.PRE_TOOL_USE,
                        "excel_.*", hookScript("echo 'policy veto' >&2; exit 2"), 5));
        ToolGuardService.GuardDecision decision = service.decide("excel_execute",
                tool("excel_execute", ToolEffect.WRITE), "{}",
                AiPermissionMode.FULL_ACCESS, null);
        assertEquals(ToolGuardService.Verdict.DENY, decision.verdict());
        assertEquals("policy veto", decision.reason());
    }

    @Test
    void denyRuleBeatsModeDefaultAndAllowSuppressesThePrompt() {
        ToolGuardService service = guard(
                "{\"deny\":[\"Tool(computer_*)\"],\"allow\":[\"Effect(read)\"]}", (HookDispatcher.HookDefinition[]) null);
        // deny rule
        ToolGuardService.GuardDecision denied = service.decide("computer_click",
                tool("computer_click", ToolEffect.EXTERNAL), "{}",
                AiPermissionMode.FULL_ACCESS, null);
        assertEquals(ToolGuardService.Verdict.DENY, denied.verdict());
        // allow rule (read effect) in ask-mode would otherwise prompt
        ToolGuardService.GuardDecision allowed = service.decide("web_fetch",
                tool("web_fetch", ToolEffect.READ), "{}",
                AiPermissionMode.ASK_FOR_APPROVAL, null);
        assertEquals(ToolGuardService.Verdict.ALLOW, allowed.verdict());
        // mode default: external tool, no rules matched → ask
        ToolGuardService.GuardDecision fallback = service.decide("browser_navigate",
                tool("browser_navigate", ToolEffect.EXTERNAL), "{}",
                AiPermissionMode.APPROVE_FOR_ME, null);
        assertEquals(ToolGuardService.Verdict.ASK, fallback.verdict());
    }

    @Test
    void commandAccessUsesTheArgumentsCommand() {
        ToolGuardService service = guard(
                "{\"deny\":[\"Command(rm)\"]}", (HookDispatcher.HookDefinition[]) null);
        ToolGuardService.GuardDecision denied = service.decide("execute_command",
                tool("execute_command", ToolEffect.COMMAND),
                "{\"command\":\"echo hi && rm -rf /tmp/x\"}",
                AiPermissionMode.FULL_ACCESS, null);
        assertEquals(ToolGuardService.Verdict.DENY, denied.verdict());
    }

    @Test
    void hookConfigParseRejectsUnknownEventsByDroppingThem() {
        List<HookDispatcher.HookDefinition> parsed = ToolGuardService.HookConfig.parse(
                "[{\"name\":\"a\",\"event\":\"pre_tool_use\",\"type\":\"command\",\"command\":\"true\"},"
                        + "{\"name\":\"b\",\"event\":\"nonsense\",\"type\":\"command\",\"command\":\"true\"}]");
        assertEquals(1, parsed.size());
        assertEquals("a", parsed.getFirst().name());
        assertEquals(0, ToolGuardService.HookConfig.parse("not json").size());
        assertEquals(0, ToolGuardService.HookConfig.parse("{\"not\":\"a list\"}").size());
    }

    @Test
    void hookConfigParseClampsTimeoutSecondsIntoTheSupportedWindow() {
        // CQ-05: a typo like 600 must not make every tool call serially wait minutes —
        // configured hook timeouts clamp into [1, 60]s at parse time. Absent stays 5s.
        List<HookDispatcher.HookDefinition> parsed = ToolGuardService.HookConfig.parse(
                "[{\"name\":\"huge\",\"event\":\"pre_tool_use\",\"type\":\"command\",\"command\":\"true\",\"timeoutSeconds\":600},"
                        + "{\"name\":\"zero\",\"event\":\"pre_tool_use\",\"type\":\"command\",\"command\":\"true\",\"timeoutSeconds\":0},"
                        + "{\"name\":\"unset\",\"event\":\"pre_tool_use\",\"type\":\"command\",\"command\":\"true\"}]");
        assertEquals(java.time.Duration.ofSeconds(60), parsed.get(0).timeout());
        assertEquals(java.time.Duration.ofSeconds(1), parsed.get(1).timeout());
        assertEquals(java.time.Duration.ofSeconds(5), parsed.get(2).timeout());
    }

    @Test
    void guardObservesRunCompletionWithoutThrowing() {
        ToolGuardService service = guard("{}", (HookDispatcher.HookDefinition[]) null);
        service.observeRunComplete("run-1", "goal", "done", false);
        service.observeRunComplete("run-2", "goal", "failed", true);
        service.observeToolResult("web_fetch", "{}", "{\"ok\":true}", false, "run-1");
    }

    @Test
    void nullRulesJsonBehavesLikeEmptyConfiguration() {
        ToolGuardService service = new ToolGuardService(new HookDispatcher(), null, null);
        assertEquals(0, service.rules().size());
        assertEquals(ToolGuardService.Verdict.ASK, service.decide("browser_navigate",
                tool("browser_navigate", ToolEffect.EXTERNAL), "{}",
                AiPermissionMode.ASK_FOR_APPROVAL, null).verdict());
    }

    @Test
    void malformedStoredRulesFailOpenToTheModeDefault() {
        ToolGuardService service = new ToolGuardService(new HookDispatcher(), "{not json", "[]");
        assertEquals(0, service.rules().size());
        // Corrupt stored config must not brick tool calls — the mode default decides.
        assertEquals(ToolGuardService.Verdict.ALLOW, service.decide("web_fetch",
                tool("web_fetch", ToolEffect.READ), "{}",
                AiPermissionMode.ASK_FOR_APPROVAL, null).verdict());
    }

    @Test
    void catastrophicFloorDeniesEvenFullAccessAndBlanketAllow() {
        ToolGuardService service = guard(
                "{\"allow\":[\"Command\"]}", (HookDispatcher.HookDefinition[]) null);
        for (String command : List.of(
                "rm -rf /",
                "rm -fr //",
                "rm --recursive --force /",
                "sudo rm -rf /",
                "mkfs.ext4 /dev/sda1",
                "dd if=/dev/zero of=/dev/sda bs=1M count=10",
                "sh -c 'rm -rf ~/'",
                // CQ-01 bypass matrix through the full pipeline.
                "rm / -rf",
                "sudo rm --recursive /",
                "echo safe\nrm -rf /",
                "rm -rf ${HOME}",
                "sh -c 'rm -rf ${HOME}'")) {
            ToolGuardService.GuardDecision decision = service.decide("execute_command",
                    tool("execute_command", ToolEffect.COMMAND),
                    "{\"command\":\"" + command.replace("\\", "\\\\").replace("\"", "\\\"")
                            .replace("\n", "\\n") + "\"}",
                    AiPermissionMode.FULL_ACCESS, null);
            assertEquals(ToolGuardService.Verdict.DENY, decision.verdict(), "command: " + command);
        }
        // Regular dangerous commands stay at ASK under a blanket allow (the existing
        // dangerous-verb floor), NOT denied — the hard floor is only for the catastrophic set.
        ToolGuardService.GuardDecision regular = service.decide("execute_command",
                tool("execute_command", ToolEffect.COMMAND),
                "{\"command\":\"rm -rf ./build\"}",
                AiPermissionMode.FULL_ACCESS, null);
        assertEquals(ToolGuardService.Verdict.ASK, regular.verdict(),
                "non-catastrophic rm stays governed by rules/mode, not the hard floor");
    }

    @Test
    void unverifiableCommandsAskEvenInFullAccessWithoutRules() {
        ToolGuardService service = guard("{}", (HookDispatcher.HookDefinition[]) null);
        // Destruction-free but unparseable text falls to a human decision instead of
        // auto-running under FULL_ACCESS (CQ-01).
        assertEquals(ToolGuardService.Verdict.ASK, service.decide("execute_command",
                tool("execute_command", ToolEffect.COMMAND),
                "{\"command\":\"ls $(pwd)\"}",
                AiPermissionMode.FULL_ACCESS, null).verdict());
        // Verifiable commands keep auto-running under FULL_ACCESS.
        assertEquals(ToolGuardService.Verdict.ALLOW, service.decide("execute_command",
                tool("execute_command", ToolEffect.COMMAND),
                "{\"command\":\"git status\"}",
                AiPermissionMode.FULL_ACCESS, null).verdict());
    }
}
