package fan.summer.fengyu.plugin.email;

import fan.summer.fengyu.devkit.PluginDevServer;
import fan.summer.fengyu.plugin.email.rpc.EmailRpcHandlers;

import java.nio.file.Path;

/**
 * IDE-debug entry point for the Email Center worker.
 *
 * <p>Run this class's {@code main()} from your IDE's <strong>Debug</strong> action (NOT
 * {@link EmailWorkerMain}). It starts the devkit's {@link PluginDevServer} on loopback TCP,
 * serving the <strong>same handlers</strong> as the production worker via
 * {@link EmailWorkerMain#worker(EmailRpcHandlers)}. The {@code @infinia/plugin-dev} Vite plugin
 * in {@code ui-src/vite.config.ts} connects to this server and forwards {@code rpc.invoke} from
 * the UI, so IDE breakpoints in {@code EmailRpcHandlers} fire directly — no JDWP remote attach.
 *
 * <p><strong>Email requires a database.</strong> {@link EmailWorkerMain#handlers} reads the
 * {@code FENGYU_DB_*} environment variables (via {@link fan.summer.fengyu.sdk.PluginDatabaseConfig}),
 * so configure them in your IDE run configuration — e.g. an embedded H2 for dev:
 * <pre>{@code
 * FENGYU_DB_TYPE=h2
 * FENGYU_DB_DRIVER=org.h2.Driver
 * FENGYU_DB_URL=jdbc:h2:file:/tmp/fengyu-email-dev;AUTO_SERVER=TRUE
 * FENGYU_DB_USERNAME=sa
 * FENGYU_DB_PASSWORD=
 * FENGYU_PLUGIN_DATA_DIR=/tmp/fengyu-email-dev-data
 * }</pre>
 *
 * <p>Lives under {@code src/test/java} so it never ships in the shaded production JAR (the
 * devkit dependency is test-scoped). Override the port with {@code -Dfengyu.dev.port=<n>}.
 */
public final class PluginDevMain {
    private PluginDevMain() {}

    public static void main(String[] args) throws Exception {
        EmailRpcHandlers handlers = EmailWorkerMain.handlers(System.getenv());
        PluginDevServer server = PluginDevServer.builder()
            .worker(EmailWorkerMain.worker(handlers))
            .host("127.0.0.1")
            .port(Integer.getInteger("fengyu.dev.port", 24057))
            .pluginRoot(Path.of(System.getProperty("user.dir")))
            .start();
        // Block until the IDE Stop button terminates the process.
        server.await();
    }
}
