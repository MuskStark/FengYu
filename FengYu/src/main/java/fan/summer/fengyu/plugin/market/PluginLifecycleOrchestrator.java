package fan.summer.fengyu.plugin.market;

import fan.summer.fengyu.plugin.runtime.PluginLogStore;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * The single plugin lifecycle transaction orchestrator: local {@code .fyp} upload,
 * the unified store and the cloud store all sequence worker stop → install →
 * health preflight → commit/rollback through this class so the runtime gate can
 * never drift between entry points (review M-3).
 *
 * <p>Two shapes are offered. {@link #installWithUpdateGate} runs the complete
 * begin → install → preflight → commit sequence for a single package and leaves
 * nothing open. The {@code *Staged} methods expose the same phases separately so
 * a multi-artifact transaction (a store dependency plan) can hold several plugin
 * updates open and commit or roll them back as one unit.
 */
@Service
public class PluginLifecycleOrchestrator {

    private final PluginPackageService packages;
    private final PluginProcessManager processes;
    private final PluginLogStore logs;

    public PluginLifecycleOrchestrator(PluginPackageService packages,
            PluginProcessManager processes, PluginLogStore logs) {
        this.packages = packages;
        this.processes = processes;
        this.logs = logs;
    }

    /** Runs the actual install/upgrade, rethrowing its checked exceptions. */
    @FunctionalInterface
    public interface InstallAction {
        PluginManifest run() throws IOException, InterruptedException;
    }

    /**
     * Install/upgrade inside the per-plugin update gate (P0-6). {@link
     * PluginProcessManager#beginUpdate} marks the id updating (concurrent invokes
     * refuse) and stops the running Worker — it throws if the Worker cannot be
     * stopped, which aborts the swap rather than leaving the old code running
     * against a new package (on Windows a running JVM would also hold the jar,
     * blocking the atomic move). Updates additionally pass a health preflight
     * before the rollback snapshot is committed; a failing preflight stops the
     * worker, restores the previous package and re-preflights it.
     */
    public PluginManifest installWithUpdateGate(String pluginId, InstallAction installAction)
            throws IOException, InterruptedException {
        if (pluginId == null) {
            // Brand-new package whose id could not be previewed: no Worker to stop.
            return installAction.run();
        }
        boolean update = isInstalled(pluginId);
        processes.beginUpdate(pluginId);
        try {
            PluginManifest installed = installAction.run();
            if (update) {
                try {
                    processes.preflight(pluginId);
                    packages.commitUpdate(pluginId);
                } catch (RuntimeException | IOException healthFailure) {
                    processes.stop(pluginId);
                    packages.rollbackUpdate(pluginId);
                    try {
                        processes.preflight(pluginId);
                    } catch (RuntimeException rollbackHealthFailure) {
                        if (rollbackHealthFailure != healthFailure) {
                            healthFailure.addSuppressed(rollbackHealthFailure);
                        }
                    }
                    throw healthFailure;
                }
            }
            return installed;
        } finally {
            processes.endUpdate(pluginId);
        }
    }

    /**
     * Uninstall inside the update gate (not a bare stop): an invoke arriving
     * mid-uninstall must not respawn a worker from the directory being deleted.
     * Also drops the plugin's captured log buffer and live subscribers so a
     * reinstalled plugin with the same id doesn't surface stale history.
     */
    public void uninstallWithGate(String pluginId, boolean deleteData) throws IOException {
        processes.beginUpdate(pluginId);
        try {
            packages.uninstall(pluginId, deleteData);
        } finally {
            processes.endUpdate(pluginId);
        }
        logs.clear(pluginId);
    }

    // ---- staged phases for multi-artifact store transactions ----

    /** Whether a plugin with this id is currently installed (update vs fresh install). */
    public boolean isInstalled(String pluginId) {
        return packages.find(pluginId).isPresent();
    }

    /**
     * Opens the gate and stops the worker without committing: the package-level
     * rollback journal written by the installer stays open until {@link
     * #commitStaged} / {@link #rollbackStaged}, so a crash mid-transaction is
     * recovered to the previous package at startup.
     */
    public void beginStaged(String pluginId) {
        processes.beginUpdate(pluginId);
    }

    /** Health-checks the freshly swapped package while the transaction is still open. */
    public void preflightStaged(String pluginId) {
        processes.preflight(pluginId);
    }

    /** Closes the gate without committing; for failure paths that already cleaned up. */
    public void endStaged(String pluginId) {
        processes.endUpdate(pluginId);
    }

    /** Stops a plugin's worker without the update gate (enable/disable path). */
    public void stopWorker(String pluginId) {
        processes.stop(pluginId);
    }

    /**
     * Commits a staged install (deletes the rollback snapshot + journal; a no-op
     * for fresh installs) and re-enables invokes.
     */
    public void commitStaged(String pluginId) throws IOException {
        try {
            packages.commitUpdate(pluginId);
        } finally {
            processes.endUpdate(pluginId);
        }
    }

    /**
     * Rolls a staged install back to the pre-update package: stop the worker,
     * restore the snapshot, re-preflight the restored version, re-enable invokes.
     * Tolerates a missing snapshot (the package service's own startup recovery
     * may have restored it first, or the install was fresh).
     */
    public void rollbackStaged(String pluginId) {
        processes.stop(pluginId);
        try {
            packages.rollbackUpdate(pluginId);
        } catch (RuntimeException | IOException nothingToRestore) {
            // Nothing to roll back — see javadoc.
        }
        try {
            processes.preflight(pluginId);
        } catch (RuntimeException rollbackHealthIgnored) {
            // The restored version should be healthy; if it is not, the plugin
            // stays stopped and surfaces through the regular runtime health path.
        }
        processes.endUpdate(pluginId);
    }
}
