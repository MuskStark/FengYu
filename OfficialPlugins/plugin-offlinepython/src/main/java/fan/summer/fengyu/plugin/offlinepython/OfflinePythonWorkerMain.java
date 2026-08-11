package fan.summer.fengyu.plugin.offlinepython;

import fan.summer.offlinepython.generated.BuildCancelInput;
import fan.summer.offlinepython.generated.BuildCancelOutput;
import fan.summer.offlinepython.generated.BuildStartInput;
import fan.summer.offlinepython.generated.BuildStartOutput;
import fan.summer.offlinepython.generated.BuildStatusInput;
import fan.summer.offlinepython.generated.BuildStatusOutput;
import fan.summer.offlinepython.generated.ConfigGetInput;
import fan.summer.offlinepython.generated.ConfigGetOutput;
import fan.summer.offlinepython.generated.ConfigSaveInput;
import fan.summer.offlinepython.generated.ConfigSaveOutput;
import fan.summer.offlinepython.generated.DeployCancelInput;
import fan.summer.offlinepython.generated.DeployCancelOutput;
import fan.summer.offlinepython.generated.DeployStartInput;
import fan.summer.offlinepython.generated.DeployStartOutput;
import fan.summer.offlinepython.generated.DeployStatusInput;
import fan.summer.offlinepython.generated.DeployStatusOutput;
import fan.summer.offlinepython.generated.DepsLatestInput;
import fan.summer.offlinepython.generated.DepsLatestOutput;
import fan.summer.offlinepython.generated.DepsSearchInput;
import fan.summer.offlinepython.generated.DepsSearchOutput;
import fan.summer.offlinepython.generated.DoctorInput;
import fan.summer.offlinepython.generated.DoctorOutput;
import fan.summer.offlinepython.generated.InitInput;
import fan.summer.offlinepython.generated.InitOutput;
import fan.summer.offlinepython.generated.OfflinepythonBuildStartInput;
import fan.summer.offlinepython.generated.OfflinepythonBuildStartOutput;
import fan.summer.offlinepython.generated.OfflinepythonBuildStatusInput;
import fan.summer.offlinepython.generated.OfflinepythonBuildStatusOutput;
import fan.summer.offlinepython.generated.OfflinepythonDoctorInput;
import fan.summer.offlinepython.generated.OfflinepythonDoctorOutput;
import fan.summer.offlinepython.generated.OfflinepythonInitProjectInput;
import fan.summer.offlinepython.generated.OfflinepythonInitProjectOutput;
import fan.summer.offlinepython.generated.OfflinepythonSearchDepsInput;
import fan.summer.offlinepython.generated.OfflinepythonSearchDepsOutput;
import fan.summer.offlinepython.generated.OfflinepythonVerifyInput;
import fan.summer.offlinepython.generated.OfflinepythonVerifyOutput;
import fan.summer.offlinepython.generated.PackageInput;
import fan.summer.offlinepython.generated.PackageOutput;
import fan.summer.offlinepython.generated.PluginMethods;
import fan.summer.offlinepython.generated.PythonDetectInput;
import fan.summer.offlinepython.generated.PythonDetectOutput;
import fan.summer.offlinepython.generated.RequirementsGetInput;
import fan.summer.offlinepython.generated.RequirementsGetOutput;
import fan.summer.offlinepython.generated.RequirementsSaveInput;
import fan.summer.offlinepython.generated.RequirementsSaveOutput;
import fan.summer.offlinepython.generated.VerifyInput;
import fan.summer.offlinepython.generated.VerifyOutput;
import fan.summer.fengyu.sdk.Jobs;
import fan.summer.fengyu.sdk.JsonRpcWorker;

