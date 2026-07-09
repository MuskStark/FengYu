package fan.summer.zhiflow.utils;

import fan.summer.zhiflow.database.entity.setting.email.ZhiFlowSettingEmailEntity;
import fan.summer.zhiflow.database.repository.setting.email.ZhiFlowSettingEmailRepository;
import fan.summer.zhiflow.security.SecurityContext;
import jakarta.activation.FileDataSource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * Utility bean for sending emails via SMTP using Simple Java Mail.
 *
 * <p>SMTP configuration is loaded automatically from the database
 * ({@code swiss_kit_setting_email} table, user-scoped) on each send operation. The bean supports
 * plain-text emails, HTML emails, CC/BCC recipients, and file attachments. Connection testing is
 * also provided via {@link #testConnection()}.
 *
 * <p>Converted from a pure-static MyBatis utility to a Spring {@code @Component} so it can inject
 * {@link ZhiFlowSettingEmailRepository} and {@link SecurityContext} (user-scoped reads). Callers
 * inject this bean and call its instance methods. All methods throw {@link EmailException} on
 * failure. Instances of {@link EmailMessage} are built using the {@link EmailMessage.Builder}
 * pattern.
 *
 * <p><strong>Thread safety:</strong> This class is thread-safe; multiple threads may call
 * {@code sendText}, {@code sendHtml}, and {@code sendEmail} concurrently, each reading a fresh
 * config from the repository.
 *
 * @since 1.0
 * @author ZhiFlow
 * @see EmailMessage
 * @see EmailException
 */
@Component
public class EmailUtil {

    private static final Logger log = LoggerFactory.getLogger(EmailUtil.class);

    private final ZhiFlowSettingEmailRepository emailRepo;
    private final SecurityContext securityContext;

    public EmailUtil(ZhiFlowSettingEmailRepository emailRepo, SecurityContext securityContext) {
        this.emailRepo = emailRepo;
        this.securityContext = securityContext;
    }

    /**
     * Sends a plain-text email to the specified recipient.
     *
     * @param to      the recipient email address; must not be {@code null} or blank
     * @param subject the email subject; must not be {@code null} or blank
     * @param text    the plain-text body; must not be {@code null}
     * @throws EmailException if sending fails or SMTP is not configured
     * @since 1.0
     */
    public void sendText(String to, String subject, String text) throws EmailException {
        sendEmail(EmailMessage.builder()
                .to(to)
                .subject(subject)
                .textBody(text)
                .build());
    }

    /**
     * Sends an HTML email to the specified recipient.
     *
     * @param to      the recipient email address; must not be {@code null} or blank
     * @param subject the email subject; must not be {@code null} or blank
     * @param html    the HTML body; must not be {@code null}
     * @throws EmailException if sending fails or SMTP is not configured
     * @since 1.0
     */
    public void sendHtml(String to, String subject, String html) throws EmailException {
        sendEmail(EmailMessage.builder()
                .to(to)
                .subject(subject)
                .htmlBody(html)
                .build());
    }

    /**
     * Sends an email using a fully-configured {@link EmailMessage}.
     *
     * <p>The message is validated, SMTP configuration is loaded from the database,
     * the email is built and dispatched via Simple Java Mail {@link Mailer}, and
     * the result is logged at INFO level on success or ERROR level on failure.</p>
     *
     * @param message the email message to send; must not be {@code null}
     * @throws EmailException if validation fails, SMTP is not configured, or sending fails
     * @since 1.0
     */
    public void sendEmail(EmailMessage message) throws EmailException {
        validateMessage(message);
        ZhiFlowSettingEmailEntity config = loadConfig();
        try {
            Email email = buildEmail(config, message);
            Mailer mailer = buildMailer(config);
            log.debug("Sending email | to={} subject={}", message.to, message.subject);
            mailer.sendMail(email);
            log.info("Email sent successfully | to={}", message.to);
        } catch (Exception e) {
            log.error("Failed to send email | to={} error={}", message.to, e.getMessage(), e);
            throw new EmailException("Failed to send email: " + e.getMessage(), e);
        }
    }

    /**
     * Tests the SMTP connection using the currently configured SMTP settings.
     *
     * <p>This method loads the SMTP configuration from the database, creates a
     * {@link Mailer}, and calls {@link Mailer#testConnection()}. No email is actually
     * sent. The result is logged at INFO level on success or ERROR level on failure.</p>
     *
     * @throws EmailException if the connection test fails or SMTP is not configured
     * @since 1.0
     */
    public void testConnection() throws EmailException {
        ZhiFlowSettingEmailEntity config = loadConfig();
        log.debug("Testing SMTP connection | host={}:{}", config.getSmtpAddress(), config.getSmtpPort());
        try {
            Mailer mailer = buildMailer(config);
            mailer.testConnection();
            log.info("SMTP connection test passed | host={}", config.getSmtpAddress());
        } catch (Exception e) {
            log.error("SMTP connection test failed | error={}", e.getMessage(), e);
            throw new EmailException("Connection test failed: " + e.getMessage(), e);
        }
    }

    private Email buildEmail(ZhiFlowSettingEmailEntity config, EmailMessage message) {
        String from = (config.getFromAddress() != null && !config.getFromAddress().isBlank())
                ? config.getFromAddress()
                : config.getEmail();

        var builder = EmailBuilder.startingBlank()
                .from(from)
                .withSubject(message.subject);

        for (String to : message.to) {
            builder.to(to);
        }
        if (message.cc != null) {
            for (String cc : message.cc) {
                builder.cc(cc);
            }
        }
        if (message.bcc != null) {
            for (String bcc : message.bcc) {
                builder.bcc(bcc);
            }
        }

        if (message.textBody != null) {
            builder.withPlainText(message.textBody);
        }
        if (message.htmlBody != null) {
            builder.withHTMLText(message.htmlBody);
        }

        if (message.attachments != null) {
            for (File file : message.attachments) {
                if (!file.exists() || !file.isFile()) {
                    log.warn("Attachment not found, skipping: {}", file.getAbsolutePath());
                    continue;
                }
                builder.withAttachment(file.getName(), new FileDataSource(file));
                log.debug("Adding attachment: {} ({} bytes)", file.getName(), file.length());
            }
        }

        return builder.buildEmail();
    }

    private Mailer buildMailer(ZhiFlowSettingEmailEntity config) {
        TransportStrategy strategy = resolveTransportStrategy(config);
        log.debug("SMTP strategy: {}", strategy);
        return MailerBuilder
                .withSMTPServer(
                        config.getSmtpAddress(),
                        config.getSmtpPort(),
                        config.getEmail(),
                        config.getPassword()
                )
                .withTransportStrategy(strategy)
                .withSessionTimeout(10_000)
                .buildMailer();
    }

    private TransportStrategy resolveTransportStrategy(ZhiFlowSettingEmailEntity config) {
        if (Boolean.TRUE.equals(config.getNeedSSL())) {
            return TransportStrategy.SMTPS;
        }
        if (Boolean.TRUE.equals(config.getNeedTLS())) {
            return TransportStrategy.SMTP_TLS;
        }
        return TransportStrategy.SMTP;
    }

    private ZhiFlowSettingEmailEntity loadConfig() throws EmailException {
        log.debug("Loading SMTP config from database");
        Long uid = securityContext.currentUserId();
        ZhiFlowSettingEmailEntity config = emailRepo.findFirstByUserIdOrderByIdDesc(uid)
                .orElse(null);
        if (config == null) {
            throw new EmailException(
                    "No email configuration found. Please configure SMTP settings first.", null);
        }
        log.debug("Loaded SMTP config | host={}:{} tls={} ssl={}",
                config.getSmtpAddress(), config.getSmtpPort(),
                config.getNeedTLS(), config.getNeedSSL());
        return config;
    }

    private void validateMessage(EmailMessage message) {
        if (message == null)
            throw new IllegalArgumentException("EmailMessage must not be null");
        if (message.to == null || message.to.isEmpty())
            throw new IllegalArgumentException("At least one recipient (to) is required");
        if (message.subject == null || message.subject.isBlank())
            throw new IllegalArgumentException("Subject must not be empty");
        if (message.textBody == null && message.htmlBody == null)
            throw new IllegalArgumentException("Either textBody or htmlBody must be provided");

        for (String to : message.to) {
            if (containsCRLF(to)) {
                throw new IllegalArgumentException("Invalid recipient address: " + to);
            }
        }
        if (containsCRLF(message.subject)) {
            throw new IllegalArgumentException("Invalid subject: contains illegal characters");
        }
    }

    private static boolean containsCRLF(String value) {
        return value != null && (value.contains("\r") || value.contains("\n"));
    }

    /**
     * Represents a single email message with recipients, subject, body, and optional attachments.
     *
     * <p>This class is immutable; instances are constructed via the nested
     * {@link Builder}. All fields are mandatory unless noted otherwise.</p>
     *
     * @since 1.0
     * @see Builder
     */
    public static class EmailMessage {

        private final List<String> to;
        private final List<String> cc;
        private final List<String> bcc;
        private final String subject;
        private final String textBody;
        private final String htmlBody;
        private final List<File> attachments;

        private EmailMessage(Builder b) {
            this.to = b.to;
            this.cc = b.cc;
            this.bcc = b.bcc;
            this.subject = b.subject;
            this.textBody = b.textBody;
            this.htmlBody = b.htmlBody;
            this.attachments = b.attachments;
        }

        /**
         * Creates a new empty builder for an {@link EmailMessage}.
         *
         * @return a new {@code Builder} instance
         * @since 1.0
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Builder for {@link EmailMessage} instances using the fluent setter pattern.
         *
         * <p>All setter methods return {@code this} to allow chaining. At least one
         * recipient ({@code to}) and either a {@code textBody} or {@code htmlBody}
         * must be provided before {@link #build()} is called, otherwise
         * {@link EmailException} will be thrown at send time.</p>
         *
         * @since 1.0
         */
        public static class Builder {
            private List<String> to;
            private List<String> cc;
            private List<String> bcc;
            private String subject;
            private String textBody;
            private String htmlBody;
            private List<File> attachments;

            /**
             * Sets the primary recipient(s) for the email.
             *
             * @param to one or more recipient email addresses; must not be {@code null}
             * @return this builder instance
             * @since 1.0
             */
            public Builder to(String... to) {
                this.to = java.util.Arrays.asList(to);
                return this;
            }

            /**
             * Sets the primary recipient(s) for the email from a list.
             *
             * @param to a list of recipient email addresses; must not be {@code null}
             * @return this builder instance
             * @since 1.0
             */
            public Builder to(List<String> to) {
                this.to = to;
                return this;
            }

            /**
             * Sets the CC recipient(s) for the email.
             *
             * @param cc one or more CC recipient email addresses; may be {@code null}
             * @return this builder instance
             * @since 1.0
             */
            public Builder cc(String... cc) {
                this.cc = java.util.Arrays.asList(cc);
                return this;
            }

            /**
             * Sets the CC recipient(s) for the email from a list.
             *
             * @param cc a list of CC recipient email addresses; may be {@code null}
             * @return this builder instance
             * @since 1.0
             */
            public Builder cc(List<String> cc) {
                this.cc = cc;
                return this;
            }

            /**
             * Sets the BCC recipient(s) for the email.
             *
             * @param bcc one or more BCC recipient email addresses; may be {@code null}
             * @return this builder instance
             * @since 1.0
             */
            public Builder bcc(String... bcc) {
                this.bcc = java.util.Arrays.asList(bcc);
                return this;
            }

            /**
             * Sets the BCC recipient(s) for the email from a list.
             *
             * @param bcc a list of BCC recipient email addresses; may be {@code null}
             * @return this builder instance
             * @since 1.0
             */
            public Builder bcc(List<String> bcc) {
                this.bcc = bcc;
                return this;
            }

            /**
             * Sets the email subject.
             *
             * @param subject the subject line; must not be {@code null} or blank
             * @return this builder instance
             * @since 1.0
             */
            public Builder subject(String subject) {
                this.subject = subject;
                return this;
            }

            /**
             * Sets the plain-text body of the email.
             *
             * @param text the plain-text body; may be {@code null} if {@code htmlBody} is set
             * @return this builder instance
             * @since 1.0
             */
            public Builder textBody(String text) {
                this.textBody = text;
                return this;
            }

            /**
             * Sets the HTML body of the email.
             *
             * @param html the HTML body; may be {@code null} if {@code textBody} is set
             * @return this builder instance
             * @since 1.0
             */
            public Builder htmlBody(String html) {
                this.htmlBody = html;
                return this;
            }

            /**
             * Sets the file attachments for the email.
             *
             * @param files one or more {@link File} attachments; files that do not exist or
             *              are not regular files are logged and skipped at send time
             * @return this builder instance
             * @since 1.0
             */
            public Builder attachments(File... files) {
                this.attachments = java.util.Arrays.asList(files);
                return this;
            }

            /**
             * Sets the file attachments for the email from a list.
             *
             * @param files a list of {@link File} attachments; files that do not exist or
             *              are not regular files are logged and skipped at send time
             * @return this builder instance
             * @since 1.0
             */
            public Builder attachments(List<File> files) {
                this.attachments = files;
                return this;
            }

            /**
             * Builds and returns an immutable {@link EmailMessage} from the current builder state.
             *
             * <p>Validation of required fields is deferred to send time rather than build time.</p>
             *
             * @return a new {@link EmailMessage}; never {@code null}
             * @since 1.0
             */
            public EmailMessage build() {
                return new EmailMessage(this);
            }
        }
    }

    /**
     * Exception thrown when an email operation (send or connection test) fails.
     *
     * <p>This exception wraps the underlying cause where applicable, including SMTP
     * authentication failures, network errors, and missing configuration.</p>
     *
     * @since 1.0
     */
    public static class EmailException extends Exception {
        public EmailException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
