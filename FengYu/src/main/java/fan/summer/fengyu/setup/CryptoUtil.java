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
 * <p><b>OS keychain integration point.</b> The key material can instead be supplied via the
 * {@code FENGYU_MACHINE_KEY} environment variable (checked before the {@code .machineid}
 * file). Deployments that keep secrets in the operating system's credential store inject it
 * through a launcher wrapper, e.g. macOS:
 * <pre>
 *   export FENGYU_MACHINE_KEY="$(security find-generic-password -s FengYu -a machine-key -w)"
 * </pre>
 * Linux: {@code secret-tool lookup fengyu machine-key}; Windows: a Credential Manager read
 * via PowerShell in the run script. Requirements: 16+ characters, stable across restarts
 * (every encrypted value — datasource passwords, provider API keys, MCP secrets — is bound
 * to it). This is the supported hook for keychain-backed deployments; without it the
 * machine-bound {@code .machineid} file remains the (documented) fallback, whose key sits
 * on the same disk as the ciphertext and therefore guards against off-machine use of a
 * stolen file, not against a same-user reader.
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

    /**
     * Overrides the {@code .machineid} file as the key material source — the OS-keychain
     * integration point (see the class javadoc for launcher examples). Read from the system
     * property first ({@code -DFENGYU_MACHINE_KEY=...}) and then the environment variable;
     * both must be 16+ characters and stable across restarts.
     */
    public static final String MACHINE_KEY_PROPERTY = "FENGYU_MACHINE_KEY";

    private static String injectedMachineKey() {
        String value = System.getProperty(MACHINE_KEY_PROPERTY);
        if (value == null || value.isBlank()) value = System.getenv(MACHINE_KEY_PROPERTY);
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() < 16) {
            throw new RuntimeException(MACHINE_KEY_PROPERTY + " must be at least 16 characters"
                    + " (every encrypted value is bound to it)");
        }
        return "env:" + value.trim();
    }

    private static String machineId(Path machineIdFile) {
        String injected = injectedMachineKey();
        if (injected != null) return injected;
        try {
            Files.createDirectories(machineIdFile.getParent());
            SensitiveFilePermissions.protectDirectory(machineIdFile.getParent());
            if (Files.exists(machineIdFile)) {
                SensitiveFilePermissions.protectFile(machineIdFile);
                return Files.readString(machineIdFile).trim();
            }
            String machineId = UUID.randomUUID().toString();
            Files.writeString(machineIdFile, machineId);
            SensitiveFilePermissions.protectFile(machineIdFile);
            return machineId;
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive crypto key", e);
        }
    }

    private static SecretKeySpec deriveKey(Path machineIdFile) {
        return new SecretKeySpec(machineKeyBytes(machineIdFile), ALGORITHM);
    }

    private static byte[] machineKeyBytes(Path machineIdFile) {
        try {
            String material = PROJECT_CONSTANT + ":" + machineId(machineIdFile);
            return MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive crypto key", e);
        }
    }

    /**
     * Deterministic, machine-bound secret for {@code context}. Unlike {@link #encrypt} (whose
     * random IV makes every ciphertext unique), this is reproducible across restarts — required
     * when the secret provisions a persistent artifact (e.g. a per-plugin embedded database
     * whose file keeps the user it was created with).
     */
    public static String deriveMachineSecret(String context) {
        return deriveMachineSecret(context,
                RuntimePaths.configDirectory(RuntimePaths.root()).resolve(".machineid"));
    }

    static String deriveMachineSecret(String context, Path machineIdFile) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(machineKeyBytes(machineIdFile), "HmacSHA256"));
            byte[] out = mac.doFinal(context.getBytes(StandardCharsets.UTF_8));
            return "fy_" + java.util.HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new RuntimeException("Secret derivation failed", e);
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
