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
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns MCP connections that can be changed without restarting FengYu.
 *
 * <p>The Spring AI starter is intentionally startup-scoped. This manager uses the same official
 * MCP SDK transports, but owns the client lifecycle itself so a saved server is connected now,
 * its tools are immediately visible to the live AI registry, and an update replaces the old
 * process/session safely.</p>
 *
 * <p>Tool names are namespaced per server ({@code <server>__<tool>}, produced by Spring AI from
 * the client identity), so permission rules can target one server and two servers can expose the
 * same tool name without colliding. Tools the user disabled for a server never reach the AI
 * catalog. The tool catalog itself is a cached snapshot: reading it never performs a live MCP
 * round trip, so a dead or slow server cannot block chat startup.</p>
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

    public McpRuntimeManager() {
        this(RuntimePaths.root());
    }

    /** Focused-test constructor; production uses the canonical runtime root. */
    public McpRuntimeManager(Path runtimeRoot) {
        this.directory = runtimeRoot.resolve("mcp-servers").toAbsolutePath().normalize();
        this.registryFile = directory.resolve(REGISTRY_FILE);
        this.secretsFile = directory.resolve(SECRETS_FILE);
    }

    @PostConstruct
    public void start() {
        lifecycle.lock();
        try {
            load();
            syncImportedServersLocked();
            rebuildPrefixesLocked();
            for (StoredServer definition : definitions.values()) {
                if (definition.enabled()) connect(definition);
            }
            refreshProvider();
        } finally {
            lifecycle.unlock();
        }
    }

    @PreDestroy
    public void stop() {
        lifecycle.lock();
        try {
            for (ManagedServer connection : connections.values()) closeQuietly(connection.client());
            connections.clear();
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
            if (definition.enabled()) connect(definition);
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
        lifecycle.lock();
        try {
            StoredServer definition = lookupDefinition(id);
            if (definition == null) throw new McpRuntimeException("MCP server not found: " + id);
            ManagedServer old = connections.remove(id);
            if (old != null) closeQuietly(old.client());
            connect(definition);
            refreshProvider();
            ServerView result = view(definition);
            // Testing a disabled server must not silently enable it for the AI registry. Keep the
            // transient result for the caller, then tear down the temporary session immediately.
            if (!definition.enabled()) {
                ManagedServer temporary = connections.remove(id);
                if (temporary != null) closeQuietly(temporary.client());
                refreshProvider();
            }
            return result;
        } finally {
            lifecycle.unlock();
        }
    }

    /** Direct MCP call endpoint used by the Settings UI and useful for diagnostics. */
    public Object call(String id, String tool, Map<String, Object> arguments) {
        McpSyncClient client = connectedClient(id);
        if (tool == null || tool.isBlank()) throw new McpRuntimeException("tool is required");
        McpSchema.CallToolResult result = client.callTool(
                McpSchema.CallToolRequest.builder().name(tool).arguments(arguments == null ? Map.of() : arguments).build());
        return Map.of("isError", Boolean.TRUE.equals(result.isError()), "content", result.content());
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

    private record ManagedServer(McpSyncClient client, String status, String error) {}

    private void connect(StoredServer definition) {
        McpSyncClient client = null;
        try {
            client = buildClient(definition);
            client.initialize();
            // Force an actual tools/list round trip now. initialize() alone proves only the
            // handshake; this catches servers that start but cannot serve tools.
            client.listTools();
            connections.put(definition.id(), new ManagedServer(client, "connected", null));
        } catch (Exception error) {
            closeQuietly(client);
            log.warn("MCP server {} failed to connect: {}", definition.name(), error.toString());
            connections.put(definition.id(), new ManagedServer(null, "error", safeMessage(error)));
        }
    }

    private McpSyncClient buildClient(StoredServer definition) {
        SecretConfig secrets = secretFor(definition.id());
        String type = normalizeType(definition.type());
        var transport = switch (type) {
            case "STDIO" -> new StdioClientTransport(
                    ServerParameters.builder(required(definition.command(), "command"))
                            .args(definition.args() == null ? List.of() : definition.args())
                            .env(sanitizeEnv(definition.name(), secrets.env())).build(),
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
                .clientInfo(new McpSchema.Implementation(toolPrefixes.getOrDefault(definition.id(),
                        sanitizePrefix(definition.name())), HOST_VERSION))
                .requestTimeout(Duration.ofSeconds(definition.effectiveRequestTimeoutSeconds()))
                .initializationTimeout(Duration.ofSeconds(definition.effectiveInitTimeoutSeconds()))
                .toolsChangeConsumer(ignored -> refreshFromNotification())
                .build();
    }

    private void refreshFromNotification() {
        // The SDK fires this on its own thread; serialize with lifecycle mutations so the
        // snapshot is rebuilt against a stable connection set.
        lifecycle.lock();
        try {
            refreshProvider();
        } finally {
            lifecycle.unlock();
        }
    }

    private static HttpRequest.Builder requestBuilder(Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        if (headers != null) headers.forEach((key, value) -> builder.header(key, value));
        return builder;
    }

    private ServerView view(StoredServer definition) {
        ManagedServer managed = connections.get(definition.id());
        SecretConfig secrets = secretFor(definition.id());
        List<String> tools = List.of();
        if (managed != null && managed.client() != null && managed.client().isInitialized()) {
            try { tools = managed.client().listTools().tools().stream().map(McpSchema.Tool::name).toList(); }
            catch (Exception error) { /* status below carries the useful failure */ }
        }
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
     * Rebuilds the cached AI-facing tool catalog. Called only on lifecycle changes and
     * {@code tools/list_changed} notifications; {@link #callbacks()} is then a plain read.
     * The provider prefixes every tool with the client identity (the server's stable prefix),
     * and the tool filter drops the patterns the user disabled for that server.
     */
    private void refreshProvider() {
        List<McpSyncClient> clients = new ArrayList<>();
        Map<String, StoredServer> serversByPrefix = new LinkedHashMap<>();
        for (Map.Entry<String, ManagedServer> entry : connections.entrySet()) {
            ManagedServer managed = entry.getValue();
            if (managed.client() == null || !managed.client().isInitialized()) continue;
            StoredServer definition = lookupDefinition(entry.getKey());
            clients.add(managed.client());
            if (definition != null) {
                serversByPrefix.put(toolPrefixes.getOrDefault(definition.id(),
                        sanitizePrefix(definition.name())), definition);
            }
        }
        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(clients)
                .toolNamePrefixGenerator((connectionInfo, tool) -> {
                    String prefix = clientPrefix(connectionInfo);
                    return prefix == null ? tool.name() : prefix + "__" + tool.name();
                })
                .toolFilter((connectionInfo, tool) -> {
                    String prefix = clientPrefix(connectionInfo);
                    StoredServer definition = prefix == null ? null : serversByPrefix.get(prefix);
                    List<String> disabled = definition == null ? List.of() : definition.disabledToolPatterns();
                    String wireName = prefix == null ? tool.name() : prefix + "__" + tool.name();
                    return !isToolDisabled(wireName, disabled);
                })
                .build();
        callbacksSnapshot = List.of(provider.getToolCallbacks());
    }

    private static String clientPrefix(org.springframework.ai.mcp.McpConnectionInfo connectionInfo) {
        McpSchema.Implementation client = connectionInfo == null ? null : connectionInfo.clientInfo();
        String name = client == null || client.name() == null ? null : client.name().trim();
        return name == null || name.isBlank() ? null : name;
    }

    /**
     * Cherry-studio-style tool policy. A pattern disables a tool when it equals the bare tool
     * name or the full wire name ({@code server__tool}), ends with {@code *} for a prefix match
     * on either form, or is a lone {@code *} (all tools of the server).
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
                    ? wireName.startsWith(stem) || bare.startsWith(stem)
                    : pattern.equals(wireName) || pattern.equals(bare)) {
                return true;
            }
        }
        return false;
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
