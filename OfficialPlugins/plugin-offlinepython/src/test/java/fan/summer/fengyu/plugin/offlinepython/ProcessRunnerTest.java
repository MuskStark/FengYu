package fan.summer.fengyu.plugin.offlinepython;

import org.junit.jupiter.api.Test;
import fan.summer.fengyu.plugin.offlinepython.infra.ProcessRunner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProcessRunnerTest {

    @Test
    void buildsPipDownloadCommandWithInlineSpecsForWindowsTarget() {
        List<String> cmd = ProcessRunner.pipDownloadCommand(
            "/usr/local/bin/python3.12", List.of("numpy==1.26.4"), "output/wheelhouse",
            List.of("win_amd64"), "3.12", "cp", true, true);
        assertEquals(List.of(
            "/usr/local/bin/python3.12", "-m", "pip", "download",
            "numpy==1.26.4",
            "-d", "output/wheelhouse",
            "--platform", "win_amd64",
            "--python-version", "3.12",
            "--implementation", "cp",
            "--only-binary=:all:"
        ), cmd);
    }

    @Test
    void acceptsMultipleInlineSpecsInOrder() {
        List<String> cmd = ProcessRunner.pipDownloadCommand(
            "python3", List.of("numpy==1.26.4", "requests==2.31.0"), "wh",
            List.of("win_amd64"), "3.12", "cp", true, true);
        int i = cmd.indexOf("numpy==1.26.4");
        int j = cmd.indexOf("requests==2.31.0");
        assertTrue(i > 0 && j > i);
    }

    @Test
    void omitsOnlyBinaryFlagWhenFalse() {
        List<String> cmd = ProcessRunner.pipDownloadCommand(
            "python3", List.of("numpy==1.26.4"), "wh", List.of("linux_x86_64"), "3.12", "cp", false, true);
        assertFalse(cmd.contains("--only-binary=:all:"));
        assertTrue(cmd.contains("--platform"));
        assertTrue(cmd.contains("linux_x86_64"));
    }

    @Test
    void emitsOnePlatformFlagPerSelectedPlatformInOrder() {
        List<String> cmd = ProcessRunner.pipDownloadCommand(
            "python3", List.of("numpy==1.26.4"), "wh",
            List.of("win_amd64", "manylinux2014_x86_64"), "3.12", "cp", true, true);
        int i = cmd.indexOf("--platform");
        assertEquals("win_amd64", cmd.get(i + 1));
        int j = i + 1 + cmd.subList(i + 1, cmd.size()).indexOf("--platform");
        assertEquals("manylinux2014_x86_64", cmd.get(j + 1));
        assertEquals(2, cmd.stream().filter("--platform"::equals).count());
    }

    @Test
    void fallsBackToAnyPlatformWhenSelectionEmpty() {
        List<String> cmd = ProcessRunner.pipDownloadCommand(
            "python3", List.of("numpy==1.26.4"), "wh", List.of(), "3.12", "cp", true, true);
        assertTrue(cmd.contains("--platform"));
        assertTrue(cmd.contains("any"));
    }

    @Test
    void addsNoDepsWhenRecursiveFalse() {
        List<String> cmd = ProcessRunner.pipDownloadCommand(
            "python3", List.of("numpy==1.26.4"), "wh", List.of("win_amd64"), "3.12", "cp", true, false);
        assertTrue(cmd.contains("--no-deps"));
    }

    @Test
    void omitsNoDepsWhenRecursiveTrue() {
        List<String> cmd = ProcessRunner.pipDownloadCommand(
            "python3", List.of("numpy==1.26.4"), "wh", List.of("win_amd64"), "3.12", "cp", true, true);
        assertFalse(cmd.contains("--no-deps"));
    }
}
