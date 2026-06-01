package fan.summer.buildintool.pdftool.converter;

import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Detects installed Office back-ends (WPS, LibreOffice, MS Word) with
 * platform-specific checks for macOS, Windows, and Linux.
 *
 * <p>Detection priority: WPS &rarr; LibreOffice &rarr; MS Word.
 * The result is cached after the first successful detection; call
 * {@link #clearCache()} to force re-detection.</p>
 *
 * @since 3.0.0
 */
public final class OfficeDetector {

    private static final PluginLogger log = LoggerFactory.getLogger(OfficeDetector.class);
    private static final AtomicReference<DetectedBackend> CACHE = new AtomicReference<>();

    private OfficeDetector() { /* utility class */ }

    // ── Public API ──────────────────────────────────────────────

    /**
     * Detects an installed Office back-end.
     *
     * @return an {@link Optional} containing the detected back-end, or
     *         {@link Optional#empty()} if none was found
     */
    public static Optional<DetectedBackend> detect() {
        DetectedBackend cached = CACHE.get();
        if (cached != null) {
            log.debug("Returning cached backend: {}", cached);
            return Optional.of(cached);
        }

        String os = System.getProperty("os.name", "").toLowerCase();
        log.debug("Detecting Office backend on OS: {}", os);

        Optional<DetectedBackend> result;
        if (os.contains("mac")) {
            result = detectMac();
        } else if (os.contains("win")) {
            result = detectWindows();
        } else {
            result = detectLinux();
        }

        result.ifPresent(CACHE::set);
        return result;
    }

    /**
     * Clears the cached detection result so the next call to
     * {@link #detect()} will perform a fresh scan.
     */
    public static void clearCache() {
        CACHE.set(null);
        log.debug("OfficeDetector cache cleared");
    }

    // ── Backend type enum ───────────────────────────────────────

    /**
     * Identifies the kind of Office installation that was found.
     */
    public enum BackendType {
        WPS("WPS Office"),
        LIBRE_OFFICE("LibreOffice"),
        MS_WORD("Microsoft Word");

        private final String displayName;

        BackendType(String displayName) {
            this.displayName = displayName;
        }

        /** Human-readable name for UI display. */
        public String getDisplayName() {
            return displayName;
        }
    }

    // ── DetectedBackend record ──────────────────────────────────

    /**
     * Immutable descriptor for a detected Office installation.
     *
     * @param type           the back-end type
     * @param executablePath absolute path to the executable
     * @param displayName    human-readable name for UI display
     */
    public record DetectedBackend(BackendType type, String executablePath, String displayName) {}

    // ── macOS detection ─────────────────────────────────────────

    private static Optional<DetectedBackend> detectMac() {
        // WPS — macOS binary is named "wpsoffice" (not "wps")
        Optional<DetectedBackend> wps = findMacApp(
                List.of(
                        "/Applications/wpsoffice.app/Contents/MacOS/wpsoffice",
                        "/Applications/WPS Office.app/Contents/MacOS/wpsoffice",
                        "/Applications/wpsoffice.app/Contents/MacOS/wps",
                        "/Applications/WPS Office.app/Contents/MacOS/wps"
                ),
                "WPS Office",
                BackendType.WPS
        );
        if (wps.isPresent()) return wps;

        String home = System.getProperty("user.home", "");
        wps = findMacApp(
                List.of(home + "/Applications/wpsoffice.app/Contents/MacOS/wpsoffice",
                        home + "/Applications/WPS Office.app/Contents/MacOS/wpsoffice",
                        home + "/Applications/wpsoffice.app/Contents/MacOS/wps",
                        home + "/Applications/WPS Office.app/Contents/MacOS/wps"),
                "WPS Office",
                BackendType.WPS
        );
        if (wps.isPresent()) return wps;

        // LibreOffice
        Optional<DetectedBackend> lo = findMacApp(
                List.of(
                        "/Applications/LibreOffice.app/Contents/MacOS/soffice",
                        "/Applications/LibreOffice.app/Contents/MacOS/libreoffice"
                ),
                "LibreOffice",
                BackendType.LIBRE_OFFICE
        );
        if (lo.isPresent()) return lo;

        // MS Word
        Optional<DetectedBackend> msWord = findMacApp(
                List.of(
                        "/Applications/Microsoft Word.app/Contents/MacOS/Microsoft Word"
                ),
                "Microsoft Word",
                BackendType.MS_WORD
        );
        return msWord;
    }

    private static Optional<DetectedBackend> findMacApp(List<String> candidates,
                                                         String displayName,
                                                         BackendType type) {
        for (String path : candidates) {
            if (Files.isExecutable(Path.of(path))) {
                log.info("Detected {} at {}", type, path);
                return Optional.of(new DetectedBackend(type, path, displayName));
            }
        }
        log.debug("{} not found at any candidate path: {}", type, candidates);
        return Optional.empty();
    }

    // ── Windows detection ───────────────────────────────────────

    private static Optional<DetectedBackend> detectWindows() {
        // WPS
        Optional<DetectedBackend> wps = detectWindowsWps();
        if (wps.isPresent()) return wps;

        // LibreOffice
        Optional<DetectedBackend> lo = detectWindowsLibreOffice();
        if (lo.isPresent()) return lo;

        // MS Word
        return detectWindowsMsWord();
    }

    private static Optional<DetectedBackend> detectWindowsWps() {
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(X86)");
        String localAppData = System.getenv("LOCALAPPDATA");
        String userHome = System.getProperty("user.home", "");

        List<String> searchRoots = Stream.of(programFiles, programFilesX86, localAppData, userHome)
                .filter(p -> p != null && !p.isBlank())
                .distinct()
                .toList();

        for (String root : searchRoots) {
            Path wpsDir = Path.of(root, "Kingsoft", "WPS Office");
            if (!Files.isDirectory(wpsDir)) continue;

            // WPS installs into versioned sub-directories, e.g.
            //   .../WPS Office/12.8.2.12005/office6/wps.exe
            try (Stream<Path> versionDirs = Files.list(wpsDir)) {
                Optional<Path> exe = versionDirs
                        .filter(Files::isDirectory)
                        .map(d -> d.resolve("office6").resolve("wps.exe"))
                        .filter(Files::isRegularFile)
                        .findFirst();
                if (exe.isPresent()) {
                    String path = exe.get().toString();
                    log.info("Detected WPS Office at {}", path);
                    return Optional.of(new DetectedBackend(BackendType.WPS, path, "WPS Office"));
                }
            } catch (IOException e) {
                log.warn("Error scanning WPS directory {}: {}", wpsDir, e.getMessage());
            }
        }
        log.debug("WPS Office not found on Windows");
        return Optional.empty();
    }

    private static Optional<DetectedBackend> detectWindowsLibreOffice() {
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(X86)");

        List<String> candidates = Stream.of(programFiles, programFilesX86)
                .filter(p -> p != null && !p.isBlank())
                .flatMap(root -> Stream.of(
                        Path.of(root, "LibreOffice", "program", "soffice.exe").toString(),
                        Path.of(root, "LibreOffice", "program", "libreoffice.exe").toString()
                ))
                .toList();

        for (String path : candidates) {
            if (Files.isRegularFile(Path.of(path))) {
                log.info("Detected LibreOffice at {}", path);
                return Optional.of(new DetectedBackend(BackendType.LIBRE_OFFICE, path, "LibreOffice"));
            }
        }
        log.debug("LibreOffice not found on Windows");
        return Optional.empty();
    }

    private static Optional<DetectedBackend> detectWindowsMsWord() {
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(X86)");

        List<String> candidates = Stream.of(programFiles, programFilesX86)
                .filter(p -> p != null && !p.isBlank())
                .flatMap(root -> {
                    // May live under a versioned "Microsoft Office" or "Microsoft Office 15" etc.
                    Path officeRoot = Path.of(root, "Microsoft Office");
                    if (Files.isDirectory(officeRoot)) {
                        try (Stream<Path> subDirs = Files.list(officeRoot)) {
                            return Stream.concat(
                                    subDirs.filter(Files::isDirectory)
                                            .map(d -> d.resolve("WINWORD.EXE").toString()),
                                    Stream.of(officeRoot.resolve("root").resolve("Office16").resolve("WINWORD.EXE").toString(),
                                              officeRoot.resolve("root").resolve("Office15").resolve("WINWORD.EXE").toString())
                            );
                        } catch (IOException e) {
                            log.warn("Error scanning Microsoft Office directory: {}", e.getMessage());
                            return Stream.<String>empty();
                        }
                    }
                    return Stream.<String>empty();
                })
                .toList();

        for (String path : candidates) {
            if (Files.isRegularFile(Path.of(path))) {
                log.info("Detected MS Word at {}", path);
                return Optional.of(new DetectedBackend(BackendType.MS_WORD, path, "Microsoft Word"));
            }
        }
        log.debug("MS Word not found on Windows");
        return Optional.empty();
    }

    // ── Linux detection ─────────────────────────────────────────

    private static Optional<DetectedBackend> detectLinux() {
        // WPS
        Optional<DetectedBackend> wps = detectLinuxWps();
        if (wps.isPresent()) return wps;

        // LibreOffice
        Optional<DetectedBackend> lo = detectLinuxLibreOffice();
        return lo;
    }

    private static Optional<DetectedBackend> detectLinuxWps() {
        // Try `which wps` first
        Optional<String> which = which("wps");
        if (which.isPresent()) {
            String path = which.get();
            log.info("Detected WPS Office at {}", path);
            return Optional.of(new DetectedBackend(BackendType.WPS, path, "WPS Office"));
        }

        // Common install location
        Path fallback = Path.of("/opt/kingsoft/wps-office/office6/wps");
        if (Files.isExecutable(fallback)) {
            log.info("Detected WPS Office at {}", fallback);
            return Optional.of(new DetectedBackend(BackendType.WPS, fallback.toString(), "WPS Office"));
        }

        log.debug("WPS Office not found on Linux");
        return Optional.empty();
    }

    private static Optional<DetectedBackend> detectLinuxLibreOffice() {
        Optional<String> which = which("libreoffice");
        if (which.isPresent()) {
            String path = which.get();
            log.info("Detected LibreOffice at {}", path);
            return Optional.of(new DetectedBackend(BackendType.LIBRE_OFFICE, path, "LibreOffice"));
        }

        which = which("soffice");
        if (which.isPresent()) {
            String path = which.get();
            log.info("Detected LibreOffice (soffice) at {}", path);
            return Optional.of(new DetectedBackend(BackendType.LIBRE_OFFICE, path, "LibreOffice"));
        }

        log.debug("LibreOffice not found on Linux");
        return Optional.empty();
    }

    // ── Utility ─────────────────────────────────────────────────

    /**
     * Runs {@code which <command>} and returns the first line of output
     * (the resolved path) if the exit code is 0.
     */
    private static Optional<String> which(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", command);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            if (proc.waitFor() == 0 && !output.isEmpty()) {
                // `which` may return multiple lines; take the first
                String path = output.split("\\R", 2)[0].trim();
                return Optional.of(path);
            }
        } catch (IOException | InterruptedException e) {
            log.debug("which {} failed: {}", command, e.getMessage());
        }
        return Optional.empty();
    }
}
