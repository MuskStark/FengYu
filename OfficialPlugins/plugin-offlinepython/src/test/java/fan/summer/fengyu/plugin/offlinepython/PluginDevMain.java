package fan.summer.fengyu.plugin.offlinepython;

import fan.summer.fengyu.devkit.PluginDevServer;
import fan.summer.fengyu.sdk.Jobs;

import java.nio.file.Path;

/**
 * IDE-debug entry point for the Offline Python Builder worker.
 *
 * <p>Run this class's {@code main()} from your IDE's <strong>Debug</strong> action (NOT
 * {@link OfflinePythonWorkerMain}). It starts the devkit's {@link PluginDevServer} on loopback TCP,
 * serving the <strong>same handlers</strong> as the production worker via
 * {@link OfflinePythonWorkerMain#worker(OfflinePythonRpcHandlers)}. The {@code @infinia/plugin-dev}
 * Vite plugin in {@code ui-src/vite.config.ts} connects to this server and forwards
 * {@code rpc.invoke} from the UI, so IDE breakpoints in {@link OfflinePythonRpcHandlers} fire
 * directly — no JDWP remote attach.
 *
 * <p>Lives under {@code src/test/java} so it never ships in the shaded production JAR (the
 * devkit dependency is test-scoped). Override the port with {@code -Dfengyu.dev.port=<n>}.
 */
public final class PluginDevMain {
    private PluginDevMain() {}

    public static void main(String[] args) throws Exception {
        OfflinePythonSessionStore sessions = new OfflinePythonSessionStore();
        OfflinePythonRpcHandlers handlers = new OfflinePythonRpcHandlers(sessions, new Jobs());
        PluginDevServer server = PluginDevServer.builder()
            .worker(OfflinePythonWorkerMain.worker(handlers))
            .host("127.0.0.1")
            .port(Integer.getInteger("fengyu.dev.port", 24057))
            .pluginRoot(Path.of(System.getProperty("user.dir")))
            .start();
        // Block until the IDE Stop button terminates the process.
        server.await();
    }
}
