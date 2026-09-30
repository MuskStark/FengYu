package fan.summer.fengyu.plugin.email.repository;

import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.Contact;
import fan.summer.fengyu.plugin.email.model.Tag;
import fan.summer.fengyu.sdk.PluginMessages;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.session.SqlSession;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class AddressBookRepository {
    private static final PluginMessages MSGS = PluginMessages.forClassLoader(PluginMessages.DEFAULT_BASE_NAME, AddressBookRepository.class);
    private final EmailDatabase database;

    public AddressBookRepository(EmailDatabase database) {
        this.database = database;
        AccountRepository.register(database, Mapper.class);
    }

    /** Opens a session for read-only diffing (preview). Callers close it. */
    public SqlSession openSessionForRead() { return database.openSession(); }

    /**
     * Opens a transactional session for the importer's atomic commit (tag-create +
     * contact-upsert + assignment). Callers own the transaction and commit/rollback.
     */
    public SqlSession openSession() { return database.openSession(); }

    public Optional<Contact> findContact(long id) {
        try (SqlSession session = database.openSession()) {
            Mapper mapper = session.getMapper(Mapper.class);
            ContactRow row = mapper.findContact(id);
            return Optional.ofNullable(row == null ? null : contact(row, mapper.tagIds(id)));
        }
    }

    public List<Contact> searchContacts(String query, Set<Long> tagIds, int offset, int limit) {
        if (offset < 0 || limit < 1) throw new IllegalArgumentException(MSGS.format("em.err.invalidContactPage"));
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

    public Set<String> resolveEmailsForAttachmentTag(String attachmentTag, Set<Long> groupTagIds) {
        if (attachmentTag == null || attachmentTag.isBlank()
                || groupTagIds == null || groupTagIds.isEmpty()) return Set.of();
        try (SqlSession session = database.openSession()) {
            return Set.copyOf(session.getMapper(Mapper.class)
                .resolveIntersection(attachmentTag.trim().toLowerCase(), groupTagIds));
        }
    }

    public long saveContact(ContactInput input) {
        return saveContact(input, null);
    }

    public long saveContact(ContactInput input, Set<Long> tagIds) {
        try (SqlSession session = database.openSession()) {
            Mapper mapper = session.getMapper(Mapper.class);
            ContactRow row = new ContactRow(input.id(), input.email(), input.nickname(), normalizeNotes(input.notes()), null);
            if (input.id() == null) mapper.insertContact(row); else mapper.updateContact(row);
            long contactId = input.id() == null ? row.id() : input.id();
            if (tagIds != null) {
                mapper.deleteContactTags(contactId);
                for (long tagId : new LinkedHashSet<>(tagIds)) mapper.insertAssignment(contactId, tagId);
            }
            session.commit();
            return contactId;
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
                throw new IllegalArgumentException(MSGS.format("em.err.tagNameExists", name));
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

    /**
     * Resolves tag names to ids within the given session, creating any that don't
     * exist. Used by the batch importer so tag auto-create stays atomic with the
     * contact writes in the same transaction. Names are compared case-insensitively
     * (matching the {@code LOWER(name)} uniqueness constraint); the original casing
     * is preserved on create.
     *
     * @param names   the distinct tag names to resolve (any casing)
     * @param session the caller-owned transactional session (not committed here)
     * @return lowercase-name → tag id for every input name
     */
    public Map<String, Long> ensureTagsByName(Set<String> names, SqlSession session) {
        if (names == null || names.isEmpty()) return Map.of();
        Mapper mapper = session.getMapper(Mapper.class);
        Map<String, Long> resolved = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        for (String name : names) {
            String trimmed = name == null ? "" : name.trim();
            if (trimmed.isEmpty()) continue;
            Tag existing = mapper.findTagByName(trimmed);
            if (existing != null) resolved.put(trimmed.toLowerCase(), existing.id());
            else missing.add(trimmed);
        }
        for (String name : missing) {
            TagRow row = new TagRow(null, name);
            mapper.insertTag(row);
            resolved.put(name.toLowerCase(), row.id);
        }
        return resolved;
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

    /**
     * Returns the existing contact (id + current nickname/notes + tag ids) whose email
     * matches case-insensitively, within the caller's session. Used by the importer
     * to diff each parsed row against the address book.
     */
    public Contact findContactByEmail(String email, SqlSession session) {
        if (email == null || email.isBlank()) return null;
        Mapper mapper = session.getMapper(Mapper.class);
        ContactRow row = mapper.findContactByEmail(email.trim().toLowerCase());
        return row == null ? null : contact(row, mapper.tagIds(row.id()));
    }

    /**
     * Inserts a new contact (no existing id) and returns the generated id, within the
     * caller's session. Email is lowercased to match the {@link #findContactByEmail}
     * lookup so re-imports are idempotent regardless of source casing.
     */
    public long insertContact(ContactInput input, SqlSession session) {
        Mapper mapper = session.getMapper(Mapper.class);
        ContactRow row = new ContactRow(null, input.email().trim().toLowerCase(),
            trimToNull(input.nickname()), normalizeNotes(input.notes()), null);
        mapper.insertContact(row);
        return row.id();
    }

    /** Updates an existing contact's email/nickname/notes within the caller's session. */
    public void updateContact(long id, String email, String nickname, String notes, SqlSession session) {
        Mapper mapper = session.getMapper(Mapper.class);
        mapper.updateContact(new ContactRow(id, email.trim().toLowerCase(),
            trimToNull(nickname), normalizeNotes(notes), null));
    }

    /** Replaces a contact's tag assignments within the caller's session (delete-then-insert). */
    public void replaceTags(long contactId, Set<Long> tagIds, SqlSession session) {
        Mapper mapper = session.getMapper(Mapper.class);
        mapper.deleteContactTags(contactId);
        for (long tagId : new LinkedHashSet<>(tagIds)) mapper.insertAssignment(contactId, tagId);
    }

    /** Adds tag assignments for a contact without removing existing ones (used by merge mode). */
    public void addTags(long contactId, Set<Long> tagIds, SqlSession session) {
        Mapper mapper = session.getMapper(Mapper.class);
        for (long tagId : new LinkedHashSet<>(tagIds))
            if (mapper.assignmentCount(contactId, tagId) == 0) mapper.insertAssignment(contactId, tagId);
    }

    private static String trimToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record ContactInput(Long id, String email, String nickname, String notes) { }
    private static final class ContactRow {
        private Long id;
        private String email;
        private String nickname;
        private String notes;
        private LocalDateTime createdAt;
        private ContactRow() { }
        private ContactRow(Long id, String email, String nickname, String notes, LocalDateTime createdAt) {
            this.id = id; this.email = email; this.nickname = nickname;
            this.notes = notes; this.createdAt = createdAt;
        }
        public Long id() { return id; }
        public String email() { return email; }
        public String nickname() { return nickname; }
        public String notes() { return notes; }
        public LocalDateTime createdAt() { return createdAt; }
    }
    private static final class TagRow {
        private Long id;
        private final String name;
        private TagRow(Long id, String name) { this.id = id; this.name = name; }
    }

    private static Contact contact(ContactRow row, List<Long> tagIds) {
        return new Contact(row.id(), row.email(), row.nickname(), row.notes(), row.createdAt(), new LinkedHashSet<>(tagIds));
    }

    /** Normalizes notes so blank/whitespace-only values are stored as null (matches the service-layer trimToNull invariant for direct repository callers). */
    private static String normalizeNotes(String notes) {
        return notes == null || notes.isBlank() ? null : notes;
    }

    private interface Mapper {
        @Select("SELECT id,email,nickname,notes,created_at AS createdAt FROM FENGYU_PL_Email_Contact WHERE id=#{id}") ContactRow findContact(long id);
        @Select("SELECT id,email,nickname,notes,created_at AS createdAt FROM FENGYU_PL_Email_Contact WHERE LOWER(email)=#{email} ORDER BY id LIMIT 1") ContactRow findContactByEmail(String email);
        @Select({"<script>", "SELECT DISTINCT c.id,c.email,c.nickname,c.notes,c.created_at AS createdAt FROM FENGYU_PL_Email_Contact c",
            "<if test='tagIds != null and !tagIds.isEmpty()'> JOIN FENGYU_PL_Email_Contact_Tag ct ON ct.contact_id=c.id</if>",
            "WHERE (LOWER(c.email) LIKE #{pattern} OR LOWER(COALESCE(c.nickname,'')) LIKE #{pattern})",
            "<if test='tagIds != null and !tagIds.isEmpty()'> AND ct.tag_id IN <foreach item='id' collection='tagIds' open='(' separator=',' close=')'>#{id}</foreach></if>",
            "ORDER BY c.id LIMIT #{limit} OFFSET #{offset}", "</script>"})
        List<ContactRow> search(@Param("pattern") String pattern, @Param("tagIds") Set<Long> tagIds,
            @Param("offset") int offset, @Param("limit") int limit);
        @Select("SELECT tag_id FROM FENGYU_PL_Email_Contact_Tag WHERE contact_id=#{id} ORDER BY tag_id") List<Long> tagIds(long id);
        @Select({"<script>", "SELECT DISTINCT c.email FROM FENGYU_PL_Email_Contact c JOIN FENGYU_PL_Email_Contact_Tag ct ON ct.contact_id=c.id",
            "WHERE ct.tag_id IN <foreach item='id' collection='tagIds' open='(' separator=',' close=')'>#{id}</foreach>", "</script>"})
        Set<String> resolve(@Param("tagIds") Set<Long> tagIds);
        @Select({"<script>", "SELECT c.email FROM FENGYU_PL_Email_Contact c JOIN FENGYU_PL_Email_Contact_Tag ct ON ct.contact_id=c.id",
            "WHERE ct.tag_id IN <foreach item='id' collection='tagIds' open='(' separator=',' close=')'>#{id}</foreach>",
            "GROUP BY c.id,c.email HAVING COUNT(DISTINCT ct.tag_id)=#{count}", "</script>"})
        Set<String> resolveAll(@Param("tagIds") Set<Long> tagIds, @Param("count") int count);
        @Select({"<script>",
            "SELECT DISTINCT c.email FROM FENGYU_PL_Email_Contact c",
            "JOIN FENGYU_PL_Email_Contact_Tag attachment_ct ON attachment_ct.contact_id=c.id",
            "JOIN FENGYU_PL_Email_Tag attachment_t ON attachment_t.id=attachment_ct.tag_id",
            "JOIN FENGYU_PL_Email_Contact_Tag group_ct ON group_ct.contact_id=c.id",
            "WHERE LOWER(attachment_t.name)=#{attachmentTag}",
            "AND group_ct.tag_id IN <foreach item='id' collection='groupTagIds' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
        Set<String> resolveIntersection(@Param("attachmentTag") String attachmentTag,
            @Param("groupTagIds") Set<Long> groupTagIds);

        @Insert("INSERT INTO FENGYU_PL_Email_Contact(email,nickname,notes,created_at) VALUES(#{email},#{nickname},#{notes},CURRENT_TIMESTAMP)")
        @Options(useGeneratedKeys=true,keyProperty="id") int insertContact(ContactRow row);
        @Update("UPDATE FENGYU_PL_Email_Contact SET email=#{email},nickname=#{nickname},notes=#{notes} WHERE id=#{id}") int updateContact(ContactRow row);
        @Delete("DELETE FROM FENGYU_PL_Email_Contact_Tag WHERE contact_id=#{id}") int deleteContactTags(long id);
        @Delete("DELETE FROM FENGYU_PL_Email_Contact WHERE id=#{id}") int deleteContact(long id);

        @Select("SELECT id,name FROM FENGYU_PL_Email_Tag WHERE LOWER(name)=LOWER(#{name})") Tag findTagByName(String name);
        @Select("SELECT id,name FROM FENGYU_PL_Email_Tag ORDER BY LOWER(name),id") List<Tag> listTags();
        @Insert("INSERT INTO FENGYU_PL_Email_Tag(name) VALUES(#{name})") @Options(useGeneratedKeys=true,keyProperty="id") int insertTag(TagRow row);
        @Update("UPDATE FENGYU_PL_Email_Tag SET name=#{name} WHERE id=#{id}") int updateTag(TagRow row);
        @Delete("DELETE FROM FENGYU_PL_Email_Contact_Tag WHERE tag_id=#{id}") int deleteTagAssignments(long id);
        @Delete("DELETE FROM FENGYU_PL_Email_Tag WHERE id=#{id}") int deleteTag(long id);
        @Select("SELECT COUNT(*) FROM FENGYU_PL_Email_Contact_Tag WHERE contact_id=#{contactId} AND tag_id=#{tagId}")
        int assignmentCount(@Param("contactId") long contactId, @Param("tagId") long tagId);
        @Insert("INSERT INTO FENGYU_PL_Email_Contact_Tag(contact_id,tag_id) VALUES(#{contactId},#{tagId})")
        int insertAssignment(@Param("contactId") long contactId, @Param("tagId") long tagId);
    }
}
