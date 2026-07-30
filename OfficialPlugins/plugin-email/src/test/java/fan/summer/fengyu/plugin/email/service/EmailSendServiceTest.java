package fan.summer.fengyu.plugin.email.service;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import fan.summer.fengyu.plugin.email.crypto.CredentialCipher;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.EmailAccount;
import fan.summer.fengyu.plugin.email.model.EmailMessageRequest;
import fan.summer.fengyu.plugin.email.model.SendResult;
import fan.summer.fengyu.plugin.email.repository.SentLogRepository;
import fan.summer.fengyu.sdk.PluginDatabaseConfig;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.mailer.Mailer;

import javax.crypto.KeyGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailSendServiceTest {
    @TempDir Path temp;

    private GreenMail greenMail;
    private EmailDatabase database;
    private EmailSendService service;
    private long accountId;

    @BeforeEach void startServerAndAccount() throws Exception {
        greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        greenMail.start();
        greenMail.setUser("sender@example.com", "sender@example.com", "smtp-secret");
        greenMail.setUser("to@example.com", "to@example.com", "unused");
        greenMail.setUser("cc@example.com", "cc@example.com", "unused");
        greenMail.setUser("bcc@example.com", "bcc@example.com", "unused");
        database = database("smtp-" + System.nanoTime());
        CredentialCipher cipher = cipher();
        AccountService accounts = new AccountService(database, cipher);
        accountId = accounts.save(new AccountService.AccountInput(null, "Sender", "sender@example.com",
            "smtp-secret", "127.0.0.1", greenMail.getSmtp().getPort(), "PLAIN", null, null, null, true));
        service = new EmailSendService(database, cipher);
    }

    @AfterEach void stopServer() {
        if (greenMail != null) greenMail.stop();
    }

    @Test void testsConnectionAndSendsToCcBccAlternativeBodiesAndTwoAttachments() throws Exception {
        Path first = Files.writeString(temp.resolve("first.txt"), "first attachment");
        Path second = Files.write(temp.resolve("second.bin"), new byte[]{1, 2, 3, 4});

        SendResult connection = service.testSmtp(accountId);
        SendResult result = service.sendSingle(new EmailMessageRequest(accountId,
            List.of("to@example.com"), List.of("cc@example.com"), List.of("bcc@example.com"),
            "Quarterly update", "plain version", "<p>html version</p>", List.of(first, second)), "confirmation-1");

        assertTrue(connection.success());
        assertTrue(result.success());
        assertNotNull(result.messageId());
        assertTrue(greenMail.waitForIncomingEmail(5_000, 1));
        MimeMessage message = greenMail.getReceivedMessages()[0];
        assertAddresses(message.getRecipients(Message.RecipientType.TO), "to@example.com");
        assertAddresses(message.getRecipients(Message.RecipientType.CC), "cc@example.com");
        var bccUser = greenMail.getUserManager().getUserByEmail("bcc@example.com");
        assertEquals(1, greenMail.getManagers().getImapHostManager().getInbox(bccUser).getMessageCount());
        List<BodyPart> parts = leafParts(message);
        assertTrue(parts.stream().anyMatch(part -> contentType(part).startsWith("text/plain")
            && content(part).contains("plain version")));
        assertTrue(parts.stream().anyMatch(part -> contentType(part).startsWith("text/html")
            && content(part).contains("html version")));
        assertEquals(List.of("first.txt", "second.bin"), parts.stream()
            .filter(part -> Part.ATTACHMENT.equalsIgnoreCase(disposition(part)))
            .map(EmailSendServiceTest::fileName).sorted().toList());
        assertEquals(1, sentLogCount());
        assertEquals("SUCCESS", sentLogStatus());
        assertEquals("confirmation-1", new SentLogRepository(database)
            .search("confirmation-1", null, null, 0, 20).getFirst().confirmationId());
    }

    @Test void rejectsCrLfInSubjectAndEveryRecipientClassBeforeSending() {
        assertInvalid(request(List.of("to@example.com"), List.of(), List.of(), "hello\r\nBcc: victim@example.com"));
        assertInvalid(request(List.of("to@example.com\nCc: victim@example.com"), List.of(), List.of(), "safe"));
        assertInvalid(request(List.of("to@example.com"), List.of("cc@example.com\rX: y"), List.of(), "safe"));
        assertInvalid(request(List.of("to@example.com"), List.of(), List.of("bcc@example.com\nX: y"), "safe"));
        assertEquals(0, sentLogCount());
    }

    @Test void rejectsNonexistentAttachmentBeforeSending() {
        EmailMessageRequest request = new EmailMessageRequest(accountId, List.of("to@example.com"), List.of(),
            List.of(), "subject", "body", null, List.of(temp.resolve("missing.txt")));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> service.sendSingle(request));

        assertTrue(error.getMessage().contains("missing.txt"));
        assertEquals(0, sentLogCount());
    }

    @Test void configuresAllSmtpSessionTimeoutsToTenSeconds() throws Exception {
        EmailAccount account = new fan.summer.fengyu.plugin.email.repository.AccountRepository(database)
            .findAccount(accountId).orElseThrow();

        try (Mailer mailer = service.createMailer(account, "smtp-secret")) {
            String prefix = account.smtpSecurity().equals("SSL") ? "mail.smtps." : "mail.smtp.";
            assertEquals("10000", mailer.getSession().getProperty(prefix + "connectiontimeout"));
            assertEquals("10000", mailer.getSession().getProperty(prefix + "timeout"));
            assertEquals("10000", mailer.getSession().getProperty(prefix + "writetimeout"));
        }
    }

    @Test void recordsFailureForAnActualSendAttemptWithoutLeakingPassword() throws Exception {
        greenMail.stop();

        SendResult result = service.sendSingle(request(List.of("to@example.com"), List.of(), List.of(), "safe"));

        assertFalse(result.success());
        assertFalse(result.toString().contains("smtp-secret"));
        assertEquals(1, sentLogCount());
        assertEquals("FAILED", sentLogStatus());
    }

    private EmailMessageRequest request(List<String> to, List<String> cc, List<String> bcc, String subject) {
        return new EmailMessageRequest(accountId, to, cc, bcc, subject, "body", null, List.of());
    }

    private void assertInvalid(EmailMessageRequest request) {
        assertThrows(IllegalArgumentException.class, () -> service.sendSingle(request));
    }

    private int sentLogCount() {
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM FENGYU_PL_Email_Sent_Log");
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String sentLogStatus() {
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("SELECT status FROM FENGYU_PL_Email_Sent_Log");
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getString(1);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private EmailDatabase database(String name) {
        return new EmailDatabase(new PluginDatabaseConfig("h2", "org.h2.Driver",
            "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "", temp));
    }

    private static CredentialCipher cipher() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return new CredentialCipher(generator.generateKey());
    }

    private static void assertAddresses(Address[] actual, String... expected) {
        assertEquals(List.of(expected), Arrays.stream(actual).map(Address::toString).toList());
    }

    private static List<BodyPart> leafParts(Part part) throws Exception {
        List<BodyPart> result = new ArrayList<>();
        collectLeafParts(part, result);
        return result;
    }

    private static void collectLeafParts(Part part, List<BodyPart> result) throws Exception {
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) collectLeafParts(multipart.getBodyPart(i), result);
        } else if (part instanceof BodyPart bodyPart) {
            result.add(bodyPart);
        }
    }

    private static String contentType(BodyPart part) {
        try { return part.getContentType().toLowerCase(); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private static String content(BodyPart part) {
        try { return String.valueOf(part.getContent()); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private static String disposition(BodyPart part) {
        try { return part.getDisposition(); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private static String fileName(BodyPart part) {
        try { return part.getFileName(); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
