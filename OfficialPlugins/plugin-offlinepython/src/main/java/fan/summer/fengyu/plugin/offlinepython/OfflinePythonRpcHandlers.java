package fan.summer.fengyu.plugin.offlinepython;

import fan.summer.fengyu.plugin.offlinepython.command.BuildService;
import fan.summer.fengyu.plugin.offlinepython.command.DeployService;
import fan.summer.fengyu.plugin.offlinepython.command.DepsService;
import fan.summer.fengyu.plugin.offlinepython.command.DoctorService;
import fan.summer.fengyu.plugin.offlinepython.command.InitService;
import fan.summer.fengyu.plugin.offlinepython.command.PackageService;
import fan.summer.fengyu.plugin.offlinepython.command.VerifyService;
import fan.summer.fengyu.plugin.offlinepython.domain.BuildConfig;
import fan.summer.fengyu.plugin.offlinepython.domain.DeployTarget;
import fan.summer.fengyu.plugin.offlinepython.domain.Manifest;
import fan.summer.fengyu.plugin.offlinepython.domain.VerifyScope;
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
import fan.summer.offlinepython.generated.PythonDetectInput;
import fan.summer.offlinepython.generated.PythonDetectOutput;
import fan.summer.offlinepython.generated.RequirementsGetInput;
import fan.summer.offlinepython.generated.RequirementsGetOutput;
import fan.summer.offlinepython.generated.RequirementsSaveInput;
import fan.summer.offlinepython.generated.RequirementsSaveOutput;
import fan.summer.offlinepython.generated.VerifyInput;
import fan.summer.offlinepython.generated.VerifyOutput;
import fan.summer.fengyu.plugin.offlinepython.infra.JsonRpcParams;
import fan.summer.fengyu.plugin.offlinepython.infra.JsonStore;
import fan.summer.fengyu.plugin.offlinepython.infra.ProcessRunner;
import fan.summer.fengyu.plugin.offlinepython.infra.PythonDetector;
import fan.summer.fengyu.sdk.Jobs;
import fan.summer.fengyu.sdk.PluginHandlerSupport;
import fan.summer.fengyu.sdk.RpcContext;
import fan.summer.fengyu.sdk.RpcException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed RPC handlers for the Offline Python Builder worker.
 *
 * <p>Every method is registered through the typed {@code worker.method(PluginMethods.X,
 * XInput.class, XOutput.class, (input, ctx) -> handlers.x(input, ctx))} API: the SDK deserializes
 * the JSON-RPC params into the generated {@code XInput} record, binds an {@link RpcContext}, and
 * serializes the returned {@code XOutput} back into the response. The {@code {success, summary,
 * ...}} envelope is expressed as fields of the generated Output records: the shared body methods
 * build the payload as a {@code Map} (reusing the {@link PluginHandlerSupport} envelope helpers)
 * and {@link #envelope(Class, Map)} round-trips it through Gson into the typed record, so nested
 * objects/arrays map onto the generated nested records and unknown keys (e.g. the
 * generator-inexpressible {@code depPlatforms}) are dropped without data loss.
 *
 * <p><b>Cancellation.</b> Two distinct kinds, never conflated:
 * <ul>
 *   <li><b>Transport cancel</b> — a {@code $/cancelRequest} on the CURRENT waiting RPC. The
 *       {@link #run(Class, RpcContext, Body)} wrapper cooperative-checks
 *       {@link RpcContext#cancellation()} at entry and re-throws the SDK's CANCELLED
 *       {@link RpcException} so it surfaces as a clean CANCELLED response (never a crash).</li>
 *   <li><b>Domain cancel</b> — {@code buildCancel}/{@code deployCancel} cancel a STARTED job by id.
 *       The job's {@code handle.onCancel(runner::cancel)} reaps the whole pip subprocess tree via
 *       {@link ProcessRunner#cancel()} (process + descendants), so no orphan python/pip/temp-download
 *       processes survive.</li>
 * </ul>
 *
 * <p>Method families:
 * <ul>
 *   <li><b>UI-facing</b> ({@code init}, {@code configGet/configSave}, {@code requirementsGet/Save},
 *       {@code pythonDetect}, {@code depsLatest/Search}, {@code verify}, {@code package},
 *       {@code doctor}, {@code buildStart/Status/Cancel}, {@code deployStart/Status/Cancel}) —
 *       session-keyed for the Vue micro-frontend.</li>
 *   <li><b>AI-facing</b> ({@code offlinepython*}) — stateless tools that stamp the shared
 *       {@link OfflinePythonSessionStore#AI_SESSION} so a model can chain them. Each delegates to
 *       the same body as its UI counterpart.</li>
 * </ul>
 */
public final class OfflinePythonRpcHandlers extends PluginHandlerSupport implements AutoCloseable {

    private final OfflinePythonSessionStore sessions;
    private final Jobs jobs;

    // Services are stateless / cheap; hold single instances.
    private final InitService initService = new InitService();
    private final BuildService buildService = new BuildService();
    private final VerifyService verifyService = new VerifyService();
    private final PackageService packageService = new PackageService();
    private final DeployService deployService = new DeployService();
    private final DepsService depsService = new DepsService();
    private final DoctorService doctorService = new DoctorService();

    public OfflinePythonRpcHandlers(OfflinePythonSessionStore sessions, Jobs jobs) {
        super("offlinepython");
        this.sessions = sessions;
        this.jobs = jobs;
    }

    @Override public void close() { jobs.close(); }

    // ---- typed envelope helpers ------------------------------------------

    @FunctionalInterface
    private interface Body<O> {
        O run() throws Exception;
    }

    /**
     * Run one typed handler body: cooperative-check cancellation, then return its typed Output.
     * A CANCELLED {@link RpcException} propagates (clean cancel response); any other throwable is
     * flattened into a {@code success:false} envelope of the right Output type.
     */
    private <O> O run(Class<O> type, RpcContext ctx, Body<O> body) {
        ctx.cancellation().throwIfCancelled();   // cooperative checkpoint for transport cancel
        try {
            return body.run();
        } catch (RpcException re) {
            throw re;                            // CANCELLED → worker maps to a clean cancel response
        } catch (Exception e) {
            log.warn("{} handler failed: {}", pluginName, e.getClass().getSimpleName(), e);
            return failOutput(type, safeMessage(e));
        }
    }

    /** Materialize an envelope Map into the generated Output record via a Gson round-trip. */
    private <O> O envelope(Class<O> type, Map<String, Object> env) {
        return JsonStore.fromJson(JsonStore.toJson(env), type);
    }

    /** Build a success Output from a summary + payload fields. */
    private <O> O output(Class<O> type, String summary, Map<String, Object> payload) {
        Map<String, Object> env = new LinkedHashMap<>(payload);
        env.put("success", true);
        env.put("summary", summary);
        return envelope(type, env);
    }

    /** Build a failure Output (success=false) carrying only the summary. */
    private <O> O failOutput(Class<O> type, String summary) {
        return envelope(type, failure(summary));
    }

    // ---- UI-facing: project lifecycle ------------------------------------

    public InitOutput init(InitInput input, RpcContext ctx) {
        return run(InitOutput.class, ctx,
                () -> envelope(InitOutput.class, initBody(input.projectDir(), input.session())));
    }

    public ConfigGetOutput configGet(ConfigGetInput input, RpcContext ctx) {
        return run(ConfigGetOutput.class, ctx, () -> {
            Path projectDir = optionalPath(input.projectDir());
            String session = input.session();
            BuildConfig cfg = (projectDir != null && session != null)
                    ? sessions.bind(session, projectDir)
                    : sessions.get(session != null ? session : OfflinePythonSessionStore.AI_SESSION);
            return output(ConfigGetOutput.class, t("opb.msg.config.loaded"),
                    Map.of("config", JsonRpcParams.toMap(cfg)));
        });
    }

    public ConfigSaveOutput configSave(ConfigSaveInput input, RpcContext ctx) {
        return run(ConfigSaveOutput.class, ctx,
                () -> envelope(ConfigSaveOutput.class,
                        configSaveBody(input.projectDir(), input.session(), input.config())));
    }

    public RequirementsGetOutput requirementsGet(RequirementsGetInput input, RpcContext ctx) {
        return run(RequirementsGetOutput.class, ctx, () -> {
            Path projectDir = requirePath(input.projectDir(), "projectDir");
            Path req = projectDir.resolve("requirements.txt");
            String text = Files.exists(req) ? Files.readString(req) : "";
            return output(RequirementsGetOutput.class, t("opb.msg.requirements.read"), Map.of("text", text));
        });
    }

    public RequirementsSaveOutput requirementsSave(RequirementsSaveInput input, RpcContext ctx) {
        return run(RequirementsSaveOutput.class, ctx, () -> {
            Path projectDir = requirePath(input.projectDir(), "projectDir");
            String text = input.text();
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("requirements.txt"), text == null ? "" : text);
            return output(RequirementsSaveOutput.class, t("opb.msg.requirements.saved"), Map.of());
        });
    }

    public PythonDetectOutput pythonDetect(PythonDetectInput input, RpcContext ctx) {
        return run(PythonDetectOutput.class, ctx,
                () -> envelope(PythonDetectOutput.class, detectionBody(input.executable())));
    }

    public DepsLatestOutput depsLatest(DepsLatestInput input, RpcContext ctx) {
        return run(DepsLatestOutput.class, ctx, () -> {
            String pkg = requireString(input.pkg(), "pkg");
            PythonDetector.Detection d = PythonDetector.detect(input.executable());
            if (!d.ok()) return failOutput(DepsLatestOutput.class, t("opb.msg.python.notDetected"));
            var v = depsService.latestVersion(pkg, d.executable());
            return output(DepsLatestOutput.class,
                    v.isPresent() ? t("opb.msg.deps.latest", v.get()) : t("opb.msg.deps.noVersion"),
                    v.isPresent() ? Map.of("version", v.get()) : Map.of());
        });
    }

    public DepsSearchOutput depsSearch(DepsSearchInput input, RpcContext ctx) {
        return run(DepsSearchOutput.class, ctx,
                () -> envelope(DepsSearchOutput.class, searchDepsBody(input.pkg())));
    }

    public VerifyOutput verify(VerifyInput input, RpcContext ctx) {
        return run(VerifyOutput.class, ctx,
                () -> envelope(VerifyOutput.class,
                        verifyBody(input.projectDir(), input.session(),
                                input.scope() == null ? null : input.scope().name())));
    }

    public PackageOutput packageBundle(PackageInput input, RpcContext ctx) {
        return run(PackageOutput.class, ctx, () -> {
            Path projectDir = requirePath(input.projectDir(), "projectDir");
            BuildConfig cfg = sessions.bind(input.session(), projectDir);
            Path zip = packageService.packageBundle(projectDir, cfg);
            return output(PackageOutput.class, t("opb.msg.package.ok"), Map.of("zipPath", zip.toString()));
        });
    }

    public DoctorOutput doctor(DoctorInput input, RpcContext ctx) {
        return run(DoctorOutput.class, ctx,
                () -> envelope(DoctorOutput.class, doctorBody(input.executable())));
    }

    // ---- UI-facing: async build / deploy ---------------------------------

    public BuildStartOutput buildStart(BuildStartInput input, RpcContext ctx) {
        return run(BuildStartOutput.class, ctx,
                () -> envelope(BuildStartOutput.class,
                        buildStartBody(input.projectDir(), input.session(), input.executable())));
    }

    public BuildStatusOutput buildStatus(BuildStatusInput input, RpcContext ctx) {
        return run(BuildStatusOutput.class, ctx,
                () -> envelope(BuildStatusOutput.class, jobs.snapshot(input.jobId(), cursorOf(input.cursor()))));
    }

    public BuildCancelOutput buildCancel(BuildCancelInput input, RpcContext ctx) {
        return run(BuildCancelOutput.class, ctx,
                () -> envelope(BuildCancelOutput.class, jobCancelBody(input.jobId())));
    }

    public DeployStartOutput deployStart(DeployStartInput input, RpcContext ctx) {
        return run(DeployStartOutput.class, ctx,
                () -> envelope(DeployStartOutput.class, deployStartBody(input.zipPath(), input.target())));
    }

    public DeployStatusOutput deployStatus(DeployStatusInput input, RpcContext ctx) {
        return run(DeployStatusOutput.class, ctx,
                () -> envelope(DeployStatusOutput.class, jobs.snapshot(input.jobId(), cursorOf(input.cursor()))));
    }

    public DeployCancelOutput deployCancel(DeployCancelInput input, RpcContext ctx) {
        return run(DeployCancelOutput.class, ctx,
                () -> envelope(DeployCancelOutput.class, jobCancelBody(input.jobId())));
    }

    // ---- AI-facing: thin wrappers on the shared "ai" session ---------------

    public OfflinepythonDoctorOutput offlinepythonDoctor(OfflinepythonDoctorInput input, RpcContext ctx) {
        return run(OfflinepythonDoctorOutput.class, ctx,
                () -> envelope(OfflinepythonDoctorOutput.class, doctorBody(input.executable())));
    }

    public OfflinepythonSearchDepsOutput offlinepythonSearchDeps(OfflinepythonSearchDepsInput input, RpcContext ctx) {
        return run(OfflinepythonSearchDepsOutput.class, ctx,
                () -> envelope(OfflinepythonSearchDepsOutput.class, searchDepsBody(input.pkg())));
    }

    public OfflinepythonInitProjectOutput offlinepythonInitProject(OfflinepythonInitProjectInput input, RpcContext ctx) {
        return run(OfflinepythonInitProjectOutput.class, ctx,
                () -> envelope(OfflinepythonInitProjectOutput.class,
                        initBody(input.projectDir(), OfflinePythonSessionStore.AI_SESSION)));
    }

    public OfflinepythonVerifyOutput offlinepythonVerify(OfflinepythonVerifyInput input, RpcContext ctx) {
        return run(OfflinepythonVerifyOutput.class, ctx,
                () -> envelope(OfflinepythonVerifyOutput.class,
                        verifyBody(input.projectDir(), OfflinePythonSessionStore.AI_SESSION,
                                input.scope() == null ? null : input.scope().name())));
    }

    public OfflinepythonBuildStartOutput offlinepythonBuildStart(OfflinepythonBuildStartInput input, RpcContext ctx) {
        return run(OfflinepythonBuildStartOutput.class, ctx,
                () -> envelope(OfflinepythonBuildStartOutput.class,
                        buildStartBody(input.projectDir(), OfflinePythonSessionStore.AI_SESSION, input.executable())));
    }

    public OfflinepythonBuildStatusOutput offlinepythonBuildStatus(OfflinepythonBuildStatusInput input, RpcContext ctx) {
        return run(OfflinepythonBuildStatusOutput.class, ctx,
                () -> envelope(OfflinepythonBuildStatusOutput.class,
                        jobs.snapshot(input.jobId(), cursorOf(input.cursor()))));
    }

    // ---- shared bodies (UI + AI reuse the same logic) ---------------------

    private Map<String, Object> initBody(String projectDirStr, String session) throws Exception {
        Path projectDir = requirePath(projectDirStr, "projectDir");
        initService.initialize(projectDir);
        if (session != null) sessions.bind(session, projectDir);
        return ok(t("opb.msg.init.ok", projectDir), "projectDir", projectDir.toString());
    }

    private Map<String, Object> configSaveBody(String projectDirStr, String session, Object configRecord) throws Exception {
        Path projectDir = requirePath(projectDirStr, "projectDir");
        if (configRecord == null) return failKey("opb.msg.config.required");

        // Merge the incoming config onto the on-disk config (or defaults) so sections the caller
        // omitted (e.g. repository/pkg/bundle when a UI or AI tool sends only python+download)
        // retain their existing values instead of being reset to Java field defaults. The typed
        // config record has no `depPlatforms` field (the generator subset cannot express a
        // string-keyed map of string arrays); serialize-to-JSON therefore omits it, and the merge
        // preserves the on-disk depPlatforms verbatim — no data loss (the UI sends {} on save and
        // nothing in production mutates the in-memory depPlatforms).
        JsonObject incoming = JsonStore.toJsonTree(configRecord);
        Path cfgFile = projectDir.resolve("config.json");
        JsonObject base = Files.exists(cfgFile)
                ? JsonParser.parseString(Files.readString(cfgFile)).getAsJsonObject()
                : JsonStore.toJsonTree(BuildConfig.defaults());
        BuildConfig cfg = JsonStore.fromJson(
                JsonStore.mergeInto(base, incoming).toString(), BuildConfig.class);

        sessions.put(session != null ? session : OfflinePythonSessionStore.AI_SESSION, cfg);
        sessions.bind(session, projectDir);
        JsonStore.save(cfg, cfgFile);
        return ok(t("opb.msg.config.saved"), null, null);
    }

    private Map<String, Object> detectionBody(String executable) {
        PythonDetector.Detection d = PythonDetector.detect(executable);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executable", d.executable());
        out.put("pythonVersion", d.pythonVersion());
        out.put("pipVersion", d.pipVersion());
        out.put("ok", d.ok());
        return ok(d.ok() ? t("opb.python.detected", d.pythonVersion(), d.pipVersion())
                         : t("opb.python.missing"), "detection", out);
    }

    private Map<String, Object> doctorBody(String executable) {
        List<Map<String, Object>> checks = doctorService.run(executable).stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.id());
                    m.put("value", c.value());
                    m.put("ok", c.ok());
                    return m;
                })
                .toList();
        return ok(t("opb.msg.doctor.count", checks.size()), "checks", checks);
    }

    private Map<String, Object> searchDepsBody(String pkg) {
        requireString(pkg, "pkg");
        List<Map<String, Object>> wheels = depsService.searchWheels(pkg).stream()
                .map(w -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("version", w.version());
                    m.put("platformTag", w.platformTag());
                    m.put("sizeBytes", w.sizeBytes());
                    m.put("filename", w.filename());
                    return m;
                })
                .toList();
        return ok(t("opb.msg.deps.wheels", wheels.size()), "wheels", wheels);
    }

    private Map<String, Object> verifyBody(String projectDirStr, String session, String scopeText) throws Exception {
        Path projectDir = requirePath(projectDirStr, "projectDir");
        BuildConfig cfg = sessions.bind(session, projectDir);
        Path output = projectDir.resolve(cfg.getRepository().getOutput());
        Path manifestFile = output.resolve("manifest.json");
        if (!Files.exists(manifestFile)) return failKey("opb.msg.verify.buildFirst");
        Manifest manifest = JsonStore.load(manifestFile, Manifest.class);
        VerifyScope scope = scopeText == null ? VerifyScope.ALL : VerifyScope.valueOf(scopeText);
        return ok(t("opb.msg.verify.ok"), "result", JsonRpcParams.toMap(verifyService.verify(output, manifest, scope)));
    }

    private Map<String, Object> buildStartBody(String projectDirStr, String session, String executable) throws Exception {
        Path projectDir = requirePath(projectDirStr, "projectDir");
        BuildConfig cfg = sessions.bind(session != null ? session : OfflinePythonSessionStore.AI_SESSION, projectDir);
        PythonDetector.Detection det = PythonDetector.detect(executable);
        if (!det.ok()) return failKey("opb.msg.python.notDetected");
        String pythonExe = det.executable();
        Jobs.Job job = jobs.start("BUILD", handle -> {
            ProcessRunner runner = new ProcessRunner();
            handle.onCancel(runner::cancel);
            try {
                var summary = buildService.build(projectDir, cfg, pythonExe, handle::log, runner);
                if (handle.isCancelled()) throw new Jobs.CancellationException();
                handle.setSummary(JsonRpcParams.toMap(summary));
            } catch (Jobs.CancellationException ce) {
                throw ce;
            } catch (Exception e) {
                // Jobs.start flattens exceptions to a one-line markFailed without a stack trace;
                // log the full stack here so the host log surface has diagnostics, then rethrow.
                log.error("build job failed for {} with python {}: {}", projectDir, pythonExe, e.toString(), e);
                throw e;
            }
        });
        log.info("build job {} started for {} with python {}", job.id, projectDir, pythonExe);
        return ok(t("opb.msg.build.started"), "jobId", job.id);
    }

    private Map<String, Object> deployStartBody(String zipStr, DeployStartInput.DeployStartInputTarget target) throws Exception {
        Path zip = requirePath(zipStr, "zipPath");
        if (!Files.exists(zip)) return failKey("opb.msg.bundle.zipNotFound", zip);
        if (target == null) return failKey("opb.msg.target.required");
        DeployTarget deployTarget = decodeTarget(target);
        Jobs.Job job = jobs.start("DEPLOY", handle -> {
            try {
                var res = deployService.install(zip, deployTarget, handle::log);
                if (handle.isCancelled()) throw new Jobs.CancellationException();
                handle.setSummary(JsonRpcParams.toMap(res));
            } catch (Jobs.CancellationException ce) {
                throw ce;
            } catch (Exception e) {
                // Jobs.start flattens exceptions to a one-line markFailed without a stack trace;
                // log the full stack here so the host log surface has diagnostics, then rethrow.
                log.error("deploy job failed for {} -> {}: {}", zip, deployTarget, e.toString(), e);
                throw e;
            }
        });
        log.info("deploy job {} started for {} -> {}", job.id, zip, deployTarget);
        return ok(t("opb.msg.deploy.started"), "jobId", job.id);
    }

    private Map<String, Object> jobCancelBody(String jobId) {
        Jobs.Job job = jobs.get(jobId);
        if (job == null) return failKey("opb.msg.job.unknownJobId", jobId);
        if (!jobs.cancel(jobId)) return failKey("opb.msg.job.notRunning", jobId);
        return ok(t("opb.msg.cancel.ok"), "jobId", jobId);
    }

    // ---- param / target helpers ------------------------------------------

    private static int cursorOf(Integer cursor) {
        return cursor == null ? 0 : Math.max(0, cursor);
    }

    private static String requireString(String v, String name) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(name + " is required");
        return v;
    }

    private static Path requirePath(String v, String name) {
        // The host's FileRef resolver replaces a granted FileRef with the absolute path string
        // before the params reach the worker, so projectDir/zipPath always arrive as plain strings.
        if (v == null || v.isBlank()) throw new IllegalArgumentException(name + " is required");
        return Paths.get(v.trim());
    }

    private static Path optionalPath(String v) {
        return (v == null || v.isBlank()) ? null : Paths.get(v.trim());
    }

    private DeployTarget decodeTarget(DeployStartInput.DeployStartInputTarget target) {
        String pythonExe = target.pythonExe();
        if (pythonExe == null || pythonExe.isBlank()) {
            PythonDetector.Detection d = PythonDetector.detect(null);
            if (!d.ok()) throw new IllegalArgumentException(t("opb.msg.python.notDetectedProvide"));
            pythonExe = d.executable();
        }
        if (target.kind() != null && "venv".equalsIgnoreCase(target.kind().name())) {
            if (target.venvPath() == null || target.venvPath().isBlank())
                throw new IllegalArgumentException(t("opb.msg.venvPath.required"));
            return new DeployTarget.Venv(Paths.get(pythonExe.trim()), Paths.get(target.venvPath().trim()));
        }
        return new DeployTarget.Global(Paths.get(pythonExe.trim()));
    }
}
