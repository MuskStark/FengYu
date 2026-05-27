package fan.summer.database.entity.email;

import lombok.Data;

/**
 * Entity representing the configuration for a mass email sending task.
 *
 * <p>Stores metadata about how a bulk email job should be executed,
 * including which recipient tags to use, CC tags, and whether to
 * send personalized attachments based on filenames.
 *
 * @author MuskStark
 */
@Data
public class EmailMassSentConfigEntity {
    /** Primary key. */
    private Long id;
    /** Unique identifier for the email sending task. */
    private String taskId;
    /** Tag used to select the recipients ("to" recipients). */
    private String toTag;
    /** Tag used to select CC recipients. */
    private String ccTag;
    /** Whether to send an attachment with each email. */
    private boolean isSentAtt;
    /** Absolute path to the folder containing the attachment file(s). */
    private String attFolderPath;
    /** Whether to match attachment filenames to recipient names for personalization. */
    private boolean sendByFilename;
}
