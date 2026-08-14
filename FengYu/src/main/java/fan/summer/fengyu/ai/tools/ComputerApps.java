package fan.summer.fengyu.ai.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Best-effort per-OS application operations for the {@code computer_*} tools: list running GUI
 * apps, launch an app by name, and bring an app to the foreground.
 *
 * <p>Commands are fixed per-OS argv lists (never a shell string), and app names are validated
 * against a conservative allowlist before they reach any command line — so a crafted app name
 * can never inject shell or AppleScript metacharacters. Each operation is best-effort: callers
 * convert the thrown {@link IllegalStateException} into a {@code {success:false}} envelope.
 */
class ComputerApps {

    /** Functional seam over {@link ProcessBuilder}; (command, timeoutMillis) → stdout text. */
    interface CommandRunner {
        String run(List<String> command, long timeoutMillis);
    }

    private static final Pattern ALLOWED_APP_NAME = Pattern.compile("[\\p{L}\\p{N}\\s._\\-+():]*");
    private static final Pattern HAS_WORD = Pattern.compile("[\\p{L}\\p{N}]");
    private static final int MAX_NAME = 100;
    private static final long LIST_TIMEOUT_MS = 10_000;
    private static final long LAUNCH_TIMEOUT_MS = 20_000;

    private final String osName;
    private final CommandRunner runner;

    ComputerApps() {
        this(System.getProperty("os.name"), ComputerApps::runCommand);
    }

    ComputerApps(String osName, CommandRunner runner) {
        this.osName = osName == null ? "" : osName;
        this.runner = runner;
    }

    boolean isMac() {
        return ComputerKeyMap.isMac(osName);
    }

    boolean isWindows() {
        return osName.toLowerCase(Locale.ROOT).startsWith("win");
    }

    /** Names of foreground (GUI) application processes currently running. */
    List<String> list() {
        try {
            if (isMac()) {
                // System Events needs the Accessibility permission; the error text carries that hint.
                String out = runner.run(List.of("osascript", "-e",
                        "tell application \"System Events\" to get name of every application process whose background only is false"),
                        LIST_TIMEOUT_MS);
                List<String> names = new ArrayList<>();
                for (String name : out.split(",")) {
                    if (HAS_WORD.matcher(name).find()) names.add(name.trim());
                }
                return names;
            }
            if (isWindows()) {
                String out = runner.run(List.of("powershell", "-NoProfile", "-Command",
                        "Get-Process | Where-Object { $_.MainWindowTitle } | Select-Object -ExpandProperty ProcessName -Unique"),
                        LIST_TIMEOUT_MS);
                return splitLines(out);
            }
            String out = runner.run(List.of("wmctrl", "-l"), LIST_TIMEOUT_MS);
            // "0x03a00007  0 hostname window title" — title is field 3 onward.
            List<String> names = new ArrayList<>();
            for (String line : out.split("\\R")) {
                String[] fields = line.trim().split("\\s+", 4);
                if (fields.length == 4 && HAS_WORD.matcher(fields[3]).find()) names.add(fields[3]);
            }
            return names;
        } catch (Exception e) {
            String hint = isMac() ? " (grant FengYu Accessibility permission)"
                    : (!isWindows() ? " (install wmctrl)" : "");
            throw new IllegalStateException("listing running apps failed: " + safeMsg(e) + hint);
        }
    }

    /** Launches an installed app by name (e.g. {@code Safari}, {@code notepad}). */
    void launch(String app) {
        String name = validated(app);
        try {
            if (isMac()) {
                runner.run(List.of("open", "-a", name), LAUNCH_TIMEOUT_MS);
            } else if (isWindows()) {
                runner.run(List.of("powershell", "-NoProfile", "-Command",
                        "Start-Process -FilePath '" + name + "'"), LAUNCH_TIMEOUT_MS);
            } else {
                runner.run(List.of("gtk-launch", name.toLowerCase(Locale.ROOT)), LAUNCH_TIMEOUT_MS);
            }
        } catch (Exception e) {
            throw new IllegalStateException("launching '" + name + "' failed: " + safeMsg(e));
        }
    }

