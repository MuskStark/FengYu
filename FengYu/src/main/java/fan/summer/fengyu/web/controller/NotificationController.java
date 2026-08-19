package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.notification.NotificationService;
import fan.summer.fengyu.notification.NotificationView;
import fan.summer.fengyu.web.StreamTicketService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * REST + SSE surface of the host-side unified notification center over
 * {@code /api/notifications}:
 * <ul>
 *   <li>{@code POST /api/notifications} — create one (the plugin {@code notify} host bridge
 *       and any host capability post here; the row is persisted AND fanned out live).</li>
 *   <li>{@code GET /api/notifications?limit=&unreadOnly=} — newest-first history.</li>
 *   <li>{@code GET /api/notifications/unread-count} — badge counter.</li>
 *   <li>{@code POST /api/notifications/{id}/read} / {@code POST /api/notifications/read-all}
 *       — acknowledge.</li>
 *   <li>{@code DELETE /api/notifications/{id}} — remove from the center.</li>
 *   <li>{@code POST /api/notifications/stream-ticket} + {@code GET /api/notifications/stream}
 *       — the live push channel (same one-time-ticket pattern as the AI/agent streams:
 *       {@code EventSource} cannot send the header token, and the full credential must not
 *       ride in a URL that logs capture).</li>
 * </ul>
 *
 * <p>The stream carries one named event, {@code notification}, whose data is a
 * {@link NotificationView} JSON object. History is NOT replayed on the stream — clients load
 * it with the REST list and dedupe live events by {@code id}. A 25-second comment heartbeat
 * keeps intermediate HTTP stacks from reaping an otherwise-idle connection.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(25);

    private final NotificationService notifications;
    private final StreamTicketService streamTickets;

    public NotificationController(NotificationService notifications, StreamTicketService streamTickets) {
        this.notifications = notifications;
        this.streamTickets = streamTickets;
    }

    @PostMapping
    public ResponseEntity<NotificationView> create(@RequestBody CreateNotificationRequest req) {
        NotificationView view = notifications.create(
                req.source(), req.level(), req.title(), req.body(), req.link());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping
    public List<NotificationView> list(@RequestParam(required = false, defaultValue = "50") Integer limit,
            @RequestParam(required = false, defaultValue = "false") boolean unreadOnly) {
        return notifications.list(limit == null ? 50 : limit, unreadOnly);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", notifications.unreadCount());
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<NotificationView> markRead(@PathVariable Long id) {
        return notifications.markRead(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead() {
        return Map.of("marked", notifications.markAllRead());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return notifications.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** Mints the one-time ticket {@code GET /api/notifications/stream} redeems via {@code ?ticket=}. */
    @PostMapping("/stream-ticket")
    public Map<String, Object> streamTicket() {
        var issued = streamTickets.issue(StreamTicketService.NOTIFICATION_STREAM_ENDPOINT);
        return Map.of("ticket", issued.ticket(), "expiresAt", issued.expiresAt().toString());
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return new NotificationStream().start(notifications);
    }

    // ── SSE lifecycle ─────────────────────────────────────────────────

    /**
     * One live notification stream: a subscribed broadcaster callback, a heartbeat thread,
     * and a single idempotent {@link #close()} that unregisters the subscriber whenever the
     * emitter ends (client disconnect, error, timeout) so dead clients never accumulate.
     */
    static final class NotificationStream {

        private final SseEmitter emitter = new SseEmitter(0L); // no timeout — the shell keeps it open
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile Runnable unsubscribe = () -> {};

        SseEmitter start(NotificationService notifications) {
            this.unsubscribe = notifications.subscribe(this::deliver);
            emitter.onCompletion(this::close);
            emitter.onTimeout(() -> {
                close();
                emitter.complete();
            });
            emitter.onError(ex -> close());
            Thread.ofVirtual().name("notification-sse-heartbeat").start(this::heartbeat);
            return emitter;
        }

        private void deliver(NotificationView view) {
            try {
                emitter.send(SseEmitter.event().name("notification")
                        .data(view, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                // Client is gone — unregister and let the container reclaim the connection.
                close();
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // Already completed by the container.
                }
            }
        }

        private void heartbeat() {
            while (!closed.get()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (closed.get()) return;
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException | IllegalStateException e) {
                    close();
                    return;
                }
            }
        }

        private void close() {
            if (closed.compareAndSet(false, true)) unsubscribe.run();
        }
    }

    // ── DTOs ──────────────────────────────────────────────────────────

    /**
     * {@code POST /api/notifications} body. {@code source} names the originator
     * ("plugin:<id>", "host", ...); {@code level} is info|success|warning|error.
     */
    public record CreateNotificationRequest(
            String source, String level, String title, String body, String link) {}
}
