package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import fan.summer.fengyu.plugin.market.PluginLifecycleOrchestrator;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Routes install/update/uninstall by source type. */
@Service
public class InstallerDispatcher {
    private static final Logger log = LoggerFactory.getLogger(InstallerDispatcher.class);
    private final PluginPackageService packages;
    private final AgentContentInstaller agent;
    private final PluginLifecycleOrchestrator lifecycle;
    private final ObjectProvider<McpRuntimeManager> mcpRuntime;

    /** Test/backwards-compatible constructor; runtime gates are absent by design. */
    public InstallerDispatcher(PluginPackageService packages, AgentContentInstaller agent) {
        this(packages, agent, null, null, null, null);
    }

    public InstallerDispatcher(PluginPackageService packages, AgentContentInstaller agent,
            fan.summer.fengyu.plugin.runtime.PluginProcessManager processes,
            fan.summer.fengyu.plugin.runtime.PluginLogStore logs) {
        this(packages, agent, processes, logs, null, null);
    }

    @Autowired
    public InstallerDispatcher(PluginPackageService packages, AgentContentInstaller agent,
            fan.summer.fengyu.plugin.runtime.PluginProcessManager processes,
            fan.summer.fengyu.plugin.runtime.PluginLogStore logs,
            ObjectProvider<McpRuntimeManager> mcpRuntime,
            PluginLifecycleOrchestrator lifecycle) {
        this.packages = packages;
        this.agent = agent;
        this.lifecycle = lifecycle != null ? lifecycle
                : (processes != null
                        ? new PluginLifecycleOrchestrator(packages, processes, logs)
                        : null);
        this.mcpRuntime = mcpRuntime;
    }

    public void install(UnifiedCatalogEntry entry) {
        switch (entry.sourceType()) {
            case FENGYU -> installFengyu(entry, false);
            case CLAUDE, CODEX, GROK -> {
                agent.install(entry);
                syncImportedMcpServers();
            }
        }
    }

    public void update(UnifiedCatalogEntry entry) {
        update(entry, false);
    }

    public void update(UnifiedCatalogEntry entry, boolean confirmPermissionEscalation) {
        // update == reinstall for both paths
        switch (entry.sourceType()) {
            case FENGYU -> installFengyu(entry, confirmPermissionEscalation);
            case CLAUDE, CODEX, GROK -> {
                agent.install(entry);
                syncImportedMcpServers();
            }
        }
    }

    public void uninstall(UnifiedCatalogEntry entry, boolean deleteData) {
        switch (entry.sourceType()) {
            case FENGYU -> uninstallFengyu(entry, deleteData);
            case CLAUDE, CODEX, GROK -> {
                agent.uninstall(entry.uid());
                syncImportedMcpServers();
            }
        }
    }

    /**
     * Agent-content plugins may declare {@code mcpServers}; the installer writes them to
     * {@code mcp-servers/<uid>.json} and the runtime picks them up as disabled servers. Fail-open:
     * a store operation must not report failure because an MCP rescan hiccupped.
     */
    private void syncImportedMcpServers() {
        if (mcpRuntime == null) return;
        try {
            McpRuntimeManager runtime = mcpRuntime.getIfAvailable();
            if (runtime != null) runtime.syncImportedServers();
        } catch (Exception error) {
            log.warn("Could not refresh plugin-provided MCP servers: {}", error.toString());
        }
    }

    public void setEnabled(UnifiedCatalogEntry entry, boolean enabled) {
        switch (entry.sourceType()) {
            case FENGYU -> setEnabledFengyu(entry, enabled);
            case CLAUDE, CODEX, GROK -> agent.setEnabled(entry.uid(), enabled);
        }
    }

    private void installFengyu(UnifiedCatalogEntry entry, boolean confirmPermissionEscalation) {
        if (!(entry.sourceRef() instanceof UnifiedCatalogEntry.ZipUrlSource zip))
            throw new IllegalArgumentException("FengYu entry has no download URL: " + entry.uid());
        try {
            if (lifecycle != null) {
                lifecycle.installWithUpdateGate(entry.name(), () -> packages.installFromUrl(
                        zip.url(), entry.sha256(), entry.signature(), entry.keyId(),
                        confirmPermissionEscalation));
            } else {
                // Legacy/test constructor without runtime gates: install and commit
                // immediately so a successful swap never looks interrupted at
                // startup recovery.
                packages.installFromUrl(zip.url(), entry.sha256(), entry.signature(),
                        entry.keyId(), confirmPermissionEscalation);
                packages.commitUpdate(entry.name());
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
            if (lifecycle != null) {
                lifecycle.uninstallWithGate(entry.name(), deleteData);
            } else {
                packages.uninstall(entry.name(), deleteData);
            }
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
