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
 * model (task doc revision 2): selection never grants, prepare copies at send time, commit is
 * idempotent by sendId, leases derive per-turn grants over the one host copy, and retired copies
 * recycle exactly once. Server-side halves of E01–E12, A01–A03, A10, B01, and B02.
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
                new ChatResourceScopeService(chatFiles, files, copyRoot);
        scopes.clock = clock;
        return new Fixture(scopes, files, copyRoot);
    }

    private record Fixture(ChatResourceScopeService scopes, PluginFileGrantService files,
            Path copyRoot) {}

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

    private List<ChatResourceScopeService.NativeAttachment> att(String attachmentId, Path path) {
        return List.of(new ChatResourceScopeService.NativeAttachment(
                attachmentId, path.toString(), "file"));
    }

    /** Files currently sitting in the host copy store (pending + committed copies). */
    private long copyFileCount(Path copyRoot) throws Exception {
        if (!Files.exists(copyRoot)) return 0;
        try (var paths = Files.walk(copyRoot)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    // ── E01/E02: selection → draft; only prepare copies; abort reclaims ────────────────

    @Test
    void e01_prepareCopiesButMintsNoPluginGrantUntilLease() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path csv = file("contacts.csv", "a,b\n1,2\n");

        // "Selection" is client-side; the FIRST backend touch is the send prepare. Copies are
        // created (send-time truth) but zero plugin grants exist — a lease is the only minter.
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", csv));

        assertEquals(1, f.scopes().sendStatus(scope, OWNER, "send_1").attachments().size());
        assertTrue(f.files().readablePaths(PLUGIN_A).isEmpty(),
                "E01: preparing a send mints no read grant");
        assertTrue(f.files().readablePaths(PLUGIN_B).isEmpty(),
                "E01: preparing a send mints no read grant for any plugin");
        assertEquals(1, copyFileCount(f.copyRoot()), "the host copy exists");

        f.scopes().beginCommit(scope, OWNER, "send_1");
        String resourceId = f.scopes().snapshot(scope, OWNER).resources().get(0).resourceId();
        var lease = f.scopes().acquireLease(scope, OWNER, List.of(resourceId));
        assertEquals(2, lease.refs().size(), "one read grant per eligible plugin at lease time");
        assertEquals(1, f.files().readablePaths(PLUGIN_A).size(),
                "plugin A holds exactly one read grant over the host copy");
        assertEquals(1, f.files().readablePaths(PLUGIN_B).size(),
                "plugin B holds exactly one read grant over the host copy");
    }

    @Test
    void e02_abortingAnUncommittedSendReclaimsItsCopies() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path csv = file("abort.csv", "x");

        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", csv));
        assertEquals(1, copyFileCount(f.copyRoot()));

        f.scopes().abortSend(scope, OWNER, "send_1");

        assertEquals(0, copyFileCount(f.copyRoot()), "E02: no backend residue after abort");
        assertEquals("failed", f.scopes().sendStatus(scope, OWNER, "send_1").state());
        assertTrue(f.scopes().snapshot(scope, OWNER).resources().isEmpty(),
                "an aborted send commits nothing");
    }

    @Test
    void e03_prepareCapturesSendTimeContentAndFailsClearlyOnMissingSource() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path csv = file("versioned.csv", "v1");
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", csv));

        Files.writeString(csv, "v2"); // the outside world keeps editing after prepare

        f.scopes().beginCommit(scope, OWNER, "send_1");
        String resourceId = f.scopes().snapshot(scope, OWNER).resources().get(0).resourceId();
        var lease = f.scopes().acquireLease(scope, OWNER, List.of(resourceId));
        assertEquals("v1", Files.readString(
                f.files().resolve(PLUGIN_A, refFor(lease, PLUGIN_A).ref().id())),
                "the copy keeps the send-time content, not the live file");

        // A vanished source is a clear failure, never a silent empty copy (draft stays client-side).
        Files.delete(csv);
        assertThrows(IllegalArgumentException.class,
                () -> f.scopes().prepareSend(scope, OWNER, "send_2", att("att_2", csv)));
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
        // One send carrying more attachments than the per-scope ceiling: the Nth commitInto
        // throws MID-MOVE — beginCommit must undo its own partial work, not strand the scope.
        java.util.List<ChatResourceScopeService.NativeAttachment> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i <= ChatResourceScopeService.MAX_RESOURCES_PER_SCOPE; i++) {
            tooMany.add(new ChatResourceScopeService.NativeAttachment(
                    "att_" + i, file("ceil" + i + ".csv", "x").toString(), "file"));
        }
        f.scopes().prepareSend(scope, OWNER, "send_ceiling", tooMany);

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
        assertEquals(1, copyFileCount(f.copyRoot()), "replaying prepare copies nothing twice");

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
        assertEquals(1, copyFileCount(f.copyRoot()));
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
    void e09e10_releasedLeaseKeepsTheCopyAndContinueReDerivesAccess() throws Exception {
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
        assertEquals(1, copyFileCount(f.copyRoot()),
                "E10: the message-owned copy survives the turn");

        var second = f.scopes().acquireLease(scope, OWNER, List.of(resourceId));
        assertEquals(1, f.files().readablePaths(PLUGIN_A).size(),
                "E09: 'continue' re-derives access over the same copy");
        assertEquals("data", Files.readString(
                f.files().resolve(PLUGIN_A, refFor(second, PLUGIN_A).ref().id())));
    }

    @Test
    void e11_removeDuringExecutionIsImmediateForNewTurnsAndRecyclesTheCopyOnce() throws Exception {
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
        assertEquals(1, copyFileCount(f.copyRoot()),
                "the pinned copy stays until the executing turn releases");

        f.scopes().releaseLease(scope, lease.leaseId());
        assertEquals(0, copyFileCount(f.copyRoot()),
                "E11: physical recycling happens exactly once, after the drain");
        f.scopes().releaseLease(scope, lease.leaseId()); // idempotent release never throws
    }

    @Test
    void b02_pinnedLeaseKeepsResolvingUntilReleaseThenPhysicallyRecycles() throws Exception {
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
                    "release must physically reclaim the retired grants");
        }
        assertEquals(0, copyFileCount(f.copyRoot()), "the retired copy is deleted exactly once");
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
        assertEquals(1, copyFileCount(f.copyRoot()),
                "the superseded revision's copy retired with the replace");
    }

    @Test
    void a10_refreshSwapsContentAtomicallyAndKeepsPinnedTurnsOnTheOldCopy() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(OWNER);
        Path doc = file("refresh.txt", "v1");
        f.scopes().prepareSend(scope, OWNER, "send_1", att("att_1", doc));
        f.scopes().beginCommit(scope, OWNER, "send_1");
        String resourceId = f.scopes().snapshot(scope, OWNER).resources().get(0).resourceId();

        var pinned = f.scopes().acquireLease(scope, OWNER, List.of(resourceId));
        Files.writeString(doc, "v2");
        var refreshed = f.scopes().refresh(scope, OWNER, resourceId);
        assertEquals(2, refreshed.revision());

        assertEquals("v1", Files.readString(
                f.files().resolve(PLUGIN_A, refFor(pinned, PLUGIN_A).ref().id())),
                "A10: the pinned turn keeps its original version");
        var next = f.scopes().acquireLease(scope, OWNER, List.of(resourceId));
        assertEquals("v2", Files.readString(
                f.files().resolve(PLUGIN_A, refFor(next, PLUGIN_A).ref().id())),
                "A10: new turns see the refreshed version");

        f.scopes().releaseLease(scope, pinned.leaseId());
        f.scopes().releaseLease(scope, next.leaseId());
        assertEquals(1, copyFileCount(f.copyRoot()), "the superseded copy recycles after drain");

        Files.delete(doc);
        assertThrows(IllegalArgumentException.class,
                () -> f.scopes().refresh(scope, OWNER, resourceId));
        f.scopes().acquireLease(scope, OWNER, List.of(resourceId));
        // The acquire above not throwing IS the assertion: failure keeps the old revision usable.
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
        assertEquals(0, copyFileCount(f.copyRoot()), "every copy under the scope is reclaimed");
        assertThrows(IllegalArgumentException.class,
                () -> f.scopes().acquireLease(scope, OWNER, List.of(resourceId)));
    }

    @Test
    void aDirectorySelectionCopiesItsTreeOnceAndLeasesItRead() throws Exception {
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
        assertEquals("1", Files.readString(granted.resolve("one.txt")));
        assertEquals(2, copyFileCount(f.copyRoot()), "one logical copy, tree included");
    }
}
