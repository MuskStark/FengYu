package fan.summer.fengyu.plugin.offlinepython;

import fan.summer.offlinepython.contract.OfflinepythonContract.BuildCancelInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.BuildCancelOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.BuildStartInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.BuildStartOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.BuildStatusInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.BuildStatusOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.ConfigGetInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.ConfigGetOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.ConfigSaveInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.ConfigSaveOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.DeployCancelInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.DeployCancelOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.DeployStartInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.DeployStartOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.DeployStatusInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.DeployStatusOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.DepsLatestInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.DepsLatestOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.DepsSearchInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.DepsSearchOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.DoctorInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.DoctorOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.InitInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.InitOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.OfflinepythonBuildStartInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.OfflinepythonBuildStartOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.OfflinepythonBuildStatusInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.OfflinepythonBuildStatusOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.OfflinepythonDoctorInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.OfflinepythonDoctorOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.OfflinepythonInitProjectInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.OfflinepythonInitProjectOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.OfflinepythonSearchDepsInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.OfflinepythonSearchDepsOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.OfflinepythonVerifyInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.OfflinepythonVerifyOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.PackageInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.PackageOutput;
import fan.summer.offlinepython.generated.PluginMethods;
import fan.summer.offlinepython.contract.OfflinepythonContract.PythonDetectInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.PythonDetectOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.RequirementsGetInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.RequirementsGetOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.RequirementsSaveInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.RequirementsSaveOutput;
import fan.summer.offlinepython.contract.OfflinepythonContract.VerifyInput;
import fan.summer.offlinepython.contract.OfflinepythonContract.VerifyOutput;
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
