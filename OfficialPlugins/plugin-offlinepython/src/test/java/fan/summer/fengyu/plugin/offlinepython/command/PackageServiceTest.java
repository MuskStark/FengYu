package fan.summer.fengyu.plugin.offlinepython.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import fan.summer.fengyu.plugin.offlinepython.domain.BuildConfig;
import fan.summer.fengyu.plugin.offlinepython.domain.Manifest;
import fan.summer.fengyu.plugin.offlinepython.domain.WheelEntry;
import fan.summer.fengyu.plugin.offlinepython.infra.JsonStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class PackageServiceTest {

    @Test
    void packagesOutputIntoBundleZip(@TempDir Path tmp) throws IOException {
        // 搭建 output/ 结构
        Path projectDir = tmp.resolve("proj");
        Path output = projectDir.resolve("output");
        Path wheels = output.resolve("wheelhouse").resolve("3.12.10");
        Files.createDirectories(wheels);
        // manifest.json
        Manifest m = new Manifest();
        m.setSchemaVersion(1);
        m.getPython().setVersion("3.12.10");
        m.getWheels().add(new WheelEntry("numpy", "1.26.4",
                "wheelhouse/3.12.10/numpy-1.26.4-cp312-cp312-win_amd64.whl", "abc", 1000, true));
        JsonStore.save(m, output.resolve("manifest.json"));
        // 一个 wheel
        Files.writeString(wheels.resolve("numpy-1.26.4-cp312-cp312-win_amd64.whl"), "fake-wheel");
        // SHA256SUMS
        Files.writeString(output.resolve("SHA256SUMS"), "abc  wheelhouse/3.12.10/numpy-1.26.4-cp312-cp312-win_amd64.whl\n");

        BuildConfig cfg = BuildConfig.defaults();
        cfg.getPython().setVersion("3.12.10");

        Path zip = new PackageService().packageBundle(projectDir, cfg);

        assertTrue(Files.exists(zip));
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            assertNotNull(zf.getEntry("bundle/manifest.json"));
            assertNotNull(zf.getEntry("bundle/SHA256SUMS"));
            assertNotNull(zf.getEntry("bundle/wheels/numpy-1.26.4-cp312-cp312-win_amd64.whl"));
        }
    }

    @Test
    void throwsWhenManifestMissing(@TempDir Path tmp) throws IOException {
        Path projectDir = tmp.resolve("proj");
        Files.createDirectories(projectDir.resolve("output"));
        BuildConfig cfg = BuildConfig.defaults();
        assertThrows(IOException.class, () -> new PackageService().packageBundle(projectDir, cfg));
    }

    @Test
    void throwsWhenNoWheels(@TempDir Path tmp) throws IOException {
        Path projectDir = tmp.resolve("proj");
        Path output = projectDir.resolve("output");
        Files.createDirectories(output);
        JsonStore.save(new Manifest(), output.resolve("manifest.json"));
        BuildConfig cfg = BuildConfig.defaults();
        assertThrows(IOException.class, () -> new PackageService().packageBundle(projectDir, cfg));
    }
}
