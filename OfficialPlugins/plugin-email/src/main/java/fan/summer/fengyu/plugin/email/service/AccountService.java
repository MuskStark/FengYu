package fan.summer.fengyu.plugin.email.service;

import fan.summer.fengyu.plugin.email.crypto.CredentialCipher;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.EmailAccount;
import fan.summer.fengyu.plugin.email.repository.AccountRepository;
import fan.summer.fengyu.plugin.email.repository.ArchiveRepository;
import fan.summer.fengyu.plugin.email.repository.PendingSendRepository;
import fan.summer.fengyu.plugin.email.repository.SentLogRepository;
import fan.summer.fengyu.sdk.PluginMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public final class AccountService {
    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final PluginMessages MSGS = PluginMessages.forClassLoader(PluginMessages.DEFAULT_BASE_NAME, AccountService.class);
    private final AccountRepository accounts;
    private final CredentialCipher cipher;
    private final PendingSendRepository pendingSends;
    private final ArchiveRepository archives;
    private final SentLogRepository sentLogs;

    public AccountService(EmailDatabase database, CredentialCipher cipher) {
        this(new AccountRepository(database), cipher, new PendingSendRepository(database),
            new ArchiveRepository(database), new SentLogRepository(database));
    }

    /** Internal-only: account lookup without wiring send/archive cleanup. Used by EmailArchiveService. */
    AccountService(AccountRepository accounts, CredentialCipher cipher) {
        this(accounts, cipher, null, null, null);
    }

    private AccountService(AccountRepository accounts, CredentialCipher cipher, PendingSendRepository pendingSends,
            ArchiveRepository archives, SentLogRepository sentLogs) {
        this.accounts = accounts;
        this.cipher = cipher;
        this.pendingSends = pendingSends;
        this.archives = archives;
        this.sentLogs = sentLogs;
    }

    public long save(AccountInput input) {
        required(input.displayName(), "displayName");
        required(input.email(), "email");
        required(input.smtpHost(), "smtpHost");
        required(input.smtpSecurity(), "smtpSecurity");
        if (input.smtpPort() < 1 || input.smtpPort() > 65535) throw new IllegalArgumentException(MSGS.format("em.err.accountInvalidSmtpPort"));
        EmailAccount existing = input.id() == null ? null : accounts.findAccount(input.id()).orElseThrow(
            () -> new IllegalArgumentException(MSGS.format("em.err.accountUnknown", input.id())));
        String encrypted = existing == null || !blank(input.password())
            ? encrypt(required(input.password(), "password")) : existing.encryptedPassword();
        var repositoryInput = new AccountRepository.AccountInput(input.id(), input.displayName().trim(),
            input.email().trim().toLowerCase(Locale.ROOT), input.smtpHost().trim(), input.smtpPort(),
            input.smtpSecurity().trim(), trimToNull(input.imapHost()), input.imapPort(),
            trimToNull(input.imapSecurity()), input.smtpSkipCertVerify(), input.imapSkipCertVerify(),
            input.defaultAccount());
        return accounts.saveAccount(repositoryInput, encrypted);
    }

    public Optional<AccountView> find(long id) { return accounts.findAccount(id).map(AccountService::view); }
    public List<AccountView> list() { return accounts.listAccounts().stream().map(AccountService::view).toList(); }
    public boolean delete(long id) {
        // Reclaim any task a dead worker stranded in SENDING, otherwise hasOpenForAccount would block
        // the delete forever (claim/reject/expire all require PENDING, finish requires SENDING).
        if (pendingSends != null) pendingSends.reclaimStuck(LocalDateTime.now(ZoneOffset.UTC));
        if (pendingSends != null && pendingSends.hasOpenForAccount(id, LocalDateTime.now(ZoneOffset.UTC))) {
            throw new IllegalStateException(MSGS.format("em.err.accountHasOpenSend"));
        }
        EmailAccount account = accounts.findAccount(id).orElse(null);
        if (account == null) return false;
        // There are no foreign keys in the schema (Archive/PendingSend key on account_id, SentLog on
        // account_email), so removing the account here would orphan those rows and the EML files they
        // reference. Clean them up explicitly before the account row is removed.
        if (pendingSends != null) pendingSends.deleteByAccount(id);
        if (sentLogs != null) sentLogs.deleteByAccountEmail(account.email());
        List<String> emlPaths = archives == null ? List.of() : archives.emlPathsForAccount(id);
        if (archives != null) archives.deleteByAccount(id);
        boolean deleted = accounts.deleteAccount(id);
        deleteArchiveFiles(emlPaths);
        return deleted;
    }
    public boolean setDefault(long id) { return accounts.setDefault(id); }

    /** Best-effort EML file cleanup; a missing/unreadable file must not abort the account deletion. */
    private static void deleteArchiveFiles(List<String> emlPaths) {
        for (String emlPath : emlPaths) {
            try { Files.deleteIfExists(Path.of(emlPath)); }
            catch (Exception failure) { log.warn("Could not delete archived message file {}: {}", emlPath, failure.toString()); }
        }
    }

    /** Internal-only credential access for SMTP/IMAP services. */
    public String decryptPassword(long id) {
        EmailAccount account = accounts.findAccount(id).orElseThrow(() -> new IllegalArgumentException(MSGS.format("em.err.accountUnknown", id)));
        try { return cipher.decrypt(account.encryptedPassword()); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(MSGS.format("em.err.accountCredentialsLocked"), e); }
    }

    private String encrypt(String password) {
        try { return cipher.encrypt(password); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(MSGS.format("em.err.couldNotEncrypt"), e); }
    }

    private static AccountView view(EmailAccount account) {
        return new AccountView(account.id(), account.displayName(), account.email(), account.smtpHost(),
            account.smtpPort(), account.smtpSecurity(), account.imapHost(), account.imapPort(),
            account.imapSecurity(), account.smtpSkipCertVerify(), account.imapSkipCertVerify(),
            account.defaultAccount(), !blank(account.encryptedPassword()));
    }

    private static String required(String value, String field) {
        if (blank(value)) throw new IllegalArgumentException(MSGS.format("em.err.fieldRequired", field));
        return value;
    }
    private static String trimToNull(String value) { return blank(value) ? null : value.trim(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record AccountInput(Long id, String displayName, String email, String password,
        String smtpHost, int smtpPort, String smtpSecurity, String imapHost, Integer imapPort,
        String imapSecurity, boolean smtpSkipCertVerify, boolean imapSkipCertVerify, boolean defaultAccount) {
        @Override public String toString() { return "AccountInput[id=" + id + ",email=" + email + ",password=<redacted>]"; }
    }

    public record AccountView(long id, String displayName, String email, String smtpHost, int smtpPort,
        String smtpSecurity, String imapHost, Integer imapPort, String imapSecurity,
        boolean smtpSkipCertVerify, boolean imapSkipCertVerify, boolean defaultAccount, boolean passwordConfigured) { }
}
