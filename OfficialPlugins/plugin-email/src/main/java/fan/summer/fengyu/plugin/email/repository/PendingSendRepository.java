package fan.summer.fengyu.plugin.email.repository;

import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.PendingSend;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.session.SqlSession;

import java.time.LocalDateTime;
import java.util.Optional;

public final class PendingSendRepository {
    private final EmailDatabase database;

    public PendingSendRepository(EmailDatabase database) {
        this.database = database;
        AccountRepository.register(database, Mapper.class);
    }

    public PendingSend create(String confirmationId, long accountId, String mode, String snapshot,
            LocalDateTime expiresAt) {
        Row row = new Row(null, confirmationId, accountId, mode, snapshot, "PENDING", expiresAt, null);
        try (SqlSession session = database.openSession()) {
            session.getMapper(Mapper.class).insert(row);
            session.commit();
        }
        return find(confirmationId).orElseThrow();
    }

    public Optional<PendingSend> find(String confirmationId) {
        try (SqlSession session = database.openSession()) {
            return Optional.ofNullable(session.getMapper(Mapper.class).find(confirmationId));
        }
    }

    public boolean claim(String confirmationId, LocalDateTime now) {
        try (SqlSession session = database.openSession()) {
            boolean claimed = session.getMapper(Mapper.class).claim(confirmationId, now) == 1;
            session.commit();
            return claimed;
        }
    }

    public boolean reject(String confirmationId) {
        try (SqlSession session = database.openSession()) {
            boolean rejected = session.getMapper(Mapper.class).reject(confirmationId) == 1;
            session.commit();
            return rejected;
        }
    }

    public void expirePast(String confirmationId, LocalDateTime now) {
        try (SqlSession session = database.openSession()) {
            session.getMapper(Mapper.class).expirePast(confirmationId, now);
            session.commit();
        }
    }

    public void finish(String confirmationId, String status) {
        try (SqlSession session = database.openSession()) {
            session.getMapper(Mapper.class).finish(confirmationId, status);
            session.commit();
        }
    }

    public boolean hasOpenForAccount(long accountId, LocalDateTime now) {
        try (SqlSession session = database.openSession()) {
            return session.getMapper(Mapper.class).openCount(accountId, now) > 0;
        }
    }

    private static final class Row {
        private Long id;
        private final String confirmationId;
        private final long accountId;
        private final String mode;
        private final String snapshotJson;
        private final String status;
        private final LocalDateTime expiresAt;
        private final LocalDateTime updatedAt;
        private Row(Long id, String confirmationId, long accountId, String mode, String snapshotJson,
                String status, LocalDateTime expiresAt, LocalDateTime updatedAt) {
            this.id = id; this.confirmationId = confirmationId; this.accountId = accountId;
            this.mode = mode; this.snapshotJson = snapshotJson; this.status = status;
            this.expiresAt = expiresAt; this.updatedAt = updatedAt;
        }
    }

    private interface Mapper {
        @Insert("INSERT INTO FengTu_PL_Email_Pending_Send(confirmation_id,account_id,mode,snapshot_json,status,expires_at,updated_at) "
            + "VALUES(#{confirmationId},#{accountId},#{mode},#{snapshotJson},#{status},#{expiresAt},CURRENT_TIMESTAMP)")
        @Options(useGeneratedKeys=true,keyProperty="id") int insert(Row row);
        @Select("SELECT id,confirmation_id AS confirmationId,account_id AS accountId,mode,snapshot_json AS snapshotJson,status,expires_at AS expiresAt,updated_at AS updatedAt FROM FengTu_PL_Email_Pending_Send WHERE confirmation_id=#{id}")
        PendingSend find(String id);
        @Update("UPDATE FengTu_PL_Email_Pending_Send SET status='SENDING',updated_at=CURRENT_TIMESTAMP "
            + "WHERE confirmation_id=#{id} AND status='PENDING' AND expires_at>#{now}")
        int claim(@Param("id") String id, @Param("now") LocalDateTime now);
        @Update("UPDATE FengTu_PL_Email_Pending_Send SET status='REJECTED',updated_at=CURRENT_TIMESTAMP WHERE confirmation_id=#{id} AND status='PENDING'") int reject(String id);
        @Update("UPDATE FengTu_PL_Email_Pending_Send SET status='EXPIRED',updated_at=CURRENT_TIMESTAMP WHERE confirmation_id=#{id} AND status='PENDING' AND expires_at<=#{now}")
        int expirePast(@Param("id") String id, @Param("now") LocalDateTime now);
        @Update("UPDATE FengTu_PL_Email_Pending_Send SET status=#{status},updated_at=CURRENT_TIMESTAMP WHERE confirmation_id=#{id} AND status='SENDING'")
        int finish(@Param("id") String id, @Param("status") String status);
        @Select("SELECT COUNT(*) FROM FengTu_PL_Email_Pending_Send WHERE account_id=#{id} "
            + "AND (status='SENDING' OR (status='PENDING' AND expires_at>#{now}))")
        int openCount(@Param("id") long id, @Param("now") LocalDateTime now);
    }
}
