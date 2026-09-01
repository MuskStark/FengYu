package fan.summer.fengyu.store;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trust registry for Infinia Store platform signatures (design §8.3): a
 * download ticket carries a SHA-256 (content integrity) <b>and</b> an Ed25519
 * signature with a keyId (origin authenticity) — both must pass. The keys
 * here, a bundled set merged with a user file under the runtime root (with
 * revocation, so key rotation can retire a compromised platform key), decide
 * which keyIds this host accepts.
 */
@Service
public class StoreTrustStore {

    static final String BUNDLED_RESOURCE = "/store/trusted-store-keys.json";
    static final String USER_FILE = "trusted-store-keys.json";

    public record StoreKey(String id, String publicKey) {}

    record TrustDocument(List<StoreKey> keys, List<String> revokedKeys) {}

    private static final JsonMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private final Map<String, byte[]> encodings = new LinkedHashMap<>();
    private final Map<String, PublicKey> keys = new LinkedHashMap<>();
    private final Set<String> revokedKeys = new LinkedHashSet<>();

    @Autowired
    public StoreTrustStore(
            @Value("#{T(fan.summer.fengyu.runtime.RuntimePaths).root().toString()}") String runtimeRoot) {
        this(Path.of(runtimeRoot).resolve(USER_FILE));
    }

    /** Explicit user-file location — public seam for other modules' tests. */
    public StoreTrustStore(Path userFile) {
        try {
            merge(readDocument(
                    StoreTrustStore.class.getResourceAsStream(BUNDLED_RESOURCE)));
            if (Files.isRegularFile(userFile)) {
                merge(readDocument(Files.newInputStream(userFile)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load store trust registry", e);
        }
    }

    /** Resolves a trusted, non-revoked platform verification key. */
    public PublicKey verificationKey(String keyId) {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("Store ticket carries no signing key id");
        }
        if (revokedKeys.contains(keyId)) {
            throw new IllegalArgumentException(
                    "Store signing key is revoked: " + keyId);
        }
        PublicKey key = keys.get(keyId);
        if (key == null) {
            throw new IllegalArgumentException(
                    "Store signing key is not trusted: " + keyId);
        }
        return key;
    }

    public boolean hasKeys() {
        return !keys.isEmpty();
    }

    private void merge(TrustDocument document) {
        if (document == null) {
            return;
        }
        for (StoreKey key : document.keys() == null ? List.<StoreKey>of() : document.keys()) {
            byte[] encoded = parse(key.publicKey());
            byte[] existing = encodings.putIfAbsent(key.id(), encoded);
            if (existing != null && !java.util.Arrays.equals(existing, encoded)) {
                throw new IllegalStateException(
                        "Conflicting store trust entries for key " + key.id());
            }
            keys.computeIfAbsent(key.id(), id -> toPublicKey(encoded));
        }
        if (document.revokedKeys() != null) {
            revokedKeys.addAll(document.revokedKeys());
        }
    }

    private static byte[] parse(String publicKeyBase64) {
        try {
            byte[] encoded = Base64.getDecoder().decode(publicKeyBase64);
            KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
            return encoded;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid store signing key entry", e);
        }
    }

    private static PublicKey toPublicKey(byte[] encoded) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Invalid store signing key entry", e);
        }
    }

    private static TrustDocument readDocument(InputStream in) throws IOException {
        if (in == null) {
            return null;
        }
        try (in) {
            return MAPPER.readValue(in, TrustDocument.class);
        }
    }
}
