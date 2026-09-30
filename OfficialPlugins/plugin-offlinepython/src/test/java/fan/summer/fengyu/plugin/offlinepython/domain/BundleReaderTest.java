package fan.summer.fengyu.plugin.offlinepython.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BundleReaderTest {

    @Test
    void readsManifestFromZip(@TempDir Path tmp) throws IOException {
        Path zip = tmp.resolve("b.zip");
        Manifest m = new Manifest();
        m.setSchemaVersion(1);
        m.getPython().setVersion("3.12.10");
        m.getWheels().add(new WheelEntry("numpy", "1.26.4",
                "wheels/numpy-1.26.4-cp312-cp312-win_amd64.whl", "abc", 1000, true));
        m.getRequirements().add("numpy==1.26.4");
        String json = fan.summer.fengyu.plugin.offlinepython.infra.JsonStore.toJson(m);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("bundle/manifest.json"));
            z.write(json.getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("bundle/wheels/numpy-1.26.4-cp312-cp312-win_amd64.whl"));
            z.write(new byte[]{1, 2, 3});
            z.closeEntry();
        }

        BundleReader.Bundle b = BundleReader.read(zip);
        assertEquals("3.12.10", b.manifest().getPython().getVersion());
        assertEquals(1, b.wheels().size());
        assertEquals("numpy", b.wheels().get(0).getName());
    }

    @Test
    void listWheelFilesReturnsWhlNames(@TempDir Path tmp) throws IOException {
        Path zip = tmp.resolve("b.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("bundle/wheels/a.whl"));
            z.write(new byte[]{1});
            z.closeEntry();
            z.putNextEntry(new ZipEntry("bundle/wheels/b.whl"));
            z.write(new byte[]{2});
            z.closeEntry();
            z.putNextEntry(new ZipEntry("bundle/manifest.json"));
            z.write("{}".getBytes());
            z.closeEntry();
        }
        List<String> files = BundleReader.listWheelFiles(zip);
        assertEquals(List.of("a.whl", "b.whl"), files);
    }

    @Test
    void throwsOnMissingManifest(@TempDir Path tmp) throws IOException {
        Path zip = tmp.resolve("b.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("bundle/wheels/a.whl"));
            z.write(new byte[]{1});
            z.closeEntry();
        }
        assertThrows(IOException.class, () -> BundleReader.read(zip));
    }
}
