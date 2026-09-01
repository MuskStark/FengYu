package fan.summer.fengyu.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** Registry behavior for the store platform signing keys (review M-4). */
class StoreTrustStoreTest {

    @TempDir
    Path temp;

    private String freshKeyBase64() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    @Test
    void emptyRegistryTrustsNothing() {
        StoreTrustStore store = new StoreTrustStore(temp.resolve("missing.json"));

        assertFalse(store.hasKeys());
        assertThrows(IllegalArgumentException.class, () -> store.verificationKey("any"));
        assertThrows(IllegalArgumentException.class, () -> store.verificationKey(null));
    }

    @Test
    void loadsUserKeysAndRevocations() throws Exception {
        String key = freshKeyBase64();
        Files.writeString(temp.resolve("keys.json"), """
                {"keys":[{"id":"platform-2026","publicKey":"%s"}],
                 "revokedKeys":["platform-2024"]}
                """.formatted(key));

        StoreTrustStore store = new StoreTrustStore(temp.resolve("keys.json"));

        assertTrue(store.hasKeys());
        assertNotNull(store.verificationKey("platform-2026"));
        IllegalArgumentException revoked = assertThrows(IllegalArgumentException.class,
                () -> store.verificationKey("platform-2024"));
        assertTrue(revoked.getMessage().contains("revoked"), revoked.getMessage());
    }

    @Test
    void conflictingEntriesForTheSameKeyIdAreRejected() throws Exception {
        Files.writeString(temp.resolve("keys.json"), """
                {"keys":[{"id":"platform-2026","publicKey":"%s"},
                         {"id":"platform-2026","publicKey":"%s"}],
                 "revokedKeys":[]}
                """.formatted(freshKeyBase64(), freshKeyBase64()));

        assertThrows(IllegalStateException.class,
                () -> new StoreTrustStore(temp.resolve("keys.json")));
    }

    @Test
    void malformedKeyMaterialIsRejectedAtLoad() throws Exception {
        Files.writeString(temp.resolve("keys.json"), """
                {"keys":[{"id":"platform-2026","publicKey":"not-base64!!!"}],
                 "revokedKeys":[]}
                """);

        assertThrows(IllegalStateException.class,
                () -> new StoreTrustStore(temp.resolve("keys.json")));
    }
}
