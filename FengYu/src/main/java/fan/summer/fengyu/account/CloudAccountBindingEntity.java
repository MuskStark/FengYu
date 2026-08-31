package fan.summer.fengyu.account;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * Single-row binding between this installation and a cloud (Infinia Store) account.
 *
 * <p>Design §7.2 / ADR-002: signing in links a cloud identity for outbound store calls;
 * it never changes local data ownership (owner=1 stays the virtual local user).
 */
@Entity
@Table(name = "cloud_account_binding")
@Data
public class CloudAccountBindingEntity {

    /** There is at most one binding per installation — always row id 1. */
    public static final long SINGLETON_ID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_user_id", length = 64, nullable = false)
    private String storeUserId;

    @Column(length = 320)
    private String email;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(length = 500)
    private String roles;

    @Column(name = "access_token", length = 8000)
    @Convert(converter = CloudTokenConverter.class)
    private String accessToken;

    @Column(name = "access_expires_at")
    private Instant accessExpiresAt;

    @Column(name = "refresh_token", length = 8000)
    @Convert(converter = CloudTokenConverter.class)
    private String refreshToken;

    @Column(name = "store_session_id", length = 64)
    private String storeSessionId;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
