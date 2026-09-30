package fan.summer.fengyu.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * Entity representing a key-value application setting stored in the database.
 *
 * <p>User-scoped: each setting belongs to a user ({@code user_id}), enabling multi-account
 * data isolation. Local offline mode uses the virtual user id=1.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "app_setting",
        uniqueConstraints = @UniqueConstraint(name = "uk_app_setting_user_key",
                columnNames = {"user_id", "setting_key"}))
@Data
public class AppSettingEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "setting_key", nullable = false)
    private String settingKey;

    /**
     * TEXT (not VARCHAR(1000)): values like the AI permission-rule table and the hook
     * list are serialized JSON that routinely exceeds 1000 characters — a capped column
     * would truncate them into unparseable JSON and silently empty the guard config,
     * which for deny rules weakens the user's security intent (P2-6). TEXT is portable
     * across H2/MySQL/PostgreSQL; {@code ddl-auto=update} widens existing columns.
     */
    @Column(name = "setting_value", columnDefinition = "TEXT")
    private String settingValue;

    /** User isolation field. Local offline mode = 1 (virtual user). */
    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
