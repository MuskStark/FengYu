package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginLogStore;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Routes install/update/uninstall by source type. */
@Service
public class InstallerDispatcher {
    private final PluginPackageService packages;
    private final AgentContentInstaller agent;
    private final PluginProcessManager processes;
    private final PluginLogStore logs;

    /** Test/backwards-compatible constructor; runtime gates are supplied by Spring in production. */
    public InstallerDispatcher(PluginPackageService packages, AgentContentInstaller agent) {
        this(packages, agent, null, null);
    }

    @Autowired
    public InstallerDispatcher(PluginPackageService packages, AgentContentInstaller agent,
            PluginProcessManager processes, PluginLogStore logs) {
        this.packages = packages;
        this.agent = agent;
        this.processes = processes;
        this.logs = logs;
    }

    public void install(UnifiedCatalogEntry entry) {
        switch (entry.sourceType()) {
            case FENGYU -> installFengyu(entry);
            case CLAUDE, CODEX -> agent.install(entry);
        }
    }

    public void update(UnifiedCatalogEntry entry) {
        // update == reinstall for both paths
        install(entry);
    }

    public void uninstall(UnifiedCatalogEntry entry, boolean deleteData) {
        switch (entry.sourceType()) {
            case FENGYU -> uninstallFengyu(entry, deleteData);
            case CLAUDE, CODEX -> agent.uninstall(entry.uid());
        }
    }

    public void setEnabled(UnifiedCatalogEntry entry, boolean enabled) {
        switch (entry.sourceType()) {
            case FENGYU -> setEnabledFengyu(entry, enabled);
            case CLAUDE, CODEX -> agent.setEnabled(entry.uid(), enabled);
        }
    }

    private void installFengyu(UnifiedCatalogEntry entry) {
        if (!(entry.sourceRef() instanceof UnifiedCatalogEntry.ZipUrlSource zip))
            throw new IllegalArgumentException("FengYu entry has no download URL: " + entry.uid());
        try {
            if (processes != null) processes.beginUpdate(entry.name());
            try {
                packages.installFromUrl(zip.url());
            } finally {
                if (processes != null) processes.endUpdate(entry.name());
            }
        } catch (IllegalArgumentException e) {
            // Validation verdicts (bad URL scheme, digest mismatch, manifest rejection, ...)
            // already carry a user-actionable message mapped to 400 — rewrapping them into a
            // generic 500 "internal error" hid the reason from the store UI.
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("FengYu install failed: " + entry.uid(), e);
        }
    }

    // PluginPackageService.uninstall/setEnabled declare checked IOException; wrap them so the
    // dispatcher's public methods remain unchecked — mirroring installFengyu's handling.
    private void uninstallFengyu(UnifiedCatalogEntry entry, boolean deleteData) {
        try {
            if (processes != null) processes.stop(entry.name());
            packages.uninstall(entry.name(), deleteData);
            if (logs != null) logs.clear(entry.name());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("FengYu uninstall failed: " + entry.uid(), e);
        }
    }

    private void setEnabledFengyu(UnifiedCatalogEntry entry, boolean enabled) {
        try {
            packages.setEnabled(entry.name(), enabled);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("FengYu setEnabled failed: " + entry.uid(), e);
        }
    }
}
