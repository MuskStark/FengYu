package {{javaPackage}};

import fan.summer.fengyu.sdk.JsonRpcWorker;

/**
 * {{pluginName}} worker production entry point.
 *
 * Links the FengYu Plugin Worker SDK and serves the JSON-RPC 2.0 protocol over stdin/stdout —
 * exactly how the FengYu host drives the worker in production. Handler registration lives in
 * {@link {{javaClassPrefix}}Worker} so it is shared with {@code PluginDevMain} (the IDE-debug
 * entry point under {@code src/test/java}).
 *
 * For development / debugging, run {@code PluginDevMain.main()} from your IDE instead — it serves
 * the same handlers over loopback TCP so you can set breakpoints without JDWP remote attach.
 */
public final class {{javaClassPrefix}}WorkerMain {
    public static void main(String[] args) throws Exception {
        {{javaClassPrefix}}Worker.create().run();
    }
}
