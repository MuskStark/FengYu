package fan.summer.fengyu.plugin.offlinepython.domain;

import fan.summer.fengyu.plugin.offlinepython.infra.JsonStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 纯逻辑:从 bundle ZIP 读取 manifest + wheel 列表。不修改 ZIP,不依赖 JavaFX。
 */
public final class BundleReader {

    private static final String MANIFEST_ENTRY = "bundle/manifest.json";
    private static final String WHEELS_DIR = "bundle/wheels/";

    private BundleReader() {}

    public record Bundle(Manifest manifest, List<WheelEntry> wheels) {}

    /** 读 ZIP 内 bundle/manifest.json,返回 Manifest + 其 wheels 列表。 */
    public static Bundle read(Path zip) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry entry = zf.getEntry(MANIFEST_ENTRY);
            if (entry == null) throw new IOException("无效的 bundle 包:缺少 " + MANIFEST_ENTRY);
            try (InputStream in = zf.getInputStream(entry)) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                Manifest m = JsonStore.fromJson(json, Manifest.class);
                List<WheelEntry> wheels = m.getWheels() != null ? m.getWheels() : new ArrayList<>();
                return new Bundle(m, wheels);
            }
        }
    }

    /** 列出 bundle/wheels/ 下的 .whl 文件名(仅文件名,不含路径)。 */
    public static List<String> listWheelFiles(Path zip) throws IOException {
        List<String> out = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry ze = e.nextElement();
                String name = ze.getName();
                if (name.startsWith(WHEELS_DIR) && name.endsWith(".whl") && !name.endsWith("/")) {
                    out.add(Path.of(name).getFileName().toString());
                }
            }
        }
        out.sort(null);
        return out;
    }
}
