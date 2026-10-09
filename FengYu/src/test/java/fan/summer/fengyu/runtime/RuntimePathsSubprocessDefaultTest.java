package fan.summer.fengyu.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link RuntimePaths#subprocessDefaultWorkingDirectory()} pins the contract the AI
 * command tool and the approval gate rely on: desktop boots (whose process cwd moved to
 * the jar dir for the cached AOT launch) must default into the PINNED runtime root —
 * the cwd every desktop launch had before the startup cache — while bare CLI runs keep
 * the shell's working directory exactly as before.
 */
class RuntimePathsSubprocessDefaultTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty(RuntimePaths.ROOT_PROPERTY);
        System.clearProperty("fengyu.desktop");
    }

    @Test
    void desktopModeUsesThePinnedRuntimeRoot() {
        System.setProperty(RuntimePaths.ROOT_PROPERTY, "/opt/fengyu-state");
        System.setProperty("fengyu.desktop", "true");
        assertEquals(Path.of("/opt/fengyu-state"), RuntimePaths.subprocessDefaultWorkingDirectory());
    }

    @Test
    void desktopModeWithoutAPinFallsBackToTheProcessCwd() {
        // Defensive branch: a desktop spawn always pins, but a future caller must not
        // crash into a null path if that invariant ever drifts.
        System.setProperty("fengyu.desktop", "true");
        assertEquals(Path.of(System.getProperty("user.dir")), RuntimePaths.subprocessDefaultWorkingDirectory());
    }

    @Test
    void bareCliKeepsTheShellWorkingDirectoryEvenWhenRootWasNormalized() {
        // HeadlessLauncher's static init sets fengyu.runtime.dir unconditionally (to the
        // derived <cwd>/.fengyu when unpinned) — that must NOT hijack the CLI default.
        System.setProperty(RuntimePaths.ROOT_PROPERTY, Path.of(System.getProperty("user.dir"), ".fengyu").toString());
        assertEquals(Path.of(System.getProperty("user.dir")), RuntimePaths.subprocessDefaultWorkingDirectory());
    }
}
