package fan.summer.database.entity.setting.email;

import lombok.Data;

/**
 * Email settings entity.
 * Stores SMTP server configuration for sending emails.
 *
 * @author summer
 * @version 1.00
 * @date 2026/3/3
 */
@Data
public class SwissKitSettingEmailEntity {
    /**
     * Unique identifier for the email settings
     */
    private Integer id;

    /**
     * Email address/username for SMTP authentication
     */
    private String email;

    /**
     * Password for SMTP authentication
     */
    private String password;

    /**
     * SMTP server address
     */
    private String smtpAddress;

    /**
     * SMTP server port number
     */
    private Integer smtpPort;

    /**
     * Whether TLS encryption is required
     */
    private Boolean needTLS;

    /**
     * Whether SSL encryption is required
     */
    private Boolean needSSL;

    /**
     * Sender display name/address in sent emails
     */
    private String fromAddress;
}
