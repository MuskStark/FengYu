package fan.summer.offlinepython.contract;

import fan.summer.fengyu.sdk.RpcContext;
import fan.summer.fengyu.sdk.contract.FengYuAiTool;
import fan.summer.fengyu.sdk.contract.FengYuContract;
import fan.summer.fengyu.sdk.contract.FengYuField;
import fan.summer.fengyu.sdk.contract.FengYuRpc;
import java.util.List;

/** RPC contract for fan.summer.offlinepython — migrated from the manifest-first manifest.json. */
public interface OfflinepythonContract {
    @FengYuContract
    interface BuildRpc {
    @FengYuRpc(name = "buildCancel", description = "Cancel a running build job; terminates the pip subprocess tree.")
    BuildCancelOutput buildCancel(BuildCancelInput input, RpcContext context);

    @FengYuRpc(name = "buildStart", description = "Start an offline wheelhouse build (pip download) as a background job and return its job id.")
    BuildStartOutput buildStart(BuildStartInput input, RpcContext context);

    @FengYuRpc(name = "buildStatus", description = "Poll a build job: streamed logs (from cursor) and the final BuildSummary result when done.")
    BuildStatusOutput buildStatus(BuildStatusInput input, RpcContext context);
    }

    @FengYuContract
    interface ConfigurationRpc {
    @FengYuRpc(name = "configGet", description = "Load the project's BuildConfig (merged from config.json or defaults).")
    ConfigGetOutput configGet(ConfigGetInput input, RpcContext context);

    @FengYuRpc(name = "configSave", description = "Persist requirements.txt and merge the supplied config sections onto config.json.")
    ConfigSaveOutput configSave(ConfigSaveInput input, RpcContext context);
    }

    @FengYuContract
    interface DeployRpc {
    @FengYuRpc(name = "deployCancel", description = "Cancel a running deploy job; terminates the pip subprocess tree.")
    DeployCancelOutput deployCancel(DeployCancelInput input, RpcContext context);

    @FengYuRpc(name = "deployStart", description = "Start an offline deploy (per-wheel pip install) of a bundle ZIP as a background job.")
    DeployStartOutput deployStart(DeployStartInput input, RpcContext context);

    @FengYuRpc(name = "deployStatus", description = "Poll a deploy job: streamed logs (from cursor) and the final DeployResult when done.")
    DeployStatusOutput deployStatus(DeployStatusInput input, RpcContext context);
    }

    @FengYuContract
    interface ProjectRpc {
    @FengYuRpc(name = "depsLatest", description = "Resolve the latest available version of a package via pip index versions.")
    DepsLatestOutput depsLatest(DepsLatestInput input, RpcContext context);

    @FengYuRpc(name = "depsSearch", description = "Search PyPI for available wheels for a package (version, platform, size, filename).")
    DepsSearchOutput depsSearch(DepsSearchInput input, RpcContext context);

    @FengYuRpc(name = "doctor", description = "Diagnose the host Python and pip environment for offline-build readiness.")
    DoctorOutput doctor(DoctorInput input, RpcContext context);

    @FengYuRpc(name = "init", description = "Initialize an offline-Python project skeleton (config.json, requirements.txt, README.md) in a writable directory.")
    InitOutput init(InitInput input, RpcContext context);
    }

    @FengYuContract
    interface AiRpc {
    @FengYuRpc(name = "offlinepythonBuildStart", description = "AI tool: start an offline wheelhouse build and return its job id.")
    @FengYuAiTool(name = "offlinepython_build_start", description = "Start an offline wheelhouse build and return its job id.", effect = FengYuAiTool.ToolEffect.EXTERNAL)
    OfflinepythonBuildStartOutput offlinepythonBuildStart(OfflinepythonBuildStartInput input, RpcContext context);

    @FengYuRpc(name = "offlinepythonBuildStatus", description = "AI tool: poll a build job and return streamed logs plus its final result.")
    @FengYuAiTool(name = "offlinepython_build_status", description = "Poll an offline-build job and return streamed logs plus its final result.", effect = FengYuAiTool.ToolEffect.READ)
    OfflinepythonBuildStatusOutput offlinepythonBuildStatus(OfflinepythonBuildStatusInput input, RpcContext context);

