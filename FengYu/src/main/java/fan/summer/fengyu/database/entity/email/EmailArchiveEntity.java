package fan.summer.fengyu.database.entity.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.util.Date;

/**
 * Entity representing an archived email message. User-scoped.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "email_archive",
        uniqueConstraints = @UniqueConstraint(name = "uk_email_archive_user_uid",
                columnNames = {"user_id", "account_email", "folder", "message_uid"}))
@Data
public class EmailArchiveEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "account_email", nullable = false) private String accountEmail;
    @Column(nullable = false) private String folder;
    @Column(name = "message_uid", nullable = false) private String messageUid;
    @Column(length = 500) private String subject;
    @Column(name = "from_address", length = 500) private String fromAddress;
    @Column(name = "to_address", length = 1000) private String toAddress;
    @Column(name = "cc_address", length = 1000) private String ccAddress;
    @Column(name = "send_date") private Date sendDate;
    @Column(name = "has_attachment") private Boolean hasAttachment;
    @Column(name = "eml_path", length = 1000) private String emlPath;
    @Column(name = "body_preview", length = 500) private String bodyPreview;
    @Column(name = "archived_at") private Date archivedAt;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
