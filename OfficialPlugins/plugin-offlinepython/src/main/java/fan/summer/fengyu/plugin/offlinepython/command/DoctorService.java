package fan.summer.fengyu.plugin.offlinepython.command;

import fan.summer.fengyu.plugin.offlinepython.infra.ProcessRunner;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Diagnoses the host environment for build readiness. */
public class DoctorService {

    public record Check(String name, String value, boolean ok) {}

    public List<Check> run(String configuredExecutable) {
        List<Check> out = new ArrayList<>();
        var d = fan.summer.fengyu.plugin.offlinepython.infra.PythonDetector.detect(configuredExecutable);
        out.add(new Check("Python 解释器", d.executable() == null ? "未找到" : d.executable(), d.executable() != null));
        out.add(new Check("Python 版本", d.pythonVersion() == null ? "—" : d.pythonVersion(),
                d.pythonVersion() != null && fan.summer.fengyu.plugin.offlinepython.infra.PythonDetector.isAtLeast(d.pythonVersion(), "3.10")));
        out.add(new Check("pip", d.pipVersion() == null ? "缺失" : d.pipVersion(), d.pipVersion() != null));
        boolean pipDownloadOk = d.executable() != null && d.pipVersion() != null
                && parsePipDownloadSupportsPlatform(
                    ProcessRunner.captureQuiet(d.executable(), "-m", "pip", "download", "--help"));
        out.add(new Check("pip download 可用", pipDownloadOk ? "支持 --platform/--python-version" : "不支持跨平台下载", pipDownloadOk));
        boolean net = pingPyPI();
        out.add(new Check("网络 (PyPI)", net ? "可达" : "不可达", net));
        long freeGb = freeSpaceGb(Path.of(System.getProperty("user.home")));
        out.add(new Check("磁盘空间", freeGb + " GB 可用", freeGb > 1));
        Path cache = Path.of(System.getProperty("user.home"), ".offline-python", "cache");
        out.add(new Check("缓存目录", cache.toString(), isWritable(cache)));
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
            return false;
        }
    }

    private boolean isWritable(Path dir) {
        try { Files.createDirectories(dir); return Files.isWritable(dir); }
        catch (Exception e) { return false; }
    }

    private long freeSpaceGb(Path p) {
        try {
            FileStore store = Files.getFileStore(p);
            return store.getUsableSpace() / (1024L * 1024 * 1024);
        } catch (Exception e) { return 0; }
    }
}
