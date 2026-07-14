package fan.summer.fengyu.plugin.email.repository;

import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.ArchivedMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.jdbc.SQL;
import org.apache.ibatis.session.SqlSession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class ArchiveRepository {
    private final EmailDatabase database;

    public ArchiveRepository(EmailDatabase database) {
        this.database = database;
        AccountRepository.register(database, Mapper.class);
    }

    public boolean exists(long accountId, String folder, String messageUid) {
        try (SqlSession session = database.openSession()) {
            return session.getMapper(Mapper.class).countByUid(accountId, folder, messageUid) > 0;
        }
    }

    public long insert(ArchiveEntry entry) {
        try (SqlSession session = database.openSession()) {
            session.getMapper(Mapper.class).insert(entry);
            session.commit();
            return entry.id;
        }
    }

    public List<ArchivedMessage> search(SearchCriteria criteria) {
        try (SqlSession session = database.openSession()) {
            return List.copyOf(session.getMapper(Mapper.class).search(criteria));
        }
    }

    public Optional<ArchivedMessage> detail(long id) {
        try (SqlSession session = database.openSession()) {
            return Optional.ofNullable(session.getMapper(Mapper.class).detail(id));
        }
    }

    public static final class ArchiveEntry {
        private Long id;
        private final long accountId;
        private final String accountEmail;
        private final String folder;
        private final String messageUid;
        private final String subject;
        private final String fromAddress;
        private final String recipientsJson;
        private final Instant sentAt;
        private final Instant receivedAt;
        private final boolean hasAttachment;
        private final String bodyPreview;
        private final String emlPath;

        public ArchiveEntry(long accountId, String accountEmail, String folder, String messageUid, String subject,
                String fromAddress, String recipientsJson, Instant sentAt, Instant receivedAt,
                boolean hasAttachment, String bodyPreview, String emlPath) {
            this.accountId = accountId;
            this.accountEmail = accountEmail;
            this.folder = folder;
            this.messageUid = messageUid;
            this.subject = subject;
            this.fromAddress = fromAddress;
            this.recipientsJson = recipientsJson;
            this.sentAt = sentAt;
            this.receivedAt = receivedAt;
            this.hasAttachment = hasAttachment;
            this.bodyPreview = bodyPreview;
            this.emlPath = emlPath;
        }
    }

    public record SearchCriteria(Long accountId, String folder, String senderPattern, String subjectPattern,
            Instant start, Instant end, int offset, int limit) {
    }

    private interface Mapper {
        String COLUMNS = "id, account_id AS accountId, account_email AS accountEmail, folder, "
            + "message_uid AS messageUid, subject, from_address AS fromAddress, recipients_json AS recipientsJson, "
            + "sent_at AS sentAt, received_at AS receivedAt, has_attachment AS hasAttachment, "
            + "body_preview AS bodyPreview, eml_path AS emlPath, archived_at AS archivedAt";

        @Select("SELECT COUNT(*) FROM FengTu_PL_Email_Archive WHERE account_id=#{accountId} "
            + "AND folder=#{folder} AND message_uid=#{messageUid}")
        int countByUid(@Param("accountId") long accountId, @Param("folder") String folder,
            @Param("messageUid") String messageUid);

        @Insert("INSERT INTO FengTu_PL_Email_Archive(account_id,account_email,folder,message_uid,subject,"
            + "from_address,recipients_json,sent_at,received_at,has_attachment,body_preview,eml_path) VALUES("
            + "#{accountId},#{accountEmail},#{folder},#{messageUid},#{subject},#{fromAddress},#{recipientsJson},"
            + "#{sentAt},#{receivedAt},#{hasAttachment},#{bodyPreview},#{emlPath})")
        @Options(useGeneratedKeys = true, keyProperty = "id")
        int insert(ArchiveEntry entry);

        @SelectProvider(type = SearchSql.class, method = "build")
        List<ArchivedMessage> search(SearchCriteria criteria);

        @Select("SELECT " + COLUMNS + " FROM FengTu_PL_Email_Archive WHERE id=#{id}")
        ArchivedMessage detail(@Param("id") long id);
    }

    public static final class SearchSql {
        private SearchSql() { }

        public static String build(SearchCriteria criteria) {
            SQL sql = new SQL().SELECT(Mapper.COLUMNS).FROM("FengTu_PL_Email_Archive");
            if (criteria.accountId() != null) sql.WHERE("account_id = #{accountId}");
            if (criteria.folder() != null) sql.WHERE("folder = #{folder}");
            if (criteria.senderPattern() != null) sql.WHERE("LOWER(from_address) LIKE #{senderPattern}");
            if (criteria.subjectPattern() != null) sql.WHERE("LOWER(subject) LIKE #{subjectPattern}");
            if (criteria.start() != null) sql.WHERE("sent_at >= #{start}");
            if (criteria.end() != null) sql.WHERE("sent_at <= #{end}");
            return sql + " ORDER BY COALESCE(sent_at, received_at, archived_at) DESC, id DESC"
                + " LIMIT #{limit} OFFSET #{offset}";
        }
    }
}
