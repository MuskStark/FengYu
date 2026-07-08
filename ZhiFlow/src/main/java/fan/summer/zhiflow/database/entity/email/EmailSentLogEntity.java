package fan.summer.zhiflow.database.entity.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.Date;

/**
 * Entity representing a log entry for a sent email. User-scoped.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "email_sent_log")
@Data
public class EmailSentLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "\"to\"", length = 1000) private String to;
    @Column(name = "cc", length = 1000) private String cc;
    @Column(name = "bcc", length = 1000) private String bcc;
    @Column(length = 500) private String subject;
    @Column(columnDefinition = "TEXT") private String content;
    @Column(length = 1000) private String attachment;
    @Column(name = "send_time") private Date sendTime;
    @Column(name = "is_success", nullable = false) private boolean isSuccess;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
