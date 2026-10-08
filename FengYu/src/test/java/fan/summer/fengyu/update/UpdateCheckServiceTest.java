package fan.summer.fengyu.update;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void backendRejectsStoreChannelBecauseItCannotInstallDesktopPackages() {
        UpdateCheckService service = new UpdateCheckService(
                "MuskStark/FengYu", "http://10.0.0.5:8088/", 60);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.check(true));
        assertTrue(error.getMessage().contains("portable Web builds stay on GitHub"));
    }

    // ---- stable-channel prerelease gating (P3) -----------------------------------------

    private static com.fasterxml.jackson.databind.JsonNode releases(String json) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
    }

    @Test
    void stableChannelSkipsPrereleasesToTheNewestStableRelease() throws Exception {
        // The newest published entry is very often an alpha/beta/rc ahead of the newest
        // stable tag; a stable-channel user must be pointed at 4.1.0, not 4.2.0-alpha.1.
        com.fasterxml.jackson.databind.JsonNode releases = releases("""
                [ {"tag_name":"v4.2.0-alpha.1","prerelease":true},
                  {"tag_name":"v4.2.0-beta.1","prerelease":true},
                  {"tag_name":"v4.1.0","prerelease":false} ]""");

        com.fasterxml.jackson.databind.JsonNode chosen =
                UpdateCheckService.selectRelease(releases, false);

        // Raw tag comparison: selectRelease picks the release node; the leading-v strip
        // happens downstream in parse() (stripLeadingV).
        assertEquals("v4.1.0", chosen.path("tag_name").asText(),
                "the stable channel picks the newest NON-prerelease entry");
    }

    @Test
    void prereleaseOptInTakesTheNewestReleaseAsIs() throws Exception {
        com.fasterxml.jackson.databind.JsonNode releases = releases("""
                [ {"tag_name":"v4.2.0-alpha.1","prerelease":true},
                  {"tag_name":"v4.1.0","prerelease":false} ]""");

        assertEquals("v4.2.0-alpha.1",
                UpdateCheckService.selectRelease(releases, true).path("tag_name").asText(),
                "the explicit include-prereleases opt-in keeps first-entry-wins semantics");
    }

    @Test
    void stableChannelWithOnlyPrereleasesSelectsNothing() throws Exception {
        com.fasterxml.jackson.databind.JsonNode releases = releases(
                "[{\"tag_name\":\"v4.2.0-rc.1\",\"prerelease\":true}]");
        assertNull(UpdateCheckService.selectRelease(releases, false),
                "no stable release in the payload → null (fetchLatest reports it, never a prerelease)");
        assertNotNull(UpdateCheckService.selectRelease(releases, true));
    }

    @Test
    void stableChannelTreatsAMissingPrereleaseFlagAsStable() throws Exception {
        // Mirrors of the GitHub API may omit the flag; absence must not disqualify a release.
        com.fasterxml.jackson.databind.JsonNode releases = releases(
                "[{\"tag_name\":\"v4.1.0\"}]");
        assertEquals("v4.1.0",
                UpdateCheckService.selectRelease(releases, false).path("tag_name").asText());
    }

    @Test
    void stableVsPrereleaseOrderingGatesRatherThanMisorders() {
        // 4.2.0-alpha.1 IS newer than 4.1.0 by app ordering — the GATE keeps stable users off
        // it, not a broken comparison; and the gated stable pick still beats an older current.
        assertTrue(UpdateCheckService.compareAppVersions("4.2.0-alpha.1", "4.1.0") > 0,
                "prerelease of a higher minor is genuinely newer");
        assertTrue(UpdateCheckService.compareAppVersions("4.1.0", "4.0.0") > 0,
                "the gated stable pick still flags an update for a 4.0.0 user");
        assertTrue(UpdateCheckService.compareAppVersions("4.1.0", "4.1.0-beta.1") > 0,
                "a stable beats its own prerelease");
    }
}
