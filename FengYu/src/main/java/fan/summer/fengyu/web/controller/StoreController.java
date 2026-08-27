package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.store.StoreModels.ListingDetail;
import fan.summer.fengyu.store.StoreService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Native store endpoints (design §10.3): the SPA talks to the loopback host,
 * the host talks to the store. Controllers stay protocol shims — all install
 * logic lives in StoreService.
 */
@RestController
@RequestMapping("/api/store")
public class StoreController {

    private final StoreService store;

    public StoreController(StoreService store) {
        this.store = store;
    }

    public record InstallBody(String coordinate, Boolean confirmPermissions) {}

    @GetMapping("/catalog")
    public List<StoreService.CatalogView> catalog(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String query)
            throws IOException, InterruptedException {
        return store.catalog(type, query);
    }

    @GetMapping("/listings/{namespace}/{slug}")
    public ListingDetail listing(@PathVariable String namespace, @PathVariable String slug)
            throws IOException, InterruptedException {
        return store.listing(namespace, slug);
    }

    @GetMapping("/installed")
    public List<StoreService.InstalledView> installed() {
        return store.installed();
    }

    @GetMapping("/updates")
    public List<StoreService.UpdateView> updates() throws IOException, InterruptedException {
        return store.updates();
    }

    @PostMapping("/install")
    public StoreService.InstallResult install(@RequestBody InstallBody body)
            throws IOException, InterruptedException {
        if (body.coordinate() == null || body.coordinate().isBlank()) {
            throw new IllegalArgumentException("coordinate is required");
        }
        return store.install(body.coordinate(),
                Boolean.TRUE.equals(body.confirmPermissions()));
    }

    @DeleteMapping("/installed")
    public ResponseEntity<Void> uninstall(@RequestParam String coordinate,
            @RequestParam(required = false, defaultValue = "false") boolean deleteData)
            throws IOException {
        store.uninstall(coordinate, deleteData);
        return ResponseEntity.noContent().build();
    }

    public record StoreStatus(String apiBase) {}

    @GetMapping("/status")
    public StoreStatus status() {
        return new StoreStatus(store.catalogApiBase());
    }
}
