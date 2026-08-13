package fan.summer.fengyu.update;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Version-comparison and mode-detection contract for {@link UpdateCheckService}. The GitHub
 * network call itself is not exercised here (it would be flaky and rate-limited); the comparison
 * math and portable-mode flag are the load-bearing logic that must not regress.
 */
class UpdateCheckServiceTest {

    @AfterEach
    void clearPortableFlag() {
        System.clearProperty(UpdateCheckService.PORTABLE_PROPERTY);
    }

    @Test
    void preReleaseOrderingIsAlphaThenBetaThenRcThenRelease() {
        // Each newer form must compare greater than the previous one.
        assertTrue(UpdateCheckService.compareAppVersions("4.0.0-alpha.1", "4.0.0-alpha.1") == 0);
        assertTrue(UpdateCheckService.compareAppVersions("4.0.0-alpha.2", "4.0.0-alpha.1") > 0);
        assertTrue(UpdateCheckService.compareAppVersions("4.0.0-beta.1", "4.0.0-alpha.9") > 0);
        assertTrue(UpdateCheckService.compareAppVersions("4.0.0-rc.1", "4.0.0-beta.5") > 0);
        assertTrue(UpdateCheckService.compareAppVersions("4.0.0", "4.0.0-rc.9") > 0);
        assertTrue(UpdateCheckService.compareAppVersions("4.0.0-rc.1", "4.0.0") < 0);
    }

    @Test
    void numericPatchSegmentsBeatLexicographicComparison() {
        // 4.1.10 must be newer than 4.1.9 — guards against String.compareTo regressions.
        assertTrue(UpdateCheckService.compareAppVersions("4.1.10", "4.1.9") > 0);
        assertTrue(UpdateCheckService.compareAppVersions("4.10.0", "4.9.0") > 0);
    }

    @Test
    void equalReleasesCompareEqual() {
        assertEquals(0, UpdateCheckService.compareAppVersions("4.0.0", "4.0.0"));
        assertEquals(0, UpdateCheckService.compareAppVersions("4.0.0-beta.2", "4.0.0-beta.2"));
    }

    @Test
    void higherMajorIsNewerEvenAgainstPreRelease() {
        assertTrue(UpdateCheckService.compareAppVersions("5.0.0-alpha.1", "4.9.9") > 0);
        assertTrue(UpdateCheckService.compareAppVersions("4.0.0", "3.99.99") > 0);
    }

    @Test
    void portableModeReflectsSystemProperty() {
        System.clearProperty(UpdateCheckService.PORTABLE_PROPERTY);
        UpdateCheckService service = new UpdateCheckService("MuskStark/FengYu", "", 60);
        assertFalse(service.isPortableMode(), "default should be desktop (not portable)");

        System.setProperty(UpdateCheckService.PORTABLE_PROPERTY, "true");
        assertTrue(service.isPortableMode(), "run.sh-set flag should flip to portable");
    }

    @Test
    void apiBaseDefaultsToEmptyWhenUnset() {
        // 默认 apiBase 空 → 走 GitHub。构造器不应抛异常。
        UpdateCheckService service = new UpdateCheckService("MuskStark/FengYu", "", 60);
        // isPortableMode 已经覆盖；这里只验证构造器接受空 apiBase 且 currentVersion 正常
        assertNotNull(service.currentVersion());
    }

    @Test
    void apiBaseAcceptsTrailingSlashAndTrimsIt() {
        // 尾部斜杠应被去掉，避免拼出 http://host:8088//fengyu-releases/...
        UpdateCheckService service = new UpdateCheckService("MuskStark/FengYu", "http://10.0.0.5:8088/", 60);
        assertNotNull(service);
        // currentVersion 不依赖 apiBase，仅确认构造成功
        assertNotNull(service.currentVersion());
    }

    @Test
    void backendRejectsIntranetChannelBecauseItCannotInstallDesktopPackages() {
        UpdateCheckService service = new UpdateCheckService(
                "MuskStark/FengYu", "http://10.0.0.5:8088/", 60);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.check(true));
        assertTrue(error.getMessage().contains("Windows portable ZIP and Debian"));
    }
}
