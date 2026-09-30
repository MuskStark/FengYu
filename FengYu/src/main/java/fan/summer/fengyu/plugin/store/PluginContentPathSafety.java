package fan.summer.fengyu.plugin.store;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Path-traversal guards for copying agent-content files into the runtime tree.
 *
 * <p>Plugin {@code name} values arrive verbatim from third-party Claude/Codex marketplace JSON and
 * flow into filesystem paths ({@code skills/<uid>}, {@code mcp-servers/<uid>.json}) that are both
 * deleted and written. {@link #slugify(String)} normalizes such a name to a single safe path
 * segment before it ever reaches the filesystem; {@link #isInside(Path, Path)} is the defense-in-
 * depth check applied at write/delete time.
 */
final class PluginContentPathSafety {
    private PluginContentPathSafety() {}

    /** True if {@code candidate} is inside {@code base} (after normalization). */
    static boolean isInside(Path base, Path candidate) {
        Path n = candidate.toAbsolutePath().normalize();
        Path b = base.toAbsolutePath().normalize();
        return n.startsWith(b);
    }

    /**
     * Normalizes a plugin {@code name} (from untrusted marketplace JSON) into a single filesystem-
     * safe segment: lower-cased ASCII alphanumeric with {@code .}, {@code -}, {@code _} kept, every
     * other run of characters collapsed to a single {@code -}. Empty result becomes {@code plugin}.
     *
     * <p>Rejecting path separators, {@code ..}, and odd characters here means the resulting
     * {@code uid} can never traverse out of the runtime tree when used as a path segment. This
     * mirrors what {@code StoreSourceRegistry.normalizeOrigin} already does for the origin half of
     * the uid; both halves are now equally trustworthy.
     */
    static String slugify(String name) {
        if (name == null) return "plugin";
        String slug = name.toLowerCase(Locale.ROOT).trim()
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^[-._]+|[-._]+$", "");
        return slug.isEmpty() ? "plugin" : slug;
    }

    /**
     * A safe single segment is non-empty, contains no path separator, is not {@code .} or {@code ..},
     * and uses only the allowlisted characters. Used to assert a uid segment before it becomes a path.
     */
    static boolean isSafeSegment(String segment) {
        if (segment == null || segment.isEmpty()) return false;
        if (".".equals(segment) || "..".equals(segment)) return false;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '.' || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }
}
