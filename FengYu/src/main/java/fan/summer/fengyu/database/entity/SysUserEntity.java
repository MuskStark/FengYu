package fan.summer.fengyu.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * System user entity. Backs the user system groundwork (Phase 4 setup wizard).
 *
 * <p>In local offline mode, a single virtual user (id=1, username "Summer") owns all
 * data. When login is implemented in a later phase, real users are added here. The
 * {@code authProvider} field distinguishes local (username/password) from SSO sources.
 *
 * <p>{@code id} uses IDENTITY generation, but the virtual user is inserted with an explicit
 * id=1 by {@code VirtualUserInitializer} (via a flush-then-native-insert fallback).
 */
@Entity
@Table(name = "sys_user",
        uniqueConstraints = @UniqueConstraint(name = "uk_sys_user_username", columnNames = "username"))
@Data
public class SysUserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String username;

    /** bcrypt hash for local users; null for SSO users or the offline virtual user. */
    @Column(length = 255)
    private String passwordHash;

    /** Auth source: "local", "github", "google", "oidc", etc. Virtual user = "local". */
    @Column(length = 255)
    private String authProvider;

    /** SSO provider's unique user ID; null for local users. */
    @Column(length = 255)
    private String externalId;

    /** 1=enabled, 0=disabled. */
    @Column(nullable = false)
    private Integer status;

    /** 0=normal user, 1=admin. Virtual user is admin. */
    @Column(nullable = false)
    private Integer userType;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
