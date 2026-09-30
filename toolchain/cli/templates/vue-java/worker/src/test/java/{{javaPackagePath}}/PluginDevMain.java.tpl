package {{javaPackage}};

import fan.summer.fengyu.devkit.PluginDevServer;

import java.nio.file.Path;

/**
 * IDE-debug entry point for the {{pluginName}} worker.
 *
 * <p>Run this class's {@code main()} from your IDE's <strong>Debug</strong> action (NOT
 * {{javaClassPrefix}}WorkerMain). It starts a loopback TCP JSON-RPC server (the devkit's
 * {@link PluginDevServer}) that serves the <strong>same handlers</strong> as the production
 * worker via {@link {{javaClassPrefix}}Worker#create()}. The {@code @infinia/plugin-dev} Vite
 * plugin in {@code ui-src/vite.config.ts} connects to this server and forwards {@code rpc.invoke}
 * from the UI, so your IDE breakpoints in the handlers fire directly — no JDWP remote attach.
 *
 * <p>Lives under {@code src/test/java} so it is never packaged into the shaded production JAR
 * (the devkit dependency is test-scoped). Override the port with {@code -Dfengyu.dev.port=<n>}.
 */
public final class PluginDevMain {
    public static void main(String[] args) throws Exception {
        PluginDevServer server = PluginDevServer.builder()
            .worker({{javaClassPrefix}}Worker.create())
            .host("127.0.0.1")
            .port(Integer.getInteger("fengyu.dev.port", 24057))
            .pluginRoot(Path.of(System.getProperty("user.dir")).resolve(".."))
            .start();
        // Block until the IDE Stop button terminates the process.
        server.await();
    }
}
