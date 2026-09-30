package fan.summer.fengyu.plugin.email.service;

import fan.summer.fengyu.plugin.email.crypto.CredentialCipher;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.repository.ArchiveRepository;
import fan.summer.fengyu.plugin.email.repository.PendingSendRepository;
import fan.summer.fengyu.plugin.email.repository.SentLogRepository;
import fan.summer.fengyu.sdk.PluginDatabaseConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.KeyGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountServiceTest {
    @TempDir Path temp;

    @Test void managesTwoAccountsWithOneDefaultAndWriteOnlyEncryptedPasswords() throws Exception {
        EmailDatabase database = database("accounts");
        CredentialCipher cipher = cipher();
        AccountService service = new AccountService(database, cipher);

        long first = service.save(new AccountService.AccountInput(null, "Primary", "first@example.com",
            "first-secret", "smtp.example.com", 465, "SSL", "imap.example.com", 993, "SSL", false, false, true));
        long second = service.save(new AccountService.AccountInput(null, "Secondary", "second@example.com",
            "second-secret", "smtp.example.com", 587, "STARTTLS", null, null, null, false, false, true));

        var accounts = service.list();
        assertEquals(2, accounts.size());
        assertEquals(1, accounts.stream().filter(AccountService.AccountView::defaultAccount).count());
        assertTrue(accounts.stream().allMatch(AccountService.AccountView::passwordConfigured));
        assertTrue(service.find(second).orElseThrow().defaultAccount());
        assertFalse(service.find(first).orElseThrow().defaultAccount());
        assertFalse(accounts.toString().contains("first-secret"));
        assertFalse(accounts.toString().contains("second-secret"));

        String encryptedBefore = encryptedPassword(database, first);
        assertNotEquals("first-secret", encryptedBefore);
        assertEquals("first-secret", cipher.decrypt(encryptedBefore));

        service.save(new AccountService.AccountInput(first, "Primary edited", "first@example.com",
            "  ", "smtp2.example.com", 465, "SSL", "imap.example.com", 993, "SSL", false, false, false));
        assertEquals(encryptedBefore, encryptedPassword(database, first));
        assertEquals("Primary edited", service.find(first).orElseThrow().displayName());
        assertEquals(1, service.list().stream().filter(AccountService.AccountView::defaultAccount).count());

        service.save(new AccountService.AccountInput(second, "Secondary", "second@example.com",
            "", "smtp.example.com", 587, "STARTTLS", null, null, null, false, false, false));
        assertEquals(1, service.list().stream().filter(AccountService.AccountView::defaultAccount).count());
    }

    @Test void deletesAccountWithoutExposingItsPassword() throws Exception {
        EmailDatabase database = database("delete-account");
        AccountService service = new AccountService(database, cipher());
        long id = service.save(new AccountService.AccountInput(null, "Temporary", "temp@example.com",
            "secret", "smtp.example.com", 465, "SSL", null, null, null, false, false, true));

        assertTrue(service.delete(id));
        assertTrue(service.find(id).isEmpty());
    }

    @Test void refusesToDeleteAccountWithOpenPendingSend() throws Exception {
        EmailDatabase database = database("delete-open-send");
        AccountService service = new AccountService(database, cipher());
        long id = service.save(new AccountService.AccountInput(null, "Busy", "busy@example.com",
            "secret", "smtp.example.com", 465, "SSL", null, null, null, false, false, true));
        new PendingSendRepository(database).create(
            "confirm-open", id, "SINGLE", "{}", LocalDateTime.now().plusMinutes(30));

        assertThrows(IllegalStateException.class, () -> service.delete(id));
        assertTrue(service.find(id).isPresent());
    }

    @Test void deleteRemovesArchiveRowsAndEmlFilesSentLogAndPendingSend() throws Exception {
        EmailDatabase database = database("delete-cleanup");
        AccountService service = new AccountService(database, cipher());
        long id = service.save(new AccountService.AccountInput(null, "Archived", "archived@example.com",
            "secret", "smtp.example.com", 465, "SSL", null, null, null, false, false, true));

        // An archived EML on disk + its Archive row, a SentLog row, and a closed PendingSend row.
        Path eml = Files.writeString(temp.resolve("archived-cleanup.eml"), "raw message bytes");
        new ArchiveRepository(database).insert(new ArchiveRepository.ArchiveEntry(id, "archived@example.com",
            "INBOX", "uid-1", "Subject", "from@example.com", "[]", Instant.now(), Instant.now(),
            false, "preview", eml.toString()));
        new SentLogRepository(database).insert(new SentLogRepository.SentLogEntry("confirm-sent",
            "archived@example.com", "[]", "Subject", null, "SUCCESS", null));
        // A closed (rejected) send: still tied to the account but no longer blocks deletion.
        var pending = new PendingSendRepository(database);
        pending.create("confirm-done", id, "SINGLE", "{}", LocalDateTime.now().plusMinutes(30));
        assertTrue(pending.reject("confirm-done"));

        assertTrue(Files.exists(eml));
        assertEquals(1, countRowsByLong(database, "FENGYU_PL_Email_Archive", "account_id", id));
        assertEquals(1, countRowsByEmail(database, "FENGYU_PL_Email_Sent_Log", "account_email",
            "archived@example.com"));

        assertTrue(service.delete(id));

        assertTrue(service.find(id).isEmpty());
        assertFalse(Files.exists(eml), "EML file should be deleted with the account");
        assertEquals(0, countRowsByLong(database, "FENGYU_PL_Email_Archive", "account_id", id),
            "Archive rows should be deleted with the account");
        assertEquals(0, countRowsByEmail(database, "FENGYU_PL_Email_Sent_Log", "account_email",
            "archived@example.com"), "SentLog rows should be deleted with the account");
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

    private static String encryptedPassword(EmailDatabase database, long id) throws Exception {
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                 "SELECT encrypted_password FROM FENGYU_PL_Email_Account WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static int countRowsByLong(EmailDatabase database, String table, String column, long value)
            throws Exception {
        return countRows(database, "SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", value);
    }

    private static int countRowsByEmail(EmailDatabase database, String table, String column, String email)
            throws Exception {
        return countRows(database, "SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", email);
    }

    private static int countRows(EmailDatabase database, String sql, Object value) throws Exception {
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }
}
