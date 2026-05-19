package fan.summer.utils;

import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.setting.email.SwissKitSettingEmailEntity;
import fan.summer.database.mapper.setting.email.SwissKitSettingEmailMapper;
import jakarta.activation.FileDataSource;
import org.apache.ibatis.session.SqlSession;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

/**
 * Utility class for sending emails via SMTP using Simple Java Mail.
 * SMTP configuration is loaded automatically from the database
 * ({@code swiss_kit_setting_email} table).
 *
 * <p>Usage examples:</p>
 * <pre>
 *   // Plain text
 *   EmailUtil.sendText("to@example.com", "Subject", "Hello!");
 *
 *   // HTML with attachments
 *   EmailUtil.sendEmail(
 *       EmailMessage.builder()
 *           .to("a@example.com", "b@example.com")
 *           .cc("c@example.com")
 *           .subject("Monthly Report")
 *           .htmlBody("&lt;h1&gt;Report&lt;/h1&gt;")
 *           .attachments(new File("report.pdf"), new File("data.xlsx"))
 *           .build()
 *   );
 *
 *   // Test SMTP connection (bind to "Send Test Email" button)
 *   EmailUtil.testConnection();
 * </pre>
 *
 * <p>Maven dependency:</p>
 * <pre>
 *   &lt;dependency&gt;
 *       &lt;groupId&gt;org.simplejavamail&lt;/groupId&gt;
 *       &lt;artifactId&gt;simple-java-mail&lt;/artifactId&gt;
 *       &lt;version&gt;8.12.2&lt;/version&gt;
 *   &lt;/dependency&gt;
 * </pre>
 *
 * @author phoebej
 */
public class EmailUtil {

    private static final Logger log = LoggerFactory.getLogger(EmailUtil.class);

    // Utility class — no instantiation
    private EmailUtil() {
    }

    // ─────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────

    /**
     * Sends a plain text email to a single recipient.
     *
     * @param to      recipient email address
     * @param subject email subject
     * @param text    plain text body
     * @throws EmailException if config is missing or sending fails
     */
    public static void sendText(String to, String subject, String text) throws EmailException {
        sendEmail(EmailMessage.builder()
                .to(to)
                .subject(subject)
                .textBody(text)
                .build());
    }

    /**
     * Sends an HTML email to a single recipient.
     *
     * @param to      recipient email address
     * @param subject email subject
     * @param html    HTML body content
     * @throws EmailException if config is missing or sending fails
     */
    public static void sendHtml(String to, String subject, String html) throws EmailException {
        sendEmail(EmailMessage.builder()
                .to(to)
                .subject(subject)
                .htmlBody(html)
                .build());
    }

