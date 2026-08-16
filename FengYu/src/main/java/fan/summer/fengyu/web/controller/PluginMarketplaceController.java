package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.market.ManifestI18n;
import fan.summer.fengyu.plugin.market.MarketplacePlugin;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginMarketplaceService;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginLogStore;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Installation lifecycle API used by both the browser and Tauri shells. */
@RestController
@RequestMapping("/api/plugin-market")
public class PluginMarketplaceController {
    private final PluginMarketplaceService marketplace;
    private final PluginPackageService packages;
    private final PluginProcessManager processes;
    private final PluginLogStore logStore;

    public PluginMarketplaceController(PluginMarketplaceService marketplace, PluginPackageService packages,
            PluginProcessManager processes, PluginLogStore logStore) {
        this.marketplace = marketplace;
        this.packages = packages;
        this.processes = processes;
        this.logStore = logStore;
    }

    @GetMapping
    public List<MarketplacePlugin> list(
            @RequestHeader(name = "Accept-Language", required = false) String acceptLanguage) {
        // Resolve the locale straight from the header and pass it down explicitly. This sidesteps
        // LocaleContextHolder (which can be empty when the LocaleResolver bean isn't wired or when a
        // service call escapes the request thread), so localization never silently falls back to the
        // default locale just because the Spring locale context wasn't populated.
        return marketplace.list(ManifestI18n.resolveLocale(acceptLanguage));
    }

    /**
     * Uploads a {@code .fyp} package, optionally together with its {@code .fyp.sha256}
     * sidecar. The sidecar is mandatory once the user enabled checksum enforcement in
     * Settings (supply-chain hardening); otherwise it is still verified when present.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PluginManifest> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart(name = "sidecar", required = false) MultipartFile sidecar)
            throws IOException, InterruptedException {
        String id = readIncomingId(() -> packages.readArchiveManifest(file));
        return ResponseEntity.status(HttpStatus.CREATED).body(
                installWithUpdateGate(id, () -> packages.install(file, sidecar)));
    }

    @PostMapping("/upload-native")
    public ResponseEntity<PluginManifest> uploadNative(@RequestBody NativeUpload request) throws IOException, InterruptedException {
        String id = readIncomingId(() -> packages.readArchiveManifest(java.nio.file.Path.of(request.path())));
        return ResponseEntity.status(HttpStatus.CREATED).body(installWithUpdateGate(id, () -> packages.install(java.nio.file.Path.of(request.path()))));
    }

    @PostMapping("/{id}/install")
    public ResponseEntity<PluginManifest> install(@PathVariable String id) throws IOException, InterruptedException {
        return ResponseEntity.status(HttpStatus.CREATED).body(installWithUpdateGate(id, () -> marketplace.install(id)));
    }

    @PostMapping("/{id}/update")
    public PluginManifest update(@PathVariable String id) throws IOException, InterruptedException {
        return installWithUpdateGate(id, () -> marketplace.install(id));
    }

    /**
     * Run an install/upgrade inside a per-plugin update gate (P0-6). {@link PluginProcessManager#beginUpdate}
     * marks the id updating (concurrent invokes refuse) and stops the running Worker; the install
     * then swaps the package; {@link PluginProcessManager#endUpdate} re-enables invokes in a
     * finally. beginUpdate throws if the Worker cannot be stopped — that aborts the swap rather than
     * leaving the old code running against a new package (and on Windows a running JVM would also
     * hold the jar, blocking the atomic move).
     */
    private PluginManifest installWithUpdateGate(String id, InstallAction installAction) throws IOException, InterruptedException {
        if (id == null) {
            // Brand-new package whose id could not be previewed: no Worker to stop, just install.
            return installAction.run();
        }
        processes.beginUpdate(id);   // throws on stop failure → install never runs
        try {
            return installAction.run();
        } finally {
            processes.endUpdate(id);
        }
    }

    /** Read the incoming package's manifest (without installing) to learn its id, for the gate. */
    private String readIncomingId(IoManifestReader reader) {
        try {
            PluginManifest incoming = reader.read();
            return incoming == null ? null : incoming.id();
        } catch (IOException | RuntimeException ignored) {
            // If the manifest can't be previewed the install's own validation surfaces the real
            // error; proceed without a gate (a brand-new id has no Worker to stop).
            return null;
        }
    }

    /** Runs the actual install/upgrade, rethrowing its checked exceptions. */
    @FunctionalInterface
    interface InstallAction {
        PluginManifest run() throws IOException, InterruptedException;
    }

    /** Reads a plugin's manifest from an incoming package, throwing {@link IOException} on failure. */
    @FunctionalInterface
    interface IoManifestReader {
        PluginManifest read() throws IOException;
    }

    @PatchMapping("/{id}/enabled")
    public Map<String, Object> enabled(@PathVariable String id, @RequestBody EnabledRequest request) throws IOException {
        packages.setEnabled(id, request.enabled());
        if (!request.enabled()) processes.stop(id);
        return Map.of("id", id, "enabled", request.enabled());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> uninstall(@PathVariable String id,
            @RequestParam(name = "deleteData") boolean deleteData) throws IOException {
        // The update gate (not a bare stop) so an invoke arriving mid-uninstall cannot
        // respawn a worker from the directory being deleted.
        processes.beginUpdate(id);
        try {
            packages.uninstall(id, deleteData);
        } finally {
            processes.endUpdate(id);
        }
        // Drop the plugin's captured log buffer and any live log subscribers, so an uninstalled
        // plugin doesn't leave up to CAPACITY stale lines (and a dangling subscriber map entry)
        // behind — and a reinstalled plugin with the same id doesn't surface the old history.
        logStore.clear(id);
        return ResponseEntity.noContent().build();
    }

    public record EnabledRequest(boolean enabled) {}
    public record NativeUpload(String path) {}
}