/**
 * Offline Python Builder worker. Speaks newline-delimited JSON-RPC 2.0 on stdio.
 *
 * <p>Every method is registered through the typed {@link JsonRpcWorker#method} API: the SDK
 * deserializes the JSON-RPC params into the generated {@code XInput} record, binds an
 * {@code RpcContext} to the handler thread (cancellation token + locale + logger), invokes the
 * matching {@link OfflinePythonRpcHandlers} method, and serializes the returned {@code XOutput}
 * back into the response. {@link JsonRpcWorker#run()} redirects stdout to stderr so the protocol
 * stream on stdout stays clean.
 *
 * <p>Methods are split into the session-keyed UI workflow and the stateless AI tools declared in
 * {@code manifest.json}; both share one {@link OfflinePythonRpcHandlers}. Build/deploy are launched
 * as async {@link fan.summer.fengyu.sdk.Jobs} (start → jobId → poll status) because the host kills
 * any single RPC after the declared timeout and a real {@code pip download} routinely exceeds that.
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
            .method(PluginMethods.INIT, InitInput.class, InitOutput.class, handlers::init)
            .method(PluginMethods.CONFIG_GET, ConfigGetInput.class, ConfigGetOutput.class, handlers::configGet)
            .method(PluginMethods.CONFIG_SAVE, ConfigSaveInput.class, ConfigSaveOutput.class, handlers::configSave)
            .method(PluginMethods.REQUIREMENTS_GET, RequirementsGetInput.class, RequirementsGetOutput.class, handlers::requirementsGet)
            .method(PluginMethods.REQUIREMENTS_SAVE, RequirementsSaveInput.class, RequirementsSaveOutput.class, handlers::requirementsSave)
            .method(PluginMethods.PYTHON_DETECT, PythonDetectInput.class, PythonDetectOutput.class, handlers::pythonDetect)
            .method(PluginMethods.DEPS_LATEST, DepsLatestInput.class, DepsLatestOutput.class, handlers::depsLatest)
            .method(PluginMethods.DEPS_SEARCH, DepsSearchInput.class, DepsSearchOutput.class, handlers::depsSearch)
            .method(PluginMethods.VERIFY, VerifyInput.class, VerifyOutput.class, handlers::verify)
            .method(PluginMethods.PACKAGE, PackageInput.class, PackageOutput.class, handlers::packageBundle)
            .method(PluginMethods.DOCTOR, DoctorInput.class, DoctorOutput.class, handlers::doctor)
            // ---- Async build / deploy ----
            .method(PluginMethods.BUILD_START, BuildStartInput.class, BuildStartOutput.class, handlers::buildStart)
            .method(PluginMethods.BUILD_STATUS, BuildStatusInput.class, BuildStatusOutput.class, handlers::buildStatus)
            .method(PluginMethods.BUILD_CANCEL, BuildCancelInput.class, BuildCancelOutput.class, handlers::buildCancel)
            .method(PluginMethods.DEPLOY_START, DeployStartInput.class, DeployStartOutput.class, handlers::deployStart)
            .method(PluginMethods.DEPLOY_STATUS, DeployStatusInput.class, DeployStatusOutput.class, handlers::deployStatus)
            .method(PluginMethods.DEPLOY_CANCEL, DeployCancelInput.class, DeployCancelOutput.class, handlers::deployCancel)
            // ---- AI-facing, stateless tools (declared in manifest.aiTools[]) ----
            .method(PluginMethods.OFFLINEPYTHON_DOCTOR, OfflinepythonDoctorInput.class, OfflinepythonDoctorOutput.class, handlers::offlinepythonDoctor)
            .method(PluginMethods.OFFLINEPYTHON_SEARCH_DEPS, OfflinepythonSearchDepsInput.class, OfflinepythonSearchDepsOutput.class, handlers::offlinepythonSearchDeps)
            .method(PluginMethods.OFFLINEPYTHON_INIT_PROJECT, OfflinepythonInitProjectInput.class, OfflinepythonInitProjectOutput.class, handlers::offlinepythonInitProject)
            .method(PluginMethods.OFFLINEPYTHON_VERIFY, OfflinepythonVerifyInput.class, OfflinepythonVerifyOutput.class, handlers::offlinepythonVerify)
            .method(PluginMethods.OFFLINEPYTHON_BUILD_START, OfflinepythonBuildStartInput.class, OfflinepythonBuildStartOutput.class, handlers::offlinepythonBuildStart)
            .method(PluginMethods.OFFLINEPYTHON_BUILD_STATUS, OfflinepythonBuildStatusInput.class, OfflinepythonBuildStatusOutput.class, handlers::offlinepythonBuildStatus);
    }
}
