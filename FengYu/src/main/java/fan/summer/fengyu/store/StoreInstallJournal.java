package fan.summer.fengyu.store;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Crash-recovery journal for multi-artifact store transactions (design §9.2,
 * review M-8). The journal file is written <b>before</b> any installer runs,
 * updated as each plan item mutates local state, and deleted only after every
 * installer, runtime preflight and the ledger commit succeeded. A journal file
 * present at startup marks an interrupted transaction; {@code StoreService}
 * rolls the applied items back in reverse order.
 *
 * <p>Old state capture: plugin items rely on the package service's own
 * retained rollback snapshot; skill directories are copied under the
 * transaction's backup directory; a previous imported MCP server file is kept
 * inline (base64 — templates are tiny).
 */
public final class StoreInstallJournal {

    private static final Logger log = LoggerFactory.getLogger(StoreInstallJournal.class);

    static final String FILE_NAME = "transaction.json";

    /**
     * One plan item and everything needed to undo it: the store coordinates,
     * the local identity the installer produced, whether it was applied /
     * committed, and the prior ledger entry plus type-specific old state.
     */
    public record ItemState(
            String coordinate,
            String type,
            String releaseId,
            String version,
            String sha256,
            String localId,
            boolean applied,
            boolean committed,
            StoreInstallLedger.Entry oldLedgerEntry,
            String skillBackup,
            String mcpOldContent) {}

    public record PendingTransaction(String id, String rootCoordinate, String startedAt,
            List<ItemState> items) {}

    private final Path file;
    private final Path backupDir;
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private PendingTransaction tx;

    private StoreInstallJournal(Path file, Path backupDir, PendingTransaction tx) {
        this.file = file;
        this.backupDir = backupDir;
        this.tx = tx;
    }

    /** Opens (and persists) a new transaction; refuses to start over an existing one. */
    public static StoreInstallJournal begin(Path storeDir, String rootCoordinate,
            List<ItemState> items) throws IOException {
        Path file = storeDir.resolve(FILE_NAME);
        if (Files.exists(file)) {
            throw new IllegalStateException(
                    "A store install transaction is already in progress: " + file);
        }
        String id = UUID.randomUUID().toString().substring(0, 12);
        StoreInstallJournal journal = new StoreInstallJournal(file,
                storeDir.resolve("txn-backup-" + id),
                new PendingTransaction(id, rootCoordinate, Instant.now().toString(), items));
        journal.persist();
        return journal;
    }

    /** Loads a leftover journal, if one exists (quarantining it if unreadable). */
    public static Optional<PendingTransaction> load(Path storeDir) {
        Path file = storeDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JsonMapper.builder().findAndAddModules().build()
                    .readValue(file.toFile(), PendingTransaction.class));
        } catch (IOException | RuntimeException corrupt) {
            log.warn("Unreadable store transaction journal {} ignored ({})",
                    file, corrupt.toString());
            return Optional.empty();
        }
    }

    /** Re-attaches to a loaded transaction so recovery can mutate/delete it. */
    public static StoreInstallJournal attach(Path storeDir, PendingTransaction tx) {
        return new StoreInstallJournal(storeDir.resolve(FILE_NAME),
                storeDir.resolve("txn-backup-" + tx.id()), tx);
    }

    public List<ItemState> items() {
        return tx.items();
    }

    public ItemState item(String coordinate) {
        return tx.items().stream()
                .filter(i -> coordinate.equals(i.coordinate())).findFirst().orElseThrow();
    }

    /** Directory for pre-install backups of replaced local state (skills). */
    public Path backupDir() {
        return backupDir;
    }

    public synchronized void markApplied(String coordinate, String localId) {
        mutate(coordinate, item -> new ItemState(item.coordinate(), item.type(),
                item.releaseId(), item.version(), item.sha256(), localId, true,
                item.committed(), item.oldLedgerEntry(), item.skillBackup(),
                item.mcpOldContent()));
    }

    public synchronized void noteSkillBackup(String coordinate, String backupName) {
        mutate(coordinate, item -> new ItemState(item.coordinate(), item.type(),
                item.releaseId(), item.version(), item.sha256(), item.localId(),
                item.applied(), item.committed(), item.oldLedgerEntry(), backupName,
                item.mcpOldContent()));
    }

    /** Records the ticket-attested SHA-256 that drove this item's download. */
    public synchronized void noteTicketSha(String coordinate, String sha256) {
        mutate(coordinate, item -> new ItemState(item.coordinate(), item.type(),
                item.releaseId(), item.version(), sha256, item.localId(),
                item.applied(), item.committed(), item.oldLedgerEntry(),
                item.skillBackup(), item.mcpOldContent()));
    }

    public synchronized void noteMcpOld(String coordinate, String base64OrNull) {
        mutate(coordinate, item -> new ItemState(item.coordinate(), item.type(),
                item.releaseId(), item.version(), item.sha256(), item.localId(),
                item.applied(), item.committed(), item.oldLedgerEntry(),
                item.skillBackup(), base64OrNull));
    }

    public synchronized void markCommitted(String coordinate) {
        mutate(coordinate, item -> new ItemState(item.coordinate(), item.type(),
                item.releaseId(), item.version(), item.sha256(), item.localId(),
                item.applied(), true, item.oldLedgerEntry(), item.skillBackup(),
                item.mcpOldContent()));
    }

    /** Removes the journal and its backup directory (transaction finished, either way). */
    public void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not remove store transaction journal {}: {}", file, e.toString());
        }
        deleteTree(backupDir);
    }

    private void mutate(String coordinate, java.util.function.UnaryOperator<ItemState> change) {
        List<ItemState> next = new ArrayList<>();
        for (ItemState item : tx.items()) {
            next.add(coordinate.equals(item.coordinate()) ? change.apply(item) : item);
        }
        tx = new PendingTransaction(tx.id(), tx.rootCoordinate(), tx.startedAt(), next);
        try {
            persist();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist store transaction journal", e);
        }
    }

    private void persist() throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp-"
                + Thread.currentThread().getId());
        Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(tx), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("Could not remove store transaction backup {}: {}", root, e.toString());
        }
    }
}
