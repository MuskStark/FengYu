package fan.summer.fengyu.plugin.email.repository;

import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.sdk.PluginDatabaseConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AddressBookRepositoryTest {
    @TempDir Path temp;

    @Test
    void intersectsAttachmentTagWithAnySelectedGroupTag() {
        EmailDatabase database = new EmailDatabase(new PluginDatabaseConfig("h2", "org.h2.Driver",
            "jdbc:h2:mem:tag-intersection;DB_CLOSE_DELAY=-1", "sa", "", temp));
        AddressBookRepository repository = new AddressBookRepository(database);
        long east = repository.saveTag(null, "East");
        long south = repository.saveTag(null, "South");
        long customer = repository.saveTag(null, "Customer");
        long manager = repository.saveTag(null, "Manager");
        long alice = repository.saveContact(new AddressBookRepository.ContactInput(null, "alice@example.com", "Alice", null));
        long bob = repository.saveContact(new AddressBookRepository.ContactInput(null, "bob@example.com", "Bob", null));
        long carol = repository.saveContact(new AddressBookRepository.ContactInput(null, "carol@example.com", "Carol", null));
        long dana = repository.saveContact(new AddressBookRepository.ContactInput(null, "dana@example.com", "Dana", null));
        repository.assignTags(Set.of(alice), Set.of(east, customer));
        repository.assignTags(Set.of(bob), Set.of(east, customer, manager));
        repository.assignTags(Set.of(carol), Set.of(east, manager));
        repository.assignTags(Set.of(dana), Set.of(south, customer));

        assertEquals(Set.of("alice@example.com", "bob@example.com"),
            repository.resolveEmailsForAttachmentTag("East", Set.of(customer)));
        assertEquals(Set.of("bob@example.com", "carol@example.com"),
            repository.resolveEmailsForAttachmentTag("East", Set.of(manager)));
        assertEquals(Set.of("dana@example.com"),
            repository.resolveEmailsForAttachmentTag("South", Set.of(customer)));
        assertEquals(Set.of("alice@example.com", "bob@example.com"),
            repository.resolveEmailsForAttachmentTag("east", Set.of(customer)));
        assertEquals(Set.of(), repository.resolveEmailsForAttachmentTag("Missing", Set.of(customer)));
        assertEquals(Set.of(), repository.resolveEmailsForAttachmentTag("East", Set.of()));
    }

    @Test
    void persistsAndReadsBackContactNotes() {
        EmailDatabase database = new EmailDatabase(new PluginDatabaseConfig("h2", "org.h2.Driver",
            "jdbc:h2:mem:contact-notes;DB_CLOSE_DELAY=-1", "sa", "", temp));
        AddressBookRepository repository = new AddressBookRepository(database);

        long withNotes = repository.saveContact(new AddressBookRepository.ContactInput(
            null, "alice@example.com", "Alice", "VIP since 2024"));
        long withoutNotes = repository.saveContact(new AddressBookRepository.ContactInput(
            null, "bob@example.com", "Bob", "  "));

        assertEquals("VIP since 2024", repository.findContact(withNotes).orElseThrow().notes());
        assertNull(repository.findContact(withoutNotes).orElseThrow().notes());
    }
}
