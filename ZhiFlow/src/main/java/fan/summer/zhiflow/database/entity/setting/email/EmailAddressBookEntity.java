package fan.summer.zhiflow.database.entity.setting.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Entity representing an entry in the email address book. User-scoped.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "email_address_book")
@Data
public class EmailAddressBookEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "email_address", nullable = false)
    private String emailAddress;

    private String nickname;

    @Column(length = 1000)
    private String tags;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
