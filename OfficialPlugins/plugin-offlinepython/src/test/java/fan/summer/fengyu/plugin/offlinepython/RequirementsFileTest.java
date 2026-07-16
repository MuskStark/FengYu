package fan.summer.fengyu.plugin.offlinepython;

import org.junit.jupiter.api.Test;
import fan.summer.fengyu.plugin.offlinepython.domain.DependencySpec;
import fan.summer.fengyu.plugin.offlinepython.domain.RequirementsFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequirementsFileTest {

    @Test
    void parsesPinnedVersion() {
        DependencySpec d = DependencySpec.parse("numpy==1.26.4");
        assertEquals("numpy", d.name());
        assertEquals("==1.26.4", d.versionSpec());
        assertNull(d.marker());
    }

    @Test
    void parsesMinimumVersion() {
        DependencySpec d = DependencySpec.parse("requests>=2.31");
        assertEquals("requests", d.name());
        assertEquals(">=2.31", d.versionSpec());
    }

    @Test
    void parsesPlatformMarker() {
        DependencySpec d = DependencySpec.parse("flask==3.0.0 ; sys_platform == \"linux\"");
        assertEquals("flask", d.name());
        assertEquals("==3.0.0", d.versionSpec());
        assertEquals("sys_platform == \"linux\"", d.marker());
    }

    @Test
    void parsesNameOnlyDefaultsToLatest() {
        DependencySpec d = DependencySpec.parse("scipy");
        assertEquals("scipy", d.name());
        assertEquals("", d.versionSpec());
    }

    @Test
    void ignoresCommentsAndBlankLines() {
        List<DependencySpec> deps = RequirementsFile.parse("""
            # comment line
            numpy==1.26.4

            requests>=2.31
            """);
        assertEquals(2, deps.size());
        assertEquals("numpy", deps.get(0).name());
    }

    @Test
    void roundTripsThroughWrite() {
        List<DependencySpec> deps = List.of(
            new DependencySpec("numpy", "==1.26.4", null),
            new DependencySpec("flask", "==3.0.0", "sys_platform == \"linux\""));
        String written = RequirementsFile.write(deps);
        List<DependencySpec> reparsed = RequirementsFile.parse(written);
        assertEquals(deps, reparsed);
    }
}
