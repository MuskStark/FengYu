package fan.summer.fengyu.ai;

import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavior contract of the conversation-scoped chat resource registry under the send-transaction
 * model (task doc revision 2, desktop calibration 2026-10): selection never grants, prepare
 * captures at send time (a zero-copy LIVE reference for ordinary native picks, a bounded copy
 * for sensitive paths and uploads), commit is idempotent by sendId, leases derive per-turn
 * grants over the reference and re-validate live fingerprints at the turn boundary, and retired
 * copies recycle exactly once. Server-side halves of E01–E12, A01–A03, A10, B01, B02, and the
 * D2 restart semantics.
 */
class ChatResourceScopeServiceTest {

    private static final long OWNER = 7L;
    private static final String PLUGIN_A = "test.excel";
    private static final String PLUGIN_B = "test.python";

    @TempDir Path temp;
    private int seq = 0;

    private Fixture fixture() throws Exception {
        return fixture(Instant::now);
    }

    /** A fixture with a controllable clock — the send-TTL sweep (E12) needs to move time. */
    private Fixture fixture(java.util.function.Supplier<Instant> clock) throws Exception {
        return fixture(clock, fan.summer.fengyu.runtime.ChatResourceLimits.defaults());
    }

    /** The full fixture: controllable clock AND controllable caps (quota-delta tests). */
    private Fixture fixture(java.util.function.Supplier<Instant> clock,
            fan.summer.fengyu.runtime.ChatResourceLimits limits) throws Exception {
        Path pluginRoot = Files.createDirectories(temp.resolve("plugins"));
        for (String pluginId : List.of(PLUGIN_A, PLUGIN_B)) {
            Path pluginDir = Files.createDirectories(pluginRoot.resolve(pluginId));
            Files.writeString(pluginDir.resolve("manifest.json"), """
                {"schemaVersion":2,"id":"%s","name":"P","description":"test","version":"1.0.0",
                 "author":"test","icon":"test","category":"OTHER","ui":{"entry":"ui/index.html"},
                 "backend":{"callTimeoutSeconds":60},"permissions":["files.read","files.write"],
                 "official":false,"aiTools":[]}
                """.formatted(pluginId));
        }
        PluginFileGrantService files = new PluginFileGrantService(
                temp.resolve("grants-" + (++seq)).toString());
        ChatFileGrantService chatFiles =
                new ChatFileGrantService(new PluginPackageService(pluginRoot.toString()), files);
        Path copyRoot = temp.resolve("copies-" + seq);
        ChatResourceScopeService scopes =
                new ChatResourceScopeService(chatFiles, files, copyRoot, limits);
        scopes.clock = clock;
        return new Fixture(scopes, files, chatFiles, copyRoot);
    }

    private record Fixture(ChatResourceScopeService scopes, PluginFileGrantService files,
            ChatFileGrantService chatFiles, Path copyRoot) {}

    private Path file(String name, String content) throws Exception {
        return Files.writeString(temp.resolve(name), content);
    }

    /** The lease's grant for one specific plugin (refs fan out across eligible plugins). */
    private static ChatFileContext.ActiveFileRef refFor(
            ChatResourceScopeService.Lease lease, String pluginId) {
        return lease.refs().stream()
                .filter(r -> r.pluginId().equals(pluginId))
                .findFirst()
                .orElseThrow();
    }

    /** The lease grant for one plugin that resolves to the given target file. */
    private ChatFileContext.ActiveFileRef refTo(Fixture f,
            ChatResourceScopeService.Lease lease, String pluginId, Path target) throws Exception {
        for (ChatFileContext.ActiveFileRef ref : lease.refs()) {
            if (!ref.pluginId().equals(pluginId)) continue;
            if (f.files().resolve(ref.pluginId(), ref.ref().id()).toRealPath()
                    .equals(target.toRealPath())) {
                return ref;
            }
        }
        throw new AssertionError("no grant for " + pluginId + " resolving to " + target);
    }

    private List<ChatResourceScopeService.NativeAttachment> att(String attachmentId, Path path) {
        return List.of(new ChatResourceScopeService.NativeAttachment(
                attachmentId, path.toString(), "file"));
    }

    /** Owned copies currently sitting in the host copy store (D2 manifests excluded). */
    private long copyFileCount(Path copyRoot) throws Exception {
        if (!Files.exists(copyRoot)) return 0;
        try (var paths = Files.walk(copyRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString()
                            .equals(ChatResourceScopeService.SCOPE_MANIFEST)
                            && !p.getFileName().toString().startsWith(
                                    ChatResourceScopeService.SCOPE_MANIFEST + ".tmp-"))
                    .count();
        }
    }

    // ── E01/E02: selection → draft; only prepare copies; abort reclaims ────────────────

    @Test
    void aTreePastTheFileCapIsRejectedWithoutCopyingAnything() throws Exception {
        // The walk is capped before materializing: a directory one entry past the cap is
        // rejected cleanly, and nothing lands in the copy store first.
        Fixture f = fixture();
        Path huge = Files.createDirectories(temp.resolve("huge"));
        for (int i = 0; i < ChatResourceScopeService.MAX_TREE_FILES + 2; i++) {
            Files.writeString(huge.resolve("f" + i), "x");
        }
        String scopeId = f.scopes().createScope(OWNER);

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> f.scopes().prepareSend(scopeId, OWNER, "send-cap",
                        List.of(new ChatResourceScopeService.NativeAttachment(
                                "a1", huge.toString(), "directory"))));

