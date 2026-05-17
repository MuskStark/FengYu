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
 */
public class EmailUtil {

    private static final Logger log = LoggerFactory.getLogger(EmailUtil.class);

    private EmailUtil() {
    }

    public static void sendText(String to, String subject, String text) throws EmailException {
        sendEmail(EmailMessage.builder()
                .to(to)
                .subject(subject)
                .textBody(text)
                .build());
    }

    public static void sendHtml(String to, String subject, String html) throws EmailException {
        sendEmail(EmailMessage.builder()
                .to(to)
                .subject(subject)
                .htmlBody(html)
                .build());
    }

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

    private static Email buildEmail(SwissKitSettingEmailEntity config, EmailMessage message) {
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

    private static TransportStrategy resolveTransportStrategy(SwissKitSettingEmailEntity config) {
        if (Boolean.TRUE.equals(config.getNeedSSL())) {
            return TransportStrategy.SMTPS;
        }
        if (Boolean.TRUE.equals(config.getNeedTLS())) {
            return TransportStrategy.SMTP_TLS;
        }
        return TransportStrategy.SMTP;
    }

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

    private static void validateMessage(EmailMessage message) {
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

    public static class EmailException extends Exception {
        public EmailException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
