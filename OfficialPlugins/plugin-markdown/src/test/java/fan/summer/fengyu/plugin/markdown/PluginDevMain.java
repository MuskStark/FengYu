package fan.summer.fengyu.plugin.markdown;

import fan.summer.fengyu.devkit.PluginDevServer;

import java.nio.file.Path;

/**
 * IDE-debug entry point for the Markdown worker.
 *
 * <p>Run this class's {@code main()} from your IDE's <strong>Debug</strong> action (NOT
 * {@link MarkdownWorkerMain}). It starts the devkit's {@link PluginDevServer} on loopback TCP,
 * serving the <strong>same handlers</strong> as the production worker via
 * {@link MarkdownWorkerMain#worker(MarkdownRpcHandlers)}. The {@code @infinia/plugin-dev} Vite
 * plugin in {@code ui-src/vite.config.ts} connects to this server and forwards {@code rpc.invoke}
 * from the UI, so IDE breakpoints in {@link MarkdownRpcHandlers} fire directly — no JDWP remote
 * attach.
 *
 * <p>Lives under {@code src/test/java} so it never ships in the shaded production JAR (the
 * devkit dependency is test-scoped). Override the port with {@code -Dfengyu.dev.port=<n>}.
 */
public final class PluginDevMain {
    private PluginDevMain() {}

    public static void main(String[] args) throws Exception {
        MarkdownRpcHandlers handlers = new MarkdownRpcHandlers();
        PluginDevServer server = PluginDevServer.builder()
            .worker(MarkdownWorkerMain.worker(handlers))
            .host("127.0.0.1")
            .port(Integer.getInteger("fengyu.dev.port", 24057))
            .pluginRoot(Path.of(System.getProperty("user.dir")))
            .start();
        // Block until the IDE Stop button terminates the process.
        server.await();
    }
}
