package fan.summer.fengyu.setup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CryptoUtilTest {

    @Test
    void encrypt_decrypt_roundtrip_recoversPlaintext() {
        String plain = "mySecretPassword123!";
        String cipher = CryptoUtil.encrypt(plain);
        assertNotEquals(plain, cipher);
        assertTrue(cipher.startsWith("ENC("));
        assertEquals(plain, CryptoUtil.decrypt(cipher));
    }

    @Test
    void decrypt_plainTextWithoutPrefix_returnsAsIs() {
        // Plaintext without ENC(...) prefix is returned as-is (backward compat for hand-written config)
        assertEquals("rawPassword", CryptoUtil.decrypt("rawPassword"));
    }

    @Test
    void encrypt_emptyString_stillEncrypted() {
        String cipher = CryptoUtil.encrypt("");
        assertTrue(cipher.startsWith("ENC("));
        assertEquals("", CryptoUtil.decrypt(cipher));
    }

    @Test
    void decrypt_nullOrBlank_returnsInput() {
        assertNull(CryptoUtil.decrypt(null));
        assertEquals("", CryptoUtil.decrypt(""));
    }

    @Test
    void machineKeyIsTheKeychainIntegrationPoint() throws Exception {
        // The property (set from an OS keychain by a launcher wrapper) fully replaces the
        // .machineid file as the key material: round-trip works, is stable while the key is
        // stable, and a DIFFERENT key cannot decrypt (off-machine semantics preserved).
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("crypto-injected-key");
        java.nio.file.Path machineId = dir.resolve(".machineid");

        System.setProperty(CryptoUtil.MACHINE_KEY_PROPERTY, "keychain-supplied-secret-material");
        try {
            String cipher = CryptoUtil.encrypt("secret", machineId);
            assertTrue(cipher.startsWith("ENC("));
            assertEquals("secret", CryptoUtil.decrypt(cipher, machineId));
            // encrypt() is AES-GCM with a random IV (deliberately NOT reproducible); the
            // deterministic member is deriveMachineSecret, which backs plugin DB credentials.
            assertEquals(CryptoUtil.deriveMachineSecret("plugin-db:x", machineId),
                    CryptoUtil.deriveMachineSecret("plugin-db:x", machineId));
            System.setProperty(CryptoUtil.MACHINE_KEY_PROPERTY, "a-totally-different-key!");
            assertThrows(RuntimeException.class, () -> CryptoUtil.decrypt(cipher, machineId),
                    "a different key must not decrypt the first key's ciphertext");
            assertFalse(java.nio.file.Files.exists(machineId),
                    "the .machineid file must not even be created while a key is injected");
        } finally {
            System.clearProperty(CryptoUtil.MACHINE_KEY_PROPERTY);
        }

        // Without the injected key the file fallback still works on the same directory.
        assertNull(System.getProperty(CryptoUtil.MACHINE_KEY_PROPERTY));
        String cipher = CryptoUtil.encrypt("fallback", machineId);
        assertEquals("fallback", CryptoUtil.decrypt(cipher, machineId));
        assertTrue(java.nio.file.Files.exists(machineId));
    }

    @Test
    void machineKeyMustBeStrongEnough() {
        System.setProperty(CryptoUtil.MACHINE_KEY_PROPERTY, "short");
        try {
            RuntimeException rejected = assertThrows(RuntimeException.class,
                    () -> CryptoUtil.encrypt("x", java.nio.file.Path.of("/tmp/nonexistent-mid")));
            // The reason is wrapped several layers deep (encrypt → derive → validate).
            Throwable root = rejected;
            while (root.getCause() != null) root = root.getCause();
            assertTrue(String.valueOf(root.getMessage()).contains("16"),
                    "message was: " + root.getMessage());
        } finally {
            System.clearProperty(CryptoUtil.MACHINE_KEY_PROPERTY);
        }
    }
}
