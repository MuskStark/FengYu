package fan.summer.fengyu.plugin.email.rpc;

import fan.summer.fengyu.plugin.email.service.AccountService;

import java.util.List;
import java.util.Optional;

/** RPC boundary: passwords are accepted on writes and absent from every response type. */
public final class AccountRpc {
    private final AccountService accounts;
    public AccountRpc(AccountService accounts) { this.accounts = accounts; }

    public List<AccountService.AccountView> list() { return accounts.list(); }
    public Optional<AccountService.AccountView> find(long id) { return accounts.find(id); }
    public AccountService.AccountView save(AccountRequest request) {
        long id = accounts.save(request.toInput());
        return accounts.find(id).orElseThrow();
    }
    public boolean delete(long id) { return accounts.delete(id); }
    public boolean setDefault(long id) { return accounts.setDefault(id); }

    public record AccountRequest(Long id, String displayName, String email, String password,
        String smtpHost, int smtpPort, String smtpSecurity, String imapHost, Integer imapPort,
        String imapSecurity, boolean smtpSkipCertVerify, boolean imapSkipCertVerify, boolean defaultAccount) {
        AccountService.AccountInput toInput() {
            return new AccountService.AccountInput(id, displayName, email, password, smtpHost, smtpPort,
                smtpSecurity, imapHost, imapPort, imapSecurity, smtpSkipCertVerify, imapSkipCertVerify, defaultAccount);
        }
        @Override public String toString() { return "AccountRequest[id=" + id + ",email=" + email + ",password=<redacted>]"; }
    }
    public record ConnectionTestRequest(Long accountId, AccountRequest unsavedAccount) { }
}
