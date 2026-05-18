package fan.summer.database.mapper.email;

import fan.summer.database.entity.email.EmailSentLogEntity;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface EmailSentLogMapper {

    /**
     * Insert a new email sent log.
     *
     * @param log the email sent log entity
     * @return the number of affected rows
     */
    int insert(EmailSentLogEntity log);

    /**
     * Delete email sent log by ID.
     *
     * @param id the log ID
     * @return the number of affected rows
     */
    int deleteById(Long id);

    /**
     * Update email sent log.
     *
     * @param log the email sent log entity
     * @return the number of affected rows
     */
    int update(EmailSentLogEntity log);

    /**
     * Select email sent log by ID.
     *
     * @param id the log ID
     * @return the email sent log entity, or null if not found
     */
    EmailSentLogEntity selectById(Long id);

    /**
     * Select all email sent logs.
     *
     * @return list of all email sent logs
     */
    List<EmailSentLogEntity> selectAll();

    /**
     * Select email sent logs by success status.
     *
     * @param isSuccess whether the email was sent successfully
     * @return list of email sent logs matching the criteria
     */
    List<EmailSentLogEntity> selectBySuccess(@Param("isSuccess") boolean isSuccess);

    /**
     * Select email sent logs within date range.
     *
     * @param startDate the start date
     * @param endDate   the end date
     * @return list of email sent logs within the date range
     */
    List<EmailSentLogEntity> selectByDateRange(@Param("startDate") Date startDate,
                                               @Param("endDate") Date endDate);

    /**
     * Delete all email sent logs.
     *
     * @return the number of affected rows
     */
    int deleteAll();

    /**
     * Count total email sent logs.
     *
     * @return total count of email sent logs
     */
    long count();

    /**
     * Count email sent logs by success status.
     *
     * @param isSuccess whether the email was sent successfully
     * @return count of email sent logs matching the criteria
     */
    long countBySuccess(@Param("isSuccess") boolean isSuccess);
}
