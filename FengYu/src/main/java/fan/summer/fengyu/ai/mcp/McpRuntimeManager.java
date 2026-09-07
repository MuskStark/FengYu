package fan.summer.fengyu.ai.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.runtime.RuntimePaths;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns MCP connections that can be changed without restarting FengYu.
 *
 * <p>The Spring AI starter is intentionally startup-scoped. This manager uses the same official
 * MCP SDK transports, but owns the client lifecycle itself so a saved server is connected now,
 * its tools are immediately visible to the live AI registry, and an update replaces the old
 * process/session safely.</p>
 *
 * <p>Tool names are namespaced per server ({@code <server>__<tool>}, produced from
 * the client identity), so permission rules can target one server and two servers can expose
 * the same tool name without colliding. Tools the user disabled for a server never reach the AI
 * catalog. The tool catalog itself is a cached snapshot: reading it never performs a live MCP
 * round trip, so a dead or slow server cannot block chat startup.</p>
 *
 * <h2>Liveness model</h2>
 * <p>Startup connects every enabled server <em>concurrently</em> under one shared wall-clock
 * budget; an attempt that misses the budget is abandoned and the server lands in
 * {@code error} state. Error-state servers are retried by a background sweep with
 * exponential backoff (30s doubling to a 10-minute cap), and a tool call whose failure looks
 * like a dead connection marks that server invalid and triggers one immediate rebuild —
 * a crashed server therefore self-heals instead of leaving dead callbacks in the catalog
 * until someone presses Test.</p>
 */
@Service
public final class McpRuntimeManager {

    private static final Logger log = LoggerFactory.getLogger(McpRuntimeManager.class);
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_INIT_TIMEOUT_SECONDS = 30;
    private static final int MIN_TIMEOUT_SECONDS = 5;
    private static final int MAX_REQUEST_TIMEOUT_SECONDS = 600;
    private static final int MAX_INIT_TIMEOUT_SECONDS = 300;
    private static final String REGISTRY_FILE = "servers.json";
    private static final String SECRETS_FILE = "secrets.json";
    private static final String HOST_VERSION = "4.0.0";

    /** Total startup wall-clock budget across ALL servers (P2-5). */
    static final Duration DEFAULT_STARTUP_CONNECT_BUDGET = Duration.ofSeconds(60);
    /** First retry of an error-state server; doubles per failed attempt. */
    static final Duration DEFAULT_INITIAL_RETRY_DELAY = Duration.ofSeconds(30);
    /** Backoff ceiling for error-state servers. */
    static final Duration DEFAULT_MAX_RETRY_DELAY = Duration.ofMinutes(10);
    /** How often the background sweep looks for due reconnects. */
    static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofSeconds(15);

    /**
     * Environment keys never passed to a dynamic STDIO server. A server command is already
     * arbitrary code execution by design, but these keys inject code into the interpreter the
     * command runs on (Node/JVM/dynamic linker), which turns a "run this tool" decision into a
     * persistent host compromise. Same rationale as cherry-studio's DXT/MCPB import denylist.
     */
    private static final Set<String> DENIED_ENV_KEYS = Set.of(
            "NODE_OPTIONS", "NPM_CONFIG_NODE_OPTIONS", "NODE_PATH",
            "JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS",
            "LD_PRELOAD", "LD_LIBRARY_PATH", "PYTHONPATH");
    private static final List<String> DENIED_ENV_PREFIXES = List.of("DYLD_");

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final Path directory;
    private final Path registryFile;
    private final Path secretsFile;
    private final Map<String, StoredServer> definitions = new LinkedHashMap<>();
    /** Servers contributed by installed agent-content plugins ({@code mcp-servers/<uid>.json}); never persisted here. */
    private final Map<String, StoredServer> imported = new LinkedHashMap<>();
    private final Map<String, SecretConfig> importedSecrets = new LinkedHashMap<>();
    private final Map<String, ManagedServer> connections = new ConcurrentHashMap<>();
    private final Map<String, String> toolPrefixes = new LinkedHashMap<>();
    private final ReentrantLock lifecycle = new ReentrantLock();
    private volatile List<ToolCallback> callbacksSnapshot = List.of();

    /** Reconnect bookkeeping (error-state servers only); keyed by server id. */
    private final Map<String, ReconnectState> reconnectState = new ConcurrentHashMap<>();
    /** Connects run on virtual threads: an init handshake may block for minutes. */
    private final ExecutorService connectExecutor;
    /** Background sweep that retries error-state servers with exponential backoff (P2-4). */
    private final ScheduledExecutorService reconnectScheduler;
    private final Duration startupConnectBudget;
    private final Duration initialRetryDelay;
    private final Duration maxRetryDelay;
    private final Duration sweepInterval;
    /** False once {@link #stop()} ran; guards background work on a shut-down instance. */
    private volatile boolean stopped = false;

    public McpRuntimeManager() {
        this(RuntimePaths.root(), null);
    }

    /** Focused-test constructor; production uses the canonical runtime root. */
    public McpRuntimeManager(Path runtimeRoot) {
        this(runtimeRoot, null);
    }

