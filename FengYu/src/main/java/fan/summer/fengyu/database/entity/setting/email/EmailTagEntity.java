package fan.summer.fengyu.database.entity.setting.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * Entity representing an email tag. User-scoped.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "email_tag",
        uniqueConstraints = @UniqueConstraint(name = "uk_email_tag_user_tag",
                columnNames = {"user_id", "tag"}))
@Data
public class EmailTagEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private String tag;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
