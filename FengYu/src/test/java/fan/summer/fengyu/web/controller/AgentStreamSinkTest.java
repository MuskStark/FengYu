package fan.summer.fengyu.web.controller;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentStreamSinkTest {

    @Test
    void terminalEventRequestsCleanupOnlyOnce() {
        AtomicInteger cleanups = new AtomicInteger();
        AgentController.AgentStreamSink sink =
                new AgentController.AgentStreamSink("run-1", ignored -> cleanups.incrementAndGet());

        sink.onComplete("done");
        sink.onError("late error");

        assertEquals(1, cleanups.get());
    }
}
