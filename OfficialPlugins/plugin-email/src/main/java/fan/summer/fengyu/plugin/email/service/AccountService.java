package fan.summer.fengyu.plugin.email.service;

import fan.summer.fengyu.plugin.email.crypto.CredentialCipher;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.EmailAccount;
import fan.summer.fengyu.plugin.email.repository.AccountRepository;
import fan.summer.fengyu.plugin.email.repository.PendingSendRepository;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public final class AccountService {
    private final AccountRepository accounts;
    private final CredentialCipher cipher;
    private final PendingSendRepository pendingSends;

    public AccountService(EmailDatabase database, CredentialCipher cipher) {
        this(new AccountRepository(database), cipher, new PendingSendRepository(database));
    }

    AccountService(AccountRepository accounts, CredentialCipher cipher) {
        this(accounts, cipher, null);
    }

    private AccountService(AccountRepository accounts, CredentialCipher cipher, PendingSendRepository pendingSends) {
        this.accounts = accounts;
        this.cipher = cipher;
        this.pendingSends = pendingSends;
    }

    public long save(AccountInput input) {
        required(input.displayName(), "displayName");
        required(input.email(), "email");
        required(input.smtpHost(), "smtpHost");
        required(input.smtpSecurity(), "smtpSecurity");
        if (input.smtpPort() < 1 || input.smtpPort() > 65535) throw new IllegalArgumentException("Invalid SMTP port");
        EmailAccount existing = input.id() == null ? null : accounts.findAccount(input.id()).orElseThrow(
            () -> new IllegalArgumentException("Unknown account: " + input.id()));
        String encrypted = existing == null || !blank(input.password())
            ? encrypt(required(input.password(), "password")) : existing.encryptedPassword();
        var repositoryInput = new AccountRepository.AccountInput(input.id(), input.displayName().trim(),
            input.email().trim().toLowerCase(Locale.ROOT), input.smtpHost().trim(), input.smtpPort(),
            input.smtpSecurity().trim(), trimToNull(input.imapHost()), input.imapPort(),
            trimToNull(input.imapSecurity()), input.defaultAccount());
        return accounts.saveAccount(repositoryInput, encrypted);
    }

    public Optional<AccountView> find(long id) { return accounts.findAccount(id).map(AccountService::view); }
    public List<AccountView> list() { return accounts.listAccounts().stream().map(AccountService::view).toList(); }
    public boolean delete(long id) {
        if (pendingSends != null && pendingSends.hasOpenForAccount(id, LocalDateTime.now(ZoneOffset.UTC))) {
            throw new IllegalStateException("Account has an open send operation");
        }
        return accounts.deleteAccount(id);
    }
    public boolean setDefault(long id) { return accounts.setDefault(id); }

    /** Internal-only credential access for SMTP/IMAP services. */
    public String decryptPassword(long id) {
        EmailAccount account = accounts.findAccount(id).orElseThrow(() -> new IllegalArgumentException("Unknown account: " + id));
        try { return cipher.decrypt(account.encryptedPassword()); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("Account credentials are locked", e); }
    }

    private String encrypt(String password) {
        try { return cipher.encrypt(password); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("Could not encrypt account credentials", e); }
    }

    private static AccountView view(EmailAccount account) {
        return new AccountView(account.id(), account.displayName(), account.email(), account.smtpHost(),
            account.smtpPort(), account.smtpSecurity(), account.imapHost(), account.imapPort(),
            account.imapSecurity(), account.defaultAccount(), !blank(account.encryptedPassword()));
    }

    private static String required(String value, String field) {
        if (blank(value)) throw new IllegalArgumentException(field + " is required");
        return value;
    }
    private static String trimToNull(String value) { return blank(value) ? null : value.trim(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record AccountInput(Long id, String displayName, String email, String password,
        String smtpHost, int smtpPort, String smtpSecurity, String imapHost, Integer imapPort,
        String imapSecurity, boolean defaultAccount) {
        @Override public String toString() { return "AccountInput[id=" + id + ",email=" + email + ",password=<redacted>]"; }
    }

    public record AccountView(long id, String displayName, String email, String smtpHost, int smtpPort,
        String smtpSecurity, String imapHost, Integer imapPort, String imapSecurity,
        boolean defaultAccount, boolean passwordConfigured) { }
}
