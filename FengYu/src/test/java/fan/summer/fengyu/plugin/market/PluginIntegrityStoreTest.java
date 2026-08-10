package fan.summer.fengyu.plugin.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PluginIntegrityStore}: a recorded manifest digest verifies against an
 * unchanged manifest, fails against a tampered manifest, and is removed on forget.
 */
class PluginIntegrityStoreTest {
    @TempDir Path temp;

    private static final String ID = "com.example.worker";
    private static final String VERSION = "1.0.0";

    @Test
    void recordedDigestVerifiesAgainstUnchangedManifest() throws Exception {
        PluginIntegrityStore store = new PluginIntegrityStore(temp);
        Path manifest = writeManifest("{}");

        store.record(ID, VERSION, manifest);
        Optional<Boolean> result = store.verify(ID, manifest);

        assertTrue(result.isPresent(), "a recorded digest must be verifiable");
        assertTrue(result.get(), "unchanged manifest must verify");
        assertEquals(VERSION, store.read(ID).orElseThrow().version());
    }

    @Test
    void tamperedManifestFailsVerification() throws Exception {
        PluginIntegrityStore store = new PluginIntegrityStore(temp);
        Path manifest = writeManifest("{}");
        store.record(ID, VERSION, manifest);

        // Tamper: change the on-disk manifest after the record was taken.
        Files.writeString(manifest, "{\"changed\":true}");

        Optional<Boolean> result = store.verify(ID, manifest);
        assertTrue(result.isPresent());
        assertFalse(result.get(), "a tampered manifest must NOT verify (fail closed)");
    }

    @Test
    void verifyReturnsEmptyForAbsentRecord() throws Exception {
        PluginIntegrityStore store = new PluginIntegrityStore(temp);
        Path manifest = writeManifest("{}");
        // No record() call — verify returns empty. The store itself stays neutral; the caller
        // (PluginProcessManager) treats an empty result as fail-closed once migration has run, so a
        // missing record is NOT silently "unverified-but-allowed" at Worker start.
        assertTrue(store.verify(ID, manifest).isEmpty());
        assertTrue(store.read(ID).isEmpty());
    }

    @Test
    void forgetRemovesTheRecord() throws Exception {
        PluginIntegrityStore store = new PluginIntegrityStore(temp);
        Path manifest = writeManifest("{}");
        store.record(ID, VERSION, manifest);
        assertTrue(store.verify(ID, manifest).isPresent());

        store.forget(ID);

        assertTrue(store.read(ID).isEmpty(), "forget must drop the record");
        assertTrue(store.verify(ID, manifest).isEmpty(), "after forget, enforcement is inactive");
    }

    @Test
    void reRecordOverwritesPriorDigest() throws Exception {
        PluginIntegrityStore store = new PluginIntegrityStore(temp);
        Path manifest = writeManifest("{}");
        store.record(ID, VERSION, manifest);
        // Upgrade: manifest content changes, then a new record is taken.
        Files.writeString(manifest, "{\"v\":2}");
        store.record(ID, "2.0.0", manifest);

        assertTrue(store.verify(ID, manifest).get(), "the new digest must verify after re-record");
        assertEquals("2.0.0", store.read(ID).orElseThrow().version());
    }

    /**
     * Regression (P0-2 whole-package verify): {@link PluginIntegrityStore#verifyPackage} recomputes
     * the live package directory digest and compares it to the record, catching tampering of any
     * file in the package (not just manifest.json).
     */
    @Test
    void verifyPackageMatchesUnchangedDirectory() throws Exception {
        PluginIntegrityStore store = new PluginIntegrityStore(temp);
        Path pkg = writePackage("{\"v\":1}", "worker.jar-bytes", "lib/deps.jar");
        Path manifest = pkg.resolve("manifest.json");
        store.record(ID, VERSION, manifest, pkg);

        assertTrue(store.verifyPackage(ID, pkg).isPresent(), "a record with a package digest must be verifiable");
        assertTrue(store.verifyPackage(ID, pkg).get(), "an unchanged package directory must verify");
    }

