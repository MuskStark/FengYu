package fan.summer.fengyu.plugin.store;

import java.nio.file.Path;

/** Path-traversal guards for copying agent-content files into the runtime tree. */
final class PluginContentPathSafety {
    private PluginContentPathSafety() {}

    /** True if {@code candidate} is inside {@code base} (after normalization). */
    static boolean isInside(Path base, Path candidate) {
        Path n = candidate.toAbsolutePath().normalize();
        Path b = base.toAbsolutePath().normalize();
        return n.startsWith(b);
    }
}
