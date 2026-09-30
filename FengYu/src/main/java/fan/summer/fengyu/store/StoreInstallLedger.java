package fan.summer.fengyu.store;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Local install ledger mapping store coordinates to installed local identities
 * (design §11.2 store_install_record). Plugins/skills keep their authoritative
 * state on disk; this ledger owns the coordinate binding, the installed store
 * version and the SHA-256 that drove the install, which is what update checks
 * compare against.
 */
@Service
public class StoreInstallLedger {

    private static final Logger log = LoggerFactory.getLogger(StoreInstallLedger.class);

    public record Entry(
            String coordinate,
            String type,
            String localId,
            String version,
            String sha256,
            String installedAt) {}

    private final Path file;
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final List<Entry> entries = new ArrayList<>();

    @org.springframework.beans.factory.annotation.Autowired
    public StoreInstallLedger(
            @Value("#{T(fan.summer.fengyu.runtime.RuntimePaths).root().toString()}") String runtimeRoot) {
        this(Path.of(runtimeRoot).resolve("store").resolve("installs.json"));
    }

    /** Also the test seam: an explicit ledger file location. */
    StoreInstallLedger(Path file) {
        this.file = file;
        load();
    }

    public synchronized List<Entry> all() {
        return List.copyOf(entries);
    }

    public synchronized Optional<Entry> find(String coordinate) {
        return entries.stream().filter(e -> e.coordinate.equals(coordinate)).findFirst();
    }

    public synchronized void record(String coordinate, String type, String localId,
            String version, String sha256) {
        entries.removeIf(e -> e.coordinate().equals(coordinate));
        entries.add(new Entry(coordinate, type, localId, version, sha256,
                Instant.now().toString()));
        save();
    }

    /**
     * Records a batch in one atomic save — the multi-artifact store plan commits
     * all of its ledger entries together or not at all.
     */
    public synchronized void recordAll(List<Entry> newEntries) {
        for (Entry entry : newEntries) {
            entries.removeIf(e -> e.coordinate().equals(entry.coordinate()));
        }
        entries.addAll(newEntries);
        save();
    }

    /**
     * Restores a transaction's prior state for one coordinate: removes whatever
     * the transaction left there and, when the item had a previous entry, puts
     * that entry back verbatim (preserving its installedAt) — used by rollback
     * and startup recovery.
     */
    public synchronized void restore(String coordinate,
            @org.springframework.lang.Nullable Entry entry) {
        entries.removeIf(e -> e.coordinate().equals(coordinate));
        if (entry != null) {
            entries.add(entry);
        }
        save();
    }

    public synchronized boolean remove(String coordinate) {
        boolean removed = entries.removeIf(e -> e.coordinate().equals(coordinate));
        if (removed) {
            save();
        }
        return removed;
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            CollectionType type = mapper.getTypeFactory()
                    .constructCollectionType(List.class, Entry.class);
            List<Entry> restored = mapper.readValue(file.toFile(), type);
            restored.stream().filter(e -> e != null && e.coordinate() != null)
                    .forEach(entries::add);
        } catch (IOException | RuntimeException e) {
            // A corrupt ledger must not brick the store (or the whole app context):
            // quarantine the file for inspection and continue with an empty ledger;
            // entries rebuild as users reinstall (design §11.2).
            quarantine(e);
        }
    }

    private void quarantine(Exception cause) {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC).format(Instant.now());
        Path quarantineFile = file.resolveSibling(
                file.getFileName() + ".corrupt-" + stamp);
        try {
            Files.move(file, quarantineFile, StandardCopyOption.REPLACE_EXISTING);
            log.warn("Store install ledger {} was unreadable and has been quarantined "
                    + "as {}; starting with an empty ledger", file, quarantineFile, cause);
        } catch (IOException moveFailure) {
            // Even without the rename the ledger starts empty; the broken file stays
            // put so the user can recover it manually.
            log.warn("Store install ledger {} is unreadable and could not be "
                    + "quarantined ({}); starting with an empty ledger",
                    file, moveFailure.toString(), cause);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp-"
                    + Thread.currentThread().getId());
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(entries), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist store install ledger", e);
        }
    }
}
