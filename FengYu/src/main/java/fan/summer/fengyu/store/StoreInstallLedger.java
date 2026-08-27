package fan.summer.fengyu.store;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
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
        entries.removeIf(e -> e.coordinate.equals(coordinate));
        entries.add(new Entry(coordinate, type, localId, version, sha256,
                Instant.now().toString()));
        save();
    }

    public synchronized boolean remove(String coordinate) {
        boolean removed = entries.removeIf(e -> e.coordinate.equals(coordinate));
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
            entries.addAll(mapper.readValue(file.toFile(), type));
        } catch (IOException e) {
            // A corrupt ledger must not brick the store view; entries rebuild on install.
            throw new IllegalStateException("Cannot read store install ledger " + file, e);
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
