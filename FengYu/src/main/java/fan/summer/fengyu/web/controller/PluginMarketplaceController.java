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

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PluginManifest> upload(@RequestPart("file") MultipartFile file) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(packages.install(file));
    }

    @PostMapping("/upload-native")
    public ResponseEntity<PluginManifest> uploadNative(@RequestBody NativeUpload request) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(packages.install(java.nio.file.Path.of(request.path())));
    }

    @PostMapping("/{id}/install")
    public ResponseEntity<PluginManifest> install(@PathVariable String id) throws IOException, InterruptedException {
        return ResponseEntity.status(HttpStatus.CREATED).body(marketplace.install(id));
    }

    @PostMapping("/{id}/update")
    public PluginManifest update(@PathVariable String id) throws IOException, InterruptedException {
        return marketplace.install(id);
    }

    @PatchMapping("/{id}/enabled")
    public Map<String, Object> enabled(@PathVariable String id, @RequestBody EnabledRequest request) throws IOException {
        packages.setEnabled(id, request.enabled());
        if (!request.enabled()) processes.stop(id);
        return Map.of("id", id, "enabled", request.enabled());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> uninstall(@PathVariable String id) throws IOException {
        processes.stop(id);
        packages.uninstall(id);
        // Drop the plugin's captured log buffer and any live log subscribers, so an uninstalled
        // plugin doesn't leave up to CAPACITY stale lines (and a dangling subscriber map entry)
        // behind — and a reinstalled plugin with the same id doesn't surface the old history.
        logStore.clear(id);
        return ResponseEntity.noContent().build();
    }

    public record EnabledRequest(boolean enabled) {}
    public record NativeUpload(String path) {}
}
