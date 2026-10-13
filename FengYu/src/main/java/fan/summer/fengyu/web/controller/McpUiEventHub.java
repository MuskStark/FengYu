package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fan-out hub for MCP Apps events that must REACH THE BROWSER: resource-update notifications
 * (an MCP server pushed new contents for a resource a sandbox page subscribed to). One SSE
 * stream per browser session; every open page's bridge consumes it.
 *
 * <p>Also owns the (server, uri) reference count for page resource subscriptions, so N pages
 * watching one resource cost exactly one MCP subscription.</p>
 */
@Service
public class McpUiEventHub {

    private static final Logger log = LoggerFactory.getLogger(McpUiEventHub.class);
    /** Streaming responses are the one place a long-lived emitter is correct; heartbeatless idle is fine. */
    private static final long EMITTER_TIMEOUT_MS = 0L;

    private final CopyOnWriteArrayList<SseEmitter> clients = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> subscriptions = new java.util.concurrent.ConcurrentHashMap<>();
    private final McpRuntimeManager runtime;

    public McpUiEventHub(McpRuntimeManager runtime) {
        this.runtime = runtime;
        // The manager takes the LAST listener; there is exactly one hub per APP context.
        runtime.setUiUpdateListener(this::resourceUpdated);
    }

    /** Opens one browser event stream. Completion/timeout/error all unregister it. */
    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        clients.add(emitter);
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> clients.remove(emitter));
        emitter.onError(ignored -> clients.remove(emitter));
        return emitter;
    }

    /** Reference-counted subscribe; the MCP subscribe fires when the FIRST holder arrives. */
    public void acquireSubscription(String serverId, String uri) {
        String key = serverId + "#" + uri;
        int holders = subscriptions.computeIfAbsent(key, ignored -> new AtomicInteger())
                .incrementAndGet();
        if (holders == 1) {
            try {
                runtime.subscribeResource(serverId, uri);
            } catch (RuntimeException firstHolderFailed) {
                subscriptions.remove(key);
                throw firstHolderFailed;
            }
        }
    }

    /** Reference-counted unsubscribe; the MCP unsubscribe fires when the LAST holder leaves. */
    public void releaseSubscription(String serverId, String uri) {
        String key = serverId + "#" + uri;
        AtomicInteger counter = subscriptions.get(key);
        if (counter == null) return;
        if (counter.decrementAndGet() <= 0) {
            subscriptions.remove(key);
            try {
                runtime.unsubscribeResource(serverId, uri);
            } catch (RuntimeException teardownFailure) {
                log.debug("MCP resource unsubscribe failed (server may be gone): {}",
                        teardownFailure.toString());
            }
        }
    }

    /** Manager callback: a subscribed resource changed on some server. */
    void resourceUpdated(String serverId, String uri) {
        broadcast("resource-updated", Map.of("serverId", serverId, "uri", uri == null ? "" : uri));
    }

    private void broadcast(String event, Map<String, Object> payload) {
        for (SseEmitter client : clients) {
            try {
                client.send(SseEmitter.event().name(event).data(payload));
            } catch (Exception deadStream) {
                clients.remove(client);
            }
        }
    }

    public int clientCount() {
        return clients.size();
    }
}
