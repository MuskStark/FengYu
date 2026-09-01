package fan.summer.fengyu.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Data;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Single-row binding between this installation and a cloud (Infinia Store) account.
 *
 * <p>Design §7.2 / ADR-002: signing in links a cloud identity for outbound store calls;
 * it never changes local data ownership (owner=1 stays the virtual local user).
 * Tokens deliberately have no columns here (review M-5): the access token lives
 * only in memory and the refresh token only in the OS credential store.
 */
@Entity
@Table(name = "cloud_account_binding")
@Data
public class CloudAccountBindingEntity implements Persistable<Long> {

    /** There is at most one binding per installation — always row id 1. */
    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "store_user_id", length = 64, nullable = false)
    private String storeUserId;

    @Column(length = 320)
    private String email;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(length = 500)
    private String roles;

    @Column(name = "store_session_id", length = 64)
    private String storeSessionId;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * The singleton uses an assigned id. Tell Spring Data to persist a freshly-created
     * binding instead of merging it as a detached row, which Hibernate 7 rejects when
     * the installation has never signed in (or has signed out and deleted the row).
     */
    @Transient
    private boolean newEntity = true;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        newEntity = false;
    }
}
