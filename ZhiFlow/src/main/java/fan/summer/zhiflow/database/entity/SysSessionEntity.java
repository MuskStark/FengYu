package fan.summer.zhiflow.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * System session entity — reserved for future login implementation (session token or JWT jti).
 *
 * <p>Created in Phase 4 as empty groundwork; not populated until authentication is implemented.
 */
@Entity
@Table(name = "sys_session")
@Data
public class SysSessionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 500, nullable = false, unique = true)
    private String token;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "client_ip", length = 100)
    private String clientIp;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
