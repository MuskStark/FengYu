package fan.summer.fengyu.devkit;

import fan.summer.fengyu.sdk.JsonRpcWorker;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A loopback-only TCP server that speaks newline-delimited JSON-RPC 2.0 — the same protocol the
 * production host speaks over a worker's stdin/stdout — so a plugin worker can be debugged from an
 * IDE by running {@code PluginDevMain.main()} and pointing the {@code @infinia/plugin-dev} Vite
 * plugin at this port.
 *
 * <p>Each accepted connection wraps a {@link LineFramedSocketTransport} and drives the supplied
 * worker's {@link JsonRpcWorker#serve(fan.summer.fengyu.sdk.RpcTransport)} loop on its own virtual
 * thread, so the author's handler breakpoints fire directly — no JDWP remote-attach needed.
 *
 * <p>Only the loopback interface ({@code 127.0.0.1}) is ever bound; the dev server must never be
 * reachable off-host. If the configured port is taken, {@link Builder#start} fails fast with an
 * actionable message instead of silently picking another port.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * PluginDevServer.builder()
 *     .worker(MyWorker.create())          // your JsonRpcWorker, same handlers as production
 *     .host("127.0.0.1")                  // default
 *     .port(24057)                        // default
 *     .pluginId("com.example.my-plugin")  // sets FENGYU_PLUGIN_ID for the worker
 *     .pluginRoot(Path.of(".."))          // sets FENGYU_PLUGIN_ROOT for the worker
 *     .start()
 *     .await();                           // block until shut down via the IDE Stop button
 * }</pre>
 *
 * <p>{@code worker(...)} shares a single {@link JsonRpcWorker} across connections (handlers are
 * expected to be stateless); use {@code workerFactory(...)} when each connection needs its own
 * instance (e.g. per-connection mutable state).
 */
public final class PluginDevServer {
    private final ServerSocket serverSocket;
    private final Supplier<JsonRpcWorker> workerSource;
    private final String host;
    private final int port;
    private final Consumer<String> onDiag;
    private volatile boolean closed = false;
    private final Thread acceptThread;

    /** Begin configuring a new dev server. {@link Builder#worker} / {@link Builder#workerFactory} is required. */
    public static Builder builder() {
        return new Builder();
    }

    private PluginDevServer(ServerSocket serverSocket, Supplier<JsonRpcWorker> workerSource,
                            String host, int port, Consumer<String> onDiag) {
        this.serverSocket = serverSocket;
        this.workerSource = workerSource;
        this.host = host;
        this.port = port;
        this.onDiag = onDiag;
        this.acceptThread = Thread.ofPlatform().name("fengyu-dev-accept", 0).daemon(false).start(this::acceptLoop);
    }

    /** The bound loopback host (always {@code 127.0.0.1}). */
    public String host() { return host; }

    /** The bound port (useful when {@link Builder#port(int)} was given 0 for an ephemeral port). */
    public int port() { return port; }

