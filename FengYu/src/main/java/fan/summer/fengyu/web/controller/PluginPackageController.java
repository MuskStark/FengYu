package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.market.PackageInspection;
import fan.summer.fengyu.plugin.market.PluginLifecycleOrchestrator;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Local {@code .fyp} package lifecycle API: upload (browser and desktop-native), pre-install
 * inspection, enable/disable and uninstall. Catalog browsing and remote installs live with the
 * store surfaces ({@code /api/store}, {@code /api/plugin-store}). Runtime gating (worker stop,
 * health preflight, commit/rollback) is owned by {@link PluginLifecycleOrchestrator}.
 */
@RestController
@RequestMapping("/api/plugin-packages")
public class PluginPackageController {
    private final PluginPackageService packages;
    private final PluginLifecycleOrchestrator lifecycle;

    public PluginPackageController(PluginPackageService packages,
            PluginLifecycleOrchestrator lifecycle) {
        this.packages = packages;
        this.lifecycle = lifecycle;
    }

    /**
     * Uploads a {@code .fyp} package, optionally together with its {@code .fyp.sha256}
     * sidecar. The sidecar is mandatory once the user enabled checksum enforcement in
     * Settings (supply-chain hardening); otherwise it is still verified when present.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PluginManifest> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart(name = "sidecar", required = false) MultipartFile sidecar,
            @RequestParam(name = "confirmPermissions", defaultValue = "false") boolean confirmPermissions)
            throws IOException, InterruptedException {
        String id = readIncomingId(() -> packages.readArchiveManifest(file));
        return ResponseEntity.status(HttpStatus.CREATED).body(
                lifecycle.installWithUpdateGate(id,
                        () -> packages.install(file, sidecar, confirmPermissions)));
    }

    @PostMapping("/upload-native")
    public ResponseEntity<PluginManifest> uploadNative(@RequestBody NativeUpload request) throws IOException, InterruptedException {
        String id = readIncomingId(() -> packages.readArchiveManifest(java.nio.file.Path.of(request.path())));
        return ResponseEntity.status(HttpStatus.CREATED).body(
                lifecycle.installWithUpdateGate(id,
                        () -> packages.install(java.nio.file.Path.of(request.path()),
                                Boolean.TRUE.equals(request.confirmPermissions()))));
    }

    /**
     * Pre-install inspection of an uploaded {@code .fyp}: reads the package's manifest WITHOUT
     * installing and reports what the upload would do (new install vs update, and the version
     * step), so the UI can confirm a local-package update — warning on a downgrade or a
     * same-version reinstall — before the upload stops the running Worker and swaps the
     * installed directory.
     */
    @PostMapping(value = "/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PackageInspection inspect(@RequestPart("file") MultipartFile file) throws IOException {
        PluginManifest incoming = packages.readArchiveManifest(file);
        return PackageInspection.of(incoming, packages.find(incoming.id()));
    }

    /** Path-based twin of {@link #inspect} for the desktop shell's native file picker. */
    @PostMapping("/inspect-native")
    public PackageInspection inspectNative(@RequestBody NativeUpload request) throws IOException {
        PluginManifest incoming = packages.readArchiveManifest(java.nio.file.Path.of(request.path()));
        return PackageInspection.of(incoming, packages.find(incoming.id()));
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

    /** Reads a plugin's manifest from an incoming package, throwing {@link IOException} on failure. */
    @FunctionalInterface
    interface IoManifestReader {
        PluginManifest read() throws IOException;
    }

    @PatchMapping("/{id}/enabled")
    public Map<String, Object> enabled(@PathVariable String id, @RequestBody EnabledRequest request) throws IOException {
        packages.setEnabled(id, request.enabled());
        if (!request.enabled()) lifecycle.stopWorker(id);
        return Map.of("id", id, "enabled", request.enabled());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> uninstall(@PathVariable String id,
            @RequestParam(name = "deleteData") boolean deleteData) throws IOException {
        lifecycle.uninstallWithGate(id, deleteData);
        return ResponseEntity.noContent().build();
    }

    public record EnabledRequest(boolean enabled) {}
    public record NativeUpload(String path, Boolean confirmPermissions) {
        public NativeUpload(String path) { this(path, null); }
    }
}
