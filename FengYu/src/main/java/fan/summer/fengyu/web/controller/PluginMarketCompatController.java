package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.market.PackageInspection;
import fan.summer.fengyu.plugin.market.PluginLifecycleOrchestrator;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
 * Deprecated compatibility aliases for the pre-RC {@code /api/plugin-market} API
 * (design §10.3: keep the legacy surface instead of breaking integrations in one
 * shot). The local-package lifecycle endpoints moved to
 * {@code /api/plugin-packages} and are forwarded 1:1 with deprecation headers;
 * the remote-catalog endpoints were superseded by the unified store surfaces
 * ({@code /api/plugin-store}, {@code /api/store}) and answer {@code 410 Gone}
 * with their replacement, so an integrator is never left guessing.
 */
@RestController
@RequestMapping("/api/plugin-market")
public class PluginMarketCompatController {

    private static final String DEPRECATION = "version=\"4.0.0-rc.1\"";

    private final PluginPackageService packages;
    private final PluginLifecycleOrchestrator lifecycle;

    public PluginMarketCompatController(PluginPackageService packages,
            PluginLifecycleOrchestrator lifecycle) {
        this.packages = packages;
        this.lifecycle = lifecycle;
    }

    // ---- removed with the unified store: explicit 410 + replacement ----

    /** The remote catalog list moved to the unified store catalog. */
    @GetMapping
    public ResponseEntity<Map<String, String>> list() {
        return gone("/api/plugin-store/catalog",
                "The plugin catalog moved to the unified store surfaces");
    }

    /** Catalog installs moved to the unified store ({@code /api/plugin-store}). */
    @PostMapping("/{id}/install")
    public ResponseEntity<Map<String, String>> install(@PathVariable String id) {
        return gone("/api/plugin-store/{uid}/install",
                "Catalog installs moved to the unified store surfaces");
    }

    /** Catalog updates moved to the unified store ({@code /api/plugin-store}). */
    @PostMapping("/{id}/update")
    public ResponseEntity<Map<String, String>> update(@PathVariable String id) {
        return gone("/api/plugin-store/{uid}/update",
                "Catalog updates moved to the unified store surfaces");
    }

    // ---- lifecycle aliases: 1:1 forwards with deprecation headers ----

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PluginManifest> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart(name = "sidecar", required = false) MultipartFile sidecar,
            @RequestParam(name = "confirmPermissions", defaultValue = "false") boolean confirmPermissions)
            throws IOException, InterruptedException {
        return deprecated(ResponseEntity.status(HttpStatus.CREATED).body(
                installWithGate(previewId(file), () ->
                        packages.install(file, sidecar, confirmPermissions))));
    }

    @PostMapping("/upload-native")
    public ResponseEntity<PluginManifest> uploadNative(
            @RequestBody PluginPackageController.NativeUpload request)
            throws IOException, InterruptedException {
        java.nio.file.Path archive = java.nio.file.Path.of(request.path());
        return deprecated(ResponseEntity.status(HttpStatus.CREATED).body(
                installWithGate(previewId(archive), () ->
                        packages.install(archive,
                                Boolean.TRUE.equals(request.confirmPermissions())))));
    }

    @PostMapping(value = "/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PackageInspection inspect(@RequestPart("file") MultipartFile file)
            throws IOException {
        PluginManifest incoming = packages.readArchiveManifest(file);
        return PackageInspection.of(incoming, packages.find(incoming.id()));
    }

    @PostMapping("/inspect-native")
    public PackageInspection inspectNative(
            @RequestBody PluginPackageController.NativeUpload request) throws IOException {
        PluginManifest incoming =
                packages.readArchiveManifest(java.nio.file.Path.of(request.path()));
        return PackageInspection.of(incoming, packages.find(incoming.id()));
    }

    @PatchMapping("/{id}/enabled")
    public ResponseEntity<Map<String, Object>> enabled(@PathVariable String id,
            @RequestBody PluginPackageController.EnabledRequest request)
            throws IOException {
        packages.setEnabled(id, request.enabled());
        if (!request.enabled()) {
            lifecycle.stopWorker(id);
        }
        return deprecated(ResponseEntity.ok(
                Map.of("id", id, "enabled", request.enabled())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> uninstall(@PathVariable String id,
            @RequestParam(name = "deleteData") boolean deleteData) throws IOException {
        lifecycle.uninstallWithGate(id, deleteData);
        return deprecated(ResponseEntity.noContent().build());
    }

    // ---- shared sequencing (identical to PluginPackageController) ----

    private PluginManifest installWithGate(String id,
            PluginLifecycleOrchestrator.InstallAction installAction)
            throws IOException, InterruptedException {
        return lifecycle.installWithUpdateGate(id, installAction);
    }

    /** Preview the incoming package's id for the gate; unpreviewable ids install ungated. */
    private String previewId(MultipartFile file) {
        try {
            PluginManifest incoming = packages.readArchiveManifest(file);
            return incoming == null ? null : incoming.id();
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private String previewId(java.nio.file.Path archive) {
        try {
            PluginManifest incoming = packages.readArchiveManifest(archive);
            return incoming == null ? null : incoming.id();
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static <T> ResponseEntity<T> deprecated(ResponseEntity<T> response) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        headers.add("Deprecation", DEPRECATION);
        headers.add("Link", "</api/plugin-packages>; rel=\"deprecation\"");
        return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
    }

    private static ResponseEntity<Map<String, String>> gone(String replacement,
            String detail) {
        return ResponseEntity.status(HttpStatus.GONE)
                .header("Deprecation", DEPRECATION)
                .header("Link", "<" + replacement + ">; rel=\"deprecation\"")
                .body(Map.of("error", detail, "replacement", replacement));
    }
}
