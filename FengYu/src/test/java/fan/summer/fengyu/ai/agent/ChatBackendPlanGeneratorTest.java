package fan.summer.fengyu.ai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatBackendPlanGeneratorTest {

    @Test
    void parsesFencedWorkflowAndNormalizesIndexes() {
        ToolCallback tool = new AgentRunnerTest.EchoToolCallback();
        String response = """
                ```json
                {
                  "goal": "echo twice",
                  "reasoning": "chain outputs",
                  "steps": [
                    {"index": 9, "toolName": "echo", "args": {"text": "hi"},
                     "description": "first", "requiresApproval": false},
                    {"index": 12, "toolName": "echo",
                     "args": {"text": "{{steps.0.result}}"},
                     "description": "second", "requiresApproval": true}
                  ]
                }
                ```
                """;

        AgentPlan plan = ChatBackendPlanGenerator.parseAndValidate(
                response, "requested", List.of(tool));

        assertEquals("echo twice", plan.goal());
        assertEquals(2, plan.steps().size());
        assertEquals(0, plan.steps().get(0).index());
        assertEquals(1, plan.steps().get(1).index());
        assertEquals("{{steps.0.result}}", plan.steps().get(1).args().get("text"));
    }

    @Test
    void rejectsUnknownTool() {
        String response = """
                {"steps":[{"toolName":"missing","args":{},"description":"bad"}]}
                """;

        assertThrows(IllegalArgumentException.class, () ->
                ChatBackendPlanGenerator.parseAndValidate(
                        response, "requested", List.of(new AgentRunnerTest.EchoToolCallback())));
    }
}
