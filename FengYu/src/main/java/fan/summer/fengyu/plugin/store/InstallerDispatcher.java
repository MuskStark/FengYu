package fan.summer.fengyu.plugin.store;

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

/**
 * Routes install/update/uninstall for FENGYU catalog entries. Two install channels remain
 * since the third-party marketplace integration (Claude/Codex/Grok) was retired — the
 * official Infinia store aggregates that content server-side now:
 *
 * <ul>
 *   <li>{@link UnifiedCatalogEntry.StoreCoordinateSource} — the official store's transaction
 *       pipeline (resolve → signed ticket → apply → ledger → commit);</li>
 *   <li>{@link UnifiedCatalogEntry.ZipUrlSource} — the legacy direct-{@code .fyp} catalog
 *       format ({@code fengyu.marketplace.catalog-url}).</li>
 * </ul>
 */
@Service
public class InstallerDispatcher {
    private static final Logger log = LoggerFactory.getLogger(InstallerDispatcher.class);
    private final PluginPackageService packages;
    private final PluginLifecycleOrchestrator lifecycle;
    /** P2-13: binds a FENGYU catalog uid to the REAL installed plugin id (null in tests). */
    private final PluginInstallRecordRepository records;
    /** Official-store transaction pipeline for coordinate-backed entries (null in tests). */
    private final ObjectProvider<fan.summer.fengyu.store.StoreService> storeService;
    /** Skill toggling for ledger-bound SKILL items (null in tests). */
    private final ObjectProvider<fan.summer.fengyu.ai.skill.SkillPackageService> skillsProvider;
    /** MCP server toggling for ledger-bound MCP items (null in tests). */
    private final ObjectProvider<fan.summer.fengyu.ai.mcp.McpRuntimeManager> mcpProvider;

    /** Test/backwards-compatible constructor; runtime gates are absent by design. */
    public InstallerDispatcher(PluginPackageService packages) {
        this(packages, null, null, null, null, null);
    }

    public InstallerDispatcher(PluginPackageService packages,
            fan.summer.fengyu.plugin.runtime.PluginProcessManager processes,
            fan.summer.fengyu.plugin.runtime.PluginLogStore logs) {
        this(packages,
                processes != null ? new PluginLifecycleOrchestrator(packages, processes, logs) : null,
                null, null, null, null);
    }

    @Autowired
    public InstallerDispatcher(PluginPackageService packages,
            PluginLifecycleOrchestrator lifecycle,
            PluginInstallRecordRepository records,
            ObjectProvider<fan.summer.fengyu.store.StoreService> storeService,
            ObjectProvider<fan.summer.fengyu.ai.skill.SkillPackageService> skillsProvider,
            ObjectProvider<fan.summer.fengyu.ai.mcp.McpRuntimeManager> mcpProvider) {
        this.packages = packages;
        this.lifecycle = lifecycle;
        this.records = records;
        this.storeService = storeService;
        this.skillsProvider = skillsProvider;
        this.mcpProvider = mcpProvider;
    }

    public void install(UnifiedCatalogEntry entry) {
        installFengyu(entry, false);
    }

    public void update(UnifiedCatalogEntry entry) {
        update(entry, false);
    }

    public void update(UnifiedCatalogEntry entry, boolean confirmPermissionEscalation) {
        // update == reinstall on both channels
        installFengyu(entry, confirmPermissionEscalation);
    }

