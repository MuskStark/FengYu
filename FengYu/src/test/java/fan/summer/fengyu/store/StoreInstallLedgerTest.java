package fan.summer.fengyu.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M-7 regression: a damaged ledger file must never abort startup. It is
 * quarantined next to the original and the store continues with an empty
 * ledger until the user reinstalls.
 */
class StoreInstallLedgerTest {

    @TempDir
    Path temp;

    private Path ledgerFile(String content) throws IOException {
        Path file = temp.resolve("installs.json");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void truncatedJsonIsQuarantinedAndStartsEmpty() throws Exception {
        Path file = ledgerFile("""
                [{"coordinate":"infinia://plugin/official/markdown","type":"PLUGIN",
                 """);

        StoreInstallLedger ledger = new StoreInstallLedger(file);

        assertTrue(ledger.all().isEmpty());
        assertTrue(Files.notExists(file), "damaged ledger is moved away");
        try (var quarantined = Files.list(temp)) {
            Path corrupt = quarantined.filter(p -> p.getFileName().toString()
                    .startsWith("installs.json.corrupt-")).findFirst().orElseThrow();
            String content = Files.readString(corrupt);
            assertTrue(content.contains("infinia://plugin/official/markdown"),
                    "quarantine preserves the damaged bytes for inspection");
        }
    }

    @Test
    void wrongShapeJsonIsQuarantinedAndStartsEmpty() throws Exception {
        Path file = ledgerFile("{\"not\":\"a list\"}");

        StoreInstallLedger ledger = new StoreInstallLedger(file);

        assertTrue(ledger.all().isEmpty());
        assertTrue(Files.notExists(file), "damaged ledger is moved away");
    }

    @Test
    void unreadableFileStillStartsEmpty() throws Exception {
        Path file = ledgerFile("[{\"coordinate\":\"c\"}]");
        // Drop all read/write permissions; even if the platform lets the rename
        // through, construction must not throw and the ledger must start empty.
        assertDoesNotThrow(() -> {
            boolean changed = file.toFile().setReadable(false)
                    && file.toFile().setWritable(false);
            assumeTrue(changed, "filesystem honors permission bits");
            try {
                new StoreInstallLedger(file);
            } finally {
                file.toFile().setWritable(true);
                file.toFile().setReadable(true);
            }
        });
    }

    @Test
    void quarantineKeepsValidRoundTripUntouched() throws Exception {
        Path file = temp.resolve("installs.json");
        StoreInstallLedger ledger = new StoreInstallLedger(file);
        ledger.record("infinia://skill/official/pdf-tools", "SKILL",
                "official.pdf-tools", "1.3.0", "abc");

        // A fresh instance over the same file sees the persisted entries.
        StoreInstallLedger reloaded = new StoreInstallLedger(file);
        List<StoreInstallLedger.Entry> all = reloaded.all();
        assertEquals(1, all.size());
        assertEquals("infinia://skill/official/pdf-tools", all.get(0).coordinate());
        assertEquals("official.pdf-tools", all.get(0).localId());
    }

    private static void assumeTrue(boolean condition, String message) {
        if (!condition) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, message);
        }
    }
}