    /** Focused-test constructor with accelerated reconnect/startup timings. */
    McpRuntimeManager(Path runtimeRoot, ReconnectTimings timings) {
        this.directory = runtimeRoot.resolve("mcp-servers").toAbsolutePath().normalize();
        this.registryFile = directory.resolve(REGISTRY_FILE);
        this.secretsFile = directory.resolve(SECRETS_FILE);
        this.startupConnectBudget = orDefault(
                timings == null ? null : timings.startupConnectBudget(), DEFAULT_STARTUP_CONNECT_BUDGET);
        this.initialRetryDelay = orDefault(
                timings == null ? null : timings.initialRetryDelay(), DEFAULT_INITIAL_RETRY_DELAY);
        this.maxRetryDelay = orDefault(
                timings == null ? null : timings.maxRetryDelay(), DEFAULT_MAX_RETRY_DELAY);
        this.sweepInterval = orDefault(
                timings == null ? null : timings.sweepInterval(), DEFAULT_SWEEP_INTERVAL);
        this.connectExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("mcp-connect-", 0).factory());
        this.reconnectScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread sweep = new Thread(task, "mcp-reconnect-sweep");
            sweep.setDaemon(true);
            return sweep;
        });
        this.reconnectScheduler.scheduleWithFixedDelay(this::sweepForReconnects,
                sweepInterval.toMillis(), sweepInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static Duration orDefault(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }

    /** Timing knobs for {@link #McpRuntimeManager(Path, ReconnectTimings)}; null entries default. */
    record ReconnectTimings(Duration startupConnectBudget, Duration initialRetryDelay,
                            Duration maxRetryDelay, Duration sweepInterval) {}

    @PostConstruct
    public void start() {
        lifecycle.lock();
        try {
            load();
            syncImportedServersLocked();
            rebuildPrefixesLocked();
            // Connect every enabled server CONCURRENTLY under one shared budget (P2-5):
            // the old serial loop added per-server init timeouts (up to 300s each), so a
            // handful of slow servers could stall startup for many minutes.
            List<StoredServer> enabled = new ArrayList<>();
            for (StoredServer definition : definitions.values()) {
                if (definition.enabled()) enabled.add(definition);
            }
            connectAllLocked(enabled);
            refreshProvider();
        } finally {
            lifecycle.unlock();
        }
    }

    @PreDestroy
    public void stop() {
        stopped = true;
        reconnectScheduler.shutdownNow();
        connectExecutor.shutdownNow();
        lifecycle.lock();
        try {
            for (ManagedServer connection : connections.values()) closeQuietly(connection.client());
            connections.clear();
            reconnectState.clear();
            imported.clear();
            importedSecrets.clear();
            callbacksSnapshot = List.of();
        } finally {
            lifecycle.unlock();
        }
    }

    /** Cached tool catalog; no MCP round trip, so a dead server cannot stall the AI registry. */
    public List<ToolCallback> callbacks() {
        return callbacksSnapshot;
    }

    public List<ServerView> servers() {
        lifecycle.lock();
        try {
            List<ServerView> views = new ArrayList<>();
            for (StoredServer definition : allDefinitionsLocked()) views.add(view(definition));
            return List.copyOf(views);
        } finally {
            lifecycle.unlock();
        }
    }

    public ServerView save(ServerRequest request, String id) {
        lifecycle.lock();
        try {
            boolean exists = definitions.containsKey(id);
            if (id != null && !exists && !imported.containsKey(id)) {
                throw new McpRuntimeException("MCP server not found: " + id);
            }
            String serverId = id == null ? UUID.randomUUID().toString() : id;
            StoredServer previous = id == null ? null
                    : definitions.getOrDefault(id, imported.get(id));
            StoredServer definition = toStored(request, serverId, previous);
            imported.remove(definition.id());
            importedSecrets.remove(definition.id());
            ManagedServer previousConnection = connections.remove(definition.id());
            if (previousConnection != null) closeQuietly(previousConnection.client());
            definitions.put(definition.id(), definition);
            saveFiles();
            rebuildPrefixesLocked();
            if (definition.enabled()) connectNow(definition);
            refreshProvider();
            return view(definition);
        } finally {
            lifecycle.unlock();
        }
    }

    public boolean delete(String id) {
        lifecycle.lock();
        try {
            if (imported.containsKey(id) && !definitions.containsKey(id)) {
                throw new McpRuntimeException(
                        "This MCP server is provided by an installed plugin; disable it or uninstall the plugin");
            }
            if (!definitions.containsKey(id)) return false;
            ManagedServer connection = connections.remove(id);
            if (connection != null) closeQuietly(connection.client());
            definitions.remove(id);
            reconnectState.remove(id);
            saveFiles();
            removeSecret(id);
            rebuildPrefixesLocked();
            refreshProvider();
            return true;
        } finally {
            lifecycle.unlock();
        }
    }

    /** Reconnects and re-discovers a server, which is also the real connectivity test. */
    public ServerView test(String id) {
        ServerView result;
        ManagedServer transientSession = null;
        lifecycle.lock();
        try {
            StoredServer definition = lookupDefinition(id);
            if (definition == null) throw new McpRuntimeException("MCP server not found: " + id);
            ManagedServer old = connections.remove(id);
            if (old != null) closeQuietly(old.client());
            ManagedServer fresh = performConnect(definition,
                    toolPrefixes.getOrDefault(id, sanitizePrefix(definition.name())),
                    secretFor(id), new ConnectAttempt());
            if (definition.enabled()) {
                publishLocked(id, fresh);
                refreshProvider();
            } else {
                // Testing a disabled server must not silently enable it for the AI registry
                // (P3): the transient session never touches connections/callbacks — the
                // view below is computed from the throwaway handle, then it is torn down.
                transientSession = fresh;
            }
            result = view(definition, fresh);
        } finally {
            lifecycle.unlock();
        }
        if (transientSession != null) closeQuietly(transientSession.client());
        return result;
    }

    /** Direct MCP call endpoint used by the Settings UI and useful for diagnostics. */
    public Object call(String id, String tool, Map<String, Object> arguments) {
        McpSyncClient client = connectedClient(id);
        if (tool == null || tool.isBlank()) throw new McpRuntimeException("tool is required");
        try {
            McpSchema.CallToolResult result = client.callTool(
                    McpSchema.CallToolRequest.builder().name(tool).arguments(arguments == null ? Map.of() : arguments).build());
            return Map.of("isError", Boolean.TRUE.equals(result.isError()), "content", result.content());
        } catch (RuntimeException failure) {
            // A dead server must fail fast into the reconnect path, not linger as a
            // zombie entry that times out on every call (P2-4).
            if (looksLikeConnectionFailure(client, failure)) {
                invalidateConnection(id, safeMessage(failure));
            }
            throw failure;
        }
    }

    public List<PromptView> prompts(String id) {
        List<McpSchema.Prompt> prompts = connectedClient(id).listPrompts().prompts();
        return prompts == null ? List.of() : prompts.stream()
                .map(prompt -> new PromptView(prompt.name(), nullToEmpty(prompt.title()), nullToEmpty(prompt.description()),
                        prompt.arguments() == null ? List.of() : prompt.arguments().stream()
                                .map(argument -> nullToEmpty(argument.name())).toList()))
                .toList();
    }

    public List<ResourceView> resources(String id) {
        List<McpSchema.Resource> resources = connectedClient(id).listResources().resources();
        return resources == null ? List.of() : resources.stream()
                .map(resource -> new ResourceView(nullToEmpty(resource.name()), nullToEmpty(resource.title()),
                        nullToEmpty(resource.uri()), nullToEmpty(resource.description()), nullToEmpty(resource.mimeType())))
                .toList();
    }

    /**
     * Rescans {@code mcp-servers/*.json} files written by the plugin store when a Claude/Codex/Grok
     * plugin declares {@code mcpServers}. Imported servers are disabled until the user enables one
     * (which adopts it into the user-managed registry). Called at startup and after plugin
     * install/uninstall; safe to call repeatedly.
     */
    public void syncImportedServers() {
        lifecycle.lock();
        try {
            syncImportedServersLocked();
            rebuildPrefixesLocked();
            refreshProvider();
        } finally {
            lifecycle.unlock();
        }
    }

    public record ServerRequest(
            String name,
            String type,
            String command,
            List<String> args,
            Map<String, String> env,
            String url,
            String endpoint,
            Map<String, String> headers,
            Boolean enabled,
            List<String> disabledTools,
            Integer requestTimeoutSeconds,
            Integer initTimeoutSeconds) {

        public ServerRequest(String name, String type, String command, List<String> args,
                Map<String, String> env, String url, String endpoint, Map<String, String> headers,
                Boolean enabled) {
            this(name, type, command, args, env, url, endpoint, headers, enabled, null, null, null);
        }
    }

    public record ServerView(
            String id,
            String name,
            String type,
            String command,
            List<String> args,
            String url,
            String endpoint,
            boolean enabled,
            String status,
            String error,
            String serverVersion,
            String protocolVersion,
            List<String> tools,
            List<String> envKeys,
            List<String> headerNames,
            List<String> disabledTools,
            int requestTimeoutSeconds,
            int initTimeoutSeconds,
            String source,
            String toolPrefix) {
    }

    public record PromptView(String name, String title, String description, List<String> arguments) {}

    public record ResourceView(String name, String title, String uri, String description, String mimeType) {}

    public static final class McpRuntimeException extends IllegalArgumentException {
        public McpRuntimeException(String message) { super(message); }
        public McpRuntimeException(String message, Throwable cause) { super(message, cause); }
    }

    private record StoredServer(String id, String name, String type, String command, List<String> args,
                                String url, String endpoint, boolean enabled, List<String> disabledTools,
                                Integer requestTimeoutSeconds, Integer initTimeoutSeconds,
                                String source) {

        List<String> disabledToolPatterns() {
            return disabledTools == null ? List.of() : disabledTools;
        }

        int effectiveRequestTimeoutSeconds() {
            return clampTimeout(requestTimeoutSeconds, DEFAULT_REQUEST_TIMEOUT_SECONDS,
                    MIN_TIMEOUT_SECONDS, MAX_REQUEST_TIMEOUT_SECONDS);
        }

        int effectiveInitTimeoutSeconds() {
            return clampTimeout(initTimeoutSeconds, DEFAULT_INIT_TIMEOUT_SECONDS,
                    MIN_TIMEOUT_SECONDS, MAX_INIT_TIMEOUT_SECONDS);
        }
    }

    private record SecretConfig(Map<String, String> env, Map<String, String> headers) {}

    /**
     * One live (or failed) server session plus its cached tools/list result. The cache is
     * refreshed at connect time and on {@code tools/list_changed} notifications, so reading
     * the catalog ({@link #callbacks()}, {@link #servers()}) never performs a live MCP round
     * trip (P2-5).
     */
    private record ManagedServer(McpSyncClient client, String status, String error,
                                 List<McpSchema.Tool> tools) {

        ManagedServer(McpSyncClient client, String status, String error) {
            this(client, status, error, List.of());
        }

        ManagedServer withTools(List<McpSchema.Tool> updated) {
            return new ManagedServer(client, status, error, updated);
        }
    }

    /** Coordination handle so a budget-exceeded startup attempt can abandon an in-flight connect. */
    private static final class ConnectAttempt {
        /** Guarded by the monitor of this attempt; set by the abandoning side before publishing the error state. */
        boolean abandoned;
    }

    private record StartupAttempt(String serverId, Future<ManagedServer> task, ConnectAttempt attempt) {}

    /** Inputs for a background reconnect, snapshotted under the lifecycle lock. */
    private record ReconnectWork(StoredServer definition, String prefix, SecretConfig secrets) {}

    /** Backoff bookkeeping for one error-state server. */
    private record ReconnectState(int failedAttempts, long retryAtNanos, boolean inFlight) {}

    /**
     * Connects every server concurrently under one shared wall-clock budget (P2-5). Attempts
     * that finish within the budget publish their own outcome; attempts that miss it are
     * abandoned — the late result is discarded, the server is marked error, and the reconnect
     * sweep owns it from there. Caller must hold the lifecycle lock.
     */
    private void connectAllLocked(List<StoredServer> toConnect) {
        if (toConnect.isEmpty()) return;
        long deadline = System.nanoTime() + startupConnectBudget.toNanos();
        List<StartupAttempt> attempts = new ArrayList<>();
        for (StoredServer definition : toConnect) {
            ConnectAttempt attempt = new ConnectAttempt();
            attempts.add(new StartupAttempt(definition.id(), connectExecutor.submit(() -> performConnect(
                    definition,
                    toolPrefixes.getOrDefault(definition.id(), sanitizePrefix(definition.name())),
                    secretFor(definition.id()), attempt)), attempt));
        }
        for (StartupAttempt attempt : attempts) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                abandonStartupAttempt(attempt);
                continue;
            }
            try {
                publishLocked(attempt.serverId(), attempt.task().get(remaining, TimeUnit.NANOSECONDS));
            } catch (TimeoutException budgetExceeded) {
                abandonStartupAttempt(attempt);
            } catch (ExecutionException | CancellationException connectFailed) {
                // performConnect records its own failure; nothing to add.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                abandonStartupAttempt(attempt);
            }
        }
    }

    /** Abandons a startup attempt that missed the shared budget: keep state error, discard the result. */
    private void abandonStartupAttempt(StartupAttempt attempt) {
        attempt.task().cancel(true);
        ManagedServer produced = null;
        synchronized (attempt.attempt()) {
            attempt.attempt().abandoned = true;
            try {
                // Completed-but-unconsumed race: salvage the handle so its process can be closed.
                if (attempt.task().isDone()) produced = attempt.task().get(0, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // Cancelled/failed — nothing to salvage.
            }
            connections.computeIfAbsent(attempt.serverId(),
                    id -> new ManagedServer(null, "error", budgetExceededMessage()));
        }
        scheduleNextRetryLocked(attempt.serverId());
        if (produced != null) closeQuietly(produced.client());
    }

    private String budgetExceededMessage() {
        return "connect exceeded the " + startupConnectBudget.toSeconds()
                + "s shared startup budget; retrying with backoff";
    }

    /**
     * Runs the full handshake plus one tools/list round trip for one server. NEVER publishes
     * state — the caller decides whether the outcome enters {@link #connections}.
     */
    private ManagedServer performConnect(StoredServer definition, String prefix, SecretConfig secrets,
                                         ConnectAttempt attempt) {
        McpSyncClient client = null;
        try {
            client = buildClient(definition, prefix, secrets);
            client.initialize();
            // Force an actual tools/list round trip now. initialize() alone proves only the
            // handshake; this catches servers that start but cannot serve tools — and seeds
            // the cached catalog for this server (P2-5).
            List<McpSchema.Tool> tools = client.listTools().tools();
            List<McpSchema.Tool> safeTools = tools == null ? List.of() : List.copyOf(tools);
            synchronized (attempt) {
                if (attempt.abandoned) {
                    closeQuietly(client);
                    return new ManagedServer(null, "error", budgetExceededMessage());
                }
                return new ManagedServer(client, "connected", null, safeTools);
            }
        } catch (Exception error) {
            closeQuietly(client);
            log.warn("MCP server {} failed to connect: {}", definition.name(), error.toString());
            synchronized (attempt) {
                if (attempt.abandoned) return new ManagedServer(null, "error", budgetExceededMessage());
                return new ManagedServer(null, "error", safeMessage(error));
            }
        }
    }

    /** Synchronous connect used by save(); caller must hold the lifecycle lock. */
    private void connectNow(StoredServer definition) {
        ManagedServer result = performConnect(definition,
                toolPrefixes.getOrDefault(definition.id(), sanitizePrefix(definition.name())),
                secretFor(definition.id()), new ConnectAttempt());
        publishLocked(definition.id(), result);
    }

    /** Publishes a connect outcome and arms or clears the reconnect backoff. Caller holds the lock. */
    private void publishLocked(String id, ManagedServer result) {
        connections.put(id, result);
        if (result.client() != null) reconnectState.remove(id);
        else scheduleNextRetryLocked(id);
    }

    /** Arms the next backoff retry for an error-state server. Caller holds the lock. */
    private void scheduleNextRetryLocked(String id) {
        ReconnectState state = reconnectState.getOrDefault(id, new ReconnectState(0, 0L, false));
        int failedAttempts = state.failedAttempts() + 1;
        reconnectState.put(id, new ReconnectState(failedAttempts,
                System.nanoTime() + backoffDelayNanos(initialRetryDelay, maxRetryDelay, failedAttempts), false));
    }

    /** Exponential reconnect backoff: {@code initial} doubled per failed attempt, capped at {@code max}. */
    static long backoffDelayNanos(Duration initial, Duration max, int failedAttempts) {
        long seconds = Math.max(1, initial.toSeconds());
        long cap = Math.max(seconds, max.toSeconds());
        for (int i = 1; i < failedAttempts && seconds < cap; i++) seconds = Math.min(seconds * 2, cap);
        return Math.min(seconds, cap) * 1_000_000_000L;
    }

    /**
     * Background sweep (P2-4): reconnects error-state servers whose backoff has elapsed.
     * Runs on the dedicated daemon scheduler thread; a thrown exception would silently kill
     * a {@code scheduleWithFixedDelay} task, so the whole sweep is fail-soft.
     */
    private void sweepForReconnects() {
        try {
            if (stopped) return;
            long now = System.nanoTime();
            lifecycle.lock();
            try {
                for (Map.Entry<String, ManagedServer> entry : connections.entrySet()) {
                    ManagedServer managed = entry.getValue();
                    if (managed.client() != null) continue; // only error-state servers
                    StoredServer definition = definitions.get(entry.getKey());
                    if (definition == null || !definition.enabled()) continue;
                    ReconnectState state = reconnectState.getOrDefault(
                            entry.getKey(), new ReconnectState(0, 0L, false));
                    if (state.inFlight() || state.retryAtNanos() > now) continue;
                    dispatchReconnectLocked(entry.getKey(), definition);
                }
            } finally {
                lifecycle.unlock();
            }
        } catch (Throwable unexpected) {
            log.debug("MCP reconnect sweep failed", unexpected);
        }
    }

    /**
     * Marks one error-state server in-flight and dispatches a single asynchronous reconnect
     * attempt on the connect executor. This is the "trigger one async rebuild" half of
     * P2-4 — {@link #failServerLocked} calls it directly (no sweep-tick latency), and the
     * sweep calls it for servers whose backoff has elapsed. Caller must hold the lifecycle
     * lock; the connect itself runs off-thread.
     */
    private void dispatchReconnectLocked(String id, StoredServer definition) {
        ReconnectState state = reconnectState.getOrDefault(id, new ReconnectState(0, 0L, false));
        reconnectState.put(id, new ReconnectState(state.failedAttempts(), System.nanoTime(), true));
        ReconnectWork work = new ReconnectWork(definition,
                toolPrefixes.getOrDefault(id, sanitizePrefix(definition.name())), secretFor(id));
        try {
            connectExecutor.execute(() -> attemptReconnect(work));
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            // stop() raced us; the state entry is dead weight on a stopped instance.
            reconnectState.remove(id);
        }
    }

    /** One background reconnect attempt, run off the scheduler thread. */
    private void attemptReconnect(ReconnectWork work) {
        StoredServer definition = work.definition();
        ManagedServer result = performConnect(definition, work.prefix(), work.secrets(), new ConnectAttempt());
        boolean published = false;
        lifecycle.lock();
        try {
            if (!stopped) {
                StoredServer current = definitions.get(definition.id());
                ManagedServer existing = connections.get(definition.id());
                // Only take over if the configuration is unchanged and the server is still in
                // error state — save()/delete()/test() in the meantime won the race.
                if (current != null && current.enabled() && existing != null && existing.client() == null) {
                    publishLocked(definition.id(), result);
                    published = true;
                    refreshProvider();
                } else {
                    // Healthy again, deleted, or superseded: drop our bookkeeping either way.
                    reconnectState.remove(definition.id());
                }
            }
        } finally {
            lifecycle.unlock();
        }
        if (!published) closeQuietly(result.client());
    }

    /** {@code tools/list_changed} for one server: refresh its cached tools, rebuild the catalog. */
    private void onToolsChanged(String id) {
        // Fired on the SDK's notification thread; serialize with lifecycle mutations so the
        // snapshot is rebuilt against a stable connection set.
        lifecycle.lock();
        try {
            if (stopped) return;
            ManagedServer managed = connections.get(id);
            if (managed == null || managed.client() == null) return;
            try {
                List<McpSchema.Tool> tools = managed.client().listTools().tools();
                connections.put(id, managed.withTools(tools == null ? List.of() : List.copyOf(tools)));
            } catch (Exception failure) {
                failServerLocked(id, safeMessage(failure));
                return; // failServerLocked already rebuilt the catalog
            }
            refreshProvider();
        } finally {
            lifecycle.unlock();
        }
    }

    /**
     * Marks a live connection dead after a failed call: closes the client, records the
     * error, arms an immediate reconnect (P2-4's "trigger one async rebuild"), and rebuilds
     * the catalog so the dead callbacks vanish. Called without the lifecycle lock held.
     */
    private void invalidateConnection(String id, String reason) {
        lifecycle.lock();
        try {
            if (stopped) return;
            ManagedServer current = connections.get(id);
            if (current == null || current.client() == null) return; // already dead/disconnected
            log.warn("MCP server {} connection failed ({}); marking dead and scheduling a rebuild", id, reason);
            failServerLocked(id, reason);
        } finally {
            lifecycle.unlock();
        }
    }

    /**
     * Fails a server's live session and triggers one immediate asynchronous rebuild (P2-4).
     * Caller holds the lock. If that rebuild fails too, its publication arms the regular
     * exponential backoff — a flapping server settles into the sweep's cadence.
     */
    private void failServerLocked(String id, String error) {
        ManagedServer current = connections.get(id);
        if (current == null) return;
        if (current.client() != null) closeQuietly(current.client());
        connections.put(id, new ManagedServer(null, "error", error));
        StoredServer definition = definitions.get(id);
        if (!stopped && definition != null && definition.enabled()) {
            dispatchReconnectLocked(id, definition);
        } else {
            reconnectState.remove(id);
        }
        refreshProvider();
    }

    /**
     * Heuristic connection-death detector. A dead stdio process or dropped HTTP session
     * surfaces either as an uninitialized client or as an IOException / closed-connection
     * error somewhere in the cause chain. A plain request timeout (server alive but slow)
     * leaves the session initialized and carries no connection-shaped error — it must NOT
     * tear the connection down.
     */
    private static boolean looksLikeConnectionFailure(McpSyncClient client, RuntimeException failure) {
        if (!client.isInitialized()) return true;
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof IOException) return true;
            String message = cause.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                boolean connectionWord = lower.contains("connection") || lower.contains("pipe")
                        || lower.contains("stream") || lower.contains("channel");
                boolean closedWord = lower.contains("closed") || lower.contains("reset")
                        || lower.contains("broken") || lower.contains("premature") || lower.contains("eof");
                if (connectionWord && closedWord) return true;
            }
        }
        return false;
    }

    /**
     * Catalog wrapper (P2-4): when a tool call fails in a connection-shaped way, the owning
     * server is marked dead and one async rebuild is armed, so the AI-facing callbacks never
     * linger as zombie clients that time out for the rest of the process lifetime.
     */
    private final class ReconnectingToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final String serverId;
        private final McpSyncClient client;

        ReconnectingToolCallback(ToolCallback delegate, String serverId, McpSyncClient client) {
            this.delegate = delegate;
            this.serverId = serverId;
            this.client = client;
        }

        @Override public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override public String call(String toolInput) {
            try {
                return delegate.call(toolInput);
            } catch (RuntimeException failure) {
                if (looksLikeConnectionFailure(client, failure)) {
                    invalidateConnection(serverId, safeMessage(failure));
                }
                throw failure;
            }
        }

        @Override public String call(String toolInput, ToolContext toolContext) {
            try {
                return delegate.call(toolInput, toolContext);
            } catch (RuntimeException failure) {
                if (looksLikeConnectionFailure(client, failure)) {
                    invalidateConnection(serverId, safeMessage(failure));
                }
                throw failure;
            }
        }
    }

    private McpSyncClient buildClient(StoredServer definition, String prefix, SecretConfig secrets) {
        String type = normalizeType(definition.type());
        var transport = switch (type) {
            case "STDIO" -> new StdioClientTransport(
                    ServerParameters.builder(required(definition.command(), "command"))
                            .args(definition.args() == null ? List.of() : definition.args())
                            .env(childEnvWithNeutralizedHostSecrets(
                                    sanitizeEnv(definition.name(), secrets.env()), System.getenv())).build(),
                    io.modelcontextprotocol.json.McpJsonDefaults.getMapper());
            case "SSE" -> HttpClientSseClientTransport.builder(requiredUrl(definition.url()))
                    .sseEndpoint(defaultEndpoint(definition.endpoint(), "/sse"))
                    .requestBuilder(requestBuilder(secrets.headers())).build();
            case "STREAMABLE_HTTP" -> HttpClientStreamableHttpTransport.builder(requiredUrl(definition.url()))
                    .endpoint(defaultEndpoint(definition.endpoint(), "/mcp"))
                    .requestBuilder(requestBuilder(secrets.headers())).build();
            default -> throw new McpRuntimeException("Unsupported MCP transport type: " + definition.type());
        };
        return McpClient.sync(transport)
                // Spring AI derives the wire tool name from the client identity, so a per-server
                // name is what makes `Mcp(server__tool)` permission rules and per-tool filtering
                // unambiguous when several servers are connected.
                .clientInfo(new McpSchema.Implementation(prefix, HOST_VERSION))
                .requestTimeout(Duration.ofSeconds(definition.effectiveRequestTimeoutSeconds()))
                .initializationTimeout(Duration.ofSeconds(definition.effectiveInitTimeoutSeconds()))
                .toolsChangeConsumer(ignored -> onToolsChanged(definition.id()))
                .build();
    }

    private static HttpRequest.Builder requestBuilder(Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        if (headers != null) headers.forEach((key, value) -> builder.header(key, value));
        return builder;
    }

    private ServerView view(StoredServer definition) {
        return view(definition, null);
    }

    /** Status read is served from the cached tools list — no MCP round trip (P2-5). */
    private ServerView view(StoredServer definition, ManagedServer override) {
        ManagedServer managed = override != null ? override : connections.get(definition.id());
        SecretConfig secrets = secretFor(definition.id());
        List<String> tools = managed == null ? List.of()
                : managed.tools().stream().map(McpSchema.Tool::name).toList();
        McpSchema.InitializeResult init = managed == null || managed.client() == null
                ? null : managed.client().getCurrentInitializationResult();
        McpSchema.Implementation info = managed == null || managed.client() == null
                ? null : managed.client().getServerInfo();
        return new ServerView(definition.id(), definition.name(), definition.type(), definition.command(),
                definition.args(), definition.url(), definition.endpoint(), definition.enabled(),
                managed == null ? "disconnected" : managed.status(), managed == null ? null : managed.error(),
                info == null ? "" : nullToEmpty(info.version()), init == null ? "" : nullToEmpty(init.protocolVersion()),
                tools, secrets.env().keySet().stream().sorted().toList(),
                secrets.headers().keySet().stream().sorted().toList(),
                definition.disabledToolPatterns(), definition.effectiveRequestTimeoutSeconds(),
                definition.effectiveInitTimeoutSeconds(), definition.source(),
                toolPrefixes.getOrDefault(definition.id(), sanitizePrefix(definition.name())));
    }

    /**
     * Rebuilds the cached AI-facing tool catalog from the per-server tools cache — no MCP
     * round trip (P2-5). Called only on lifecycle changes and {@code tools/list_changed}
     * notifications; {@link #callbacks()} is then a plain read. Every callback names the
     * tool with the server's stable prefix and dead-connection failures tear the server
     * down for an async rebuild (see {@link ReconnectingToolCallback}).
     */
    private void refreshProvider() {
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (Map.Entry<String, ManagedServer> entry : connections.entrySet()) {
            ManagedServer managed = entry.getValue();
            // Error-state clients never enter the catalog; neither do uninitialized ones.
            if (managed.client() == null || !managed.client().isInitialized()) continue;
            StoredServer definition = lookupDefinition(entry.getKey());
            String prefix = definition == null ? null
                    : toolPrefixes.getOrDefault(definition.id(), sanitizePrefix(definition.name()));
            List<String> disabled = definition == null ? List.of() : definition.disabledToolPatterns();
            for (McpSchema.Tool tool : managed.tools()) {
                String wireName = prefix == null ? tool.name() : prefix + "__" + tool.name();
                if (isToolDisabled(wireName, disabled)) continue;
                byName.putIfAbsent(wireName, new ReconnectingToolCallback(
                        SyncMcpToolCallback.builder()
                                .mcpClient(managed.client())
                                .tool(tool)
                                .prefixedToolName(wireName)
                                .build(),
                        entry.getKey(), managed.client()));
            }
        }
        callbacksSnapshot = List.copyOf(byName.values());
    }

    /**
     * Cherry-studio-style tool policy. A pattern disables a tool when it equals the bare tool
     * name or the full wire name ({@code server__tool}), or is a lone {@code *} (all tools of
     * the server). A trailing {@code *} is a prefix match bounded by a WORD boundary: the stem
     * must either end at end-of-name, end at a separator character ({@code _ - . :}), or be
     * immediately followed by one — so {@code acc*} disables {@code acc} and {@code acc_lookup}
     * but NOT {@code account}, and {@code server__*} still disables every tool of that server
     * because the stem itself ends on the separator.
     */
    static boolean isToolDisabled(String wireName, List<String> patterns) {
        if (wireName == null || patterns == null || patterns.isEmpty()) return false;
        String bare = wireName.contains("__") ? wireName.substring(wireName.indexOf("__") + 2) : wireName;
        for (String raw : patterns) {
            if (raw == null || raw.isBlank()) continue;
            String pattern = raw.trim();
            if ("*".equals(pattern)) return true;
            boolean wildcard = pattern.endsWith("*") && pattern.length() > 1;
            String stem = wildcard ? pattern.substring(0, pattern.length() - 1) : pattern;
            if (wildcard
                    ? wildcardMatchesWord(wireName, stem) || wildcardMatchesWord(bare, stem)
                    : pattern.equals(wireName) || pattern.equals(bare)) {
                return true;
            }
        }
        return false;
    }

    /** Trailing-{@code *} prefix match that stops at a word boundary (see {@link #isToolDisabled}). */
    private static boolean wildcardMatchesWord(String name, String stem) {
        if (!name.startsWith(stem)) return false;
        if (name.length() == stem.length()) return true; // exact match; the * is redundant
        if (isToolNameSeparator(stem.charAt(stem.length() - 1))) return true; // stem already ends a word
        return isToolNameSeparator(name.charAt(stem.length())); // name continues with a new word
    }

    private static boolean isToolNameSeparator(char c) {
        return c == '_' || c == '-' || c == '.' || c == ':';
    }

    private McpSyncClient connectedClient(String id) {
        ManagedServer connection = connections.get(id);
        if (connection == null || connection.client() == null || !connection.client().isInitialized()) {
            throw new McpRuntimeException("MCP server is not connected: " + id);
        }
        return connection.client();
    }

    private StoredServer toStored(ServerRequest request, String id, StoredServer previous) {
        if (request == null) throw new McpRuntimeException("request is required");
        String name = required(request.name(), "name");
        String type = normalizeType(request.type());
        if ("STDIO".equals(type)) required(request.command(), "command");
        else requiredUrl(request.url());
        SecretConfig previousSecrets = previous == null ? new SecretConfig(Map.of(), Map.of()) : secretFor(previous.id());
        Map<String, String> oldSecrets = previousSecrets.env();
        Map<String, String> oldHeaders = previousSecrets.headers();
        SecretConfig secrets = new SecretConfig(
                request.env() == null ? oldSecrets : cleanMap(request.env()),
                request.headers() == null ? oldHeaders : cleanMap(request.headers()));
        writeSecret(id, secrets);
        List<String> disabledTools = request.disabledTools() == null
                ? (previous == null ? List.of() : previous.disabledToolPatterns())
                : cleanToolPatterns(request.disabledTools());
        return new StoredServer(id, name, type, blankToNull(request.command()),
                request.args() == null ? List.of() : List.copyOf(request.args()), blankToNull(request.url()),
                blankToNull(request.endpoint()), request.enabled() == null || request.enabled(),
                disabledTools,
                clampTimeout(request.requestTimeoutSeconds() != null ? request.requestTimeoutSeconds()
                        : previous == null ? null : previous.requestTimeoutSeconds(),
                        DEFAULT_REQUEST_TIMEOUT_SECONDS, MIN_TIMEOUT_SECONDS, MAX_REQUEST_TIMEOUT_SECONDS),
                clampTimeout(request.initTimeoutSeconds() != null ? request.initTimeoutSeconds()
                        : previous == null ? null : previous.initTimeoutSeconds(),
                        DEFAULT_INIT_TIMEOUT_SECONDS, MIN_TIMEOUT_SECONDS, MAX_INIT_TIMEOUT_SECONDS),
                previous == null ? null : previous.source());
    }

    private void load() {
        try {
            Files.createDirectories(directory);
            if (Files.exists(registryFile)) {
                List<StoredServer> loaded = json.readValue(Files.readString(registryFile), new TypeReference<>() {});
                if (loaded != null) {
                    for (StoredServer value : loaded) {
                        if (value != null && value.id() != null && !value.id().isBlank()) {
                            definitions.put(value.id(), value);
                        }
                    }
                }
            }
        } catch (Exception error) {
            // MCP is an optional integration. A truncated or hand-edited registry must not make
            // the host unbootable; leave the file untouched so the user can recover it manually.
            definitions.clear();
            log.warn("Ignoring unreadable MCP server registry {}: {}", registryFile, safeMessage(error));
        }
    }

    private void syncImportedServersLocked() {
        Map<String, StoredServer> next = new LinkedHashMap<>();
        Map<String, SecretConfig> nextSecrets = new LinkedHashMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                if (REGISTRY_FILE.equals(fileName) || SECRETS_FILE.equals(fileName) || fileName.contains(".tmp-")) {
                    continue;
                }
                String source = fileName.substring(0, fileName.length() - ".json".length());
                JsonNode root = json.readTree(Files.readString(file));
                if (root == null || !root.isObject()) continue;
                for (Iterator<Map.Entry<String, JsonNode>> fields = root.fields(); fields.hasNext(); ) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    try {
                        ImportedServer parsed = parseImportedServer(source, field.getKey(), field.getValue());
                        if (parsed != null) {
                            next.put(parsed.definition().id(), parsed.definition());
                            nextSecrets.put(parsed.definition().id(), parsed.secrets());
                        }
                    } catch (Exception bad) {
                        log.warn("Skipping invalid imported MCP server {} in {}: {}",
                                field.getKey(), fileName, safeMessage(bad));
                    }
                }
            }
        } catch (Exception error) {
            log.warn("Cannot scan imported MCP server configs in {}: {}", directory, safeMessage(error));
        }
        // Servers the user already saved (adopted) stay user-managed; the import never overrides them.
        next.keySet().removeIf(definitions::containsKey);
        imported.clear();
        imported.putAll(next);
        importedSecrets.clear();
        importedSecrets.putAll(nextSecrets);
    }

    private record ImportedServer(StoredServer definition, SecretConfig secrets) {}

    /** Claude/Codex/Grok plugin {@code mcpServers} entries: stdio {@code command/args/env} or remote {@code url/headers}. */
    private ImportedServer parseImportedServer(String source, String key, JsonNode node) {
        if (node == null || !node.isObject()) return null;
        String id = source + "/" + key;
        String displayName = node.hasNonNull("name") ? node.get("name").asText() : key;
        String command = text(node, "command");
        String url = text(node, "url");
        if (command != null && !command.isBlank()) {
            StoredServer definition = new StoredServer(id, displayName, "STDIO", command.trim(),
                    stringList(node, "args"), null, null, false,
                    List.of(), null, null, source);
            return new ImportedServer(definition,
                    new SecretConfig(sanitizeEnv(displayName, stringMap(node, "env")), Map.of()));
        }
        if (url != null && !url.isBlank()) {
            String rawType = text(node, "type");
            String normalized = rawType == null ? null
                    : rawType.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            String type = "SSE".equals(normalized) ? "SSE" : "STREAMABLE_HTTP";
            // Split a non-root path out of the URL: the HTTP transports take a base URI plus a
            // separate endpoint path, and appending the default endpoint to a URL that already
            // carries one would double it.
            URI uri = URI.create(url.trim());
            String path = uri.getPath();
            boolean hasPath = path != null && !path.isBlank() && !"/".equals(path);
            String baseUrl = hasPath
                    ? (uri.getScheme() + "://" + uri.getRawAuthority() + "/").replaceAll("/+$", "/")
                    : url.trim();
            String endpoint = hasPath ? path : null;
            StoredServer definition = new StoredServer(id, displayName, type, null, List.of(),
                    baseUrl, endpoint, false, List.of(), null, null, source);
            return new ImportedServer(definition, new SecretConfig(Map.of(), stringMap(node, "headers")));
        }
        return null;
    }

    private void saveFiles() {
        try {
            Files.createDirectories(directory);
            writeAtomically(registryFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(definitions.values()));
        } catch (Exception error) {
            throw new McpRuntimeException("Cannot save MCP server registry", error);
        }
    }

    private SecretConfig secretFor(String id) {
        SecretConfig persisted = readSecrets().getOrDefault(id, null);
        if (persisted != null) return persisted;
        return importedSecrets.getOrDefault(id, new SecretConfig(Map.of(), Map.of()));
    }

    private Map<String, SecretConfig> readSecrets() {
        try {
            if (!Files.exists(secretsFile)) return Map.of();
            Map<String, SecretConfig> result = json.readValue(Files.readString(secretsFile), new TypeReference<>() {});
            if (result == null) return Map.of();
            // Values are stored in CryptoUtil's machine-bound ENC(...) envelope; rows written
            // before that (plaintext) still decrypt transparently.
            Map<String, SecretConfig> decrypted = new LinkedHashMap<>();
            result.forEach((id, cfg) -> decrypted.put(id, new SecretConfig(
                    decryptAll(cfg.env()), decryptAll(cfg.headers()))));
            return decrypted;
        } catch (Exception error) {
            log.warn("Cannot read MCP secrets: {}", error.toString());
            return Map.of();
        }
    }

    private static Map<String, String> decryptAll(Map<String, String> values) {
        if (values == null || values.isEmpty()) return values == null ? Map.of() : values;
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((key, value) ->
                out.put(key, fan.summer.fengyu.setup.CryptoUtil.decrypt(value)));
        return out;
    }

    private static Map<String, String> encryptAll(Map<String, String> values) {
        if (values == null || values.isEmpty()) return values == null ? Map.of() : values;
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((key, value) -> out.put(key,
                value == null || value.isBlank() ? value : fan.summer.fengyu.setup.CryptoUtil.encrypt(value)));
        return out;
    }

    private void writeSecret(String id, SecretConfig secrets) {
        try {
            Files.createDirectories(directory);
            Map<String, SecretConfig> all = new LinkedHashMap<>(readSecrets());
            all.put(id, new SecretConfig(encryptAll(secrets.env()), encryptAll(secrets.headers())));
            writeAtomically(secretsFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(all));
        } catch (Exception error) {
            throw new McpRuntimeException("Cannot save MCP credentials", error);
        }
    }

    private void removeSecret(String id) {
        try {
            Map<String, SecretConfig> all = new LinkedHashMap<>(readSecrets());
            all.remove(id);
            if (all.isEmpty()) {
                Files.deleteIfExists(secretsFile);
            } else {
                writeAtomically(secretsFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(all));
            }
        } catch (Exception error) {
            throw new McpRuntimeException("Cannot delete MCP credentials", error);
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            protect(target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void protect(Path file) {
        try {
            java.nio.file.attribute.PosixFilePermission[] ignored = new java.nio.file.attribute.PosixFilePermission[0];
            Files.setPosixFilePermissions(file, java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) { }
    }

    private static String normalizeType(String value) {
        String type = value == null ? "STDIO" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("HTTP".equals(type) || "STREAMABLEHTTP".equals(type)) type = "STREAMABLE_HTTP";
        if (!List.of("STDIO", "SSE", "STREAMABLE_HTTP").contains(type))
            throw new McpRuntimeException("type must be stdio, sse, or streamable-http");
        return type;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new McpRuntimeException(field + " is required");
        return value.trim();
    }

    private static String requiredUrl(String value) {
        String url = required(value, "url");
        URI uri;
        try { uri = URI.create(url); } catch (IllegalArgumentException error) { throw new McpRuntimeException("url is invalid", error); }
        if (!List.of("http", "https").contains(uri.getScheme())) throw new McpRuntimeException("url must use http or https");
        return url;
    }

    private static String defaultEndpoint(String endpoint, String fallback) {
        return endpoint == null || endpoint.isBlank() ? fallback : endpoint.trim();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static Map<String, String> cleanMap(Map<String, String> values) {
        Map<String, String> cleaned = new LinkedHashMap<>();
        values.forEach((key, value) -> { if (key != null && !key.isBlank() && value != null) cleaned.put(key.trim(), value); });
        return Collections.unmodifiableMap(cleaned);
    }

    private static List<String> cleanToolPatterns(List<String> patterns) {
        if (patterns == null) return List.of();
        List<String> cleaned = new ArrayList<>();
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank()) cleaned.add(pattern.trim());
        }
        return List.copyOf(cleaned);
    }

    private static int clampTimeout(Integer seconds, int fallback, int min, int max) {
        if (seconds == null) return fallback;
        return Math.max(min, Math.min(max, seconds));
    }

    private static Map<String, String> sanitizeEnv(String serverName, Map<String, String> env) {
        if (env == null || env.isEmpty()) return Map.of();
        Map<String, String> safe = new LinkedHashMap<>();
        env.forEach((key, value) -> {
            if (isDeniedEnvKey(key)) {
                log.warn("MCP server {}: dropped forbidden env key {}", serverName, key);
            } else if (key != null && !key.isBlank() && value != null) {
                safe.put(key, value);
            }
        });
        return Collections.unmodifiableMap(safe);
    }

    /**
     * The SDK's stdio transport starts the child from a copy of the HOST environment and only
     * overlays the configured env ({@code ProcessBuilder.environment().putAll}), so the JVM's
     * own credentials — {@code FENGYU_AUTH_TOKEN}, the browser bridge token, any provider key
     * present in the launch environment — would otherwise be readable by every third-party MCP
     * server, and through them the entire token-gated API. The overlay cannot remove inherited
     * keys, so each sensitive inherited key is neutralized with an explicit empty value; keys
     * the operator explicitly configured for this server keep their configured value (the only
     * sanctioned way to hand a credential to an MCP server).
     */
    static Map<String, String> childEnvWithNeutralizedHostSecrets(Map<String, String> configured,
                                                                  Map<String, String> hostEnv) {
        Map<String, String> env = new LinkedHashMap<>(configured);
        hostEnv.forEach((key, value) -> {
            if (!env.containsKey(key)
                    && fan.summer.fengyu.ai.tools.CommandExecuteTool.isSensitiveEnvironmentName(key)) {
                env.put(key, "");
            }
        });
        return Collections.unmodifiableMap(env);
    }

    private static boolean isDeniedEnvKey(String key) {
        if (key == null) return false;
        String normalized = key.trim();
        if (DENIED_ENV_KEYS.contains(normalized.toUpperCase(Locale.ROOT))) return true;
        String upper = normalized.toUpperCase(Locale.ROOT);
        return DENIED_ENV_PREFIXES.stream().anyMatch(upper::startsWith);
    }

    /**
     * Stable wire-name prefix for one server. Doubles as the client identity so the provider's
     * prefix generator and tool filter can map a connection back to its configuration. Sanitized
     * to lowercase words on single underscores (never a double underscore, which the
     * {@code Mcp(server__tool)} permission grammar could not parse).
     */
    private void rebuildPrefixesLocked() {
        toolPrefixes.clear();
        Set<String> used = new HashSet<>();
        for (StoredServer definition : allDefinitionsLocked()) {
            String base = sanitizePrefix(definition.name());
            String prefix = base;
            if (!used.add(prefix)) prefix = base + "_" + shortId(definition.id());
            used.add(prefix);
            toolPrefixes.put(definition.id(), prefix);
        }
    }

    private static String sanitizePrefix(String name) {
        String cleaned = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        cleaned = cleaned.replaceAll("^_+|_+$", "");
        if (cleaned.isEmpty()) cleaned = "server";
        if (cleaned.length() > 32) cleaned = cleaned.substring(0, 32).replaceAll("_+$", "");
        return cleaned;
    }

    private static String shortId(String id) {
        String hash = Integer.toHexString(id == null ? 0 : id.hashCode());
        return (hash + "0000").substring(0, 4).replaceAll("[^a-z0-9]", "0");
    }

    private List<StoredServer> allDefinitionsLocked() {
        List<StoredServer> all = new ArrayList<>(definitions.values());
        all.addAll(imported.values());
        return all;
    }

    private StoredServer lookupDefinition(String id) {
        StoredServer definition = definitions.get(id);
        return definition != null ? definition : imported.get(id);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText() == null ? null : value.asText();
    }

    private static List<String> stringList(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        value.forEach(item -> { if (item != null && !item.isNull()) out.add(item.asText()); });
        return List.copyOf(out);
    }

    private static Map<String, String> stringMap(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        value.fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && !entry.getValue().isNull()) {
                out.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return Collections.unmodifiableMap(out);
    }

    private static void closeQuietly(McpSyncClient client) {
        if (client == null) return;
        try {
            client.closeGracefully();
        } catch (Exception gracefulFailure) {
            try {
                client.close();
            } catch (Exception closeFailure) {
                log.debug("MCP client close failed after graceful close failure", closeFailure);
            }
        }
    }
}
