package fan.summer.fengyu.plugin.email.repository;

import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.sdk.PluginMessages;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.SqlSession;

import java.time.LocalDateTime;
import java.util.List;

/** Append-only audit log for immutable message snapshots. */
public final class SentLogRepository {
    private static final PluginMessages MSGS = PluginMessages.forClassLoader(PluginMessages.DEFAULT_BASE_NAME, SentLogRepository.class);
    private final EmailDatabase database;

    public SentLogRepository(EmailDatabase database) {
        this.database = database;
        AccountRepository.register(database, Mapper.class);
    }

    public void insert(SentLogEntry entry) {
        try (SqlSession session = database.openSession()) {
            session.getMapper(Mapper.class).insert(entry);
            session.commit();
        }
    }

    public List<SentMessageView> search(String confirmationId, String status, String query,
            int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException(MSGS.format("em.err.invalidSentMessagePage"));
        String pattern = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase() + "%";
        try (SqlSession session = database.openSession()) {
            return List.copyOf(session.getMapper(Mapper.class)
                .search(confirmationId, status, pattern, offset, limit));
        }
    }

    /** Delete audit rows for an account's email. SentLog keys on {@code account_email}, not id. */
    public int deleteByAccountEmail(String email) {
        try (SqlSession session = database.openSession()) {
            int deleted = session.getMapper(Mapper.class).deleteByAccountEmail(email);
            session.commit();
            return deleted;
        }
    }

    public record SentLogEntry(String confirmationId, String accountEmail, String recipientsJson,
            String subject, String attachmentJson, String status, String errorMessage) { }
    public record SentMessageView(long id, String confirmationId, String accountEmail,
            String recipientsJson, String subject, String attachmentJson, String status,
            String errorMessage, LocalDateTime sentAt) { }

    private interface Mapper {
        @Insert("INSERT INTO FENGYU_PL_Email_Sent_Log(confirmation_id,account_email,recipients_json,subject,attachment_json,status,error_message,sent_at) "
            + "VALUES(#{confirmationId},#{accountEmail},#{recipientsJson},#{subject},#{attachmentJson},#{status},#{errorMessage},CURRENT_TIMESTAMP)")
        int insert(SentLogEntry entry);
        @Select({"<script>",
            "SELECT id,confirmation_id AS confirmationId,account_email AS accountEmail,recipients_json AS recipientsJson,subject,attachment_json AS attachmentJson,status,error_message AS errorMessage,sent_at AS sentAt",
            "FROM FENGYU_PL_Email_Sent_Log",
            "<where>",
            "<if test='confirmationId != null and !confirmationId.isBlank()'>confirmation_id=#{confirmationId}</if>",
            "<if test='status != null and !status.isBlank()'>AND status=#{status}</if>",
            "<if test='pattern != null'>AND (LOWER(account_email) LIKE #{pattern} OR LOWER(COALESCE(subject,'')) LIKE #{pattern} OR LOWER(recipients_json) LIKE #{pattern})</if>",
            "</where>",
            "ORDER BY sent_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}",
            "</script>"})
        List<SentMessageView> search(@Param("confirmationId") String confirmationId,
            @Param("status") String status, @Param("pattern") String pattern,
            @Param("offset") int offset, @Param("limit") int limit);
        @Delete("DELETE FROM FENGYU_PL_Email_Sent_Log WHERE account_email=#{email}")
        int deleteByAccountEmail(@Param("email") String email);
    }
}
