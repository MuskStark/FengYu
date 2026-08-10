package fan.summer.fengyu.plugin.offlinepython;

import fan.summer.fengyu.plugin.offlinepython.command.BuildService;
import fan.summer.fengyu.plugin.offlinepython.command.DeployService;
import fan.summer.fengyu.plugin.offlinepython.command.DepsService;
import fan.summer.fengyu.plugin.offlinepython.command.DoctorService;
import fan.summer.fengyu.plugin.offlinepython.command.InitService;
import fan.summer.fengyu.plugin.offlinepython.command.PackageService;
import fan.summer.fengyu.plugin.offlinepython.command.VerifyService;
import fan.summer.fengyu.plugin.offlinepython.domain.BuildConfig;
import fan.summer.fengyu.plugin.offlinepython.domain.BundleReader;
import fan.summer.fengyu.plugin.offlinepython.domain.DeployTarget;
import fan.summer.fengyu.plugin.offlinepython.domain.Manifest;
import fan.summer.fengyu.plugin.offlinepython.domain.VerifyScope;
import fan.summer.fengyu.plugin.offlinepython.infra.JsonRpcParams;
import fan.summer.fengyu.plugin.offlinepython.infra.JsonStore;
import fan.summer.fengyu.plugin.offlinepython.infra.ProcessRunner;
import fan.summer.fengyu.plugin.offlinepython.infra.PythonDetector;
import fan.summer.fengyu.sdk.Jobs;
import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.PluginHandlerSupport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapts the Offline Python Builder services to official-SDK JSON-RPC handlers.
 *
 * <p>Method families:
 * <ul>
 *   <li><b>UI-facing</b> ({@code init}, {@code config.*}, {@code requirements.*}, {@code python.detect},
 *       {@code deps.*}, {@code verify}, {@code package}, {@code doctor}, {@code build.start/status/cancel},
 *       {@code deploy.start/status/cancel}) — session-keyed for the Vue micro-frontend.</li>
 *   <li><b>AI-facing</b> ({@code offlinepython_*}) — stateless tools declared in {@code manifest.json},
 *       all operating on the shared {@link OfflinePythonSessionStore#AI_SESSION} so a model can chain them.</li>
 * </ul>
 *
 * <p>Every result follows the {@code {success, summary, ...}} contract; exceptions become
 * {@code {success:false, summary}} via the inherited {@link PluginHandlerSupport#handle(String, fan.summer.fengyu.sdk.PluginHandler)}.
 */
public final class OfflinePythonRpcHandlers extends PluginHandlerSupport {

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

    // ---- UI-facing: project lifecycle --------------------------------------

    public Object init(Map<String, Object> params) {
        return result(() -> {
            Path projectDir = requiredPath(params, "projectDir");
            String session = JsonRpcParams.string(params, "session");
            initService.initialize(projectDir);
            if (session != null) sessions.bind(session, projectDir);
            return ok(t("opb.msg.init.ok", projectDir), "projectDir", projectDir.toString());
        });
    }

    public Object configGet(Map<String, Object> params) {
        return result(() -> {
            String session = JsonRpcParams.string(params, "session");
            Path projectDir = optionalPath(params, "projectDir");
            BuildConfig cfg = (projectDir != null && session != null)
                    ? sessions.bind(session, projectDir)
                    : sessions.get(session != null ? session : OfflinePythonSessionStore.AI_SESSION);
            return ok(t("opb.msg.config.loaded"), "config", JsonRpcParams.toMap(cfg));
        });
    }

    public Object configSave(Map<String, Object> params) {
        return result(() -> {
            String session = JsonRpcParams.string(params, "session");
            Path projectDir = requiredPath(params, "projectDir");
            Object cfgObj = params.get("config");
            if (!(cfgObj instanceof Map<?, ?> raw)) return failKey("opb.msg.config.required");

            // Merge the incoming config onto the on-disk config (or defaults) so
            // sections the caller omitted (e.g. repository/pkg/bundle when a UI
            // or AI tool sends only python+download) retain their existing values
            // instead of being reset to Java field defaults. Without this, every
            // partial save silently clobbered repository/pkg/bundle.
            JsonObject incoming = JsonStore.toJsonTree(raw);
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
        });
    }

    public Object requirementsGet(Map<String, Object> params) {
        return result(() -> {
            Path projectDir = requiredPath(params, "projectDir");
            Path req = projectDir.resolve("requirements.txt");
            String text = Files.exists(req) ? Files.readString(req) : "";
            return ok(t("opb.msg.requirements.read"), "text", text);
        });
    }

    public Object requirementsSave(Map<String, Object> params) {
        return result(() -> {
            Path projectDir = requiredPath(params, "projectDir");
            String text = JsonRpcParams.string(params, "text");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("requirements.txt"), text == null ? "" : text);
            return ok(t("opb.msg.requirements.saved"), null, null);
        });
    }

    public Object pythonDetect(Map<String, Object> params) {
        return result(() -> {
            String exe = JsonRpcParams.string(params, "executable");
            PythonDetector.Detection d = PythonDetector.detect(exe);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("executable", d.executable());
            out.put("pythonVersion", d.pythonVersion());
            out.put("pipVersion", d.pipVersion());
            out.put("ok", d.ok());
            return ok(d.ok() ? t("opb.python.detected", d.pythonVersion(), d.pipVersion())
                             : t("opb.python.missing"), "detection", out);
        });
    }

    public Object depsLatest(Map<String, Object> params) {
        return result(() -> {
            String pkg = requiredString(params, "pkg");
            String exe = JsonRpcParams.string(params, "executable");
            PythonDetector.Detection d = PythonDetector.detect(exe);
            if (!d.ok()) return failKey("opb.msg.python.notDetected");
            var v = depsService.latestVersion(pkg, d.executable());
            return ok(v.isPresent() ? t("opb.msg.deps.latest", v.get()) : t("opb.msg.deps.noVersion"), "version", v.orElse(null));
        });
    }

    public Object depsSearch(Map<String, Object> params) {
        return result(() -> {
            String pkg = requiredString(params, "pkg");
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
        });
    }

    public Object verify(Map<String, Object> params) {
        return result(() -> {
            Path projectDir = requiredPath(params, "projectDir");
            BuildConfig cfg = sessions.bind(JsonRpcParams.string(params, "session"), projectDir);
            Path output = projectDir.resolve(cfg.getRepository().getOutput());
            Path manifestFile = output.resolve("manifest.json");
            if (!Files.exists(manifestFile)) return failKey("opb.msg.verify.buildFirst");
            Manifest manifest = JsonStore.load(manifestFile, Manifest.class);
            String scopeText = JsonRpcParams.string(params, "scope");
            VerifyScope scope = scopeText == null ? VerifyScope.ALL : VerifyScope.valueOf(scopeText);
            return ok(t("opb.msg.verify.ok"), "result", JsonRpcParams.toMap(verifyService.verify(output, manifest, scope)));
        });
    }

    public Object doPackage(Map<String, Object> params) {
        return result(() -> {
            Path projectDir = requiredPath(params, "projectDir");
            BuildConfig cfg = sessions.bind(JsonRpcParams.string(params, "session"), projectDir);
            Path zip = packageService.packageBundle(projectDir, cfg);
            return ok(t("opb.msg.package.ok"), "zipPath", zip.toString());
        });
    }

    public Object doctor(Map<String, Object> params) {
        return result(() -> {
            String exe = JsonRpcParams.string(params, "executable");
            List<Map<String, Object>> checks = doctorService.run(exe).stream()
                    .map(c -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", c.id());
                        m.put("value", c.value());
                        m.put("ok", c.ok());
                        return m;
                    })
                    .toList();
            return ok(t("opb.msg.doctor.count", checks.size()), "checks", checks);
        });
    }

    // ---- UI-facing: async build / deploy -----------------------------------

    public Object buildStart(Map<String, Object> params) {
        return result(() -> {
            Path projectDir = requiredPath(params, "projectDir");
            String session = JsonRpcParams.string(params, "session");
            String exe = JsonRpcParams.string(params, "executable");
            BuildConfig cfg = sessions.bind(session != null ? session : OfflinePythonSessionStore.AI_SESSION, projectDir);
            PythonDetector.Detection det = PythonDetector.detect(exe);
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
        });
    }

    public Object buildStatus(Map<String, Object> params) {
        return jobStatus(params);
    }

    public Object buildCancel(Map<String, Object> params) {
        return jobCancel(params);
    }

    public Object deployStart(Map<String, Object> params) {
        return result(() -> {
            Path zip = requiredPath(params, "zipPath");
            if (!Files.exists(zip)) return failKey("opb.msg.bundle.zipNotFound", zip);
            Object targetObj = params.get("target");
            if (!(targetObj instanceof Map<?, ?> raw)) return failKey("opb.msg.target.required");
            @SuppressWarnings("unchecked") Map<String, Object> targetMap = (Map<String, Object>) raw;
            DeployTarget target = decodeTarget(targetMap);
            Jobs.Job job = jobs.start("DEPLOY", handle -> {
                try {
                    var res = deployService.install(zip, target, handle::log);
                    if (handle.isCancelled()) throw new Jobs.CancellationException();
                    handle.setSummary(JsonRpcParams.toMap(res));
                } catch (Jobs.CancellationException ce) {
                    throw ce;
                } catch (Exception e) {
                    // Jobs.start flattens exceptions to a one-line markFailed without a stack trace;
                    // log the full stack here so the host log surface has diagnostics, then rethrow.
                    log.error("deploy job failed for {} -> {}: {}", zip, target, e.toString(), e);
                    throw e;
                }
            });
            log.info("deploy job {} started for {} -> {}", job.id, zip, target);
            return ok(t("opb.msg.deploy.started"), "jobId", job.id);
        });
    }

    public Object deployStatus(Map<String, Object> params) {
        return jobStatus(params);
    }

    public Object deployCancel(Map<String, Object> params) {
        return jobCancel(params);
    }

    // ---- AI-facing: thin wrappers on the shared "ai" session ---------------
    // Each maps to a manifest.json aiTools[] entry. They are intentionally small and
    // stateless so a model can call them in any order.

    public Object aiDoctor(Map<String, Object> params)        { return doctor(withAi(params)); }
    public Object aiSearchDeps(Map<String, Object> params)    { return depsSearch(params); }
    public Object aiInitProject(Map<String, Object> params)   { return init(withAi(params)); }
    public Object aiVerify(Map<String, Object> params)        { return verify(withAi(params)); }
    public Object aiBuildStart(Map<String, Object> params)    { return buildStart(withAi(params)); }
    public Object aiBuildStatus(Map<String, Object> params)   { return buildStatus(params); }

    /** Stamp the AI session key + reuse the model-supplied projectDir. */
    private static Map<String, Object> withAi(Map<String, Object> params) {
        Map<String, Object> copy = new LinkedHashMap<>(params);
        copy.putIfAbsent("session", OfflinePythonSessionStore.AI_SESSION);
        return copy;
    }

    // ---- shared job helpers ------------------------------------------------

    private Object jobStatus(Map<String, Object> params) {
        return result(() -> {
            String jobId = requiredString(params, "jobId");
            int cursor = JsonRpcParams.integer(params, "cursor", 0);
            Jobs.Job job = jobs.get(jobId);
            if (job == null) return failKey("opb.msg.job.unknownJobId", jobId);
            return job.snapshot(cursor);
        });
    }

    private Object jobCancel(Map<String, Object> params) {
        return result(() -> {
            String jobId = requiredString(params, "jobId");
            Jobs.Job job = jobs.get(jobId);
            if (job == null) return failKey("opb.msg.job.unknownJobId", jobId);
            if (!jobs.cancel(jobId)) return failKey("opb.msg.job.notRunning", jobId);
            return ok(t("opb.msg.cancel.ok"), "jobId", jobId);
        });
    }

    // ---- param helpers (resolve FileRef→path via host, or accept raw paths) ----

    private static String requiredString(Map<String, Object> params, String key) {
        String v = JsonRpcWorker.string(params, key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException(key + " is required");
        return v;
    }

    private static Path requiredPath(Map<String, Object> params, String key) {
        // The host resolves FileRef objects ({id:"ref_..",kind:..}) to filesystem path strings
        // before the params reach the worker, so a path arrives either as a plain string or as
        // the resolved String inside a leftover map — accept both.
        Object v = params.get(key);
        String s = toPathString(v);
        if (s == null || s.isBlank()) throw new IllegalArgumentException(key + " is required");
        return Paths.get(s.trim());
    }

    private static Path optionalPath(Map<String, Object> params, String key) {
        String s = toPathString(params.get(key));
        return (s == null || s.isBlank()) ? null : Paths.get(s.trim());
    }

    private static String toPathString(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s;
        if (v instanceof Map<?, ?> m) {
            // {"path":"..."} or resolved {"id":"ref_..","path":"..."} shapes
            Object p = m.get("path");
            if (p != null) return p.toString();
            Object id = m.get("id");
            if (id != null) return id.toString();
        }
        return v.toString();
    }

    private DeployTarget decodeTarget(Map<String, Object> target) {
        String kind = JsonRpcParams.string(target, "kind");
        String pythonExe = JsonRpcParams.string(target, "pythonExe");
        if (pythonExe == null || pythonExe.isBlank()) {
            PythonDetector.Detection d = PythonDetector.detect(null);
            if (!d.ok()) throw new IllegalArgumentException(t("opb.msg.python.notDetectedProvide"));
            pythonExe = d.executable();
        }
        if ("venv".equalsIgnoreCase(kind)) {
            String venv = JsonRpcParams.string(target, "venvPath");
            if (venv == null || venv.isBlank()) throw new IllegalArgumentException(t("opb.msg.venvPath.required"));
            return new DeployTarget.Venv(Paths.get(pythonExe.trim()), Paths.get(venv.trim()));
        }
        return new DeployTarget.Global(Paths.get(pythonExe.trim()));
    }
}