    @FengYuRpc(name = "offlinepythonDoctor", description = "AI tool: diagnose the host Python and pip environment for offline-build readiness.")
    @FengYuAiTool(name = "offlinepython_doctor", description = "Diagnose the host Python and pip environment for offline-build readiness.", effect = FengYuAiTool.ToolEffect.READ)
    OfflinepythonDoctorOutput offlinepythonDoctor(OfflinepythonDoctorInput input, RpcContext context);

    @FengYuRpc(name = "offlinepythonInitProject", description = "AI tool: initialize an offline-Python project in a writable directory.")
    @FengYuAiTool(name = "offlinepython_init_project", description = "Initialize an offline-Python project in a writable directory.", effect = FengYuAiTool.ToolEffect.WRITE)
    OfflinepythonInitProjectOutput offlinepythonInitProject(OfflinepythonInitProjectInput input, RpcContext context);

    @FengYuRpc(name = "offlinepythonSearchDeps", description = "AI tool: search PyPI for available wheels for a package.")
    @FengYuAiTool(name = "offlinepython_search_deps", description = "Search PyPI for available wheels for a package.", effect = FengYuAiTool.ToolEffect.EXTERNAL)
    OfflinepythonSearchDepsOutput offlinepythonSearchDeps(OfflinepythonSearchDepsInput input, RpcContext context);

    @FengYuRpc(name = "offlinepythonVerify", description = "AI tool: verify an offline-build output against its manifest.")
    @FengYuAiTool(name = "offlinepython_verify", description = "Verify an offline-build output against its manifest.", effect = FengYuAiTool.ToolEffect.READ)
    OfflinepythonVerifyOutput offlinepythonVerify(OfflinepythonVerifyInput input, RpcContext context);
    }

    @FengYuContract
    interface PackagingRpc {
    @FengYuRpc(name = "package", description = "Package a built output/ repository into a deployable bundle ZIP.")
    PackageOutput package_(PackageInput input, RpcContext context);

    @FengYuRpc(name = "pythonDetect", description = "Detect the host Python/pip interpreter, optionally from a configured executable path.")
    PythonDetectOutput pythonDetect(PythonDetectInput input, RpcContext context);

    @FengYuRpc(name = "requirementsGet", description = "Read requirements.txt from a project directory (empty string when absent).")
    RequirementsGetOutput requirementsGet(RequirementsGetInput input, RpcContext context);

    @FengYuRpc(name = "requirementsSave", description = "Write requirements.txt into a project directory.")
    RequirementsSaveOutput requirementsSave(RequirementsSaveInput input, RpcContext context);

    @FengYuRpc(name = "verify", description = "Verify an offline-build output/ repository against its manifest (SHA256, integrity, wheels, requirements).")
    VerifyOutput verify(VerifyInput input, RpcContext context);
    }

    public record BuildCancelInput(
        @FengYuField(required = true)
        String jobId
    ) {}

