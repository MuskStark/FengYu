package fan.summer.fengyu.plugin.email.model;

import java.time.LocalDateTime;

/** Internal immutable account state. Credentials must never be returned over RPC. */
public record EmailAccount(long id, String displayName, String email, String encryptedPassword,
        String smtpHost, int smtpPort, String smtpSecurity, String imapHost, Integer imapPort,
        String imapSecurity, boolean smtpSkipCertVerify, boolean imapSkipCertVerify,
        boolean defaultAccount, LocalDateTime createdAt) {
}
