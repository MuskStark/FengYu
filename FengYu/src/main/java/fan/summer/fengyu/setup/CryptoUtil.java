package fan.summer.fengyu.setup;

import fan.summer.fengyu.runtime.RuntimePaths;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Lightweight AES-GCM encryption for sensitive datasource config fields (e.g. db.password).
 *
 * <p>Key derivation: a fixed project constant XOR'd with a per-machine random UUID
 * (stored at {@code <programWorkingDirectory>/.fengyu/config/.machineid}), SHA-256'd to a
 * 256-bit AES key.
 * This means an encrypted config file cannot be decrypted on a different machine,
 * reducing the value of a stolen config file.
 *
 * <p>Encrypted values are prefixed with {@code ENC(...)} so {@link #decrypt} can detect
 * them; values without the prefix are returned as-is (supports hand-written plaintext configs).
 */
public final class CryptoUtil {

    private static final String PREFIX = "ENC(";
    private static final String SUFFIX = ")";
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private static final String PROJECT_CONSTANT = "FengYu-4.0-Phase4-SetupKey";

    private CryptoUtil() {}

    private static SecretKeySpec deriveKey(Path machineIdFile) {
        try {
            Files.createDirectories(machineIdFile.getParent());
            SensitiveFilePermissions.protectDirectory(machineIdFile.getParent());
            String machineId;
            if (Files.exists(machineIdFile)) {
                SensitiveFilePermissions.protectFile(machineIdFile);
                machineId = Files.readString(machineIdFile).trim();
            } else {
                machineId = UUID.randomUUID().toString();
                Files.writeString(machineIdFile, machineId);
                SensitiveFilePermissions.protectFile(machineIdFile);
            }
            String material = PROJECT_CONSTANT + ":" + machineId;
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, ALGORITHM);
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive crypto key", e);
        }
    }

    public static String encrypt(String plain) {
        return encrypt(plain, RuntimePaths.configDirectory(RuntimePaths.root()).resolve(".machineid"));
    }

    static String encrypt(String plain, Path machineIdFile) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(machineIdFile), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            // Prepend IV to cipher text, base64 the whole thing.
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            String b64 = Base64.getEncoder().encodeToString(combined);
            return PREFIX + b64 + SUFFIX;
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public static String decrypt(String value) {
        return decrypt(value, RuntimePaths.configDirectory(RuntimePaths.root()).resolve(".machineid"));
    }

    static String decrypt(String value, Path machineIdFile) {
        if (value == null || value.isBlank()) return value;
        if (!value.startsWith(PREFIX) || !value.endsWith(SUFFIX)) {
            return value;   // plaintext passthrough
        }
        try {
            String b64 = value.substring(PREFIX.length(), value.length() - SUFFIX.length());
            byte[] combined = Base64.getDecoder().decode(b64);
            byte[] iv = new byte[IV_BYTES];
            byte[] cipherText = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            System.arraycopy(combined, IV_BYTES, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(machineIdFile), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(cipherText);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
