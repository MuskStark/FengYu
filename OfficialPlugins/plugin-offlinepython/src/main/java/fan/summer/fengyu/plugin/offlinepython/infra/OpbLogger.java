package fan.summer.fengyu.plugin.offlinepython.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Headless logger for the Offline Python Builder worker.
 *
 * <p>Each line is routed two ways:
 * <ol>
 *   <li>to {@link System#err} (the {@code JsonRpcWorker} loop redirects {@code System.out}
 *       to {@code System.err}, so this is the worker's stderr — the host captures and logs it
 *       through {@code SensitiveValueRedactor});</li>
 *   <li>appended to the project-local {@code .offline-python.log} when one is set via
 *       {@link #setLogFile(Path)} (best-effort, never blocks).</li>
 * </ol>
 *
 * <p>Thread-safe: the file append is guarded by a synchronized block. The
 * {@code log(String)} / {@code log(String, String)} / {@code setLogFile(Path)} signatures are
 * preserved so existing {@code command/} callers need no changes.
 */
public class OpbLogger {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Logger HOST = Logger.getLogger("fengyu.offlinepython");

    /** No-arg ctor: the worker no longer receives a host logger (it uses System.err). */
    public OpbLogger() {}

    /** 当前日志文件路径;null 时只走 stderr(项目未打开时)。 */
    private volatile Path logFile;

    /** 设置日志文件(通常 = projectDir/.offline-python.log)。null = 只走 stderr。 */
    public void setLogFile(Path file) {
        this.logFile = file;
    }

    /** 追加一行日志(INFO 级)。 */
    public void log(String line) {
        log("INFO", line);
    }

    /** 追加一行指定级别的日志。 */
    public void log(String level, String line) {
        String ts = LocalTime.now().withNano(0).format(TS);
        String rendered = "[" + ts + "] " + ("INFO".equals(level) ? "" : "[" + level + "] ") + line;
        // 1. worker stderr(宿主捕获并记录到宿主日志骨干)
        routeToHost(level, rendered);
        // 2. 文件追加
        appendToFile(rendered);
    }

    private static void routeToHost(String level, String rendered) {
        Level jul = switch (level) {
            case "ERROR" -> Level.SEVERE;
            case "WARN"  -> Level.WARNING;
            case "DEBUG" -> Level.FINE;
            default      -> Level.INFO;
        };
        // Always mirror to stderr too: the host pumps worker stderr through its own logger,
        // and System.err is the only host-visible channel from an isolated child process.
        System.err.println(rendered);
        HOST.log(jul, rendered);
    }

    private synchronized void appendToFile(String rendered) {
        Path f = logFile;
        if (f == null) return;
        try {
            Files.writeString(f, rendered + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (NoSuchFileException ignored) {
            // 父目录可能尚未创建,尝试创建后重试一次
            try {
                Files.createDirectories(f.getParent());
                Files.writeString(f, rendered + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored2) { /* best-effort */ }
        } catch (IOException ignored) { /* best-effort:文件不可写不阻塞主流程 */ }
    }
}
