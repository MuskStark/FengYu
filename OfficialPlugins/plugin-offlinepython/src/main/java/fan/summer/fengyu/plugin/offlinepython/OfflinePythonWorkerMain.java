package fan.summer.fengyu.plugin.offlinepython;

import fan.summer.fengyu.sdk.Jobs;
import fan.summer.fengyu.sdk.JsonRpcWorker;

/**
 * Offline Python Builder worker. Speaks newline-delimited JSON-RPC 2.0 on stdio.
 *
 * <p>Methods are split into the session-keyed UI workflow and the stateless AI tools declared in
 * {@code manifest.json}; both share one {@link OfflinePythonRpcHandlers}. Build/deploy are launched
 * as async {@link Jobs} (start → jobId → poll status) because the host kills any single RPC after
 * ~60s and a real {@code pip download} routinely exceeds that. {@link JsonRpcWorker#run()} redirects
 * stdout to stderr so the protocol stream on stdout stays clean.
 */
public final class OfflinePythonWorkerMain {
    private OfflinePythonWorkerMain() {}

    public static void main(String[] args) throws Exception {
        OfflinePythonSessionStore sessions = new OfflinePythonSessionStore();
        OfflinePythonRpcHandlers handlers = new OfflinePythonRpcHandlers(sessions, new Jobs());
        worker(handlers).run();
    }

    static JsonRpcWorker worker(OfflinePythonRpcHandlers handlers) {
        return new JsonRpcWorker()
            // ---- UI-facing, session-keyed workflow ----
            .on("init", handlers.safe(handlers::init))
            .on("config.get", handlers.safe(handlers::configGet))
            .on("config.save", handlers.safe(handlers::configSave))
            .on("requirements.get", handlers.safe(handlers::requirementsGet))
            .on("requirements.save", handlers.safe(handlers::requirementsSave))
            .on("python.detect", handlers.safe(handlers::pythonDetect))
            .on("deps.latest", handlers.safe(handlers::depsLatest))
            .on("deps.search", handlers.safe(handlers::depsSearch))
            .on("verify", handlers.safe(handlers::verify))
            .on("package", handlers.safe(handlers::doPackage))
            .on("doctor", handlers.safe(handlers::doctor))
            // ---- Async build / deploy ----
            .on("build.start", handlers.safe(handlers::buildStart))
            .on("build.status", handlers.safe(handlers::buildStatus))
            .on("build.cancel", handlers.safe(handlers::buildCancel))
            .on("deploy.start", handlers.safe(handlers::deployStart))
            .on("deploy.status", handlers.safe(handlers::deployStatus))
            .on("deploy.cancel", handlers.safe(handlers::deployCancel))
            // ---- AI-facing, stateless tools (declared in manifest.aiTools[]) ----
            .on("offlinepython_doctor", handlers.safe(handlers::aiDoctor))
            .on("offlinepython_search_deps", handlers.safe(handlers::aiSearchDeps))
            .on("offlinepython_init_project", handlers.safe(handlers::aiInitProject))
            .on("offlinepython_verify", handlers.safe(handlers::aiVerify))
            .on("offlinepython_build_start", handlers.safe(handlers::aiBuildStart))
            .on("offlinepython_build_status", handlers.safe(handlers::aiBuildStatus));
    }
}
