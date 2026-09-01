package fan.summer.fengyu.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link CloudSecretStore} on the host's native credential facility: macOS
 * Keychain ({@code security} CLI), Linux Secret Service ({@code secret-tool})
 * or Windows Credential Manager (PowerShell + CredWrite/CredRead). The store is
 * probed once at construction; on hosts without any facility (plain servers)
 * {@link #available()} is false and every operation throws — callers keep the
 * session in memory instead of persisting anywhere weaker.
 */
@Component
public class OsCloudSecretStore implements CloudSecretStore {

    private static final Logger log = LoggerFactory.getLogger(OsCloudSecretStore.class);
    private static final String KEYCHAIN_ACCOUNT = "fengyu";
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(8);

    /** Runs one OS command with optional stdin and returns its stdout (test seam). */
    interface CommandRunner {
        String run(List<String> command, String stdin) throws IOException,
                InterruptedException;
    }

    enum Backend { MACOS_KEYCHAIN, LINUX_SECRET_SERVICE, WINDOWS_CREDENTIAL_MANAGER, NONE }

    private final Backend backend;
    private final CommandRunner runner;

    public OsCloudSecretStore() {
        this(detectBackend(), OsCloudSecretStore::runCommand);
    }

    /** Test seam: force a backend and stub the command execution. */
    OsCloudSecretStore(Backend backend, CommandRunner runner) {
        this.backend = backend;
        this.runner = runner;
    }

    static Backend detectBackend() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return Files.isRegularFile(Path.of("/usr/bin/security"))
                    ? Backend.MACOS_KEYCHAIN : Backend.NONE;
        }
        if (os.contains("win")) {
            return canRun(List.of("powershell", "-NoProfile", "-Command", "exit 0"))
                    ? Backend.WINDOWS_CREDENTIAL_MANAGER : Backend.NONE;
        }
        return canRun(List.of("secret-tool", "--version"))
                ? Backend.LINUX_SECRET_SERVICE : Backend.NONE;
    }

    private static boolean canRun(List<String> probe) {
        try {
            new ProcessBuilder(probe).start().waitFor(5, TimeUnit.SECONDS);
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    @Override
    public boolean available() {
        return backend != Backend.NONE;
    }

    @Override
    public void save(String name, String value) {
        requireBackend();
        try {
            switch (backend) {
                case MACOS_KEYCHAIN -> exec(List.of("security", "add-generic-password",
                        "-a", KEYCHAIN_ACCOUNT, "-s", name, "-w", value, "-U"), null);
                case LINUX_SECRET_SERVICE -> exec(List.of("secret-tool", "store",
                        "--label=FengYu", "service", name), value);
                case WINDOWS_CREDENTIAL_MANAGER -> exec(List.of("powershell", "-NoProfile",
                        "-NonInteractive", "-Command", windowsScript("save", name)),
                        Base64.getEncoder().encodeToString(value.getBytes(
                                StandardCharsets.UTF_8)));
                default -> throw unavailable();
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("OS credential store write failed: "
                    + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> load(String name) {
        requireBackend();
        try {
            switch (backend) {
                case MACOS_KEYCHAIN -> {
                    // `security find-generic-password` exits non-zero when the item
                    // is absent — that is a normal miss, not a failure.
                    try {
                        return Optional.of(exec(List.of("security",
                                "find-generic-password", "-a", KEYCHAIN_ACCOUNT,
                                "-s", name, "-w"), null).stripTrailing());
                    } catch (IOException missing) {
                        return Optional.empty();
                    }
                }
                case LINUX_SECRET_SERVICE -> {
                    try {
                        String value = exec(List.of("secret-tool", "lookup", "service",
                                name), null);
                        return value == null || value.isBlank()
                                ? Optional.empty() : Optional.of(value.stripTrailing());
                    } catch (IOException missing) {
                        return Optional.empty();
                    }
                }
                case WINDOWS_CREDENTIAL_MANAGER -> {
                    try {
                        String base64 = exec(List.of("powershell", "-NoProfile",
                                "-NonInteractive", "-Command",
                                windowsScript("load", name)), null);
                        return base64 == null || base64.isBlank()
                                ? Optional.empty()
                                : Optional.of(new String(Base64.getDecoder().decode(
                                        base64.stripTrailing()), StandardCharsets.UTF_8));
                    } catch (IOException | IllegalArgumentException missing) {
                        return Optional.empty();
                    }
                }
                default -> throw unavailable();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OS credential store read interrupted", e);
        }
    }

    @Override
    public void delete(String name) {
        requireBackend();
        try {
            switch (backend) {
                case MACOS_KEYCHAIN -> {
                    try {
                        exec(List.of("security", "delete-generic-password",
                                "-a", KEYCHAIN_ACCOUNT, "-s", name), null);
                    } catch (IOException absent) {
                        // Nothing to remove — fine.
                    }
                }
                case LINUX_SECRET_SERVICE -> {
                    try {
                        exec(List.of("secret-tool", "clear", "service", name), null);
                    } catch (IOException absent) {
                        // Nothing to remove — fine.
                    }
                }
                case WINDOWS_CREDENTIAL_MANAGER -> {
                    try {
                        exec(List.of("powershell", "-NoProfile", "-NonInteractive",
                                "-Command", windowsScript("delete", name)), null);
                    } catch (IOException absent) {
                        // Nothing to remove — fine.
                    }
                }
                default -> throw unavailable();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OS credential store delete interrupted", e);
        }
    }

    private void requireBackend() {
        if (backend == Backend.NONE) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("No OS credential store (Keychain / "
                + "Credential Manager / Secret Service) is available on this host");
    }

    private String exec(List<String> command, String stdin) throws IOException,
            InterruptedException {
        String out = runner.run(command, stdin);
        if (out != null && !out.isBlank()) {
            log.debug("OS credential store said: {}", out.stripTrailing());
        }
        return out == null ? "" : out;
    }

    private static String runCommand(List<String> command, String stdin)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.redirectErrorStream(false);
        Process process = builder.start();
        if (stdin != null) {
            process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        process.getOutputStream().close();
        String stdout = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (!process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("timed out: " + command.get(0));
        }
        if (process.exitValue() != 0) {
            throw new IOException(command.get(0) + " exited with " + process.exitValue());
        }
        return stdout;
    }

    /**
     * One PowerShell program with the credential-manager P/Invoke; the operation
     * and target name are inlined. Values travel as base64 (blob bytes are not
     * valid UTF-16 command-line material).
     */
    private static String windowsScript(String operation, String target) {
        String program = """
                Add-Type -TypeDefinition @'
                using System;
                using System.Runtime.InteropServices;
                public class FyCredMan {
                  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
                  public struct CREDENTIAL {
                    public int Flags; public int Type;
                    [MarshalAs(UnmanagedType.LPWStr)] public string TargetName;
                    [MarshalAs(UnmanagedType.LPWStr)] public string Comment;
                    public long LastWritten;
                    public int CredentialBlobSize; public IntPtr CredentialBlob;
                    public int Persist; public int AttributeCount; public IntPtr Attributes;
                    [MarshalAs(UnmanagedType.LPWStr)] public string TargetAlias;
                    [MarshalAs(UnmanagedType.LPWStr)] public string UserName;
                  }
                  [DllImport("advapi32.dll", CharSet=CharSet.Unicode, EntryPoint="CredWriteW", SetLastError=true)]
                  public static extern bool CredWrite(ref CREDENTIAL c, int flags);
                  [DllImport("advapi32.dll", CharSet=CharSet.Unicode, EntryPoint="CredReadW", SetLastError=true)]
                  public static extern bool CredRead(string target, int type, int flags, out IntPtr cred);
                  [DllImport("advapi32.dll", CharSet=CharSet.Unicode, EntryPoint="CredDeleteW", SetLastError=true)]
                  public static extern bool CredDelete(string target, int type, int flags);
                  [DllImport("advapi32.dll")] public static extern void CredFree(IntPtr cred);
                  public static byte[] Read(string target) {
                    IntPtr p;
                    if (!CredRead(target, 1, 0, out p)) return null;
                    try {
                      var c = (CREDENTIAL) Marshal.PtrToStructure(p, typeof(CREDENTIAL));
                      var b = new byte[c.CredentialBlobSize];
                      Marshal.Copy(c.CredentialBlob, b, 0, c.CredentialBlobSize);
                      return b;
                    } finally { CredFree(p); }
                  }
                  public static void Write(string target, byte[] blob) {
                    var c = new CREDENTIAL();
                    c.Type = 1; c.TargetName = target; c.UserName = "fengyu"; c.Persist = 2;
                    c.CredentialBlob = Marshal.AllocHGlobal(blob.Length);
                    Marshal.Copy(blob, 0, c.CredentialBlob, blob.Length);
                    c.CredentialBlobSize = blob.Length;
                    try {
                      if (!CredWrite(ref c, 0)) throw new Exception("CredWrite failed");
                    } finally { Marshal.FreeHGlobal(c.CredentialBlob); }
                  }
                }
                '@
                """;
        return program + switch (operation) {
            case "save" -> """
                $in = [Console]::In.ReadToEnd()
                [FyCredMan]::Write('%s', [Convert]::FromBase64String($in))
                exit 0
                """.formatted(target);
            case "load" -> """
                $b = [FyCredMan]::Read('%s')
                if ($null -eq $b) { exit 1 }
                [Convert]::ToBase64String($b)
                exit 0
                """.formatted(target);
            case "delete" -> """
                [void][FyCredMan]::Delete('%s')
                exit 0
                """.formatted(target);
            default -> throw new IllegalArgumentException(operation);
        };
    }
}
