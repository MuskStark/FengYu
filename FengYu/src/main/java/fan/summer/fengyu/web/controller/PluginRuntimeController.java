package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.market.ManifestI18n;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.InstalledPluginDescriptor;
import fan.summer.fengyu.plugin.runtime.PluginLogEntry;
import fan.summer.fengyu.plugin.runtime.PluginLogStore;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@RestController
public class PluginRuntimeController {
    private static final Logger log = LoggerFactory.getLogger(PluginRuntimeController.class);
    static final String PLUGIN_CONTENT_SECURITY_POLICY =
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; font-src 'self' data:; connect-src 'none'; "
            + "object-src 'none'; base-uri 'none'";

    private final PluginPackageService packages;
    private final PluginProcessManager processes;
    private final PluginLogStore logStore;

    public PluginRuntimeController(PluginPackageService packages, PluginProcessManager processes,
            PluginLogStore logStore) {
        this.packages = packages;
        this.processes = processes;
        this.logStore = logStore;
    }

    @GetMapping("/api/plugin-runtime")
    public List<InstalledPluginDescriptor> plugins(
            @org.springframework.web.bind.annotation.RequestHeader(name = "Accept-Language", required = false) String acceptLanguage) {
        // Resolve locale from the header directly and pass it down, matching the marketplace path.
        String locale = ManifestI18n.resolveLocale(acceptLanguage);
        return packages.installed().stream()
                .filter(m -> packages.isEnabled(m.id()))
                .map(m -> descriptor(m, locale)).toList();
    }

    @PostMapping("/api/plugin-runtime/{id}/invoke")
    public Object invoke(@PathVariable String id, @RequestBody InvokeRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(name = "Accept-Language", required = false) String acceptLanguage) {
        // Resolve the request locale (same resolver as the plugin list above) and thread it into the
        // worker call so plugin summaries/errors render in the user's language. Null header → default
        // locale (en), matching pre-i18n behaviour.
        String locale = ManifestI18n.resolveLocale(acceptLanguage);
        return processes.invokeTracked(id, request.callId(), request.method(), request.params(), locale);
    }

    @PostMapping("/api/plugin-runtime/{id}/invoke/{callId}/cancel")
    public Map<String, Object> cancelInvoke(@PathVariable String id, @PathVariable String callId) {
        return Map.of("cancelled", processes.cancel(id, callId));
    }

    /**
     * Recent captured log lines for a plugin (REST fallback for non-SSE clients). Returns oldest-first,
     * up to {@code maxLines} (default 200). Empty list if the plugin has no captured output yet.
     */
    @GetMapping("/api/plugin-runtime/{id}/logs")
    public List<PluginLogEntry> logs(@PathVariable String id,
            @RequestParam(name = "maxLines", defaultValue = "200") int maxLines) {
        return logStore.recent(id, maxLines);
    }

    /**
     * Live log stream as {@code text/event-stream}: replays the buffered history, then pushes each
     * newly captured line as a named {@code log} event. The connection is an infinite tail (no
     * {@code done} event) mirroring a console — the client closes it when done. Modelled on
     * {@code AgentController}'s sink pattern but simpler: there is no terminal state.
     *
     * <p><b>Dead-subscriber cleanup.</b> A send failure (the client closed, a network error) is not
     * merely logged: the subscriber is unregistered immediately and the emitter completed, so a dead
     * connection never keeps receiving (and its drain thread is freed). This is idempotent — the
     * {@code unsubscribe} runnable and {@code emitter.complete()} are both safe to call repeatedly.
     *
     * <p><b>Replay ordering.</b> The live subscriber is registered in a paused state, the buffered
     * history is replayed, and only then is its FIFO drainer activated. Concurrent appends queue
     * behind that barrier, so live delivery cannot overtake history and no mutable array/high-water
     * data race is needed.
     */
    @GetMapping(value = "/api/plugin-runtime/{id}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter logStream(@PathVariable String id) {
        // No timeout: the stream is a long-lived console; the client (or emitter error/timeout)
        // ends it. Subscribers are unregistered on every terminal callback so dead clients don't leak.
        SseEmitter emitter = new SseEmitter(0L);
        // The subscription does not start its live drainer until activate(), below. An AtomicReference
        // lets the live failure path own lifecycle cleanup without the ordinary-array publication race
        // of the previous high-water holder.
        AtomicReference<Runnable> unsubscribeRef = new AtomicReference<>(() -> {});
        Consumer<PluginLogEntry> subscriber = entry -> {
            if (!sendLogEntry(emitter, id, entry)) unsubscribeRef.get().run();
        };
        PluginLogStore.Subscription subscription = logStore.subscribeWithSnapshot(id, subscriber);
        Runnable unsubscribe = subscription.unsubscribe();
        unsubscribeRef.set(unsubscribe);
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(unsubscribe);
        emitter.onError(ignored -> unsubscribe.run());
        // Replay the snapshot (taken atomically with the subscribe) so a late client sees context.
        for (PluginLogEntry entry : subscription.snapshot()) {
            if (!sendLogEntry(emitter, id, entry)) {
                // The callbacks above are not guaranteed to run synchronously from complete(); own
                // cleanup here so a failure during replay cannot leave a paused subscriber registered.
                unsubscribe.run();
                return emitter;
            }
        }
        subscription.activate();
        return emitter;
    }

