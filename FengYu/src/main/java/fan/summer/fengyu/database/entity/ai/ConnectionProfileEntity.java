package fan.summer.fengyu.database.entity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * One reusable named credential ("connection") a user fills in ONCE and picks from a
 * dropdown wherever a flow node asks for it — the ConnectionProfile half of the
 * connections model. Field values are owner-readable (loopback + launch token): the
 * settings editor must be able to re-display them for editing, exactly like the MCP
 * server form re-displays its env/header values.
 */
@Entity
@Table(name = "ai_connection_profile",
        indexes = {
                @Index(name = "idx_connection_profile_owner", columnList = "user_id,name"),
                @Index(name = "idx_connection_profile_type", columnList = "user_id,type")
        })
@Data
public class ConnectionProfileEntity {
    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Display name, unique per user ("公司邮箱"). */
    @Column(nullable = false, length = 80)
    private String name;

    /** Machine kind this profile answers ("email", "api-key") — matches tool annotations. */
    @Column(nullable = false, length = 40)
    private String type;

    @Column(length = 200)
    private String description;

    /** Flat string map of credential fields ({"token": "…", "account": "…"}). */
    @Column(name = "fields_json", columnDefinition = "TEXT", nullable = false)
    private String fieldsJson = "{}";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