    public record BuildCancelOutput(
        @FengYuField(nullable = true)
        String jobId,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record BuildStartInput(
        @FengYuField(description = "Python executable; null = auto-detect.", nullable = true)
        String executable,
        @FengYuField(required = true)
        String projectDir,
        @FengYuField(nullable = true)
        String session
    ) {}

    public record BuildStartOutput(
        @FengYuField(description = "Job identifier for status polling.", nullable = true)
        String jobId,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record BuildStatusInput(
        @FengYuField(description = "Absolute log cursor from a prior snapshot; 0 = start.", minimum = 0)
        Integer cursor,
        @FengYuField(required = true)
        String jobId
    ) {}

    public record BuildStatusOutput(
        Integer cursor,
        Boolean done,
        Integer droppedLogs,
        Integer elapsedMs,
        @FengYuField(nullable = true)
        String error,
        String jobId,
        List<String> logs,
        @FengYuField(description = "BuildSummary totals (present once the build completes).", nullable = true)
        BuildStatusOutputResult result,
        String status,
        boolean success,
        @FengYuField(required = true)
        String summary,
        @FengYuField(description = "Job type label (BUILD).", nullable = true)
        String type
    ) {
      public record BuildStatusOutputResult(
          Integer cacheHits,
          Integer durationMs,
          Integer totalBytes,
          Integer totalWheels
      ) {}
    }

    public record ConfigGetInput(
        @FengYuField(description = "Project directory to bind the session to.", nullable = true)
        String projectDir,
        @FengYuField(description = "UI session key; defaults to the shared AI session when absent.", nullable = true)
        String session
    ) {}

    public record ConfigGetOutput(
        @FengYuField(description = "The full worker BuildConfig (depPlatforms is preserved on disk by config.save and is not surfaced here).")
        ConfigGetOutputConfig config,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record ConfigGetOutputConfig(
          ConfigGetOutputConfigBundle bundle,
          ConfigGetOutputConfigDownload download,
          ConfigGetOutputConfigPkg pkg,
          ConfigGetOutputConfigPython python,
          ConfigGetOutputConfigRepository repository
      ) {
        public record ConfigGetOutputConfigBundle(
            Boolean autoPackage,
            String name,
            Boolean sha256
        ) {}
      
        public record ConfigGetOutputConfigDownload(
            String mirror,
            Boolean onlyBinary,
            Boolean recursive,
            Boolean upgradePip
        ) {}
      
        public record ConfigGetOutputConfigPkg(
            Boolean readme,
            Boolean sha256,
            Boolean zip
        ) {}
      
        public record ConfigGetOutputConfigPython(
            @FengYuField(description = "Python executable path; null = auto-detect.", nullable = true)
            String executable,
            String implementation,
            Boolean installer,
            List<String> platforms,
            String version
        ) {}
      
        public record ConfigGetOutputConfigRepository(
            Boolean cache,
            String output,
            String wheelDir
        ) {}
      }
    }

    public record ConfigSaveInput(
        @FengYuField(description = "Full or partial BuildConfig; merged onto the on-disk config so omitted sections keep their values.")
        ConfigSaveInputConfig config,
        @FengYuField(description = "Project directory to save into.", required = true)
        String projectDir,
        @FengYuField(nullable = true)
        String session
    ) {
      public record ConfigSaveInputConfig(
          ConfigSaveInputConfigBundle bundle,
          ConfigSaveInputConfigDownload download,
          ConfigSaveInputConfigPkg pkg,
          ConfigSaveInputConfigPython python,
          ConfigSaveInputConfigRepository repository
      ) {
        public record ConfigSaveInputConfigBundle(
            Boolean autoPackage,
            String name,
            Boolean sha256
        ) {}
      
        public record ConfigSaveInputConfigDownload(
            String mirror,
            Boolean onlyBinary,
            Boolean recursive,
            Boolean upgradePip
        ) {}
      
        public record ConfigSaveInputConfigPkg(
            Boolean readme,
            Boolean sha256,
            Boolean zip
        ) {}
      
        public record ConfigSaveInputConfigPython(
            @FengYuField(nullable = true)
            String executable,
            String implementation,
            Boolean installer,
            List<String> platforms,
            String version
        ) {}
      
        public record ConfigSaveInputConfigRepository(
            Boolean cache,
            String output,
            String wheelDir
        ) {}
      }
    }

    public record ConfigSaveOutput(
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record DeployCancelInput(
        @FengYuField(required = true)
        String jobId
    ) {}

    public record DeployCancelOutput(
        @FengYuField(nullable = true)
        String jobId,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record DeployStartInput(
        @FengYuField(description = "Install target: global site-packages or a new virtualenv.", required = true)
        DeployStartInputTarget target,
        @FengYuField(description = "Bundle ZIP path.", required = true)
        String zipPath
    ) {
      public record DeployStartInputTarget(
          @FengYuField(nullable = true)
          DeployStartInputTargetKind kind,
          @FengYuField(description = "Target interpreter path.", required = true)
          String pythonExe,
          @FengYuField(description = "venv target only: directory to create.", nullable = true)
          String venvPath
      ) {
        public enum DeployStartInputTargetKind {
          global,
          venv
        }
      }
    }

    public record DeployStartOutput(
        @FengYuField(nullable = true)
        String jobId,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record DeployStatusInput(
        @FengYuField(minimum = 0)
        Integer cursor,
        @FengYuField(required = true)
        String jobId
    ) {}

    public record DeployStatusOutput(
        Integer cursor,
        Boolean done,
        Integer droppedLogs,
        Integer elapsedMs,
        @FengYuField(nullable = true)
        String error,
        String jobId,
        List<String> logs,
        @FengYuField(description = "DeployResult totals (present once the deploy completes).", nullable = true)
        DeployStatusOutputResult result,
        String status,
        boolean success,
        @FengYuField(required = true)
        String summary,
        @FengYuField(nullable = true)
        String type
    ) {
      public record DeployStatusOutputResult(
          Integer durationMs,
          Integer failed,
          Integer installed,
          Integer skipped
      ) {}
    }

    public record DepsLatestInput(
        @FengYuField(description = "Python executable; null = auto-detect.", nullable = true)
        String executable,
        @FengYuField(description = "Package name.", required = true)
        String pkg
    ) {}

    public record DepsLatestOutput(
        boolean success,
        @FengYuField(required = true)
        String summary,
        @FengYuField(description = "Latest version, or null when none found.", nullable = true)
        String version
    ) {}

    public record DepsSearchInput(
        @FengYuField(description = "Package name.", required = true)
        String pkg
    ) {}

    public record DepsSearchOutput(
        boolean success,
        @FengYuField(required = true)
        String summary,
        List<DepsSearchOutputWheels> wheels
    ) {
      public record DepsSearchOutputWheels(
          String filename,
          String platformTag,
          Integer sizeBytes,
          String version
      ) {}
    }

    public record DoctorInput(
        @FengYuField(description = "Python executable; null = auto-detect.", nullable = true)
        String executable
    ) {}

    public record DoctorOutput(
        List<DoctorOutputChecks> checks,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record DoctorOutputChecks(
          String id,
          Boolean ok,
          @FengYuField(nullable = true)
          String value
      ) {}
    }

    public record InitInput(
        @FengYuField(description = "Absolute path of the writable project directory (host resolves the FileRef to a path).", required = true)
        String projectDir,
        @FengYuField(description = "Optional UI session key; absent for AI tools.", nullable = true)
        String session
    ) {}

    public record InitOutput(
        @FengYuField(description = "The initialized project directory.", nullable = true)
        String projectDir,
        @FengYuField(description = "true when the project skeleton was initialized.")
        boolean success,
        @FengYuField(description = "Short localized result summary.", required = true)
        String summary
    ) {}

    public record OfflinepythonBuildStartInput(
        @FengYuField(description = "Python executable; null = auto-detect.", nullable = true)
        String executable,
        @FengYuField(required = true)
        String projectDir
    ) {}

    public record OfflinepythonBuildStartOutput(
        @FengYuField(nullable = true)
        String jobId,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record OfflinepythonBuildStatusInput(
        @FengYuField(minimum = 0)
        Integer cursor,
        @FengYuField(required = true)
        String jobId
    ) {}

    public record OfflinepythonBuildStatusOutput(
        Integer cursor,
        Boolean done,
        Integer droppedLogs,
        Integer elapsedMs,
        @FengYuField(nullable = true)
        String error,
        String jobId,
        List<String> logs,
        @FengYuField(nullable = true)
        OfflinepythonBuildStatusOutputResult result,
        String status,
        boolean success,
        @FengYuField(required = true)
        String summary,
        @FengYuField(nullable = true)
        String type
    ) {
      public record OfflinepythonBuildStatusOutputResult(
          Integer cacheHits,
          Integer durationMs,
          Integer totalBytes,
          Integer totalWheels
      ) {}
    }

    public record OfflinepythonDoctorInput(
        @FengYuField(description = "Python executable; null = auto-detect.", nullable = true)
        String executable
    ) {}

    public record OfflinepythonDoctorOutput(
        List<OfflinepythonDoctorOutputChecks> checks,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record OfflinepythonDoctorOutputChecks(
          String id,
          Boolean ok,
          @FengYuField(nullable = true)
          String value
      ) {}
    }

    public record OfflinepythonInitProjectInput(
        @FengYuField(description = "Absolute path of the writable project directory.", required = true)
        String projectDir
    ) {}

    public record OfflinepythonInitProjectOutput(
        @FengYuField(nullable = true)
        String projectDir,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record OfflinepythonSearchDepsInput(
        @FengYuField(required = true)
        String pkg
    ) {}

    public record OfflinepythonSearchDepsOutput(
        boolean success,
        @FengYuField(required = true)
        String summary,
        List<OfflinepythonSearchDepsOutputWheels> wheels
    ) {
      public record OfflinepythonSearchDepsOutputWheels(
          String filename,
          String platformTag,
          Integer sizeBytes,
          String version
      ) {}
    }

    public record OfflinepythonVerifyInput(
        @FengYuField(required = true)
        String projectDir,
        @FengYuField(nullable = true)
        OfflinepythonVerifyInputScope scope
    ) {
      public enum OfflinepythonVerifyInputScope {
        ALL,
        INTEGRITY,
        SHA256
      }
    }

    public record OfflinepythonVerifyOutput(
        @FengYuField(description = "Per-check outcomes; individual checks are null when out of scope.")
        OfflinepythonVerifyOutputResult result,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record OfflinepythonVerifyOutputResult(
          @FengYuField(nullable = true)
          OfflinepythonVerifyOutputResultFileIntegrity fileIntegrity,
          @FengYuField(nullable = true)
          OfflinepythonVerifyOutputResultManifest manifest,
          @FengYuField(nullable = true)
          OfflinepythonVerifyOutputResultRequirements requirements,
          @FengYuField(nullable = true)
          OfflinepythonVerifyOutputResultSha256 sha256,
          @FengYuField(nullable = true)
          OfflinepythonVerifyOutputResultWheels wheels
      ) {
        public record OfflinepythonVerifyOutputResultFileIntegrity(
            @FengYuField(nullable = true)
            String detail,
            List<String> problems,
            @FengYuField(required = true)
            OfflinepythonVerifyOutputResultFileIntegrityStatus status
        ) {
          public enum OfflinepythonVerifyOutputResultFileIntegrityStatus {
            PASS,
            WARN,
            FAIL
          }
        }
      
        public record OfflinepythonVerifyOutputResultManifest(
            @FengYuField(nullable = true)
            String detail,
            List<String> problems,
            @FengYuField(required = true)
            OfflinepythonVerifyOutputResultManifestStatus status
        ) {
          public enum OfflinepythonVerifyOutputResultManifestStatus {
            PASS,
            WARN,
            FAIL
          }
        }
      
        public record OfflinepythonVerifyOutputResultRequirements(
            @FengYuField(nullable = true)
            String detail,
            List<String> problems,
            @FengYuField(required = true)
            OfflinepythonVerifyOutputResultRequirementsStatus status
        ) {
          public enum OfflinepythonVerifyOutputResultRequirementsStatus {
            PASS,
            WARN,
            FAIL
          }
        }
      
        public record OfflinepythonVerifyOutputResultSha256(
            @FengYuField(nullable = true)
            String detail,
            List<String> problems,
            @FengYuField(required = true)
            OfflinepythonVerifyOutputResultSha256Status status
        ) {
          public enum OfflinepythonVerifyOutputResultSha256Status {
            PASS,
            WARN,
            FAIL
          }
        }
      
        public record OfflinepythonVerifyOutputResultWheels(
            @FengYuField(nullable = true)
            String detail,
            List<String> problems,
            @FengYuField(required = true)
            OfflinepythonVerifyOutputResultWheelsStatus status
        ) {
          public enum OfflinepythonVerifyOutputResultWheelsStatus {
            PASS,
            WARN,
            FAIL
          }
        }
      }
    }

    public record PackageInput(
        @FengYuField(required = true)
        String projectDir,
        @FengYuField(nullable = true)
        String session
    ) {}

    public record PackageOutput(
        boolean success,
        @FengYuField(required = true)
        String summary,
        @FengYuField(description = "The produced bundle ZIP path.", nullable = true)
        String zipPath
    ) {}

    public record PythonDetectInput(
        @FengYuField(description = "Python executable path; null/empty = auto-detect on PATH.", nullable = true)
        String executable
    ) {}

    public record PythonDetectOutput(
        PythonDetectOutputDetection detection,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record PythonDetectOutputDetection(
          @FengYuField(nullable = true)
          String executable,
          Boolean ok,
          @FengYuField(nullable = true)
          String pipVersion,
          @FengYuField(nullable = true)
          String pythonVersion
      ) {}
    }

    public record RequirementsGetInput(
        @FengYuField(description = "Project directory.", required = true)
        String projectDir
    ) {}

    public record RequirementsGetOutput(
        boolean success,
        @FengYuField(required = true)
        String summary,
        @FengYuField(description = "The requirements.txt contents.", nullable = true)
        String text
    ) {}

    public record RequirementsSaveInput(
        @FengYuField(required = true)
        String projectDir,
        @FengYuField(description = "The requirements.txt contents; null/empty clears the file.", nullable = true)
        String text
    ) {}

    public record RequirementsSaveOutput(
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record VerifyInput(
        @FengYuField(required = true)
        String projectDir,
        @FengYuField(description = "Verification scope; defaults to ALL.", nullable = true)
        VerifyInputScope scope,
        @FengYuField(nullable = true)
        String session
    ) {
      public enum VerifyInputScope {
        ALL,
        INTEGRITY,
        SHA256
      }
    }

    public record VerifyOutput(
        @FengYuField(description = "Per-check outcomes; individual checks are null when out of scope.")
        VerifyOutputResult result,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record VerifyOutputResult(
          @FengYuField(nullable = true)
          VerifyOutputResultFileIntegrity fileIntegrity,
          @FengYuField(nullable = true)
          VerifyOutputResultManifest manifest,
          @FengYuField(nullable = true)
          VerifyOutputResultRequirements requirements,
          @FengYuField(nullable = true)
          VerifyOutputResultSha256 sha256,
          @FengYuField(nullable = true)
          VerifyOutputResultWheels wheels
      ) {
        public record VerifyOutputResultFileIntegrity(
            @FengYuField(nullable = true)
            String detail,
            List<String> problems,
            @FengYuField(required = true)
            VerifyOutputResultFileIntegrityStatus status
        ) {
          public enum VerifyOutputResultFileIntegrityStatus {
            PASS,
            WARN,
            FAIL
          }
        }
      
        public record VerifyOutputResultManifest(
            @FengYuField(nullable = true)
            String detail,
            List<String> problems,
            @FengYuField(required = true)
            VerifyOutputResultManifestStatus status
        ) {
          public enum VerifyOutputResultManifestStatus {
            PASS,
            WARN,
            FAIL
          }
        }
      
        public record VerifyOutputResultRequirements(
            @FengYuField(nullable = true)
            String detail,
            List<String> problems,
            @FengYuField(required = true)
            VerifyOutputResultRequirementsStatus status
        ) {
          public enum VerifyOutputResultRequirementsStatus {
            PASS,
            WARN,
            FAIL
          }
        }
      
        public record VerifyOutputResultSha256(
            @FengYuField(nullable = true)
            String detail,
            List<String> problems,
            @FengYuField(required = true)
            VerifyOutputResultSha256Status status
        ) {
          public enum VerifyOutputResultSha256Status {
            PASS,
            WARN,
            FAIL
          }
        }
      
        public record VerifyOutputResultWheels(
            @FengYuField(nullable = true)
            String detail,
            List<String> problems,
            @FengYuField(required = true)
            VerifyOutputResultWheelsStatus status
        ) {
          public enum VerifyOutputResultWheelsStatus {
            PASS,
            WARN,
            FAIL
          }
        }
      }
    }

}
