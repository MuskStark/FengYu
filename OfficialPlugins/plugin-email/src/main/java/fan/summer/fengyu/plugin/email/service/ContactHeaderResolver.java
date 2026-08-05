package fan.summer.fengyu.plugin.email.service;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps raw header-cell text from a contact-list file (CSV or Excel) to the four
 * logical columns the importer understands. Matching is case-insensitive and
 * tolerant of common header aliases — including Chinese — so users can import
 * Gmail, Outlook, and native exports without renaming columns.
 *
 * <p>Unknown headers are ignored (extra columns are silently dropped), matching
 * the best-effort import contract.
 */
final class ContactHeaderResolver {
    /** Logical column identifiers. */
    enum Column { EMAIL, NICKNAME, NOTES, TAGS }

    private static final Map<Column, Set<String>> ALIASES = Map.of(
        Column.EMAIL, Set.of("email", "e-mail", "mail", "address", "邮箱", "电子邮件", "電子郵件"),
        Column.NICKNAME, Set.of("name", "nickname", "display name", "fullname", "full name", "姓名", "昵称", "暱稱", "名字"),
        Column.NOTES, Set.of("notes", "note", "comment", "comments", "remark", "备注", "注释", "備註"),
        Column.TAGS, Set.of("tags", "tag", "labels", "label", "group", "groups", "标签", "分组", "標籤", "分組"));

    /** Returns the logical column for a raw header cell, or {@code null} if unrecognized. */
    static Column resolve(String header) {
        if (header == null) return null;
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;
        for (Map.Entry<Column, Set<String>> entry : ALIASES.entrySet()) {
            if (entry.getValue().contains(normalized)) return entry.getKey();
        }
        return null;
    }

    private ContactHeaderResolver() { }
}