    public void uninstall(UnifiedCatalogEntry entry, boolean deleteData) {
        if (entry.sourceRef() instanceof UnifiedCatalogEntry.StoreCoordinateSource store) {
            uninstallThroughStore(store.coordinate(), deleteData);
            return;
        }
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

    public void setEnabled(UnifiedCatalogEntry entry, boolean enabled) {
        if (entry.sourceRef() instanceof UnifiedCatalogEntry.StoreCoordinateSource store) {
            setEnabledThroughStore(store.coordinate(), entry, enabled);
            return;
        }
        try {
            packages.setEnabled(resolveFengyuPluginId(entry), enabled);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("FengYu setEnabled failed: " + entry.uid(), e);
        }
    }

    private void installFengyu(UnifiedCatalogEntry entry, boolean confirmPermissionEscalation) {
        if (entry.sourceRef() instanceof UnifiedCatalogEntry.StoreCoordinateSource store) {
            installThroughStore(store.coordinate(), confirmPermissionEscalation);
            return;
        }
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
            final Path staged = staging;
            String realId = previewPackageId(staging);
            PluginManifest installed;
            if (lifecycle != null) {
                installed = lifecycle.installWithUpdateGate(realId, () -> packages.installStaged(
                        staged, entry.sha256(), entry.signature(), entry.keyId(),
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
            // already carry a user-actionable message mapped to 400 — rewrapping them into
            // a generic 500 "internal error" hid the reason from the store UI.
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

    /**
     * Official-store install path for coordinate-backed entries: the store transaction
     * pipeline (dependency resolve → signed download ticket → apply → ledger → commit) owns
     * the whole flow, so the unified catalog and the store skills market install through one
     * audited channel.
     */
    private void installThroughStore(String coordinate, boolean confirmPermissions) {
        if (storeService == null)
            throw new IllegalStateException("Store service unavailable for " + coordinate);
        try {
            storeService.getObject().install(coordinate, confirmPermissions);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Store install interrupted: " + coordinate, interrupted);
        } catch (IllegalArgumentException e) {
            // User-actionable verdicts (missing/incompatible dependencies, bad coordinate)
            // map to 400 — rewrapping them would hide the reason behind a generic 500.
            throw e;
        } catch (java.io.IOException | RuntimeException failure) {
            throw new RuntimeException("Store install failed: " + coordinate, failure);
        }
    }

    /** Store-ledger-aware uninstall for coordinate-backed entries (package + ledger row). */
    private void uninstallThroughStore(String coordinate, boolean deleteData) {
        if (storeService == null)
            throw new IllegalStateException("Store service unavailable for " + coordinate);
        try {
            storeService.getObject().uninstall(coordinate, deleteData);
        } catch (IllegalArgumentException e) {
            // "Not installed from the store" and friends are user-actionable (400), not 500s.
            throw e;
        } catch (java.io.IOException | RuntimeException failure) {
            throw new RuntimeException("Store uninstall failed: " + coordinate, failure);
        }
    }

    /**
     * Enable/disable for coordinate-backed entries routed by the ledger's item type: PLUGIN
     * toggles the package, SKILL toggles the skill package, so the unified page's toggle works
     * for the store's whole catalog, not just plugins.
     */
    private void setEnabledThroughStore(String coordinate, UnifiedCatalogEntry entry, boolean enabled) {
        fan.summer.fengyu.store.StoreService.InstalledView view = storeLedgerEntry(coordinate);
        if (view == null) {
            // Not ledger-bound (never store-installed): fall back to the plugin package path.
            try {
                packages.setEnabled(resolveFengyuPluginId(entry), enabled);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("FengYu setEnabled failed: " + entry.uid(), e);
            }
            return;
        }
        try {
            if ("SKILL".equals(view.type()) && skillsProvider != null) {
                skillsProvider.getObject().setEnabled(view.localId(), enabled);
            } else if ("MCP".equals(view.type())) {
                setMcpServerEnabled(view.localId(), enabled);
            } else {
                packages.setEnabled(view.localId(), enabled);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Store setEnabled failed: " + coordinate, e);
        }
    }

    /**
     * Toggles an imported store MCP template's server definition. The store toggle is the
     * user's explicit gesture, so enabling it carries the same {@code confirmImported} review
     * the MCP settings page asks for; disabling never needs it. Env/header secrets stay
     * untouched (save keeps previous values for null request fields).
     */
    private void setMcpServerEnabled(String serverKey, boolean enabled) {
        if (mcpProvider == null)
            throw new IllegalStateException("MCP runtime unavailable for " + serverKey);
        fan.summer.fengyu.ai.mcp.McpRuntimeManager mcp = mcpProvider.getObject();
        fan.summer.fengyu.ai.mcp.McpRuntimeManager.ServerView current = mcp.servers().stream()
                .filter(server -> serverKey.equals(server.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "MCP server not found: " + serverKey));
        mcp.save(new fan.summer.fengyu.ai.mcp.McpRuntimeManager.ServerRequest(
                current.name(), current.type(), current.command(), current.args(),
                null, current.url(), current.endpoint(), null, enabled,
                current.disabledTools(),
                current.requestTimeoutSeconds() > 0 ? current.requestTimeoutSeconds() : null,
                current.initTimeoutSeconds() > 0 ? current.initTimeoutSeconds() : null,
                enabled ? Boolean.TRUE : null), current.id());
    }

    /**
     * Uninstalls a 4.0-era third-party install (Claude/Codex/Grok): those catalog adapters
     * are retired, so the unified catalog can never resolve the uid again — the DELETE
     * endpoint falls back to the install record and removes the materialized skill dir,
     * MCP-server config, and the record itself (the cleanup AgentContentInstaller used to do).
     */
    public void uninstallLegacyThirdParty(String uid, Path runtimeRoot) {
        if (records == null) {
            throw new IllegalArgumentException("No catalog entry or install record for uid: " + uid);
        }
        PluginInstallRecordEntity rec = records
                .findByUidAndUserId(uid, SecurityConstants.LOCAL_VIRTUAL_USER_ID)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No catalog entry or install record for uid: " + uid));
        // Defense-in-depth: never let a crafted uid delete outside the runtime tree.
        Path skillDir = PluginContentPathSafety.isInside(runtimeRoot,
                runtimeRoot.resolve("skills").resolve(uid))
                ? runtimeRoot.resolve("skills").resolve(uid) : null;
        Path mcpConfig = PluginContentPathSafety.isInside(runtimeRoot,
                runtimeRoot.resolve("mcp-servers").resolve(uid + ".json"))
                ? runtimeRoot.resolve("mcp-servers").resolve(uid + ".json") : null;
        try {
            if (skillDir != null) deleteRecursive(skillDir);
            if (mcpConfig != null) Files.deleteIfExists(mcpConfig);
            records.delete(rec);
        } catch (Exception e) {
            throw new RuntimeException("Legacy uninstall failed: " + uid, e);
        }
    }

    /** Best-effort recursive delete for legacy materializations; missing paths are fine. */
    private static void deleteRecursive(Path path) throws java.io.IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) {
                    // best effort
                }
            });
        }
    }

    /** The ledger row for a coordinate, or {@code null} when the store service/row is absent. */
    private fan.summer.fengyu.store.StoreService.InstalledView storeLedgerEntry(String coordinate) {
        if (storeService == null) return null;
        try {
            return storeService.getObject().installed().stream()
                    .filter(v -> coordinate.equals(v.coordinate()))
                    .findFirst().orElse(null);
        } catch (RuntimeException lookupFailed) {
            // Ledger lookup is an optimization; the fallbacks still apply.
            return null;
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
    private void lifecycleAwareUninstall(String pluginId, boolean deleteData) throws java.io.IOException {
        if (lifecycle != null) {
            lifecycle.uninstallWithGate(pluginId, deleteData);
        } else {
            packages.uninstall(pluginId, deleteData);
        }
    }

    /**
     * P2-13: the plugin id an entry's operations should target — the catalog slug when it IS the
     * installed id (the normal case), otherwise the real id bound at install time through the
     * install record or the store ledger. Falls back to the slug so a not-installed entry still
     * surfaces the normal "Plugin is not installed" verdict.
     */
    private String resolveFengyuPluginId(UnifiedCatalogEntry entry) {
        if (packages.find(entry.name()).isPresent()) return entry.name();
        if (entry.sourceRef() instanceof UnifiedCatalogEntry.StoreCoordinateSource store) {
            fan.summer.fengyu.store.StoreService.InstalledView view = storeLedgerEntry(store.coordinate());
            if (view != null && view.localId() != null && packages.find(view.localId()).isPresent())
                return view.localId();
        }
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
}