    @Test
    void verifyPackageFailsOnTamperedNonManifestFile() throws Exception {
        PluginIntegrityStore store = new PluginIntegrityStore(temp);
        Path pkg = writePackage("{\"v\":1}", "worker.jar-bytes", "lib/deps.jar");
        store.record(ID, VERSION, pkg.resolve("manifest.json"), pkg);

        // Tamper with a NON-manifest file (the Worker JAR). The manifest check still passes, but the
        // whole-package digest must catch the change — this is the gap the review flagged.
        Files.writeString(pkg.resolve("worker.jar"), "worker.jar-TAMPERED");

        Optional<Boolean> result = store.verifyPackage(ID, pkg);
        assertTrue(result.isPresent());
        assertFalse(result.get(), "a tampered non-manifest file must fail whole-package verification");
    }

    @Test
    void verifyPackageEmptyForLegacyManifestOnlyRecord() throws Exception {
        PluginIntegrityStore store = new PluginIntegrityStore(temp);
        Path pkg = writePackage("{\"v\":1}", "worker.jar-bytes");
        // Legacy manifest-only record (no package digest) — verifyPackage returns empty so the caller
        // knows whole-package enforcement is not active for this record.
        store.record(ID, VERSION, pkg.resolve("manifest.json"));
        assertTrue(store.verifyPackage(ID, pkg).isEmpty(),
            "a legacy manifest-only record must not be treated as whole-package verified");
    }

    /**
     * The uninstall tombstone is the marker the official-plugin seeder checks to honour a user
     * uninstall across restarts. markUninstalled → isUninstalled must be true; it is independent of
     * the integrity record (record/forget operate on {@code <id>.json}, the tombstone on
     * {@code <id>.uninstalled}); and clearUninstalled must remove it so a later reinstall is allowed.
     */
    @Test
    void uninstallTombstoneIsIndependentOfIntegrityRecord() {
        PluginIntegrityStore store = new PluginIntegrityStore(temp);
        assertFalse(store.isUninstalled(ID), "no tombstone before markUninstalled");

        store.markUninstalled(ID);
        assertTrue(store.isUninstalled(ID), "tombstone present after markUninstalled");

        // Coexists with a record: recording/clearing the record does not touch the tombstone.
        store.forget(ID);
        assertTrue(store.isUninstalled(ID), "forget does not clear the tombstone");

        store.clearUninstalled(ID);
        assertFalse(store.isUninstalled(ID), "clearUninstalled removes the tombstone");
    }

    @Test
    void recordDoesNotImplyUninstalledAndViceVersa() throws Exception {
        PluginIntegrityStore store = new PluginIntegrityStore(temp);
        Path manifest = writeManifest("{}");
        store.record(ID, VERSION, manifest);
        assertFalse(store.isUninstalled(ID), "a recorded plugin is NOT uninstalled");

        store.markUninstalled(ID);
        assertTrue(store.verify(ID, manifest).isPresent(), "the tombstone does not disturb the record");
        assertTrue(store.read(ID).isPresent(), "record survives a tombstone mark");
    }

    private Path writeManifest(String content) throws Exception {
        Path dir = Files.createDirectories(temp.resolve(ID));
        Path manifest = dir.resolve("manifest.json");
        Files.writeString(manifest, content);
        return manifest;
    }

    /** Build a package directory with a manifest plus the given extra files (path -> content). */
    private Path writePackage(String manifestContent, String... files) throws Exception {
        Path pkg = Files.createDirectories(temp.resolve(ID));
        Files.writeString(pkg.resolve("manifest.json"), manifestContent);
        for (int i = 0; i < files.length; i++) {
            Path f = pkg.resolve(files[i]);
            Files.createDirectories(f.getParent());
            Files.writeString(f, "content-" + i);
        }
        return pkg;
    }
}
