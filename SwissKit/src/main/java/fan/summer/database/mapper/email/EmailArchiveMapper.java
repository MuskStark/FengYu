package fan.summer.database.mapper.email;

import fan.summer.database.entity.email.EmailArchiveEntity;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * MyBatis mapper for {@link EmailArchiveEntity} persistence operations.
 *
 * <p>Provides insert and query operations for the {@code email_archive} table,
 * which stores archived email metadata indexed by account, folder, and IMAP UID.</p>
 *
 * @since 3.0.0
 * @see EmailArchiveEntity
 */
public interface EmailArchiveMapper {
    /**
     * Inserts a new archived email record.
     *
     * @param entity the archive entity to persist
     * @return the number of rows affected (1 on success)
     */
    int insert(EmailArchiveEntity entity);

    /**
     * Looks up an archived email by its unique IMAP UID within a folder.
     *
     * @param accountEmail the email account address
     * @param folder       the IMAP folder name
     * @param messageUid   the IMAP message UID
     * @return the matching entity, or {@code null} if not found
     */
    EmailArchiveEntity selectByUid(@Param("accountEmail") String accountEmail,
                                    @Param("folder") String folder,
                                    @Param("messageUid") String messageUid);

    /**
     * Searches archived emails by flexible criteria. All parameters are optional;
     * non-null parameters are combined with AND logic.
     *
     * @param accountEmail the email account to filter by; may be null
     * @param fromAddress  substring match on the sender address; may be null
     * @param subject      substring match on the subject line; may be null
     * @param startDate    inclusive lower bound for send date; may be null
     * @param endDate      inclusive upper bound for send date; may be null
     * @return a list of matching entities ordered by send date descending
     */
    List<EmailArchiveEntity> selectByQuery(@Param("accountEmail") String accountEmail,
                                            @Param("fromAddress") String fromAddress,
                                            @Param("subject") String subject,
                                            @Param("startDate") Date startDate,
                                            @Param("endDate") Date endDate);
}
