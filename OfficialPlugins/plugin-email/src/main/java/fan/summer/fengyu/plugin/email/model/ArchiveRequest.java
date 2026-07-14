package fan.summer.fengyu.plugin.email.model;

import java.nio.file.Path;
import java.time.Instant;

/** Parameters for one explicitly requested IMAP collection run. */
public record ArchiveRequest(long accountId, String folder, Instant start, Instant end, Path outputDirectory) {
}
