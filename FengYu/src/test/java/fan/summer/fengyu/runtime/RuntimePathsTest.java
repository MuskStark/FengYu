package fan.summer.fengyu.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimePathsTest {

    @Test
    void defaultsToHiddenRuntimeDirectoryUnderProgramWorkingDirectory() {
        Path root = RuntimePaths.resolveRoot("", "/opt/infinia");

        assertEquals(Path.of("/opt/infinia/.fengyu").toAbsolutePath().normalize(), root);
    }

    @Test
    void explicitRuntimeDirectoryOverridesWorkingDirectory() {
        Path root = RuntimePaths.resolveRoot("/data/infinia", "/opt/infinia");

        assertEquals(Path.of("/data/infinia").toAbsolutePath().normalize(), root);
    }

    @Test
    void allRuntimeDirectoriesAreChildrenOfOneRoot() {
        Path root = Path.of("/data/infinia").toAbsolutePath().normalize();

        assertEquals(root.resolve("config"), RuntimePaths.configDirectory(root));
        assertEquals(root.resolve("database"), RuntimePaths.databaseDirectory(root));
        assertEquals(root.resolve("logs"), RuntimePaths.logDirectory(root));
        assertEquals(root.resolve("plugins"), RuntimePaths.pluginDirectory(root));
        assertEquals(root.resolve("plugin-data"), RuntimePaths.pluginDataDirectory(root));
        assertEquals(root.resolve("skills"), RuntimePaths.skillDirectory(root));
        assertEquals(root.resolve("runtime-files"), RuntimePaths.runtimeFilesDirectory(root));
    }
}
