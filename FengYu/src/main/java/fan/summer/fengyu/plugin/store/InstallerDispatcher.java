package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.entity.store.PluginInstallRecordEntity;
import fan.summer.fengyu.database.repository.PluginInstallRecordRepository;
import fan.summer.fengyu.plugin.market.PluginLifecycleOrchestrator;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/** Routes install/update/uninstall by source type. */
@Service
public class InstallerDispatcher {
    private static final Logger log = LoggerFactory.getLogger(InstallerDispatcher.class);
    private final PluginPackageService packages;
    private final AgentContentInstaller agent;
    private final PluginLifecycleOrchestrator lifecycle;
    private final ObjectProvider<McpRuntimeManager> mcpRuntime;
    /** P2-13: binds a FENGYU catalog uid to the REAL installed plugin id (null in tests). */
    private final PluginInstallRecordRepository records;

    /** Test/backwards-compatible constructor; runtime gates are absent by design. */
    public InstallerDispatcher(PluginPackageService packages, AgentContentInstaller agent) {
        this(packages, agent, null, null, null, null, null);
    }

    public InstallerDispatcher(PluginPackageService packages, AgentContentInstaller agent,
            fan.summer.fengyu.plugin.runtime.PluginProcessManager processes,
            fan.summer.fengyu.plugin.runtime.PluginLogStore logs) {
        this(packages, agent, processes, logs, null, null, null);
    }

    @Autowired
    public InstallerDispatcher(PluginPackageService packages, AgentContentInstaller agent,
            fan.summer.fengyu.plugin.runtime.PluginProcessManager processes,
            fan.summer.fengyu.plugin.runtime.PluginLogStore logs,
            ObjectProvider<McpRuntimeManager> mcpRuntime,
            PluginLifecycleOrchestrator lifecycle,
            PluginInstallRecordRepository records) {
        this.packages = packages;
        this.agent = agent;
        this.lifecycle = lifecycle != null ? lifecycle
                : (processes != null
                        ? new PluginLifecycleOrchestrator(packages, processes, logs)
                        : null);
        this.mcpRuntime = mcpRuntime;
        this.records = records;
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
        Path staging = null;
        try {
            // P2-13: download FIRST, read the package's REAL manifest id, and key the update gate
            // on that id. The previous code gated on the catalog entry's slug: when the slug
            // differed from the package id, beginUpdate stopped the WRONG worker (or none), and
            // because the slug was not installed, no preflight and no commit ran — the package
            // journal written under the real id stayed open and the next restart's
            // recoverInterruptedUpdates silently rolled the successful install back.
            staging = packages.downloadToStaging(zip.url(), entry.sha256());
            String realId = previewPackageId(staging);
            PluginManifest installed;
            if (lifecycle != null) {
                installed = lifecycle.installWithUpdateGate(realId, () -> packages.installStaged(
                        staging, entry.sha256(), entry.signature(), entry.keyId(),
                        confirmPermissionEscalation));
            } else {
                // Legacy/test constructor without runtime gates: install and commit immediately
                // so a successful swap never looks interrupted at startup recovery.
                installed = packages.installStaged(staging, entry.sha256(), entry.signature(),
                        entry.keyId(), confirmPermissionEscalation);
                if (realId != null) packages.commitUpdate(realId);
            }
            recordFengyuInstall(entry, installed);
        } catch (IllegalArgumentException e) {
            // Validation verdicts (bad URL scheme, digest mismatch, manifest rejection, ...)
            // already carry a user-actionable message mapped to 400 — rewrapping them into a
            // generic 500 "internal error" hid the reason from the store UI.
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("FengYu install failed: " + entry.uid(), e);
        } finally {
            if (staging != null) {
                try {
                    Files.deleteIfExists(staging);
                } catch (java.io.IOException ignored) {
                    // Temp-file cleanup only.
                }
            }
        }
    }