        assertTrue(rejected.getMessage().contains("too many files"), rejected.getMessage());
        assertEquals(0, copyFileCount(f.copyRoot()), "nothing was copied before the rejection");
    }

    @Test
    void e01_prepareReferencesTheFileButMintsNoPluginGrantUntilLease() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path csv = file("contacts.csv", "a,b\n1,2\n");

        // "Selection" is client-side; the FIRST backend touch is the send prepare. An ordinary
        // native pick is a zero-copy LIVE reference (D1) — zero plugin grants exist, and a lease
        // is the only grant minter.
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", csv));

        assertEquals(1, f.scopes().sendStatus(scope, OWNER, "send_1").attachments().size());
        assertTrue(f.files().readablePaths(PLUGIN_A).isEmpty(),
                "E01: preparing a send mints no read grant");
        assertTrue(f.files().readablePaths(PLUGIN_B).isEmpty(),
                "E01: preparing a send mints no read grant for any plugin");
        assertEquals(0, copyFileCount(f.copyRoot()),
                "D1: an ordinary native pick writes no copy at all");

        f.scopes().beginCommit(scope, OWNER, "send_1");
        String resourceId = f.scopes().snapshot(scope, OWNER).resources().get(0).resourceId();
        var lease = f.scopes().acquireLease(scope, OWNER, List.of(resourceId));
        assertEquals(2, lease.refs().size(), "one read grant per eligible plugin at lease time");
        assertEquals(1, f.files().readablePaths(PLUGIN_A).size(),
                "plugin A holds exactly one read grant over the live reference");
        assertEquals(1, f.files().readablePaths(PLUGIN_B).size(),
                "plugin B holds exactly one read grant over the live reference");
        assertEquals(csv.toRealPath(),
                f.files().resolve(PLUGIN_A, refFor(lease, PLUGIN_A).ref().id()).toRealPath(),
                "the grant resolves to the user's original file, not a copy");
    }

    @Test
    void e02_abortingAnUncommittedSendLeavesNoResidue() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path csv = file("abort.csv", "x");

        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", csv));
        assertEquals(0, copyFileCount(f.copyRoot()));

        f.scopes().abortSend(scope, OWNER, "send_1");

        assertEquals(0, copyFileCount(f.copyRoot()), "E02: no backend residue after abort");
        assertEquals("failed", f.scopes().sendStatus(scope, OWNER, "send_1").state());
        assertTrue(f.scopes().snapshot(scope, OWNER).resources().isEmpty(),
                "an aborted send commits nothing");
        assertTrue(Files.exists(csv), "aborting never touches the user's original file");
    }

    @Test
    void e03_aChangedLiveAttachmentFailsTheTurnBoundaryNotSilently() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path csv = file("versioned.csv", "v1");
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", csv));
        f.scopes().beginCommit(scope, OWNER, "send_1");
        String resourceId = f.scopes().snapshot(scope, OWNER).resources().get(0).resourceId();

        // The old data-level snapshot became a turn-boundary check (D1): content the user
        // changed since attaching fails the next lease LOUDLY — the model never silently reads
        // something other than what was described at attach time. (The size+mtime fingerprint
        // is deliberately cheap; a same-size edit inside one filesystem timestamp tick is
        // outside its power — the refresh entry point is the user's re-arm for that.)
        Files.writeString(csv, "v2 with more content");
        IllegalArgumentException changed = assertThrows(IllegalArgumentException.class,
                () -> f.scopes().acquireLease(scope, OWNER, List.of(resourceId)));
        assertTrue(changed.getMessage().contains("changed"), changed.getMessage());

        // A vanished attachment is the same loud failure, never a silent empty grant.
        Files.delete(csv);
        IllegalArgumentException vanished = assertThrows(IllegalArgumentException.class,
                () -> f.scopes().acquireLease(scope, OWNER, List.of(resourceId)));
        assertTrue(vanished.getMessage().contains("no longer available"), vanished.getMessage());

        // And a vanished source at PREPARE time is a clear failure (draft stays client-side).
        Path other = temp.resolve("missing.csv");
        assertThrows(IllegalArgumentException.class,
                () -> f.scopes().prepareSend(scope, OWNER, "send_2", att("att_2", other)));
    }

    @Test
    void e04_aFailingAttachmentReclaimsTheWholeTransactionCopies() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path good = file("good.csv", "a");
        Path missing = temp.resolve("missing.csv");

        assertThrows(IllegalArgumentException.class, () -> f.scopes().prepareSend(scope, OWNER,
                "send_1", List.of(
                        new ChatResourceScopeService.NativeAttachment("att_1", good.toString(), "file"),
                        new ChatResourceScopeService.NativeAttachment("att_2", missing.toString(), "file"))));

        assertEquals(0, copyFileCount(f.copyRoot()),
                "E04: the first attachment's copy must not permanently leak");
        assertEquals("failed", f.scopes().sendStatus(scope, OWNER, "send_1").state());
    }

    @Test
    void aCeilingHitMidCommitRollsTheWholeSendBackByItself() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        // One send carrying more uploads than the per-scope ceiling: the Nth commitInto
        // throws MID-MOVE — beginCommit must undo its own partial work, not strand the scope.
        // Uploads (not native picks) so the rollback actually reclaims OWNED COPIES and the
        // count assertion can fail if the reclaim logic regresses.
        f.scopes().prepareSend(scope, OWNER, "send_ceiling", List.of());
        for (int i = 0; i <= ChatResourceScopeService.MAX_RESOURCES_PER_SCOPE; i++) {
            f.scopes().addUploadToSend(scope, OWNER, "send_ceiling", "att_" + i,
                    new MockMultipartFile("file", "ceil" + i + ".csv", "text/csv",
                            ("x" + i).getBytes()));
        }
        assertTrue(copyFileCount(f.copyRoot()) > 0, "the uploads landed their copies");

        assertThrows(IllegalArgumentException.class,
                () -> f.scopes().beginCommit(scope, OWNER, "send_ceiling"));

        assertTrue(f.scopes().snapshot(scope, OWNER).resources().isEmpty(),
                "no half-committed resources survive a mid-commit failure");
        assertEquals("failed", f.scopes().sendStatus(scope, OWNER, "send_ceiling").state(),
                "the transaction does not linger in COMMITTING until the TTL");
        assertEquals(0, copyFileCount(f.copyRoot()), "every copy of the failed send is reclaimed");
    }

    // ── E05–E08: send idempotence ───────────────────────────────────────────────────────

    @Test
    void e05_sameSendIdReplaysOnePreparationAndOneCommitResult() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path csv = file("idem.csv", "x");

        var first = f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", csv));
        var replay = f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", csv));

        assertEquals("prepared", first.state());
        assertEquals(first.sendId(), replay.sendId());
        assertEquals(0, copyFileCount(f.copyRoot()), "a live reference is adopted once, never doubled");

        f.scopes().beginCommit(scope, OWNER, "send_1");
        f.scopes().finishCommit(scope, OWNER, "send_1", java.util.Map.of("streamId", "st_1"));
        var recorded = f.scopes().replayableResult(scope, OWNER, "send_1");
        assertNotNull(recorded, "E06: the accepted result is recoverable by sendId");
        assertEquals("st_1", recorded.get("streamId"));
    }

    @Test
    void e07_sameSendIdWithDifferentPayloadIsAConflict() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path one = file("one.csv", "1");
        Path two = file("two.csv", "2");

        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", one));

        assertThrows(IllegalArgumentException.class,
                () -> f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", two)),
                "E07: a conflicting replay must be rejected, not overwrite");
        assertEquals(0, copyFileCount(f.copyRoot()));
    }

    @Test
    void e12_expiredUncommittedSendsAreRolledBackByTheSweep() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        Fixture f = fixture(now::get);
        String scope = f.scopes().createScope(OWNER);
        Path csv = file("ttl.csv", "x");

        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", csv));
        now.set(now.get().plus(ChatResourceScopeService.SEND_TTL.plusSeconds(1)));

        f.scopes().createScope(OWNER); // any scope operation sweeps

        assertEquals("failed", f.scopes().sendStatus(scope, OWNER, "send_1").state());
        assertEquals(0, copyFileCount(f.copyRoot()), "E12: orphan transaction copies reclaimed");
    }

    // ── E09–E11: copy ownership is decoupled from grant lifetime ────────────────────────

    @Test
    void e09e10_releasedLeaseKeepsTheReferenceAndContinueReDerivesAccess() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path csv = file("continue.csv", "data");
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", csv));
        f.scopes().beginCommit(scope, OWNER, "send_1");
        String resourceId = f.scopes().snapshot(scope, OWNER).resources().get(0).resourceId();

        var first = f.scopes().acquireLease(scope, OWNER, List.of(resourceId));
        assertEquals(1, f.files().readablePaths(PLUGIN_A).size());
        f.scopes().releaseLease(scope, first.leaseId());

        assertEquals(0, f.files().readablePaths(PLUGIN_A).size(),
                "E10: the turn's grants are gone when it ends");
        assertTrue(Files.exists(csv), "E10: the user's file is untouched by the turn");

        var second = f.scopes().acquireLease(scope, OWNER, List.of(resourceId));
        assertEquals(1, f.files().readablePaths(PLUGIN_A).size(),
                "E09: 'continue' re-derives access over the same reference");
        assertEquals("data", Files.readString(
                f.files().resolve(PLUGIN_A, refFor(second, PLUGIN_A).ref().id())));
    }

    @Test
    void e11_removeDuringExecutionIsImmediateForNewTurnsAndNeverDeletesTheOriginal() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path csv = file("race.csv", "x");
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", csv));
        f.scopes().beginCommit(scope, OWNER, "send_1");
        String resourceId = f.scopes().snapshot(scope, OWNER).resources().get(0).resourceId();

        var lease = f.scopes().acquireLease(scope, OWNER, List.of(resourceId));
        f.scopes().removeResource(scope, OWNER, resourceId);

        assertThrows(IllegalArgumentException.class,
                () -> f.scopes().acquireLease(scope, OWNER, List.of(resourceId)),
                "E11/B02: logical revocation is immediate for new turns");
        assertTrue(Files.exists(csv),
                "the executing turn's grant still resolves the original file");

        f.scopes().releaseLease(scope, lease.leaseId());
        assertTrue(Files.exists(csv),
                "E11: a live reference is only ever forgotten — the user's file survives");
        f.scopes().releaseLease(scope, lease.leaseId()); // idempotent release never throws
    }

    @Test
    void b02_pinnedLeaseKeepsResolvingUntilReleaseThenRevokes() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path doc = file("lease.txt", "x");
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", doc));
        f.scopes().beginCommit(scope, OWNER, "send_1");
        String resourceId = f.scopes().snapshot(scope, OWNER).resources().get(0).resourceId();

        var lease = f.scopes().acquireLease(scope, OWNER, List.of(resourceId));
        f.scopes().removeResource(scope, OWNER, resourceId);

        for (var ref : lease.refs()) f.files().validate(ref.pluginId(), ref.ref());

        f.scopes().releaseLease(scope, lease.leaseId());
        for (var ref : lease.refs()) {
            assertThrows(IllegalArgumentException.class,
                    () -> f.files().resolve(ref.pluginId(), ref.ref().id()),
                    "release must revoke the retired grants");
        }
        assertTrue(Files.exists(doc),
                "b02: revoking a live reference never deletes the user's file");
        f.scopes().releaseLease(scope, lease.leaseId()); // idempotent — no second run, no throw
    }

    // ── A01–A03, A10: aggregation, identity, refresh ────────────────────────────────────

    @Test
    void a01_oneSelectionIsOneRecordLeasedToEveryEligiblePlugin() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path csv = file("contacts.csv", "a,b\n1,2\n");
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", csv));
        f.scopes().beginCommit(scope, OWNER, "send_1");

        var resources = f.scopes().snapshot(scope, OWNER).resources();
        assertEquals(1, resources.size(), "A01: one aggregated record");
        assertEquals("input", resources.get(0).purpose());
        assertEquals("read", resources.get(0).access());

        var lease = f.scopes().acquireLease(scope, OWNER,
                List.of(resources.get(0).resourceId()));
        assertEquals(2, lease.refs().size(), "fanned out to both eligible plugins");
        f.scopes().releaseLease(scope, lease.leaseId());

        f.scopes().removeResource(scope, OWNER, resources.get(0).resourceId());
        assertTrue(f.scopes().snapshot(scope, OWNER).resources().isEmpty(),
                "removing the record retires every underlying grant with it");
    }

    @Test
    void a02_sameNameDifferentDirectoriesStayIndependent() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path dirA = Files.createDirectories(temp.resolve("dirA"));
        Path dirB = Files.createDirectories(temp.resolve("dirB"));
        Path one = Files.writeString(dirA.resolve("data.csv"), "AAA");
        Path two = Files.writeString(dirB.resolve("data.csv"), "BBB");

        f.scopes().prepareSend(scope, OWNER, "send_1",
                List.of(new ChatResourceScopeService.NativeAttachment("att_1", one.toString(), "file")));
        f.scopes().prepareSend(scope, OWNER, "send_2",
                List.of(new ChatResourceScopeService.NativeAttachment("att_2", two.toString(), "file")));
        f.scopes().beginCommit(scope, OWNER, "send_1");
        f.scopes().beginCommit(scope, OWNER, "send_2");

        var resources = f.scopes().snapshot(scope, OWNER).resources();
        assertEquals(2, resources.size(), "A02: two records, neither replaced the other");
        assertNotEquals(resources.get(0).resourceId(), resources.get(1).resourceId());

        var lease = f.scopes().acquireLease(scope, OWNER,
                resources.stream().map(ChatResourceScopeService.Resource::resourceId).toList());
        var pluginRefs = lease.refs().stream()
                .filter(r -> r.pluginId().equals(PLUGIN_A)).toList();
        assertEquals(2, pluginRefs.size(), "one grant per resource per plugin");
        String first = Files.readString(f.files().resolve(PLUGIN_A, pluginRefs.get(0).ref().id()));
        String second = Files.readString(f.files().resolve(PLUGIN_A, pluginRefs.get(1).ref().id()));
        assertTrue((first + second).contains("AAA") && (first + second).contains("BBB"),
                "contents never cross-wired");
    }

    @Test
    void a03_resendingTheSamePathBumpsTheRevisionWithoutLeakingCopies() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path csv = file("same.csv", "v1");
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", csv));
        f.scopes().beginCommit(scope, OWNER, "send_1");
        String firstId = f.scopes().snapshot(scope, OWNER).resources().get(0).resourceId();

        Files.writeString(csv, "v2");
        f.scopes().prepareSend(scope, OWNER, "send_2", att("att_2", csv));
        f.scopes().beginCommit(scope, OWNER, "send_2");
        f.scopes().finishCommit(scope, OWNER, "send_2", java.util.Map.of());
        var resources = f.scopes().snapshot(scope, OWNER).resources();

        assertEquals(1, resources.size(), "A03: same path stays one resource");
        assertEquals(firstId, resources.get(0).resourceId());
        assertEquals(2, resources.get(0).revision());
    }

    @Test
    void a10_refreshOnALiveReferenceReArmsTheFingerprintAtTheCurrentContent() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path doc = file("refresh.txt", "v1");
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", doc));
        f.scopes().beginCommit(scope, OWNER, "send_1");
        String resourceId = f.scopes().snapshot(scope, OWNER).resources().get(0).resourceId();

        var pinned = f.scopes().acquireLease(scope, OWNER, List.of(resourceId));
        Files.writeString(doc, "v2 with more content");
        assertThrows(IllegalArgumentException.class,
                () -> f.scopes().acquireLease(scope, OWNER, List.of(resourceId)));
        var refreshed = f.scopes().refresh(scope, OWNER, resourceId);
        assertEquals(2, refreshed.revision());

        var next = f.scopes().acquireLease(scope, OWNER, List.of(resourceId));
        assertEquals("v2 with more content", Files.readString(
                f.files().resolve(PLUGIN_A, refFor(next, PLUGIN_A).ref().id())),
                "A10: turns after the refresh see the current content");

        f.scopes().releaseLease(scope, pinned.leaseId());
        f.scopes().releaseLease(scope, next.leaseId());
        assertTrue(Files.exists(doc), "refresh never deletes the user's file");

        Files.delete(doc);
        assertThrows(IllegalArgumentException.class,
                () -> f.scopes().refresh(scope, OWNER, resourceId));
        assertThrows(IllegalArgumentException.class,
                () -> f.scopes().acquireLease(scope, OWNER, List.of(resourceId)),
                "a live reference whose source is gone cannot be leased");
    }

    @Test
    void a10_snapshotBackedNativePicksKeepThePinnedVersionSemantics() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path doc = file("sensitive.txt", "v1");
        // The production matcher keys off the real user home; the seam pins the decision so
        // the SNAPSHOT branch stays covered without touching the machine's home. prepare
        // resolves symlinks first, so the seam sees the REAL path.
        Path realDoc = doc.toRealPath();
        f.scopes().snapshotEligible = path -> path.equals(realDoc);

        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", doc));
        f.scopes().beginCommit(scope, OWNER, "send_1");
        String resourceId = f.scopes().snapshot(scope, OWNER).resources().get(0).resourceId();
        assertEquals(1, copyFileCount(f.copyRoot()), "a sensitive pick keeps its bounded copy");

        var pinned = f.scopes().acquireLease(scope, OWNER, List.of(resourceId));
        Files.writeString(doc, "v2");
        var refreshed = f.scopes().refresh(scope, OWNER, resourceId);
        assertEquals(2, refreshed.revision());

        assertEquals("v1", Files.readString(
                f.files().resolve(PLUGIN_A, refFor(pinned, PLUGIN_A).ref().id())),
                "A10: the pinned turn keeps its original version (snapshot branch)");
        var next = f.scopes().acquireLease(scope, OWNER, List.of(resourceId));
        assertEquals("v2", Files.readString(
                f.files().resolve(PLUGIN_A, refFor(next, PLUGIN_A).ref().id())),
                "A10: new turns see the refreshed copy");

        f.scopes().releaseLease(scope, pinned.leaseId());
        f.scopes().releaseLease(scope, next.leaseId());
        assertEquals(1, copyFileCount(f.copyRoot()), "the superseded copy recycles after drain");
    }

    // ── ownership and lifecycle ─────────────────────────────────────────────────────────

    @Test
    void b01_crossScopeAndForeignOwnerAccessIsRejected() throws Exception {
        Fixture f = fixture();
        String mine = f.scopes().createScope(OWNER);
        String other = f.scopes().createScope(99L);
        Path doc = file("secret.txt", "x");
        f.scopes().prepareSend(mine, OWNER, "send_1", att("att_1", doc));
        f.scopes().beginCommit(mine, OWNER, "send_1");
        String resourceId = f.scopes().snapshot(mine, OWNER).resources().get(0).resourceId();

        assertThrows(IllegalArgumentException.class,
                () -> f.scopes().acquireLease(other, 99L, List.of(resourceId)),
                "B01: another scope cannot resolve this scope's resource");
        assertThrows(IllegalArgumentException.class,
                () -> f.scopes().acquireLease(mine, OWNER, List.of("res_not_there")));
        assertThrows(IllegalArgumentException.class, () -> f.scopes().snapshot(mine, 99L));
        assertThrows(IllegalArgumentException.class,
                () -> f.scopes().prepareSend(mine, 99L, "send_x", List.of()));
    }

    @Test
    void uploadsJoinATransactionAndTheirIdentityIsTheAttachmentNotTheName() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        f.scopes().prepareSend(scope, OWNER, "send_1", List.of());
        var first = new MockMultipartFile("file", "report.csv", "text/csv", "a".getBytes());
        var second = new MockMultipartFile("file", "report.csv", "text/csv", "different".getBytes());

        f.scopes().addUploadToSend(scope, OWNER, "send_1", "att_1", first);
        f.scopes().addUploadToSend(scope, OWNER, "send_1", "att_2", second);
        // Idempotent re-upload of the same attachment id joins once.
        f.scopes().addUploadToSend(scope, OWNER, "send_1", "att_1", first);
        f.scopes().beginCommit(scope, OWNER, "send_1");

        var resources = f.scopes().snapshot(scope, OWNER).resources();
        assertEquals(2, resources.size(), "same-named uploads from different sources stay independent");
        assertEquals("upload", resources.get(0).source());
    }

    @Test
    void outputTargetIsHostSaveOnlyAndNeverGrantsWorkerWrite() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path out = Files.createDirectories(temp.resolve("out-target"));
        Path empty = Files.createDirectories(temp.resolve("empty-out"));

        f.scopes().setOutputTarget(scope, OWNER, out.toString());
        assertEquals(out.toString(), f.scopes().outputTarget(scope));

        assertTrue(f.files().writablePaths(PLUGIN_A).isEmpty(),
                "invariant 5.3-8: an output target mints no grant");
        assertTrue(f.files().writablePaths(PLUGIN_B).isEmpty());

        f.scopes().setOutputTarget(scope, OWNER, empty.toString()); // C03: empty dir is valid
        f.scopes().setOutputTarget(scope, OWNER, null);
        assertEquals(null, f.scopes().outputTarget(scope));

        assertThrows(IllegalArgumentException.class,
                () -> f.scopes().setOutputTarget(scope, OWNER, temp.resolve("missing").toString()));
    }

    @Test
    void closingScopeRevokesGrantsReclaimsCopiesAndIsIdempotent() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path doc = file("close.txt", "x");
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", doc));
        f.scopes().beginCommit(scope, OWNER, "send_1");
        String resourceId = f.scopes().snapshot(scope, OWNER).resources().get(0).resourceId();
        var lease = f.scopes().acquireLease(scope, OWNER, List.of(resourceId));

        f.scopes().closeScope(scope, OWNER);
        f.scopes().closeScope(scope, OWNER); // idempotent

        assertTrue(f.files().readablePaths(PLUGIN_A).isEmpty());
        assertTrue(Files.exists(doc), "closing a scope never deletes a live-referenced original");
        assertThrows(IllegalArgumentException.class,
                () -> f.scopes().acquireLease(scope, OWNER, List.of(resourceId)));
    }

    @Test
    void aDirectorySelectionIsAZeroCopyLiveReferenceLeasedRead() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path dir = Files.createDirectories(temp.resolve("资料"));
        Files.writeString(dir.resolve("one.txt"), "1");
        Files.writeString(dir.resolve("nested.txt"), "2");

        f.scopes().prepareSend(scope, OWNER, "send_1", List.of(
                new ChatResourceScopeService.NativeAttachment("att_1", dir.toString(), "directory")));
        f.scopes().beginCommit(scope, OWNER, "send_1");
        var resource = f.scopes().snapshot(scope, OWNER).resources().get(0);
        assertEquals("directory", resource.kind());

        var lease = f.scopes().acquireLease(scope, OWNER, List.of(resource.resourceId()));
        Path granted = f.files().resolve(PLUGIN_A, refFor(lease, PLUGIN_A).ref().id());
        assertEquals(dir.toRealPath(), granted.toRealPath(), "the tree is referenced, not copied");
        assertEquals("1", Files.readString(granted.resolve("one.txt")));
        assertEquals(0, copyFileCount(f.copyRoot()), "no tree duplication for a native directory");
    }

    // ── D2: restart persistence ─────────────────────────────────────────────────────────

    @Test
    void aRestartReArmsLiveReferencesAndOwnedCopies() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path live = file("persisted.csv", "still-here");
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", live));
        f.scopes().addUploadToSend(scope, OWNER, "send_1", "att_2",
                new MockMultipartFile("file", "report.csv", "text/csv", "up".getBytes()));
        f.scopes().beginCommit(scope, OWNER, "send_1");
        String liveId = f.scopes().snapshot(scope, OWNER).resources().stream()
                .filter(r -> "native".equals(r.source())).findFirst().orElseThrow().resourceId();
        String uploadId = f.scopes().snapshot(scope, OWNER).resources().stream()
                .filter(r -> "upload".equals(r.source())).findFirst().orElseThrow().resourceId();

        // "Restart": a fresh registry over the same copy root re-arms what the manifest pinned.
        ChatResourceScopeService rebooted = new ChatResourceScopeService(
                f.chatFiles(), f.files(), f.copyRoot());
        var resources = rebooted.snapshot(scope, OWNER).resources();
        assertEquals(2, resources.size(), "D2: both resources survive the restart");
        assertEquals("ready", resources.stream()
                .filter(r -> r.resourceId().equals(liveId)).findFirst().orElseThrow().status());
        assertEquals("ready", resources.stream()
                .filter(r -> r.resourceId().equals(uploadId)).findFirst().orElseThrow().status());
        var lease = rebooted.acquireLease(scope, OWNER, List.of(liveId, uploadId));
        assertEquals("still-here", Files.readString(
                f.files().resolve(PLUGIN_A, refTo(f, lease, PLUGIN_A, live).ref().id())));
    }

    @Test
    void aRestartMarksStaleLiveReferencesUnavailableButKeepsThemVisible() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path stale = file("stale.csv", "v1");
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", stale));
        f.scopes().beginCommit(scope, OWNER, "send_1");
        String resourceId = f.scopes().snapshot(scope, OWNER).resources().get(0).resourceId();

        Files.writeString(stale, "v2 changed after the manifest was written");

        ChatResourceScopeService rebooted = new ChatResourceScopeService(
                f.chatFiles(), f.files(), f.copyRoot());
        var resource = rebooted.snapshot(scope, OWNER).resources().get(0);
        assertEquals("unavailable", resource.status(),
                "D2: a changed live reference is marked, not silently dropped");
        assertThrows(IllegalArgumentException.class,
                () -> rebooted.acquireLease(scope, OWNER, List.of(resourceId)));
        rebooted.removeResource(scope, OWNER, resourceId); // visible AND removable
        assertTrue(rebooted.snapshot(scope, OWNER).resources().isEmpty());
    }

    @Test
    void aRestartReclaimsOrphanCopyDirectories() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        // A crashed PREPARE (uploads landed, send never committed): the pending copy has no
        // committed resource vouching for it inside the scope dir.
        f.scopes().prepareSend(scope, OWNER, "send_1", List.of());
        f.scopes().addUploadToSend(scope, OWNER, "send_1", "att_1",
                new MockMultipartFile("file", "orphan-parent.csv", "text/csv", "x".getBytes()));
        assertEquals(1, copyFileCount(f.copyRoot()));
        // And a crashed run's directory with no manifest at all.
        Path orphan = Files.createDirectories(f.copyRoot().resolve("cs_orphan"));
        Files.writeString(orphan.resolve("leftover.txt"), "crashed run");

        ChatResourceScopeService rebooted = new ChatResourceScopeService(
                f.chatFiles(), f.files(), f.copyRoot());
        assertFalse(Files.exists(orphan), "a dir without a manifest is a crashed run's residue");
        assertEquals(0, copyFileCount(f.copyRoot()),
                "pending copies the manifest does not vouch for are reclaimed");
        assertTrue(rebooted.snapshot(scope, OWNER).resources().isEmpty());
    }

    @Test
    void uploadsAreRejectedOnceTheSendIsCommitting() throws Exception {
        // E08's state guard: uploads belong to the payload — once the commit bracket opens,
        // a racing upload must be refused, not silently merged into the committing set.
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        f.scopes().prepareSend(scope, OWNER, "send_1", List.of());
        f.scopes().beginCommit(scope, OWNER, "send_1");

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> f.scopes().addUploadToSend(scope, OWNER, "send_1", "att_late",
                        new MockMultipartFile("file", "late.csv", "text/csv", "x".getBytes())));
        assertTrue(rejected.getMessage().contains("uploads belong to the payload"),
                rejected.getMessage());
        assertTrue(f.scopes().snapshot(scope, OWNER).resources().isEmpty(),
                "the late upload never joined the committing send");
    }

    @Test
    void finishCommitOnASendSweptToFailedBetweenBeginAndFinishIsRejected() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        Fixture f = fixture(now::get);
        String scope = f.scopes().createScope(OWNER);
        Path csv = file("stalled.csv", "x");
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", csv));
        f.scopes().beginCommit(scope, OWNER, "send_1");

        now.set(now.get().plus(ChatResourceScopeService.SEND_TTL.plusSeconds(1)));
        f.scopes().createScope(OWNER); // any scope operation sweeps (no lease → FAILED)
        assertEquals("failed", f.scopes().sendStatus(scope, OWNER, "send_1").state());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> f.scopes().finishCommit(scope, OWNER, "send_1",
                        java.util.Map.of("streamId", "st_9")));
        assertTrue(refused.getMessage().contains("cannot finish"), refused.getMessage());
        assertEquals(null, f.scopes().replayableResult(scope, OWNER, "send_1"),
                "a swept send must never be resurrected as an idempotence record");
    }

    // ── review regressions ──────────────────────────────────────────────────────────────

    @Test
    void aCommittingSendHoldingLiveReferencesSurvivesTheTtlSweep() throws Exception {
        // The sweep's COMMITTING protection asks "does a lease still pin this send's
        // resources?" — a pin list that filtered out live references let long turns (>30 min
        // streams, agent steps at 900 s each) lose their attachments mid-execution.
        java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        Fixture f = fixture(now::get);
        String scope = f.scopes().createScope(OWNER);
        Path live = file("longturn.csv", "streaming");
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", live));
        f.scopes().beginCommit(scope, OWNER, "send_1");
        String resourceId = f.scopes().snapshot(scope, OWNER).resources().get(0).resourceId();
        var lease = f.scopes().acquireLease(scope, OWNER, List.of(resourceId));

        now.set(now.get().plus(ChatResourceScopeService.SEND_TTL.plusSeconds(1)));
        f.scopes().createScope(OWNER); // any scope operation sweeps

        assertEquals(1, f.scopes().snapshot(scope, OWNER).resources().size(),
                "a committing send with a live lease is never rolled back by the TTL sweep");
        f.scopes().finishCommit(scope, OWNER, "send_1", java.util.Map.of());
        assertEquals("committed", f.scopes().sendStatus(scope, OWNER, "send_1").state());
        f.scopes().releaseLease(scope, lease.leaseId());
    }

    @Test
    void aCommittingSendWithoutALiveLeaseIsStillSweptPastTheTtl() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        Fixture f = fixture(now::get);
        String scope = f.scopes().createScope(OWNER);
        Path live = file("idle.csv", "x");
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", live));
        f.scopes().beginCommit(scope, OWNER, "send_1");
        assertTrue(f.scopes().snapshot(scope, OWNER).resources().size() > 0);

        now.set(now.get().plus(ChatResourceScopeService.SEND_TTL.plusSeconds(1)));
        f.scopes().createScope(OWNER);

        assertTrue(f.scopes().snapshot(scope, OWNER).resources().isEmpty(),
                "no lease, no protection — the expired COMMITTING send rolls back");
    }

    @Test
    void aSymlinkedSelectionRecordsTheRealTarget() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path real = file("real-target.csv", "content");
        Path alias = Files.createSymbolicLink(temp.resolve("alias.csv"), real);

        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", alias));
        f.scopes().beginCommit(scope, OWNER, "send_1");
        var resource = f.scopes().snapshot(scope, OWNER).resources().get(0);
        assertEquals(real.toRealPath(), resource.copyPath().toRealPath(),
                "the reference and the sensitive-path decision both see the REAL target");
        var lease = f.scopes().acquireLease(scope, OWNER, List.of(resource.resourceId()));
        assertEquals("content", Files.readString(
                f.files().resolve(PLUGIN_A, refFor(lease, PLUGIN_A).ref().id())));
    }

    @Test
    void aCraftedManifestCopyPathOutsideTheScopeDirectoryIsNeutered() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path live = file("keep.csv", "x");
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", live));
        f.scopes().beginCommit(scope, OWNER, "send_1");

        // Forge a manifest whose owned entry names an OUTSIDE directory (the arbitrary-delete
        // route): the restart must neuter it, and closing the scope must not delete the target.
        Path precious = Files.createDirectories(temp.resolve("precious-user-dir"));
        Files.writeString(precious.resolve("keepme.txt"), "do not delete");
        Path manifest = f.copyRoot().resolve(scope).resolve(ChatResourceScopeService.SCOPE_MANIFEST);
        String json = Files.readString(manifest);
        String resourceId = f.scopes().snapshot(scope, OWNER).resources().get(0).resourceId();
        json = json.replace("\"ownedCopy\":false", "\"ownedCopy\":true")
                .replace("\"copyPath\":\"" + live.toRealPath() + "\"",
                        "\"copyPath\":\"" + precious + "\"");
        Files.writeString(manifest, json);

        ChatResourceScopeService rebooted = new ChatResourceScopeService(
                f.chatFiles(), f.files(), f.copyRoot());
        var resource = rebooted.snapshot(scope, OWNER).resources().get(0);
        assertEquals("unavailable", resource.status(),
                "an out-of-scope copyPath is never trusted for deletion");
        assertEquals(resourceId, resource.resourceId());
        rebooted.closeScope(scope, OWNER);
        assertTrue(Files.exists(precious.resolve("keepme.txt")),
                "the forged entry must never delete a path outside the copy store");
    }

    @Test
    void refreshOnALiveReferenceEnforcesTheQuotaDeltaInBothDirections() throws Exception {
        fan.summer.fengyu.runtime.ChatResourceLimits small =
                new fan.summer.fengyu.runtime.ChatResourceLimits();
        small.setMaxSingleFileBytes(org.springframework.util.unit.DataSize.ofMegabytes(4));
        small.setMaxTreeBytes(org.springframework.util.unit.DataSize.ofMegabytes(4));
        small.setMaxScopeBytes(org.springframework.util.unit.DataSize.ofMegabytes(4));
        Fixture f = fixture(Instant::now, small);
        String scope = f.scopes().createScope(OWNER);
        Path doc = file("quota.txt", "1234"); // 4 bytes — the whole 4 MB budget in spirit
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", doc));
        f.scopes().beginCommit(scope, OWNER, "send_1");
        String resourceId = f.scopes().snapshot(scope, OWNER).resources().get(0).resourceId();

        // Grow past the scope cap: the refresh's size delta must be rejected, old revision kept.
        Files.writeString(doc, "grown far past the cap ".repeat(220_000));
        assertThrows(IllegalArgumentException.class,
                () -> f.scopes().refresh(scope, OWNER, resourceId));
        assertEquals(1, f.scopes().snapshot(scope, OWNER).resources().size());

        // Shrink back: the released headroom makes the refresh pass again.
        Files.writeString(doc, "small again but a different length");
        var refreshed = f.scopes().refresh(scope, OWNER, resourceId);
        assertEquals(2, refreshed.revision());
        f.scopes().acquireLease(scope, OWNER, List.of(resourceId)); // re-armed and leasable
    }
}