    private void acceptLoop() {
        onDiag.accept("FengYu dev server listening on " + host + ":" + port
            + " (attach your IDE PluginDevMain; @infinia/plugin-dev Vite plugin connects here)");
        while (!closed) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                if (closed) return;
                onDiag.accept("dev server: accept failed: " + e.getMessage());
                return;
            }
            // Reject any non-loopback peer defensively even though the socket is bound to 127.0.0.1.
            if (!client.getInetAddress().isLoopbackAddress()) {
                onDiag.accept("dev server: rejected non-loopback connection from " + client.getInetAddress());
                try { client.close(); } catch (IOException ignored) {}
                continue;
            }
            Thread.startVirtualThread(() -> handle(client));
        }
    }

    private void handle(Socket client) {
        try (client; LineFramedSocketTransport transport = new LineFramedSocketTransport(client, onDiag)) {
            JsonRpcWorker worker = workerSource.get();
            worker.serve(transport);
        } catch (Exception e) {
            // serve() returns cleanly on EOF; anything else is a transport error worth surfacing
            // to the IDE console but must not take down the accept loop or other connections.
            onDiag.accept("dev server: connection ended: " + e.getMessage());
        }
    }

    /** Stop the accept loop and close the listening socket. Pending connections finish naturally. */
    public void close() {
        if (closed) return;
        closed = true;
        try { serverSocket.close(); } catch (IOException ignored) {}
        acceptThread.interrupt();
    }

    /** Block the calling thread until the server is stopped (for {@code main()} entry points). */
    public void await() throws InterruptedException {
        acceptThread.join();
    }

    /** A builder for {@link PluginDevServer}. Exactly one of {@link #worker} / {@link #workerFactory} is required. */
    public static final class Builder {
        private JsonRpcWorker worker;
        private Supplier<JsonRpcWorker> workerFactory;
        private String host = "127.0.0.1";
        private int port = 24057;
        private String pluginId;
        private Path pluginRoot;
        private Consumer<String> onDiag = msg -> System.err.println("[fengyu-dev] " + msg);

        /** Share a single {@link JsonRpcWorker} across all connections (stateless handlers). */
        public Builder worker(JsonRpcWorker worker) {
            Objects.requireNonNull(worker, "worker");
            if (this.workerFactory != null) throw new IllegalStateException("workerFactory already set");
            this.worker = worker;
            return this;
        }

        /** Provide a fresh {@link JsonRpcWorker} per connection (per-connection mutable state). */
        public Builder workerFactory(Supplier<JsonRpcWorker> factory) {
            Objects.requireNonNull(factory, "workerFactory");
            if (this.worker != null) throw new IllegalStateException("worker already set");
            this.workerFactory = factory;
            return this;
        }

        /** Loopback host. Defaults to {@code 127.0.0.1}; a non-loopback value is rejected. */
        public Builder host(String host) {
            this.host = Objects.requireNonNull(host, "host");
            return this;
        }

        /** Port to bind. Defaults to 24057; pass 0 for an ephemeral port chosen by the OS. */
        public Builder port(int port) {
            if (port < 0 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
            this.port = port;
            return this;
        }

        /**
         * Plugin id exposed to the worker via the {@code FENGYU_PLUGIN_ID} system property,
         * mirroring the {@code FENGYU_PLUGIN_ID} env var the production host injects. Optional.
         */
        public Builder pluginId(String pluginId) {
            this.pluginId = pluginId;
            return this;
        }

        /**
         * Plugin root exposed to the worker via the {@code FENGYU_PLUGIN_ROOT} system property,
         * mirroring the {@code FENGYU_PLUGIN_ROOT} env var the production host injects. Optional.
         */
        public Builder pluginRoot(Path pluginRoot) {
            this.pluginRoot = pluginRoot;
            return this;
        }

        /** Sink for diagnostic lines (defaults to {@code System.err}). */
        public Builder onDiagnostic(Consumer<String> onDiag) {
            this.onDiag = Objects.requireNonNullElse(onDiag, msg -> {});
            return this;
        }

        public PluginDevServer start() throws IOException {
            if (worker == null && workerFactory == null) {
                throw new IllegalStateException("either worker() or workerFactory() is required");
            }
            final Supplier<JsonRpcWorker> source = workerFactory != null
                ? workerFactory
                : () -> worker;

            InetAddress addr;
            try {
                addr = InetAddress.getByName(host);
            } catch (java.net.UnknownHostException e) {
                throw new IllegalArgumentException("unknown host: " + host, e);
            }
            if (!addr.isLoopbackAddress()) {
                throw new IllegalArgumentException(
                    "dev server must bind a loopback address; '" + host + "' is not loopback");
            }

            // Set FENGYU_PLUGIN_ID / FENGYU_PLUGIN_ROOT for the worker before any connection is
            // accepted, mirroring the production host's environment injection by name (there they
            // are ProcessBuilder env vars; here System.setProperty is the same-process IDE launch
            // equivalent). JsonRpcWorker resolves these via getenv with a System.getProperty
            // fallback, so this same-JVM injection populates RpcContext.pluginId()/pluginRoot()
            // for in-IDE debugging (env stays authoritative in production).
            if (pluginId != null) {
                System.setProperty("FENGYU_PLUGIN_ID", pluginId);
            }
            if (pluginRoot != null) {
                // Use portable separators so the value is stable when serialized by a worker
                // and compared with slash-delimited manifest paths on Windows as well as Unix.
                System.setProperty("FENGYU_PLUGIN_ROOT",
                    pluginRoot.toAbsolutePath().normalize().toString().replace('\\', '/'));
            }

            ServerSocket socket;
            try {
                socket = new ServerSocket();
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress(addr, port));
            } catch (IOException e) {
                throw new IOException(
                    "FengYu dev server could not bind " + host + ":" + port
                    + " (in use? stop the other dev server, or pick another port via -Dfengyu.dev.port=<n>): "
                    + e.getMessage(), e);
            }
            int boundPort = socket.getLocalPort();
            return new PluginDevServer(socket, source, host, boundPort, onDiag);
        }
    }
}
