package fan.summer.fengyu.plugin.email.repository;

import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.Contact;
import fan.summer.fengyu.plugin.email.model.Tag;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.session.SqlSession;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class AddressBookRepository {
    private final EmailDatabase database;

    public AddressBookRepository(EmailDatabase database) {
        this.database = database;
        AccountRepository.register(database, Mapper.class);
    }

    public Optional<Contact> findContact(long id) {
        try (SqlSession session = database.openSession()) {
            Mapper mapper = session.getMapper(Mapper.class);
            ContactRow row = mapper.findContact(id);
            return Optional.ofNullable(row == null ? null : contact(row, mapper.tagIds(id)));
        }
    }

    public List<Contact> searchContacts(String query, Set<Long> tagIds, int offset, int limit) {
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("Invalid contact page");
        try (SqlSession session = database.openSession()) {
            Mapper mapper = session.getMapper(Mapper.class);
            String pattern = "%" + (query == null ? "" : query.trim().toLowerCase()) + "%";
            return mapper.search(pattern, tagIds == null ? Set.of() : tagIds, offset, limit).stream()
                .map(row -> contact(row, mapper.tagIds(row.id()))).toList();
        }
    }

    public Set<String> resolveRecipientEmails(Set<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) return Set.of();
        try (SqlSession session = database.openSession()) {
            return Set.copyOf(session.getMapper(Mapper.class).resolve(tagIds));
        }
    }

    public Set<String> resolveRecipientEmails(Set<Long> tagIds, boolean requireAllTags) {
        if (!requireAllTags) return resolveRecipientEmails(tagIds);
        if (tagIds == null || tagIds.isEmpty()) return Set.of();
        try (SqlSession session = database.openSession()) {
            return Set.copyOf(session.getMapper(Mapper.class).resolveAll(tagIds, tagIds.size()));
        }
    }

    public long saveContact(ContactInput input) {
        try (SqlSession session = database.openSession()) {
            Mapper mapper = session.getMapper(Mapper.class);
            ContactRow row = new ContactRow(input.id(), input.email(), input.nickname(), null);
            if (input.id() == null) mapper.insertContact(row); else mapper.updateContact(row);
            session.commit();
            return input.id() == null ? row.id() : input.id();
        }
    }

    public boolean deleteContact(long id) {
        try (SqlSession session = database.openSession()) {
            Mapper mapper = session.getMapper(Mapper.class);
            mapper.deleteContactTags(id);
            boolean deleted = mapper.deleteContact(id) > 0;
            session.commit();
            return deleted;
        }
    }

    public long saveTag(Long id, String name) {
        try (SqlSession session = database.openSession()) {
            Mapper mapper = session.getMapper(Mapper.class);
            Tag duplicate = mapper.findTagByName(name);
            if (duplicate != null && (id == null || duplicate.id() != id))
                throw new IllegalArgumentException("Tag name already exists: " + name);
            TagRow row = new TagRow(id, name);
            if (id == null) mapper.insertTag(row); else mapper.updateTag(row);
            session.commit();
            return id == null ? row.id : id;
        }
    }

    public List<Tag> listTags() {
        try (SqlSession session = database.openSession()) {
            return List.copyOf(session.getMapper(Mapper.class).listTags());
        }
    }

    public boolean deleteTag(long id) {
        try (SqlSession session = database.openSession()) {
            Mapper mapper = session.getMapper(Mapper.class);
            mapper.deleteTagAssignments(id);
            boolean deleted = mapper.deleteTag(id) > 0;
            session.commit();
            return deleted;
        }
    }

    public void assignTags(Set<Long> contactIds, Set<Long> tagIds) {
        if (contactIds == null || tagIds == null || contactIds.isEmpty() || tagIds.isEmpty()) return;
        try (SqlSession session = database.openSession()) {
            Mapper mapper = session.getMapper(Mapper.class);
            for (long contactId : contactIds) for (long tagId : tagIds)
                if (mapper.assignmentCount(contactId, tagId) == 0) mapper.insertAssignment(contactId, tagId);
            session.commit();
        }
    }

    public record ContactInput(Long id, String email, String nickname) { }
    private static final class ContactRow {
        private Long id;
        private String email;
        private String nickname;
        private LocalDateTime createdAt;
        private ContactRow() { }
        private ContactRow(Long id, String email, String nickname, LocalDateTime createdAt) {
            this.id = id; this.email = email; this.nickname = nickname; this.createdAt = createdAt;
        }
        public Long id() { return id; }
        public String email() { return email; }
        public String nickname() { return nickname; }
        public LocalDateTime createdAt() { return createdAt; }
    }
    private static final class TagRow {
        private Long id;
        private final String name;
        private TagRow(Long id, String name) { this.id = id; this.name = name; }
    }

    private static Contact contact(ContactRow row, List<Long> tagIds) {
        return new Contact(row.id(), row.email(), row.nickname(), row.createdAt(), new LinkedHashSet<>(tagIds));
    }

    private interface Mapper {
        @Select("SELECT id,email,nickname,created_at AS createdAt FROM FengTu_PL_Email_Contact WHERE id=#{id}") ContactRow findContact(long id);
        @Select({"<script>", "SELECT DISTINCT c.id,c.email,c.nickname,c.created_at AS createdAt FROM FengTu_PL_Email_Contact c",
            "<if test='tagIds != null and !tagIds.isEmpty()'> JOIN FengTu_PL_Email_Contact_Tag ct ON ct.contact_id=c.id</if>",
            "WHERE (LOWER(c.email) LIKE #{pattern} OR LOWER(COALESCE(c.nickname,'')) LIKE #{pattern})",
            "<if test='tagIds != null and !tagIds.isEmpty()'> AND ct.tag_id IN <foreach item='id' collection='tagIds' open='(' separator=',' close=')'>#{id}</foreach></if>",
            "ORDER BY c.id LIMIT #{limit} OFFSET #{offset}", "</script>"})
        List<ContactRow> search(@Param("pattern") String pattern, @Param("tagIds") Set<Long> tagIds,
            @Param("offset") int offset, @Param("limit") int limit);
        @Select("SELECT tag_id FROM FengTu_PL_Email_Contact_Tag WHERE contact_id=#{id} ORDER BY tag_id") List<Long> tagIds(long id);
        @Select({"<script>", "SELECT DISTINCT c.email FROM FengTu_PL_Email_Contact c JOIN FengTu_PL_Email_Contact_Tag ct ON ct.contact_id=c.id",
            "WHERE ct.tag_id IN <foreach item='id' collection='tagIds' open='(' separator=',' close=')'>#{id}</foreach>", "</script>"})
        Set<String> resolve(@Param("tagIds") Set<Long> tagIds);
        @Select({"<script>", "SELECT c.email FROM FengTu_PL_Email_Contact c JOIN FengTu_PL_Email_Contact_Tag ct ON ct.contact_id=c.id",
            "WHERE ct.tag_id IN <foreach item='id' collection='tagIds' open='(' separator=',' close=')'>#{id}</foreach>",
            "GROUP BY c.id,c.email HAVING COUNT(DISTINCT ct.tag_id)=#{count}", "</script>"})
        Set<String> resolveAll(@Param("tagIds") Set<Long> tagIds, @Param("count") int count);

        @Insert("INSERT INTO FengTu_PL_Email_Contact(email,nickname) VALUES(#{email},#{nickname})")
        @Options(useGeneratedKeys=true,keyProperty="id") int insertContact(ContactRow row);
        @Update("UPDATE FengTu_PL_Email_Contact SET email=#{email},nickname=#{nickname} WHERE id=#{id}") int updateContact(ContactRow row);
        @Delete("DELETE FROM FengTu_PL_Email_Contact_Tag WHERE contact_id=#{id}") int deleteContactTags(long id);
        @Delete("DELETE FROM FengTu_PL_Email_Contact WHERE id=#{id}") int deleteContact(long id);

        @Select("SELECT id,name FROM FengTu_PL_Email_Tag WHERE LOWER(name)=LOWER(#{name})") Tag findTagByName(String name);
        @Select("SELECT id,name FROM FengTu_PL_Email_Tag ORDER BY LOWER(name),id") List<Tag> listTags();
        @Insert("INSERT INTO FengTu_PL_Email_Tag(name) VALUES(#{name})") @Options(useGeneratedKeys=true,keyProperty="id") int insertTag(TagRow row);
        @Update("UPDATE FengTu_PL_Email_Tag SET name=#{name} WHERE id=#{id}") int updateTag(TagRow row);
        @Delete("DELETE FROM FengTu_PL_Email_Contact_Tag WHERE tag_id=#{id}") int deleteTagAssignments(long id);
        @Delete("DELETE FROM FengTu_PL_Email_Tag WHERE id=#{id}") int deleteTag(long id);
        @Select("SELECT COUNT(*) FROM FengTu_PL_Email_Contact_Tag WHERE contact_id=#{contactId} AND tag_id=#{tagId}")
        int assignmentCount(@Param("contactId") long contactId, @Param("tagId") long tagId);
        @Insert("INSERT INTO FengTu_PL_Email_Contact_Tag(contact_id,tag_id) VALUES(#{contactId},#{tagId})")
        int insertAssignment(@Param("contactId") long contactId, @Param("tagId") long tagId);
    }
}
