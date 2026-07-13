package fan.summer.fengyu.plugin.email.crypto;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.Set;

/** Loads or atomically creates the plugin-local AES key. */
public final class PluginKeyStore {
    private final Path keyFile;
    public PluginKeyStore(Path dataDirectory) { this.keyFile = dataDirectory.resolve("credential.key"); }

    public SecretKey loadOrCreate() {
        try {
            Files.createDirectories(keyFile.getParent());
            if (Files.exists(keyFile)) return decode(Files.readString(keyFile).trim());
            KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256);
            SecretKey key = generator.generateKey();
            Path temporary = Files.createTempFile(keyFile.getParent(), "credential", ".tmp");
            Files.writeString(temporary, Base64.getEncoder().encodeToString(key.getEncoded()));
            try { Files.setPosixFilePermissions(temporary, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
            catch (UnsupportedOperationException ignored) { }
            try { Files.move(temporary, keyFile, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                try { Files.move(temporary, keyFile); }
                catch (java.nio.file.FileAlreadyExistsException race) { Files.deleteIfExists(temporary); }
            } catch (java.nio.file.FileAlreadyExistsException race) { Files.deleteIfExists(temporary); }
            return Files.exists(keyFile) ? decode(Files.readString(keyFile).trim()) : key;
        } catch (Exception e) { throw new IllegalStateException("Cannot load email credential key", e); }
    }

    private static SecretKey decode(String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length != 32) throw new IllegalStateException("Email credential key has invalid length");
        return new SecretKeySpec(bytes, "AES");
    }
}
