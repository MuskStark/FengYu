package fan.summer.fengyu.plugin.email.service;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import fan.summer.fengyu.plugin.email.crypto.CredentialCipher;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.EmailMessageRequest;
import fan.summer.fengyu.plugin.email.model.SendResult;
import fan.summer.fengyu.sdk.PluginDatabaseConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.KeyGenerator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingSendServiceTest {
    @TempDir Path temp;
    private final Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);

    @Test void preparationSendsNothingAndPersistsExactImmutableRecipientSnapshot() {
        AtomicInteger sends = new AtomicInteger();
        var service = service("prepare", request -> { sends.incrementAndGet(); return SendResult.success("sent"); });

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

    @Test void rejectionAndReplayNeverSend() {
        AtomicInteger sends = new AtomicInteger();
        var service = service("reject", request -> { sends.incrementAndGet(); return SendResult.success("sent"); });
        String id = service.prepareSingle(request("alice@example.com")).confirmation().confirmationId();

        assertEquals("REJECTED", service.reject(id).status());
        assertEquals("REJECTED", service.confirm(id).status());
        assertEquals(0, sends.get());
    }

    @Test void expiredConfirmationNeverSends() {
        AtomicInteger sends = new AtomicInteger();
        var database = database("expired");
        var service = new PendingSendService(database,
            request -> { sends.incrementAndGet(); return SendResult.success("sent"); }, clock, Duration.ofSeconds(-1));
        String id = service.prepareSingle(request("alice@example.com")).confirmation().confirmationId();

        assertEquals("EXPIRED", service.confirm(id).status());
        assertEquals(0, sends.get());
    }

    @Test void partialFailureIsReportedPerRecipient() {
        var service = service("partial", request -> request.to().contains("bad@example.com")
            ? SendResult.failure("mailbox unavailable") : SendResult.success(request.to().getFirst()));
        var plan = BatchPlanner.byTags(request("template@example.com"),
            java.util.Set.of("good@example.com", "bad@example.com"));
        String id = service.prepareBatch("TAGS", plan).confirmation().confirmationId();

        var result = service.confirm(id);

        assertEquals("PARTIAL_FAILED", result.status());
        assertEquals(1, result.succeeded());
        assertEquals(1, result.failed());
    }

    @Test void retryCreatesANewPendingRecordAndLeavesTerminalRecordClosed() {
        var service = service("retry", request -> request.to().contains("bad@example.com")
            ? SendResult.failure("mailbox unavailable") : SendResult.success("sent"));
        var plan = BatchPlanner.byTags(request("template@example.com"),
            java.util.Set.of("good@example.com", "bad@example.com"));
        String originalId = service.prepareBatch("TAGS", plan).confirmation().confirmationId();
        assertEquals("PARTIAL_FAILED", service.confirm(originalId).status());

        var retry = service.retryFailed(originalId, java.util.Set.of("bad@example.com"));

        assertNotEquals(originalId, retry.confirmation().confirmationId());
        assertEquals("PARTIAL_FAILED", service.status(originalId).orElseThrow().status());
        var retryPending = service.status(retry.confirmation().confirmationId()).orElseThrow();
        assertEquals("PENDING", retryPending.status());
        assertTrue(retryPending.snapshotJson().contains("bad@example.com"));
        assertFalse(retryPending.snapshotJson().contains("good@example.com"));
    }

    @Test void twoConcurrentConfirmationsExecuteSmtpOnlyOnce() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var service = service("concurrent", request -> {
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
                "PLAIN", null, null, null, true));
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
