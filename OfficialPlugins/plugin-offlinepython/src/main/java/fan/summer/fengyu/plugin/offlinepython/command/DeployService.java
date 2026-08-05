package fan.summer.fengyu.plugin.offlinepython.command;

import fan.summer.fengyu.plugin.offlinepython.domain.BundleReader;
import fan.summer.fengyu.plugin.offlinepython.domain.DeployResult;
import fan.summer.fengyu.plugin.offlinepython.domain.DeployTarget;
import fan.summer.fengyu.plugin.offlinepython.domain.PlatformMatcher;
import fan.summer.fengyu.plugin.offlinepython.domain.WheelEntry;
import fan.summer.fengyu.plugin.offlinepython.infra.ProcessRunner;
import fan.summer.fengyu.plugin.offlinepython.infra.PythonDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 部署执行核心:解压 bundle ZIP → 按本机平台筛选 wheel → 逐包 pip install。
 * 不依赖 JavaFX;ProcessRunner 注入便于测试。
 */
public class DeployService {

    private static final Logger log = LoggerFactory.getLogger(DeployService.class);

    private final ProcessRunner runner;

    public DeployService() { this(new ProcessRunner()); }
    public DeployService(ProcessRunner runner) { this.runner = runner; }

    /**
     * 把 ZIP 中适配本机的 wheel 装到目标环境(全局或新建 venv)。
     *
     * @param zip      bundle ZIP 路径
     * @param target   安装目标
     * @param onLog    日志回调(每行一条)
     * @return 安装结果汇总
     */
    public DeployResult install(Path zip, DeployTarget target, Consumer<String> onLog) throws Exception {
        long start = System.currentTimeMillis();

        // 1. 解压 wheels 到临时目录
        Path tmpDir = Files.createTempDirectory("opb-deploy");
        Path wheelsDir = tmpDir.resolve("wheels");
        Files.createDirectories(wheelsDir);
        try {
            extractWheels(zip, wheelsDir, onLog);

            // 2. (仅 venv)创建虚拟环境
            Path pythonExe = resolvePython(target, onLog);

            // 3. 读 manifest + 筛选适配本机的 wheel
            BundleReader.Bundle bundle = BundleReader.read(zip);
            PythonDetector.Detection det = PythonDetector.detect(null);
            PlatformMatcher.HostTags host = PlatformMatcher.detectHost(det.pythonVersion());
            List<WheelEntry> matched = PlatformMatcher.match(host, bundle.wheels());
            onLog.accept("适配本机的 wheel:" + matched.size() + " / " + bundle.wheels().size());

            // 4. 逐包安装
            int installed = 0, failed = 0, skipped = 0;
            for (WheelEntry w : matched) {
                String whlFile = Path.of(w.getFile()).getFileName().toString();
                Path whlPath = wheelsDir.resolve(whlFile);
                if (!Files.exists(whlPath)) {
                    onLog.accept("跳过(ZIP 内缺失文件):" + whlFile);
                    skipped++;
                    continue;
                }
                List<String> cmd = List.of(
                        pythonExe.toString(), "-m", "pip", "install",
                        "--no-index", "--no-deps",
                        "--find-links", wheelsDir.toString(),
                        whlFile);
                onLog.accept("$ " + String.join(" ", cmd));
                try {
                    int code = runner.run(cmd, onLog);
                    if (code == 0) {
                        installed++;
                        onLog.accept("✓ " + w.getName());
                    } else {
                        failed++;
                        onLog.accept("✗ " + w.getName() + " (exit " + code + ")");
                    }
                } catch (Exception ex) {
                    failed++;
                    onLog.accept("✗ " + w.getName() + " : " + ex.getMessage());
                }
            }

            long dur = System.currentTimeMillis() - start;
            if (failed > 0) {
                log.warn("deploy finished with {} failure(s): installed={}, skipped={} in {} ms",
                    failed, installed, skipped, dur);
            } else {
                log.info("deploy completed: installed={}, skipped={} in {} ms", installed, skipped, dur);
            }
            return new DeployResult(installed, skipped, failed, dur);
        } finally {
            // 清理临时目录(失败时保留以便排查?此处统一清理,日志已有记录)
            deleteRecursively(tmpDir);
        }
    }

    /** 解压 bundle/wheels/*.whl 到 destDir。 */
    private void extractWheels(Path zip, Path destDir, Consumer<String> onLog) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            java.util.Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry ze = e.nextElement();
                if (ze.getName().startsWith("bundle/wheels/") && ze.getName().endsWith(".whl") && !ze.isDirectory()) {
                    String fname = Path.of(ze.getName()).getFileName().toString();
                    Path out = destDir.resolve(fname);
                    try (var in = zf.getInputStream(ze)) {
                        Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    /** 解析目标 Python 可执行文件路径;venv 先创建环境再返回其 python 路径。 */
    private Path resolvePython(DeployTarget target, Consumer<String> onLog) throws Exception {
        return switch (target) {
            case DeployTarget.Global g -> g.pythonExe();
            case DeployTarget.Venv v -> {
                List<String> cmd = List.of(v.pythonExe().toString(), "-m", "venv", v.venvPath().toString());
                onLog.accept("$ " + String.join(" ", cmd));
                int code = runner.run(cmd, onLog);
                if (code != 0) throw new IllegalStateException("虚拟环境创建失败(exit " + code + ")");
                yield venvPython(v.venvPath());
            }
        };
    }

    /** venv 内 python 路径:Windows = venv/Scripts/python.exe,其他 = venv/bin/python。 */
    private Path venvPython(Path venvRoot) {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path exe = win ? venvRoot.resolve("Scripts").resolve("python.exe")
                       : venvRoot.resolve("bin").resolve("python");
        return exe;
    }

    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
