package fan.summer.database.entity.setting.email;

import lombok.Data;

/**
 * Entity class representing an email address book entry.
 * Stores email address, nickname, and associated tags.
 *
 * @author phoebej
 * @version 1.00
 * @Date 2026/3/7
 */
@Data
public class EmailAddressBookEntity {
    /** Unique identifier for the address book entry */
    private Integer id;

    /** The email address */
    private String emailAddress;

    /** Display name/nickname for the contact */
    private String nickname;

    /** Comma-separated list of tags associated with this address */
    private String tags;
}
