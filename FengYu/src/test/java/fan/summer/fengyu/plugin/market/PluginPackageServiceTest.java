package fan.summer.fengyu.plugin.market;

import fan.summer.fengyu.setup.PluginDbProvisioner;
import fan.summer.fengyu.setup.PluginDbProvisioningStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PluginPackageServiceTest {
    @TempDir Path temp;

    @Test
    void installsDisablesAndUninstallsPackage() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        PluginManifest manifest = service.install(packageFile("1.0.0"));

        assertEquals("com.example.demo", manifest.id());
        assertEquals(1, service.installed().size());
        assertTrue(service.isEnabled(manifest.id()));

        service.setEnabled(manifest.id(), false);
        assertFalse(service.isEnabled(manifest.id()));
        service.uninstall(manifest.id());
        assertTrue(service.installed().isEmpty());
    }

    @Test
    void updateReplacesVersionAndKeepsDisabledState() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        service.install(packageFile("1.0.0"));
        service.setEnabled("com.example.demo", false);
        service.install(packageFile("1.1.0"));

        assertEquals("1.1.0", service.installed().getFirst().version());
        assertFalse(service.isEnabled("com.example.demo"));
    }

    @Test
    void rejectsZipSlip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("../escape.txt"));
            zip.write("bad".getBytes(StandardCharsets.UTF_8));
        }
        MockMultipartFile file = new MockMultipartFile("file", "bad.fyp", "application/zip", bytes.toByteArray());
        PluginPackageService service = new PluginPackageService(temp.toString());
        assertThrows(IllegalArgumentException.class, () -> service.install(file));
        assertFalse(Files.exists(temp.getParent().resolve("escape.txt")));
    }

    @Test
    void installsSharedValidFullFixture() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        PluginManifest manifest = service.install(fixturePackage("valid-full.json", "ui/index.html", "<html>full</html>"));
        assertEquals("com.example.full", manifest.id());
        // database and network.email are accepted by the shared canonical permission set.
        assertTrue(manifest.permissions().containsAll(java.util.List.of("database", "network.email")));
    }

    @Test
    void rejectsInvalidId() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        MockMultipartFile file = inlinePackage(
            "{\"schemaVersion\":2,\"id\":\"UPPER\",\"name\":\"X\",\"description\":\"d\",\"author\":\"a\",\"icon\":\"i\",\"category\":\"c\",\"version\":\"1.0.0\",\"ui\":{\"entry\":\"ui/index.html\"}}",
            "ui/index.html", "<html></html>");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.install(file));
        assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("id"));
    }

    @Test
    void rejectsMissingUiEntry() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        // ui.entry points at ui/index.html but the archive only carries README.txt, not the UI file.
        MockMultipartFile file = inlinePackage(
            "{\"schemaVersion\":2,\"id\":\"com.example.no-ui\",\"name\":\"NoUi\",\"description\":\"d\",\"author\":\"a\",\"icon\":\"i\",\"category\":\"c\",\"version\":\"1.0.0\",\"ui\":{\"entry\":\"ui/index.html\"}}",
            "README.txt", "placeholder");
        assertThrows(IllegalArgumentException.class, () -> service.install(file));
    }

    @Test
    void rejectsUnknownAiToolEffect() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        MockMultipartFile file = inlinePackage(
            """
            {"schemaVersion":2,"id":"com.example.effect","name":"Effect","description":"Effect test",
             "version":"1.0.0","author":"Example","icon":"toolbox","category":"dev",
             "ui":{"entry":"ui/index.html"},
             "rpc":{"methods":{"change":{"inputSchema":{"type":"object","properties":{}}}}},
             "aiTools":[{"name":"change","description":"Change","method":"change","effect":"delete-everything"}]}
            """,
            "ui/index.html", "<html></html>");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.install(file));
        assertTrue(ex.getMessage().contains("effect"));
    }

    /**
     * Regression (P0-8): an uploaded (untrusted) package cannot claim {@code official: true}. The
     * {@code official} flag is host-trusted only; a self-declared official badge must be rejected so
     * no third party can masquerade as an official plugin (or replace one by id).
     */
    @Test
    void untrustedUploadCannotClaimOfficial() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        MockMultipartFile file = inlinePackage(
            """
            {"schemaVersion":2,"id":"com.example.claim","name":"Claim","description":"claims official",
             "version":"1.0.0","author":"X","icon":"puzzle-outline","category":"dev",
             "ui":{"entry":"ui/index.html"},"official":true,"permissions":[]}
            """,
            "ui/index.html", "<html></html>");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.install(file));
        assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("official"),
            "untrusted official claim must be rejected: " + ex.getMessage());
    }

    /**
     * Regression (P0-8): an uploaded (untrusted) package cannot use the reserved
     * {@code fan.summer.*} namespace, even with {@code official: false}. Without this a hostile
     * package could squat e.g. {@code fan.summer.browser} and be indistinguishable from the real one.
     */
    @Test
    void untrustedUploadCannotUseReservedNamespace() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        MockMultipartFile file = inlinePackage(
            """
            {"schemaVersion":2,"id":"fan.summer.browser","name":"Fake Browser","description":"impersonation",
             "version":"1.0.0","author":"attacker","icon":"puzzle-outline","category":"dev",
             "ui":{"entry":"ui/index.html"},"permissions":[]}
            """,
            "ui/index.html", "<html></html>");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.install(file));
        assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("reserved"),
            "reserved namespace must be rejected on the untrusted path: " + ex.getMessage());
    }

    /**
     * Regression (P0-8): the trusted install path (the official-plugin seeder) MAY legitimately
     * declare {@code official: true} and use {@code fan.summer.*}. This is the path that justifies
     * the trust (a SHA-256 sidecar verified by the caller before reaching installTrusted).
     */
    @Test
    void trustedInstallAllowsOfficialInReservedNamespace() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        Path archive = writeArchive(temp.resolve("official.fyp"),
            """
            {"schemaVersion":2,"id":"fan.summer.demo","name":"Official Demo","description":"trusted",
             "version":"1.0.0","author":"FengYu","icon":"puzzle-outline","category":"dev",
             "ui":{"entry":"ui/index.html"},"official":true,"permissions":[]}
            """,
            "ui/index.html", "<html>official</html>");
        PluginManifest manifest = service.installTrusted(archive);
        assertEquals("fan.summer.demo", manifest.id());
        assertTrue(manifest.official(), "trusted install must preserve the official flag");
    }

    /**
     * Regression (P0-8): a normal third-party upload with a non-reserved id and {@code official:false}
     * installs unchanged — namespace reservation and official-claim rejection must not break the
     * ordinary third-party install path.
     */
    @Test
    void thirdPartyUploadWithoutClaimsInstallsNormally() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        PluginManifest manifest = service.install(packageFile("1.0.0"));
        assertEquals("com.example.demo", manifest.id());
        assertFalse(manifest.official());
    }

    /**
     * Feature: a local install via the native path may claim official identity when it ships a
     * matching {@code .sha256} sidecar (the CLI packager's trust credential, the same one the
     * official seeder verifies). This lets a user install a rebuilt official plugin locally through
     * the same trust level as the seeder. The sidecar format is GNU coreutils {@code sha256sum -c}:
     * {@code <hex>  <basename>}.
     */
    @Test
    void nativeInstallWithMatchingSidecarInstallsOfficialPlugin() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        Path archive = writeArchive(temp.resolve("official.fyp"),
            """
            {"schemaVersion":2,"id":"fan.summer.demo","name":"Official Demo","description":"trusted",
             "version":"1.0.0","author":"FengYu","icon":"puzzle-outline","category":"dev",
             "ui":{"entry":"ui/index.html"},"official":true,"permissions":[]}
            """,
            "ui/index.html", "<html>official</html>");
        writeSidecar(archive);

        PluginManifest manifest = service.install(archive);
        assertEquals("fan.summer.demo", manifest.id());
        assertTrue(manifest.official(), "a sidecar-verified local install may claim official");
    }

    /**
     * Regression: without a sidecar, the native install path stays untrusted, so an official claim
     * must still be rejected. A third party cannot gain official identity merely by dropping a
     * hand-zipped {@code .fyp} onto disk.
     */
    @Test
    void nativeInstallWithoutSidecarRejectsOfficialClaim() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        Path archive = writeArchive(temp.resolve("official.fyp"),
            """
            {"schemaVersion":2,"id":"fan.summer.demo","name":"Official Demo","description":"trusted",
             "version":"1.0.0","author":"FengYu","icon":"puzzle-outline","category":"dev",
             "ui":{"entry":"ui/index.html"},"official":true,"permissions":[]}
            """,
            "ui/index.html", "<html>official</html>");
        // No .sha256 sidecar → untrusted → validate() rejects the official claim.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.install(archive));
        assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("official"));
    }

    /** A mismatched sidecar (e.g. for a different file) must NOT grant trust. */
    @Test
    void nativeInstallWithMismatchedSidecarRejectsOfficialClaim() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        Path archive = writeArchive(temp.resolve("official.fyp"),
            """
            {"schemaVersion":2,"id":"fan.summer.demo","name":"Official Demo","description":"trusted",
             "version":"1.0.0","author":"FengYu","icon":"puzzle-outline","category":"dev",
             "ui":{"entry":"ui/index.html"},"official":true,"permissions":[]}
            """,
            "ui/index.html", "<html>official</html>");
        // Sidecar for a *different* (non-matching) hash.
        Files.writeString(Path.of(archive + ".sha256"),
            "0000000000000000000000000000000000000000000000000000000000000000  official.fyp\n");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.install(archive));
        assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("official"),
            "a mismatched sidecar must not grant trust: " + ex.getMessage());
    }

    /** Write a valid {@code <archive>.sha256} sidecar (GNU coreutils format). */
    private void writeSidecar(Path archive) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[64 * 1024];
        try (var in = Files.newInputStream(archive)) {
            int n;
            while ((n = in.read(buf)) >= 0) digest.update(buf, 0, n);
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        Files.writeString(Path.of(archive + ".sha256"), hex + "  " + archive.getFileName() + "\n");
    }


    @Test
    void uninstallInvokesDeprovisionWhenAProvisionerIsAttached(
            @TempDir Path pluginsRoot,
            @TempDir Path hostConfig) throws Exception {
        Path pluginDir = pluginsRoot.resolve("fan.summer.email");
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("manifest.json"),
            "{\"schemaVersion\":2,\"id\":\"fan.summer.email\",\"name\":\"Email\","
            + "\"description\":\"d\",\"version\":\"1.0.0\",\"author\":\"a\",\"icon\":\"email\","
            + "\"category\":\"net\",\"ui\":{\"entry\":\"ui/index.html\"},"
            + "\"backend\":{\"callTimeoutSeconds\":60},"
            + "\"permissions\":[\"database\"]}");

        java.util.List<String> deprovisioned = new java.util.ArrayList<>();
        PluginDbProvisioner provisioner = new PluginDbProvisioner(
            new fan.summer.fengyu.setup.DataSourceConfigService(hostConfig.toString()),
            new PluginDbProvisioningStore(hostConfig)) {
            @Override public void deprovision(String pluginId) { deprovisioned.add(pluginId); }
        };
        PluginPackageService service = new PluginPackageService(pluginsRoot.toString());
        service.attachProvisionerForTest(provisioner);

        service.uninstall("fan.summer.email");

        assertEquals(java.util.List.of("fan.summer.email"), deprovisioned,
            "uninstall must deprovision the plugin's DB credentials");
        assertFalse(Files.exists(pluginDir), "plugin directory must be deleted too");
    }

    @Test
    void uninstallRetainsOrDeletesRuntimeDataAccordingToExplicitPolicy() throws Exception {
        Path pluginsRoot = temp.resolve("plugins");
        Path dataRoot = temp.resolve("plugin-data");
        PluginPackageService service = new PluginPackageService(pluginsRoot.toString(), dataRoot);

        service.install(packageFile("1.0.0"));
        Path dataFile = dataRoot.resolve("com.example.demo/state/profile.db");
        Files.createDirectories(dataFile.getParent());
        Files.writeString(dataFile, "state");
        service.uninstall("com.example.demo", false);
        assertTrue(Files.exists(dataFile), "retain policy must keep runtime data");

        service.install(packageFile("1.0.0"));
        service.uninstall("com.example.demo", true);
        assertFalse(Files.exists(dataRoot.resolve("com.example.demo")),
            "delete policy must remove the complete plugin data directory");
    }

    @Test
    void retainDataPolicyAlsoRetainsProvisionedDatabaseNamespace() throws Exception {
        Path pluginsRoot = temp.resolve("retain-db-plugins");
        Path dataRoot = temp.resolve("retain-db-data");
        PluginPackageService service = new PluginPackageService(pluginsRoot.toString(), dataRoot);
        service.install(packageFile("1.0.0"));

        java.util.List<String> deprovisioned = new java.util.ArrayList<>();
        PluginDbProvisioner provisioner = new PluginDbProvisioner(
            new fan.summer.fengyu.setup.DataSourceConfigService(temp.resolve("retain-db-config").toString()),
            new PluginDbProvisioningStore(temp.resolve("retain-db-config"))) {
            @Override public void deprovision(String pluginId) { deprovisioned.add(pluginId); }
        };
        service.attachProvisionerForTest(provisioner);

        service.uninstall("com.example.demo", false);

        assertTrue(deprovisioned.isEmpty(),
            "retaining plugin data must also retain its provisioned DB namespace and credentials");
    }

    /** Locates the cross-language shared fixtures from either FengYu/ or the repository root. */
    private Path fixture(String name) {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path direct = root.resolve("toolchain/spec/fixtures").resolve(name);
        return Files.exists(direct) ? direct : root.resolve("../toolchain/spec/fixtures").resolve(name).normalize();
    }

    private MockMultipartFile fixturePackage(String fixtureName, String assetPath, String assetContent) throws Exception {
        String manifest = Files.readString(fixture(fixtureName), StandardCharsets.UTF_8);
        return zip("fixture.fyp", manifest, assetPath, assetContent);
    }

    private MockMultipartFile inlinePackage(String manifestJson, String assetPath, String assetContent) throws Exception {
        return zip("fixture.fyp", manifestJson, assetPath, assetContent);
    }

    private MockMultipartFile packageFile(String version) throws Exception {
        String manifest = """
            {"schemaVersion":2,"id":"com.example.demo","name":"Demo","description":"Demo plugin",
             "version":"%s","author":"Example","icon":"puzzle-outline","category":"dev",
             "ui":{"entry":"ui/index.html"},"permissions":["files.read"]}
            """.formatted(version);
        return zip("demo.fyp", manifest, "ui/index.html", "<html>demo</html>");
    }

    private MockMultipartFile zip(String filename, String manifest, String assetPath, String assetContent) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            add(zip, "manifest.json", manifest);
            add(zip, assetPath, assetContent);
        }
        return new MockMultipartFile("file", filename, "application/zip", bytes.toByteArray());
    }

    private static void add(ZipOutputStream zip, String path, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Write a .fyp (zip) archive to {@code path} with a manifest and a single UI asset. */
    private Path writeArchive(Path path, String manifest, String assetPath, String assetContent) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            add(zip, "manifest.json", manifest);
            add(zip, assetPath, assetContent);
        }
        Files.write(path, bytes.toByteArray());
        return path;
    }
}
