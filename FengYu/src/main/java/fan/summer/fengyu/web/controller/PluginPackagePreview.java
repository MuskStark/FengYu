package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.market.PluginLifecycleOrchestrator;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Shared pre-gate plumbing for the local {@code .fyp} lifecycle surfaces —
 * {@link PluginPackageController} and its deprecated alias
 * {@link PluginMarketCompatController}: preview the incoming package's id so
 * {@link PluginLifecycleOrchestrator}'s update gate can stop the right Worker before the
 * install swaps the directory. One implementation instead of controller-private duplicates.
 */
final class PluginPackagePreview {

    private PluginPackagePreview() {
    }

    /**
     * Reads the incoming package's manifest (without installing) to learn its id, for the
     * update gate; unpreviewable packages return {@code null} so the install's own validation
     * surfaces the real error and a brand-new id simply installs ungated.
     */
    static String previewId(PluginPackageService packages, MultipartFile file) {
        try {
            return idOf(packages.readArchiveManifest(file));
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    /** Path-based twin of {@link #previewId(PluginPackageService, MultipartFile)}. */
    static String previewId(PluginPackageService packages, Path archive) {
        try {
            return idOf(packages.readArchiveManifest(archive));
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static String idOf(PluginManifest incoming) {
        return incoming == null ? null : incoming.id();
    }
}
