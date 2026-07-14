package fan.summer.fengyu.plugin.email.repository;

import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.session.SqlSession;

/** Append-only audit log for immutable message snapshots. */
public final class SentLogRepository {
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

    public record SentLogEntry(String confirmationId, String accountEmail, String recipientsJson,
            String subject, String attachmentJson, String status, String errorMessage) { }

    private interface Mapper {
        @Insert("INSERT INTO FengTu_PL_Email_Sent_Log(confirmation_id,account_email,recipients_json,subject,attachment_json,status,error_message,sent_at) "
            + "VALUES(#{confirmationId},#{accountEmail},#{recipientsJson},#{subject},#{attachmentJson},#{status},#{errorMessage},CURRENT_TIMESTAMP)")
        int insert(SentLogEntry entry);
    }
}
