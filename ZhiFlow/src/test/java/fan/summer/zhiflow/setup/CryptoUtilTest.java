package fan.summer.zhiflow.setup;

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
}
