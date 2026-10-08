package fan.summer.fengyu.web.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Read-only view over the runtime log directory (the unified log surface: fengyu.log,
 * plugin.log, and — when running under the desktop shell — desktop.log / update.log /
 * self-update.log all land in the same place). Powers the settings page's log panel;
 * {@code GET /api/logs} lists the active {@code *.log} files (rotated {@code .gz}
 * archives stay filesystem-only) and {@code GET /api/logs/{name}/tail} reads the tail.
 * Names are single-segment and jailed to the log directory; the endpoints ride the
 * standard token auth like every other {@code /api/**} route.
 */
@RestController
@RequestMapping("/api/logs")
public class LogsController {

    private static final int DEFAULT_TAIL_BYTES = 64 * 1024;
    private static final int MAX_TAIL_BYTES = 256 * 1024;

    /** One active log file; {@code lastModified} drives the panel's ordering. */
    public record LogFileView(String name, long size, Instant lastModified) {}

    /** The log panel's overview: active files plus the plugin ids seen in plugin.log. */
    public record LogsOverviewView(List<LogFileView> files, List<String> plugins) {}

    /** The tail of one log file, aligned to whole lines. */
    public record LogTailView(String name, long size, Instant lastModified, String content) {}

    @GetMapping
    public LogsOverviewView list() throws IOException {
        Path dir = logDirectory().toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) return new LogsOverviewView(List.of(), List.of());
        List<LogFileView> files;
        try (Stream<Path> paths = Files.list(dir)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".log"))
                    .map(file -> {
                        try {
                            return new LogFileView(file.getFileName().toString(),
                                    Files.size(file),
                                    Files.getLastModifiedTime(file).toInstant());
                        } catch (IOException unreadable) {
                            return null; // a racing rotation must not fail the listing
                        }
                    })
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(LogFileView::lastModified).reversed())
                    .toList();
        }
        return new LogsOverviewView(files, pluginIds(dir.resolve("plugin.log")));
    }

    /**
     * The plugin ids that actually appear in the unified plugin.log — extracted from the
     * logger column ({@code plugin.<id>.<source> - msg}), which is the per-line plugin
     * identity since the per-plugin file fan-out was retired. Powers the panel's plugin
     * sub-dropdown. Cached against the file's (size, mtime) so repeated listings do not
     * rescans; best-effort — an unreadable file yields an empty list.
     */
    private List<String> pluginIds(Path pluginLog) {
        if (!Files.isRegularFile(pluginLog)) return List.of();
        try {
            var attrs = Files.readAttributes(pluginLog, java.nio.file.attribute.BasicFileAttributes.class);
            long stamp = attrs.size() * 31 + attrs.lastModifiedTime().toMillis();
            PluginIdsCache cached = pluginIdsCache;
            if (cached != null && cached.stamp() == stamp) return cached.ids();
            java.util.Set<String> ids = new java.util.TreeSet<>();
            try (var reader = Files.newBufferedReader(pluginLog, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String id = pluginIdFromLine(line);
                    if (id != null) ids.add(id);
                }
            }
            List<String> out = List.copyOf(ids);
            pluginIdsCache = new PluginIdsCache(stamp, out);
            return out;
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    /** {@code ... plugin.<id>.<source> - msg} → {@code <id>}; null when no plugin logger column. */
    private static String pluginIdFromLine(String line) {
        int start = line.indexOf(" plugin.");
        if (start < 0) return null;
        start += " plugin.".length();
        // The logback pattern separates the logger column from the message with " - ".
        int end = line.indexOf(" - ", start);
        String logger = line.substring(start, end < 0 ? line.length() : end);
        // A real logger column is a dot-separated identifier — spaces mean this "plugin."
        // occurrence was prose inside a message, not the logger column.
        if (logger.isBlank() || logger.indexOf(' ') >= 0) return null;
        int source = logger.lastIndexOf('.');
        if (source <= 0) return logger;
        return logger.substring(0, source);
    }

    private record PluginIdsCache(long stamp, List<String> ids) {}
    private volatile PluginIdsCache pluginIdsCache;

    @GetMapping("/{name}/tail")
    public LogTailView tail(@PathVariable String name,
            @RequestParam(name = "maxBytes", required = false) Integer maxBytes) throws IOException {
        int limit = maxBytes == null ? DEFAULT_TAIL_BYTES
                : Math.max(1, Math.min(maxBytes, MAX_TAIL_BYTES));
        Path dir = logDirectory().toAbsolutePath().normalize();
        Path file = resolveLogFile(dir, name);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No such log file: " + name);
        }
        long size = Files.size(file);
        int read = (int) Math.min(size, limit);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            String content = readTailBytes(channel, size - read, read);
            // A size-capped read starts mid-line (and possibly mid-character): drop the
            // leading partial line so the panel never renders half a row.
            if (read == limit && size > limit) {
                int firstNewline = content.indexOf('\n');
                if (firstNewline >= 0) content = content.substring(firstNewline + 1);
            }
            return new LogTailView(name, size,
                    Files.getLastModifiedTime(file).toInstant(), content);
        }
    }

    /**
     * Reads exactly {@code read} bytes starting at {@code position} and decodes them as UTF-8.
     * {@link FileChannel#read(ByteBuffer)} is not guaranteed to fill the buffer in one call,
     * so the loop keeps reading until the buffer is full — a short single read would decode
     * trailing zero bytes into the panel. A file that shrank mid-read (log rotation) yields
     * whatever bytes are left.
     */
    static String readTailBytes(FileChannel channel, long position, int read) throws IOException {
        channel.position(position);
        ByteBuffer buffer = ByteBuffer.allocate(read);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) break;
        }
        buffer.flip();
        return StandardCharsets.UTF_8.decode(buffer).toString();
    }

    /**
     * The active log directory: {@code fengyu.log.dir} is what HeadlessLauncher primed
     * (it points at the tmpdir fallback when the runtime root is unwritable), so it is
     * the source of truth — the RuntimePaths derivation is the test/IDE fallback.
     * Package-private for tests to override.
     */
    Path logDirectory() {
        String configured = System.getProperty("fengyu.log.dir");
        return configured != null && !configured.isBlank() ? Path.of(configured)
                : fan.summer.fengyu.runtime.RuntimePaths.logDirectory(
                        fan.summer.fengyu.runtime.RuntimePaths.root());
    }

    /** Resolves a single-segment {@code *.log} name inside {@code dir}; anything else is a 400. */
    private static Path resolveLogFile(Path dir, String name) {
        if (name == null || name.isBlank() || !name.endsWith(".log")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Invalid log file name");
        }
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir)) {
            throw new IllegalArgumentException("Invalid log file name");
        }
        return file;
    }
}