    /** Preview the downloaded package's id for the gate; unpreviewable ids install ungated. */
    private String previewPackageId(Path staging) {
        try {
            PluginManifest incoming = packages.readArchiveManifest(staging);
            return incoming == null ? null : incoming.id();
        } catch (Exception unpreviewable) {
            // The install's own validation surfaces the real error; proceed without a gate.
            return null;
        }
    }

    /**
     * P2-13: persist the uid → real plugin id binding (and version/path bookkeeping) so a
     * mismatched-id install stays visible in the unified catalog and uninstallable. Best-effort:
     * bookkeeping must never fail a successful install.
     */
    private void recordFengyuInstall(UnifiedCatalogEntry entry, PluginManifest installed) {
        if (records == null || installed == null) return;
        try {
            PluginInstallRecordEntity rec = records
                .findByUidAndUserId(entry.uid(), SecurityConstants.LOCAL_VIRTUAL_USER_ID)
                .orElseGet(() -> {
                    PluginInstallRecordEntity created = new PluginInstallRecordEntity();
                    created.setUid(entry.uid());
                    created.setSourceType(StoreSourceType.FENGYU.name());
                    created.setOrigin(entry.origin());
                    created.setUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID);
                    return created;
                });
            rec.setPluginName(entry.displayName() == null ? entry.name() : entry.displayName());
            rec.setVersion(installed.version());
            rec.setPinnedSha(entry.sha256());
            rec.setInstallPath(packages.directory(installed.id()).toString());
            rec.setDeclaredSkills("[]");
            rec.setMcpServerRefs("[]");
            rec.setHasMcpServers(false);
            rec.setEnabled(packages.isEnabled(installed.id()));
            rec.setUpdatedAt(LocalDateTime.now());
            records.save(rec);
        } catch (Exception recordFailure) {
            log.warn("Could not record FENGYU install bookkeeping for {}: {}",
                    entry.uid(), recordFailure.toString());
        }
    }

    // PluginPackageService.uninstall/setEnabled declare checked IOException; wrap them so the
    // dispatcher's public methods remain unchecked — mirroring installFengyu's handling.
    private void uninstallFengyu(UnifiedCatalogEntry entry, boolean deleteData) {
        try {
            lifecycleAwareUninstall(resolveFengyuPluginId(entry), deleteData);
            if (records != null) {
                records.findByUidAndUserId(entry.uid(), SecurityConstants.LOCAL_VIRTUAL_USER_ID)
                    .ifPresent(rec -> records.delete(rec));
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("FengYu uninstall failed: " + entry.uid(), e);
        }
    }

    private void lifecycleAwareUninstall(String pluginId, boolean deleteData) throws java.io.IOException {
        if (lifecycle != null) {
            lifecycle.uninstallWithGate(pluginId, deleteData);
        } else {
            packages.uninstall(pluginId, deleteData);
        }
    }

    /**
     * P2-13: the plugin id an entry operations should target — the catalog slug when it IS the
     * installed id (the normal case), otherwise the real id bound at install time through the
     * install record. Falls back to the slug so a not-installed entry still surfaces the normal
     * "Plugin is not installed" verdict.
     */
    private String resolveFengyuPluginId(UnifiedCatalogEntry entry) {
        if (packages.find(entry.name()).isPresent()) return entry.name();
        if (records != null) {
            var rec = records.findByUidAndUserId(entry.uid(), SecurityConstants.LOCAL_VIRTUAL_USER_ID);
            if (rec.isPresent() && rec.get().getInstallPath() != null) {
                Path dir = Path.of(rec.get().getInstallPath());
                String id = dir.getFileName() == null ? null : dir.getFileName().toString();
                if (id != null && packages.find(id).isPresent()) return id;
            }
        }
        return entry.name();
    }

    private void setEnabledFengyu(UnifiedCatalogEntry entry, boolean enabled) {
        try {
            packages.setEnabled(resolveFengyuPluginId(entry), enabled);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("FengYu setEnabled failed: " + entry.uid(), e);
        }
    }
}
