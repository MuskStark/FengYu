package fan.summer.fengyu.plugin.offlinepython;

import org.junit.jupiter.api.Test;
import fan.summer.fengyu.plugin.offlinepython.command.DepsService;
import fan.summer.fengyu.plugin.offlinepython.command.WheelInfo;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DepsServiceTest {

    @Test
    void parsesLatestVersionFromPipIndexVersions() {
        String out = "numpy (1.26.4)\nAvailable versions: 1.26.4, 1.26.3, 1.26.2";
        assertEquals(Optional.of("1.26.4"), DepsService.parseLatestVersion(out));
    }

    @Test
    void parseLatestVersionEmptyOnGarbage() {
        assertTrue(DepsService.parseLatestVersion("no versions found").isEmpty());
        assertTrue(DepsService.parseLatestVersion((String) null).isEmpty());
    }

    @Test
    void parsesWheelSizeFromPypiJsonForPlatform() {
        String json = """
            { "urls": [
              {"filename":"numpy-1.26.4-cp312-cp312-win_amd64.whl","size":19098624},
              {"filename":"numpy-1.26.4.tar.gz","size":1000}
            ]}
            """;
        long size = DepsService.parsePyPIWheelSize(json, "win_amd64", "1.26.4");
        assertEquals(19098624L, size);
    }

    @Test
    void parsePyPIWheelSizeFallsBackToZero() {
        assertEquals(0L, DepsService.parsePyPIWheelSize("{}", "win_amd64", "1.26.4"));
        assertEquals(0L, DepsService.parsePyPIWheelSize((String) null, "win_amd64", "1.26.4"));
    }

    @Test
    void parsePyPIWheelSizePicksAnyWheelIfPlatformMisses() {
        String json = """
            { "urls": [
              {"filename":"numpy-1.26.4-cp312-cp312-manylinux.whl","size":17000000},
              {"filename":"numpy-1.26.4.tar.gz","size":1000}
            ]}
            """;
        assertEquals(17000000L, DepsService.parsePyPIWheelSize(json, "win_amd64", "1.26.4"));
    }

    @Test
    void parsesWheelsFromPypiReleasesJson() {
        String json = """
            { "releases": {
              "1.26.4": [
                {"filename":"numpy-1.26.4-cp312-cp312-win_amd64.whl","size":19098624},
                {"filename":"numpy-1.26.4.tar.gz","size":1000}
              ],
              "1.25.2": [
                {"filename":"numpy-1.25.2-cp312-cp312-manylinux2014_x86_64.whl","size":17800000}
              ]
            }}""";
        List<WheelInfo> wheels = DepsService.parseWheels(json);
        assertEquals(2, wheels.size()); // .tar.gz 被过滤
        assertTrue(wheels.stream().anyMatch(w ->
                w.platformTag().equals("win_amd64") && w.version().equals("1.26.4")));
        assertTrue(wheels.stream().anyMatch(w ->
                w.platformTag().equals("manylinux2014_x86_64") && w.version().equals("1.25.2")));
    }

    @Test
    void parseWheelsEmptyOnGarbage() {
        assertTrue(DepsService.parseWheels("").isEmpty());
        assertTrue(DepsService.parseWheels("not json").isEmpty());
        assertTrue(DepsService.parseWheels(null).isEmpty());
    }

    @Test
    void extractPlatformTagFromWheelFilename() {
        assertEquals("win_amd64",
                DepsService.extractPlatformTag("numpy-1.26.4-cp312-cp312-win_amd64.whl"));
        assertEquals("manylinux2014_x86_64",
                DepsService.extractPlatformTag("numpy-1.26.4-cp312-cp312-manylinux2014_x86_64.whl"));
        assertEquals("any",
                DepsService.extractPlatformTag("pkg-1.0-py3-none-any.whl"));
        // 目录外标签原样透传（pip 仍接受）
        assertEquals("manylinux_2_28_x86_64",
                DepsService.extractPlatformTag("numpy-2.0-pp39-pypy39_pp73-manylinux_2_28_x86_64.whl"));
    }
}
