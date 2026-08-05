package fan.summer.fengyu.plugin.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ChromiumResolverTest {

    @Test
    void tier1UserConfigPathWins(@TempDir Path tmp) throws Exception {
        Path userBrowser = tmp.resolve("user-chrome");
        Files.writeString(userBrowser, "fake");   // any existing file
        // Tier 1 uses Files.isExecutable; a written file lacks the exec bit, so set it.
        assertTrue(userBrowser.toFile().setExecutable(true),
                "test setup: could not make userBrowser executable");
        ChromiumResolver resolver = new ChromiumResolver(
                () -> userBrowser.toString(),          // tier 1: user config
                tmp.resolve("data"),                  // tier 2: plugin data dir
                false,                                // isWindows
                dir -> fail("should not download"));  // tier 3 never reached
        assertEquals(userBrowser.toString(), resolver.resolve());
    }

    @Test
    void tier2AlreadyDownloadedChromium(@TempDir Path tmp) throws Exception {
        Path dataDir = tmp.resolve("data");
        Path chrome = dataDir.resolve("chromium/chromium-1/chrome");
        Files.createDirectories(chrome.getParent());
        Files.createFile(chrome);

        ChromiumResolver resolver = new ChromiumResolver(
                () -> null,          // no user config
                dataDir,
                false,              // linux executable name "chrome"
                dir -> fail("should not download"));
        // resolve returns a path pointing at the existing chrome binary
        String resolved = resolver.resolve();
        assertTrue(resolved.endsWith("chrome"), "resolved=" + resolved);
    }

    @Test
    void tier3DownloadsWhenMissing(@TempDir Path tmp) throws Exception {
        Path dataDir = tmp.resolve("data");
        int[] installCalls = {0};
        // Simulate: after install(), the chrome binary now exists in the expected location.
        // The installer receives chromiumRoot = <dataDir>/chromium; the canonical on-disk
        // layout (matching tier 2) is <chromiumRoot>/chromium-<ver>/chrome.exe.
        ChromiumResolver resolver = new ChromiumResolver(
                () -> null,
                dataDir,
                true,                       // isWindows → chrome.exe
                dir -> {
                    installCalls[0]++;
                    Path chrome = dir.resolve("chromium-1/chrome.exe");
                    Files.createDirectories(chrome.getParent());
                    Files.createFile(chrome);
                });
        String resolved = resolver.resolve();
        assertEquals(1, installCalls[0], "installer invoked exactly once");
        assertTrue(resolved.endsWith("chrome.exe"), "resolved=" + resolved);
    }

    @Test
    void tier3SkippedWhenUserConfigBlankAndReturnsNullIfStillMissing(@TempDir Path tmp) {
        ChromiumResolver resolver = new ChromiumResolver(
                () -> "",    // blank config = no tier 1
                tmp.resolve("data"),
                false,
                dir -> { /* no-op: don't create anything */ });
        // Nothing exists and download is a no-op → return null so Playwright uses its default.
        assertNull(resolver.resolve());
    }
}
