package fan.summer.fengyu.plugin.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.runtime.RuntimePaths;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ed25519 publisher trust roots and key/package revocations for downloadable plugins. */
@Service
public class PluginTrustStore {
    static final String BUNDLED_RESOURCE = "/plugin/trusted-publishers.json";
    static final String USER_FILE = "trusted-plugin-publishers.json";
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final Path runtimeRoot;
    private final TrustDocument testDocument;

    public PluginTrustStore() {
        this(RuntimePaths.root(), null);
    }

    PluginTrustStore(Path runtimeRoot) {
        this(runtimeRoot, null);
    }

    PluginTrustStore(TrustDocument document) {
        this(null, document);
    }

    private PluginTrustStore(Path runtimeRoot, TrustDocument testDocument) {
        this.runtimeRoot = runtimeRoot == null ? null : runtimeRoot.toAbsolutePath().normalize();
        this.testDocument = testDocument;
    }

    public Verification verify(Path archive, String digest, PluginManifest manifest,
            String signatureBase64, String keyId) {
        TrustDocument trust = load();
        for (RevokedPackage revoked : safe(trust.revokedPackages())) {
            boolean id = revoked.id() == null || revoked.id().equals(manifest.id());
            boolean version = revoked.version() == null || revoked.version().equals(manifest.version());
            boolean sha = revoked.sha256() == null || revoked.sha256().equalsIgnoreCase(digest);
            if (id && version && sha) {
                throw new IllegalArgumentException("Plugin package is revoked: " + manifest.id()
                    + " " + manifest.version());
            }
        }
        boolean hasSignature = signatureBase64 != null && !signatureBase64.isBlank();
        boolean hasKey = keyId != null && !keyId.isBlank();
        if (!hasSignature && !hasKey) return new Verification(false, null);
        if (hasSignature != hasKey) {
            throw new IllegalArgumentException("Plugin signature and keyId must be supplied together");
        }
        if (safe(trust.revokedKeys()).contains(keyId)) {
            throw new IllegalArgumentException("Plugin publisher key is revoked: " + keyId);
        }
        PublisherKey publisher = safe(trust.keys()).stream()
            .filter(key -> keyId.equals(key.id())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Plugin publisher key is not trusted: " + keyId));
        // Dot boundary: a key authorized for `a.b` must not sign `a.bevil` — prefix matching
        // without the boundary lets one namespace shadow a sibling namespace (C3). A trailing
        // dot in the stored prefix (e.g. "com.acme.") is accepted as the same boundary.
        boolean namespaceAllowed = safe(publisher.namespaces()).stream()
            .map(prefix -> prefix != null && prefix.endsWith(".")
                    ? prefix.substring(0, prefix.length() - 1) : prefix)
            .anyMatch(prefix -> manifest.id().equals(prefix)
                || (prefix != null && !prefix.isBlank() && manifest.id().startsWith(prefix + ".")));
        if (!namespaceAllowed) {
            throw new IllegalArgumentException("Publisher key " + keyId
                + " is not authorized for plugin id " + manifest.id());
        }
        try {
            byte[] publicKey = Base64.getDecoder().decode(stripPem(publisher.publicKey()));
            byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64.trim());
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(publicKey)));
            try (InputStream input = Files.newInputStream(archive)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) >= 0;) verifier.update(buffer, 0, count);
            }
            if (!verifier.verify(signatureBytes)) {
                throw new IllegalArgumentException("Plugin signature is invalid for key " + keyId);
            }
            return new Verification(true, keyId);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot verify plugin signature: " + e.getMessage(), e);
        }
    }

    private TrustDocument load() {
        if (testDocument != null) return testDocument;
        List<TrustDocument> documents = new ArrayList<>();
        try (InputStream bundled = PluginTrustStore.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (bundled != null) documents.add(json.readValue(bundled, TrustDocument.class));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read bundled plugin trust store", e);
        }
        Path user = runtimeRoot == null ? null : runtimeRoot.resolve(USER_FILE);
        if (user != null && Files.isRegularFile(user)) {
            try { documents.add(json.readValue(user.toFile(), TrustDocument.class)); }
            catch (IOException e) { throw new IllegalStateException("Cannot read " + user, e); }
        }
        Map<String, PublisherKey> keys = new LinkedHashMap<>();
        List<String> revokedKeys = new ArrayList<>();
        List<RevokedPackage> revokedPackages = new ArrayList<>();
        for (TrustDocument document : documents) {
            for (PublisherKey key : safe(document.keys())) {
                PublisherKey previous = keys.putIfAbsent(key.id(), key);
                if (previous != null && !previous.equals(key)) {
                    throw new IllegalStateException("Conflicting plugin trust key id: " + key.id());
                }
            }
            revokedKeys.addAll(safe(document.revokedKeys()));
            revokedPackages.addAll(safe(document.revokedPackages()));
        }
        return new TrustDocument(List.copyOf(keys.values()), List.copyOf(revokedKeys),
            List.copyOf(revokedPackages));
    }

    private static String stripPem(String value) {
        if (value == null) throw new IllegalArgumentException("Trusted publisher publicKey is required");
        return value.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
    }

    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }

    public record PublisherKey(String id, String publicKey, List<String> namespaces) {}
    public record RevokedPackage(String id, String version, String sha256) {}
    public record TrustDocument(List<PublisherKey> keys, List<String> revokedKeys,
                                List<RevokedPackage> revokedPackages) {}
    public record Verification(boolean trusted, String keyId) {}
}
