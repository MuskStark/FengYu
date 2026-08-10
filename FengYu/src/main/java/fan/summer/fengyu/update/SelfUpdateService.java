package fan.summer.fengyu.update;

import fan.summer.fengyu.runtime.RuntimePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Portable-mode ({@code java -jar}) self-update: downloads the new shaded JAR, verifies it
 * against the release's {@code checksums.txt}, then spawns a detached restart script that waits
 * for this JVM to exit, backs up + replaces the JAR, and relaunches with the original args.
 *
 * <p>A running JVM cannot overwrite its own JAR (Windows file lock; on POSIX the running process
 * keeps the old inode anyway), so the actual file swap happens after {@link System#exit} inside
 * a process that is truly detached from this JVM. The script is generated fresh each run into the
 * writable runtime-files directory, so the portable package layout is never touched.
 *
 * <p>In desktop/Electron deployments this bean's {@link #applyUpdate} throws — the shell owns
 * updates via electron-updater, and {@link UpdateCheckService#isPortableMode()} is false.
 */
@Service
public class SelfUpdateService {
    private static final Logger log = LoggerFactory.getLogger(SelfUpdateService.class);

    private static final String PORTABLE_JAR_NAME = "Infinia.jar";
    private static final String CHECKSUMS_ASSET = "checksums.txt";
    private static final String BACKUP_SUFFIX = ".bak";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final UpdateCheckService updateCheck;

    public SelfUpdateService(UpdateCheckService updateCheck) {
        this.updateCheck = updateCheck;
    }

    /**
     * Download, verify, and trigger a self-restart. The {@code exitAction} indirection mirrors
     * {@code SettingsController}'s pattern so the caller controls the exact exit sequencing
     * (give the HTTP response a beat to flush, then {@code System.exit}).
     *
     * @param info       the {@link UpdateInfo} carrying the asset download URL (must be portable mode)
     * @param exitAction invoked after the restart script is spawned; should exit the JVM
     */
    public void applyUpdate(UpdateInfo info, Runnable exitAction) {
        if (!updateCheck.isPortableMode()) {
            throw new IllegalStateException("Self-update is only available in portable (java -jar) mode");
        }
        if (info == null || info.downloadAssetUrl() == null || info.downloadAssetUrl().isBlank()) {
            throw new IllegalArgumentException("Latest release has no Infinia.jar asset to download");
        }

        try {
            Path currentJar = resolveCurrentJar();
            String expectedHash = resolveExpectedHash(info);

            log.info("[self-update] downloading {} -> temp", info.latestVersion());
            Path downloaded = downloadJar(info.downloadAssetUrl());
            String actualHash = sha256Hex(downloaded);
            if (!expectedHash.equalsIgnoreCase(actualHash)) {
                tryDelete(downloaded);
                throw new IllegalStateException(
                        "SHA-256 mismatch for Infinia.jar (expected " + expectedHash + ", got " + actualHash + ")");
            }
            log.info("[self-update] checksum verified (sha256={})", expectedHash);

            Path script = writeRestartScript(currentJar, downloaded, info.latestVersion());
            spawnDetached(script);
            log.info("[self-update] restart script spawned; exiting current JVM to let it swap the JAR");

            exitAction.run();
        } catch (IOException e) {
            throw new IllegalStateException("Self-update failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Self-update interrupted", e);
        }
    }

    /** Resolve the path of the currently running JAR (the portable launcher's {@code Infinia.jar}). */
    private Path resolveCurrentJar() {
        String classPath = System.getProperty("java.class.path");
        if (classPath != null && !classPath.isBlank()) {
            for (String entry : classPath.split(java.io.File.pathSeparator)) {
                if (entry.toLowerCase().endsWith(".jar")) return Paths.get(entry).toAbsolutePath().normalize();
            }
        }
        try {
            URL source = SelfUpdateService.class.getProtectionDomain().getCodeSource().getLocation();
            if (source != null && source.getFile().toLowerCase().endsWith(".jar")) {
                return Paths.get(source.toURI()).toAbsolutePath().normalize();
            }
        } catch (Exception ignored) { }
        throw new IllegalStateException("Cannot determine the running JAR path — not a portable java -jar launch?");
    }

    /**
     * Fetch {@code checksums.txt} from the release and pull out the line for {@code Infinia.jar}.
     * Format is GNU coreutils {@code "<hex>  Infinia.jar"}.
     */
    private String resolveExpectedHash(UpdateInfo info) throws IOException, InterruptedException {
        URI checksumsUrl = buildAssetUrl(info, CHECKSUMS_ASSET);
        HttpRequest req = HttpRequest.newBuilder(checksumsUrl)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "FengYu-Updater")
                .header("Accept", "application/octet-stream")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("checksums.txt returned HTTP " + resp.statusCode());
        }
        for (String raw : resp.body().split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            int ws = firstWhitespace(line);
            if (ws <= 0) continue;
            String hash = line.substring(0, ws);
            String namePart = line.substring(ws).trim();
            // text-mode prefix: strip a leading '*' on the filename token
            if (namePart.startsWith("*")) namePart = namePart.substring(1);
            if (PORTABLE_JAR_NAME.equals(namePart)) return hash;
        }
        throw new IllegalStateException("checksums.txt has no entry for " + PORTABLE_JAR_NAME);
    }

    private URI buildAssetUrl(UpdateInfo info, String assetName) {
        // The Infinia.jar browser_download_url is https://github.com/.../releases/download/<tag>/Infinia.jar;
        // a sibling asset swaps only the trailing filename.
        String base = info.downloadAssetUrl();
        int slash = base.lastIndexOf('/');
        if (slash < 0) throw new IllegalStateException("Malformed asset URL: " + base);
        return URI.create(base.substring(0, slash + 1) + assetName);
    }

    private static int firstWhitespace(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i))) return i;
        }
        return -1;
    }

    private Path downloadJar(String url) throws IOException, InterruptedException {
        Path staging = RuntimePaths.runtimeFilesDirectory(RuntimePaths.root())
                .resolve("update-staging-" + System.currentTimeMillis() + ".jar");
        Files.createDirectories(staging.getParent());
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "FengYu-Updater")
                .GET().build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(staging));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("Infinia.jar download returned HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private Path writeRestartScript(Path currentJar, Path downloadedJar, String newVersion) throws IOException {
        Path runtimeFiles = RuntimePaths.runtimeFilesDirectory(RuntimePaths.root());
        Files.createDirectories(runtimeFiles);
        long pid = ProcessHandle.current().pid();
        Path jarBackup = currentJar.resolveSibling(currentJar.getFileName() + BACKUP_SUFFIX);
        String javaExecutable = ProcessHandle.current().info().command().orElse("java");

        List<String> relaunchCommand = buildRelaunchCommand(currentJar, javaExecutable);

        Path logFile = runtimeFiles.resolve("self-update-" + System.currentTimeMillis() + ".log");
        if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            Path script = runtimeFiles.resolve("self-update.bat");
            String body = renderWindowsScript(pid, currentJar, downloadedJar, jarBackup, logFile, relaunchCommand, newVersion);
            Files.writeString(script, body, StandardCharsets.UTF_8);
            return script;
        }
        Path script = runtimeFiles.resolve("self-update.sh");
        String body = renderPosixScript(pid, currentJar, downloadedJar, jarBackup, logFile, relaunchCommand, newVersion);
        Files.writeString(script, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    /** Rebuild the original {@code java ... -jar Infinia.jar <args>} command line. */
    private List<String> buildRelaunchCommand(Path currentJar, String javaExecutable) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable);
        // Preserve JVM flags (-D / -X / module flags) from the original launch.
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            // Skip debugger/introspection args that won't bind cleanly on a fresh PID.
            if (arg.startsWith("-agentlib") || arg.startsWith("-javaagent")) continue;
            cmd.add(arg);
        }
        cmd.add("-jar");
        cmd.add(currentJar.toString());
        // The launcher's positional args (--port=..., --token=...). The portable run scripts pass
        // these through, so ManagementFactory does not surface them; they live in sun.java.command.
        String sunCommand = System.getProperty("sun.java.command");
        if (sunCommand != null) {
            int jarIdx = sunCommand.indexOf("-jar");
            if (jarIdx >= 0) {
                int after = sunCommand.indexOf(' ', jarIdx);
                if (after >= 0 && after + 1 < sunCommand.length()) {
                    for (String tok : splitRespectingQuotes(sunCommand.substring(after + 1))) {
                        if (!tok.isBlank()) cmd.add(tok);
                    }
                }
            }
        }
        return cmd;
    }

    private static List<String> splitRespectingQuotes(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static String renderPosixScript(long pid, Path currentJar, Path downloadedJar,
            Path backup, Path logFile, List<String> relaunch, String newVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env bash\n");
        sb.append("# Auto-generated by FengYu self-update. Relaunches Infinia ").append(newVersion).append(".\n");
        sb.append("set -uo pipefail\n");
        sb.append("echo \"[self-update] waiting for JVM (pid ").append(pid).append(") to exit\"\n");
        // Spin until the old JVM is gone — tail --pid blocks until the process exits (Linux/macOS).
        sb.append("tail --pid=").append(pid).append(" -f /dev/null 2>/dev/null || ");
        sb.append("while kill -0 ").append(pid).append(" 2>/dev/null; do sleep 1; done\n");
        sb.append("sleep 1\n");
        sb.append("cp -f \"").append(currentJar).append("\" \"").append(backup).append("\" 2>/dev/null || true\n");
        sb.append("mv -f \"").append(downloadedJar).append("\" \"").append(currentJar).append("\"\n");
        sb.append("echo \"[self-update] JAR replaced; relaunching\"\n");
        sb.append("exec ").append(joinShell(relaunch)).append('\n');
        return sb.toString();
    }

    private static String renderWindowsScript(long pid, Path currentJar, Path downloadedJar,
            Path backup, Path logFile, List<String> relaunch, String newVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\n");
        sb.append("REM Auto-generated by FengYu self-update. Relaunches Infinia ").append(newVersion).append(".\n");
        sb.append(":wait\n");
        sb.append("tasklist /FI \"PID eq ").append(pid).append("\" 2>nul | find \"").append(pid).append("\" >nul\n");
        sb.append("if not errorlevel 1 (\n");
        sb.append("  timeout /t 1 /nobreak >nul\n");
        sb.append("  goto wait\n");
        sb.append(")\n");
        sb.append("copy /Y \"").append(currentJar).append("\" \"").append(backup).append("\" >nul 2>&1\n");
        sb.append("move /Y \"").append(downloadedJar).append("\" \"").append(currentJar).append("\" >nul\n");
        sb.append("echo [self-update] JAR replaced; relaunching\n");
        sb.append("start \"\" /b ").append(joinWindows(relaunch)).append('\n');
        return sb.toString();
    }

    private static String joinShell(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(shellQuote(cmd.get(i)));
        }
        return sb.toString();
    }

    private static String joinWindows(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.size(); i++) {
            if (i > 0) sb.append(' ');
            String tok = cmd.get(i);
            if (tok.isEmpty() || tok.contains(" ")) sb.append('"').append(tok).append('"');
            else sb.append(tok);
        }
        return sb.toString();
    }

    private static String shellQuote(String token) {
        if (token.isEmpty()) return "''";
        if (token.matches("[A-Za-z0-9_@%+=:,./-]+")) return token;
        return "'" + token.replace("'", "'\"'\"'") + "'";
    }

    /**
     * Spawn the restart script truly detached: redirect its output to a log file so the JVM's exit
     * doesn't break a pipe, and never waitFor it.
     */
    private void spawnDetached(Path script) throws IOException {
        ProcessBuilder builder;
        Path logFile = script.resolveSibling(script.getFileName() + ".log");
        if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            builder = new ProcessBuilder("cmd", "/c", "start", "\"self-update\"", "/min",
                    script.toString());
        } else {
            builder = new ProcessBuilder("sh", "-c",
                    "nohup " + shellQuote(script.toString()) + " > " + shellQuote(logFile.toString())
                            + " 2>&1 &");
        }
        builder.redirectOutput(logFile.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        // Drain + close immediately so the child is not coupled to this JVM's lifetime.
        process.getInputStream().close();
        process.getErrorStream().close();
        process.getOutputStream().close();
    }

    private static String sha256Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (var in = Files.newInputStream(file)) {
            int count;
            while ((count = in.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void tryDelete(Path file) {
        try { Files.deleteIfExists(file); }
        catch (IOException ignored) { }
    }
}
