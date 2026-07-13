package fan.summer.fengyu.plugin.email.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/** Versioned AES-256-GCM encryption for SMTP/IMAP credentials. */
public final class CredentialCipher {
    private final SecretKey key;
    private final SecureRandom random;
    public CredentialCipher(SecretKey key) { this(key, new SecureRandom()); }
    CredentialCipher(SecretKey key, SecureRandom random) { this.key = key; this.random = random; }

    public String encrypt(String plaintext) throws GeneralSecurityException {
        byte[] nonce = new byte[12]; random.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        return "v1:" + encode(nonce) + ":" + encode(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    public String decrypt(String encoded) throws GeneralSecurityException {
        String[] parts = encoded.split(":", -1);
        if (parts.length != 3 || !"v1".equals(parts[0])) throw new GeneralSecurityException("Unsupported credential format");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, decode(parts[1])));
        return new String(cipher.doFinal(decode(parts[2])), StandardCharsets.UTF_8);
    }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static byte[] decode(String value) { return Base64.getUrlDecoder().decode(value); }
}
