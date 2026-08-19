package fan.summer.fengyu.web;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One-time tickets for the SSE {@code EventSource} endpoints.
 *
 * <p>{@code EventSource} cannot attach request headers, so the stream endpoints
 * ({@code GET /api/ai/stream}, {@code GET /api/agent/stream},
 * {@code GET /api/notifications/stream}) historically accepted the auth
 * token as {@code ?token=} — which leaks it into every URL-capturing layer (proxy access logs,
 * shell history, webview diagnostics). Instead, an authenticated client first POSTs to a
 * {@code /stream-ticket} endpoint (header-authenticated) and receives a random ticket that:
 *
 * <ul>
 *   <li>is valid for {@link #TTL_SECONDS} seconds and a SINGLE redemption — a ticket observed
 *       in a log is worthless once used, and replaying it before use still only opens a
 *       stream, never the wider API;</li>
 *   <li>is bound to ONE stream endpoint (the issuer names it; the redeemer must match), so a
 *       ticket minted for the AI stream can never open the agent stream instead;</li>
 *   <li>carries none of the token's authority — it authorizes exactly one stream
 *       connection.</li>
 * </ul>
 *
 * <p>Tickets live in memory only; outstanding tickets are capped and expired ones are swept on
 * every issue, so the map cannot grow without bound.
 */
@Component
public class StreamTicketService {

    static final long TTL_SECONDS = 60;
    static final int MAX_OUTSTANDING = 10_000;
    /** The endpoints a ticket may be bound to (see {@link #issue(String)}). */
    public static final String AI_STREAM_ENDPOINT = "/api/ai/stream";
    public static final String AGENT_STREAM_ENDPOINT = "/api/agent/stream";
    public static final String NOTIFICATION_STREAM_ENDPOINT = "/api/notifications/stream";
    /** Membership check used by {@code TokenAuthFilter} to route {@code ?ticket=} redemptions. */
    public static final java.util.Set<String> STREAM_ENDPOINTS = java.util.Set.of(
            AI_STREAM_ENDPOINT, AGENT_STREAM_ENDPOINT, NOTIFICATION_STREAM_ENDPOINT);

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final AtomicLong issued = new AtomicLong();

    public StreamTicketService() {
        this(Clock.systemUTC());
    }

    StreamTicketService(Clock clock) {
        this.clock = clock;
    }

    /** One issued, not-yet-redeemed ticket. */
    public record IssuedTicket(String ticket, Instant expiresAt) {}

    /** The stored ticket: its expiry and the single endpoint it may be redeemed on. */
    private record Ticket(Instant expiresAt, String endpoint) {}

    /** Mints a fresh single-use ticket for {@code endpoint} (valid {@link #TTL_SECONDS}s from now). */
    public IssuedTicket issue(String endpoint) {
        Instant now = clock.instant();
        sweep(now);
        if (tickets.size() >= MAX_OUTSTANDING) {
            throw new IllegalStateException("Too many outstanding stream tickets");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = now.plusSeconds(TTL_SECONDS);
        tickets.put(ticket, new Ticket(expiresAt, endpoint));
        issued.incrementAndGet();
        return new IssuedTicket(ticket, expiresAt);
    }

    /**
     * Redeems a ticket: succeeds exactly once, only before its expiry, and only on the endpoint
     * it was minted for. Constant-shape work either way (a single map operation) so redemption
     * offers no timing oracle on ticket values.
     */
    public boolean redeem(String ticket, String endpoint) {
        if (ticket == null || ticket.isBlank()) return false;
        Ticket entry = tickets.remove(ticket);
        return entry != null
                && entry.endpoint().equals(endpoint)
                && entry.expiresAt().isAfter(clock.instant());
    }

    /** Test/diagnostic counters. */
    public long issuedCount() {
        return issued.get();
    }

    public int outstanding() {
        return tickets.size();
    }

    private void sweep(Instant now) {
        if (tickets.isEmpty()) return;
        Iterator<Map.Entry<String, Ticket>> it = tickets.entrySet().iterator();
        while (it.hasNext()) {
            if (!it.next().getValue().expiresAt().isAfter(now)) it.remove();
        }
    }
}
