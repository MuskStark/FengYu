package fan.summer.fengyu.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-version and corruption regressions for the crash-recovery journal: a journal
 * written by an older build (extra fields like 4.0.x's {@code tombstoneExisted}) must
 * load instead of wedging every later install behind {@code begin()}'s "already in
 * progress" check, and a journal this build cannot parse at all must be quarantined
 * out of the way (the ledger's pattern) so installs can proceed.
 */
class StoreInstallJournalTest {

    @TempDir
    Path temp;

    private Path journalFile(String content) throws IOException {
        Path file = temp.resolve("transaction.json");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void journalWithUnknownFieldsFromAnOlderBuildLoads() throws Exception {
        Path file = journalFile("""
                {"id":"deadbeef","rootCoordinate":"infinia://plugin/official/markdown",
                 "startedAt":"2026-09-01T00:00:00Z","someFutureTopLevelField":true,
                 "items":[{"coordinate":"infinia://plugin/official/markdown","type":"PLUGIN",
                   "releaseId":"r1","version":"1.0.0","sha256":"abc","localId":null,
                   "applied":true,"committed":false,"oldLedgerEntry":null,
                   "skillBackup":null,"mcpOldContent":null,
                   "tombstoneExisted":false}]}
                """);

        var leftover = StoreInstallJournal.load(temp);

        assertTrue(leftover.isPresent(), "unknown fields must not fail the parse");
        List<StoreInstallJournal.ItemState> items = leftover.get().items();
        assertEquals(1, items.size());
        assertEquals("infinia://plugin/official/markdown", items.get(0).coordinate());
        assertTrue(items.get(0).applied());
        assertTrue(Files.exists(file), "a loadable journal is left in place for recovery");
    }

    @Test
    void malformedJournalIsQuarantinedAndUnwedgesFutureInstalls() throws Exception {
        Path file = journalFile("""
                {"id":"deadbeef","items":[{"coordinate":"infinia://skill/off
                """);

        assertTrue(StoreInstallJournal.load(temp).isEmpty(),
                "an unparseable journal is treated as absent");

        assertTrue(Files.notExists(file), "damaged journal is moved away");
        try (var quarantined = Files.list(temp)) {
            Path corrupt = quarantined.filter(p -> p.getFileName().toString()
                    .startsWith("transaction.json.corrupt-")).findFirst().orElseThrow();
            assertTrue(Files.readString(corrupt).contains("deadbeef"),
                    "quarantine preserves the damaged bytes for inspection");
        }

        // The wedge this guards against: without quarantine, every begin() dies on
        // "A store install transaction is already in progress".
        StoreInstallJournal journal = assertDoesNotThrow(() -> StoreInstallJournal.begin(temp,
                "infinia://plugin/official/markdown",
                List.of(new StoreInstallJournal.ItemState("infinia://plugin/official/markdown",
                        "PLUGIN", "r1", "1.0.0", "abc", null, false, false, null, null, null))));
        journal.delete();
    }
}
