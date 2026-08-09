package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.setup.DbProvisioningException;
import fan.summer.fengyu.setup.PluginDbProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * User-authorized plugin DB provisioning endpoint. Mirrors the existing {@code network.email}
 * confirm pattern: the frontend shows a confirm dialog, then POSTs here to actually create the
 * per-plugin DB user/schema via {@link PluginDbProvisioner}. Provisioning is NEVER implicit on
 * install or worker spawn — only on this explicit, user-initiated call.
 */
@RestController
public class PluginDbController {
    private static final Logger log = LoggerFactory.getLogger(PluginDbController.class);

    private final PluginPackageService packages;
    private final PluginDbProvisioner provisioner;

    public PluginDbController(PluginPackageService packages, PluginDbProvisioner provisioner) {
        this.packages = packages;
        this.provisioner = provisioner;
    }

    @PostMapping("/api/plugin-db/provision/{id}")
    public ResponseEntity<ProvisionResponse> provision(@PathVariable String id) {
        PluginManifest manifest = packages.find(id).orElse(null);
        if (manifest == null) {
            return ResponseEntity.notFound().build();
        }
        List<String> perms = manifest.permissions() == null ? List.of() : manifest.permissions();
        if (!perms.contains("database")) {
            return ResponseEntity.status(409).body(
                new ProvisionResponse(false, "plugin does not declare the 'database' permission", id));
        }
        try {
            provisioner.provision(id);
            log.info("User authorized DB provisioning for plugin {}", id);
            return ResponseEntity.ok(new ProvisionResponse(true, "provisioned", id));
        } catch (DbProvisioningException e) {
            log.warn("DB provisioning failed for {}: {}", id, e.getMessage());
            return ResponseEntity.status(500).body(new ProvisionResponse(false, e.getMessage(), id));
        }
    }

    @PostMapping("/api/plugin-db/status/{id}")
    public ProvisionResponse status(@PathVariable String id) {
        String state = provisioner.status(id);
        return new ProvisionResponse("provisioned".equals(state), state, id);
    }

    /** Operator-triggered counterpart to the scheduled crash/transient-failure reconciler. */
    @PostMapping("/api/plugin-db/retry/{id}")
    public ProvisionResponse retry(@PathVariable String id) {
        String state = provisioner.retryIncompleteOperation(id);
        return new ProvisionResponse("provisioned".equals(state), state, id);
    }

    /** Response body for provision/status. Never includes the credentials themselves. */
    public record ProvisionResponse(boolean provisioned, String status, String pluginId) {}
}
