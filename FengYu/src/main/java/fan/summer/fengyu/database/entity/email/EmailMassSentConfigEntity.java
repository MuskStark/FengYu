package fan.summer.fengyu.database.entity.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * Entity representing the configuration for a mass email sending task. User-scoped.
 *
 * @author MuskStark
 */
@Entity
@Table(name = "email_mass_sent_config",
        uniqueConstraints = @UniqueConstraint(name = "uk_email_mass_user_task",
                columnNames = {"user_id", "task_id"}))
@Data
public class EmailMassSentConfigEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false) private String taskId;
    @Column(name = "to_tag") private String toTag;
    @Column(name = "cc_tag") private String ccTag;
    @Column(name = "is_sent_att", nullable = false) private boolean isSentAtt;
    @Column(name = "att_folder_path") private String attFolderPath;
    @Column(name = "send_by_filename", nullable = false) private boolean sendByFilename;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
