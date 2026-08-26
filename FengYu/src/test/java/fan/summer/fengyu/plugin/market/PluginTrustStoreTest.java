package fan.summer.fengyu.plugin.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginTrustStoreTest {
    @TempDir Path temp;

    @Test void verifiesAuthorizedPublisherAndRejectsTamper() throws Exception {
        Path archive = temp.resolve("demo.fyp");
        Files.writeString(archive, "package bytes");
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var document = new PluginTrustStore.TrustDocument(
            List.of(new PluginTrustStore.PublisherKey("acme-2026",
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                List.of("com.acme."))), List.of(), List.of());
        PluginTrustStore store = new PluginTrustStore(document);
        PluginManifest manifest = manifest("com.acme.demo", "1.0.0");
        String signature = sign(pair.getPrivate(), Files.readAllBytes(archive));
        String digest = PluginIntegrityStore.sha256Hex(archive);

        assertTrue(store.verify(archive, digest, manifest, signature, "acme-2026").trusted());
        Files.writeString(archive, "tampered");
        assertThrows(IllegalArgumentException.class,
            () -> store.verify(archive, digest, manifest, signature, "acme-2026"));
    }

    @Test void enforcesNamespaceAndRevocations() throws Exception {
        Path archive = temp.resolve("revoked.fyp");
        Files.writeString(archive, "bytes");
        String digest = PluginIntegrityStore.sha256Hex(archive);
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String signature = sign(pair.getPrivate(), Files.readAllBytes(archive));
        var key = new PluginTrustStore.PublisherKey("key", publicKey, List.of("com.acme."));

        PluginTrustStore wrongNamespace = new PluginTrustStore(
            new PluginTrustStore.TrustDocument(List.of(key), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> wrongNamespace.verify(archive, digest,
            manifest("org.other.demo", "1.0.0"), signature, "key"));

        PluginTrustStore revoked = new PluginTrustStore(new PluginTrustStore.TrustDocument(
            List.of(key), List.of(),
            List.of(new PluginTrustStore.RevokedPackage("com.acme.demo", "1.0.0", digest))));
        assertThrows(IllegalArgumentException.class, () -> revoked.verify(archive, digest,
            manifest("com.acme.demo", "1.0.0"), signature, "key"));
    }

    /** C3: a namespace prefix must not spill across a dot boundary onto sibling namespaces. */
    @Test void namespacePrefixRequiresDotBoundary() throws Exception {
        Path archive = temp.resolve("boundary.fyp");
        Files.writeString(archive, "bytes");
        String digest = PluginIntegrityStore.sha256Hex(archive);
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String signature = sign(pair.getPrivate(), Files.readAllBytes(archive));
        var key = new PluginTrustStore.PublisherKey("key", publicKey, List.of("com.acme"));

        PluginTrustStore store = new PluginTrustStore(
            new PluginTrustStore.TrustDocument(List.of(key), List.of(), List.of()));

        // The exact namespace id and true children verify...
        store.verify(archive, digest, manifest("com.acme", "1.0.0"), signature, "key");
        store.verify(archive, digest, manifest("com.acme.tools.demo", "1.0.0"), signature, "key");
        // ...but a sibling that merely SHARES THE PREFIX does not (com.acmeevil).
        assertThrows(IllegalArgumentException.class, () -> store.verify(archive, digest,
            manifest("com.acmeevil.app", "1.0.0"), signature, "key"));
    }

    private static String sign(java.security.PrivateKey key, byte[] bytes) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(bytes);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static PluginManifest manifest(String id, String version) {
        return new PluginManifest(2, id, "Demo", "Demo", version, "Acme", "puzzle", "other",
            new PluginManifest.Ui("ui/index.html"), null, List.of(), null, false, null, List.of());
    }
}
