package fan.summer.fengyu.plugin.email.service;

import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.Contact;
import fan.summer.fengyu.plugin.email.model.ContactImport.ImportOptions;
import fan.summer.fengyu.plugin.email.model.ContactImport.ImportPreview;
import fan.summer.fengyu.plugin.email.model.ContactImport.ImportResult;
import fan.summer.fengyu.plugin.email.model.ContactImport.ParseError;
import fan.summer.fengyu.plugin.email.model.ContactImport.ParsedContact;
import fan.summer.fengyu.plugin.email.model.Tag;
import fan.summer.fengyu.plugin.email.repository.AddressBookRepository;
import fan.summer.fengyu.sdk.PluginMessages;
import org.apache.ibatis.session.SqlSession;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates a contact batch-import: parse a file, diff it against the address
 * book (preview, no writes), and optionally apply the import atomically (commit).
 *
 * <p>Both phases are stateless — preview and commit each parse the file and load
 * existing state independently, so no in-worker state is held between the two
 * calls (the resolved {@code FileRef} path is ephemeral). Commit re-checks the
 * diff, so its counts are authoritative even if the address book changed between
 * preview and commit.
 *
 * <p>Tag auto-create: tag names in the file that don't yet exist are created at
 * commit (never at preview). Names are compared case-insensitively, so
 * re-importing the same file never creates duplicate tags. The default duplicate
 * mode is {@code merge} — additive and non-destructive (tags unioned, nickname/
 * notes filled only when the file cell is non-blank).
 */
public final class ContactImporter {
    private static final PluginMessages MSGS = PluginMessages.forClassLoader(PluginMessages.DEFAULT_BASE_NAME, ContactImporter.class);
    private final AddressBookRepository addressBook;

    public ContactImporter(EmailDatabase database) {
        this.addressBook = new AddressBookRepository(database);
    }

    /** Dry-run: computes what would happen, writes nothing. */
    public ImportPreview preview(Path file, ImportOptions options) {
        Parsed parsed = parse(file, options);
        try (SqlSession session = addressBook.openSessionForRead()) {
            Map<String, Tag> existingTagsByLower = indexExistingTags();
            Set<String> existingEmailsLower = collectExistingEmails(parsed.contacts, session);
            return buildPreview(parsed, options, existingTagsByLower.keySet(), existingEmailsLower);
        }
    }

    /** Applies the import in a single atomic transaction. */
    public ImportResult commit(Path file, ImportOptions options) {
        Parsed parsed = parse(file, options);
        List<ParseError> errors = new ArrayList<>(parsed.errors);
        try (SqlSession session = addressBook.openSession()) {
            // Snapshot pre-existing tag names (lowercased) BEFORE ensureTagsByName creates new ones,
            // so tagsCreated is computed precisely.
            Set<String> preExistingTagKeys = indexExistingTags().keySet();

            // Phase 1 — resolve/create tags so ids exist before assignments reference them.
            Set<String> distinctTags = collectDistinctTags(parsed.contacts);
            Map<String, Long> tagIdByName = addressBook.ensureTagsByName(distinctTags, session);
            int tagsCreated = countNewTags(distinctTags, preExistingTagKeys);

            // Phase 2 — upsert contacts; Phase 3 — assignments.
            int created = 0, merged = 0, skipped = 0, assigned = 0;
            for (ParsedContact contact : parsed.contacts) {
                String email = validateEmail(contact, errors);
                if (email == null) continue; // row error recorded, import continues
                Contact existing = addressBook.findContactByEmail(email, session);
                Set<Long> tagIds = resolveTagIds(contact.tags(), tagIdByName);
                if (existing == null) {
                    long id = addressBook.insertContact(new AddressBookRepository.ContactInput(
                        null, email, contact.nickname(), contact.notes()), session);
                    addressBook.replaceTags(id, tagIds, session);
                    assigned += tagIds.size();
                    created++;
                } else {
                    switch (options.duplicateMode()) {
                        case "skip" -> skipped++;
                        case "overwrite" -> {
                            addressBook.updateContact(existing.id(), email, contact.nickname(), contact.notes(), session);
                            addressBook.replaceTags(existing.id(), tagIds, session);
                            assigned += tagIds.size();
                            merged++;
                        }
                        default -> { // "merge" — additive
                            String nickname = contact.nickname() != null ? contact.nickname() : existing.nickname();
                            String notes = contact.notes() != null ? contact.notes() : existing.notes();
                            addressBook.updateContact(existing.id(), email, nickname, notes, session);
                            Set<Long> union = new LinkedHashSet<>(existing.tagIds());
                            union.addAll(tagIds);
                            addressBook.replaceTags(existing.id(), union, session);
                            assigned += union.size();
                            merged++;
                        }
                    }
                }
            }
            session.commit();
            return new ImportResult(created, merged, skipped, tagsCreated, assigned, errors);
        }
    }

