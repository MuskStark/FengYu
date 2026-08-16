package fan.summer.fengyu.update;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ed25519 release-signature verification (P3: asymmetric update-source signing). The public
 * key ships as an OPTIONAL classpath resource ({@code /update/release-signing-public.pem});
 * while absent, verification stays checksum-only. These tests pin both modes through the
 * key-explicit overload {@link SelfUpdateService#verifyReleaseSignature(byte[], byte[], byte[])}.
 */
class SelfUpdateSignatureTest {

    private static final byte[] CHECKSUMS =
            "abc123  Infinia.jar\n".getBytes(StandardCharsets.UTF_8);

    private static KeyPair ed25519() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static byte[] sign(KeyPair pair, byte[] data) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(data);
        return signer.sign();
    }

    @Test
    void noBundledKeyMeansChecksumOnlyVerification() {
        // This repository does not bundle a signing key yet — the resource is absent, so any
        // signature state passes without failing (historical behavior preserved).
        assertNull(SelfUpdateService.loadSigningPublicKey(),
            "no key is bundled in this build; update this test when one ships");
        assertDoesNotThrow(() -> SelfUpdateService.verifyReleaseSignature(CHECKSUMS, null));
        assertDoesNotThrow(() ->
                SelfUpdateService.verifyReleaseSignature(CHECKSUMS, new byte[] {1, 2, 3}));
    }

    @Test
    void validSignatureVerifies() throws Exception {
        KeyPair release = ed25519();
        byte[] signature = sign(release, CHECKSUMS);
        assertDoesNotThrow(() -> SelfUpdateService.verifyReleaseSignature(
                release.getPublic().getEncoded(), CHECKSUMS, signature));
    }

    @Test
    void signatureOverDifferentBytesIsRejected() throws Exception {
        KeyPair release = ed25519();
        byte[] signature = sign(release,
                "evil999  Infinia.jar\n".getBytes(StandardCharsets.UTF_8));
        IllegalStateException rejected = assertThrows(IllegalStateException.class, () ->
                SelfUpdateService.verifyReleaseSignature(
                        release.getPublic().getEncoded(), CHECKSUMS, signature));
        assertTrue(rejected.getMessage().contains("INVALID"), "message was: " + rejected.getMessage());
    }

    @Test
    void signatureByADifferentKeyIsRejected() throws Exception {
        KeyPair release = ed25519();
        KeyPair attacker = ed25519();
        byte[] signature = sign(attacker, CHECKSUMS);
        assertThrows(IllegalStateException.class, () ->
                SelfUpdateService.verifyReleaseSignature(
                        release.getPublic().getEncoded(), CHECKSUMS, signature));
    }

    @Test
    void missingSignatureFailsClosedWhenAKeyIsBundled() throws Exception {
        KeyPair release = ed25519();
        IllegalStateException missing = assertThrows(IllegalStateException.class, () ->
                SelfUpdateService.verifyReleaseSignature(
                        release.getPublic().getEncoded(), CHECKSUMS, null));
        assertTrue(missing.getMessage().contains("signature required"),
                "message was: " + missing.getMessage());
    }

    @Test
    void malformedPublicKeyFailsClosed() {
        assertThrows(IllegalStateException.class, () ->
                SelfUpdateService.verifyReleaseSignature(
                        "not-a-key".getBytes(StandardCharsets.UTF_8), CHECKSUMS, new byte[] {1}));
    }
}
