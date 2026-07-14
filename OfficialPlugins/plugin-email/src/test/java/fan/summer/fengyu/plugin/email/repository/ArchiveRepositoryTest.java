package fan.summer.fengyu.plugin.email.repository;

import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.ArchivedMessage;
import fan.summer.fengyu.sdk.PluginDatabaseConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ArchiveRepositoryTest {
    @TempDir Path temp;

    @Test void sqliteRoundTripsArchiveTimestampsAndSearchesByDate() {
        EmailDatabase database = new EmailDatabase(new PluginDatabaseConfig("sqlite", "org.sqlite.JDBC",
            "jdbc:sqlite:" + temp.resolve("email.db"), "", "", temp));
        ArchiveRepository repository = new ArchiveRepository(database);
        Instant sentAt = Instant.parse("2026-04-05T06:07:08.123Z");
        Instant receivedAt = Instant.parse("2026-04-05T06:08:09.456Z");

        long id = repository.insert(new ArchiveRepository.ArchiveEntry(7, "owner@example.com", "INBOX", "42",
            "SQLite archive", "sender@example.com", "{}", sentAt, receivedAt, false, "preview", "/tmp/42.eml"));

        List<ArchivedMessage> matches = repository.search(new ArchiveRepository.SearchCriteria(7L, "INBOX",
            null, null, sentAt.minusMillis(1), sentAt.plusMillis(1), 0, 10));
        ArchivedMessage detail = repository.detail(id).orElseThrow();
        assertEquals(List.of(detail), matches);
        assertEquals(sentAt, detail.sentAt());
        assertEquals(receivedAt, detail.receivedAt());
        assertNotNull(detail.archivedAt());
    }
}
