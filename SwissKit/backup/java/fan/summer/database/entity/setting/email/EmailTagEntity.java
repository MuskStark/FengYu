package fan.summer.database.entity.setting.email;

import lombok.Data;

/**
 * Entity class representing an email tag.
 * Tags are used to categorize email addresses in the address book.
 *
 * @author phoebej
 */
@Data
public class EmailTagEntity {
    /** Unique identifier for the tag */
    private Long id;

    /** The tag name/label */
    private String tag;
}
