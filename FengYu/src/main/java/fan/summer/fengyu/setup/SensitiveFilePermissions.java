package fan.summer.fengyu.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

/** Applies owner-only POSIX permissions to runtime files that contain secrets or key material. */
final class SensitiveFilePermissions {

    private SensitiveFilePermissions() {}

    static void protectDirectory(Path directory) throws IOException {
        set(directory, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
    }

    static void protectFile(Path file) throws IOException {
        set(file, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
    }

    private static void set(Path path, java.util.Set<PosixFilePermission> permissions)
            throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows and non-POSIX filesystems rely on their native user-profile ACLs.
        }
    }
}