    // ---- helpers ------------------------------------------------------------

    private record Parsed(List<ParsedContact> contacts, List<ParseError> errors) { }

    private Parsed parse(Path file, ImportOptions options) {
        try (ContactFileParser parser = ContactParserFactory.forFile(file, options.tagDelimiter())) {
            ContactFileParser.Result result = parser.parse(file);
            return new Parsed(result.contacts(), result.errors());
        } catch (Exception e) {
            return new Parsed(List.of(), List.of(new ParseError(0, MSGS.format("em.err.contactImportCouldNotReadFile", e.getMessage()))));
        }
    }

    private Map<String, Tag> indexExistingTags() {
        Map<String, Tag> byLower = new LinkedHashMap<>();
        for (Tag tag : addressBook.listTags()) byLower.putIfAbsent(tag.name().toLowerCase(Locale.ROOT), tag);
        return byLower;
    }

    /** One existence lookup per distinct parsed email, to keep preview cheap on large files. */
    private Set<String> collectExistingEmails(List<ParsedContact> contacts, SqlSession session) {
        Set<String> existing = new HashSet<>();
        Set<String> checked = new HashSet<>();
        for (ParsedContact c : contacts) {
            if (c.email() == null || c.email().isBlank()) continue;
            String email = c.email().trim().toLowerCase(Locale.ROOT);
            if (checked.add(email) && addressBook.findContactByEmail(email, session) != null) existing.add(email);
        }
        return existing;
    }

    private static Set<String> collectDistinctTags(List<ParsedContact> contacts) {
        Set<String> tags = new LinkedHashSet<>();
        for (ParsedContact c : contacts)
            for (String tag : c.tags()) if (tag != null && !tag.isBlank()) tags.add(tag);
        return tags;
    }

    private static ImportPreview buildPreview(Parsed parsed, ImportOptions options,
            Set<String> existingTagsLower, Set<String> existingEmailsLower) {
        List<String> createdTags = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ParsedContact contact : parsed.contacts) {
            for (String tag : contact.tags()) {
                String lower = tag.toLowerCase(Locale.ROOT);
                if (existingTagsLower.contains(lower) || !seen.add(lower)) continue;
                createdTags.add(tag); // preserve original casing for display
            }
        }
        int created = 0, merged = 0, skipped = 0;
        for (ParsedContact contact : parsed.contacts) {
            if (contact.email() == null || contact.email().isBlank()) continue;
            String email = contact.email().trim().toLowerCase(Locale.ROOT);
            if (!existingEmailsLower.contains(email)) created++;
            else if ("skip".equals(options.duplicateMode())) skipped++;
            else merged++; // merge or overwrite both touch an existing contact in the preview
        }
        int rowsValid = (int) parsed.contacts.stream()
            .filter(c -> c.email() != null && !c.email().isBlank()).count();
        int rowsTotal = parsed.contacts.size() + (int) parsed.errors.stream().filter(e -> e.row() != 0).count();
        return new ImportPreview(rowsTotal, rowsValid, created, merged, skipped, createdTags, parsed.errors());
    }

    private static int countNewTags(Set<String> distinctTags, Set<String> preExistingKeys) {
        int count = 0;
        for (String name : distinctTags) if (!preExistingKeys.contains(name.toLowerCase(Locale.ROOT))) count++;
        return count;
    }

    /** Normalizes + validates; returns the lowercase email or null with an error recorded. */
    private static String validateEmail(ParsedContact contact, List<ParseError> errors) {
        String email = contact.email();
        if (email == null || email.isBlank()) {
            errors.add(new ParseError(contact.row(), MSGS.format("em.err.contactMissingEmail")));
            return null;
        }
        email = email.trim().toLowerCase(Locale.ROOT);
        if (!email.contains("@")) {
            errors.add(new ParseError(contact.row(), MSGS.format("em.err.contactInvalidEmailValue", contact.email())));
            return null;
        }
        return email;
    }

    private static Set<Long> resolveTagIds(List<String> tagNames, Map<String, Long> tagIdByName) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String name : tagNames) {
            Long id = tagIdByName.get(name.toLowerCase(Locale.ROOT));
            if (id != null) ids.add(id);
        }
        return ids;
    }
}
