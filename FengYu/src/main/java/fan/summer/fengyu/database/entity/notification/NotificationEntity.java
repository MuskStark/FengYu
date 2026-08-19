package fan.summer.fengyu.database.entity.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One host-side notification, surfaced uniformly by the shell (web + desktop): a live toast
 * while the app is visible, a native OS notification through the Electron shell when it is
 * not, and a persisted entry in the notification center afterwards.
 *
 * <p>Producers write through {@code fan.summer.fengyu.notification.NotificationService},
 * which persists the row and fans it out to live SSE subscribers; nothing writes this
 * entity directly. Table creation is left to Hibernate {@code ddl-auto=update} like every
 * other 4.0.0 entity — no Flyway migration unless a rename/drop is ever needed.
 *
 * <p>{@code source} identifies the originator ({@code host}, {@code agent}, or
 * {@code plugin:<id>}) so the frontend can localize known titles and badge plugin origins;
 * {@code title}/{@code body} carry the display text as stored (English fallback for
 * backend-generated entries).
 */
@Entity
@Table(name = "host_notification")
@Data
public class NotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Originator: "host", "agent", or "plugin:<id>". */
    @Column(nullable = false, length = 100)
    private String source = "host";

    /** One of info | success | warning | error (validated by the service). */
    @Column(nullable = false, length = 16)
    private String level = "info";

    @Column(nullable = false, length = 200)
    private String title = "";

    /** Longer context line; empty string when the title says it all. */
    @Column(columnDefinition = "TEXT")
    private String body = "";

    /** Optional SPA route to open when the notification is activated (e.g. "/agent"). */
    @Column(length = 500)
    private String link;

    /** Null while unread; stamped when the user acknowledges the notification. */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
