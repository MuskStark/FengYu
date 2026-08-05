package fan.summer.fengyu.plugin.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.function.Supplier;

/**
 * Resolves the browser executable path by three-tier priority:
 * <ol>
 *   <li>User-configured path (from plugin settings; may point at a system Chrome/Edge).</li>
 *   <li>Already-downloaded Chromium under {@code <dataDir>/chromium/}.</li>
 *   <li>Auto-download Chromium into {@code <dataDir>/chromium/} via {@link ChromiumInstaller}.</li>
 * </ol>
 * Returns {@code null} when no path is available, letting Playwright fall back to its
 * bundled/installed browser. The platform-specific executable name (chrome / chrome.exe /
 * Chromium.app/Contents/MacOS/Chromium) is applied to tier-2 discovery.
 *
 * <p>Pure logic plus filesystem checks; the network-touching Playwright CLI call is hidden
 * behind the {@link ChromiumInstaller} seam, so the three tiers are unit-testable without
 * a network. The production installer is {@link #installViaCli(Path)} (reachable via
 * {@link #forEnvironment(Path, Supplier)}), which is <em>not</em> unit-tested — it needs
 * network and is a known coverage gap.
 */
public final class ChromiumResolver {

    private static final Logger log = LoggerFactory.getLogger(ChromiumResolver.class);

    private final Supplier<String> userConfigPath;
    private final Path dataDir;
    private final boolean windows;
    private final ChromiumInstaller installer;

    public ChromiumResolver(Supplier<String> userConfigPath, Path dataDir,
                            boolean windows, ChromiumInstaller installer) {
        this.userConfigPath = userConfigPath;
        this.dataDir = dataDir;
        this.windows = windows;
        this.installer = installer;
    }

    /** Convenience factory reading env + os.name; used by the worker. */
    public static ChromiumResolver forEnvironment(Path dataDir, Supplier<String> userConfigPath) {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        return new ChromiumResolver(userConfigPath, dataDir, win, ChromiumResolver::installViaCli);
    }

    public String resolve() {
        // Tier 1: user-configured path.
        String configured = userConfigPath == null ? null : userConfigPath.get();
        if (configured != null && !configured.isBlank() && Files.isExecutable(Path.of(configured))) {
            log.info("Using user-configured browser: {}", configured);
            return configured;
        }
        // Tier 2 + 3: under <dataDir>/chromium/.
        Path chromiumRoot = dataDir.resolve("chromium");
        String found = findExisting(chromiumRoot);
        if (found != null) {
            log.info("Using downloaded Chromium: {}", found);
            return found;
        }
        // Tier 3: download.
        try {
            log.info("No Chromium found; downloading into {}", chromiumRoot);
            installer.install(chromiumRoot);
        } catch (Exception e) {
            log.warn("Chromium download failed (will fall back to Playwright default): {}", e.getMessage());
            return null;
        }
        found = findExisting(chromiumRoot);
        if (found == null) {
            log.warn("Chromium still absent after install; falling back to Playwright default");
        }
        return found;
    }

    /**
     * Scan {@code <chromiumRoot>} for a versioned subdirectory (e.g.
     * {@code chromium-115}) containing the platform binary, and return its
     * absolute path, or {@code null} if none is found.
     */
    private String findExisting(Path chromiumRoot) {
        if (!Files.isDirectory(chromiumRoot)) return null;
        String exeName = executableName();
        try (var dirs = Files.list(chromiumRoot)) {
            for (Path dir : (Iterable<Path>) dirs::iterator) {
                Path candidate = dir.resolve(exeName);
                if (Files.isRegularFile(candidate)) return candidate.toString();
                // macOS nested app bundle.
                Path macCandidate = dir.resolve("Chromium.app/Contents/MacOS/Chromium");
                if (Files.isRegularFile(macCandidate)) return macCandidate.toString();
            }
        } catch (Exception e) {
            log.debug("chromium scan failed: {}", e.getMessage());
        }
        return null;
    }

    private String executableName() {
        return windows ? "chrome.exe" : "chrome";
    }

    /**
     * Production installer: sets {@code PLAYWRIGHT_BROWSERS_PATH} and runs
     * {@code com.microsoft.playwright.CLI install chromium} as a subprocess.
     *
     * <p>Not unit-tested (needs network) — a known coverage gap. The drain thread reads the
     * subprocess's merged stdout/stderr line-by-line into SLF4J so the pipe does not fill
     * and block; this also keeps all output in the logging framework rather than on the
     * raw {@code System.err} stream.
     */
    private static void installViaCli(Path targetDir) throws Exception {
        Files.createDirectories(targetDir);
        ProcessBuilder pb = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                "-cp", System.getProperty("java.class.path"),
                "com.microsoft.playwright.CLI", "install", "chromium");
        pb.environment().put("PLAYWRIGHT_BROWSERS_PATH", targetDir.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // Drain output to the log (avoids blocking when the pipe fills).
        var drain = Thread.startVirtualThread(() -> {
            try (var reader = p.inputReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[playwright-install] {}", line);
                }
            } catch (Exception ignored) {
            }
        });
        int code = p.waitFor();
        drain.join();
        if (code != 0) throw new IllegalStateException("playwright install exited " + code);
    }
}
