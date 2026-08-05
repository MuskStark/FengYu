package fan.summer.fengyu.plugin.browser;

import java.nio.file.Path;

/**
 * Downloads a Chromium build into {@code targetDir} using the Playwright CLI.
 * The production implementation sets {@code PLAYWRIGHT_BROWSERS_PATH=<targetDir>}
 * and invokes {@code com.microsoft.playwright.CLI install chromium}.
 *
 * <p>This is a seam: tests inject a fake that just creates the expected file, so the
 * three-tier resolution logic in {@link ChromiumResolver} can be exercised without
 * touching the network. The production implementation is
 * {@link ChromiumResolver#installViaCli(Path)}.
 */
@FunctionalInterface
public interface ChromiumInstaller {
    void install(Path targetDir) throws Exception;
}
