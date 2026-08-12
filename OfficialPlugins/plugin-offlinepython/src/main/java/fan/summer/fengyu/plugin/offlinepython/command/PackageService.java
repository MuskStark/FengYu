package fan.summer.fengyu.plugin.offlinepython.command;

import fan.summer.fengyu.plugin.offlinepython.domain.BuildConfig;
import fan.summer.fengyu.sdk.PluginMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把构建产物 output/ 打包成一个 bundle ZIP,供离线机部署使用。
 * ZIP 结构:bundle/{manifest.json, SHA256SUMS?, wheels/*.whl}
 */
public class PackageService {

    private static final String BUNDLE_ROOT = "bundle/";
    private static final String WHEELS_DIR = BUNDLE_ROOT + "wheels/";

    /** Localized worker messages for envelope summaries / exception messages. */
    private static final PluginMessages MSGS =
            PluginMessages.forClassLoader(PluginMessages.DEFAULT_BASE_NAME, PackageService.class);

    private static final Logger log = LoggerFactory.getLogger(PackageService.class);

    public PackageService() {}

    /** 打包 projectDir/output 为 bundle ZIP,返回生成的 ZIP 路径。 */
    public Path packageBundle(Path projectDir, BuildConfig cfg) throws IOException {
        Path output = projectDir.resolve(cfg.getRepository().getOutput());
        Path manifest = output.resolve("manifest.json");
        if (!Files.exists(manifest)) {
            throw new IOException(MSGS.format("opb.msg.package.buildFirst"));
        }
        Path wheelhouse = output.resolve(cfg.getRepository().getWheelDir())
                .resolve(cfg.getPython().getVersion());
        if (!Files.exists(wheelhouse) || countWheels(wheelhouse) == 0) {
            throw new IOException(MSGS.format("opb.msg.package.noWheels"));
        }

        String bundleName = (cfg.getBundle() != null && cfg.getBundle().getName() != null
                && !cfg.getBundle().getName().isBlank())
                ? cfg.getBundle().getName()
                : projectDir.getFileName().toString();
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        Path zip = output.resolve(bundleName + "-bundle-" + stamp + ".zip");

        boolean includeSha = cfg.getBundle() == null || cfg.getBundle().isSha256();
        int count = 0;
        long bytes = 0;

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            // manifest.json
            addEntry(zos, BUNDLE_ROOT + "manifest.json", manifest);
            // SHA256SUMS
            Path sums = output.resolve("SHA256SUMS");
            if (includeSha && Files.exists(sums)) {
                addEntry(zos, BUNDLE_ROOT + "SHA256SUMS", sums);
            }
            // wheels(扁平化,按文件名排序)
            try (Stream<Path> files = Files.list(wheelhouse)) {
                List<Path> sorted = files.filter(p -> p.toString().endsWith(".whl"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
                for (Path whl : sorted) {
                    String entryName = WHEELS_DIR + whl.getFileName().toString();
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(whl, zos);
                    zos.closeEntry();
                    count++;
                    bytes += Files.size(whl);
                }
            }
        }

        // Route through SLF4J directly: the SDK provider emits the structured stderr event the host
        // captures. (The old OpbLogger wrapper was wired with a null instance in production, so this
        // milestone used to be silently dropped — see fengyu-plugin-dev logging rules.)
        log.info(MSGS.format("opb.msg.package.done", count, humanBytes(bytes), zip.getFileName()));
        return zip;
    }

    private long countWheels(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".whl")).count();
        }
    }

    private void addEntry(ZipOutputStream zos, String name, Path src) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        Files.copy(src, zos);
        zos.closeEntry();
    }

    private static String humanBytes(long b) {
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format("%.1f MB", b / (1024.0 * 1024));
    }
}
