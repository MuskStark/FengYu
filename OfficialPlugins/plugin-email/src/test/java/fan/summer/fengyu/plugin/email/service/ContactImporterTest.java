package fan.summer.fengyu.plugin.email.service;

import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.ContactImport.ImportOptions;
import fan.summer.fengyu.plugin.email.model.ContactImport.ImportPreview;
import fan.summer.fengyu.plugin.email.model.ContactImport.ImportResult;
import fan.summer.fengyu.plugin.email.model.Tag;
import fan.summer.fengyu.sdk.PluginDatabaseConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContactImporterTest {
    @TempDir Path temp;

    private EmailDatabase database(String name) {
        return new EmailDatabase(new PluginDatabaseConfig("h2", "org.h2.Driver",
            "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "", temp));
    }

    private Path csv(String name, String content) throws Exception {
        Path file = temp.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    @Test void freshImportCreatesContactsAndAutoCreatesTags() throws Exception {
        EmailDatabase db = database("import-fresh");
        ContactImporter importer = new ContactImporter(db);
        // Multiple tags per row live in ONE cell, delimited (the import contract — not one column per tag).
        Path file = csv("fresh.csv",
            "email,name,tags\n" +
            "alice@example.com,Alice,Marketing|VIP\n" +
            "bob@example.com,Bob,Marketing|Sales\n");

        ImportPreview preview = importer.preview(file, new ImportOptions("merge", "auto"));
        assertEquals(2, preview.createdContacts());
        assertEquals(0, preview.mergedContacts());
        assertEquals(List.of("Marketing", "VIP", "Sales"), preview.createdTags());

        ImportResult result = importer.commit(file, new ImportOptions("merge", "auto"));
        assertEquals(2, result.created());
        assertEquals(0, result.merged());
        assertEquals(3, result.tagsCreated());

        // Tags actually persisted.
        List<String> tagNames = new AddressBookService(db).listTags().stream().map(Tag::name).toList();
        assertTrue(tagNames.containsAll(List.of("Marketing", "VIP", "Sales")));
        // Contacts actually persisted.
        assertEquals(Set.of("alice@example.com", "bob@example.com"),
            emailsOf(db, "alice@example.com"));
    }

    @Test void reImportIsIdempotentNoDuplicateTags() throws Exception {
        EmailDatabase db = database("import-idempotent");
        ContactImporter importer = new ContactImporter(db);
        Path file = csv("re.csv", "email,name,tags\namy@example.com,Amy,Team\n");

        importer.commit(file, new ImportOptions("merge", "auto"));
        ImportResult second = importer.commit(file, new ImportOptions("merge", "auto"));

        assertEquals(0, second.created(), "merge should not create a second contact");
        assertEquals(1, second.merged(), "merge should touch the existing contact");
        assertEquals(0, second.tagsCreated(), "tag must not be recreated");
        // Still exactly one tag.
        assertEquals(1, new AddressBookService(db).listTags().size());
        // Still exactly one contact.
        assertEquals(1, new AddressBookService(db).search("", Set.of(), 0, 50).size());
    }

    @Test void mergeModeIsAdditiveForTagsAndKeepsExistingNotes() throws Exception {
        EmailDatabase db = database("import-merge");
        AddressBookService addressBook = new AddressBookService(db);
        long tagId = addressBook.saveTag(null, "Existing");
        long contactId = addressBook.saveContact(new AddressBookService.ContactInput(
            null, "carol@example.com", "Carol", "Original notes"));
        addressBook.assignTags(Set.of(contactId), Set.of(tagId));

        ContactImporter importer = new ContactImporter(db);
        Path file = csv("merge.csv",
            "email,notes,tags\n" +
            "CAROL@example.com,,NewTag\n"); // blank notes → keep existing; uppercase email → still same contact

        ImportResult result = importer.commit(file, new ImportOptions("merge", "auto"));
        assertEquals(0, result.created());
        assertEquals(1, result.merged());
        assertEquals(1, result.tagsCreated(), "only NewTag should be created");

        // No new contact row.
        assertEquals(1, addressBook.search("", Set.of(), 0, 50).size());
        var carol = addressBook.findContact(contactId).orElseThrow();
        // Notes preserved (file cell blank).
        assertEquals("Original notes", carol.notes());
        // Tags unioned, not replaced.
        Set<String> tagNames = tagNamesOf(addressBook, carol);
        assertTrue(tagNames.contains("Existing"), "merge must keep existing tags: " + tagNames);
        assertTrue(tagNames.contains("NewTag"), "merge must add new tags: " + tagNames);
    }

    @Test void overwriteModeReplacesTagsAndNotes() throws Exception {
        EmailDatabase db = database("import-overwrite");
        AddressBookService addressBook = new AddressBookService(db);
        long oldTag = addressBook.saveTag(null, "Old");
        long contactId = addressBook.saveContact(new AddressBookService.ContactInput(
            null, "dave@example.com", "Dave", "Old notes"));
        addressBook.assignTags(Set.of(contactId), Set.of(oldTag));

        ContactImporter importer = new ContactImporter(db);
        Path file = csv("ow.csv",
            "email,name,notes,tags\n" +
            "dave@example.com,David,New notes,Fresh\n");

        ImportResult result = importer.commit(file, new ImportOptions("overwrite", "auto"));
        assertEquals(0, result.created());
        assertEquals(1, result.merged());

        var dave = addressBook.findContact(contactId).orElseThrow();
        assertEquals("David", dave.nickname());
        assertEquals("New notes", dave.notes());
        assertEquals(Set.of("Fresh"), tagNamesOf(addressBook, dave), "overwrite must replace tags");
    }

    @Test void skipModeLeavesExistingContactsUntouched() throws Exception {
        EmailDatabase db = database("import-skip");
        AddressBookService addressBook = new AddressBookService(db);
        long tag = addressBook.saveTag(null, "Kept");
        long contactId = addressBook.saveContact(new AddressBookService.ContactInput(
            null, "eve@example.com", "Eve", "Keep me"));
        addressBook.assignTags(Set.of(contactId), Set.of(tag));

        ContactImporter importer = new ContactImporter(db);
        Path file = csv("skip.csv",
            "email,name,notes,tags\n" +
            "eve@example.com,Eve-Changed,Changed notes,NewTag\n" +
            "frank@example.com,Frank,New\n");

        ImportResult result = importer.commit(file, new ImportOptions("skip", "auto"));
        assertEquals(1, result.created(), "frank should be created");
        assertEquals(1, result.skipped(), "eve should be skipped");

        var eve = addressBook.findContact(contactId).orElseThrow();
        assertEquals("Eve", eve.nickname(), "skip must not change nickname");
        assertEquals("Keep me", eve.notes(), "skip must not change notes");
        assertEquals(Set.of("Kept"), tagNamesOf(addressBook, eve), "skip must not change tags");
    }

    @Test void invalidEmailsAreRowErrorsButImportContinues() throws Exception {
        EmailDatabase db = database("import-errors");
        ContactImporter importer = new ContactImporter(db);
        Path file = csv("err.csv",
            "email,name\n" +
            "good@example.com,Good\n" +
            "not-an-email,Bad\n" +
            "also@example.com,Also\n");

        ImportResult result = importer.commit(file, new ImportOptions("merge", "auto"));
        assertEquals(2, result.created());
        assertEquals(1, result.errors().size(), "the bad row should be a recorded error");
        assertTrue(result.errors().getFirst().message().toLowerCase().contains("email"));
    }

    @Test void fileWithoutTagsColumnIsPlainContactImport() throws Exception {
        EmailDatabase db = database("import-notags");
        ContactImporter importer = new ContactImporter(db);
        Path file = csv("notags.csv", "email,name\nx@x.com,X\n");

        ImportPreview preview = importer.preview(file, new ImportOptions("merge", "auto"));
        assertTrue(preview.createdTags().isEmpty(), "no tags column → no tags created");

        ImportResult result = importer.commit(file, new ImportOptions("merge", "auto"));
        assertEquals(1, result.created());
        assertEquals(0, result.tagsCreated());
        assertEquals(0, result.tagsAssigned());
    }

    private static Set<String> tagNamesOf(AddressBookService addressBook, fan.summer.fengyu.plugin.email.model.Contact contact) {
        var byId = new java.util.HashMap<Long, String>();
        for (Tag t : addressBook.listTags()) byId.put(t.id(), t.name());
        Set<String> names = new java.util.HashSet<>();
        for (long id : contact.tagIds()) names.add(byId.get(id));
        return names;
    }

    /** Returns the distinct lowercased emails currently stored (sanity check). */
    private static Set<String> emailsOf(EmailDatabase db, String sample) {
        AddressBookService service = new AddressBookService(db);
        Set<String> emails = new java.util.HashSet<>();
        for (var c : service.search("", Set.of(), 0, 100)) emails.add(c.email());
        return emails;
    }
}
