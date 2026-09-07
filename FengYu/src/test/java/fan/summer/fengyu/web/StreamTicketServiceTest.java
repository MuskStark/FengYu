package fan.summer.fengyu.web;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One-time stream tickets: single redemption, endpoint binding, TTL, concurrency, bounded storage. */
class StreamTicketServiceTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-08-15T00:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void ticketRedeemsExactlyOnce() {
        StreamTicketService service = new StreamTicketService();
        StreamTicketService.IssuedTicket issued =
                service.issue(StreamTicketService.AI_STREAM_ENDPOINT);
        assertTrue(service.redeem(issued.ticket(), StreamTicketService.AI_STREAM_ENDPOINT),
                "first redemption must succeed");
        assertFalse(service.redeem(issued.ticket(), StreamTicketService.AI_STREAM_ENDPOINT),
                "replay must fail");
        assertFalse(service.redeem(issued.ticket(), StreamTicketService.AI_STREAM_ENDPOINT),
                "further replays must fail");
    }

    /** A ticket minted for one stream endpoint must not open the other. */
    @Test
    void ticketRedeemsOnlyOnItsBoundEndpoint() {
        StreamTicketService service = new StreamTicketService();
        StreamTicketService.IssuedTicket aiTicket =
                service.issue(StreamTicketService.AI_STREAM_ENDPOINT);

        assertFalse(service.redeem(aiTicket.ticket(), StreamTicketService.AGENT_STREAM_ENDPOINT),
                "an AI-stream ticket must not open the agent stream");
        // A wrong-endpoint attempt is anomalous (client bug or probing), so it consumed the
        // single-use ticket — fail-closed: the ticket can no longer open its own endpoint either.
        assertFalse(service.redeem(aiTicket.ticket(), StreamTicketService.AI_STREAM_ENDPOINT));

        StreamTicketService.IssuedTicket agentTicket =
                service.issue(StreamTicketService.AGENT_STREAM_ENDPOINT);
        assertFalse(service.redeem(agentTicket.ticket(), StreamTicketService.AI_STREAM_ENDPOINT));
        assertFalse(service.redeem(agentTicket.ticket(), StreamTicketService.AGENT_STREAM_ENDPOINT),
                "the wrong-endpoint attempt already consumed the ticket");

        // A correctly-bound redemption on a fresh ticket still works.
        StreamTicketService.IssuedTicket fresh =
                service.issue(StreamTicketService.AGENT_STREAM_ENDPOINT);
        assertTrue(service.redeem(fresh.ticket(), StreamTicketService.AGENT_STREAM_ENDPOINT));
    }

    @Test
    void ticketExpiresAfterItsTtl() {
        MutableClock clock = new MutableClock();
        StreamTicketService service = new StreamTicketService(clock);
        StreamTicketService.IssuedTicket issued =
                service.issue(StreamTicketService.AGENT_STREAM_ENDPOINT);
        clock.advance(Duration.ofSeconds(StreamTicketService.TTL_SECONDS).plusMillis(1));
        assertFalse(service.redeem(issued.ticket(), StreamTicketService.AGENT_STREAM_ENDPOINT),
                "expired ticket must not redeem");
    }

    @Test
    void concurrentRedemptionsSucceedExactlyOnce() throws Exception {
        StreamTicketService service = new StreamTicketService();
        StreamTicketService.IssuedTicket issued =
                service.issue(StreamTicketService.AI_STREAM_ENDPOINT);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        List<Thread> racers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try { start.await(); } catch (InterruptedException ignored) { return; }
                if (service.redeem(issued.ticket(), StreamTicketService.AI_STREAM_ENDPOINT)) {
                    wins.incrementAndGet();
                }
            });
            t.start();
            racers.add(t);
        }
        start.countDown();
        for (Thread t : racers) t.join();
        assertEquals(1, wins.get(), "exactly one concurrent redemption must win");
    }

    @Test
    void nullBlankAndUnknownTicketsNeverRedeem() {
        StreamTicketService service = new StreamTicketService();
        assertFalse(service.redeem(null, StreamTicketService.AI_STREAM_ENDPOINT));
        assertFalse(service.redeem("", StreamTicketService.AI_STREAM_ENDPOINT));
        assertFalse(service.redeem("   ", StreamTicketService.AI_STREAM_ENDPOINT));
        assertFalse(service.redeem("not-a-real-ticket", StreamTicketService.AI_STREAM_ENDPOINT));
    }

    @Test
    void eachIssueMintsAFreshTicket() {
        StreamTicketService service = new StreamTicketService();
        assertNotEquals(service.issue(StreamTicketService.AI_STREAM_ENDPOINT).ticket(),
                service.issue(StreamTicketService.AI_STREAM_ENDPOINT).ticket());
    }

    @Test
    void expiredTicketsAreSweptOnIssue() {
        MutableClock clock = new MutableClock();
        StreamTicketService service = new StreamTicketService(clock);
        service.issue(StreamTicketService.AI_STREAM_ENDPOINT);
        service.issue(StreamTicketService.AI_STREAM_ENDPOINT);
        assertEquals(2, service.outstanding());
        clock.advance(Duration.ofSeconds(StreamTicketService.TTL_SECONDS + 1));
        service.issue(StreamTicketService.AI_STREAM_ENDPOINT); // sweeps the two expired entries
        assertEquals(1, service.outstanding());
    }

    @Test
    void outstandingTicketsAreCapped() {
        MutableClock clock = new MutableClock();
        StreamTicketService service = new StreamTicketService(clock);
        for (int i = 0; i < StreamTicketService.MAX_OUTSTANDING; i++) {
            service.issue(StreamTicketService.AI_STREAM_ENDPOINT);
        }
        assertThrows(IllegalStateException.class,
                () -> service.issue(StreamTicketService.AI_STREAM_ENDPOINT));
    }

    /**
     * P3: the per-plugin log stream path is parameterized ({@code /api/plugin-runtime/{id}/logs/stream}),
     * so its tickets bind to the WILDCARD pattern and redeem on any concrete plugin id — in token
     * mode the endpoint used to be unreachable because EventSource cannot attach the auth header
     * and the exact-match whitelist never contained the parameterized path.
     */
    @Test
    void pluginLogStreamTicketsRedeemOnAnyConcretePluginId() {
        StreamTicketService service = new StreamTicketService();
        StreamTicketService.IssuedTicket issued =
                service.issue(StreamTicketService.PLUGIN_LOG_STREAM_PATTERN);

        String markdown = "/api/plugin-runtime/fan.summer.markdown/logs/stream";
        String excel = "/api/plugin-runtime/fan.summer.excel/logs/stream";
        assertTrue(service.redeem(issued.ticket(), markdown),
                "the pattern-bound ticket redeems on a concrete plugin log stream");

        // Single-use like every ticket: a second redemption (other plugin or the same) fails.
        StreamTicketService.IssuedTicket second =
                service.issue(StreamTicketService.PLUGIN_LOG_STREAM_PATTERN);
        assertFalse(service.redeem(second.ticket(), excel + "/extra"),
                "the wildcard must not overshoot the pattern suffix");
        assertFalse(service.redeem(second.ticket(), markdown),
                "the overshoot attempt consumed the single-use ticket");

        // A ticket for another stream still cannot open a plugin log stream, and vice versa.
        StreamTicketService.IssuedTicket aiTicket =
                service.issue(StreamTicketService.AI_STREAM_ENDPOINT);
        assertFalse(service.redeem(aiTicket.ticket(), markdown));
        StreamTicketService.IssuedTicket logTicket =
                service.issue(StreamTicketService.PLUGIN_LOG_STREAM_PATTERN);
        assertFalse(service.redeem(logTicket.ticket(), StreamTicketService.AI_STREAM_ENDPOINT));
    }

    @Test
    void ticketEndpointForResolvesWildcardPatternsAndRejectsOthers() {
        // TokenAuthFilter routes ?ticket= redemptions through this mapping.
        assertEquals(StreamTicketService.AI_STREAM_ENDPOINT,
                StreamTicketService.ticketEndpointFor("/api/ai/stream"));
        assertEquals(StreamTicketService.PLUGIN_LOG_STREAM_PATTERN,
                StreamTicketService.ticketEndpointFor("/api/plugin-runtime/x/logs/stream"));
        assertEquals(StreamTicketService.PLUGIN_LOG_STREAM_PATTERN,
                StreamTicketService.ticketEndpointFor("/api/plugin-runtime/a/long/id/logs/stream"));
        assertEquals("/api/notifications/stream",
                StreamTicketService.ticketEndpointFor("/api/notifications/stream"));
        assertNull(StreamTicketService.ticketEndpointFor("/api/something/else"));
        assertNull(StreamTicketService.ticketEndpointFor("/api/plugin-runtime/x/logs/stream/extra"));
        assertNull(StreamTicketService.ticketEndpointFor(null));
        assertNull(StreamTicketService.ticketEndpointFor("/api/plugin-runtime//logs/stream"),
                "the wildcard needs at least one path character");
    }

    @Test
    void arbitraryWildcardBindingsCannotBeIssued() {
        StreamTicketService service = new StreamTicketService();
        // issue() is not restricted, but redemption only honors wildcards for REGISTERED
        // endpoints: an ad-hoc "/anything/*" binding can never widen into a pattern match.
        StreamTicketService.IssuedTicket rogue = service.issue("/anything/*");
        assertFalse(service.redeem(rogue.ticket(), "/anything/else"));
        assertFalse(service.redeem(rogue.ticket(), "/anything/*"));
    }
}
