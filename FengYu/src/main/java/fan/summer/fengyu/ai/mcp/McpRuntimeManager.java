package fan.summer.fengyu.ai.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 */
@Service
public final class McpRuntimeManager {

    private static final Logger log = LoggerFactory.getLogger(McpRuntimeManager.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration INITIALIZATION_TIMEOUT = Duration.ofSeconds(30);
    private static final String REGISTRY_FILE = "servers.json";
    private static final String SECRETS_FILE = "secrets.json";

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final Path directory;
    private final Path registryFile;
    private final Path secretsFile;
    private final Map<String, StoredServer> definitions = new LinkedHashMap<>();
    private final Map<String, ManagedServer> connections = new ConcurrentHashMap<>();
    private final ReentrantLock lifecycle = new ReentrantLock();
    private volatile SyncMcpToolCallbackProvider callbackProvider = SyncMcpToolCallbackProvider.builder().build();

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
            callbackProvider = SyncMcpToolCallbackProvider.builder().build();
        } finally {
            lifecycle.unlock();
        }
    }

    public List<ToolCallback> callbacks() {
        return List.of(callbackProvider.getToolCallbacks());
    }

    public List<ServerView> servers() {
        lifecycle.lock();
        try {
            List<ServerView> views = new ArrayList<>();
            for (StoredServer definition : definitions.values()) views.add(view(definition));
            return List.copyOf(views);
        } finally {
            lifecycle.unlock();
        }
    }

    public ServerView save(ServerRequest request, String id) {
        lifecycle.lock();
        try {
            if (id != null && !definitions.containsKey(id)) {
                throw new McpRuntimeException("MCP server not found: " + id);
            }
            String serverId = id == null ? UUID.randomUUID().toString() : id;
            StoredServer definition = toStored(request, serverId, id == null ? null : definitions.get(id));
            ManagedServer previous = connections.remove(definition.id());
            if (previous != null) closeQuietly(previous.client());
            definitions.put(definition.id(), definition);
            saveFiles();
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
            if (!definitions.containsKey(id)) return false;
            ManagedServer connection = connections.remove(id);
            if (connection != null) closeQuietly(connection.client());
            definitions.remove(id);
            saveFiles();
            removeSecret(id);
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
            StoredServer definition = definitions.get(id);
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

    public record ServerRequest(
            String name,
            String type,
            String command,
            List<String> args,
            Map<String, String> env,
            String url,
            String endpoint,
            Map<String, String> headers,
            Boolean enabled) {
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
            List<String> headerNames) {
    }

    public record PromptView(String name, String title, String description, List<String> arguments) {}

    public record ResourceView(String name, String title, String uri, String description, String mimeType) {}

    public static final class McpRuntimeException extends IllegalArgumentException {
        public McpRuntimeException(String message) { super(message); }
        public McpRuntimeException(String message, Throwable cause) { super(message, cause); }
    }

    private record StoredServer(String id, String name, String type, String command, List<String> args,
                                String url, String endpoint, boolean enabled) {}

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
        SecretConfig secrets = readSecrets().getOrDefault(definition.id(), new SecretConfig(Map.of(), Map.of()));
        String type = normalizeType(definition.type());
        var transport = switch (type) {
            case "STDIO" -> new StdioClientTransport(
                    ServerParameters.builder(required(definition.command(), "command"))
                            .args(definition.args() == null ? List.of() : definition.args())
                            .env(secrets.env()).build(),
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
                .clientInfo(new McpSchema.Implementation("FengYu", "4.0.0"))
                .requestTimeout(REQUEST_TIMEOUT)
                .initializationTimeout(INITIALIZATION_TIMEOUT)
                .toolsChangeConsumer(ignored -> refreshProvider())
                .build();
    }

    private static HttpRequest.Builder requestBuilder(Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        if (headers != null) headers.forEach((key, value) -> builder.header(key, value));
        return builder;
    }

    private ServerView view(StoredServer definition) {
        ManagedServer managed = connections.get(definition.id());
        SecretConfig secrets = readSecrets().getOrDefault(definition.id(), new SecretConfig(Map.of(), Map.of()));
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
                tools, secrets.env().keySet().stream().sorted().toList(), secrets.headers().keySet().stream().sorted().toList());
    }

    private void refreshProvider() {
        List<McpSyncClient> clients = connections.values().stream().map(ManagedServer::client)
                .filter(client -> client != null && client.isInitialized()).toList();
        callbackProvider = SyncMcpToolCallbackProvider.builder().mcpClients(clients).build();
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
        Map<String, String> oldSecrets = previous == null ? Map.of() : readSecrets()
                .getOrDefault(id, new SecretConfig(Map.of(), Map.of())).env();
        Map<String, String> oldHeaders = previous == null ? Map.of() : readSecrets()
                .getOrDefault(id, new SecretConfig(Map.of(), Map.of())).headers();
        SecretConfig secrets = new SecretConfig(
                request.env() == null ? oldSecrets : cleanMap(request.env()),
                request.headers() == null ? oldHeaders : cleanMap(request.headers()));
        writeSecret(id, secrets);
        return new StoredServer(id, name, type, blankToNull(request.command()),
                request.args() == null ? List.of() : List.copyOf(request.args()), blankToNull(request.url()),
                blankToNull(request.endpoint()), request.enabled() == null || request.enabled());
    }

    private void load() {
        try {
            Files.createDirectories(directory);
            if (Files.exists(registryFile)) {
                List<StoredServer> loaded = json.readValue(Files.readString(registryFile), new TypeReference<>() {});
                for (StoredServer value : loaded) definitions.put(value.id(), value);
            }
        } catch (Exception error) {
            throw new McpRuntimeException("Cannot read MCP server registry", error);
        }
    }

    private void saveFiles() {
        try {
            Files.createDirectories(directory);
            Files.writeString(registryFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(definitions.values()),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            protect(registryFile);
        } catch (Exception error) {
            throw new McpRuntimeException("Cannot save MCP server registry", error);
        }
    }

    private Map<String, SecretConfig> readSecrets() {
        try {
            if (!Files.exists(secretsFile)) return Map.of();
            Map<String, SecretConfig> result = json.readValue(Files.readString(secretsFile), new TypeReference<>() {});
            return result == null ? Map.of() : result;
        } catch (Exception error) {
            log.warn("Cannot read MCP secrets: {}", error.toString());
            return Map.of();
        }
    }

    private void writeSecret(String id, SecretConfig secrets) {
        try {
            Files.createDirectories(directory);
            Map<String, SecretConfig> all = new LinkedHashMap<>(readSecrets());
            all.put(id, secrets);
            Files.writeString(secretsFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(all),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            protect(secretsFile);
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
                Files.writeString(secretsFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(all),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                protect(secretsFile);
            }
        } catch (Exception error) {
            throw new McpRuntimeException("Cannot delete MCP credentials", error);
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

    private static void closeQuietly(McpSyncClient client) {
        if (client != null) try { client.closeGracefully(); } catch (Exception ignored) { client.close(); }
    }
}
