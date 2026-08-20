package fan.summer.fengyu.plugin.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The inspection drives the pre-upload confirmation dialog, so its version ordering must
 * mirror what the marketplace badge shows: same comparator, same verdicts.
 */
class PackageInspectionTest {
    @TempDir Path temp;

    @Test
    void unknownIdReportsFreshInstallWithoutComparison() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        PluginManifest incoming = service.readArchiveManifest(packageFile("1.0.0"));

        PackageInspection inspection = PackageInspection.of(incoming, service.find(incoming.id()));

        assertEquals("com.example.demo", inspection.id());
        assertEquals("Demo", inspection.name());
        assertEquals("1.0.0", inspection.version());
        assertFalse(inspection.installed());
        assertNull(inspection.installedVersion());
        assertNull(inspection.comparison());
        assertEquals(java.util.List.of("files.read"), inspection.permissions());
        assertEquals(java.util.List.of("files.read"), inspection.addedPermissions());
        assertTrue(inspection.permissionEscalation());
    }

    @Test
    void newerIncomingPackageIsAnUpgrade() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        service.install(packageFile("1.0.0"));

        PackageInspection inspection = inspect(service, "1.2.0");

        assertTrue(inspection.installed());
        assertEquals("1.0.0", inspection.installedVersion());
        assertEquals(PackageInspection.UPGRADE, inspection.comparison());
    }

    @Test
    void olderIncomingPackageIsADowngrade() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        service.install(packageFile("2.0.0"));

        PackageInspection inspection = inspect(service, "1.2.0");

        assertEquals(PackageInspection.DOWNGRADE, inspection.comparison());
    }

    @Test
    void equalVersionsReportSame() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        service.install(packageFile("1.4.2"));

        PackageInspection inspection = inspect(service, "1.4.2");

        assertTrue(inspection.installed());
        assertEquals(PackageInspection.SAME, inspection.comparison());
    }

    @Test
    void differentIdIsAFreshInstallEvenWithOtherPluginsPresent() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        service.install(packageFile("1.0.0"));

        PackageInspection inspection = PackageInspection.of(
            service.readArchiveManifest(packageFile("3.0.0", "net.other.plugin")), Optional.empty());

        assertFalse(inspection.installed());
        assertNull(inspection.comparison());
        assertEquals("net.other.plugin", inspection.id());
    }

    @Test
    void reportsAddedAndRemovedPermissionsForUpdateApproval() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        service.install(packageFile("1.0.0", "com.example.demo", "[\"files.read\",\"files.write\"]"));
        PluginManifest incoming = service.readArchiveManifest(
            packageFile("1.1.0", "com.example.demo", "[\"files.read\",\"network\"]"));

        PackageInspection inspection = PackageInspection.of(incoming, service.find(incoming.id()));

        assertEquals(java.util.List.of("network"), inspection.addedPermissions());
        assertEquals(java.util.List.of("files.write"), inspection.removedPermissions());
        assertTrue(inspection.permissionEscalation());
    }

    private PackageInspection inspect(PluginPackageService service, String incomingVersion) throws Exception {
        PluginManifest incoming = service.readArchiveManifest(packageFile(incomingVersion));
        return PackageInspection.of(incoming, service.find(incoming.id()));
    }

    private MockMultipartFile packageFile(String version) throws Exception {
        return packageFile(version, "com.example.demo");
    }

    private MockMultipartFile packageFile(String version, String id) throws Exception {
        return packageFile(version, id, "[\"files.read\"]");
    }

    private MockMultipartFile packageFile(String version, String id, String permissions) throws Exception {
        String manifest = """
            {"schemaVersion":2,"id":"%s","name":"Demo","description":"Demo plugin",
             "version":"%s","author":"Example","icon":"puzzle-outline","category":"dev",
             "ui":{"entry":"ui/index.html"},"permissions":%s}
            """.formatted(id, version, permissions);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ui/index.html"));
            zip.write("<html>demo</html>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new MockMultipartFile("file", "demo.fyp", "application/zip", bytes.toByteArray());
    }
}
