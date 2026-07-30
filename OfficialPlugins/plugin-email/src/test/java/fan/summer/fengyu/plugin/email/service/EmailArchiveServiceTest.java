package fan.summer.fengyu.plugin.email.service;

import com.icegreen.greenmail.store.MailFolder;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import fan.summer.fengyu.plugin.email.crypto.CredentialCipher;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.ArchiveRequest;
import fan.summer.fengyu.plugin.email.model.ArchivedMessage;
import fan.summer.fengyu.sdk.PluginDatabaseConfig;
import jakarta.activation.DataHandler;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.KeyGenerator;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailArchiveServiceTest {
    @TempDir Path temp;

    private GreenMail greenMail;
    private GreenMailUser user;
    private EmailDatabase database;
    private EmailArchiveService service;
    private CredentialCipher credentialCipher;
    private long accountId;

    @BeforeEach void startImapAndAccount() throws Exception {
        greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP));
        greenMail.start();
        user = greenMail.setUser("collector@example.com", "collector@example.com", "imap-secret");

        database = new EmailDatabase(new PluginDatabaseConfig("h2", "org.h2.Driver",
            "jdbc:h2:mem:archive-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "", temp));
        credentialCipher = cipher();
        AccountService accounts = new AccountService(database, credentialCipher);
        accountId = accounts.save(new AccountService.AccountInput(null, "Collector", "collector@example.com",
            "imap-secret", "127.0.0.1", 25, "PLAIN", "127.0.0.1", greenMail.getImap().getPort(),
            "PLAIN", true));
        service = new EmailArchiveService(database, credentialCipher);
    }

    @AfterEach void stopServer() {
        if (greenMail != null) greenMail.stop();
    }

    @Test void collectsDateFilteredCustomFolderAsSafeEmlWithBoundedPreviewAndAttachmentThenDeduplicates()
            throws Exception {
        MailFolder folder = folder("Receipts/2026");
        Instant included = Instant.parse("2026-01-15T12:00:00Z");
        append(folder, message("Quarterly / report: *?<>|", "sender@example.com",
            "x".repeat(650), true, included), included);
        Instant excluded = Instant.parse("2025-12-15T12:00:00Z");
        append(folder, message("Old report", "old@example.com", "old", false, excluded), excluded);
        List<EmailArchiveService.Progress> progress = new ArrayList<>();
        ArchiveRequest request = new ArchiveRequest(accountId, "Receipts/2026",
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-31T23:59:59Z"),
            temp.resolve("archive"));

        EmailArchiveService.CollectResult first = service.collect(request, progress::add);
        EmailArchiveService.CollectResult second = service.collect(request, ignored -> { });

        assertEquals(1, first.newArchived());
        assertEquals(0, first.skippedDuplicates());
        assertEquals(0, first.failures());
        assertEquals(0, second.newArchived());
        assertEquals(1, second.skippedDuplicates());
        assertEquals(0, second.failures());
        assertFalse(progress.isEmpty());
        assertEquals(1, progress.getLast().completed());
        ArchivedMessage archived = service.search(new EmailArchiveService.SearchFilter(accountId,
            "Receipts/2026", null, null, null, null, 0, 10)).getFirst();
        assertEquals("sender@example.com", archived.fromAddress());
        assertEquals(500, archived.bodyPreview().length());
        assertTrue(archived.hasAttachment());
        Path eml = Path.of(archived.emlPath());
        assertTrue(Files.isRegularFile(eml));
        assertTrue(eml.getFileName().toString().endsWith("_" + archived.messageUid() + ".eml"));
        assertFalse(eml.getFileName().toString().matches(".*[\\\\/:*?\"<>|].*"));
        assertTrue(Files.readString(eml).contains("Quarterly / report"));
        assertEquals(1, Files.list(request.outputDirectory()).count());
    }

    @Test void malformedMessageFailureDoesNotPreventLaterMessagesFromBeingArchived() throws Exception {
        MailFolder inbox = folder("INBOX");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        append(inbox, malformedMessage(), now.minusSeconds(60));
        append(inbox, message("Still good", "good@example.com", "usable body", false, now), now);

        EmailArchiveService.CollectResult result = service.collect(new ArchiveRequest(accountId, "INBOX",
            null, null, temp.resolve("malformed-run")), ignored -> { });

        assertEquals(1, result.newArchived());
        assertEquals(1, result.failures());
        assertEquals("Still good", service.search(new EmailArchiveService.SearchFilter(accountId,
            "INBOX", null, null, null, null, 0, 10)).getFirst().subject());
    }

    @Test void sameUidInDifferentAccountsAndFoldersUsesDistinctArchivePaths() throws Exception {
        Instant now = Instant.parse("2026-02-03T04:05:06Z");
        MailFolder inbox = folder("INBOX");
        MailFolder receipts = folder("Receipts/2026");
        append(inbox, message("Shared subject", "sender@example.com", "inbox", false, now), now);
        append(receipts, message("Shared subject", "sender@example.com", "receipt", false, now), now);

        GreenMailUser otherUser = greenMail.setUser("other@example.com", "other@example.com", "other-secret");
        AccountService accounts = new AccountService(database, credentialCipher);
        long otherAccountId = accounts.save(new AccountService.AccountInput(null, "Other", "other@example.com",
            "other-secret", "127.0.0.1", 25, "PLAIN", "127.0.0.1", greenMail.getImap().getPort(),
            "PLAIN", false));
        MailFolder otherInbox = greenMail.getManagers().getImapHostManager().getInbox(otherUser);
        append(otherInbox, message("Shared subject", "sender@example.com", "other", false, now), now);
        Path output = temp.resolve("shared-archive");

        service.collect(new ArchiveRequest(accountId, "INBOX", null, null, output), ignored -> { });
        service.collect(new ArchiveRequest(accountId, "Receipts/2026", null, null, output), ignored -> { });
        service.collect(new ArchiveRequest(otherAccountId, "INBOX", null, null, output), ignored -> { });

        List<ArchivedMessage> archived = service.search(new EmailArchiveService.SearchFilter(null,
            null, null, null, null, null, 0, 10));
        assertEquals(3, archived.size());
        assertEquals(1, archived.stream().map(ArchivedMessage::messageUid).distinct().count());
        assertEquals(3, archived.stream().map(ArchivedMessage::emlPath).distinct().count());
        assertTrue(archived.stream().map(ArchivedMessage::emlPath).map(Path::of).allMatch(Files::isRegularFile));
    }

    @Test void searchAppliesEveryFilterPaginatesAndCapsLimitAtOneHundred() throws Exception {
        MailFolder inbox = folder("INBOX");
        Instant base = Instant.parse("2026-03-01T00:00:00Z");
        for (int i = 0; i < 105; i++) {
            String sender = i % 2 == 0 ? "target@example.com" : "other@example.com";
            append(inbox, message("Invoice " + String.format("%03d", i), sender,
                "preview " + i, false, base.plus(i, ChronoUnit.MINUTES)), base.plus(i, ChronoUnit.MINUTES));
        }
        service.collect(new ArchiveRequest(accountId, "INBOX", null, null, temp.resolve("pages")), ignored -> { });

        List<ArchivedMessage> capped = service.search(new EmailArchiveService.SearchFilter(accountId,
            "INBOX", null, "Invoice", base.minusSeconds(1), base.plus(2, ChronoUnit.DAYS), 0, 500));
        List<ArchivedMessage> page = service.search(new EmailArchiveService.SearchFilter(accountId,
            "INBOX", "target@example.com", "Invoice", base.minusSeconds(1),
            base.plus(2, ChronoUnit.DAYS), 10, 7));
        List<ArchivedMessage> wrongAccount = service.search(new EmailArchiveService.SearchFilter(accountId + 1,
            "INBOX", null, null, null, null, 0, 10));

        assertEquals(100, capped.size());
        assertEquals(7, page.size());
        assertTrue(page.stream().allMatch(item -> item.fromAddress().equals("target@example.com")));
        assertTrue(page.stream().allMatch(item -> item.subject().contains("Invoice")));
        assertTrue(wrongAccount.isEmpty());
        ArchivedMessage detail = service.detail(page.getFirst().id()).orElseThrow();
        assertEquals(page.getFirst(), detail);
        assertTrue(Arrays.stream(ArchivedMessage.class.getRecordComponents())
            .noneMatch(component -> component.getName().toLowerCase().contains("raweml")));
    }

    @Test void searchTreatsPercentUnderscoreAndEscapeCharacterAsLiteralText() throws Exception {
        MailFolder inbox = folder("INBOX");
        Instant now = Instant.parse("2026-03-02T00:00:00Z");
        append(inbox, message("literal %_! token", "Sender %_! token <literal@example.com>",
            "literal", false, now), now);
        append(inbox, message("literal AB! token", "Sender AB! token <decoy@example.com>",
            "decoy", false, now.plusSeconds(1)), now.plusSeconds(1));
        service.collect(new ArchiveRequest(accountId, "INBOX", null, null, temp.resolve("literal-search")),
            ignored -> { });

        List<ArchivedMessage> bySubject = service.search(new EmailArchiveService.SearchFilter(accountId,
            "INBOX", null, "%_!", null, null, 0, 10));
        List<ArchivedMessage> bySender = service.search(new EmailArchiveService.SearchFilter(accountId,
            "INBOX", "%_!", null, null, null, 0, 10));

        assertEquals(List.of("literal %_! token"), bySubject.stream().map(ArchivedMessage::subject).toList());
        assertEquals(List.of("literal@example.com"), bySender.stream()
            .map(ArchivedMessage::fromAddress).map(value -> value.substring(value.indexOf('<') + 1, value.indexOf('>')))
            .toList());
    }

    @Test void deletesFinalEmlWhenMetadataInsertFails() throws Exception {
        MailFolder inbox = folder("INBOX");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        append(inbox, message("Metadata failure", "sender@example.com", "force metadata failure", false, now), now);
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE FENGYU_PL_Email_Archive ADD CONSTRAINT archive_preview_check "
                + "CHECK (body_preview <> 'force metadata failure')");
        }
        Path output = temp.resolve("failed-metadata");

        EmailArchiveService.CollectResult result = service.collect(
            new ArchiveRequest(accountId, "INBOX", null, null, output), ignored -> { });

        assertEquals(0, result.newArchived());
        assertEquals(1, result.failures());
        try (var files = Files.walk(output)) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
        assertTrue(service.search(new EmailArchiveService.SearchFilter(accountId,
            "INBOX", null, null, null, null, 0, 10)).isEmpty());
    }

    @Test void cjkArchiveFilenameFitsUtf8ByteLimitAndRetainsUidSuffix() throws Exception {
        MailFolder inbox = folder("INBOX");
        Instant now = Instant.parse("2026-03-03T00:00:00Z");
        append(inbox, message("归档邮件主题".repeat(40), "sender@example.com", "body", false, now), now);

        EmailArchiveService.CollectResult result = service.collect(new ArchiveRequest(accountId, "INBOX",
            null, null, temp.resolve("cjk-filename")), ignored -> { });

        assertEquals(1, result.newArchived());
        ArchivedMessage archived = service.search(new EmailArchiveService.SearchFilter(accountId,
            "INBOX", null, null, null, null, 0, 10)).getFirst();
        String filename = Path.of(archived.emlPath()).getFileName().toString();
        assertTrue(filename.endsWith("_" + archived.messageUid() + ".eml"));
        assertTrue(filename.getBytes(StandardCharsets.UTF_8).length <= 254);
        String temporaryName = EmailArchiveService.temporaryArchivePath(Path.of(archived.emlPath()))
            .getFileName().toString();
        assertTrue(temporaryName.getBytes(StandardCharsets.UTF_8).length <= 254);
    }

    private MailFolder folder(String name) throws Exception {
        var manager = greenMail.getManagers().getImapHostManager();
        if ("INBOX".equalsIgnoreCase(name)) return manager.getInbox(user);
        return manager.createMailbox(user, name);
    }

    private static void append(MailFolder folder, MimeMessage message, Instant received) {
        folder.appendMessage(message, new Flags(), Date.from(received));
    }

    private static MimeMessage message(String subject, String from, String body, boolean attachment, Instant sent)
            throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse("collector@example.com"));
        message.setSubject(subject, StandardCharsets.UTF_8.name());
        message.setSentDate(Date.from(sent));
        if (attachment) {
            MimeBodyPart text = new MimeBodyPart();
            text.setText(body, StandardCharsets.UTF_8.name());
            MimeBodyPart file = new MimeBodyPart();
            file.setDataHandler(new DataHandler(new ByteArrayDataSource("attachment", "text/plain")));
            file.setFileName("receipt.txt");
            file.setDisposition(MimeBodyPart.ATTACHMENT);
            Multipart multipart = new MimeMultipart();
            multipart.addBodyPart(text);
            multipart.addBodyPart(file);
            message.setContent(multipart);
        } else {
            message.setText(body, StandardCharsets.UTF_8.name());
        }
        message.saveChanges();
        return message;
    }

    private static MimeMessage malformedMessage() throws Exception {
        String raw = "From: broken@example.com\r\n"
            + "To: collector@example.com\r\n"
            + "Subject: Broken charset\r\n"
            + "Content-Type: text/plain; charset=x-no-such-charset\r\n\r\n"
            + "This body cannot be decoded with its declared charset.\r\n";
        return new MimeMessage(Session.getInstance(new Properties()),
            new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII)));
    }

    private static CredentialCipher cipher() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return new CredentialCipher(generator.generateKey());
    }
}
