package fan.summer.fengyu.ai.skill;

import com.sun.net.httpserver.HttpServer;
import fan.summer.fengyu.store.StoreTrustStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Trust-chain regression for the remote skill marketplace (review M-6): skill
 * guidance feeds the AI's prompt context, so catalog and {@code .fys} downloads
 * follow the store's rules — HTTPS/SSRF policy, mandatory SHA-256, platform
 * Ed25519 signature, signature-anchored official badge, and SemVer update
 * ordering.
 */
class SkillMarketplaceServiceTest {

    @TempDir
    Path temp;

    HttpServer server;
    KeyPair platformKey;
    byte[] fysBytes;
    String fysSha;

    @BeforeEach
    void setUp() throws Exception {
        platformKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        fysBytes = buildFys("dev.example.market-skill", "1.2.0", false);
        fysSha = sha256(fysBytes);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body;
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith(".fys")) {
                body = fysBytes;
            } else {
                body = catalogFor(path).getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** One catalog entry; null fields are omitted so each variant tests its own gap. */
    private String entryJson(String sha256, String signature, String keyId) {
        StringBuilder entry = new StringBuilder("""
                {"id":"dev.example.market-skill","name":"Market Skill",
                 "description":"d","version":"1.2.0","author":"someone",
                 "downloadUrl":"%s/skill.fys","official":true"""
                .formatted(base()));
        if (sha256 != null) {
            entry.append(",\"sha256\":\"").append(sha256).append('"');
        }
        if (signature != null) {
            entry.append(",\"signature\":\"").append(signature).append('"');
        }
        if (keyId != null) {
            entry.append(",\"keyId\":\"").append(keyId).append('"');
        }
        return "[" + entry + "}]";
    }

    private String catalogFor(String path) {
        return switch (path) {
            case "/nosha.json" -> entryJson(null, signature(fysBytes), "platform-2026");
            case "/wrongsha.json" -> entryJson("0".repeat(64),
                    signature(fysBytes), "platform-2026");
            case "/unsigned.json" -> entryJson(fysSha, null, null);
            default -> entryJson(fysSha, signature(fysBytes), "platform-2026");
        };
    }

    private SkillMarketplaceService market(String catalogPath, String userKeysJson,
            boolean requireSignature) throws Exception {
        Path keys = temp.resolve("keys.json");
        Files.writeString(keys, userKeysJson);
        return new SkillMarketplaceService(
                new SkillPackageService(temp.resolve("skills").toString()),
                new StoreTrustStore(keys), base() + catalogPath, requireSignature);
    }

    private String trustedUserKeys() {
        return """
                {"keys":[{"id":"platform-2026","publicKey":"%s"}],"revokedKeys":[]}
                """.formatted(Base64.getEncoder().encodeToString(
                        platformKey.getPublic().getEncoded()));
    }

    private String emptyUserKeys() {
        return "{\"keys\":[],\"revokedKeys\":[]}";
    }

    private String signature(byte[] bytes) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(platformKey.getPrivate());
            signature.update(bytes);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] buildFys(String id, String version, boolean official)
            throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(("""
                    {"schemaVersion":1,"id":"%s","name":"%s","description":"d",
                     "version":"%s","author":"someone","official":%s}
                    """).formatted(id, id, version, official)
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            zip.write("# Guidance".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void signedEntryInstallsAfterFullVerification() throws Exception {
        SkillMarketplaceService service = market("/catalog.json",
                trustedUserKeys(), true);

        SkillManifest installed = service.install("dev.example.market-skill");

        assertEquals("dev.example.market-skill", installed.id());
        assertEquals("1.2.0", installed.version());
        assertTrue(Files.isRegularFile(temp.resolve("skills")
                .resolve("dev.example.market-skill").resolve("SKILL.md")));
    }

    @Test
    void entryWithoutSha256IsRefused() throws Exception {
        SkillMarketplaceService service = market("/nosha.json",
                trustedUserKeys(), true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.install("dev.example.market-skill"));
        assertTrue(error.getMessage().contains("no SHA-256"), error.getMessage());
    }

    @Test
    void tamperedDigestIsRefused() throws Exception {
        SkillMarketplaceService service = market("/wrongsha.json",
                trustedUserKeys(), true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.install("dev.example.market-skill"));
        assertTrue(error.getMessage().contains("integrity check failed"),
                error.getMessage());
    }

    @Test
    void unsignedEntryIsRefusedByDefault() throws Exception {
        SkillMarketplaceService service = market("/unsigned.json",
                trustedUserKeys(), true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.install("dev.example.market-skill"));
        assertTrue(error.getMessage().contains("not signed"), error.getMessage());
    }

    @Test
    void untrustedSigningKeyIsRefused() throws Exception {
        SkillMarketplaceService service = market("/catalog.json",
                emptyUserKeys(), true);

        assertThrows(IllegalArgumentException.class,
                () -> service.install("dev.example.market-skill"));
    }

    @Test
    void officialBadgeRequiresATrustedSigningKey() throws Exception {
        SkillMarketplaceService trusted = market("/catalog.json",
                trustedUserKeys(), true);
        assertTrue(trusted.list().get(0).official(),
                "the entry's keyId verifies → official badge displays");

        SkillMarketplaceService untrusted = market("/catalog.json",
                emptyUserKeys(), true);
        assertFalse(untrusted.list().get(0).official(),
                "a catalog's official claim alone must not display");
    }

    @Test
    void versionComparisonFollowsSemVerPrecedence() {
        assertTrue(SkillMarketplaceService.compareVersions("1.0.0-rc.1", "1.0.0-beta.9") > 0);
        assertTrue(SkillMarketplaceService.compareVersions("1.0.0", "1.0.0-rc.1") > 0);
        assertTrue(SkillMarketplaceService.compareVersions("2.0.0-beta.10", "2.0.0-beta.2") > 0);
        assertEquals(0, SkillMarketplaceService.compareVersions("1.0.0+build.1", "1.0.0"));
    }

    @Test
    void plainHttpCatalogsOffLoopbackAreRefused() throws Exception {
        SkillMarketplaceService service = new SkillMarketplaceService(
                new SkillPackageService(temp.resolve("skills").toString()),
                new StoreTrustStore(temp.resolve("keys.json")),
                "http://8.8.8.8/catalog.json", true);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                service::list);
        assertNotNull(error.getCause());
        assertTrue(error.getCause().getMessage().contains("loopback"),
                error.toString());
    }
}