    /** Brings a running app's windows to the foreground. */
    void activate(String app) {
        String name = validated(app);
        try {
            if (isMac()) {
                runner.run(List.of("osascript", "-e",
                        "tell application \"" + name + "\" to activate"), LIST_TIMEOUT_MS);
            } else if (isWindows()) {
                // Get-Process wants the bare process name (no ".exe"); focus it by PID when a
                // windowed process matches, else fall back to a window-title match — process and
                // product names often differ on Windows (e.g. "Code" hosts "Visual Studio Code").
                String bare = name.replaceAll("(?i)\\.exe$", "");
                runner.run(List.of("powershell", "-NoProfile", "-Command",
                        "$n = '" + bare + "';"
                                + " $p = Get-Process -Name $n -ErrorAction SilentlyContinue"
                                + " | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1;"
                                + " if ($p) { (New-Object -ComObject WScript.Shell).AppActivate($p.Id) }"
                                + " elseif ((New-Object -ComObject WScript.Shell).AppActivate($n)) {}"
                                + " else { throw ('no window found for ' + $n) }"),
                        LIST_TIMEOUT_MS);
            } else {
                runner.run(List.of("wmctrl", "-a", name), LIST_TIMEOUT_MS);
            }
        } catch (Exception e) {
            throw new IllegalStateException("activating '" + name + "' failed: " + safeMsg(e));
        }
    }

    /** Conservative allowlist: no quotes, backslashes, or shell/AppleScript metacharacters. */
    private static String validated(String app) {
        String name = app == null ? "" : app.trim();
        if (name.isEmpty() || name.length() > MAX_NAME || !ALLOWED_APP_NAME.matcher(name).matches()
                || !HAS_WORD.matcher(name).find()) {
            throw new IllegalArgumentException(
                    "invalid app name '" + name + "': use a plain application name like 'Safari' or 'notepad'");
        }
        return name;
    }

    private static List<String> splitLines(String out) {
        List<String> names = new ArrayList<>();
        for (String line : out.split("\\R")) {
            if (HAS_WORD.matcher(line).find()) names.add(line.trim());
        }
        return names;
    }

    private static String safeMsg(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** Default runner: captures stdout, fails on non-zero exit, caps captured output. */
    static String runCommand(List<String> command, long timeoutMillis) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("timeout must be positive");
        Process process = null;
        ExecutorService readerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        Future<String> outputFuture = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process runningProcess = process;
            outputFuture = readerExecutor.submit(() -> readOutput(runningProcess));
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                closeOutput(process);
                throw new IllegalStateException("timed out after " + timeoutMillis + " ms");
            }
            String output = readOutputFuture(outputFuture);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("exit " + process.exitValue() + ": "
                        + output.trim().replaceAll("[\\r\\n]", " "));
            }
            return output.trim();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage(), e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (process != null) closeOutput(process);
            if (outputFuture != null) outputFuture.cancel(true);
            readerExecutor.shutdownNow();
        }
    }

    /** Drain output concurrently so a child cannot block the timeout while its pipe is open. */
    private static String readOutput(Process process) throws Exception {
        StringBuilder out = new StringBuilder();
        char[] buffer = new char[2048];
        try (var reader = process.inputReader()) {
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (out.length() < 20_000) {
                    out.append(buffer, 0, Math.min(count, 20_000 - out.length()));
                }
            }
        }
        return out.toString();
    }

    private static String readOutputFuture(Future<String> outputFuture) {
        try {
            return outputFuture.get(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while reading command output", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("could not collect command output", e);
        }
    }

    private static void closeOutput(Process process) {
        try { process.getInputStream().close(); } catch (Exception ignored) { }
    }
}
