package fan.summer.fengyu.plugin.offlinepython.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless logger for the Offline Python Builder worker.
 *
 * <p>Each line is routed two ways:
 * <ol>
 *   <li>to SLF4J (the Worker SDK emits a structured stderr event that the host captures);</li>
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
    private static final Logger HOST = LoggerFactory.getLogger("fengyu.offlinepython");

    /** No-arg ctor: the worker no longer receives a host logger (it uses the SDK provider). */
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
        switch (level) {
            case "ERROR" -> HOST.error(rendered);
            case "WARN" -> HOST.warn(rendered);
            case "DEBUG" -> HOST.debug(rendered);
            default -> HOST.info(rendered);
        }
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
