package fan.summer.fengyu.plugin.email.service;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import fan.summer.fengyu.plugin.email.crypto.CredentialCipher;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.EmailMessageRequest;
import fan.summer.fengyu.plugin.email.model.SendResult;
import fan.summer.fengyu.plugin.email.repository.AddressBookRepository;
import fan.summer.fengyu.sdk.PluginDatabaseConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.KeyGenerator;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingSendServiceTest {
    @TempDir Path temp;
    private final Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);

    @Test void preparationSendsNothingAndPersistsExactImmutableRecipientSnapshot() {
        AtomicInteger sends = new AtomicInteger();
        var service = service("prepare", (confirmationId, request) -> { sends.incrementAndGet(); return SendResult.success("sent"); });

        var envelope = service.prepareSingle(request("alice@example.com"));

        assertEquals(0, sends.get());
        assertTrue(envelope.confirmationRequired());
        assertEquals("fan.summer.email", envelope.confirmation().pluginId());
        assertEquals("confirm_send", envelope.confirmation().approveMethod());
        assertEquals("reject_send", envelope.confirmation().rejectMethod());
        var pending = service.status(envelope.confirmation().confirmationId()).orElseThrow();
        assertEquals("PENDING", pending.status());
        assertTrue(pending.snapshotJson().contains("alice@example.com"));
        assertFalse(pending.snapshotJson().contains("smtp-secret"));
    }

    @Test void confirmationPassesPreparedIdToTheSender() {
        AtomicReference<String> receivedId = new AtomicReference<>();
        var service = service("confirmation-id", (confirmationId, request) -> {
            receivedId.set(confirmationId);
            return SendResult.success("sent");
        });
        String preparedId = service.prepareSingle(request("alice@example.com")).confirmation().confirmationId();

        service.confirm(preparedId);

        assertEquals(preparedId, receivedId.get());
    }

    @Test void attachmentBatchPreviewDoesNotPersistAndPrepareKeepsExactTagMetadata() throws Exception {
        EmailDatabase database = database("batch-preview");
        AddressBookRepository contacts = new AddressBookRepository(database);
        long east = contacts.saveTag(null, "East");
        long customers = contacts.saveTag(null, "Customers");
        long managers = contacts.saveTag(null, "Managers");
        long alice = contacts.saveContact(new AddressBookRepository.ContactInput(null, "alice@example.com", "Alice", null));
        long manager = contacts.saveContact(new AddressBookRepository.ContactInput(null, "manager@example.com", "Manager", null));
        contacts.assignTags(java.util.Set.of(alice), java.util.Set.of(east, customers));
        contacts.assignTags(java.util.Set.of(manager), java.util.Set.of(east, managers));
        Path input = Files.createDirectory(temp.resolve("batch-preview-input"));
        Path tagged = Files.writeString(input.resolve("report_East.pdf"), "report");
        Path ignored = Files.writeString(input.resolve("README"), "ignored");
        Path common = Files.writeString(temp.resolve("terms.pdf"), "terms");
        var service = new PendingSendService(database,
            (confirmationId, request) -> SendResult.success("sent"), clock, Duration.ofMinutes(30));

        var preview = service.previewBatch(request("template@example.com"), input, List.of(common),
            java.util.Set.of(customers), java.util.Set.of(managers));

        assertEquals(1, preview.messageCount());
        assertEquals("East", preview.messages().getFirst().attachmentTag());
        assertEquals(List.of("alice@example.com"), preview.messages().getFirst().to());
        assertEquals(List.of("manager@example.com"), preview.messages().getFirst().cc());
        assertEquals(List.of(tagged.toString()), preview.messages().getFirst().tagAttachments());
        assertEquals(List.of(common.toString()), preview.messages().getFirst().commonAttachments());
        assertEquals(List.of(ignored.toString()), preview.ignoredFiles());
        assertTrue(service.records(null, null, null, null, 0, 20).tasks().isEmpty());

        var confirmation = service.prepareAttachmentBatch(request("template@example.com"), input,
            List.of(common), java.util.Set.of(customers), java.util.Set.of(managers));
        String snapshot = service.status(confirmation.confirmation().confirmationId()).orElseThrow().snapshotJson();
        assertTrue(snapshot.contains("\"attachmentTag\":\"East\""));
        assertTrue(snapshot.contains(tagged.toString()));
        assertTrue(snapshot.contains(common.toString()));
    }

    @Test void confirmationSummaryIdentifiesTheExactMessageWithoutIncludingItsBody() {
        var service = service("summary", (confirmationId, request) -> SendResult.success("sent"));
        var request = new EmailMessageRequest(42, List.of("to@example.com"), List.of("cc@example.com"),
            List.of("bcc@example.com"), "Quarterly report", "private body", "<p>private body</p>",
            List.of(Path.of("/tmp/report.pdf")));

        var summary = service.prepareSingle(request).confirmation().summary();

        // Single send has no attachment tag, so per-message rows group under the synthetic "Message 1".
        assertTrue(summary.contains(new PendingSendService.SummaryRow("account", "42", null)));
        assertTrue(summary.contains(new PendingSendService.SummaryRow("mode", "SINGLE", null)));
        assertTrue(summary.contains(new PendingSendService.SummaryRow("to", "to@example.com", "Message 1")));
        assertTrue(summary.contains(new PendingSendService.SummaryRow("cc", "cc@example.com", "Message 1")));
        assertTrue(summary.contains(new PendingSendService.SummaryRow("bcc", "bcc@example.com", "Message 1")));
        assertTrue(summary.contains(new PendingSendService.SummaryRow("subject", "Quarterly report", "Message 1")));
        assertTrue(summary.contains(new PendingSendService.SummaryRow("tag_attachments", "report.pdf", "Message 1")));
        assertFalse(summary.toString().contains("private body"));
    }

    @Test void rejectionAndReplayNeverSend() {
        AtomicInteger sends = new AtomicInteger();
        var service = service("reject", (confirmationId, request) -> { sends.incrementAndGet(); return SendResult.success("sent"); });
        String id = service.prepareSingle(request("alice@example.com")).confirmation().confirmationId();

        assertEquals("REJECTED", service.reject(id).status());
        assertEquals("REJECTED", service.confirm(id).status());
        assertEquals(0, sends.get());
    }

    @Test void expiredConfirmationNeverSends() {
        AtomicInteger sends = new AtomicInteger();
        var database = database("expired");
        var service = new PendingSendService(database,
            (confirmationId, request) -> { sends.incrementAndGet(); return SendResult.success("sent"); }, clock, Duration.ofSeconds(-1));
        String id = service.prepareSingle(request("alice@example.com")).confirmation().confirmationId();

        assertEquals("EXPIRED", service.confirm(id).status());
        assertEquals(0, sends.get());
    }

    @Test void sqliteExpiryUsesUtcEvenWhenTheJvmClockHasAnotherZone() {
        AtomicInteger sends = new AtomicInteger();
        Clock shanghaiClock = Clock.fixed(Instant.parse("2026-07-14T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
        EmailDatabase database = new EmailDatabase(new PluginDatabaseConfig("sqlite", "org.sqlite.JDBC",
            "jdbc:sqlite:" + temp.resolve("timezone.db"), "", "", temp.resolve("timezone-data")));
        var service = new PendingSendService(database,
            (confirmationId, request) -> { sends.incrementAndGet(); return SendResult.success("sent"); },
            shanghaiClock, Duration.ofSeconds(-1));
        String id = service.prepareSingle(request("alice@example.com")).confirmation().confirmationId();

        assertEquals("EXPIRED", service.confirm(id).status());
        assertEquals(0, sends.get());
    }

    @Test void partialFailureIsReportedPerRecipient() {
        var service = service("partial", (confirmationId, request) -> request.to().contains("bad@example.com")
            ? SendResult.failure("mailbox unavailable") : SendResult.success(request.to().getFirst()));
        var plan = BatchPlanner.byTags(request("template@example.com"),
            java.util.Set.of("good@example.com", "bad@example.com"));
        String id = service.prepareBatch("TAGS", plan).confirmation().confirmationId();

        var result = service.confirm(id);

        assertEquals("PARTIAL_FAILED", result.status());
        assertEquals(1, result.succeeded());
        assertEquals(1, result.failed());
    }

    @Test void twoConcurrentConfirmationsExecuteSmtpOnlyOnce() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var service = service("concurrent", (confirmationId, request) -> {
            sends.incrementAndGet(); entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return SendResult.success("sent");
        });
        String id = service.prepareSingle(request("alice@example.com")).confirmation().confirmationId();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> service.confirm(id));
            entered.await();
            var second = pool.submit(() -> service.confirm(id));
            release.countDown();
            var firstResult = first.get();
            var secondResult = second.get();
            assertNotEquals("PENDING", firstResult.status());
            assertNotEquals("PENDING", secondResult.status());
        }
        assertEquals(1, sends.get());
    }

    @Test void greenMailReceivesOneMessageOnlyAfterConfirmationAndNoneOnReplay() throws Exception {
        GreenMail greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        greenMail.start();
        try {
            greenMail.setUser("sender@example.com", "sender@example.com", "smtp-secret");
            greenMail.setUser("alice@example.com", "alice@example.com", "unused");
            EmailDatabase database = database("greenmail-confirm");
            CredentialCipher cipher = cipher();
            long accountId = new AccountService(database, cipher).save(new AccountService.AccountInput(null,
                "Sender", "sender@example.com", "smtp-secret", "127.0.0.1", greenMail.getSmtp().getPort(),
                "PLAIN", null, null, null, false, false, true));
            var service = new PendingSendService(database, new EmailSendService(database, cipher));
            String id = service.prepareSingle(new EmailMessageRequest(accountId, List.of("alice@example.com"),
                List.of(), List.of(), "Subject", "Body", null, List.of())).confirmation().confirmationId();

            assertEquals(0, greenMail.getReceivedMessages().length);
            assertEquals("COMPLETED", service.confirm(id).status());
            assertTrue(greenMail.waitForIncomingEmail(5_000, 1));
            assertEquals("COMPLETED", service.confirm(id).status());
            assertEquals(1, greenMail.getReceivedMessages().length);
        } finally {
            greenMail.stop();
        }
    }

    @Test void stuckSendingIsReclaimedAsFailedOnNextCall() throws Exception {
        EmailDatabase database = database("stuck-sending");
        AtomicInteger sends = new AtomicInteger();
        // A clock far in the future makes STALE_THRESHOLD (5 min) treat any already-SENDING row as stale.
        Clock futureClock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
        var service = new PendingSendService(database,
            (confirmationId, request) -> { sends.incrementAndGet(); return SendResult.success("sent"); },
            futureClock, Duration.ofMinutes(30));
        String id = service.prepareSingle(request("alice@example.com")).confirmation().confirmationId();
        // Simulate a worker that claimed the task (PENDING -> SENDING) then died before finishing.
        forceStatus(database, id, "SENDING", "2000-01-01 00:00:00");

        String status = service.status(id).orElseThrow().status();

        assertEquals("FAILED", status);
        assertEquals(0, sends.get());
    }

    private static void forceStatus(EmailDatabase database, String confirmationId, String status, String updatedAt)
            throws Exception {
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                 "UPDATE FENGYU_PL_Email_Pending_Send SET status=?, updated_at=? WHERE confirmation_id=?")) {
            statement.setString(1, status);
            statement.setString(2, updatedAt);
            statement.setString(3, confirmationId);
            statement.executeUpdate();
        }
    }

    private PendingSendService service(String name, PendingSendService.Sender sender) {
        return new PendingSendService(database(name), sender, clock, Duration.ofMinutes(30));
    }

    private EmailDatabase database(String name) {
        return new EmailDatabase(new PluginDatabaseConfig("h2", "org.h2.Driver",
            "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "", temp));
    }

    private static EmailMessageRequest request(String recipient) {
        return new EmailMessageRequest(7, List.of(recipient), List.of(), List.of(),
            "Subject", "Plain", "<p>HTML</p>", List.of());
    }

    private static CredentialCipher cipher() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return new CredentialCipher(generator.generateKey());
    }
}
