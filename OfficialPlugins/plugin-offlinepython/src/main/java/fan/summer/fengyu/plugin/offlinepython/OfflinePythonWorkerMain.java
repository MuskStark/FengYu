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
            .onClose(handlers)
            // ---- UI-facing, session-keyed workflow ----
            .on("init", handlers.handle("init", handlers::init))
            .on("config.get", handlers.handle("config.get", handlers::configGet))
            .on("config.save", handlers.handle("config.save", handlers::configSave))
            .on("requirements.get", handlers.handle("requirements.get", handlers::requirementsGet))
            .on("requirements.save", handlers.handle("requirements.save", handlers::requirementsSave))
            .on("python.detect", handlers.handle("python.detect", handlers::pythonDetect))
            .on("deps.latest", handlers.handle("deps.latest", handlers::depsLatest))
            .on("deps.search", handlers.handle("deps.search", handlers::depsSearch))
            .on("verify", handlers.handle("verify", handlers::verify))
            .on("package", handlers.handle("package", handlers::doPackage))
            .on("doctor", handlers.handle("doctor", handlers::doctor))
            // ---- Async build / deploy ----
            .on("build.start", handlers.handle("build.start", handlers::buildStart))
            .on("build.status", handlers.handle("build.status", handlers::buildStatus))
            .on("build.cancel", handlers.handle("build.cancel", handlers::buildCancel))
            .on("deploy.start", handlers.handle("deploy.start", handlers::deployStart))
            .on("deploy.status", handlers.handle("deploy.status", handlers::deployStatus))
            .on("deploy.cancel", handlers.handle("deploy.cancel", handlers::deployCancel))
            // ---- AI-facing, stateless tools (declared in manifest.aiTools[]) ----
            .on("offlinepython_doctor", handlers.handle("offlinepython_doctor", handlers::aiDoctor))
            .on("offlinepython_search_deps", handlers.handle("offlinepython_search_deps", handlers::aiSearchDeps))
            .on("offlinepython_init_project", handlers.handle("offlinepython_init_project", handlers::aiInitProject))
            .on("offlinepython_verify", handlers.handle("offlinepython_verify", handlers::aiVerify))
            .on("offlinepython_build_start", handlers.handle("offlinepython_build_start", handlers::aiBuildStart))
            .on("offlinepython_build_status", handlers.handle("offlinepython_build_status", handlers::aiBuildStatus));
    }
}
