package fan.summer.fengyu.plugin.offlinepython.command;

import fan.summer.fengyu.plugin.offlinepython.infra.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Diagnoses the host environment for build readiness. */
public class DoctorService {

    private static final Logger log = LoggerFactory.getLogger(DoctorService.class);

    /**
     * One diagnostic row. {@code id} is a stable, locale-independent identifier the
     * UI translates via {@code t('opb.doctor.check.' + id)}; {@code value} carries
     * the raw data (version, path) or an English short label; {@code ok} is the
     * pass/fail flag. The worker never returns localized prose so the UI controls
     * all user-facing language.
     */
    public record Check(String id, String value, boolean ok) {}

    public List<Check> run(String configuredExecutable) {
        List<Check> out = new ArrayList<>();
        var d = fan.summer.fengyu.plugin.offlinepython.infra.PythonDetector.detect(configuredExecutable);
        out.add(new Check("python_interpreter", d.executable() == null ? "not_found" : d.executable(), d.executable() != null));
        out.add(new Check("python_version", d.pythonVersion() == null ? "—" : d.pythonVersion(),
                d.pythonVersion() != null && fan.summer.fengyu.plugin.offlinepython.infra.PythonDetector.isAtLeast(d.pythonVersion(), "3.10")));
        out.add(new Check("pip", d.pipVersion() == null ? "missing" : d.pipVersion(), d.pipVersion() != null));
        boolean pipDownloadOk = d.executable() != null && d.pipVersion() != null
                && parsePipDownloadSupportsPlatform(
                    ProcessRunner.captureQuiet(d.executable(), "-m", "pip", "download", "--help"));
        out.add(new Check("pip_download", pipDownloadOk ? "supported" : "unsupported", pipDownloadOk));
        boolean net = pingPyPI();
        out.add(new Check("network", net ? "reachable" : "unreachable", net));
        long freeGb = freeSpaceGb(Path.of(System.getProperty("user.home")));
        out.add(new Check("disk_space", freeGb + " GB", freeGb > 1));
        Path cache = Path.of(System.getProperty("user.home"), ".offline-python", "cache");
        out.add(new Check("cache_dir", cache.toString(), isWritable(cache)));
        return out;
    }

    /** True if `pip download --help` mentions --platform (cross-platform download support). */
    public static boolean parsePipDownloadSupportsPlatform(String helpOutput) {
        return helpOutput != null && helpOutput.contains("--platform");
    }

    private boolean pingPyPI() {
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://pypi.org/simple/"))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody()).build();
            java.net.http.HttpResponse<Void> r = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5)).build()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
            return r.statusCode() >= 200 && r.statusCode() < 500;
        } catch (Exception e) {
            log.warn("PyPI reachability probe failed: {}", e.toString());
            return false;
        }
    }

    private boolean isWritable(Path dir) {
        try { Files.createDirectories(dir); return Files.isWritable(dir); }
        catch (Exception e) {
            log.warn("cache dir writability probe failed for {}: {}", dir, e.toString());
            return false;
        }
    }

    private long freeSpaceGb(Path p) {
        try {
            FileStore store = Files.getFileStore(p);
            return store.getUsableSpace() / (1024L * 1024 * 1024);
        } catch (Exception e) {
            log.warn("disk space probe failed for {}: {}", p, e.toString());
            return 0;
        }
    }
}
