package fan.summer.fengyu.plugin.email.service;

import com.google.gson.Gson;
import fan.summer.fengyu.plugin.email.crypto.CredentialCipher;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.EmailAccount;
import fan.summer.fengyu.plugin.email.model.EmailMessageRequest;
import fan.summer.fengyu.plugin.email.model.SendResult;
import fan.summer.fengyu.plugin.email.repository.AccountRepository;
import fan.summer.fengyu.plugin.email.repository.SentLogRepository;
import jakarta.activation.FileDataSource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EmailSendService {
    static final int SESSION_TIMEOUT_MILLIS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(EmailSendService.class);

    private final AccountRepository accounts;
    private final AccountService accountService;
    private final SentLogRepository sentLogs;
    private final Gson gson = new Gson();

    public EmailSendService(EmailDatabase database, CredentialCipher cipher) {
        this.accounts = new AccountRepository(database);
        this.accountService = new AccountService(accounts, cipher);
        this.sentLogs = new SentLogRepository(database);
    }

    public SendResult testSmtp(long accountId) {
        EmailAccount account = account(accountId);
        String password = accountService.decryptPassword(accountId);
        try (Mailer mailer = createMailer(account, password)) {
            mailer.testConnection();
            log.info("SMTP test succeeded for account {} ({})", accountId, account.email());
            return SendResult.success(null);
        } catch (Exception e) {
            log.warn("SMTP test failed for account {} ({}): {}", accountId, account.email(), safeError(e, password));
            return SendResult.failure(safeError(e, password));
        }
    }

    public SendResult sendSingle(EmailMessageRequest request) {
        return sendSingle(request, null);
    }

    public SendResult sendSingle(EmailMessageRequest request, String confirmationId) {
        validate(request);
        EmailAccount account = account(request.accountId());
        String password = accountService.decryptPassword(account.id());
        Email email = buildEmail(account, request);
        boolean attempted = false;
        try (Mailer mailer = createMailer(account, password)) {
            attempted = true;
            mailer.sendMail(email).join();
            SendResult result = SendResult.success(email.getId());
            insertLog(account, request, confirmationId, "SUCCESS", null);
            log.info("sent mail id={} from {} to {} recipients (account={})",
                confirmationId, account.email(), request.to().size() + request.cc().size() + request.bcc().size(), account.id());
            return result;
        } catch (Exception e) {
            String error = safeError(e, password);
            if (attempted) insertLog(account, request, confirmationId, "FAILED", error);
            log.warn("send failed id={} account={}: {}", confirmationId, account.id(), error, e);
            return SendResult.failure(error);
        }
    }

    Mailer createMailer(EmailAccount account, String password) {
        var builder = MailerBuilder.withSMTPServer(account.smtpHost(), account.smtpPort(), account.email(), password)
            .withTransportStrategy(transportStrategy(account.smtpSecurity()))
            .withSessionTimeout(SESSION_TIMEOUT_MILLIS);
        // Per-account opt-in: accept self-signed / private-CA certificates. trustingAllHosts(true)
        // sets mail.smtp.ssl.trust=* (Angus Mail then skips the certificate-chain check entirely);
        // verifyingServerIdentity(false) skips the RFC 6125 hostname-vs-cert check. Both are needed
        // for intranet SMTP servers whose certificate CN/SAN does not match the configured host.
        if (account.smtpSkipCertVerify()) builder = builder.trustingAllHosts(true).verifyingServerIdentity(false);
        return builder.buildMailer();
    }

    private Email buildEmail(EmailAccount account, EmailMessageRequest request) {
        EmailPopulatingBuilder builder = EmailBuilder.startingBlank()
            .from(account.displayName(), account.email())
            .toMultiple(request.to())
            .ccAddresses(request.cc())
            .bccAddresses(request.bcc())
            .withSubject(request.subject());
        if (request.plainText() != null) builder.withPlainText(request.plainText());
        if (request.htmlText() != null) builder.withHTMLText(request.htmlText());
        for (Path attachment : request.attachments()) {
            builder.withAttachment(attachment.getFileName().toString(), new FileDataSource(attachment.toFile()));
        }
        return builder.buildEmail();
    }

    private void insertLog(EmailAccount account, EmailMessageRequest request, String confirmationId,
            String status, String error) {
        Map<String, List<String>> recipients = new LinkedHashMap<>();
        recipients.put("to", request.to());
        recipients.put("cc", request.cc());
        recipients.put("bcc", request.bcc());
        List<String> attachments = request.attachments().stream()
            .map(path -> path.getFileName().toString()).toList();
        sentLogs.insert(new SentLogRepository.SentLogEntry(confirmationId, account.email(), gson.toJson(recipients),
            request.subject(), gson.toJson(attachments), status, error));
    }

    private EmailAccount account(long id) {
        return accounts.findAccount(id).orElseThrow(() -> new IllegalArgumentException("Unknown account: " + id));
    }

    private static void validate(EmailMessageRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        headerValue(request.subject(), "subject", true);
        validateRecipients(request.to(), "to");
        validateRecipients(request.cc(), "cc");
        validateRecipients(request.bcc(), "bcc");
        if (request.to().isEmpty() && request.cc().isEmpty() && request.bcc().isEmpty()) {
            throw new IllegalArgumentException("At least one recipient is required");
        }
        if (request.plainText() == null && request.htmlText() == null) {
            throw new IllegalArgumentException("A message body is required");
        }
        for (Path attachment : request.attachments()) {
            if (attachment == null || !Files.isRegularFile(attachment)) {
                throw new IllegalArgumentException("Attachment does not exist: " + attachment);
            }
        }
    }

    private static void validateRecipients(List<String> recipients, String field) {
        for (String recipient : recipients) headerValue(recipient, field, false);
    }

    private static void headerValue(String value, String field, boolean nullable) {
        if ((!nullable && (value == null || value.isBlank()))) {
            throw new IllegalArgumentException(field + " contains an empty value");
        }
        if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException(field + " contains a line break");
        }
    }

    private static TransportStrategy transportStrategy(String configured) {
        return switch (configured.trim().toUpperCase(Locale.ROOT)) {
            case "PLAIN", "SMTP", "NONE" -> TransportStrategy.SMTP;
            case "SSL", "SMTPS" -> TransportStrategy.SMTPS;
            case "TLS", "STARTTLS" -> TransportStrategy.SMTP_TLS;
            default -> throw new IllegalArgumentException("Unsupported SMTP security: " + configured);
        };
    }

    private static String safeError(Exception error, String password) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        if (message == null || message.isBlank()) message = cause.getClass().getSimpleName();
        return password == null || password.isEmpty() ? message : message.replace(password, "<redacted>");
    }
}
