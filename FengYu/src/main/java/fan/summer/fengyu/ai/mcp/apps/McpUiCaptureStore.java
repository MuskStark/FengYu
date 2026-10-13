package fan.summer.fengyu.ai.mcp.apps;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captured raw MCP tool results for UI-capable tools, waiting to be attached to the SSE tool
 * event. Spring AI delivers tool callbacks as opaque {@code String call(toolInput)} invocations
 * with no call identity, while the AI loop fires result events keyed by the model's call id —
 * the two sides never see the same key. Both CAN see the same deterministic pair
 * {@code wireName + raw-args-json}, so the callback fulfills that key and
 * {@link McpUiRuntimeHolder#attachUi} drains it when the result event is built.
 *
 * <p>Bounded by design: at most {@link #MAX_RESULTS} fulfilled results live at once and each
 * expires after {@link #TTL_MS}; anything unconsumed is garbage-collected by expiry, so a
 * dropped SSE frame cannot leak memory.</p>
 */
public final class McpUiCaptureStore {

    private static final int MAX_RESULTS = 64;
    private static final long TTL_MS = 5 * 60 * 1000L;

    private record Entry(McpToolUiMeta.CapturedResult result, long at) {}

    private final Map<String, Deque<Entry>> results = new ConcurrentHashMap<>();

    /** Deterministic capture key shared by the callback side and the event side. */
    public static String key(String wireName, String argsJson) {
        return wireName + "#" + sha256(argsJson == null ? "" : argsJson);
    }

    /** Callback side: stores one raw result. Never blocks; oldest entries evicted past the cap. */
    public void fulfill(String wireName, String argsJson, McpToolUiMeta.CapturedResult result) {
        if (result == null) return;
        String key = key(wireName, argsJson);
        Deque<Entry> deque = results.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(new Entry(result, System.currentTimeMillis()));
            while (deque.size() > 4) deque.removeFirst();
        }
        if (results.size() > MAX_RESULTS) {
            long cutoff = System.currentTimeMillis() - TTL_MS;
            Iterator<Map.Entry<String, Deque<Entry>>> it = results.entrySet().iterator();
            while (results.size() > MAX_RESULTS && it.hasNext()) {
                Deque<Entry> candidate = it.next().getValue();
                synchronized (candidate) {
                    // Expired or empty keys go first; a fresh burst of parallel calls keeps its
                    // entries and lets the arbitrary-key fallback below bound the map instead.
                    if (candidate.isEmpty() || candidate.peekFirst().at() < cutoff) it.remove();
                }
            }
            if (results.size() > MAX_RESULTS) {
                Iterator<String> keys = results.keySet().iterator();
                if (keys.hasNext()) {
                    results.remove(keys.next());
                }
            }
        }
    }

    /** Event side: takes the oldest fulfilled result for the key, or null when none is waiting. */
    public McpToolUiMeta.CapturedResult take(String wireName, String argsJson) {
        String key = key(wireName, argsJson);
        Deque<Entry> deque = results.get(key);
        if (deque == null) return null;
        synchronized (deque) {
            Entry entry = deque.pollFirst();
            if (deque.isEmpty()) results.remove(key, deque);
            return entry == null ? null : entry.result();
        }
    }

    /** Drops everything (server rebuild/stop). */
    public void clear() {
        results.clear();
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every supported JCE provider; unreachable in practice.
            return Integer.toHexString(value.hashCode());
        }
    }

    /** Test/diagnostics view. */
    Map<String, Deque<Entry>> snapshot() {
        return new LinkedHashMap<>(results);
    }
}
