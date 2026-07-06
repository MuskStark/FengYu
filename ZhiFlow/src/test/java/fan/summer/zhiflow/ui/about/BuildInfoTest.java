package fan.summer.zhiflow.ui.about;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class BuildInfoTest {

    @Test
    void valueReturnsRealValueWhenPresent() {
        Properties p = new Properties();
        p.setProperty("app.version", "3.1.0");
        assertEquals("3.1.0", new BuildInfo(p).value("app.version", BuildInfo.DEV_VERSION));
    }

    @Test
    void fallsBackWhenKeyMissing() {
        BuildInfo info = new BuildInfo(new Properties());
        assertEquals(BuildInfo.DEV_VERSION, info.value("app.version", BuildInfo.DEV_VERSION));
        assertEquals(BuildInfo.DEV_BUILD_TIME, info.value("build.time", BuildInfo.DEV_BUILD_TIME));
    }

    @Test
    void fallsBackWhenUnfilteredPlaceholderRemains() {
        Properties p = new Properties();
        p.setProperty("app.version", "${project.version}");
        p.setProperty("build.time", "${maven.build.timestamp}");
        BuildInfo info = new BuildInfo(p);
        assertEquals(BuildInfo.DEV_VERSION, info.value("app.version", BuildInfo.DEV_VERSION));
        assertEquals(BuildInfo.DEV_BUILD_TIME, info.value("build.time", BuildInfo.DEV_BUILD_TIME));
    }

    @Test
    void getVersionReadsClasspathFixture() {
        // src/test/resources/build-info.properties shadows the main template on the
        // test classpath, so INSTANCE loads the fixture (full path check).
        assertEquals("9.9.9-test", BuildInfo.getVersion());
        assertEquals("2026-01-01 00:00 UTC", BuildInfo.getBuildTime());
    }

    @Test
    void formatsIsoBuildTimeAsLocalZoned() {
        // The packaged JAR emits ISO-8601 because Maven's maven.build.timestamp.format
        // does not apply to ${maven.build.timestamp} inside filtered resources.
        // formatBuildTime must convert it to "yyyy-MM-dd HH:mm z".
        BuildInfo info = new BuildInfo(new Properties());
        String formatted = info.formatBuildTime("2026-06-22T09:23:11Z");
        assertNotEquals("2026-06-22T09:23:11Z", formatted);
        assertTrue(formatted.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} .+"),
            "expected 'yyyy-MM-dd HH:mm z', got: " + formatted);
    }

    @Test
    void formatBuildTimePassesThroughNonIso() {
        BuildInfo info = new BuildInfo(new Properties());
        // human-readable fixture value → unchanged
        assertEquals("2026-01-01 00:00 UTC", info.formatBuildTime("2026-01-01 00:00 UTC"));
        // dev fallback → unchanged
        assertEquals(BuildInfo.DEV_BUILD_TIME, info.formatBuildTime(BuildInfo.DEV_BUILD_TIME));
    }
}
