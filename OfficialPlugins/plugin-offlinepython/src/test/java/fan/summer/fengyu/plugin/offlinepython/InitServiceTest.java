package fan.summer.fengyu.plugin.offlinepython;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import fan.summer.fengyu.plugin.offlinepython.command.InitService;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InitServiceTest {

    @Test
    void writesSkeletonFiles(@TempDir Path project) throws Exception {
        InitService init = new InitService();
        init.initialize(project);

        assertTrue(Files.exists(project.resolve("config.json")));
        assertTrue(Files.exists(project.resolve("requirements.txt")));
        assertTrue(Files.exists(project.resolve("README.md")));

        String req = Files.readString(project.resolve("requirements.txt"));
        assertTrue(req.contains("# Add packages, one per line"));

        String cfg = Files.readString(project.resolve("config.json"));
        assertTrue(cfg.contains("\"version\"") && cfg.contains("3.12.10"));
    }

    @Test
    void doesNotOverwriteExistingRequirements(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("requirements.txt"), "numpy==1.0\n");
        new InitService().initialize(project);
        assertEquals("numpy==1.0\n", Files.readString(project.resolve("requirements.txt")));
    }
}
