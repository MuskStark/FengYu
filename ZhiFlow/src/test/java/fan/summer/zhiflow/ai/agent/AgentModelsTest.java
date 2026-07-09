package fan.summer.zhiflow.ai.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AgentModelsTest {

    @Test
    void planAndStepConstruction() {
        AgentStep step = new AgentStep(0, "json_format", Map.of("json", "{}"), "format json", false);
        AgentPlan plan = new AgentPlan("goal", List.of(step), "because");
        assertEquals(1, plan.steps().size());
        assertEquals(StepStatus.PENDING, new StepExecution(0, StepStatus.PENDING, null).status());
    }

    @Test
    void runStartsPlanning() {
        AgentRun run = new AgentRun("run-1", "format this json", new AgentRunConfig(false, false, true, 3));
        assertEquals(AgentRunStatus.PLANNING, run.getStatus());
        assertEquals("format this json", run.getGoal());
        assertFalse(run.isCancelled());
    }
}
