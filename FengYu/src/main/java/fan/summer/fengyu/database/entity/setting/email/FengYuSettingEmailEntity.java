package fan.summer.fengyu.database.entity.setting.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Entity representing the email SMTP configuration. User-scoped.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "fengyu_setting_email")
@Data
public class FengYuSettingEmailEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false) private String email;
    @Column(nullable = false) private String password;
    @Column(name = "smtp_address", nullable = false) private String smtpAddress;
    @Column(name = "smtp_port", nullable = false) private Integer smtpPort;
    @Column(name = "need_tls", nullable = false) private Boolean needTLS;
    @Column(name = "need_ssl", nullable = false) private Boolean needSSL;
    @Column(name = "from_address") private String fromAddress;
    @Column(name = "imap_address") private String imapAddress;
    @Column(name = "imap_port") private Integer imapPort;
    @Column(name = "imap_ssl", nullable = false) private Boolean imapSSL;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