    /**
     * Send one log entry over the SSE emitter. On failure the subscriber is considered dead: the
     * emitter is completed (idempotent) and the caller's terminal callbacks unregister it. Returns
     * {@code false} when the send failed so the replay loop can stop early.
     */
    protected boolean sendLogEntry(SseEmitter emitter, String id, PluginLogEntry entry) {
        try {
            emitter.send(SseEmitter.event().name("log").data(entry, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("plugin {}: SSE log send failed: {}", id, e.getMessage());
            try { emitter.complete(); } catch (Exception ignored) {}
            return false;
        }
    }

    @GetMapping("/plugin-runtime/{id}/**")
    public ResponseEntity<Resource> asset(@PathVariable String id, HttpServletRequest request) {
        PluginManifest manifest = packages.find(id).orElse(null);
        if (manifest == null || !packages.isEnabled(id)) return ResponseEntity.notFound().build();
        String full = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String prefix = "/plugin-runtime/" + id + "/";
        String relative = full.startsWith(prefix) ? full.substring(prefix.length()) : "";
        if (relative.isBlank()) relative = manifest.ui().entry();
        Path path = packages.asset(id, relative);
        if (!Files.isRegularFile(path)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
            .contentType(contentType(path.getFileName().toString()))
            // No Access-Control-Allow-Origin: the iframe embedding this page runs same-site
            // (loopback shell) and needs no CORS; a wildcard here would let any website read
            // installed-plugin package bytes (UI code, worker.jar) cross-origin.
            .header("Content-Security-Policy", PLUGIN_CONTENT_SECURITY_POLICY)
            .header("X-Content-Type-Options", "nosniff")
            .body(new FileSystemResource(path));
    }

    private InstalledPluginDescriptor descriptor(PluginManifest m, String locale) {
        return new InstalledPluginDescriptor(m.id(), ManifestI18n.name(m, locale), ManifestI18n.description(m, locale),
            m.category() == null ? "OTHER" : m.category().toUpperCase(), m.icon(), m.version(),
            "/plugin-runtime/" + m.id() + "/" + m.ui().entry(), m.author(),
            m.permissions() == null ? List.of() : m.permissions(), packages.isEnabled(m.id()),
            "BLUE", m.aiTools() != null && !m.aiTools().isEmpty(), m.official() ? "OFFICIAL" : "THIRD_PARTY");
    }

    static MediaType contentType(String name) {
        if (name.endsWith(".html")) return utf8("text", "html");
        if (name.endsWith(".js") || name.endsWith(".mjs")) return utf8("text", "javascript");
        if (name.endsWith(".css")) return utf8("text", "css");
        if (name.endsWith(".json")) return utf8("application", "json");
        if (name.endsWith(".svg")) return MediaType.parseMediaType("image/svg+xml");
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private static MediaType utf8(String type, String subtype) {
        return new MediaType(type, subtype, StandardCharsets.UTF_8);
    }

    public record InvokeRequest(String callId, String method, Map<String, Object> params) {}
}
