package fan.summer.fengyu.plugin.email.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import java.security.GeneralSecurityException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CredentialCipherTest {
    @Test void roundTripsWithFreshNoncesAndRejectsTampering() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256);
        CredentialCipher cipher = new CredentialCipher(generator.generateKey());
        String first = cipher.encrypt("mail-password");
        String second = cipher.encrypt("mail-password");
        assertNotEquals(first, second);
        assertEquals("mail-password", cipher.decrypt(first));
        String tampered = first.substring(0, first.length() - 2) + "aa";
        assertThrows(GeneralSecurityException.class, () -> cipher.decrypt(tampered));
    }

    @Test void wrongKeyCannotDecrypt() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256);
        String encrypted = new CredentialCipher(generator.generateKey()).encrypt("secret");
        CredentialCipher wrong = new CredentialCipher(generator.generateKey());
        assertThrows(GeneralSecurityException.class, () -> wrong.decrypt(encrypted));
    }
}