    /**
     * Sends a full email with optional CC, BCC, HTML body, and file attachments.
     *
     * @param message the email message descriptor
     * @throws EmailException if config is missing or sending fails
     */
    public static void sendEmail(EmailMessage message) throws EmailException {
        validateMessage(message);
        SwissKitSettingEmailEntity config = loadConfig();
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
     * Tests the SMTP connection using the database config, without sending any email.
     * Bind this to the "Send Test Email" button in {@code SettingKitPage}.
     *
     * @throws EmailException if config is missing or the connection fails
     */
    public static void testConnection() throws EmailException {
        SwissKitSettingEmailEntity config = loadConfig();
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

    // ─────────────────────────────────────────────────────────
    // Build Email & Mailer
    // ─────────────────────────────────────────────────────────

    private static Email buildEmail(SwissKitSettingEmailEntity config, EmailMessage message) {
        // from: prefer fromAddress field, fall back to login email
        String from = (config.getFromAddress() != null && !config.getFromAddress().isBlank())
                ? config.getFromAddress()
                : config.getEmail();

        var builder = EmailBuilder.startingBlank()
                .from(from)
                .withSubject(message.subject);

        // Recipients
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

        // Body — support plain text, HTML, or both simultaneously
        if (message.textBody != null) {
            builder.withPlainText(message.textBody);
        }
        if (message.htmlBody != null) {
            builder.withHTMLText(message.htmlBody);
        }

        // Attachments
        if (message.attachments != null) {
            for (File file : message.attachments) {
                if (!file.exists() || !file.isFile()) {
                    log.warn("Attachment not found, skipping: {}", file.getAbsolutePath());
                    continue;
                }
                // Simple Java Mail handles filename encoding internally (RFC 2047)
                builder.withAttachment(file.getName(), new FileDataSource(file));
                log.debug("Adding attachment: {} ({} bytes)", file.getName(), file.length());
            }
        }

        return builder.buildEmail();
    }

    private static Mailer buildMailer(SwissKitSettingEmailEntity config) {
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

    /**
     * Resolves the correct {@link TransportStrategy} from the entity's TLS/SSL flags.
     * SSL takes priority when both flags are somehow enabled.
     *
     * <ul>
     *   <li>{@code needSSL=true}  → SMTPS (port 465, direct SSL)</li>
     *   <li>{@code needTLS=true}  → SMTP_TLS (port 587, STARTTLS)</li>
     *   <li>neither               → SMTP (port 25, no encryption)</li>
     * </ul>
     */
    private static TransportStrategy resolveTransportStrategy(SwissKitSettingEmailEntity config) {
        if (Boolean.TRUE.equals(config.getNeedSSL())) {
            return TransportStrategy.SMTPS;
        }
        if (Boolean.TRUE.equals(config.getNeedTLS())) {
            return TransportStrategy.SMTP_TLS;
        }
        return TransportStrategy.SMTP;
    }

    // ─────────────────────────────────────────────────────────
    // Config loading from DB
    // ─────────────────────────────────────────────────────────

    /**
     * Loads the latest email config from the database via MyBatis.
     *
     * @throws EmailException if no config record exists
     */
    private static SwissKitSettingEmailEntity loadConfig() throws EmailException {
        log.debug("Loading SMTP config from database");
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            SwissKitSettingEmailMapper mapper = session.getMapper(SwissKitSettingEmailMapper.class);
            SwissKitSettingEmailEntity config = mapper.selectLatest();
            if (config == null) {
                throw new EmailException(
                        "No email configuration found. Please configure SMTP settings first.", null);
            }
            log.debug("Loaded SMTP config | host={}:{} tls={} ssl={}",
                    config.getSmtpAddress(), config.getSmtpPort(),
                    config.getNeedTLS(), config.getNeedSSL());
            return config;
        }
    }

    // ─────────────────────────────────────────────────────────
    // Validation
    // ─────────────────────────────────────────────────────────

    private static void validateMessage(EmailMessage message) {
        if (message == null)
            throw new IllegalArgumentException("EmailMessage must not be null");
        if (message.to == null || message.to.isEmpty())
            throw new IllegalArgumentException("At least one recipient (to) is required");
        if (message.subject == null || message.subject.isBlank())
            throw new IllegalArgumentException("Subject must not be empty");
        if (message.textBody == null && message.htmlBody == null)
            throw new IllegalArgumentException("Either textBody or htmlBody must be provided");

        // Prevent SMTP injection: filter out \r \n characters from recipient addresses
        for (String to : message.to) {
            if (containsCRLF(to)) {
                throw new IllegalArgumentException("Invalid recipient address: " + to);
            }
        }
        // Same filter for subject
        if (containsCRLF(message.subject)) {
            throw new IllegalArgumentException("Invalid subject: contains illegal characters");
        }
    }

    private static boolean containsCRLF(String value) {
        return value != null && (value.contains("\r") || value.contains("\n"));
    }

    // ─────────────────────────────────────────────────────────
    // EmailMessage
    // ─────────────────────────────────────────────────────────

    /**
     * Describes the content of an outgoing email.
     * Construct via {@link Builder}.
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

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private List<String> to;
            private List<String> cc;
            private List<String> bcc;
            private String subject;
            private String textBody;
            private String htmlBody;
            private List<File> attachments;

            public Builder to(String... to) {
                this.to = java.util.Arrays.asList(to);
                return this;
            }

            public Builder to(List<String> to) {
                this.to = to;
                return this;
            }

            public Builder cc(String... cc) {
                this.cc = java.util.Arrays.asList(cc);
                return this;
            }

            public Builder cc(List<String> cc) {
                this.cc = cc;
                return this;
            }

            public Builder bcc(String... bcc) {
                this.bcc = java.util.Arrays.asList(bcc);
                return this;
            }

            public Builder bcc(List<String> bcc) {
                this.bcc = bcc;
                return this;
            }

            public Builder subject(String subject) {
                this.subject = subject;
                return this;
            }

            public Builder textBody(String text) {
                this.textBody = text;
                return this;
            }

            public Builder htmlBody(String html) {
                this.htmlBody = html;
                return this;
            }

            public Builder attachments(File... files) {
                this.attachments = java.util.Arrays.asList(files);
                return this;
            }

            public Builder attachments(List<File> files) {
                this.attachments = files;
                return this;
            }

            public EmailMessage build() {
                return new EmailMessage(this);
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // Exception
    // ─────────────────────────────────────────────────────────

    /**
     * Checked exception for email sending or configuration failures.
     */
    public static class EmailException extends Exception {
        public EmailException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}