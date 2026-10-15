package fan.summer.fengyu.ai.mcp.apps;

import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory registry of prepared MCP Apps sandbox pages. The web UI {@code prepare}s a page
 * (the backend fetches and validates the {@code ui://} HTML once), and the sandbox origin
 * (a dedicated connector port on 127.0.0.1 — see {@code SandboxConnectorConfig}) serves the
 * registered document keyed by the returned sandboxId. The id is the capability: an
 * unguessable random 128-bit token whose only power is loading that one page.
 *
 * <p>Pages also drive MCP Apps tool visibility: an app-only ({@code visibility: ["app"]})
 * server tool joins the model's catalog exactly while this registry holds a live page for its
 * server ("claiming"). Every registration change reports the per-server live count to
 * {@link McpRuntimeManager}, which refreshes its catalog on the 0↔1 transition.
 *
 * <p>Entries are bounded ({@link #MAX_SANDBOXES}) and expire after {@link #TTL_MS}; the web UI
 * also disposes explicitly when a card unmounts. Nothing here persists — a host restart
 * forgets every page, and the browser side simply re-prepares.</p>
 */
@Service
public class McpSandboxRegistry {

    static final int MAX_SANDBOXES = 64;
    static final long TTL_MS = 30 * 60 * 1000L;

    /** One registered sandbox page and everything the two sandbox endpoints need to serve it. */
    public record SandboxEntry(
            String sandboxId,
            String serverId,
            String serverName,
            String resourceUri,
            String scopeId,
            String html,
            McpToolUiMeta.McpUiCsp csp,
            McpToolUiMeta.McpAppResourceMeta resourceMeta,
            long createdAt,
            long seq) {}

    private final Map<String, SandboxEntry> entries = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> pagesPerServer =
            new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final AtomicLong insertionSeq = new AtomicLong();
    private final McpRuntimeManager runtime;

    public McpSandboxRegistry(McpRuntimeManager runtime) {
        this.runtime = runtime;
    }

    /** Registers one prepared page; evicts the oldest-inserted entries past the cap. */
    public SandboxEntry register(String serverId, String serverName, String resourceUri,
                                 String scopeId, String html,
                                 McpToolUiMeta.McpUiCsp csp,
                                 McpToolUiMeta.McpAppResourceMeta resourceMeta) {
        sweep();
        if (entries.size() >= MAX_SANDBOXES) {
            entries.values().stream()
                    .min((a, b) -> Long.compare(a.seq(), b.seq()))
                    .ifPresent(oldest -> removeEntry(oldest));
        }
        String sandboxId = newSandboxId();
        SandboxEntry entry = new SandboxEntry(sandboxId, serverId, serverName, resourceUri, scopeId,
                html, csp, resourceMeta, System.currentTimeMillis(), insertionSeq.incrementAndGet());
        entries.put(sandboxId, entry);
        java.util.concurrent.atomic.AtomicInteger count = pagesPerServer.computeIfAbsent(
                serverId, ignored -> new java.util.concurrent.atomic.AtomicInteger());
        runtime.onUiPageCountChanged(serverId, count.incrementAndGet());
        return entry;
    }

    /** Read-for-serve; liveness is the creation-time TTL, refreshed only by re-prepare. */
    public SandboxEntry get(String sandboxId) {
        if (sandboxId == null) return null;
        SandboxEntry entry = entries.get(sandboxId);
        if (entry == null) return null;
        // The sweep only runs on register(); serve-time expiry keeps an idle host honest too.
        if (System.currentTimeMillis() - entry.createdAt() > TTL_MS) {
            removeEntry(entry);
            return null;
        }
        return entry;
    }

    /** Explicit teardown when the hosting card/panel unmounts. */
    public boolean dispose(String sandboxId) {
        if (sandboxId == null) return false;
        SandboxEntry entry = entries.get(sandboxId);
        if (entry == null) return false;
        removeEntry(entry);
        return true;
    }

    /** Removes every sandbox bound to one server (server deleted/disabled). */
    public void disposeServer(String serverId) {
        if (serverId == null) return;
        entries.values().stream()
                .filter(entry -> serverId.equals(entry.serverId()))
                .toList()
                .forEach(this::removeEntry);
    }

    public int size() {
        return entries.size();
    }

    /** Drops entries idle past the TTL. */
    void sweep() {
        long cutoff = System.currentTimeMillis() - TTL_MS;
        entries.values().stream()
                .filter(entry -> entry.createdAt() < cutoff)
                .toList()
                .forEach(this::removeEntry);
    }

    private void removeEntry(SandboxEntry entry) {
        if (entries.remove(entry.sandboxId(), entry)) {
            java.util.concurrent.atomic.AtomicInteger count =
                    pagesPerServer.get(entry.serverId());
            if (count != null) {
                int remaining = count.decrementAndGet();
                runtime.onUiPageCountChanged(entry.serverId(), Math.max(0, remaining));
                if (remaining <= 0) {
                    // Drop the zeroed counter so server ids never accumulate for the process lifetime.
                    pagesPerServer.remove(entry.serverId(), count);
                }
            }
        }
    }

    /** Live page count for one server (drives app-only tool claiming). */
    public int openPages(String serverId) {
        java.util.concurrent.atomic.AtomicInteger count = pagesPerServer.get(serverId);
        return count == null ? 0 : Math.max(0, count.get());
    }

    private String newSandboxId() {
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** Test/diagnostic view. */
    List<SandboxEntry> snapshot() {
        return new ArrayList<>(entries.values());
    }
}
