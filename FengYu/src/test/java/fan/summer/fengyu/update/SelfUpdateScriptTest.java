package fan.summer.fengyu.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.FileSystems;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-1 regression: the self-update restart script embeds the API token, so it must be
 * owner-only on disk, must deliver the token via the {@code FENGYU_AUTH_TOKEN} environment
 * instead of a {@code --token=} argv (world-readable via {@code ps}/WMI for the relaunched
 * process's whole lifetime), and must only ever interpolate a whitelisted release version.
 */
class SelfUpdateScriptTest {

    private static final String TOKEN = "sekret-token-1";

    private final SelfUpdateService service = new SelfUpdateService(null);

    // ── version sanitization ─────────────────────────────────────────────────────

    @Test
    void sanitizeVersionAcceptsOrdinaryReleaseTags() {
        assertEquals("4.0.0", SelfUpdateService.sanitizeVersion("4.0.0"));
        assertEquals("4.0.0-beta.1", SelfUpdateService.sanitizeVersion("4.0.0-beta.1"));
        assertEquals("v10.20.30-RC.4", SelfUpdateService.sanitizeVersion("v10.20.30-RC.4"));
    }

    @Test
    void sanitizeVersionRejectsAnythingOutsideTheIdentifierCharset() {
        assertThrows(IllegalStateException.class,
                () -> SelfUpdateService.sanitizeVersion("4.0.0\"; rm -rf /"));
        assertThrows(IllegalStateException.class,
                () -> SelfUpdateService.sanitizeVersion("4.0.0\n& del everything"));
        assertThrows(IllegalStateException.class, () -> SelfUpdateService.sanitizeVersion(""));
        assertThrows(IllegalStateException.class, () -> SelfUpdateService.sanitizeVersion(null));
    }

    // ── relaunch command: token travels by environment, never argv ───────────────

    @Test
    void relaunchCommandDropsTheTokenFlagFromTheReconstructedCommandLine() {
        String previous = System.getProperty("sun.java.command");
        try {
            System.setProperty("sun.java.command",
                    "fan.summer.fengyu.HeadlessLauncher -jar /opt/fengyu/Infinia.jar"
                            + " --port=24056 --token=leak-me");
            List<String> cmd = service.buildRelaunchCommand(Path.of("/opt/fengyu/Infinia.jar"), "java");
            assertTrue(cmd.contains("java"), cmd.toString());
            assertTrue(cmd.contains("-jar"), cmd.toString());
            assertTrue(cmd.contains("--port=24056"), "non-token flags must survive: " + cmd);
            assertFalse(cmd.toString().contains("--token="),
                    "the token flag must not be reconstructed into argv: " + cmd);
            assertFalse(cmd.contains("leak-me"), cmd.toString());
        } finally {
            if (previous == null) System.clearProperty("sun.java.command");
            else System.setProperty("sun.java.command", previous);
        }
    }

    // ── rendered scripts ─────────────────────────────────────────────────────────

    @Test
    void posixScriptExportsTheTokenAsEnvironmentAndNeverWritesItIntoArgv() {
        String body = SelfUpdateService.renderPosixScript(4242L,
                Path.of("/opt/fengyu/Infinia.jar"), Path.of("/tmp/staged.jar"),
                Path.of("/opt/fengyu/Infinia.jar.bak"),
                List.of("java", "-jar", "/opt/fengyu/Infinia.jar", "--port=24056"),
                "4.0.1", TOKEN);
        assertTrue(body.contains("export FENGYU_AUTH_TOKEN=" + TOKEN + "\n"),
                "token must ride the environment: " + body);
        assertFalse(body.contains("--token="), body);
        assertTrue(body.contains("Relaunches Infinia 4.0.1"), body);
    }

    @Test
    void posixScriptWithNoTokenEmitsNoCredentialMaterial() {
        String body = SelfUpdateService.renderPosixScript(4242L,
                Path.of("/opt/fengyu/Infinia.jar"), Path.of("/tmp/staged.jar"),
                Path.of("/opt/fengyu/Infinia.jar.bak"),
                List.of("java", "-jar", "/opt/fengyu/Infinia.jar"), "4.0.1", "");
        assertFalse(body.contains("FENGYU_AUTH_TOKEN"), body);
    }

    @Test
    void posixScriptQuotesTokensThatNeedIt() {
        String body = SelfUpdateService.renderPosixScript(4242L,
                Path.of("/j.jar"), Path.of("/s.jar"), Path.of("/j.jar.bak"),
                List.of("java", "-jar", "/j.jar"), "4.0.1", "to'ken");
        assertTrue(body.contains("export FENGYU_AUTH_TOKEN='to'\"'\"'ken'"), body);
    }

    @Test
    void windowsScriptSetsTheTokenViaEnvironmentAndStaysAsciiSafe() {
        String body = SelfUpdateService.renderWindowsScript(4242L,
                Path.of("C:\\fengyu\\Infinia.jar"), Path.of("C:\\fengyu\\staged.jar"),
                Path.of("C:\\fengyu\\Infinia.jar.bak"),
                List.of("java", "-jar", "C:\\fengyu\\Infinia.jar", "--port=24056"),
                "4.0.1", TOKEN);
        // cmd parses .bat in the OEM code page; the leading chcp lets the UTF-8 file (and any
        // non-ASCII install path in it) decode correctly on CJK Windows.
        assertTrue(body.startsWith("@echo off\r\nchcp 65001 >nul\r\n"), body);
        assertTrue(body.contains("set \"FENGYU_AUTH_TOKEN=" + TOKEN + "\""), body);
        assertFalse(body.contains("--token="), body);
        // Every LF must be part of a CRLF pair — cmd wants DOS line endings.
        for (int i = body.indexOf('\n'); i >= 0; i = body.indexOf('\n', i + 1)) {
            assertTrue(i > 0 && body.charAt(i - 1) == '\r', "bare LF at index " + i + ": " + body);
        }
    }

    @Test
    void windowsScriptRejectsTokensABatFileCannotCarry() {
        assertThrows(IllegalStateException.class, () ->
                SelfUpdateService.renderWindowsScript(4242L, Path.of("C:\\j.jar"),
                        Path.of("C:\\s.jar"), Path.of("C:\\j.jar.bak"),
                        List.of("java", "-jar", "C:\\j.jar"), "4.0.1", "per%cent"));
        assertThrows(IllegalStateException.class, () ->
                SelfUpdateService.renderWindowsScript(4242L, Path.of("C:\\j.jar"),
                        Path.of("C:\\s.jar"), Path.of("C:\\j.jar.bak"),
                        List.of("java", "-jar", "C:\\j.jar"), "4.0.1", "qu\"ote"));
    }

    // ── written script: owner-only permissions ───────────────────────────────────

    @Test
    void writtenPosixScriptIsOwnerOnlyExecutable(@TempDir Path runtimeRoot) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        String previousRoot = System.getProperty("fengyu.runtime.dir");
        String previousToken = System.getProperty("fengyu.auth.token");
        try {
            System.setProperty("fengyu.runtime.dir", runtimeRoot.toString());
            System.setProperty("fengyu.auth.token", TOKEN);
            Path script = service.writeRestartScript(
                    runtimeRoot.resolve("Infinia.jar"), runtimeRoot.resolve("staged.jar"), "4.0.1");
            assertEquals(EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE),
                    Files.getPosixFilePermissions(script),
                    "script embeds the token and must be rwx------");
            String body = Files.readString(script);
            assertTrue(body.contains("export FENGYU_AUTH_TOKEN=" + TOKEN), body);
        } finally {
            if (previousRoot == null) System.clearProperty("fengyu.runtime.dir");
            else System.setProperty("fengyu.runtime.dir", previousRoot);
            if (previousToken == null) System.clearProperty("fengyu.auth.token");
            else System.setProperty("fengyu.auth.token", previousToken);
        }
    }

    @Test
    void writeRestartScriptRefusesUnsafeVersionsBeforeTouchingDisk(@TempDir Path runtimeRoot) {
        String previousRoot = System.getProperty("fengyu.runtime.dir");
        try {
            System.setProperty("fengyu.runtime.dir", runtimeRoot.toString());
            assertThrows(IllegalStateException.class, () -> service.writeRestartScript(
                    runtimeRoot.resolve("Infinia.jar"), runtimeRoot.resolve("staged.jar"),
                    "4.0.0 && curl evil"));
            assertFalse(Files.exists(runtimeRoot.resolve("runtime-files")),
                    "the aborted update must not even create the script directory");
        } finally {
            if (previousRoot == null) System.clearProperty("fengyu.runtime.dir");
            else System.setProperty("fengyu.runtime.dir", previousRoot);
        }
    }
}
