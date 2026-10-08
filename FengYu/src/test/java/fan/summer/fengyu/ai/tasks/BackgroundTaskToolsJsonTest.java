package fan.summer.fengyu.ai.tasks;

import fan.summer.fengyu.ai.util.JsonHelper;
import fan.summer.fengyu.ai.workflow.WorkflowExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The model-facing tool envelopes are hand-built JSON around model-supplied ids and
 * arbitrary exception messages. Both used to be interpolated raw (or with a naive
 * quote-swap), so an id carrying {@code "} or a multi-line error message produced
 * unparseable JSON — the model then saw a parse failure instead of the real result.
 * Every dynamic fragment now rides through {@link JsonHelper}.
 */
class BackgroundTaskToolsJsonTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<WorkflowExecutionService> provider() {
        ObjectProvider<WorkflowExecutionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(WorkflowExecutionService.class));
        return provider;
    }

    @Test
    void killAndOutputEnvelopesSurviveQuoteBearingTaskIds() {
        BackgroundTaskTools tools = new BackgroundTaskTools(new BackgroundTaskRegistry(),
                provider(), mock(BackgroundTaskScheduler.class));
        String oddId = "we\"ird\\id";

        Map<String, Object> kill = JsonHelper.parseObject(tools.kill(oddId));
        assertEquals(Boolean.FALSE, kill.get("ok"));
        assertEquals(oddId, kill.get("taskId"));

        Map<String, Object> output = JsonHelper.parseObject(tools.output(oddId, 0));
        assertEquals(oddId, output.get("taskId"));
        assertEquals("unknown task", output.get("error"));
    }

    @Test
    void errorEnvelopesEscapeNewlinesQuotesAndBackslashesInMessages() {
        BackgroundTaskScheduler scheduler = mock(BackgroundTaskScheduler.class);
        when(scheduler.create(anyString(), any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("line one\nline \"two\" \\three"));
        BackgroundTaskTools tools = new BackgroundTaskTools(new BackgroundTaskRegistry(),
                provider(), scheduler);

        Map<String, Object> envelope =
                JsonHelper.parseObject(tools.schedule("wf-1", "{}", 60, true, false));

        assertEquals("line one\nline \"two\" \\three", envelope.get("error"),
                "the message must round-trip through the JSON envelope");
        assertEquals(null, envelope.get("scheduleId"));
    }
}
