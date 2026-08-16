package fan.summer.fengyu.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    @Test
    void liveEventsCarryMonotonicSeqStartingAtOne() {
        AgentController.AgentStreamSink sink =
                new AgentController.AgentStreamSink("run-seq", ignored -> {});
        RecordingEmitter client = new RecordingEmitter();
        sink.attach(client);

        sink.onPlanToken("hello");
        sink.onStepStart(0);

        assertEquals(List.of("plan_token", "step_start"), client.eventNames());
        assertEquals(List.of(1L, 2L), client.seqs());
    }

    /**
     * CQ-02(a): a client that dies mid-run must not lose the dead window's events — the
     * sink returns to buffering, and the next attach replays the missed events in order
     * with their original (monotonic) seq numbers. Events the dead client did receive are
     * NOT replayed; the client uses seq to skip anything already seen.
     */
    @Test
    void reattachReplaysEventsMissedDuringTheDeadWindowInOrder() {
        AgentController.AgentStreamSink sink =
                new AgentController.AgentStreamSink("run-reattach", ignored -> {});
        RecordingEmitter first = new RecordingEmitter();
        sink.attach(first);
        sink.onPlanToken("hello"); // seq 1 — delivered live to the first client
        assertEquals(List.of("plan_token"), first.eventNames());

        first.failSend = true;             // the first client dies
        sink.onStepStart(0);               // seq 2 — live send fails → re-buffered
        sink.onStepComplete(0, "result");  // seq 3 — dead window → buffered

        RecordingEmitter second = new RecordingEmitter();
        sink.attach(second);
        assertEquals(List.of("step_start", "step_complete"), second.eventNames());
        assertEquals(List.of(2L, 3L), second.seqs());
    }

    /** Records what the sink sends: named SSE events + their (seq-stamped) map payloads. */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> eventNames = new ArrayList<>();
        private final List<Object> payloads = new ArrayList<>();
        volatile boolean failSend;

        RecordingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failSend) throw new IOException("client closed");
            for (ResponseBodyEmitter.DataWithMediaType piece : builder.build()) {
                if (piece.getData() instanceof String text && text.startsWith("event:")) {
                    // The builder's framing text ("event:<name>\n[data:") — keep just the name.
                    int end = text.indexOf('\n');
                    eventNames.add(end >= 0
                            ? text.substring("event:".length(), end)
                            : text.substring("event:".length()));
                } else if (!(piece.getData() instanceof String)) {
                    payloads.add(piece.getData());
                }
            }
        }

        @Override public void complete() { /* no container in tests */ }
        @Override public void completeWithError(Throwable ex) { /* no container in tests */ }

        List<String> eventNames() {
            return eventNames;
        }

        List<Long> seqs() {
            return payloads.stream()
                    .map(payload -> ((Number) ((Map<?, ?>) payload).get("seq")).longValue())
                    .toList();
        }
    }
}
