package fan.summer.database.mapper.email;

import fan.summer.database.entity.email.EmailSentLogEntity;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * MyBatis mapper for {@link EmailSentLogEntity} email send log operations.
 * <p>
 * Provides comprehensive CRUD and query operations for tracking email
 * send history, with support for filtering by success status and date range.
 *
 * @since 3.0.0
 * @see EmailSentLogEntity
 */
public interface EmailSentLogMapper {
    /**
     * Inserts a new email send log entry.
     *
     * @param log the log entity to insert
     * @return the number of rows affected
     */
    int insert(EmailSentLogEntity log);

    /**
     * Deletes an email send log by its ID.
     *
     * @param id the log entry ID to delete
     * @return the number of rows affected
     */
    int deleteById(Long id);

    /**
     * Updates an existing email send log entry.
     *
     * @param log the log entity with updated values
     * @return the number of rows affected
     */
    int update(EmailSentLogEntity log);

    /**
     * Retrieves an email send log by its ID.
     *
     * @param id the log entry ID to look up
     * @return the log entity, or {@code null} if not found
     */
    EmailSentLogEntity selectById(Long id);

    /**
     * Retrieves all email send log entries.
     *
     * @return a list of all log entities, ordered by send time descending
     */
    List<EmailSentLogEntity> selectAll();

    /**
     * Retrieves email send logs filtered by success status.
     *
     * @param isSuccess whether to fetch successful ({@code true}) or failed ({@code false}) logs
     * @return a list of matching log entities
     */
    List<EmailSentLogEntity> selectBySuccess(@Param("isSuccess") boolean isSuccess);

    /**
     * Retrieves email send logs within a date range.
     *
     * @param startDate the start of the date range (inclusive)
     * @param endDate the end of the date range (inclusive)
     * @return a list of log entities within the range, ordered by send time descending
     */
    List<EmailSentLogEntity> selectByDateRange(@Param("startDate") Date startDate, @Param("endDate") Date endDate);

    /**
     * Deletes all email send log entries.
     *
     * @return the number of rows affected
     */
    int deleteAll();

    /**
     * Counts the total number of email send log entries.
     *
     * @return the total count of log entries
     */
    long count();

    /**
     * Counts the number of email send logs by success status.
     *
     * @param isSuccess whether to count successful ({@code true}) or failed ({@code false}) logs
     * @return the count of matching log entries
     */
    long countBySuccess(@Param("isSuccess") boolean isSuccess);
}
