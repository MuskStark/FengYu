package fan.summer.fengyu.ai.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Path jail for the workspace file tools. Every model-supplied path resolves through
 * {@link #resolve(Path, String)} before any IO: relative paths resolve against the workspace
 * root, absolute paths are accepted only when they land inside it, and symbolic links are
 * collapsed against the real root before the containment check so a link planted inside the
 * tree can never redirect a read or write outside it.
 */
public final class WorkspacePathPolicy {

    private WorkspacePathPolicy() {}

    /** Thrown when a model-supplied path is malformed or escapes the workspace root. */
    public static class EscapeException extends RuntimeException {
        public EscapeException(String message) {
            super(message);
        }
    }

    /**
     * Resolve one model-supplied path against {@code root}, rejecting anything that leaves the
     * root after normalization and symlink collapsing. The target itself need not exist yet
     * ({@code write_file} creates files); only the deepest existing ancestor is canonicalized.
     */
    public static Path resolve(Path root, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new EscapeException("Path must not be blank");
        }
        String cleaned = rawPath.strip().replace("\\", "/");
        if (cleaned.startsWith("~/")) {
            cleaned = System.getProperty("user.home") + cleaned.substring(1);
        }
        Path supplied = Path.of(cleaned);
        Path normalized = (supplied.isAbsolute() ? supplied : root.resolve(supplied)).normalize();
        if (normalized.startsWith("..") || !normalized.isAbsolute()) {
            throw new EscapeException("Path escapes the workspace root: " + rawPath);
        }

        Path realRoot = realExisting(root, "workspace root");
        // Canonicalize the longest existing prefix so symlinked directories collapse before the
        // containment check; the non-existing tail is appended unchanged.
        Path current = normalized;
        java.util.List<String> tail = new java.util.ArrayList<>();
        while (current != null) {
            if (Files.exists(current)) {
                Path real = realExisting(current, rawPath);
                Path target = tail.isEmpty() ? real
                        : real.resolve(String.join("/", tail));
                if (!target.startsWith(realRoot)) {
                    throw new EscapeException(
                            "Path escapes the workspace root (after resolving links): " + rawPath);
                }
                return target;
            }
            if (current.getFileName() != null) tail.add(0, current.getFileName().toString());
            current = current.getParent();
        }
        throw new EscapeException("Path escapes the workspace root: " + rawPath);
    }

    /** Workspace-relative display form; absolute only when the file is somehow outside (defensive). */
    public static String display(Path root, Path file) {
        return file.startsWith(root)
                ? root.relativize(file).toString().replace('\\', '/')
                : file.toString();
    }

    private static Path realExisting(Path path, String what) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new EscapeException("Cannot resolve " + what + ": " + path);
        }
    }
}
