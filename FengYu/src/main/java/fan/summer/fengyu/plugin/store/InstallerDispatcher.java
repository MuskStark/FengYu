package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.plugin.market.PluginPackageService;
import org.springframework.stereotype.Service;

/** Routes install/update/uninstall by source type. */
@Service
public class InstallerDispatcher {
    private final PluginPackageService packages;
    private final AgentContentInstaller agent;

    public InstallerDispatcher(PluginPackageService packages, AgentContentInstaller agent) {
        this.packages = packages;
        this.agent = agent;
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

    public void uninstall(UnifiedCatalogEntry entry) {
        switch (entry.sourceType()) {
            case FENGYU -> uninstallFengyu(entry);
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
            packages.installFromUrl(zip.url());
        } catch (Exception e) {
            throw new RuntimeException("FengYu install failed: " + entry.uid(), e);
        }
    }

    // PluginPackageService.uninstall/setEnabled declare checked IOException; wrap them so the
    // dispatcher's public methods remain unchecked — mirroring installFengyu's handling.
    private void uninstallFengyu(UnifiedCatalogEntry entry) {
        try {
            packages.uninstall(entry.name());
        } catch (Exception e) {
            throw new RuntimeException("FengYu uninstall failed: " + entry.uid(), e);
        }
    }

    private void setEnabledFengyu(UnifiedCatalogEntry entry, boolean enabled) {
        try {
            packages.setEnabled(entry.name(), enabled);
        } catch (Exception e) {
            throw new RuntimeException("FengYu setEnabled failed: " + entry.uid(), e);
        }
    }
}
